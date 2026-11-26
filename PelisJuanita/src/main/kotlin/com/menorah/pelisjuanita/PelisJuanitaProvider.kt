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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
    private val publicCatalogMutex = Mutex()
    private val movieIdCache = ConcurrentHashMap<String, Long>()
    private val seriesIdCache = ConcurrentHashMap<String, Long>()
    private val siteGate = Semaphore(4)
    private val seasonGate = Semaphore(4)
    private val linkGate = Semaphore(4)

    @Volatile
    internal var testWebResponse: ((android.webkit.WebResourceRequest) -> android.webkit.WebResourceResponse?)? = null

    fun clearCaches() {
        htmlCache.clear()
        publicCatalogCache.clear()
        movieIdCache.clear()
        seriesIdCache.clear()
    }

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

        val headers = PelisJuanitaSessionHelper.buildRequestHeaders(
            session,
            referer = referer,
            isAjax = isAjax,
            includeCookieHeader = false,
        )
        val cookies = session.cookieMap()
        return siteGate.withPermit {
            var code = 0
            var finalUrl = url
            var body = ""
            var error: Throwable? = null
            val activeTestResponse = testWebResponse

            if (activeTestResponse == null) {
                try {
                    val res = if (postForm != null) {
                        app.post(url, headers = headers, cookies = cookies, data = postForm, timeout = 18L)
                    } else {
                        app.get(url, headers = headers, cookies = cookies, timeout = 18L)
                    }
                    code = res.code
                    finalUrl = res.url
                    body = res.text
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    error = t
                }
            } else {
                code = 403
            }

            val webCtx = appContext
            if (webCtx != null &&
                PelisJuanitaSessionHelper.shouldTryWebTransport(code, finalUrl, body, error)
            ) {
                val webRes = runCatching {
                    val ua = session.userAgent.takeIf { it.isNotBlank() }
                    if (postForm != null) {
                        PelisJuanitaWebTransport.post(
                            webCtx,
                            url,
                            referer,
                            postForm,
                            userAgent = ua,
                            seedCookieHeader = session.cookieHeader,
                            testResponse = activeTestResponse,
                        )
                    } else {
                        PelisJuanitaWebTransport.get(
                            webCtx,
                            url,
                            referer,
                            userAgent = ua,
                            seedCookieHeader = session.cookieHeader,
                            testResponse = activeTestResponse,
                        )
                    }
                }.getOrNull()
                if (webRes != null) {
                    code = webRes.code
                    finalUrl = webRes.url
                    body = webRes.text
                    if (activeTestResponse == null &&
                        webRes.cookieHeader.isNotBlank() &&
                        code in 200..299 &&
                        !PelisJuanitaSessionHelper.looksLikeVerificationGate(code, finalUrl, body) &&
                        !PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(code, body)
                    ) {
                        val mergedCookies = PelisJuanitaSessionHelper.mergeCookieHeaders(
                            session.cookieHeader,
                            webRes.cookieHeader,
                        )
                        if (mergedCookies != session.cookieHeader) {
                            sessionStore.save(session.copy(cookieHeader = mergedCookies))
                        }
                    }
                }
            }

            val gated = PelisJuanitaSessionHelper.looksLikeVerificationGate(code, finalUrl, body)
            if (gated) {
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
            "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            "Referer" to "$mainUrl/",
        )
        return runCatching {
            val res = app.get(url, headers = headers, timeout = 15L)
            if (res.isSuccessful) res.text else null
        }.getOrNull()
    }

    private fun cachePublicRecords(items: List<PelisJuanitaSearchRecord>) {
        for (rec in items) {
            if (rec.id <= 0L) continue
            if (rec.kind == PelisJuanitaItemKind.Series) {
                seriesIdCache.putIfAbsent(rec.slug, rec.id)
            } else {
                movieIdCache.putIfAbsent(rec.slug, rec.id)
            }
        }
    }

    private suspend fun fetchPublicCatalogPage(page: Int): PelisJuanitaSearchResultPage? {
        val safePage = page.coerceAtLeast(1)
        publicCatalogCache[safePage]?.let { cached ->
            if (System.currentTimeMillis() - cached.storedAtMs < 60_000L) return cached.page
        }
        return publicCatalogMutex.withLock {
            publicCatalogCache[safePage]?.let { cached ->
                if (System.currentTimeMillis() - cached.storedAtMs < 60_000L) return@withLock cached.page
            }
            val raw = fetchPublicJson(PelisJuanitaMarkup.buildSearchApiUrl("%", safePage)) ?: return@withLock null
            val parsed = PelisJuanitaMarkup.parseSearchJson(raw) ?: return@withLock null
            cachePublicRecords(parsed.items)
            publicCatalogCache[safePage] = CachedSearchPage(parsed, System.currentTimeMillis())
            parsed
        }
    }

    private suspend fun lookupPublicRecord(slug: String): PelisJuanitaSearchRecord? {
        val words = slug.split('-').map { it.trim() }.filter { it.isNotEmpty() }
        val fullQuery = words.joinToString(" ").ifEmpty { slug }
        val queries = buildList {
            add(fullQuery)
            val longest = words.filter { it.length >= 4 }.maxByOrNull { it.length }
            if (longest != null && !longest.equals(fullQuery, ignoreCase = true)) {
                add(longest)
            }
            val first = words.firstOrNull()
            if (first != null && !first.equals(fullQuery, ignoreCase = true) && !first.equals(longest, ignoreCase = true)) {
                add(first)
            }
        }
        var firstAny: PelisJuanitaSearchRecord? = null
        for (q in queries) {
            val raw = fetchPublicJson(PelisJuanitaMarkup.buildSearchApiUrl(q, 1)) ?: continue
            val parsed = PelisJuanitaMarkup.parseSearchJson(raw) ?: continue
            cachePublicRecords(parsed.items)
            val exact = parsed.items.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
            if (exact != null) return exact
            if (firstAny == null && q == fullQuery) {
                firstAny = parsed.items.firstOrNull()
            }
        }
        return firstAny
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
            fetchPublicSeriesItems(page, publicPage)
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
            hasNext = if (wantSeries) filtered.isNotEmpty() else publicPage.hasNext,
        )
    }

    private suspend fun fetchPublicSeriesItems(
        requestedPage: Int,
        firstPage: PelisJuanitaSearchResultPage,
    ): List<PelisJuanitaSearchRecord> {
        val direct = firstPage.items.filter { it.kind == PelisJuanitaItemKind.Series }
        if (direct.isNotEmpty()) return direct

        val total = firstPage.totalPages
        if (total <= 1) return emptyList()

        // buscar.php lists movies first (~75-85% of pages) and series toward the end; start from the tail.
        val probePages = listOf(
            (total - requestedPage + 1).coerceIn(1, total),
            ((total * 5) / 6 + requestedPage - 1).coerceIn(1, total),
            ((total * 3) / 4 + requestedPage - 1).coerceIn(1, total),
            (requestedPage + 10).coerceIn(1, total),
        ).distinct()

        for (p in probePages) {
            val candidate = fetchPublicCatalogPage(p)?.items?.filter { it.kind == PelisJuanitaItemKind.Series }.orEmpty()
            if (candidate.isNotEmpty()) return candidate
        }
        return emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = PelisJuanitaMarkup.normalizeSearchQuery(query)
        if (trimmed.isEmpty()) return null

        val rawJson = fetchPublicJson(PelisJuanitaMarkup.buildSearchApiUrl(trimmed, page))
        val jsonPage = rawJson?.let { PelisJuanitaMarkup.parseSearchJson(it) }
        if (jsonPage != null && (jsonPage.items.isNotEmpty() || !sessionStore.read().hasPhpSession())) {
            cachePublicRecords(jsonPage.items)
            return newSearchResponseList(
                list = jsonPage.items.map(::toSearchResponse),
                hasNext = jsonPage.hasNext,
            )
        }

        // Fallback to authenticated HTML search if buscar.php is unavailable or returned empty
        if (sessionStore.read().hasPhpSession()) {
            val unifiedAjax = fetchSite(
                PelisJuanitaMarkup.buildUnifiedSearchAjaxUrl(trimmed, page),
                "$mainUrl/movies/",
                isAjax = true,
            )
            val unifiedParsed = if (!unifiedAjax.gated && unifiedAjax.text.isNotBlank()) {
                PelisJuanitaMarkup.parseCatalogHtml(unifiedAjax.text, page)
            } else {
                null
            }
            if (unifiedParsed != null && unifiedParsed.items.isNotEmpty()) {
                return newSearchResponseList(unifiedParsed.items.map(::toSearchResponse), hasNext = unifiedParsed.hasNext)
            }

            val searchPageRes = fetchSite(
                PelisJuanitaMarkup.buildSearchPageUrl(trimmed, page),
                "$mainUrl/movies/",
                isAjax = false,
            )
            val searchPageParsed = if (!searchPageRes.gated && searchPageRes.text.isNotBlank()) {
                PelisJuanitaMarkup.parseCatalogHtml(searchPageRes.text, page)
            } else {
                null
            }
            if (searchPageParsed != null && searchPageParsed.items.isNotEmpty()) {
                return newSearchResponseList(searchPageParsed.items.map(::toSearchResponse), hasNext = searchPageParsed.hasNext)
            }

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
            if (cards.isNotEmpty() || jsonPage == null) {
                return newSearchResponseList(cards.map(::toSearchResponse), hasNext = hasMore)
            }
        }

        if (jsonPage != null) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }
        return null
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    private suspend fun resolveMovieIdAndShell(slug: String, fetchShellIfMissing: Boolean): Pair<Long?, FetchedSitePage?> {
        val cached = movieIdCache[slug]
        if (cached != null && cached > 0) return cached to null

        val pub = lookupPublicRecord(slug)
        if (pub != null && pub.id > 0) {
            movieIdCache[slug] = pub.id
            return pub.id to null
        }

        if (!fetchShellIfMissing) return null to null
        val shell = fetchSite(
            url = PelisJuanitaMarkup.buildMovieShellUrl(slug),
            referer = "$mainUrl/movies/",
            isAjax = false,
        )
        if (shell.gated) {
            throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
        }
        val extracted = PelisJuanitaMarkup.extractMovieIdFromShell(shell.text)
        if (extracted != null && extracted > 0) {
            movieIdCache[slug] = extracted
        }
        return extracted to shell
    }

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

            val shellId = PelisJuanitaMarkup.extractSeriesIdFromShell(shellRes.text)
            val cachedId = seriesIdCache[target.slug]
            val publicRec = if (shellId == null || cachedId == null) lookupPublicRecord(target.slug) else null
            val serieId = shellId ?: cachedId ?: publicRec?.id
            if (serieId != null && serieId > 0) {
                seriesIdCache[target.slug] = serieId
            }

            val initSeason = target.season ?: 1
            val initEpisode = target.episode ?: 1
            var infoRes = fetchSite(
                url = PelisJuanitaMarkup.buildSerieInfoEpisodeUrl(target.slug, initSeason, initEpisode, serieId),
                referer = parentUrl,
                isAjax = true,
            )
            if (infoRes.gated) {
                throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
            }

            var detail = PelisJuanitaMarkup.parseSeriesDetailHtml(infoRes.text, parentUrl)
            if (detail == null) {
                val seasonModeRes = fetchSite(
                    url = PelisJuanitaMarkup.buildSerieInfoSeasonModeUrl(target.slug, initSeason, serieId),
                    referer = parentUrl,
                    isAjax = true,
                )
                if (seasonModeRes.gated) {
                    throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
                }
                if (seasonModeRes.text.isNotBlank()) {
                    detail = PelisJuanitaMarkup.parseSeriesDetailHtml(seasonModeRes.text, parentUrl)
                    if (detail != null) infoRes = seasonModeRes
                }
            }
            if (detail == null && shellRes.text.isNotBlank()) {
                detail = PelisJuanitaMarkup.parseSeriesDetailHtml(shellRes.text, parentUrl)
            }
            if (detail == null) {
                if (infoRes.code == 403 || infoRes.code == 503 || infoRes.text.isBlank()) {
                    throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
                }
                return null
            }

            val allEpisodes = Collections.synchronizedList(detail.episodes.toMutableList())
            val knownSeasons = linkedSetOf<Int>().apply {
                addAll(detail.seasons)
                add(detail.currentSeason.coerceAtLeast(1))
                if (initSeason > 0) add(initSeason)
                publicRec?.seasonsCount?.let { sc ->
                    for (s in 1..sc.coerceAtMost(40)) add(s)
                }
            }.sorted()

            // Always fetch every season whose episodes are absent (including currentSeason when #ulTemporada awaits cargarEpisodiosDeTemporada).
            val missingSeasons = knownSeasons.filter { s -> allEpisodes.none { it.season == s } }
            if (missingSeasons.isNotEmpty()) {
                coroutineScope {
                    missingSeasons.map { seasonNum ->
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
                                var seasonEpisodes = if (!sRes.gated && sRes.text.isNotBlank()) {
                                    PelisJuanitaMarkup.parseSeasonEpisodesHtml(sRes.text, target.slug, defaultSeason = seasonNum)
                                } else {
                                    emptyList()
                                }
                                if (seasonEpisodes.isEmpty() && !sRes.gated) {
                                    val epFallback = fetchSite(
                                        url = PelisJuanitaMarkup.buildSerieInfoEpisodeUrl(target.slug, seasonNum, 1, serieId),
                                        referer = parentUrl,
                                        isAjax = true,
                                    )
                                    if (!epFallback.gated && epFallback.text.isNotBlank()) {
                                        seasonEpisodes = PelisJuanitaMarkup.parseSeasonEpisodesHtml(
                                            epFallback.text,
                                            target.slug,
                                            defaultSeason = seasonNum,
                                        )
                                    }
                                }
                                if (seasonEpisodes.isNotEmpty()) {
                                    allEpisodes.addAll(seasonEpisodes)
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

            if (sortedEpisodes.isEmpty() && (infoRes.code !in 200..299 || infoRes.text.isBlank())) {
                throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
            }

            return newTvSeriesLoadResponse(detail.title, parentUrl, TvType.TvSeries, sortedEpisodes) {
                val resolvedPoster = detail.posterUrl ?: publicRec?.posterUrl
                this.posterUrl = resolvedPoster
                this.backgroundPosterUrl = detail.backdropUrl ?: publicRec?.backdropUrl ?: resolvedPoster
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
        val hasSession = sessionStore.read().hasPhpSession()
        val (movieId, movieShell) = if (hasSession) {
            resolveMovieIdAndShell(target.slug, fetchShellIfMissing = true)
        } else {
            movieIdCache[target.slug] to null
        }
        val movieRes = fetchSite(
            url = PelisJuanitaMarkup.buildMovieInfoUrl(target.slug, movieId),
            referer = target.publicUrl,
            isAjax = true,
        )
        if (!movieRes.gated && movieRes.text.isNotBlank()) {
            val detail = PelisJuanitaMarkup.parseMovieDetailHtml(movieRes.text, target.publicUrl)
                ?: movieShell?.text?.takeIf { it.isNotBlank() }?.let { PelisJuanitaMarkup.parseMovieDetailHtml(it, target.publicUrl) }
            if (detail != null) {
                val fallbackRec = if (detail.backdropUrl == null || detail.posterUrl == null) {
                    lookupPublicRecord(target.slug)
                } else {
                    null
                }
                val resolvedPoster = detail.posterUrl ?: fallbackRec?.posterUrl
                return newMovieLoadResponse(detail.title, target.publicUrl, TvType.Movie, target.publicUrl) {
                    this.posterUrl = resolvedPoster
                    this.backgroundPosterUrl = detail.backdropUrl ?: fallbackRec?.backdropUrl ?: resolvedPoster
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
            this.backgroundPosterUrl = rec.backdropUrl ?: rec.posterUrl
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
        var infoRes = if (target.isSeries) {
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
            val (movieId, _) = resolveMovieIdAndShell(target.slug, fetchShellIfMissing = sessionStore.read().hasPhpSession())
            fetchSite(
                url = PelisJuanitaMarkup.buildMovieInfoUrl(target.slug, movieId),
                referer = target.publicUrl,
                isAjax = true,
                useCache = true,
            )
        }

        if (infoRes.gated || infoRes.code == 403 || infoRes.code == 503) {
            throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
        }

        var rawSources = PelisJuanitaMarkup.parseStreamSourcesHtml(infoRes.text)
        if (rawSources.isEmpty() && !target.isSeries) {
            val movieShell = fetchSite(
                url = PelisJuanitaMarkup.buildMovieShellUrl(target.slug),
                referer = "$mainUrl/movies/",
                isAjax = false,
                useCache = false,
            )
            if (movieShell.gated) {
                throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
            }
            val shellId = PelisJuanitaMarkup.extractMovieIdFromShell(movieShell.text)
                ?: movieIdCache[target.slug]
                ?: lookupPublicRecord(target.slug)?.id
            if (shellId != null && shellId > 0) {
                movieIdCache[target.slug] = shellId
            }
            infoRes = fetchSite(
                url = PelisJuanitaMarkup.buildMovieInfoUrl(target.slug, shellId),
                referer = target.publicUrl,
                isAjax = true,
                useCache = false,
            )
            if (infoRes.gated || infoRes.code == 403 || infoRes.code == 503) {
                throw ErrorLoadingException(PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE)
            }
            rawSources = PelisJuanitaMarkup.parseStreamSourcesHtml(infoRes.text)
            if (rawSources.isEmpty() && movieShell.text.isNotBlank()) {
                rawSources = PelisJuanitaMarkup.parseStreamSourcesHtml(movieShell.text)
            }
        }
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
                lowerSrv == "voe" || lowerUrl.contains("voe.") || lowerUrl.contains("caseyseemavoid") -> {
                    PelisJuanitaVoeExtractor.extract(source.url, referer, "Voe", subtitleCallback, sink)
                }
                lowerSrv == "earnvids" ||
                    lowerUrl.contains("morencius") ||
                    lowerUrl.contains("callistanise") ||
                    lowerUrl.contains("minochinos") ||
                    lowerUrl.contains("movearnpre") ||
                    lowerUrl.contains("dingtezuni") ||
                    lowerUrl.contains("mivalyo") ||
                    lowerUrl.contains("taylorplayer") ||
                    lowerUrl.contains("vidhide") ||
                    lowerUrl.contains("filelions") ||
                    lowerUrl.contains("peytonepre") ||
                    lowerUrl.contains("smoothpre") ||
                    lowerUrl.contains("dhtpre") ||
                    lowerUrl.contains("ryderjet") -> {
                    PelisJuanitaVidHideExtractor.extract(source.url, referer, "Earnvids", subtitleCallback, sink)
                }
                lowerSrv == "streamwish" ||
                    lowerUrl.contains("streamwish") ||
                    lowerUrl.contains("hglink") ||
                    lowerUrl.contains("medixiru") ||
                    lowerUrl.contains("vsembed") ||
                    lowerUrl.contains("flaswish") ||
                    lowerUrl.contains("hlswish") ||
                    lowerUrl.contains("playerwish") ||
                    lowerUrl.contains("strwish") ||
                    lowerUrl.contains("dhcplay") -> {
                    PelisJuanitaStreamWishExtractor.extract(source.url, referer, "StreamWish", subtitleCallback, sink)
                }
                lowerSrv == "savefiles" || lowerUrl.contains("savefiles.") -> {
                    PelisJuanitaSavefilesExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                }
                lowerSrv == "lulustream" || lowerUrl.contains("lulustream") || lowerUrl.contains("lplaygo") -> {
                    PelisJuanitaLplaygoExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    if (collected.isEmpty()) loadExtractor(source.url, referer, subtitleCallback, sink)
                }
                lowerSrv == "byse" || lowerUrl.contains("byse") || lowerUrl.contains("n1mwq.org") -> {
                    PelisJuanitaByseExtractor().getUrl(source.url, referer, subtitleCallback, sink)
                    if (collected.isEmpty()) loadExtractor(source.url, referer, subtitleCallback, sink)
                }
                lowerSrv == "mixdrop" || lowerUrl.contains("mixdrop") || lowerUrl.contains("mxdrop") -> {
                    PelisJuanitaMixDropTopExtractor.extract(source.url, referer, "MixDrop", subtitleCallback, sink)
                    if (collected.isEmpty()) {
                        loadExtractor(source.url, referer, subtitleCallback, sink)
                    }
                }
                lowerUrl.contains("oisauh") -> {
                    PelisJuanitaOisauhExtractor().getUrl(source.url, referer, subtitleCallback, sink)
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
