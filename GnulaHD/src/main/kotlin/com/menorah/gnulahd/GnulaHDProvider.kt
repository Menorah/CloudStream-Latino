package com.menorah.gnulahd

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GnulaHDProvider : MainAPI() {
    override var mainUrl = GnulaHDMarkup.MAIN_URL
    override var name = "GnulaHD"
    override var lang = "es-419"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override val getMainPageTimeoutMs: Long? = 75_000L
    override val searchTimeoutMs: Long? = 30_000L
    override val quickSearchTimeoutMs: Long? = 20_000L
    override val loadTimeoutMs: Long? = 45_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    override val mainPage = mainPageOf(
        SECTION_HOME_WEEK to "Tendencias · Esta semana",
        SECTION_HOME_TODAY to "Tendencias · Hoy",
        "/recien-agregado/" to "Recién agregado",
        "/ver/peliculas/" to "Películas",
        "/ver/series/" to "Series",
        "/ver/anime/" to "Anime",
        "/ver/peliculas/latino/" to "Películas · Latino",
        "/ver/series/latino/" to "Series · Latino",
        "/ver/anime/latino/" to "Anime · Latino",
        "/ver/peliculas/subtitulado/" to "Películas · Subtitulado",
        "/ver/peliculas/castellano/" to "Películas · Castellano",
        "/ver/peliculas/?orderby=views" to "Películas más vistas",
        "/ver/series/?orderby=views" to "Series más vistas",
        "/ver/peliculas/?anio=2026" to "Estrenos 2026",
        "/proximos-estrenos/" to "Próximos Estrenos",
        "/genres/accion/" to "Acción",
        "/genres/animacion/" to "Animación",
        "/genres/ciencia-ficcion/" to "Ciencia Ficción",
        "/genres/comedia/" to "Comedia",
        "/genres/drama/" to "Drama",
        "/genres/suspenso/" to "Suspenso",
        "/genres/terror/" to "Terror",
    )

    private companion object {
        const val SECTION_HOME_WEEK = "HOME_WEEK"
        const val SECTION_HOME_TODAY = "HOME_TODAY"
        const val CACHE_TTL_MS = 120_000L
    }

    private data class CachedHtml(val text: String, val storedAtMs: Long)

    private val htmlCache = ConcurrentHashMap<String, CachedHtml>()
    private val nextRequestAllowedAtMs = AtomicLong(0L)
    private val siteGate = Semaphore(3)

    private val siteHeaders = mapOf(
        "User-Agent" to GnulaHDMarkup.DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend fun getText(
        url: String,
        referer: String = "$mainUrl/",
        accept: String? = null,
        timeoutSec: Long = 18L,
        useCache: Boolean = true,
    ): String? {
        val now = System.currentTimeMillis()
        if (useCache) {
            htmlCache[url]?.let { cached ->
                if (now - cached.storedAtMs < CACHE_TTL_MS) return cached.text
            }
        }

        val headers = if (accept != null) siteHeaders + ("Accept" to accept) else siteHeaders
        for (attempt in 0..2) {
            val waitMs = nextRequestAllowedAtMs.get() - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)

            var retryWaitMs = 0L
            val text = try {
                siteGate.withPermit {
                    val cooldown = nextRequestAllowedAtMs.get() - System.currentTimeMillis()
                    if (cooldown > 0) delay(cooldown)
                    nextRequestAllowedAtMs.updateAndGet { prev -> maxOf(prev, System.currentTimeMillis() + 80L) }
                    val res = app.get(url, referer = referer, headers = headers, timeout = timeoutSec)
                    when {
                        res.isSuccessful -> res.text
                        res.code == 429 || res.code in 500..599 -> {
                            val retrySec = res.headers["Retry-After"]?.trim()?.toLongOrNull()
                            retryWaitMs = ((retrySec?.times(1000L)) ?: (1200L * (attempt + 1))).coerceIn(800L, 5000L)
                            nextRequestAllowedAtMs.updateAndGet { prev ->
                                maxOf(prev, System.currentTimeMillis() + retryWaitMs)
                            }
                            null
                        }
                        else -> null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                retryWaitMs = 600L * (attempt + 1)
                null
            }

            if (text != null) {
                if (useCache) {
                    if (htmlCache.size > 96) htmlCache.clear()
                    htmlCache[url] = CachedHtml(text, System.currentTimeMillis())
                }
                return text
            }
            if (attempt < 2) {
                delay(if (retryWaitMs > 0) retryWaitMs else 700L * (attempt + 1))
            }
        }
        return null
    }

    private fun GnulaCatalogCard.toSearchResponse(): SearchResponse {
        return when (kind) {
            GnulaMediaKind.MOVIE, GnulaMediaKind.ANIME_MOVIE ->
                newMovieSearchResponse(
                    title,
                    url,
                    if (kind == GnulaMediaKind.ANIME_MOVIE) TvType.AnimeMovie else TvType.Movie,
                ) {
                    this.posterUrl = this@toSearchResponse.posterUrl
                    this.year = this@toSearchResponse.year
                    this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
                }

            GnulaMediaKind.SERIES, GnulaMediaKind.ANIME ->
                newTvSeriesSearchResponse(
                    title,
                    url,
                    if (kind == GnulaMediaKind.ANIME) TvType.Anime else TvType.TvSeries,
                ) {
                    this.posterUrl = this@toSearchResponse.posterUrl
                    this.year = this@toSearchResponse.year
                    this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
                }
        }
    }

    private fun kindHintForPath(data: String): GnulaMediaKind = when {
        data.contains("/ver/anime", ignoreCase = true) -> GnulaMediaKind.ANIME
        data.contains("/ver/series", ignoreCase = true) -> GnulaMediaKind.SERIES
        else -> GnulaMediaKind.MOVIE
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        when (request.data) {
            SECTION_HOME_WEEK, SECTION_HOME_TODAY -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
                val html = getText("$mainUrl/") ?: return newHomePageResponse(request, emptyList(), hasNext = false)
                val doc = Jsoup.parse(html, "$mainUrl/")
                val cards = if (request.data == SECTION_HOME_WEEK) {
                    GnulaHDMarkup.parseHomeTrendingWeek(doc, mainUrl)
                } else {
                    GnulaHDMarkup.parseHomeTrendingToday(doc, mainUrl)
                }
                return newHomePageResponse(request, cards.map { it.toSearchResponse() }, hasNext = false)
            }

            else -> {
                val targetUrl = GnulaHDMarkup.buildPagedUrl(request.data, page, mainUrl)
                val html = getText(targetUrl) ?: return newHomePageResponse(request, emptyList(), hasNext = false)
                val doc = Jsoup.parse(html, targetUrl)
                val parsed = GnulaHDMarkup.parseCatalogPage(doc, mainUrl, kindHintForPath(request.data))
                return newHomePageResponse(
                    request,
                    parsed.items.map { it.toSearchResponse() },
                    hasNext = parsed.hasNext,
                )
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val searchUrl = GnulaHDMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val html = getText(searchUrl, useCache = false) ?: return newSearchResponseList(emptyList(), hasNext = false)
        val doc = Jsoup.parse(html, searchUrl)
        val parsed = GnulaHDMarkup.parseCatalogPage(doc, mainUrl)
        return newSearchResponseList(parsed.items.map { it.toSearchResponse() }, hasNext = parsed.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val apiUrl = GnulaHDMarkup.buildQuickSearchUrl(trimmed, mainUrl)
        val jsonText = getText(apiUrl, accept = "application/json, */*", timeoutSec = 10L, useCache = false)
        if (!jsonText.isNullOrBlank()) {
            val quickCards = GnulaHDMarkup.parseQuickSearchJson(jsonText, mainUrl)
            if (quickCards.isNotEmpty()) return quickCards.map { it.toSearchResponse() }
        }
        if (trimmed.length >= 3) {
            return search(trimmed, 1)?.items
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? = loadInternal(url, allowParentRedirect = true)

    private suspend fun loadInternal(url: String, allowParentRedirect: Boolean): LoadResponse? {
        val resolved = GnulaHDMarkup.resolveUrl(mainUrl, url) ?: return null
        val html = getText(resolved) ?: return null
        val doc = Jsoup.parse(html, resolved)
        val detail = GnulaHDMarkup.parseDetail(doc, resolved, mainUrl) ?: return null

        val parentUrl = detail.parentSeriesUrl
        if (allowParentRedirect && detail.episodes.isEmpty() && !parentUrl.isNullOrBlank() &&
            parentUrl.trimEnd('/') != resolved.trimEnd('/')
        ) {
            val parentResp = runCatching { loadInternal(parentUrl, allowParentRedirect = false) }.getOrNull()
            if (parentResp != null) return parentResp
        }

        val actorData = detail.actors.map { a ->
            ActorData(Actor(a.name, a.photoUrl), roleString = a.role)
        }
        val recResponses = detail.recommendations
            .filter { it.url.trimEnd('/') != detail.url.trimEnd('/') }
            .map { it.toSearchResponse() }

        return if (detail.episodes.isNotEmpty() || (detail.comingSoon && (detail.kind == GnulaMediaKind.SERIES || detail.kind == GnulaMediaKind.ANIME))) {
            val tvType = if (detail.kind == GnulaMediaKind.ANIME) TvType.Anime else TvType.TvSeries
            val episodeItems = detail.episodes.map { ep ->
                val dataRef = GnulaHDMarkup.encodePlayerTarget(ep.postId, ep.token, ep.url)
                newEpisode(dataRef, fix = false) {
                    this.name = ep.title
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = ep.posterUrl ?: detail.posterUrl
                    this.description = ep.overview
                    ep.durationMinutes?.let { this.runTime = it }
                    ep.isoDate?.let { this.addDate(it) }
                }
            }
            val seasonsList = detail.episodes.asSequence()
                .map { it.season }
                .distinct()
                .sorted()
                .map { s -> SeasonData(s, "Temporada $s", s) }
                .toList()

            newTvSeriesLoadResponse(detail.title, detail.url, tvType, episodeItems) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundPosterUrl
                this.plot = detail.plot
                this.year = detail.year
                detail.rating?.let { this.score = Score.from10(it) }
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (actorData.isNotEmpty()) this.actors = actorData
                detail.trailerUrl?.let { addTrailer(it) }
                if (recResponses.isNotEmpty()) this.recommendations = recResponses
                if (seasonsList.isNotEmpty()) this.seasonNames = seasonsList
            }
        } else {
            val tvType = if (detail.kind == GnulaMediaKind.ANIME_MOVIE || detail.kind == GnulaMediaKind.ANIME) {
                TvType.AnimeMovie
            } else {
                TvType.Movie
            }
            val dataRef = if (detail.comingSoon) {
                ""
            } else {
                GnulaHDMarkup.encodePlayerTarget(detail.playerId, detail.playerToken, detail.url)
            }
            newMovieLoadResponse(detail.title, detail.url, tvType, dataRef) {
                this.comingSoon = detail.comingSoon
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundPosterUrl
                this.plot = detail.plot
                this.year = detail.year
                this.duration = detail.durationMinutes
                detail.rating?.let { this.score = Score.from10(it) }
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (actorData.isNotEmpty()) this.actors = actorData
                detail.trailerUrl?.let { addTrailer(it) }
                if (recResponses.isNotEmpty()) this.recommendations = recResponses
            }
        }
    }

    private suspend fun fetchPlayerPayload(
        playerId: String,
        token: String,
        refererPageUrl: String,
    ): GnulaPlayerPayload? {
        val apiUrl = GnulaHDMarkup.buildPlayerApiUrl(playerId, token, mainUrl)
        val raw = getText(
            url = apiUrl,
            referer = refererPageUrl,
            accept = "application/json, */*",
            timeoutSec = 15L,
            useCache = false,
        ) ?: return null
        val cipher = GnulaHDMarkup.extractPlayerApiCiphertext(raw) ?: return null
        val decoded = GnulaHDMarkup.decodeGnrdPayload(cipher) ?: return null
        return GnulaHDMarkup.parsePlayerPayload(decoded)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        val target = GnulaHDMarkup.parsePlayerTarget(data, mainUrl)
        val refererPage = target.pageUrl ?: "$mainUrl/"

        var payload: GnulaPlayerPayload? = if (!target.playerId.isNullOrBlank() && !target.token.isNullOrBlank()) {
            fetchPlayerPayload(target.playerId, target.token, refererPage)
        } else {
            null
        }

        // Token expired / omitted: refresh credentials from the movie or episode HTML page.
        if (payload == null && !target.pageUrl.isNullOrBlank()) {
            val pageHtml = getText(target.pageUrl, referer = "$mainUrl/", useCache = false)
            if (!pageHtml.isNullOrBlank()) {
                val doc = Jsoup.parse(pageHtml, target.pageUrl)
                var creds = GnulaHDMarkup.extractInlinePlayerCredentials(pageHtml)
                if (creds == null) {
                    val epCards = GnulaHDMarkup.parseEpisodeCards(doc, mainUrl)
                    val matched = epCards.firstOrNull { it.url.trimEnd('/') == target.pageUrl.trimEnd('/') }
                        ?: epCards.firstOrNull()
                    if (matched?.postId != null && matched.token != null) {
                        creds = matched.postId to matched.token
                    }
                }
                if (creds != null) {
                    payload = fetchPlayerPayload(creds.first, creds.second, target.pageUrl)
                }
            }
        }

        val resolvedPayload = payload ?: return false
        val jobs = buildList {
            for (langGroup in resolvedPayload.langs) {
                for (srv in langGroup.servers) {
                    add(Triple(srv.src, srv.title, langGroup.label))
                }
            }
        }
        if (jobs.isEmpty() && resolvedPayload.downloads.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lock = Any()

        if (jobs.isNotEmpty()) {
            jobs.amap { (srcUrl, srvTitle, langLabel) ->
                GnulaExtractorDispatcher.extractServer(
                    rawUrl = srcUrl,
                    serverTitle = srvTitle,
                    langLabel = langLabel,
                    refererPageUrl = refererPage,
                    subtitleCallback = subtitleCallback,
                ) { labeled ->
                    val dedupKey = "${labeled.url}|${labeled.name}"
                    if (seen.add(dedupKey)) {
                        synchronized(lock) {
                            emitted.set(true)
                            callback(labeled)
                        }
                    }
                }
            }
        }

        if (!emitted.get() && resolvedPayload.downloads.isNotEmpty()) {
            resolvedPayload.downloads.amap { dl ->
                GnulaExtractorDispatcher.extractServer(
                    rawUrl = dl.url,
                    serverTitle = dl.name,
                    langLabel = dl.language,
                    refererPageUrl = refererPage,
                    subtitleCallback = subtitleCallback,
                ) { labeled ->
                    val dedupKey = "${labeled.url}|${labeled.name}"
                    if (seen.add(dedupKey)) {
                        synchronized(lock) {
                            emitted.set(true)
                            callback(labeled)
                        }
                    }
                }
            }
        }

        return emitted.get()
    }
}
