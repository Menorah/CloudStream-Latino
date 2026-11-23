package com.menorah.animeonline

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Proveedor DooPlay. Las fuentes y su disponibilidad dependen del sitio y de cada host. */
class AnimeOnlineProvider : MainAPI() {
    override var mainUrl = "https://ww4.animeonline.ninja"
    override var name = "AnimeOnline Ninja"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val cf by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
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

    private suspend fun fetch(path: String): NiceResponse {
        val relative = sitePath(path)
        if (relative.startsWith("http")) return app.get(relative, interceptor = cf)
        var last: Exception? = null
        for (domain in listOf(mainUrl.trimEnd('/'), "https://ww4.animeonline.ninja", "https://ww3.animeonline.ninja", "https://ver2.animeonline.ninja").distinct()) {
            try {
                val response = app.get(domain + relative, interceptor = cf)
                if (response.isSuccessful) return response
                last = IllegalStateException("HTTP ${response.code} en $domain")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No se pudo acceder al sitio")
    }

    private fun image(element: Element?): String? {
        if (element == null) return null
        return listOf("data-src", "data-lazy-src", "src").map { element.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrlNull(it) }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("article.item, .result-item").mapNotNull { card ->
            val anchor = card.selectFirst(".data h3 a[href], .details .title a[href], h3.title a[href], .image a[href], .poster a[href]")
                ?: return@mapNotNull null
            val url = fixUrl(anchor.attr("href"))
            val type = when {
                url.contains("/pelicula/") -> TvType.AnimeMovie
                url.contains("/online/") -> TvType.Anime
                else -> return@mapNotNull null
            }
            val title = anchor.text().trim().ifBlank { card.selectFirst("img[alt]")?.attr("alt").orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, url, type) {
                posterUrl = image(card.selectFirst("img"))
            }
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/') + if (page > 1) "/page/$page/" else "/"
        val response = fetch(path)
        val next = response.document.selectFirst(".pagination a[href*='/page/${page + 1}/'], .pagination a.next, a.next.page-numbers, link[rel=next]") != null
        return newHomePageResponse(HomePageList(request.name, cards(response.document)), hasNext = next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return cards(fetch("/search/?s=$encoded").document)
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val response = fetch(url)
        val doc = response.document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster = image(doc.selectFirst(".poster img"))
        val plot = doc.selectFirst("#info .wp-content, .wp-content, .sinopsis")?.text()?.trim()
        val tags = doc.select(".sgeneros a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val episodes = doc.select("#seasons ul.episodios li").mapIndexedNotNull { index, item ->
            val link = item.selectFirst(".episodiotitle a[href]") ?: return@mapIndexedNotNull null
            val numbers = Regex("(\\d+)\\s*[-x]\\s*(\\d+)").find(item.selectFirst(".numerando")?.text().orEmpty())
            newEpisode(fixUrl(link.attr("href"))) {
                name = link.text().trim().ifBlank { "Episodio ${index + 1}" }
                season = numbers?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episode = numbers?.groupValues?.get(2)?.toIntOrNull() ?: index + 1
                posterUrl = image(item.selectFirst("img"))
            }
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val page = fetch(data)
        val pageUrl = page.url ?: data
        val origin = origin(pageUrl) ?: mainUrl
        var emitted = false
        val emit: (ExtractorLink) -> Unit = { emitted = true; callback(it) }
        val visited = mutableSetOf<String>()
        for (option in page.document.select("#playeroptionsul li[data-post][data-nume]")) {
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            if (post.isBlank() || nume.isBlank()) continue
            val type = option.attr("data-type").ifBlank { if (sitePath(data).startsWith("/pelicula/")) "movie" else "tv" }
            val ajax = runCatching {
                app.post("$origin/wp-admin/admin-ajax.php", data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type), headers = mapOf("Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest"), interceptor = cf).text
            }.getOrNull()
            var embed = ajax?.let { runCatching { JSONObject(it).optString("embed_url") }.getOrNull() }.orEmpty()
            if (embed.isBlank()) {
                val rest = runCatching { app.get("$origin/wp-json/dooplayer/v1/post/$post?type=${URLEncoder.encode(type, "UTF-8")}&source=${URLEncoder.encode(nume, "UTF-8")}", headers = mapOf("Referer" to pageUrl), interceptor = cf).text }.getOrNull()
                embed = rest?.let { runCatching { JSONObject(it).optString("embed_url") }.getOrNull() }.orEmpty()
            }
            resolve(embed, pageUrl, subtitleCallback, emit, visited, 0)
        }
        for (frame in page.document.select("iframe[src]")) resolve(frame.absUrl("src"), pageUrl, subtitleCallback, emit, visited, 0)
        return emitted
    }

    private suspend fun resolve(input: String, referer: String, subtitles: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, visited: MutableSet<String>, depth: Int) {
        if (depth > 3 || input.isBlank()) return
        val cleaned = if (input.contains("<iframe")) Jsoup.parse(input).selectFirst("iframe[src]")?.attr("src").orEmpty() else input.trim()
        val url = runCatching { URI(referer).resolve(cleaned).toString() }.getOrNull() ?: return
        if (!(url.startsWith("https://") || url.startsWith("http://")) || !visited.add(url)) return
        val path = URI(url).path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
            val type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, type) { this.referer = referer })
            return
        }
        // Extractores integrados de CloudStream para los alojamientos conocidos.
        val native = runCatching { loadExtractor(url, referer, subtitles, callback) }.getOrDefault(false)
        if (native) return
        val response = runCatching { app.get(url, headers = mapOf("Referer" to referer), interceptor = cf) }.getOrNull() ?: return
        val doc = response.document
        val raw = doc.selectFirst("script#msp-data")?.data()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#msp[data-api]")?.absUrl("data-api")?.takeIf { it.isNotBlank() }?.let { api ->
                runCatching { app.get(api, headers = mapOf("Referer" to url), interceptor = cf).text }.getOrNull()
            }
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val key = json?.optString("pk").orEmpty()
        val langs = json?.optJSONArray("langs")
        if (key.isNotEmpty() && langs != null) {
            for (i in 0 until langs.length()) {
                val items = langs.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val encoded = items.optJSONObject(j)?.optString("url").orEmpty()
                    val decoded = runCatching {
                        val bytes = Base64.decode(encoded.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
                        String(ByteArray(bytes.size) { n -> (bytes[n].toInt() xor key[n % key.length].code).toByte() }, Charsets.UTF_8)
                    }.getOrNull().orEmpty()
                    resolve(decoded, url, subtitles, callback, visited, depth + 1)
                }
            }
        }
        for (frame in doc.select("iframe[src]")) resolve(frame.absUrl("src"), url, subtitles, callback, visited, depth + 1)
    }

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme == null || uri.host == null) null else "${uri.scheme}://${uri.authority}"
    }.getOrNull()
}
