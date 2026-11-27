package com.menorah.veraniv2

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicBoolean

class VerAniV2Provider : MainAPI() {
    override var mainUrl = VerAniV2Markup.DEFAULT_MAIN_URL
    override var name = "VerAni.me v2"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        VerAniV2Markup.SECTION_WEEKLY to "Estrenos Semanales",
        "$mainUrl/animes/" to "Últimos Animes",
        "$mainUrl/animes/?estado=en-emision" to "En Emisión",
        "$mainUrl/animes/?idioma=Espa%C3%B1ol%20Latino" to "Audio Latino",
        "$mainUrl/animes/?idioma=Espa%C3%B1ol%20Espa%C3%B1a" to "Castellano",
        "$mainUrl/animes/?orderby=popular" to "Más Populares",
        "$mainUrl/peliculas/" to "Películas",
        "$mainUrl/animes/accion/" to "Acción",
        "$mainUrl/animes/aventura/" to "Aventura",
        "$mainUrl/animes/fantasia/" to "Fantasía",
        "$mainUrl/animes/isekai/" to "Isekai",
        "$mainUrl/animes/romance/" to "Romance",
        "$mainUrl/animes/shounen/" to "Shounen",
    )

    private val defaultHeaders = mapOf(
        "user-agent" to VerAniV2ExtractorUserAgent.UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
    )

    private fun VerAniCard.toSearchResponse(): SearchResponse {
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = this@toSearchResponse.posterUrl
            }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = this@toSearchResponse.posterUrl
                val epCount = this@toSearchResponse.latestEpisode ?: this@toSearchResponse.episodesCount
                if (epCount != null && epCount > 0) {
                    addDubStatus(isDub = this@toSearchResponse.isDub, episodes = epCount)
                } else if (this@toSearchResponse.isDub) {
                    addDubStatus(isDub = true, episodes = null)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == VerAniV2Markup.SECTION_WEEKLY) {
            if (page > 1) {
                return newHomePageResponse(request, emptyList(), hasNext = false)
            }
            val res = app.get("$mainUrl/", headers = defaultHeaders, timeout = 15L)
            val doc = Jsoup.parse(res.text, res.url)
            val items = VerAniV2Markup.parseHomeWeeklyUpdates(doc, mainUrl).map { it.toSearchResponse() }
            return newHomePageResponse(request, items, hasNext = false)
        }

        val targetUrl = VerAniV2Markup.buildArchivePageUrl(request.data, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        val doc = Jsoup.parse(res.text, res.url)
        val paged = VerAniV2Markup.parseArchiveCards(doc, mainUrl)
        val items = paged.items.map { it.toSearchResponse() }
        return newHomePageResponse(request, items, hasNext = paged.hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = VerAniV2Markup.buildSearchUrl(trimmed, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        val doc = Jsoup.parse(res.text, res.url)
        val paged = VerAniV2Markup.parseArchiveCards(doc, mainUrl)
        return newSearchResponseList(paged.items.map { it.toSearchResponse() }, hasNext = paged.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? =
        loadInternal(url, allowParentRedirect = true)

    private suspend fun loadInternal(url: String, allowParentRedirect: Boolean): LoadResponse? {
        val resolvedUrl = VerAniV2Markup.resolveUrl(mainUrl, url) ?: url
        val res = app.get(resolvedUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        if (!res.isSuccessful) return null
        val finalUrl = res.url
        val doc = Jsoup.parse(res.text, finalUrl)

        return when (VerAniV2Markup.detectPageKind(doc)) {
            VerAniPageKind.MOVIE -> {
                val movie = VerAniV2Markup.parseMovieDetail(doc, finalUrl, mainUrl) ?: return null
                newMovieLoadResponse(movie.title, finalUrl, TvType.AnimeMovie, finalUrl) {
                    this.posterUrl = movie.posterUrl
                    this.backgroundPosterUrl = movie.backgroundUrl ?: movie.posterUrl
                    this.year = movie.year
                    this.plot = movie.plot
                    this.tags = movie.tags
                }
            }

            VerAniPageKind.CHAPTER -> {
                val chapter = VerAniV2Markup.parseChapterDetail(doc, finalUrl, mainUrl)
                val parentUrl = chapter.parentAnimeUrl
                if (allowParentRedirect &&
                    !parentUrl.isNullOrBlank() &&
                    parentUrl.trimEnd('/') != finalUrl.trimEnd('/')
                ) {
                    val parentResponse = runCatching { loadInternal(parentUrl, allowParentRedirect = false) }.getOrNull()
                    if (parentResponse != null) return parentResponse
                }
                newMovieLoadResponse(chapter.title, finalUrl, TvType.AnimeMovie, finalUrl) {
                    this.posterUrl = chapter.backdropUrl
                    this.backgroundPosterUrl = chapter.backdropUrl
                }
            }

            VerAniPageKind.ANIME, VerAniPageKind.UNKNOWN -> {
                val detail = VerAniV2Markup.parseAnimeDetail(doc, finalUrl, mainUrl) ?: return null
                val episodes = detail.episodes.map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                        this.posterUrl = ep.posterUrl ?: detail.posterUrl
                    }
                }
                newAnimeLoadResponse(detail.title, finalUrl, TvType.Anime) {
                    this.posterUrl = detail.posterUrl
                    this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                    this.year = detail.year
                    this.plot = detail.plot
                    this.tags = detail.tags
                    this.showStatus = detail.status
                    this.seasonNames = detail.seasons.map { s ->
                        SeasonData(season = s.season, name = s.name, displaySeason = null)
                    }
                    this.recommendations = detail.recommendations.map { it.toSearchResponse() }
                    addTrailer(detail.trailerUrl)
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resolvedUrl = VerAniV2Markup.resolveUrl(mainUrl, data) ?: return false
        val res = runCatching {
            app.get(resolvedUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        }.getOrNull() ?: return false
        if (!res.isSuccessful) return false

        val doc = Jsoup.parse(res.text, res.url)
        val tabs = VerAniV2Markup.parsePlayerTabs(doc, mainUrl)
        if (tabs.isEmpty()) return false

        val emittedAny = AtomicBoolean(false)
        tabs.amap { tab ->
            VerAniV2ExtractorDispatcher.extractTab(
                tab = tab,
                refererPageUrl = res.url,
                subtitleCallback = subtitleCallback,
                callback = { link ->
                    emittedAny.set(true)
                    callback(link)
                },
            )
        }
        return emittedAny.get()
    }
}
