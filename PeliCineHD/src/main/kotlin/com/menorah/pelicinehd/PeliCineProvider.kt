package com.menorah.pelicinehd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

/**
 * PeliCineHD provider (Dooplay WordPress theme), verified against the live site in
 * November 2026: titles at `/movies/<slug>/` and `/series/<slug>/`, catalogs at
 * `/peliculas/` and `/series/`, search at `/?s=`, seasons via `action_select_season`
 * on `wp-admin/admin-ajax.php`, players at `/?trembed=<n>&trid=<post>&trtype=<1|2>`.
 *
 * Cloudflare fronts the site: plain HTTP clients get a managed challenge. The device
 * WebView passes it ([PeliCineBrowser]); its clearance cookies are reused afterwards.
 */
class PeliCineProvider(private val context: android.content.Context? = null) : MainAPI() {
    override var mainUrl = PeliCineMarkup.BASE
    override var name = "PeliCineHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/peliculas" to "Películas",
        "/series" to "Series",
    )

    private val browser by lazy { context?.let { PeliCineBrowser(it) } }
    @Volatile private var clearance: String? = null

    private fun headers(): Map<String, String> = buildMap {
        put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        put("Accept-Language", "es-419,es;q=0.9")
        clearance?.let { put("Cookie", it) }
    }

    private fun challenged(text: String, code: Int): Boolean =
        code == 403 || code == 503 || text.contains("Just a moment", true) ||
            text.contains("cf-chl", true) || text.contains("_cf_chl_opt")

    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(PeliCineMarkup.siteUrl(url)) { "Dirección fuera de PeliCineHD" }
        val res = app.get(url, headers = headers(), timeout = 15)
        var text = res.text
        var finalUrl = res.url
        if (!res.isSuccessful || !PeliCineMarkup.siteUrl(finalUrl) || challenged(text, res.code)) {
            val page = browser?.fetch(url)
            if (page != null && page.text.isNotBlank() && PeliCineMarkup.siteUrl(page.url) && !challenged(page.text, 200)) {
                text = page.text
                finalUrl = page.url
                browser?.cookieHeader(url)?.let { clearance = it }
            } else {
                throw IllegalStateException("PeliCineHD no respondió correctamente (HTTP ${res.code}).")
            }
        }
        return Jsoup.parse(text, finalUrl)
    }

    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { card ->
        val type = if (card.url.contains("/movies/")) TvType.Movie else TvType.TvSeries
        newMovieSearchResponse(card.title, card.url, type) { posterUrl = card.poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val path = if (page == 1) "$base/" else "$base/page/$page/"
        val doc = fetch(path)
        return newHomePageResponse(
            HomePageList(request.name, responses(PeliCineMarkup.cards(doc))),
            hasNext = PeliCineMarkup.hasNext(doc),
        )
    }

    private fun diagnosticCard(): MovieSearchResponse =
        newMovieSearchResponse("🧪 Diagnóstico de PeliCineHD (no es contenido)", "diagnostico://pelicinehd", TvType.Movie)

    override suspend fun search(query: String): List<SearchResponse> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        if (text.contains("diag", ignoreCase = true)) return listOf(diagnosticCard())
        return responses(PeliCineMarkup.cards(fetch("/?s=${URLEncoder.encode(text, "UTF-8")}")))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val text = query.trim()
        if (text.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        if (page == 1 && text.contains("diag", ignoreCase = true)) {
            return newSearchResponseList(listOf(diagnosticCard()), hasNext = false)
        }
        val doc = fetch("/page/$page/?s=${URLEncoder.encode(text, "UTF-8")}")
        return newSearchResponseList(responses(PeliCineMarkup.cards(doc)), hasNext = PeliCineMarkup.hasNext(doc))
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("diagnostico://")) {
            return newMovieLoadResponse("🧪 Diagnóstico de PeliCineHD (no es contenido)", url, TvType.Movie, url) {
                plot = "Reproduce este título: la lista de fuentes muestra cada paso contra el sitio real."
            }
        }
        val doc = fetch(url)
        val title = PeliCineMarkup.detailTitle(doc) ?: return null
        val poster = PeliCineMarkup.detailPoster(doc)
        val plot = PeliCineMarkup.detailPlot(doc)
        val year = PeliCineMarkup.detailYear(doc)
        val genres = PeliCineMarkup.detailGenres(doc)
        val isMovie = URI(url).path.startsWith("/movies/") || PeliCineMarkup.seasons(doc).isEmpty()
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.plot = plot; this.year = year
                if (genres.isNotEmpty()) this.tags = genres
            }
        }
        val post = PeliCineMarkup.postId(doc)
        val chapters = mutableListOf<Episode>()
        if (post != null) {
            for (entry in PeliCineMarkup.seasons(doc).take(40)) {
                val html = try {
                    app.post("$mainUrl/wp-admin/admin-ajax.php", referer = url, headers = headers(),
                        data = mapOf("action" to "action_select_season", "season" to entry.season.toString(), "post" to post),
                        timeout = 15).text
                } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                if (html == null) continue
                PeliCineMarkup.seasonEpisodes(html, url).forEachIndexed { index, ref ->
                    chapters.add(newEpisode(ref.url) {
                        name = ref.title
                        season = entry.season
                        episode = index + 1
                    })
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, chapters) {
            posterUrl = poster; this.plot = plot; this.year = year
            if (genres.isNotEmpty()) this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.startsWith("diagnostico://")) return diagnose(callback)
        val page = fetch(data)
        val tv = !URI(data).path.startsWith("/movies/")
        val post = PeliCineMarkup.postId(page)
            ?: Regex("trid=(\\d{1,12})").find(page.html())?.groupValues?.get(1)
        var emitted = false

        suspend fun resolveTrembed(trembedUrl: String, label: String): Boolean {
            val playerText = try {
                app.get(trembedUrl, referer = data, headers = headers(), timeout = 15).text
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { null } ?: return false
            var any = false
            // VOE-style pages inline the real manifest; emit it directly.
            PeliCineMarkup.manifest(playerText)?.let { manifest ->
                val type = if (manifest.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(newExtractorLink(this.name, label, manifest, type) { this.referer = data })
                any = true
            }
            val target = PeliCineMarkup.trembedTarget(playerText)
            if (target != null && target != PeliCineMarkup.manifest(playerText)) {
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try { loadExtractor(target, data, subtitleCallback) { link -> links.add(link) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                val found = synchronized(links) { links.toList() }
                for (link in found) callback(relabeled(link, label))
                if (found.isNotEmpty()) any = true
            }
            return any
        }

        if (post != null) {
            val servers = PeliCineMarkup.servers(page)
            val count = if (servers.isEmpty()) 3 else servers.size
            for (server in servers.ifEmpty { (0 until count).map { ServerLabel(it, "Servidor ${it + 1}", "") } }) {
                val label = "[${PeliCineMarkup.language(server.language)}] ${server.name}"
                if (resolveTrembed(PeliCineMarkup.trembedUrl(server.index + 1, post, tv), label)) emitted = true
            }
        }
        if (!emitted) {
            // Generic embeds sitting directly on the page.
            for (iframe in page.select("iframe[src]").take(6)) {
                val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                if (!src.startsWith("http")) continue
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try { loadExtractor(src, data, subtitleCallback) { link -> links.add(link) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                val found = synchronized(links) { links.toList() }
                for (link in found) callback(relabeled(link, "[Embebido]"))
                if (found.isNotEmpty()) emitted = true
            }
        }
        return emitted
    }

    private suspend fun diagnose(callback: (ExtractorLink) -> Unit): Boolean {
        val steps = mutableListOf<String>()
        fun step(text: String) { steps.add(text.replace(Regex("\\s+"), " ").trim().take(110)) }
        try {
            val target = "$mainUrl/movies/la-odisea/"
            val res = app.get(target, headers = headers(), timeout = 15)
            step("ficha HTTP ${res.code} bytes=${res.text.length} desafio=${challenged(res.text, res.code)}")
            if (challenged(res.text, res.code)) {
                val page = browser?.fetch(target)
                step("webview bytes=${page?.text?.length ?: 0} desafio=${page?.let { challenged(it.text, 200) }}")
                if (page != null) browser?.cookieHeader(target)?.let { clearance = it }
            }
            val doc = fetch(target)
            step("post=${PeliCineMarkup.postId(doc) ?: "¿?"} servidores=${PeliCineMarkup.servers(doc).size} tarjetas=${PeliCineMarkup.cards(doc).size}")
            val post = PeliCineMarkup.postId(doc)
            if (post != null) {
                for (i in 1..2) {
                    val player = try {
                        app.get(PeliCineMarkup.trembedUrl(i, post, false), referer = target, headers = headers(), timeout = 15)
                    } catch (e: CancellationException) { throw e } catch (t: Throwable) { null }
                    val text = player?.text.orEmpty()
                    step("trembed$i HTTP ${player?.code ?: "err"} bytes=${text.length} iframe=${PeliCineMarkup.trembedTarget(text) != null} manifest=${PeliCineMarkup.manifest(text)?.take(50)}")
                }
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

    private suspend fun relabeled(link: ExtractorLink, label: String): ExtractorLink {
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData
        }
    }
}
