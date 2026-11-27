package com.menorah.repelis24czbis

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
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
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicBoolean

class Repelis24CZBisProvider : MainAPI() {
    override var mainUrl = Repelis24CZBisMarkup.DEFAULT_MAIN_URL
    override var name = "RePelis24.cz bis"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Destacadas",
        "$mainUrl/peliculas/" to "Películas",
        "$mainUrl/series/" to "Series",
        "$mainUrl/genero/accion/" to "Acción",
        "$mainUrl/genero/animacion/" to "Animación",
        "$mainUrl/genero/aventura/" to "Aventura",
        "$mainUrl/genero/belica/" to "Bélica",
        "$mainUrl/genero/ciencia-ficcion/" to "Ciencia Ficción",
        "$mainUrl/genero/comedia/" to "Comedia",
        "$mainUrl/genero/crimen/" to "Crimen",
        "$mainUrl/genero/documental/" to "Documental",
        "$mainUrl/genero/drama/" to "Drama",
        "$mainUrl/genero/familia/" to "Familia",
        "$mainUrl/genero/fantasia/" to "Fantasía",
        "$mainUrl/genero/historia/" to "Historia",
        "$mainUrl/genero/misterio/" to "Misterio",
        "$mainUrl/genero/romance/" to "Romance",
        "$mainUrl/genero/suspense/" to "Suspense",
        "$mainUrl/genero/terror/" to "Terror",
    )

    private fun Repelis24CZCard.toSearchResponse(): SearchResponse =
        if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
            }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = Repelis24CZBisMarkup.buildArchivePageUrl(request.data, page, mainUrl)
        val html = Repelis24CZSession.fetchHtml(targetUrl, "$mainUrl/") ?: return newHomePageResponse(request, emptyList(), false)
        val doc = Jsoup.parse(html, targetUrl)
        val items = Repelis24CZBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && Repelis24CZBisMarkup.parseHasNext(doc, page)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = Repelis24CZBisMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val html = Repelis24CZSession.fetchHtml(targetUrl, "$mainUrl/") ?: return newSearchResponseList(emptyList(), false)
        val doc = Jsoup.parse(html, targetUrl)
        val items = Repelis24CZBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && Repelis24CZBisMarkup.parseHasNext(doc, page)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val resolved = Repelis24CZBisMarkup.resolveUrl(mainUrl, url) ?: url
        val html = Repelis24CZSession.fetchHtml(resolved, "$mainUrl/") ?: return null
        val doc = Jsoup.parse(html, resolved)
        val detail = Repelis24CZBisMarkup.parseDetail(doc, resolved, mainUrl) ?: return null

        return if (detail.isSeries) {
            val eps = detail.episodes.map { ep ->
                val epData = Repelis24CZBisMarkup.encodeEpisodeData(ep.episodeId, detail.url)
                newEpisode(epData) {
                    this.name = ep.title
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = ep.posterUrl
                }
            }
            newTvSeriesLoadResponse(detail.title, detail.url, TvType.TvSeries, eps) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                this.plot = detail.plot
                this.year = detail.year
                detail.rating10?.let { this.score = Score.from10(it) }
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) addActors(detail.actors)
                detail.trailerUrl?.let { addTrailer(it) }
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map { it.toSearchResponse() }
                }
            }
        } else {
            newMovieLoadResponse(detail.title, detail.url, TvType.Movie, detail.url) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                this.plot = detail.plot
                this.year = detail.year
                detail.durationMinutes?.let { addDuration(it) }
                detail.rating10?.let { this.score = Score.from10(it) }
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) addActors(detail.actors)
                detail.trailerUrl?.let { addTrailer(it) }
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map { it.toSearchResponse() }
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
        val ajaxUrl = "${mainUrl.trimEnd('/')}/wp-admin/admin-ajax.php"
        val epPair = Repelis24CZBisMarkup.decodeEpisodeData(data)

        val (refererUrl, options) = if (epPair != null) {
            val (epId, seriesUrl) = epPair
            val hubHtml = Repelis24CZSession.postAjax(
                ajaxUrl = ajaxUrl,
                referer = seriesUrl,
                params = mapOf(
                    "action" to "dpu_get_episode_player",
                    "episode_id" to epId,
                ),
            ) ?: return false
            seriesUrl to Repelis24CZBisMarkup.parsePlayerOptions(Jsoup.parse(hubHtml, seriesUrl))
        } else {
            val movieUrl = Repelis24CZBisMarkup.resolveUrl(mainUrl, data) ?: data
            val html = Repelis24CZSession.fetchHtml(movieUrl, "$mainUrl/") ?: return false
            movieUrl to Repelis24CZBisMarkup.parsePlayerOptions(Jsoup.parse(html, movieUrl))
        }

        if (options.isEmpty()) return false

        val embedUrls = options.amap { opt ->
            val ajaxBody = Repelis24CZSession.postAjax(
                ajaxUrl = ajaxUrl,
                referer = refererUrl,
                params = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to opt.postId,
                    "nume" to opt.nume,
                    "type" to opt.type,
                ),
            ) ?: return@amap null
            Repelis24CZBisMarkup.parseDooPlayerAjaxResponse(ajaxBody, mainUrl)
        }.filterNotNull().distinct()

        val emitted = AtomicBoolean(false)
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            emitted.set(true)
            callback(link)
        }
        embedUrls.amap { embed ->
            runCatching {
                loadExtractor(embed, refererUrl, subtitleCallback, wrappedCallback)
            }
        }
        return emitted.get()
    }
}
