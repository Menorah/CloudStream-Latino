package com.menorah.animeonline

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class AnimeOnlineProvider : MainAPI() {
    override var mainUrl = "https://ww4.animeonline.ninja"
    override var name = "AnimeOnline Ninja"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val site = SiteClient()

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

    private fun document(response: NiceResponse): Document = Jsoup.parse(response.text, response.url)

    /** Requests the catalogue host directly. ver2 is an entry page, not a mirror. */
    private suspend fun fetch(path: String): NiceResponse {
        val relative = sitePath(path)
        val url = if (relative.startsWith("http")) relative else mainUrl.trimEnd('/') + relative
        var response = site.get(url)
        if (!response.isSuccessful) throw IllegalStateException("AnimeOnline no respondió (HTTP ${response.code}). Comprueba el acceso al sitio en el navegador.")
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

    private fun cards(document: Document): List<SearchResponse> = SiteMarkup.cards(document).map { card ->
        val type = if (card.movie) TvType.AnimeMovie else TvType.Anime
        newTvSeriesSearchResponse(card.title, card.url, type) { posterUrl = card.poster }
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
        val episodes = SiteMarkup.episodes(doc).map { item ->
            newEpisode(item.url) { name = item.title; season = item.season; episode = item.number; posterUrl = item.poster }
        }
        if (sitePath(url).startsWith("/pelicula/")) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster; this.plot = plot; this.tags = tags
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster; this.plot = plot; this.tags = tags
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
        var emitted = false
        val emit: (ExtractorLink) -> Unit = { emitted = true; callback(it) }
        // Stop before CloudStream's default 120-second timeout, even if several hosts are slow.
        withTimeoutOrNull(75_000L) {
            val page = fetch(data)
            val pageUrl = page.url
            val origin = origin(pageUrl) ?: mainUrl
            val doc = document(page)
            val visited = mutableSetOf<String>()
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
        return emitted
    }

    private suspend fun resolve(input: String, referer: String, subtitles: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, visited: MutableSet<String>, depth: Int) {
        if (depth > 3 || input.isBlank()) return
        val cleaned = if (input.contains("<iframe")) Jsoup.parse(input, referer).selectFirst("iframe[src]")?.absUrl("src").orEmpty() else input.trim()
        val url = runCatching { URI(referer).resolve(cleaned).toString() }.getOrNull() ?: return
        if (!(url.startsWith("https://") || url.startsWith("http://")) || !visited.add(url)) return
        val path = URI(url).path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
            val type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, type) { this.referer = referer })
            return
        }
        var gotLink = false
        withTimeoutOrNull(10_000L) {
            attempt { loadExtractor(url, referer, subtitles) { gotLink = true; callback(it) } }
        }
        if (gotLink) return
        val response = attempt { app.get(url, headers = mapOf("Referer" to referer), timeout = SiteClient.REQUEST_SECONDS) } ?: return
        if (!response.isSuccessful) return
        val doc = document(response)
        for (server in SiteMarkup.playerServers(doc).take(12)) {
            // Bound each external host separately as well as the overall loadLinks call.
            withTimeoutOrNull(8_000L) {
                resolve(server.url, response.url, subtitles, callback, visited, depth + 1)
            }
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
                val items = langs.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    if (count++ >= 12) break
                    val encoded = items.optJSONObject(j)?.optString("url").orEmpty()
                    val decoded = runCatching {
                        val bytes = Base64.decode(encoded.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
                        String(ByteArray(bytes.size) { n -> (bytes[n].toInt() xor key[n % key.length].code).toByte() }, Charsets.UTF_8)
                    }.getOrNull().orEmpty()
                    resolve(decoded, response.url, subtitles, callback, visited, depth + 1)
                }
            }
        }
        for (frame in doc.select("iframe[src]").take(8)) resolve(frame.absUrl("src"), response.url, subtitles, callback, visited, depth + 1)
    }

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme == null || uri.host == null) null else "${uri.scheme}://${uri.authority}"
    }.getOrNull()
}
