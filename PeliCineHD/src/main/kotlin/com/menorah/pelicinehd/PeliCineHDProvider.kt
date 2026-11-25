package com.menorah.pelicinehd

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * PeliCineHD: sitio WordPress (tema Toroflilm) con fichas `/movies/<slug>/` y `/series/<slug>/`.
 *
 * El reproductor de cada ficha es un iframe a `/?trembed=N&trid=…&trtype=…`, que responde con un
 * HTML mínimo que envuelve el host real; ese host entrega un JW Player configurado a partir de un
 * `eval(...)` empaquetado. Aquí se resuelve esa cadena completa y se publican las URLs `.m3u8`.
 */
class PeliCineHDProvider : MainAPI() {
    override var mainUrl = PeliCineHDMarkup.BASE
    override var name = "PeliCineHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "latest" to "Novedades",
        // `%d` marca los listados paginados; la primera página se pide sin `/page/1/`.
        "/peliculas/page/%d/" to "Películas",
        "/series/page/%d/" to "Series",
        "/category/accion/page/%d/" to "Acción",
        "/category/animacion/page/%d/" to "Animación",
        "/category/aventura/page/%d/" to "Aventura",
        "/category/ciencia-ficcion/page/%d/" to "Ciencia ficción",
        "/category/comedia/page/%d/" to "Comedia",
        "/category/crimen/page/%d/" to "Crimen",
        "/category/drama/page/%d/" to "Drama",
        "/category/familia/page/%d/" to "Familia",
        "/category/fantasia/page/%d/" to "Fantasía",
        "/category/misterio/page/%d/" to "Misterio",
        "/category/romance/page/%d/" to "Romance",
        "/category/suspense/page/%d/" to "Suspense",
        "/category/terror/page/%d/" to "Terror",
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-AR,es;q=0.9,en;q=0.8",
    )

    /** Navegador del dispositivo: única vía por la que el sitio contesta (ver [PeliCineHDBrowser]). */
    private val browser = PeliCineHDBrowser()

    /**
     * Últimas páginas pedidas al navegador. Abrir la WebView cuesta segundos y CloudStream pide la
     * portada, la ficha y los enlaces seguidos, a veces dos veces; se guardan unas pocas.
     */
    private val pages = object : LinkedHashMap<String, Document>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>?): Boolean =
            size > 8
    }

    /**
     * Las portadas las descarga el cargador de imágenes de la app, que tampoco usa la WebView: se le
     * pasan la cookie del sitio y el UA del navegador, que es lo único que puede ayudarle.
     */
    private fun imageHeaders(): Map<String, String> = runCatching {
        val raw = android.webkit.CookieManager.getInstance().getCookie(mainUrl).orEmpty()
        val cookies = raw.split(';').map { it.trim() }.filter { it.contains('=') }.joinToString("; ")
        val agent = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent
        buildMap {
            put("Referer", "$mainUrl/")
            if (cookies.isNotEmpty()) put("Cookie", cookies)
            if (!agent.isNullOrBlank()) put("User-Agent", agent)
        }
    }.getOrDefault(mapOf("Referer" to "$mainUrl/"))

    /**
     * Pide [url] al navegador del dispositivo y devuelve el documento. El sitio bloquea a los
     * clientes HTTP por su huella (403 o conexión cerrada), no por cookies, así que no sirve
     * mandar `cf_clearance` ni resolver el reto: hay que dejar que la WebView haga la petición.
     */
    private suspend fun fetch(input: String, referer: String? = null, cached: Boolean = true): Document {
        val url = absolute(input)
        require(PeliCineHDMarkup.siteUrl(url)) { "Dirección fuera de PeliCineHD: $url" }
        if (cached) synchronized(pages) { pages[url] }?.let { return it }

        val page = browser.get(url, referer ?: "$mainUrl/")
        if (page == null) {
            throw ErrorLoadingException(
                "PeliCineHD no cargó en el navegador interno. Abre pelicinehd.com en el navegador " +
                    "del dispositivo para pasar la verificación y vuelve a intentarlo."
            )
        }
        if (!page.code.toString().startsWith("2") || PeliCineHDMarkup.isChallenge(page.code, page.body)) {
            throw ErrorLoadingException(
                "PeliCineHD devolvió HTTP ${page.code} al cargar la página. Abre pelicinehd.com en " +
                    "el navegador del dispositivo y vuelve a intentarlo."
            )
        }
        val doc = Jsoup.parse(page.body, page.url)
        if (cached) synchronized(pages) { pages[url] = doc }
        return doc
    }

    private fun withReferer(referer: String?): Map<String, String> =
        if (referer.isNullOrBlank()) headers else headers + mapOf("Referer" to referer)

    private fun absolute(input: String): String =
        if (input.startsWith("http")) input else URI("$mainUrl/").resolve(input.removePrefix("/")).toString()

    /** Las portadas también están tras el bloqueo: se adjuntan cookie y UA del navegador. */
    private fun responses(cards: List<Card>): List<SearchResponse> {
        val posterHeaders = imageHeaders()
        return cards.map { card ->
            if (card.movie) newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                posterUrl = card.poster
                this.posterHeaders = posterHeaders
                year = card.year
            } else newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                posterUrl = card.poster
                this.posterHeaders = posterHeaders
                year = card.year
            }
        }
    }

    // ---------------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "latest") {
            val doc = fetch("/")
            val sections = PeliCineHDMarkup.sections(doc)
            if (sections.isNotEmpty()) {
                return newHomePageResponse(
                    sections.map { (name, items) -> HomePageList(name, responses(items)) },
                    hasNext = false,
                )
            }
            return newHomePageResponse(request.name, responses(PeliCineHDMarkup.catalogue(doc)), false)
        }

        // Las entradas con `%d` son catálogos paginados; el resto solo tiene la primera página.
        val template = request.data
        val paginated = template.contains("%d")
        val path = when {
            !paginated -> template.replace("%d", "")
            page <= 1 -> template.format(1).replace("page/1/", "")
            else -> template.format(page)
        }
        val doc = fetch(path)
        val items = PeliCineHDMarkup.catalogue(doc)
        return newHomePageResponse(
            request.name,
            responses(items),
            hasNext = paginated && PeliCineHDMarkup.hasNext(doc, page),
        )
    }

    // ---------------------------------------------------------------- búsqueda

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val doc = fetch("/?s=${URLEncoder.encode(term, "UTF-8")}")
        val found = PeliCineHDMarkup.catalogue(doc)
        return responses(found)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------- ficha

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        // La guía recomienda lanzar errores: CloudStream los muestra al usuario y facilitan el reporte.
        val details = PeliCineHDMarkup.details(doc)
            ?: throw ErrorLoadingException("PeliCineHD no devolvió el título de $url")
        val canonical = PeliCineHDMarkup.canonical(doc) ?: absolute(url)
        val tags = details.genres.ifEmpty { null }
        val cast = details.actors.take(15).map { ActorData(Actor(it)) }.ifEmpty { null }

        if (!PeliCineHDMarkup.isSeries(doc)) {
            return newMovieLoadResponse(details.title, canonical, TvType.Movie, canonical) {
                posterUrl = details.poster
                posterHeaders = imageHeaders()
                backgroundPosterUrl = details.poster
                plot = details.plot
                year = details.year
                duration = details.durationMinutes
                this.score = details.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
                this.tags = tags
                this.actors = cast
            }
        }

        val items = loadEpisodes(doc, canonical)
        if (items.isEmpty()) throw ErrorLoadingException("PeliCineHD no devolvió episodios de ${details.title}")
        val episodes = items.map { item ->
            newEpisode(item.url) {
                name = item.name
                season = item.season
                episode = item.episode
                posterUrl = item.poster ?: details.poster
            }
        }
        return newTvSeriesLoadResponse(details.title, canonical, TvType.TvSeries, episodes) {
            posterUrl = details.poster
            posterHeaders = imageHeaders()
            backgroundPosterUrl = details.poster
            plot = details.plot
            year = details.year
            this.score = details.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            this.tags = tags
            this.actors = cast
        }
    }

    /**
     * La ficha lista solo la temporada seleccionada; las demás se piden por AJAX con
     * `action_select_season` y el `data-post` del desplegable. Si el AJAX falla se conserva lo
     * que ya venía en el HTML para no perder la serie entera.
     */
    private suspend fun loadEpisodes(doc: Document, pageUrl: String): List<EpisodeItem> {
        val current = PeliCineHDMarkup.currentEpisodes(doc)
        val seasons = PeliCineHDMarkup.seasons(doc)
        val post = PeliCineHDMarkup.postId(doc)
        if (post == null || seasons.size <= 1) return current
        val missing = seasons - current.map { it.season }.toSet()
        if (missing.isEmpty() || missing.size > 60) return current

        // Una WebView cada vez: abrir varias a la vez es más lento y más frágil.
        val permits = Semaphore(1)
        val extra = coroutineScope {
            missing.map { season ->
                async {
                    permits.withPermit {
                        runCatching<List<EpisodeItem>> {
                            val page = browser.post(
                                pageUrl,
                                PeliCineHDMarkup.AJAX,
                                mapOf(
                                    "action" to "action_select_season",
                                    "season" to season.toString(),
                                    "post" to post,
                                ),
                            ) ?: return@runCatching emptyList()
                            if (!page.code.toString().startsWith("2")) return@runCatching emptyList()
                            PeliCineHDMarkup.episodes(Jsoup.parse(page.body)).filter { it.season == season }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }

        return (current + extra).distinctBy { it.season to it.episode }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // ---------------------------------------------------------------- reproducción

    /**
     * Página del hoster con el JW Player. Primero como cliente normal (los hosters no están
     * protegiendo como el sitio) y, si rechaza o devuelve el reto, desde el navegador.
     */
    private suspend fun probe(url: String, referer: String?): List<String> {
        val direct = runCatching<List<String>> {
            val response = app.get(url, headers = withReferer(referer), timeout = 15L)
            if (!response.isSuccessful || PeliCineHDMarkup.isChallenge(response.code, response.text)) {
                return@runCatching emptyList()
            }
            PeliCineHDMarkup.streamsFromHost(response.text)
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return direct

        val page = runCatching { browser.get(url, referer ?: "$mainUrl/") }.getOrNull() ?: return emptyList()
        if (!page.code.toString().startsWith("2")) return emptyList()
        return PeliCineHDMarkup.streamsFromHost(page.body)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = fetch(data)
        val pageUrl = PeliCineHDMarkup.canonical(page) ?: absolute(data)
        val options = PeliCineHDMarkup.players(page)
        if (options.isEmpty()) return false

        var emitted = false
        for (option in options.take(12)) {
            val language = PeliCineHDMarkup.language(option.server)
            val label = listOf(language, option.server.ifBlank { "Opción ${option.option + 1}" })
                .filterNotNull().distinct().joinToString(" · ")

            // 1) El wrapper `?trembed=…` revela el host real. Vive en el dominio protegido, así que
            //    se prueba primero el cliente normal (barato) y luego el navegador.
            val host = runCatching<String?> {
                val response = app.get(option.url, headers = withReferer(pageUrl), timeout = 10L)
                if (!response.isSuccessful) null
                else PeliCineHDMarkup.wrappedUrl(response.text, response.url)
            }.getOrNull() ?: runCatching<String?> {
                val wrapped = browser.get(option.url, pageUrl) ?: return@runCatching null
                if (!wrapped.code.toString().startsWith("2")) null
                else PeliCineHDMarkup.wrappedUrl(wrapped.body, wrapped.url)
            }.getOrNull()

            // 2) Ese host puede estar cubierto por un extractor de CloudStream.
            if (host != null) {
                val handled = runCatching { loadExtractor(host, pageUrl, subtitleCallback) { callback(it) } }
                    .getOrDefault(false)
                if (handled) {
                    emitted = true
                    continue
                }
            }

            // 3) Si no, se lee el JW Player del host directamente y, en último caso, el del wrapper.
            val referer = host ?: option.url
            val streams = (host?.let { probe(it, pageUrl) }.orEmpty()).ifEmpty { probe(option.url, pageUrl) }
            for (stream in streams) {
                val links = runCatching {
                    M3u8Helper.generateM3u8(
                        source = label,
                        streamUrl = stream,
                        referer = referer,
                        headers = headers,
                        name = label,
                    )
                }.getOrElse { emptyList() }

                // Si el manifiesto no se pudo leer se publica igualmente el enlace base.
                val resolved = links.ifEmpty {
                    listOf(newExtractorLink("PeliCineHD", label, stream, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.headers = headers
                    })
                }
                resolved.forEach { link ->
                    emitted = true
                    callback(link)
                }
            }
        }
        return emitted
    }
}
