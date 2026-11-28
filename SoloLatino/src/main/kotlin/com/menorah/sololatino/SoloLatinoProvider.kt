package com.menorah.sololatino

import android.content.Context
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SoloLatinoProvider(
    private val appContext: Context? = null,
) : MainAPI() {

    override var mainUrl = SoloLatinoMarkup.MAIN_URL
    override var name = "SoloLatino"
    override var lang = "es-419"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override val getMainPageTimeoutMs: Long? = 45_000L
    override val searchTimeoutMs: Long? = 30_000L
    override val quickSearchTimeoutMs: Long? = 20_000L
    override val loadTimeoutMs: Long? = 45_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    override val mainPage = mainPageOf(
        *SoloLatinoMarkup.mainPageCategories().toTypedArray(),
    )

    internal val client = SoloLatinoClient(appContext)
    private val homeMutex = Mutex()
    private val linkGate = Semaphore(4)

    private data class CachedHtml(val url: String, val text: String, val storedAtMs: Long)
    private val htmlCache = ConcurrentHashMap<String, CachedHtml>()

    private suspend fun fetchHtmlCached(url: String, referer: String = "$mainUrl/", useCache: Boolean = true): SoloLatinoHttpResult {
        if (useCache) {
            htmlCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.storedAtMs < 60_000L) {
                    return SoloLatinoHttpResult(200, cached.url, cached.text)
                }
            }
        }
        val res = client.fetchSite(url, referer = referer, isAjax = false)
        if (useCache && res.code in 200..299 && res.text.isNotBlank() &&
            !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(res.code, res.text)
        ) {
            if (htmlCache.size > 96) htmlCache.clear()
            htmlCache[url] = CachedHtml(res.url, res.text, System.currentTimeMillis())
        }
        return res
    }

    private suspend fun fetchHomeHtml(): SoloLatinoHttpResult = homeMutex.withLock {
        fetchHtmlCached("$mainUrl/", "$mainUrl/", useCache = true)
    }

    private fun SoloLatinoCatalogCard.toSearchResponse(): SearchResponse = when (kind) {
        SoloLatinoMediaKind.ANIME_MOVIE ->
            newMovieSearchResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
            }
        SoloLatinoMediaKind.ANIME ->
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
            }
        SoloLatinoMediaKind.SERIES, SoloLatinoMediaKind.DORAMA ->
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
            }
        SoloLatinoMediaKind.MOVIE ->
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this@toSearchResponse.rating?.let { this.score = Score.from10(it) }
            }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == SoloLatinoMarkup.SECTION_HOME_RECENT_EPISODES ||
            request.data == SoloLatinoMarkup.SECTION_HOME_RECENT_ADDED
        ) {
            if (page > 1) {
                return newHomePageResponse(
                    HomePageList(
                        name = request.name,
                        list = emptyList(),
                        isHorizontalImages = request.data == SoloLatinoMarkup.SECTION_HOME_RECENT_EPISODES,
                    ),
                    hasNext = false,
                )
            }
            val homeRes = fetchHomeHtml()
            if (homeRes.code !in 200..299 || homeRes.text.isBlank()) return null
            val doc = Jsoup.parse(homeRes.text, homeRes.url.ifBlank { "$mainUrl/" })
            val cards = if (request.data == SoloLatinoMarkup.SECTION_HOME_RECENT_EPISODES) {
                SoloLatinoMarkup.parseHomeRecentEpisodes(doc, mainUrl)
            } else {
                SoloLatinoMarkup.parseHomeRecentAdded(doc, mainUrl)
            }
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = cards.map { it.toSearchResponse() },
                    isHorizontalImages = request.data == SoloLatinoMarkup.SECTION_HOME_RECENT_EPISODES,
                ),
                hasNext = false,
            )
        }

        val targetUrl = SoloLatinoMarkup.buildCatalogUrl(request.data, page, mainUrl)
        val res = fetchHtmlCached(targetUrl, "$mainUrl/", useCache = page == 1)
        if (res.code !in 200..299 || res.text.isBlank()) return null
        val doc = Jsoup.parse(res.text, res.url.ifBlank { targetUrl })
        val parsed = SoloLatinoMarkup.parseCatalogPage(
            doc = doc,
            baseUrl = mainUrl,
            currentPage = page,
            defaultKind = SoloLatinoMarkup.defaultKindForData(request.data),
        )
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = parsed.items.map { it.toSearchResponse() },
                isHorizontalImages = request.horizontalImages,
            ),
            hasNext = parsed.hasNext,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val suggestUrl = SoloLatinoMarkup.buildQuickSearchUrl(trimmed, mainUrl)
        val res = client.fetchSite(suggestUrl, "$mainUrl/", isAjax = true)
        if (res.code in 200..299 && res.text.isNotBlank()) {
            val cards = SoloLatinoMarkup.parseQuickSearchJson(res.text, mainUrl)
            if (cards.isNotEmpty()) return cards.map { it.toSearchResponse() }
        }
        return search(trimmed, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val searchUrl = SoloLatinoMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val res = fetchHtmlCached(searchUrl, "$mainUrl/", useCache = false)
        if (res.code in 200..299 && res.text.isNotBlank()) {
            val doc = Jsoup.parse(res.text, res.url.ifBlank { searchUrl })
            val parsed = SoloLatinoMarkup.parseCatalogPage(doc, mainUrl, page, SoloLatinoMediaKind.MOVIE)
            if (parsed.items.isNotEmpty() || page > 1) {
                return newSearchResponseList(parsed.items.map { it.toSearchResponse() }, hasNext = parsed.hasNext)
            }
        }
        if (page == 1) {
            val suggestUrl = SoloLatinoMarkup.buildQuickSearchUrl(trimmed, mainUrl)
            val suggestRes = client.fetchSite(suggestUrl, "$mainUrl/", isAjax = true)
            if (suggestRes.code in 200..299 && suggestRes.text.isNotBlank()) {
                val cards = SoloLatinoMarkup.parseQuickSearchJson(suggestRes.text, mainUrl)
                return newSearchResponseList(cards.map { it.toSearchResponse() }, hasNext = false)
            }
        }
        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun load(url: String): LoadResponse? {
        val (normalizedUrl, derivedParentUrl) = SoloLatinoMarkup.normalizeSeriesOrEpisodeTarget(url, mainUrl)
        val epCoords = SoloLatinoMarkup.parseEpisodeCoordinatesFromUrl(normalizedUrl)

        var targetUrl = derivedParentUrl ?: normalizedUrl
        var res = fetchHtmlCached(targetUrl, "$mainUrl/", useCache = true)
        var detail = if (res.code in 200..299 && res.text.isNotBlank()) {
            SoloLatinoMarkup.parseDetail(Jsoup.parse(res.text, res.url.ifBlank { targetUrl }), res.url.ifBlank { targetUrl }, mainUrl)
        } else {
            null
        }

        // If episode URL failed parent resolution or parent page wasn't available, fall back to the episode page itself
        if ((detail == null || (detail.isSeriesLike && detail.episodes.isEmpty())) && targetUrl != normalizedUrl) {
            val epRes = fetchHtmlCached(normalizedUrl, "$mainUrl/", useCache = true)
            if (epRes.code in 200..299 && epRes.text.isNotBlank()) {
                val epDetail = SoloLatinoMarkup.parseDetail(
                    Jsoup.parse(epRes.text, epRes.url.ifBlank { normalizedUrl }),
                    epRes.url.ifBlank { normalizedUrl },
                    mainUrl,
                )
                if (epDetail != null) {
                    val parentFromDoc = epDetail.parentSeriesUrl
                    if (!parentFromDoc.isNullOrBlank() && parentFromDoc != targetUrl) {
                        val pRes = fetchHtmlCached(parentFromDoc, normalizedUrl, useCache = true)
                        val pDetail = if (pRes.code in 200..299 && pRes.text.isNotBlank()) {
                            SoloLatinoMarkup.parseDetail(Jsoup.parse(pRes.text, pRes.url.ifBlank { parentFromDoc }), parentFromDoc, mainUrl)
                        } else null
                        if (pDetail != null && pDetail.episodes.isNotEmpty()) {
                            detail = pDetail
                            targetUrl = parentFromDoc
                        } else {
                            detail = epDetail
                        }
                    } else if (detail == null) {
                        detail = epDetail
                    }
                }
            }
        }

        val resolvedDetail = detail ?: return null
        val actorData = resolvedDetail.actors.map { a ->
            ActorData(Actor(a.name, a.imageUrl), roleString = a.role)
        }
        val recResponses = resolvedDetail.recommendations
            .filter { it.url.trimEnd('/') != resolvedDetail.canonicalUrl.trimEnd('/') }
            .map { it.toSearchResponse() }

        if (!resolvedDetail.isSeriesLike) {
            val tvType = if (resolvedDetail.kind == SoloLatinoMediaKind.ANIME_MOVIE) TvType.AnimeMovie else TvType.Movie
            return newMovieLoadResponse(resolvedDetail.title, resolvedDetail.canonicalUrl, tvType, resolvedDetail.canonicalUrl) {
                this.posterUrl = resolvedDetail.posterUrl
                this.backgroundPosterUrl = resolvedDetail.backgroundPosterUrl
                this.plot = resolvedDetail.plot
                this.year = resolvedDetail.year
                this.duration = resolvedDetail.durationMinutes
                resolvedDetail.rating?.let { this.score = Score.from10(it) }
                if (resolvedDetail.genres.isNotEmpty()) this.tags = resolvedDetail.genres
                if (actorData.isNotEmpty()) this.actors = actorData
                resolvedDetail.trailerUrl?.let { addTrailer(it) }
                if (recResponses.isNotEmpty()) this.recommendations = recResponses
            }
        }

        val episodeItems = resolvedDetail.episodes.toMutableList()
        if (epCoords != null && episodeItems.none { it.season == epCoords.first && it.episode == epCoords.second }) {
            episodeItems.add(
                SoloLatinoEpisodeItem(
                    url = normalizedUrl,
                    season = epCoords.first,
                    episode = epCoords.second,
                    title = "Episodio ${epCoords.second}",
                    description = null,
                    posterUrl = resolvedDetail.posterUrl,
                    releaseDate = null,
                ),
            )
            episodeItems.sortWith(compareBy({ it.season }, { it.episode }))
        }
        if (episodeItems.isEmpty() && epCoords == null) {
            // Single fallback entry using canonicalUrl if a series page had no listed episodes
            episodeItems.add(
                SoloLatinoEpisodeItem(
                    url = resolvedDetail.canonicalUrl,
                    season = 1,
                    episode = 1,
                    title = "Episodio 1",
                    description = null,
                    posterUrl = resolvedDetail.posterUrl,
                    releaseDate = null,
                ),
            )
        }

        val csEpisodes: List<Episode> = episodeItems.map { ep ->
            newEpisode(ep.url) {
                this.name = ep.title
                this.season = ep.season
                this.episode = ep.episode
                this.description = ep.description
                this.posterUrl = ep.posterUrl ?: resolvedDetail.posterUrl
                ep.releaseDate?.let { addDate(it) }
            }
        }

        val showStatus = when (resolvedDetail.statusOngoing) {
            true -> ShowStatus.Ongoing
            false -> ShowStatus.Completed
            null -> null
        }

        if (resolvedDetail.kind == SoloLatinoMediaKind.ANIME) {
            return newAnimeLoadResponse(resolvedDetail.title, resolvedDetail.canonicalUrl, TvType.Anime) {
                this.posterUrl = resolvedDetail.posterUrl
                this.backgroundPosterUrl = resolvedDetail.backgroundPosterUrl
                this.plot = resolvedDetail.plot
                this.year = resolvedDetail.year
                this.showStatus = showStatus
                this.duration = resolvedDetail.durationMinutes
                resolvedDetail.rating?.let { this.score = Score.from10(it) }
                if (resolvedDetail.genres.isNotEmpty()) this.tags = resolvedDetail.genres
                if (actorData.isNotEmpty()) this.actors = actorData
                resolvedDetail.trailerUrl?.let { addTrailer(it) }
                if (recResponses.isNotEmpty()) this.recommendations = recResponses
                addEpisodes(DubStatus.Dubbed, csEpisodes)
            }
        }

        val seasonNamesList = episodeItems.map { it.season }.distinct().sorted().map { s ->
            SeasonData(s, "Temporada $s", s)
        }

        return newTvSeriesLoadResponse(resolvedDetail.title, resolvedDetail.canonicalUrl, TvType.TvSeries, csEpisodes) {
            this.posterUrl = resolvedDetail.posterUrl
            this.backgroundPosterUrl = resolvedDetail.backgroundPosterUrl
            this.plot = resolvedDetail.plot
            this.year = resolvedDetail.year
            this.showStatus = showStatus
            this.duration = resolvedDetail.durationMinutes
            if (seasonNamesList.isNotEmpty()) this.seasonNames = seasonNamesList
            resolvedDetail.rating?.let { this.score = Score.from10(it) }
            if (resolvedDetail.genres.isNotEmpty()) this.tags = resolvedDetail.genres
            if (actorData.isNotEmpty()) this.actors = actorData
            resolvedDetail.trailerUrl?.let { addTrailer(it) }
            if (recResponses.isNotEmpty()) this.recommendations = recResponses
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = SoloLatinoMarkup.resolveUrl(mainUrl, data) ?: return false
        val res = fetchHtmlCached(pageUrl, "$mainUrl/", useCache = false)
        if (res.code !in 200..299 || res.text.isBlank()) return false

        val finalPageUrl = res.url.ifBlank { pageUrl }
        val doc = Jsoup.parse(res.text, finalPageUrl)
        val playerSection = SoloLatinoMarkup.parsePlayerSection(doc, finalPageUrl)

        val (resolvedButtons, lazyApiRes) = client.resolvePlayerSection(finalPageUrl, playerSection)

        data class DispatchTarget(
            val url: String,
            val langLabel: String,
            val label: String,
            val type: String?,
        )

        val targets = mutableListOf<DispatchTarget>()
        val seenUrls = HashSet<String>()

        fun addTarget(url: String?, langLabel: String, label: String, type: String?) {
            val clean = url?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
            if (seenUrls.add(clean)) {
                targets.add(DispatchTarget(clean, langLabel.ifBlank { "Latino" }, label.ifBlank { "Servidor" }, type))
            }
        }

        for (btn in resolvedButtons) {
            addTarget(btn.url, btn.langLabel, btn.label, btn.type)
        }
        lazyApiRes?.let { lazy ->
            addTarget(
                lazy.url,
                playerSection.lazyEmbedLang ?: "Latino",
                "Principal",
                lazy.type,
            )
        }
        for (iframeUrl in playerSection.directIframeUrls) {
            addTarget(iframeUrl, playerSection.lazyEmbedLang ?: "Latino", "Reproductor", null)
        }

        if (targets.isEmpty()) return false

        val emittedAny = AtomicBoolean(false)
        coroutineScope {
            targets.map { target ->
                async(Dispatchers.IO) {
                    linkGate.withPermit {
                        val lowerUrl = target.url.lowercase(Locale.ROOT)
                        val lowerType = target.type?.lowercase(Locale.ROOT).orEmpty()
                        val sourceLabel = "SoloLatino · ${target.langLabel} · ${target.label}"
                        val ok = when {
                            lowerType == "mp4" -> {
                                callback(
                                    newExtractorLink(sourceLabel, "${target.label} MP4", target.url, INFER_TYPE) {
                                        this.referer = "$mainUrl/"
                                        this.headers = mapOf("Referer" to "$mainUrl/", "user-agent" to client.currentUserAgent())
                                        this.quality = Qualities.Unknown.value
                                    },
                                )
                                true
                            }
                            lowerType == "gdrive" || lowerUrl.contains("drive.google.com") ->
                                SoloLatinoGDriveExtractor.extract(
                                    url = target.url,
                                    referer = finalPageUrl,
                                    sourceLabel = sourceLabel,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback,
                                )
                            lowerUrl.contains("embed69.org") ->
                                SoloLatinoEmbed69Resolver.extract(
                                    embedUrl = target.url,
                                    referer = finalPageUrl,
                                    preferredLangTag = target.langLabel,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback,
                                )
                            SoloLatinoMarkup.isPelisSeriesHoyUrl(target.url) ->
                                SoloLatinoPelisSeriesHoyExtractor.extractWithClient(
                                    client = client,
                                    url = target.url,
                                    referer = finalPageUrl,
                                    langTag = target.langLabel,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback,
                                )
                            else ->
                                SoloLatinoExtractorDispatcher.extract(
                                    url = target.url,
                                    referer = finalPageUrl,
                                    sourceLabel = sourceLabel,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback,
                                )
                        }
                        if (ok) emittedAny.set(true)
                    }
                }
            }.awaitAll()
        }
        return emittedAny.get()
    }
}
