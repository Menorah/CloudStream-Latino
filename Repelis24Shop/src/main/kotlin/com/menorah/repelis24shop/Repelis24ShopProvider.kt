package com.menorah.repelis24shop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

class Repelis24ShopProvider : MainAPI() {
    override var mainUrl = "https://repelis24.shop"
    override var name = "RePelis24.shop"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/peliculas" to "Películas", "/series" to "Series", "/peliculas/estrenos" to "Estrenos",
        "/genero/accion" to "Acción", "/genero/animacion" to "Animación", "/genero/anime" to "Anime",
        "/genero/aventura" to "Aventura", "/genero/belica" to "Bélica", "/genero/ciencia-ficcion" to "Ciencia ficción",
        "/genero/comedia" to "Comedia", "/genero/crimen" to "Crimen", "/genero/documental" to "Documental",
        "/genero/drama" to "Drama", "/genero/familia" to "Familia", "/genero/fantasia" to "Fantasía"
    )
    private val ua = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(Repelis24ShopMarkup.site(url)) { "Dirección fuera de RePelis24.shop" }
        val headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
        var response = app.get(url, headers = headers, timeout = 15)
        if (response.code == 403 && url != "$mainUrl/") {
            app.get("$mainUrl/", headers = headers, timeout = 15)
            response = app.get(url, headers = headers, timeout = 15)
        } else if (response.code in listOf(502, 503, 504)) {
            delay(750)
            response = app.get(url, headers = headers, timeout = 15)
        }
        check(response.isSuccessful && Repelis24ShopMarkup.site(response.url)) { "RePelis24.shop: HTTP ${response.code}" }
        return Jsoup.parse(response.text, response.url)
    }
    private fun responses(items: List<Card>): List<SearchResponse> = items.map { item ->
        if (item.movie) newMovieSearchResponse(item.title, item.url, TvType.Movie) { posterUrl = item.poster }
        else newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) { posterUrl = item.poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(request.data + if (page > 1) "/page/$page" else "")
        return newHomePageResponse(HomePageList(request.name, responses(Repelis24ShopMarkup.cards(doc))), hasNext = Repelis24ShopMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> = when {
        query.isBlank() -> emptyList()
        query.contains("diag", ignoreCase = true) -> listOf(
            newMovieSearchResponse("🧪 Diagnóstico de RePelis24.shop (no es contenido)", "diagnostico://repelis24shop", TvType.Movie),
        )
        else -> responses(Repelis24ShopMarkup.cards(fetch("/buscar/${URLEncoder.encode(query.trim(), "UTF-8")}")))
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("diagnostico://")) {
            return newMovieLoadResponse("🧪 Diagnóstico de RePelis24.shop (no es contenido)", url, TvType.Movie, url) {
                plot = "Reproduce este título: la lista de fuentes muestra cada paso contra el sitio real."
            }
        }
        val doc = fetch(url); val article = doc.selectFirst("article.backdrop-info") ?: return null
        val title = article.selectFirst("h1 [itemprop=name]")?.text()?.replace(Regex("(?i)^ver (película|serie)\\s+"), "")?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = Repelis24ShopMarkup.img(article.selectFirst("figure.poster img"))
            ?: Repelis24ShopMarkup.cardImage(article)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.startsWith("https://") }
        val backdrop = article.selectFirst("[style*=background]")?.attr("style")
            ?.let { Regex("url\\(['\"]?(https://[^'\")]+)['\"]?\\)").find(it)?.groupValues?.get(1) }
            ?: Repelis24ShopMarkup.img(article.selectFirst(".backdrop img, img.backdrop"))
        val description = article.selectFirst("[itemprop=description] p")?.text()?.takeIf { it.isNotBlank() }
        val year = article.selectFirst("[itemprop=datePublished]")?.text()?.trim()?.toIntOrNull()
        val tags = article.select("[itemprop=genre]").map { it.text().trim().trimEnd(',') }.filter { it.isNotBlank() }
        if (URI(url).path.orEmpty().startsWith("/pelicula/"))
            return newMovieLoadResponse(title, doc.location(), TvType.Movie, doc.location()) { posterUrl = poster; backgroundPosterUrl = backdrop ?: poster; plot = description; this.year = year; this.tags = tags }
        val seasons = Repelis24ShopMarkup.seasons(doc)
        if (seasons.isEmpty()) return null
        val all = coroutineScope {
            val semaphore = Semaphore(4)
            seasons.map { season -> async { semaphore.withPermit { Repelis24ShopMarkup.episodes(fetch(season.url), season.number) } } }.awaitAll().flatten()
        }
        val episodes = all.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number })).map { item ->
            newEpisode(item.url) { name = item.title; season = item.season; episode = item.number; posterUrl = item.poster }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, doc.location(), TvType.TvSeries, episodes) { posterUrl = poster; backgroundPosterUrl = backdrop ?: poster; plot = description; this.year = year; this.tags = tags }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.startsWith("diagnostico://")) return diagnose(callback)
        val doc = fetch(data); val players = Repelis24ShopMarkup.players(doc).take(24)
        val emitted = AtomicBoolean(false); val lock = Any()
        coroutineScope {
            val semaphore = Semaphore(4)
            players.map { player -> async { semaphore.withPermit {
                try {
                    withTimeout(18_000) {
                        val voe = Repelis24ShopMarkup.voeFromWrapper(player.url)
                        if (voe != null) {
                            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                            loadExtractor(voe, player.url, subtitleCallback) { links.add(it) }
                            for (link in synchronized(links) { links.toList() }) {
                                val labeled = label(link, player.language)
                                if (mediaPlayable(labeled.url, labeled.headers + mapOf("Referer" to labeled.referer))) {
                                    synchronized(lock) { callback(labeled); emitted.set(true) }
                                }
                            }
                        } else if (URI(player.url).path.orEmpty().startsWith("/watch/")) {
                            val res = app.get(player.url, headers = mapOf("User-Agent" to ua, "Referer" to doc.location()), timeout = 12)
                            if (!res.isSuccessful || !Repelis24ShopMarkup.nupload(res.url)) return@withTimeout
                            val media = Repelis24ShopMarkup.media(res.text) ?: return@withTimeout
                            val headers = mapOf("User-Agent" to ua, "Referer" to res.url)
                            if (!mediaPlayable(media, headers)) return@withTimeout
                            val link = newExtractorLink(name, "[${player.language}] Nuupload", media, ExtractorLinkType.M3U8) {
                                referer = res.url; this.headers = headers
                            }
                            synchronized(lock) { callback(link); emitted.set(true) }
                        }
                    }
                } catch (e: TimeoutCancellationException) { /* other player options remain */ }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* unavailable player */ }
            } } }.awaitAll()
        }
        return emitted.get()
    }
    /**
     * Host-side takedowns return HTML where the media should be; probing the first bytes
     * keeps CONTAINER_MALFORMED links out of the list. Unreachable probes stay optimistic.
     */
    private suspend fun mediaPlayable(url: String, headers: Map<String, String>): Boolean {
        if (!url.startsWith("https://")) return false
        val probe = try {
            app.get(url, headers = headers + mapOf("Range" to "bytes=0-1023"), timeout = 10)
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { return true }
        if (!probe.isSuccessful) return probe.code in listOf(206, 403, 405, 416)
        val text = probe.text
        if (text.isBlank()) return true
        val head = text.trimStart()
        if (head.startsWith("#EXTM3U") || head.startsWith("#EXT-X-")) return true
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        if (bytes.size > 12 && (String(bytes, 4, 4) == "ftyp" || bytes[0] == 0x47.toByte() || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()))) return true
        return !(head.startsWith("<") || head.contains("<html", true) || head.contains("Access denied", true))
    }
    private suspend fun label(link: ExtractorLink, language: String): ExtractorLink {
        val title = "[$language] ${link.name}"
        if (link is DrmExtractorLink) return newDrmExtractorLink(link.source, title, link.url, link.type, link.uuid) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
            kid = link.kid; key = link.key; kty = link.kty; keyRequestParameters = link.keyRequestParameters; licenseUrl = link.licenseUrl
        }
        return newExtractorLink(link.source, title, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
        }
    }

    /** Reports each scraping step as a source entry so failures are visible on-device. */
    private suspend fun diagnose(callback: (ExtractorLink) -> Unit): Boolean {
        val steps = mutableListOf<String>()
        fun step(text: String) { steps.add(text.replace(Regex("\\s+"), " ").trim().take(130)) }
        try {
            val list = fetch("/peliculas")
            val cards = Repelis24ShopMarkup.cards(list)
            step("catalogo HTTP ok tarjetas=${cards.size} conPoster=${cards.count { it.poster != null }}")
            val firstImg = list.selectFirst(".movie-item img")
            if (firstImg != null) {
                step("img attrs: " + firstImg.attributes().joinToString(" ") { "${it.key}=${it.value.take(40)}" })
            } else step("sin <img> en las tarjetas (¿inyectado por JS?)")
            val item = fetch("/pelicula/la-odisea/")
            val article = item.selectFirst("article.backdrop-info")
            val poster = article?.let { Repelis24ShopMarkup.img(it.selectFirst("figure.poster img")) }
                ?: item.selectFirst("meta[property=og:image]")?.attr("content")
            step("ficha article=${article != null} poster=${poster?.take(50) ?: "NO"} og=${item.selectFirst("meta[property=og:image]")?.attr("content")?.take(40) ?: "no"}")
            val players = Repelis24ShopMarkup.players(item).take(3)
            step("jugadores=${players.size} " + players.joinToString(" ") { it.url.take(40) })
            for (player in players.take(2)) {
                step("probe ${player.url.take(40)} → playable=${mediaPlayable(player.url, mapOf("User-Agent" to ua, "Referer" to item.location()))}")
            }
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            step("error ${t.javaClass.simpleName}: ${t.message?.take(60)}")
        }
        step("pasos=${steps.size} (fin)")
        steps.forEachIndexed { index, text ->
            callback(newExtractorLink(this.name, "%02d) %s".format(index + 1, text), "$mainUrl/", ExtractorLinkType.VIDEO) {})
        }
        return true
    }
}
