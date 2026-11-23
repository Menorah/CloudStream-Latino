package com.menorah.poseidon

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class PoseidonProvider : MainAPI() {
    override var mainUrl = "https://www.poseidonhd2.co"
    override var name = "PoseidonHD 2"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("/peliculas" to "Películas", "/series" to "Series")

    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(PoseidonMarkup.siteUrl(url)) { "Dirección fuera de PoseidonHD 2" }
        val res = app.get(url, timeout = 15)
        if (!res.isSuccessful || !PoseidonMarkup.siteUrl(res.url)) throw IllegalStateException("PoseidonHD 2 no respondió correctamente (HTTP ${res.code}).")
        return Jsoup.parse(res.text, res.url)
    }
    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { item ->
        if (item.movie) newMovieSearchResponse(item.title, item.url, TvType.Movie) { posterUrl = item.poster }
        else newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) { posterUrl = item.poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(request.data + if (page > 1) "/page/$page" else "")
        return newHomePageResponse(HomePageList(request.name, responses(PoseidonMarkup.cards(doc))), hasNext = PoseidonMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return responses(PoseidonMarkup.cards(fetch("/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}")))
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val state = PoseidonMarkup.state(doc) ?: return null
        val movie = URI(url).path.orEmpty().startsWith("/pelicula/")
        val media = PoseidonMarkup.media(state, movie)
        val title = media.path("titles").path("name").asText("").takeIf { it.isNotBlank() } ?: return null
        val poster = media.path("images").path("poster").asText("").takeIf { it.startsWith("https://") }
        val background = media.path("images").path("backdrop").asText("").takeIf { it.startsWith("https://") }
        val description = media.path("overview").asText("").takeIf { it.isNotBlank() }
        if (movie) return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; backgroundPosterUrl = background; plot = description }
        val all = PoseidonMarkup.episodes(media).toMutableList()
        // The series page can be stale and contain empty seasons even though
        // episode pages have a newer complete seasons list. Consult episode 1
        // of each empty season, but never invent episode counts.
        val missing = PoseidonMarkup.missingSeasons(media)
        val seriesUrl = URI(doc.location()).let { "${it.scheme}://${it.authority}${it.path.trimEnd('/')}" }
        coroutineScope {
            val permits = Semaphore(4)
            missing.map { season -> async { permits.withPermit {
                withTimeoutOrNull(15_000L) {
                    val detail = attempt { fetch("$seriesUrl/temporada/$season/episodio/1") } ?: return@withTimeoutOrNull emptyList<EpisodeItem>()
                    val source = PoseidonMarkup.state(detail)?.path("serie") ?: return@withTimeoutOrNull emptyList<EpisodeItem>()
                    PoseidonMarkup.episodes(source).filter { it.season == season }
                } ?: emptyList()
            } } }.awaitAll().forEach { all.addAll(it) }
        }
        val episodes = all.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number })).map { ep ->
            newEpisode("$seriesUrl/temporada/${ep.season}/episodio/${ep.number}") { name = ep.name; season = ep.season; episode = ep.number; posterUrl = ep.poster }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; backgroundPosterUrl = background; plot = description }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = fetch(data)
        val players = PoseidonMarkup.state(doc)?.let(PoseidonMarkup::players).orEmpty().take(24)
        val emitted = AtomicBoolean(false); val lock = Any()
        withTimeoutOrNull(45_000L) {
            coroutineScope {
                val permits = Semaphore(5)
                players.map { player -> async { permits.withPermit {
                    withTimeoutOrNull(12_000L) {
                        val response = attempt { app.get(player.url, headers = mapOf("Referer" to doc.location()), timeout = 8) } ?: return@withTimeoutOrNull
                        if (!response.isSuccessful || !PoseidonMarkup.wrapperUrl(response.url)) return@withTimeoutOrNull
                        val target = PoseidonMarkup.destination(response.text) ?: return@withTimeoutOrNull
                        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        attempt { loadExtractor(target, player.url, subtitleCallback) { links.add(it) } }
                        for (link in synchronized(links) { links.toList() }) {
                            val labelled = label(link, player.language)
                            synchronized(lock) { emitted.set(true); callback(labelled) }
                        }
                    }
                } } }.awaitAll()
            }
        }
        return emitted.get()
    }
    private suspend fun label(link: ExtractorLink, language: String): ExtractorLink {
        val label = "[$language] ${link.name}"
        if (link is DrmExtractorLink) return newDrmExtractorLink(link.source, label, link.url, link.type, link.uuid) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
            kid = link.kid; key = link.key; kty = link.kty; keyRequestParameters = link.keyRequestParameters; licenseUrl = link.licenseUrl
        }
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
        }
    }
}
