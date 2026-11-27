package com.menorah.cinecalidadambis

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class CinecalidadAMBisProvider : MainAPI() {
    override var mainUrl = CinecalidadAMBisMarkup.DEFAULT_MAIN_URL
    override var name = "cinecalidad.am bis"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/#destacado" to "Destacadas",
        "$mainUrl/" to "Estrenos / Películas",
        "$mainUrl/ver-serie/" to "Series",
        "$mainUrl/genero-de-la-pelicula/accion/" to "Acción",
        "$mainUrl/genero-de-la-pelicula/action-adventure/" to "Acción y Aventura",
        "$mainUrl/genero-de-la-pelicula/animacion/" to "Animación",
        "$mainUrl/genero-de-la-pelicula/aventura/" to "Aventura",
        "$mainUrl/genero-de-la-pelicula/belica/" to "Bélico",
        "$mainUrl/genero-de-la-pelicula/ciencia-ficcion/" to "Ciencia Ficción",
        "$mainUrl/genero-de-la-pelicula/sci-fi-fantasy/" to "Ciencia Ficción y Fantasía",
        "$mainUrl/genero-de-la-pelicula/comedia/" to "Comedia",
        "$mainUrl/genero-de-la-pelicula/crimen/" to "Crimen",
        "$mainUrl/genero-de-la-pelicula/peliculas-de-dc-comics-online-cinecalidad/" to "DC Comics",
        "$mainUrl/genero-de-la-pelicula/documental/" to "Documental",
        "$mainUrl/genero-de-la-pelicula/drama/" to "Drama",
        "$mainUrl/genero-de-la-pelicula/familia/" to "Familiar",
        "$mainUrl/genero-de-la-pelicula/fantasia/" to "Fantasía",
        "$mainUrl/genero-de-la-pelicula/historia/" to "Historia",
        "$mainUrl/genero-de-la-pelicula/universo-marvel/" to "Marvel",
        "$mainUrl/genero-de-la-pelicula/misterio/" to "Misterio",
        "$mainUrl/genero-de-la-pelicula/musica/" to "Música",
        "$mainUrl/genero-de-la-pelicula/pelicula-de-tv/" to "Película de TV",
        "$mainUrl/genero-de-la-pelicula/romance/" to "Romance",
        "$mainUrl/genero-de-la-pelicula/suspense/" to "Suspenso",
        "$mainUrl/genero-de-la-pelicula/terror/" to "Terror",
        "$mainUrl/genero-de-la-pelicula/western/" to "Western",
    )

    private val defaultHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    private fun CinecalidadAMCard.toSearchResponse(): SearchResponse =
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
        if (request.data.endsWith("#destacado")) {
            if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
            val res = app.get("$mainUrl/", referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
            val doc = Jsoup.parse(res.text, res.url)
            val items = CinecalidadAMBisMarkup.parseFeaturedCards(doc, mainUrl).map { it.toSearchResponse() }
            return newHomePageResponse(request, items, hasNext = false)
        }

        val targetUrl = CinecalidadAMBisMarkup.buildArchivePageUrl(request.data, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        val doc = Jsoup.parse(res.text, res.url)
        val items = CinecalidadAMBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && CinecalidadAMBisMarkup.parseHasNext(doc, page)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = CinecalidadAMBisMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        val doc = Jsoup.parse(res.text, res.url)
        val items = CinecalidadAMBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && CinecalidadAMBisMarkup.parseHasNext(doc, page)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val resolved = CinecalidadAMBisMarkup.resolveUrl(mainUrl, url) ?: url
        val res = app.get(resolved, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        if (!res.isSuccessful) return null
        val doc = Jsoup.parse(res.text, res.url)
        val detail = CinecalidadAMBisMarkup.parseDetail(doc, res.url, mainUrl) ?: return null

        return if (detail.isSeries) {
            val eps = detail.episodes.map { ep ->
                newEpisode(ep.url) {
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
        val targetUrl = CinecalidadAMBisMarkup.resolveUrl(mainUrl, data) ?: data
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        if (!res.isSuccessful) return false
        val doc = Jsoup.parse(res.text, res.url)
        val (options, _) = CinecalidadAMBisMarkup.parsePlayerOptions(doc)
        val emitted = AtomicBoolean(false)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lock = Any()

        options.amap { opt ->
            val langTag = opt.language ?: "Latino"
            val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            runCatching {
                when {
                    opt.url.contains("vimeos.net", ignoreCase = true) ->
                        CinecalidadAMVimeosExtractor().getUrl(opt.url, targetUrl, subtitleCallback) { found.add(it) }
                    opt.url.contains("videoapp.zip", ignoreCase = true) ->
                        CinecalidadAMVideoappExtractor().getUrl(opt.url, targetUrl, subtitleCallback) { found.add(it) }
                    else ->
                        loadExtractor(opt.url, targetUrl, subtitleCallback) { found.add(it) }
                }
            }
            for (link in synchronized(found) { found.toList() }) {
                val labeled = labelLink(link, langTag, opt.name)
                if (!seen.add("${labeled.url}|${labeled.name}")) continue
                synchronized(lock) {
                    emitted.set(true)
                    callback(labeled)
                }
            }
        }
        return emitted.get()
    }

    private suspend fun labelLink(link: ExtractorLink, language: String, serverFallback: String): ExtractorLink {
        val hostPart = link.name.trim().ifEmpty { serverFallback }
        val finalName = "[$language] $hostPart"
        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, finalName, link.url, link.type, link.uuid) {
                referer = link.referer
                quality = link.quality
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
        return newExtractorLink(link.source, finalName, link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }
}
