package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ATTEMPTS = 3
private const val ALL_SECTIONS_FAILED = 8

/** How long a failure keeps counting towards the "site refuses this network" decision. */
private const val FAILURE_WINDOW_MS = 10_000L
private const val REQUEST_TIMEOUT = 25L

/**
 * Request headers the site (and its Cloudflare setup) expect from a browser. The User-Agent
 * matters: Cloudflare answers 403 to clients that identify themselves as an app or a WebView.
 */
private val PAGE_HEADERS = mapOf(
    "User-Agent" to BrowserSiteTransport.BROWSER_AGENT,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "es-AR,es;q=0.9,en;q=0.8",
    "Upgrade-Insecure-Requests" to "1",
    "Referer" to "https://pelicinehd.com/"
)

/**
 * Provider for pelicinehd.com.
 *
 * The site is a WordPress install behind Cloudflare. It answers plain requests (the app's own
 * HTTP client is enough); only datacenter IPs get the interactive challenge, which is reported as
 * a readable error instead of an empty screen.
 *
 * Series are a bit unusual: the detail page only renders the episodes of one season, the rest are
 * requested through the theme's `admin-ajax.php` action, and each episode is its own page with the
 * same player markup as a movie.
 */
class PeliCineHDProvider(context: Context) : MainAPI() {
    override var mainUrl = PeliCineHDMarkup.BASE
    override var name = "PeliCineHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /**
     * The site protects itself with Cloudflare. The plain HTTP client gets the challenge on some
     * networks, so the off-screen WebView is used as a second transport: it runs the JavaScript
     * challenge, then the page is fetched from inside it.
     */
    override val usesWebView = true
    private val site = SiteClient.create(context)

    // Every section asks its own page, and the browser fallback is slower than plain HTTP.
    // Every section asks its own page through the browser first, so the screen gets room.
    override val getMainPageTimeoutMs: Long? = 300_000L
    override val searchTimeoutMs: Long? = 60_000L
    override val loadTimeoutMs: Long? = 90_000L
    override val loadLinksTimeoutMs: Long? = 240_000L

    override val mainPage = mainPageOf(
        "peliculas" to "Películas",
        "series" to "Series",
        "category/accion" to "Acción",
        "category/animacion" to "Animación",
        "category/aventura" to "Aventura",
        "category/ciencia-ficcion" to "Ciencia ficción",
        "category/comedia" to "Comedia",
        "category/crimen" to "Crimen",
        "category/drama" to "Drama",
        "category/familia" to "Familia",
        "category/fantasia" to "Fantasía",
        "category/kids" to "Infantil",
        "category/misterio" to "Misterio",
        "category/romance" to "Romance",
        "category/suspense" to "Suspenso",
        "category/terror" to "Terror"
    )

    // ------------------------------------------------------------------ http

    private suspend fun fetch(input: String, referer: String = "$mainUrl/"): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(PeliCineHDMarkup.siteUrl(url)) { "Dirección fuera de PeliCineHD" }
        var lastError: Exception? = null
        for (attempt in 1..ATTEMPTS) {
            if (attempt > 1) delay(500L * attempt)
            try {
                return fetchOnce(url, referer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ErrorLoadingException) {
                // A challenge will not disappear by asking again, and each browser attempt is slow.
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("PeliCineHD no respondió.")
    }

    private suspend fun fetchOnce(url: String, referer: String): Document {
        val response = site.get(url, PAGE_HEADERS + ("Referer" to referer))
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "PeliCineHD no respondió correctamente (HTTP ${response.code}), ni con el cliente " +
                    "HTTP ni con el navegador del dispositivo."
            )
        }
        if (!PeliCineHDMarkup.siteUrl(response.url)) {
            throw IllegalStateException("PeliCineHD redirigió fuera del sitio.")
        }
        if (PeliCineHDMarkup.isChallenge(response.text)) {
            throw ErrorLoadingException(
                "PeliCineHD pidió una verificación anti-bots. Abre el sitio en tu navegador y vuelve a intentarlo."
            )
        }
        return Jsoup.parse(response.text, response.url)
    }

