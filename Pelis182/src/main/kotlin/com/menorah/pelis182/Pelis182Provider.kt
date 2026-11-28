package com.menorah.pelis182

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
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class Pelis182Provider : MainAPI() {
    override var mainUrl = Pelis182Markup.MAIN_URL
    override var name = "Pelis182"
    override var lang = "es-419"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AnimeMovie,
        TvType.Anime,
    )

    override val getMainPageTimeoutMs: Long? = 45_000L
    override val searchTimeoutMs: Long? = 30_000L
    override val quickSearchTimeoutMs: Long? = 15_000L
    override val loadTimeoutMs: Long? = 45_000L
    override val loadLinksTimeoutMs: Long? = 45_000L

    override val mainPage = mainPageOf(
        "/catalogo/" to "Películas recientes",
        "/catalogo/?orden=tendencias" to "Tendencias",
        "/category/series/" to "Series populares",
        "/category/anime/" to "Anime",
        "/category/accion/" to "Acción",
        "/category/animacion/" to "Animación",
        "/category/aventura/" to "Aventura",
        "/category/ciencia-ficcion/" to "Ciencia Ficción",
        "/category/comedia/" to "Comedia",
        "/category/disney/" to "Disney",
        "/category/drama/" to "Drama",
        "/category/fantasia/" to "Fantasía",
        "/category/misterio-2/" to "Misterio",
        "/category/musical/" to "Musical",
        "/category/romance/" to "Romance",
        "/category/superheroes/" to "Superhéroes",
        "/category/thriller/" to "Suspenso",
        "/category/horror/" to "Terror",
    )

    private companion object {
        const val CACHE_TTL_MS = 120_000L
        const val MAX_SEASON_FETCH = 20
    }

    private data class CachedHtml(val text: String, val storedAtMs: Long)

    private val htmlCache = ConcurrentHashMap<String, CachedHtml>()
    private val nextRequestAllowedAtMs = AtomicLong(0L)
    private val siteGate = Semaphore(4)
    private val paulinitoExtractor = PaulinitoExtractor()

    private val siteHeaders = mapOf(
        "User-Agent" to Pelis182Markup.DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend fun getText(
        url: String,
        referer: String = "$mainUrl/",
        timeoutSec: Long = 16L,
        useCache: Boolean = true,
    ): String? {
        val now = System.currentTimeMillis()
        if (useCache) {
            htmlCache[url]?.let { cached ->
                if (now - cached.storedAtMs < CACHE_TTL_MS) return cached.text
            }
        }

        for (attempt in 0..2) {
            val waitMs = nextRequestAllowedAtMs.get() - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)

            var retryWaitMs = 0L
            val text = try {
                siteGate.withPermit {
                    val cooldown = nextRequestAllowedAtMs.get() - System.currentTimeMillis()
                    if (cooldown > 0) delay(cooldown)
                    nextRequestAllowedAtMs.updateAndGet { prev -> maxOf(prev, System.currentTimeMillis() + 60L) }
                    val res = app.get(url, referer = referer, headers = siteHeaders, timeout = timeoutSec)
                    when {
                        res.isSuccessful -> res.text
                        res.code == 429 || res.code in 500..599 -> {
                            val retrySec = res.headers["Retry-After"]?.trim()?.toLongOrNull()
                            retryWaitMs = ((retrySec?.times(1000L)) ?: (1000L * (attempt + 1))).coerceIn(700L, 4500L)
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
                retryWaitMs = 500L * (attempt + 1)
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
                delay(if (retryWaitMs > 0) retryWaitMs else 600L * (attempt + 1))
            }
        }
        return null
    }

    private fun Pelis182CatalogCard.toSearchResponse(): SearchResponse {
        return when (kind) {
            Pelis182MediaKind.MOVIE, Pelis182MediaKind.ANIME_MOVIE ->
                newMovieSearchResponse(
                    title,
                    url,
                    if (kind == Pelis182MediaKind.ANIME_MOVIE) TvType.AnimeMovie else TvType.Movie,
                ) {
                    this.posterUrl = this@toSearchResponse.posterUrl
                    this.year = this@toSearchResponse.year
                    this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
                }

            Pelis182MediaKind.SERIES, Pelis182MediaKind.ANIME ->
                newTvSeriesSearchResponse(
                    title,
                    url,
                    if (kind == Pelis182MediaKind.ANIME) TvType.Anime else TvType.TvSeries,
                ) {
                    this.posterUrl = this@toSearchResponse.posterUrl
                    this.year = this@toSearchResponse.year
                    this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
                }
        }
    }

    private fun defaultKindForPath(data: String): Pelis182MediaKind = when {
        data.contains("/category/anime", ignoreCase = true) -> Pelis182MediaKind.ANIME_MOVIE
        data.contains("/category/series", ignoreCase = true) -> Pelis182MediaKind.SERIES
        else -> Pelis182MediaKind.MOVIE
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = Pelis182Markup.buildPagedUrl(request.data, page, mainUrl)
        val html = getText(targetUrl) ?: return newHomePageResponse(request, emptyList(), hasNext = false)
        val doc = Jsoup.parse(html, targetUrl)
        val parsed = Pelis182Markup.parseCatalogPage(doc, mainUrl, defaultKindForPath(request.data))
        return newHomePageResponse(
            request,
            parsed.items.map { it.toSearchResponse() },
            hasNext = parsed.hasNext,
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val searchUrl = Pelis182Markup.buildSearchUrl(trimmed, page, mainUrl)
        val html = getText(searchUrl, useCache = false) ?: return newSearchResponseList(emptyList(), hasNext = false)
        val doc = Jsoup.parse(html, searchUrl)
        val parsed = Pelis182Markup.parseCatalogPage(doc, mainUrl)
        return newSearchResponseList(parsed.items.map { it.toSearchResponse() }, hasNext = parsed.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val searchUrl = Pelis182Markup.buildSearchUrl(trimmed, 1, mainUrl)
        val html = getText(searchUrl, timeoutSec = 10L, useCache = true) ?: return emptyList()
        val doc = Jsoup.parse(html, searchUrl)
        return Pelis182Markup.parseCatalogPage(doc, mainUrl).items.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resolved = Pelis182Markup.resolveUrl(mainUrl, url) ?: return null
        val html = getText(resolved) ?: return null
        val doc = Jsoup.parse(html, resolved)
        val detail = Pelis182Markup.parseDetail(doc, resolved, mainUrl) ?: return null

        val actorData = detail.actors.map { a ->
            ActorData(Actor(a.name, a.photoUrl), roleString = a.role)
        }

        if (!detail.isSeries) {
            val tvType = if (detail.kind == Pelis182MediaKind.ANIME_MOVIE) TvType.AnimeMovie else TvType.Movie
            val dataRef = if (detail.comingSoon) {
                ""
            } else {
                Pelis182Markup.encodePlayerTarget(detail.url, detail.movieEmbeds)
            }
            val recResponses = detail.recommendations
                .filter { it.url.trimEnd('/') != detail.url.trimEnd('/') }
                .map { it.toSearchResponse() }
            return newMovieLoadResponse(detail.title, detail.url, tvType, dataRef) {
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

        // Aggregate sibling seasons if this series spans multiple season posts (e.g. Temporada 1..7)
        val seasonDetailMap = Collections.synchronizedMap(linkedMapOf<Int, Pelis182Detail>())
        val curSeasonNum = detail.seasonNumber ?: 1
        seasonDetailMap[curSeasonNum] = detail

        val siblingUrls = linkedMapOf<String, String>()
        for (rec in detail.recommendations) {
            if (Pelis182Markup.isMatchingSeasonCard(detail.title, rec.title, detail.url, rec.url)) {
                siblingUrls[rec.url.trimEnd('/').lowercase()] = rec.url
            }
        }

        val mightHaveMoreSeasons = detail.seasonNumber != null || siblingUrls.isNotEmpty()
        if (mightHaveMoreSeasons) {
            val searchUrl = Pelis182Markup.buildSearchUrl(detail.title, 1, mainUrl)
            val searchHtml = getText(searchUrl, timeoutSec = 10L)
            if (!searchHtml.isNullOrBlank()) {
                val searchPage = Pelis182Markup.parseCatalogPage(Jsoup.parse(searchHtml, searchUrl), mainUrl, detail.kind)
                for (card in searchPage.items) {
                    if (Pelis182Markup.isMatchingSeasonCard(detail.title, card.title, detail.url, card.url)) {
                        siblingUrls.putIfAbsent(card.url.trimEnd('/').lowercase(), card.url)
                    }
                }
            }
        }

        val currentNorm = detail.url.trimEnd('/').lowercase()
        val extraSeasonUrls = siblingUrls.entries
            .filter { it.key != currentNorm }
            .map { it.value }
            .take(MAX_SEASON_FETCH)

        if (extraSeasonUrls.isNotEmpty()) {
            extraSeasonUrls.amap { sUrl ->
                runCatching {
                    val sHtml = getText(sUrl) ?: return@runCatching
                    val sDetail = Pelis182Markup.parseDetail(Jsoup.parse(sHtml, sUrl), sUrl, mainUrl) ?: return@runCatching
                    if (sDetail.isSeries && sDetail.episodes.isNotEmpty()) {
                        val sNum = sDetail.seasonNumber ?: sDetail.episodes.first().season
                        seasonDetailMap.putIfAbsent(sNum, sDetail)
                    }
                }
            }
        }

        val orderedSeasons = seasonDetailMap.entries.sortedBy { it.key }.map { it.value }
        val combinedEpisodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val seenEpisodeKeys = HashSet<Pair<Int, Int>>()
        val seasonNumbers = sortedSetOf<Int>()

        for (sd in orderedSeasons) {
            for (ep in sd.episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
                if (!seenEpisodeKeys.add(ep.season to ep.episode)) continue
                seasonNumbers.add(ep.season)
                val epData = Pelis182Markup.encodePlayerTarget(sd.url, ep.embeds)
                combinedEpisodes.add(
                    newEpisode(url = epData, fix = false, initializer = {
                        this.name = ep.title
                        this.season = ep.season
                        this.episode = ep.episode
                        this.posterUrl = ep.posterUrl ?: sd.posterUrl ?: detail.posterUrl
                        ep.isoDate?.let { this.addDate(it) }
                    }),
                )
            }
        }

        val primarySeasonDetail = orderedSeasons.firstOrNull() ?: detail
        val tvType = if (detail.kind == Pelis182MediaKind.ANIME) TvType.Anime else TvType.TvSeries
        val filteredRecommendations = detail.recommendations
            .filter { rec ->
                rec.url.trimEnd('/') != detail.url.trimEnd('/') &&
                    !Pelis182Markup.isMatchingSeasonCard(detail.title, rec.title, detail.url, rec.url)
            }
            .map { it.toSearchResponse() }

        val seasonNamesList = seasonNumbers.map { s -> SeasonData(s, "Temporada $s", s) }
        val seriesYear = orderedSeasons.mapNotNull { it.year }.minOrNull() ?: detail.year

        return newTvSeriesLoadResponse(detail.title, detail.url, tvType, combinedEpisodes) {
            this.comingSoon = combinedEpisodes.isEmpty()
            this.posterUrl = primarySeasonDetail.posterUrl ?: detail.posterUrl
            this.backgroundPosterUrl = detail.backgroundPosterUrl ?: primarySeasonDetail.backgroundPosterUrl
            this.plot = detail.plot ?: primarySeasonDetail.plot
            this.year = seriesYear
            detail.rating?.let { this.score = Score.from10(it) }
            if (detail.genres.isNotEmpty()) this.tags = detail.genres
            if (actorData.isNotEmpty()) this.actors = actorData
            detail.trailerUrl?.let { addTrailer(it) }
            if (filteredRecommendations.isNotEmpty()) this.recommendations = filteredRecommendations
            if (seasonNamesList.isNotEmpty()) this.seasonNames = seasonNamesList
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        val target = Pelis182Markup.parsePlayerTarget(data, mainUrl)
        val pageReferer = target.pageUrl ?: "$mainUrl/"

        var embeds = target.embeds
        if (embeds.isEmpty() && !target.pageUrl.isNullOrBlank()) {
            val html = getText(target.pageUrl, useCache = false)
            if (!html.isNullOrBlank()) {
                val detail = Pelis182Markup.parseDetail(Jsoup.parse(html, target.pageUrl), target.pageUrl, mainUrl)
                if (detail != null) {
                    embeds = detail.movieEmbeds.ifEmpty { detail.episodes.firstOrNull()?.embeds.orEmpty() }
                }
            }
        }
        if (embeds.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lock = Any()

        val safeSubCallback: (SubtitleFile) -> Unit = { sub ->
            if (seen.add("SUB|${sub.url}")) {
                synchronized(lock) { subtitleCallback(sub) }
            }
        }

        embeds.amap { embed ->
            val emitUnique: (ExtractorLink) -> Unit = { link ->
                if (seen.add("${link.url}|${link.name}")) {
                    synchronized(lock) {
                        emitted.set(true)
                        callback(link)
                    }
                }
            }

            if (embed.url.contains("paulinito.com", ignoreCase = true)) {
                val displayName = if (embed.label.equals("Pelis182", ignoreCase = true)) {
                    "Pelis182"
                } else {
                    "Pelis182 · ${embed.label}"
                }
                paulinitoExtractor.extractWithDisplayName(
                    url = embed.url,
                    referer = pageReferer,
                    displayName = displayName,
                    subtitleCallback = safeSubCallback,
                    callback = emitUnique,
                )
            } else {
                loadExtractor(embed.url, pageReferer, safeSubCallback, emitUnique)
            }
        }

        return emitted.get()
    }
}
