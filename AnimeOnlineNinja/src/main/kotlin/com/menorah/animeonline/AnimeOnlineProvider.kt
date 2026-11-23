package com.menorah.animeonline

import android.util.Base64
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AnimeOnlineProvider(context: Context) : MainAPI() {
    override var mainUrl = "https://ww4.animeonline.ninja"
    override var name = "AnimeOnline Ninja"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val site = SiteClient.create(context)
    private val posters = PosterCache.create(context)

    override val mainPage = mainPageOf(
        "/inicio/" to "Inicio",
        "/online/" to "Animes",
        "/pelicula/" to "Películas",
        "/genero/audio-latino/" to "Audio latino",
        "/genero/anime-castellano/" to "Castellano"
    )

    private fun sitePath(url: String): String {
        if (!url.startsWith("http")) return if (url.startsWith("/")) url else "/$url"
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host.orEmpty().lowercase()
        if (host != "animeonline.ninja" && !host.endsWith(".animeonline.ninja")) return url
        return (uri.rawPath?.ifBlank { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun document(response: SiteResponse): Document = Jsoup.parse(response.text, response.url)
    private fun document(response: NiceResponse): Document = Jsoup.parse(response.text, response.url)

    /** Requests the catalogue host directly. ver2 is an entry page, not a mirror. */
    private suspend fun fetch(path: String): SiteResponse {
        val relative = sitePath(path)
        val url = if (relative.startsWith("http")) relative else mainUrl.trimEnd('/') + relative
        var response = site.get(url)
        if (!response.isSuccessful) throw IllegalStateException("AnimeOnline rechazó el acceso (HTTP ${response.code}). Comprueba el sitio en el navegador del dispositivo.")
        val entry = SiteMarkup.landingEntry(document(response), response.url)
        if (entry != null) {
            // The landing button leads to /inicio/. Visit it before retrying the requested path.
            val home = site.get(entry, headers = mapOf("Referer" to response.url))
            if (!home.isSuccessful || SiteMarkup.landingEntry(document(home), home.url) != null)
                throw IllegalStateException("AnimeOnline redirige a su portada y no permite entrar en /inicio/.")
            if (url.trimEnd('/') == entry.trimEnd('/')) return home
            response = site.get(url, headers = mapOf("Referer" to entry))
            if (!response.isSuccessful || SiteMarkup.landingEntry(document(response), response.url) != null)
                throw IllegalStateException("AnimeOnline redirige a su portada en lugar del catálogo solicitado.")
        }
        return response
    }

    private suspend fun cards(document: Document): List<SearchResponse> {
        val items = SiteMarkup.cards(document)
        val cached = posters.resolve(items.map { it.poster })
        return items.map { card ->
            val type = if (card.movie) TvType.AnimeMovie else TvType.Anime
            newTvSeriesSearchResponse(card.title, card.url, type) {
                posterUrl = cached[card.poster] ?: card.poster
                posterHeaders = card.poster?.takeIf(PosterCache::isProtectedPoster)?.let { mapOf("Referer" to "$mainUrl/inicio/") }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "/inicio/" && page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        val path = request.data.trimEnd('/') + if (page > 1) "/page/$page/" else "/"
        val doc = document(fetch(path))
        val next = request.data != "/inicio/" && doc.selectFirst(".pagination a[href*='/page/${page + 1}/'], .pagination a.next, a.next.page-numbers, link[rel=next]") != null
        return newHomePageResponse(HomePageList(request.name, cards(doc)), hasNext = next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return cards(document(fetch("/search/?s=${URLEncoder.encode(query.trim(), "UTF-8")}")))
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = document(fetch(url))
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster = SiteMarkup.image(doc.selectFirst(".poster img"))
        val plot = doc.selectFirst("#info .wp-content, .wp-content, .sinopsis")?.text()?.trim()
        val tags = doc.select(".sgeneros a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val episodeData = SiteMarkup.episodes(doc)
        val cached = posters.resolve(listOf(poster) + episodeData.map { it.poster })
        val episodes = episodeData.map { item ->
            newEpisode(item.url) { name = item.title; season = item.season; episode = item.number; posterUrl = cached[item.poster] ?: item.poster }
        }
        val displayPoster = cached[poster] ?: poster
        val displayHeaders = poster?.takeIf(PosterCache::isProtectedPoster)?.let { mapOf("Referer" to "$mainUrl/inicio/") }
        if (sitePath(url).startsWith("/pelicula/")) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = displayPoster; posterHeaders = displayHeaders; this.plot = plot; this.tags = tags
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = displayPoster; posterHeaders = displayHeaders; this.plot = plot; this.tags = tags
        }
    }

    /** Do not swallow cancellation: CloudStream can stop a request when the user leaves. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val emitted = AtomicBoolean(false)
        val callbackLock = Any()
        val emit: (ExtractorLink) -> Unit = { link ->
            synchronized(callbackLock) { emitted.set(true); callback(link) }
        }
        // Stop before CloudStream's default 120-second timeout, even if several hosts are slow.
        withTimeoutOrNull(75_000L) {
            val page = fetch(data)
            val pageUrl = page.url
            val origin = origin(pageUrl) ?: mainUrl
            val doc = document(page)
            val visited = ConcurrentHashMap.newKeySet<String>()
            for (option in doc.select("#playeroptionsul li[data-post][data-nume]").take(8)) {
                val post = option.attr("data-post")
                val nume = option.attr("data-nume")
                if (post.isBlank() || nume.isBlank()) continue
                val type = option.attr("data-type").ifBlank { if (sitePath(data).startsWith("/pelicula/")) "movie" else "tv" }
                // The current site exposes the player through this GET endpoint.
                val restUrl = "$origin/wp-json/dooplayer/v1/post/${URLEncoder.encode(post, "UTF-8")}?type=${URLEncoder.encode(type, "UTF-8")}&source=${URLEncoder.encode(nume, "UTF-8")}" 
                val rest = attempt { site.get(restUrl, mapOf("Referer" to pageUrl)).text }
                var embed = rest?.let { runCatching { JSONObject(it).optString("embed_url") }.getOrNull() }.orEmpty()
                if (embed.isBlank()) {
                    val ajax = attempt {
                        app.post("$origin/wp-admin/admin-ajax.php", data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type), headers = mapOf("Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest"), timeout = SiteClient.REQUEST_SECONDS).text
                    }
                    embed = ajax?.let { runCatching { JSONObject(it).optString("embed_url") }.getOrNull() }.orEmpty()
                }
                resolve(embed, pageUrl, subtitleCallback, emit, visited, 0)
            }
            for (frame in doc.select("iframe[src]").take(8)) resolve(frame.absUrl("src"), pageUrl, subtitleCallback, emit, visited, 0)
        }
        return emitted.get()
    }

    private suspend fun resolve(input: String, referer: String, subtitles: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, visited: MutableSet<String>, depth: Int, language: String? = null) {
        if (depth > 3 || input.isBlank()) return
        val cleaned = if (input.contains("<iframe")) Jsoup.parse(input, referer).selectFirst("iframe[src]")?.absUrl("src").orEmpty() else input.trim()
        val url = runCatching { URI(referer).resolve(cleaned).toString() }.getOrNull() ?: return
        if (!(url.startsWith("https://") || url.startsWith("http://")) || !visited.add("${language.orEmpty()}|$url")) return
        val path = URI(url).path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
            val type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, sourceLabel(language, name), url, type) { this.referer = referer })
            return
        }
        // Buffer callbacks briefly so a suspendable builder can preserve DRM and all
        // extractor metadata while adding the actual language to the visible name.
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        withTimeoutOrNull(7_000L) {
            attempt { loadExtractor(url, referer, subtitles) { found.add(it) } }
        }
        val links = synchronized(found) { found.toList() }
        for (link in links) callback(withLanguage(link, language))
        if (links.isNotEmpty()) return
        val response = attempt { app.get(url, headers = mapOf("Referer" to referer), timeout = SiteClient.REQUEST_SECONDS) } ?: return
        if (!response.isSuccessful) return
        val doc = document(response)
        // Several providers can be slow or offline. Resolve a few at a time so one
        // dead host cannot consume the whole playback budget or starve a language.
        val servers = SiteMarkup.playerServers(doc).take(24)
        if (servers.isNotEmpty()) coroutineScope {
            val permits = Semaphore(4)
            servers.map { server ->
                async {
                    permits.withPermit {
                        withTimeoutOrNull(8_000L) {
                            resolve(server.url, response.url, subtitles, callback, visited, depth + 1, server.language)
                        }
                    }
                }
            }.awaitAll()
        }
        val raw = doc.selectFirst("script#msp-data")?.data()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#msp[data-api]")?.absUrl("data-api")?.takeIf { it.isNotBlank() }?.let { api ->
                attempt { app.get(api, headers = mapOf("Referer" to response.url), timeout = SiteClient.REQUEST_SECONDS).text }
            }
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val key = json?.optString("pk").orEmpty()
        val langs = json?.optJSONArray("langs")
        if (key.isNotEmpty() && langs != null) {
            var count = 0
            for (i in 0 until langs.length()) {
                val group = langs.optJSONObject(i) ?: continue
                val items = group.optJSONArray("items") ?: continue
                val groupLanguage = languageLabel(group.optString("name").ifBlank { group.optString("label").ifBlank { group.optString("lang").ifBlank { group.optString("code") } } }) ?: language
                for (j in 0 until items.length()) {
                    if (count++ >= 12) break
                    val encoded = items.optJSONObject(j)?.optString("url").orEmpty()
                    val decoded = runCatching {
                        val bytes = Base64.decode(encoded.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
                        String(ByteArray(bytes.size) { n -> (bytes[n].toInt() xor key[n % key.length].code).toByte() }, Charsets.UTF_8)
                    }.getOrNull().orEmpty()
                    resolve(decoded, response.url, subtitles, callback, visited, depth + 1, groupLanguage)
                }
            }
        }
        for (frame in doc.select("iframe[src]").take(8)) resolve(frame.absUrl("src"), response.url, subtitles, callback, visited, depth + 1, language)
    }

    private suspend fun withLanguage(link: ExtractorLink, language: String?): ExtractorLink {
        if (language == null) return link
        val label = sourceLabel(language, link.name)
        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, label, link.url, link.type, link.uuid) {
                referer = link.referer; quality = link.quality; headers = link.headers
                extractorData = link.extractorData; audioTracks = link.audioTracks
                kid = link.kid; key = link.key; kty = link.kty
                keyRequestParameters = link.keyRequestParameters; licenseUrl = link.licenseUrl
            }
        }
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers
            extractorData = link.extractorData; audioTracks = link.audioTracks
        }
    }

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme == null || uri.host == null) null else "${uri.scheme}://${uri.authority}"
    }.getOrNull()
}