    /** CloudStream loads every catalog at once; a small gate keeps the site responsive. */
    private val catalogGate = Semaphore(4)

    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { card ->
        val poster = card.poster
        if (card.movie) {
            newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                this.posterUrl = poster
                this.year = card.year
            }
        } else {
            newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = card.year
            }
        }
    }

    // -------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = try {
            catalogGate.withPermit { fetch(PeliCineHDMarkup.catalogUrl(request.data, page)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failing section must not empty the whole screen, but when the site rejects
            // every request the user has to read why instead of seeing an empty catalogue.
            // Failures are counted inside one batch: a retry seconds later starts from zero.
            val now = System.currentTimeMillis()
            if (now - lastFailureAt.get() > FAILURE_WINDOW_MS) failures.set(0)
            lastFailureAt.set(now)
            if (failures.incrementAndGet() >= ALL_SECTIONS_FAILED) {
                throw ErrorLoadingException(
                    (e as? ErrorLoadingException)?.message
                        ?: "No se pudo abrir PeliCineHD (${e.message ?: "sin respuesta"}). " +
                        "Comprueba que el sitio abra en el navegador del dispositivo."
                )
            }
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }
        return newHomePageResponse(
            HomePageList(request.name, responses(PeliCineHDMarkup.cards(document))),
            hasNext = PeliCineHDMarkup.hasNext(document, page)
        )
    }

    /**
     * The home screen asks every section at once. If eight of them fail in a row without a single
     * success in between, the site is refusing this network, not missing one category.
     */
    private val failures = AtomicInteger(0)
    private val lastFailureAt = AtomicLong(0)


    // ----------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return responses(PeliCineHDMarkup.cards(fetch(PeliCineHDMarkup.searchUrl(query))))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val document = fetch(PeliCineHDMarkup.searchUrl(query, page))
        return newSearchResponseList(
            responses(PeliCineHDMarkup.cards(document)),
            hasNext = PeliCineHDMarkup.hasNext(document, page)
        )
    }

    // ------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val document = fetch(url)
        val title = PeliCineHDMarkup.title(document) ?: throw ErrorLoadingException(
            "PeliCineHD devolvió una página sin título: el sitio puede estar mostrando otra cosa " +
                "(verificación, mantenimiento o un aviso)."
        )
        val poster = PeliCineHDMarkup.poster(document)
        val plot = PeliCineHDMarkup.plot(document)
        val year = PeliCineHDMarkup.year(document)
        val tags = PeliCineHDMarkup.genres(document)
        val actors = PeliCineHDMarkup.actors(document).map { ActorData(Actor(it)) }
        val score = PeliCineHDMarkup.score(document)
        val pageUrl = document.location()

        val recommendations = try {
            responses(PeliCineHDMarkup.recommendations(document))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (!PeliCineHDMarkup.isSeries(document)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                score?.let { this.score = Score.from10(it) }
                this.duration = PeliCineHDMarkup.durationMinutes(document)
                this.recommendations = recommendations
            }
        }

        val episodes = loadEpisodes(document, pageUrl)
        if (episodes.isEmpty()) {
            // An empty series is a failure of its own (usually a rejected season switch), and
            // CloudStream shows it as "Error loading" with no hint about where it came from.
            throw ErrorLoadingException(
                "PeliCineHD devolvió la ficha pero ningún episodio. " +
                    "Abra la serie en el navegador y vuelva a intentarlo."
            )
        }
        // Seasons with no episode of their own would leave gaps in the list, so the season
        // switch is also tried for the seasons the page does not render.
        val pendingSeasons = PeliCineHDMarkup.seasons(document)
            .map { it.number }
            .filter { number -> episodes.none { it.season == number } }
        val filled = if (pendingSeasons.isEmpty()) {
            episodes
        } else {
            val post = PeliCineHDMarkup.seasonsPostId(document) ?: 0
            pendingSeasons.flatMap { number ->
                try {
                    seasonEpisodes(post, number, pageUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
        val allEpisodes = (episodes + filled).distinctBy { it.url }
            .sortedWith(compareBy({ it.season }, { it.number }))

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, allEpisodes.map { item ->
            newEpisode(item.url) {
                name = item.title
                season = item.season
                episode = item.number
            }
        }) {
            this.posterUrl = poster
            backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            score?.let { this.score = Score.from10(it) }
            this.recommendations = recommendations
        }
    }

    /**
     * The detail page renders one season; the others are fetched from the theme's AJAX action.
     * Episode thumbnails are skipped on purpose: `Episode` has no header field and images from
     * the site need the site referer, so the list would show broken thumbnails.
     */
    private suspend fun loadEpisodes(document: Document, pageUrl: String): List<EpisodeItem> {
        val seasons = PeliCineHDMarkup.seasons(document)
        val renderedSeason = PeliCineHDMarkup.parseEpisodeNumber(
            document.selectFirst("div.choose-season dt.n_s")?.text()
        )?.second ?: seasons.firstOrNull()?.number ?: 1

        val rendered = PeliCineHDMarkup.episodes(document, renderedSeason)
        val pending = seasons.filter { it.number != renderedSeason }
        val post = PeliCineHDMarkup.seasonsPostId(document)

        val extra = if (pending.isEmpty() || post == null || post <= 0) {
            emptyList()
        } else {
            val gate = Semaphore(3)
            coroutineScope {
                pending.map { season ->
                    async {
                        gate.withPermit {
                            withTimeoutOrNull(60_000L) {
                                try {
                                    seasonEpisodes(post, season.number, pageUrl)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }.orEmpty()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        return (rendered + extra)
            .distinctBy { it.url }
            .sortedWith(compareBy({ it.season }, { it.number }))
    }

    /** Season switch: `action_select_season` returns the same episode cards the site injects. */
    private suspend fun seasonEpisodes(post: Int, season: Int, pageUrl: String): List<EpisodeItem> {
        val response = try {
            site.post(
                "$mainUrl${PeliCineHDMarkup.AJAX_PATH}",
                PAGE_HEADERS + ("Referer" to pageUrl) + ("X-Requested-With" to "XMLHttpRequest"),
                mapOf(
                    "action" to "action_select_season",
                    "season" to season.toString(),
                    "post" to post.toString()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        if (!response.isSuccessful || PeliCineHDMarkup.isChallenge(response.text)) return emptyList()
        return PeliCineHDMarkup.parseSeasonHtml(response.text, pageUrl, season)
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetch(data)
        val options = PeliCineHDMarkup.players(document).take(20)
        if (options.isEmpty()) return false
        val emitted = AtomicBoolean(false)
        val lock = Any()
        val seen = Collections.synchronizedSet(mutableSetOf<String>())

        withTimeoutOrNull(80_000L) {
            coroutineScope {
                val gate = Semaphore(4)
                options.map { option ->
                    async {
                        gate.withPermit {
                            withTimeoutOrNull(25_000L) {
                                val links = try {
                                    extract(
                                        option.url,
                                        document.location(),
                                        option.language,
                                        subtitleCallback
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                for (link in links) {
                                    if (!seen.add("${link.url}|${link.name}")) continue
                                    synchronized(lock) {
                                        emitted.set(true)
                                        callback(link)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return emitted.get()
    }

    /**
     * Every player option is an iframe on the site itself (`?trembed=n&trid=<post>&trtype=1`),
     * which answers with the hoster's own player page. CloudStream's `loadExtractor` resolves it.
     */
    private suspend fun extract(
        playerUrl: String,
        referer: String,
        language: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val raw = mutableListOf<ExtractorLink>()
        // The option is a wrapper on pelicinehd.com, and no extractor understands that host, so
        // the wrapper page is opened first and its real embed is what gets extracted. This is why
        // the plugin used to answer "Enlaces no encontrados" even on pages full of players.
        val direct = try {
            PeliCineHDMarkup.embeddedUrl(fetch(playerUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (direct != null) {
            loadExtractor(direct, playerUrl, subtitleCallback) { link -> raw.add(link) }
        }
        if (raw.isEmpty()) {
            loadExtractor(playerUrl, referer, subtitleCallback) { link -> raw.add(link) }
        }
        // The audio track lives in the option label, so it is made visible in the link name.
        return raw.map { link ->
            if (language.isBlank() || link.name.contains(language, true)) {
                link
            } else {
                newExtractorLink(
                    source = link.source,
                    name = "${link.name} [$language]",
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            }
        }
    }
}
