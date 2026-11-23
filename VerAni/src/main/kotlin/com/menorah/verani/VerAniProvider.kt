package com.menorah.verani

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class VerAniProvider(context: Context) : MainAPI() {
    override var mainUrl = "https://verani.me"
    override var name = "VerAni.me"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val mainPage = mainPageOf("/animes/" to "Animes", "/peliculas/" to "Películas")
    private val site = SiteClient.create(context)
    private val posters = PosterCache.create(context)

    private suspend fun fetch(url: String): Document {
        val target = URI("$mainUrl/").resolve(url).toString()
        require(isSiteUrl(target)) { "URL fuera del sitio VerAni" }
        val response = site.get(target)
        if (!response.isSuccessful) throw IllegalStateException("VerAni rechazó el acceso (HTTP ${response.code}).")
        return Jsoup.parse(response.text, response.url)
    }

    private suspend fun cards(doc: Document): List<SearchResponse> {
        val parsed = VerAniMarkup.cards(doc)
        val cached = posters.resolve(parsed.map { it.poster })
        return parsed.map { item ->
            val poster = cached[item.poster] ?: item.poster
            val headers = item.poster?.takeIf(PosterCache::isProtectedPoster)?.let { mapOf("Referer" to "$mainUrl/") }
            if (item.movie) newMovieSearchResponse(item.title, item.url, TvType.AnimeMovie) { posterUrl = poster; posterHeaders = headers }
            else newTvSeriesSearchResponse(item.title, item.url, TvType.Anime) { posterUrl = poster; posterHeaders = headers }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/') + if (page > 1) "/page/$page/" else "/"
        val doc = fetch(path)
        return newHomePageResponse(HomePageList(request.name, cards(doc)), hasNext = VerAniMarkup.hasNext(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return cards(fetch("/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"))
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val path = if (page > 1) "/page/$page/" else "/"
        val doc = fetch("$path?s=${URLEncoder.encode(query.trim(), "UTF-8")}")
        return newSearchResponseList(cards(doc), hasNext = VerAniMarkup.hasNext(doc))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val title = doc.selectFirst("h1")?.text()?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster = VerAniMarkup.poster(doc)
        val plot = VerAniMarkup.plot(doc)
        val episodes = VerAniMarkup.episodes(doc)
        val cached = posters.resolve(listOf(poster) + episodes.map { it.poster })
        val display = cached[poster] ?: poster
        val headers = poster?.takeIf(PosterCache::isProtectedPoster)?.let { mapOf("Referer" to "$mainUrl/") }
        val movie = URI(url).path.orEmpty().contains("/pelicula/")
        if (movie) return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) { posterUrl = display; posterHeaders = headers; this.plot = plot }
        if (episodes.isEmpty()) return null
        val list = episodes.map { ep -> newEpisode(ep.url) { name = ep.title; season = ep.season; episode = ep.number; posterUrl = cached[ep.poster] ?: ep.poster } }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, list) { posterUrl = display; posterHeaders = headers; this.plot = plot }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val sent = AtomicBoolean(false)
        val lock = Any()
        val doc = fetch(data)
        val servers = VerAniMarkup.servers(doc).take(24)
        withTimeoutOrNull(40_000L) {
            coroutineScope {
                val permits = Semaphore(5)
                servers.map { server -> async {
                    permits.withPermit {
                        withTimeoutOrNull(10_000L) {
                            val links = resolve(server.url, doc.location(), subtitleCallback, 0)
                            for (link in links) {
                                val labelled = withLanguage(link, server.language)
                                synchronized(lock) { sent.set(true); callback(labelled) }
                            }
                        }
                    }
                } }.awaitAll()
            }
        }
        return sent.get()
    }

    private suspend fun resolve(url: String, referer: String, subtitles: (SubtitleFile) -> Unit, depth: Int): List<ExtractorLink> {
        if (depth > 2) return emptyList()
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return emptyList()
        val path = uri.path.orEmpty().lowercase()
        // This provider's HLS endpoints have no extension; verify the manifest,
        // not merely the hostname, before handing it to the player.
        val hlsCandidate = path.endsWith(".m3u8") || (uri.host.equals("player.zilla-networks.com", true) && path.startsWith("/m3u8/"))
        if (hlsCandidate) {
            val response = attempt { app.get(url, headers = mapOf("Referer" to referer), timeout = 7) } ?: return emptyList()
            if (!response.isSuccessful || !response.text.trimStart().startsWith("#EXTM3U")) return emptyList()
            return listOf(newExtractorLink(name, "HLS", url, ExtractorLinkType.M3U8) { this.referer = referer })
        }
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        withTimeoutOrNull(7_000L) { attempt { loadExtractor(url, referer, subtitles) { found.add(it) } } }
        val links = synchronized(found) { found.toList() }
        if (links.isNotEmpty()) return links
        val response = attempt { app.get(url, headers = mapOf("Referer" to referer), timeout = 6) } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        val doc = Jsoup.parse(response.text, response.url)
        val frames = doc.select("iframe[src]").map { it.absUrl("src") }.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct().take(3)
        val nested = mutableListOf<ExtractorLink>()
        for (frame in frames) nested += resolve(frame, response.url, subtitles, depth + 1)
        return nested
    }

    private suspend fun withLanguage(link: ExtractorLink, language: String): ExtractorLink {
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
