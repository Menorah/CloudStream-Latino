package com.menorah.repelis24

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class RepelisProvider : MainAPI() {
    override var mainUrl = "https://repelis24.cz"
    override var name = "RePelis24.cz"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("/peliculas/" to "Películas", "/series/" to "Series")

    private suspend fun fetch(url: String): Document {
        val absolute = URI("$mainUrl/").resolve(url).toString()
        require(RepelisMarkup.siteUrl(absolute)) { "Dirección fuera de RePelis24" }
        val res = app.get(absolute, timeout = 15)
        if (!res.isSuccessful || !RepelisMarkup.siteUrl(res.url)) throw IllegalStateException("RePelis24 no respondió correctamente (HTTP ${res.code}).")
        return Jsoup.parse(res.text, res.url)
    }
    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { c ->
        if (c.movie) newMovieSearchResponse(c.title, c.url, TvType.Movie) { posterUrl = c.image }
        else newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) { posterUrl = c.image }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/') + if (page > 1) "/page/$page/" else "/"
        val doc = fetch(path)
        return newHomePageResponse(HomePageList(request.name, responses(RepelisMarkup.cards(doc))), hasNext = RepelisMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return responses(RepelisMarkup.cards(fetch("/?s=${URLEncoder.encode(query.trim(), "UTF-8")}")))
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val path = if (page > 1) "/page/$page/" else "/"
        val doc = fetch("$path?s=${URLEncoder.encode(query.trim(), "UTF-8")}")
        return newSearchResponseList(responses(RepelisMarkup.cards(doc)), hasNext = RepelisMarkup.hasNext(doc, page))
    }
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val title = RepelisMarkup.title(doc) ?: return null
        val poster = RepelisMarkup.poster(doc)
        val plotText = RepelisMarkup.plot(doc)
        val backdrop = RepelisMarkup.backdrop(doc)
        if (URI(url).path.orEmpty().startsWith("/peliculas/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; backgroundPosterUrl = backdrop; plot = plotText }
        }
        val episodes = RepelisMarkup.episodes(doc).map { item ->
            newEpisode(RepelisMarkup.episodeUrl(item.id)) { name = item.title; season = item.season; episode = item.number; posterUrl = item.image }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; backgroundPosterUrl = backdrop; plot = plotText }
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = fetch(data)
        val options = RepelisMarkup.options(doc).ifEmpty { RepelisMarkup.fallbackEpisodeOptions(data) }.take(24)
        val emitted = AtomicBoolean(false)
        val lock = Any()
        withTimeoutOrNull(45_000L) {
            coroutineScope {
                val permits = Semaphore(5)
                options.map { opt -> async { permits.withPermit {
                    withTimeoutOrNull(12_000L) {
                        val body = attempt { app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to "doo_player_ajax", "post" to opt.post, "nume" to opt.number, "type" to opt.type), headers = mapOf("Referer" to doc.location(), "X-Requested-With" to "XMLHttpRequest"), timeout = 8).text } ?: return@withTimeoutOrNull
                        val embed = runCatching { JSONObject(body).optString("embed_url") }.getOrNull().orEmpty()
                        val uri = runCatching { URI(embed) }.getOrNull()
                        if (uri?.scheme !in listOf("http", "https") || uri?.host.isNullOrBlank() || uri?.userInfo != null) return@withTimeoutOrNull
                        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        attempt { loadExtractor(embed, doc.location(), subtitleCallback) { links.add(it) } }
                        for (link in synchronized(links) { links.toList() }) {
                            val labelled = label(link, opt.language)
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
