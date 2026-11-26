package com.menorah.pelisjuanita

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

class PelisJuanitaProvider(
    private val appContext: Context? = null,
    private val sessionStore: PelisJuanitaSessionStore =
        appContext?.let { AndroidPelisJuanitaSessionStore(it) } ?: InMemoryPelisJuanitaSessionStore(),
) : MainAPI() {

    override var mainUrl = PelisJuanitaMarkup.MAIN_URL
    override var name = "PelisJuanita"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val getMainPageTimeoutMs: Long? = 60_000L
    override val searchTimeoutMs: Long? = 30_000L
    override val quickSearchTimeoutMs: Long? = 25_000L
    override val loadTimeoutMs: Long? = 45_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    override val mainPage = mainPageOf(
        "pelis:estrenos:" to "Películas Estrenos",
        "pelis:ultimas-agregadas:" to "Últimas Películas",
        "pelis:populares:" to "Películas Populares",
        "series:ultimos-episodios:" to "Últimos Episodios",
        "series:estrenos:" to "Series Estrenos",
        "series:ultimas-agregadas:" to "Últimas Series",
        "series:populares:" to "Series Populares",
        "pelis:genero:acci%C3%B3n" to "Acción",
        "pelis:genero:animaci%C3%B3n" to "Animación",
        "pelis:genero:anime" to "Anime (Películas)",
        "series:genero:anime" to "Anime (Series)",
        "series:genero:dorama" to "Doramas",
        "pelis:genero:aventura" to "Aventura",
        "pelis:genero:ficci%C3%B3n" to "Ciencia Ficción",
        "pelis:genero:comedia" to "Comedia",
        "pelis:genero:crimen" to "Crimen",
        "pelis:genero:drama" to "Drama",
        "pelis:genero:fantas%C3%ADa" to "Fantasía",
        "pelis:genero:suspense" to "Suspenso",
        "pelis:genero:terror" to "Terror",
        "pelis:audio:latino" to "Audio Latino",
        "pelis:audio:espa%C3%B1ol" to "Audio Español",
        "pelis:audio:subtitulado" to "Subtituladas",
        "series:plataforma:netflix" to "Netflix (Series)",
        "series:plataforma:hbo-max" to "HBO+ (Series)",
        "series:plataforma:disney-plus" to "Disney+ (Series)",
        "series:plataforma:amazon-video" to "Prime Video (Series)",
    )

    private data class CachedText(val text: String, val storedAtMs: Long)
    private data class CachedSearchPage(val page: PelisJuanitaSearchResultPage, val storedAtMs: Long)

    private val htmlCache = ConcurrentHashMap<String, CachedText>()
    private val publicCatalogCache = ConcurrentHashMap<Int, CachedSearchPage>()
    private val seriesIdCache = ConcurrentHashMap<String, Long>()
    private val siteGate = Semaphore(4)
    private val seasonGate = Semaphore(4)
    private val linkGate = Semaphore(4)

    private data class FetchedSitePage(
        val code: Int,
        val finalUrl: String,
        val text: String,
        val gated: Boolean,
    )

    private suspend fun fetchSite(
        url: String,
        referer: String = "$mainUrl/",
        postForm: Map<String, String>? = null,
        isAjax: Boolean = false,
        useCache: Boolean = true,
    ): FetchedSitePage {
        val cacheKey = if (postForm == null) url else "$url#${postForm.entries.joinToString("&")}"
        if (useCache) {
            val cached = htmlCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.storedAtMs < 90_000L) {
                return FetchedSitePage(200, url, cached.text, gated = false)
            }
        }

        val session = sessionStore.read()
        if (!session.hasPhpSession()) {
            return FetchedSitePage(302, "$mainUrl/inicio.php", "", gated = true)
        }

        val headers = PelisJuanitaSessionHelper.buildRequestHeaders(session, referer = referer, isAjax = isAjax)
        return siteGate.withPermit {
            var code = 0
            var finalUrl = url
            var body = ""
            var error: Throwable? = null

            try {
                val res = if (postForm != null) {
                    app.post(url, headers = headers, data = postForm, timeout = 18L)
                } else {
                    app.get(url, headers = headers, timeout = 18L)
                }
                code = res.code
                finalUrl = res.url
                body = res.text
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = t
            }

            val webCtx = appContext
            if (webCtx != null &&
                (error != null || PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(code, body))
            ) {
                val webRes = runCatching {
                    if (postForm != null) {
                        PelisJuanitaWebTransport.post(webCtx, url, referer, postForm, session.userAgent.takeIf { it.isNotBlank() })
                    } else {
                        PelisJuanitaWebTransport.get(webCtx, url, referer, session.userAgent.takeIf { it.isNotBlank() })
                    }
                }.getOrNull()
                if (webRes != null) {
                    code = webRes.code
                    finalUrl = webRes.url
                    body = webRes.text
                }
            }

            val gated = PelisJuanitaSessionHelper.looksLikeVerificationGate(code, finalUrl, body)
            if (gated) {
                sessionStore.clear()
                htmlCache.clear()
                return@withPermit FetchedSitePage(code, finalUrl, body, gated = true)
            }

            if (code in 200..299 && body.isNotBlank() && useCache) {
                htmlCache[cacheKey] = CachedText(body, System.currentTimeMillis())
            }
            FetchedSitePage(code, finalUrl, body, gated = false)
        }
    }

    private suspend fun fetchPublicJson(url: String): String? {
        val headers = mapOf(
            "User-Agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            "Referer" to "$mainUrl/",
        )
        return runCatching {
            val res = app.get(url, headers = headers, timeout = 15L)
            if (res.isSuccessful) res.text else null
        }.getOrNull()
    }

    private suspend fun fetchPublicCatalogPage(page: Int): PelisJuanitaSearchResultPage? {
        val safePage = page.coerceAtLeast(1)
        val cached = publicCatalogCache[safePage]
        if (cached != null && System.currentTimeMillis() - cached.storedAtMs < 60_000L) {
            return cached.page
        }
        val raw = fetchPublicJson(PelisJuanitaMarkup.buildSearchApiUrl("%", safePage)) ?: return null
        val parsed = PelisJuanitaMarkup.parseSearchJson(raw) ?: return null
        publicCatalogCache[safePage] = CachedSearchPage(parsed, System.currentTimeMillis())
        return parsed
    }

    private suspend fun lookupPublicRecord(slug: String): PelisJuanitaSearchRecord? {
        val query = slug.replace('-', ' ').trim().ifEmpty { slug }
        val raw = fetchPublicJson(PelisJuanitaMarkup.buildSearchApiUrl(query, 1)) ?: return null
        val parsed = PelisJuanitaMarkup.parseSearchJson(raw) ?: return null
        return parsed.items.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
            ?: parsed.items.firstOrNull()
    }

    private fun toSearchResponse(card: PelisJuanitaCatalogCard): SearchResponse {
        val tvType = when (card.kind) {
            PelisJuanitaItemKind.Movie -> TvType.Movie
            PelisJuanitaItemKind.Series, PelisJuanitaItemKind.Episode -> TvType.TvSeries
        }
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                this.posterUrl = card.posterUrl
                this.year = card.year
                this.score = Score.from10(card.rating)
            }
        } else {
            newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                this.posterUrl = card.posterUrl
                this.year = card.year
                this.score = Score.from10(card.rating)
            }
        }
    }

    private fun toSearchResponse(rec: PelisJuanitaSearchRecord): SearchResponse {
        return if (rec.kind == PelisJuanitaItemKind.Series) {
            newTvSeriesSearchResponse(rec.title, rec.url, TvType.TvSeries) {
                this.posterUrl = rec.posterUrl
                this.year = rec.year
                this.score = Score.from10(rec.rating)
            }
        } else {
            newMovieSearchResponse(rec.title, rec.url, TvType.Movie) {
                this.posterUrl = rec.posterUrl
                this.year = rec.year
                this.score = Score.from10(rec.rating)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val req = PelisJuanitaMarkup.buildCatalogRequest(request.data, page)
        val referer = if (req.isMovies) "$mainUrl/movies/" else "$mainUrl/series/"
        val fetched = fetchSite(
            url = req.url,
            referer = referer,
            postForm = req.postForm,
            isAjax = true,
            useCache = true,
        )
        if (!fetched.gated && fetched.code in 200..299 && fetched.text.isNotBlank()) {
            val parsed = PelisJuanitaMarkup.parseCatalogHtml(
                fetched.text,
                currentPage = page,
                isRecentEpisodes = req.isRecentEpisodes,
            )
            if (parsed.items.isNotEmpty()) {
                return newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = parsed.items.map(::toSearchResponse),
                        isHorizontalImages = request.horizontalImages,
                    ),
                    hasNext = parsed.hasNext,
                )
            }
        }

        // Public catalog fallback via /tv/app/buscar.php before human verification
        val publicPage = fetchPublicCatalogPage(page) ?: return null
        val wantSeries = !req.isMovies
        val filtered = if (wantSeries) {
            val direct = publicPage.items.filter { it.kind == PelisJuanitaItemKind.Series }
            if (direct.isNotEmpty()) {
                direct
            } else if (page == 1) {
                (2..4).flatMap { extra ->
                    fetchPublicCatalogPage(extra)?.items?.filter { it.kind == PelisJuanitaItemKind.Series }.orEmpty()
                }.distinctBy { it.url }
            } else {
                emptyList()
            }
        } else {
            val movies = publicPage.items.filter { it.kind == PelisJuanitaItemKind.Movie }
            movies.ifEmpty { publicPage.items }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = filtered.map(::toSearchResponse),
                isHorizontalImages = request.horizontalImages,
            ),
            hasNext = publicPage.hasNext,
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val rawJson = fetchPublicJson(PelisJuanitaMarkup.buildSearchApiUrl(trimmed, page))
        val jsonPage = rawJson?.let { PelisJuanitaMarkup.parseSearchJson(it) }
        if (jsonPage != null) {
            return newSearchResponseList(
                list = jsonPage.items.map(::toSearchResponse),
                hasNext = jsonPage.hasNext,
            )
        }

        // Fallback to authenticated HTML search if buscar.php is temporarily unavailable
        if (sessionStore.read().hasPhpSession()) {
            val (mRes, sRes) = coroutineScope {
                val m = async {
                    fetchSite(PelisJuanitaMarkup.buildMovieSearchAjaxUrl(trimmed, page), "$mainUrl/movies/", isAjax = true)
                }
                val s = async {
                    fetchSite(PelisJuanitaMarkup.buildSeriesSearchAjaxUrl(trimmed, page), "$mainUrl/series/", isAjax = true)
                }
                m.await() to s.await()
            }
            val cards = buildList {
                if (!mRes.gated && mRes.text.isNotBlank()) addAll(PelisJuanitaMarkup.parseCatalogHtml(mRes.text, page).items)
                if (!sRes.gated && sRes.text.isNotBlank()) addAll(PelisJuanitaMarkup.parseCatalogHtml(sRes.text, page).items)
            }.distinctBy { it.url }
            val hasMore = (mRes.text.isNotBlank() && PelisJuanitaMarkup.parseCatalogHtml(mRes.text, page).hasNext) ||
                (sRes.text.isNotBlank() && PelisJuanitaMarkup.parseCatalogHtml(sRes.text, page).hasNext)
            return newSearchResponseList(cards.map(::toSearchResponse), hasNext = hasMore)
        }

        return null
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun load(url: String): LoadResponse? {
        val normalizedUrl = PelisJuanitaMarkup.resolveSiteUrl(url) ?: return null
        val target = PelisJuanitaMarkup.parseTargetUrl(normalizedUrl) ?: return null

        if (target.isSeries) {
            val parentUrl = target.parentSeriesUrl
            val shellRes = fetchSite(
                url = PelisJuanitaMarkup.buildSerieShellUrl(target.slug),
                referer = "$mainUrl/series/",
                isAjax = false,
            )
            if (shellRes.gated) {
                throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
            }

            val serieId = PelisJuanitaMarkup.extractSeriesIdFromShell(shellRes.text)
                ?: lookupPublicRecord(target.slug)?.id
            if (serieId != null && serieId > 0) {
                seriesIdCache[target.slug] = serieId
            }

            val initSeason = target.season ?: 1
            val initEpisode = target.episode ?: 1
            val infoRes = fetchSite(
                url = PelisJuanitaMarkup.buildSerieInfoEpisodeUrl(target.slug, initSeason, initEpisode, serieId),
                referer = parentUrl,
                isAjax = true,
            )
            if (infoRes.gated) {
                throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
            }

            val detail = PelisJuanitaMarkup.parseSeriesDetailHtml(infoRes.text, parentUrl) ?: return null
            val allEpisodes = Collections.synchronizedList(detail.episodes.toMutableList())

            val extraSeasons = detail.seasons.filter { it != detail.currentSeason }
            if (extraSeasons.isNotEmpty()) {
                coroutineScope {
                    extraSeasons.map { seasonNum ->
                        async(Dispatchers.IO) {
                            seasonGate.withPermit {
                                val sRes = fetchSite(
                                    url = PelisJuanitaMarkup.buildSerieInfoSeasonUrl(
                                        target.slug,
                                        seasonNum,
                                        detail.currentSeason,
                                        detail.currentEpisode,
                                        serieId,
                                    ),
                                    referer = parentUrl,
                                    isAjax = true,
                                )
                                if (!sRes.gated && sRes.text.isNotBlank()) {
                                    allEpisodes.addAll(PelisJuanitaMarkup.parseSeasonEpisodesHtml(sRes.text, target.slug))
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            val sortedEpisodes = allEpisodes
                .distinctBy { it.season to it.episode }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.title
                        this.season = ep.season
                        this.episode = ep.episode
                        this.runTime = ep.runtimeMinutes
                        this.description = ep.description
                        ep.releaseDate?.let { this.addDate(it, "dd/MM/yyyy") }
                    }
                }

            return newTvSeriesLoadResponse(detail.title, parentUrl, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backdropUrl
                this.plot = detail.synopsis
                this.year = detail.year
                this.duration = detail.durationMinutes
                this.score = Score.from10(detail.rating)
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) {
                    addActors(detail.actors.map { Actor(it.name, it.imageUrl) })
                }
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map(::toSearchResponse)
                }
                detail.trailerUrl?.let { addTrailer(it) }
            }
        }

        // Movie
        val movieRes = fetchSite(
            url = PelisJuanitaMarkup.buildMovieInfoUrl(target.slug),
            referer = target.publicUrl,
            isAjax = true,
        )
        if (!movieRes.gated && movieRes.text.isNotBlank()) {
            val detail = PelisJuanitaMarkup.parseMovieDetailHtml(movieRes.text, target.publicUrl)
            if (detail != null) {
                return newMovieLoadResponse(detail.title, target.publicUrl, TvType.Movie, target.publicUrl) {
                    this.posterUrl = detail.posterUrl
                    this.backgroundPosterUrl = detail.backdropUrl
                    this.plot = detail.synopsis
                    this.year = detail.year
                    this.duration = detail.durationMinutes
                    this.score = Score.from10(detail.rating)
                    if (detail.genres.isNotEmpty()) this.tags = detail.genres
                    if (detail.actors.isNotEmpty()) {
                        addActors(detail.actors.map { Actor(it.name, it.imageUrl) })
                    }
                    if (detail.recommendations.isNotEmpty()) {
                        this.recommendations = detail.recommendations.map(::toSearchResponse)
                    }
                    detail.trailerUrl?.let { addTrailer(it) }
                }
            }
        }

        // Unverified movie fallback from public /tv/app/buscar.php metadata
        val rec = lookupPublicRecord(target.slug)
            ?: throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
        return newMovieLoadResponse(rec.title, target.publicUrl, TvType.Movie, target.publicUrl) {
            this.posterUrl = rec.posterUrl
            this.backgroundPosterUrl = rec.backdropUrl
            this.plot = rec.synopsis
            this.year = rec.year
            this.score = Score.from10(rec.rating)
            if (rec.genres.isNotEmpty()) this.tags = rec.genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!PelisJuanitaMarkup.isSiteUrl(data)) {
            val links = resolveSingleSource(
                PelisJuanitaStreamSource(
                    serverName = "",
                    languageLabel = "Latino",
                    qualityLabel = "HD",
                    url = data,
                ),
                subtitleCallback,
            )
            links.forEach(callback)
            return links.isNotEmpty()
        }

        val target = PelisJuanitaMarkup.parseTargetUrl(data) ?: return false
        val infoRes = if (target.isSeries) {
            val season = target.season ?: 1
            val episode = target.episode ?: 1
            var serieId = seriesIdCache[target.slug]
            if (serieId == null) {
                val shell = fetchSite(PelisJuanitaMarkup.buildSerieShellUrl(target.slug), "$mainUrl/series/")
                if (shell.gated) throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
                serieId = PelisJuanitaMarkup.extractSeriesIdFromShell(shell.text)
                    ?: lookupPublicRecord(target.slug)?.id
                if (serieId != null && serieId > 0) seriesIdCache[target.slug] = serieId
            }
            fetchSite(
                url = PelisJuanitaMarkup.buildSerieInfoEpisodeUrl(target.slug, season, episode, serieId),
                referer = target.publicUrl,
                isAjax = true,
                useCache = true,
            )
        } else {
            fetchSite(
                url = PelisJuanitaMarkup.buildMovieInfoUrl(target.slug),
                referer = target.publicUrl,
                isAjax = true,
                useCache = true,
            )
        }

        if (infoRes.gated) {
            throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
        }

        val rawSources = PelisJuanitaMarkup.parseStreamSourcesHtml(infoRes.text)
        if (rawSources.isEmpty()) return false

        val langPriority = mapOf("Latino" to 0, "Español" to 1, "Subtitulado" to 2)
        val serverPriority = mapOf(
            "vimeus" to 0,
            "fastserver" to 1,
            "earnvids" to 2,
            "vidhide" to 2,
            "streamwish" to 3,
            "voe" to 4,
            "vidara" to 5,
            "dood" to 6,
            "lulustream" to 7,
            "savefiles" to 8,
            "byse" to 9,
            "mixdrop" to 10,
            "waaw" to 90,
        )

        val orderedSources = rawSources
            .filterNot { it.serverName.equals("waaw", ignoreCase = true) || it.url.contains("waaw.to", ignoreCase = true) }
            .distinctBy { it.url }
            .sortedWith(
                compareBy(
                    { langPriority[it.languageLabel] ?: 5 },
                    { serverPriority[it.serverName.lowercase()] ?: 50 },
                ),
            )
            .take(16)

        val emittedCount = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            orderedSources.map { source ->
                async(Dispatchers.IO) {
                    linkGate.withPermit {
                        withTimeoutOrNull(18_000L) {
                            val links = resolveSingleSource(source, subtitleCallback)
                            for (link in links) {
                                callback(labelExtractorLink(source.languageLabel, source.serverName, source.qualityLabel, link))
                                emittedCount.incrementAndGet()
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return emittedCount.get() > 0
    }

    private suspend fun resolveSingleSource(
        source: PelisJuanitaStreamSource,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val sink: (ExtractorLink) -> Unit = { collected.add(it) }
        val referer = "$mainUrl/"
        val lowerUrl = source.url.lowercase()
        val lowerSrv = source.serverName.lowercase()

        try {
            when {
                lowerSrv == "vimeus" || lowerUrl.contains("vimeus.") -> {
                    PelisJuanitaVimeusExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                }
                lowerUrl.contains("vimeos.") -> {
                    PelisJuanitaVimeosExtractor.extract(source.url, referer, "Vimeos", subtitleCallback, sink)
                }
                lowerSrv == "fastserver" || lowerUrl.contains("fortamomar.") -> {
                    if (lowerUrl.contains("seriesplayer")) {
                        PelisJuanitaFastserverSeriesExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    } else {
                        PelisJuanitaFastserverMoviesExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    }
                }
                lowerSrv == "voe" || lowerUrl.contains("voe.") -> {
                    PelisJuanitaVoeExtractor.extract(source.url, referer, "Voe", subtitleCallback, sink)
                }
                lowerSrv == "earnvids" ||
                    lowerUrl.contains("morencius") ||
                    lowerUrl.contains("callistanise") ||
                    lowerUrl.contains("minochinos") ||
                    lowerUrl.contains("vidhide") -> {
                    PelisJuanitaVidHideExtractor.extract(source.url, referer, "Earnvids", subtitleCallback, sink)
                }
                lowerSrv == "streamwish" ||
                    lowerUrl.contains("streamwish") ||
                    lowerUrl.contains("hglink") ||
                    lowerUrl.contains("medixiru") ||
                    lowerUrl.contains("vsembed") -> {
                    PelisJuanitaStreamWishExtractor.extract(source.url, referer, "StreamWish", subtitleCallback, sink)
                }
                lowerSrv == "savefiles" || lowerUrl.contains("savefiles.") -> {
                    PelisJuanitaSavefilesExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                }
                lowerSrv == "lulustream" || lowerUrl.contains("lulustream") || lowerUrl.contains("lplaygo") -> {
                    PelisJuanitaLplaygoExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    if (collected.isEmpty()) loadExtractor(source.url, referer, subtitleCallback, sink)
                }
                lowerSrv == "byse" || lowerUrl.contains("bysesukior") -> {
                    PelisJuanitaByseExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    if (collected.isEmpty()) loadExtractor(source.url, referer, subtitleCallback, sink)
                }
                lowerUrl.contains("mixdrop.top") || lowerUrl.contains("mxdrop.click") -> {
                    loadExtractor(source.url, referer, subtitleCallback, sink)
                    if (collected.isEmpty()) {
                        PelisJuanitaMixDropTopExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    }
                }
                else -> {
                    loadExtractor(source.url, referer, subtitleCallback, sink)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        return synchronized(collected) { collected.toList() }
    }

    private suspend fun labelExtractorLink(
        langLabel: String,
        serverName: String,
        reportedQuality: String,
        link: ExtractorLink,
    ): ExtractorLink {
        val cleanSource = link.name.ifBlank { serverName }
        val taggedName = if (cleanSource.startsWith("[")) cleanSource else "[$langLabel] $cleanSource"
        val resolvedQuality = if (link.quality != Qualities.Unknown.value) {
            link.quality
        } else {
            getQualityFromName(reportedQuality)
        }
        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, taggedName, link.url, link.type, link.uuid) {
                referer = link.referer
                quality = resolvedQuality
                headers = link.headers
                extractorData = link.extractorData
                audioTracks = link.audioTracks
                kid = link.kid
                key = link.key
                kty = link.kty
                keyRequestParameters = link.keyRequestParameters
                licenseUrl = link.licenseUrl
            }
        }
        return newExtractorLink(link.source, taggedName, link.url, link.type) {
            referer = link.referer
            quality = resolvedQuality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }
}
