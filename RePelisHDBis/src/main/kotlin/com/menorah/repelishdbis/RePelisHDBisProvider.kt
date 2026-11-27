package com.menorah.repelishdbis

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicBoolean

class RePelisHDBisProvider : MainAPI() {
    override var mainUrl = RePelisHDBisMarkup.DEFAULT_MAIN_URL
    override var name = "repelishd.surf bis"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/explorar?sort=popular" to "Populares",
        "$mainUrl/cine" to "En Cines",
        "$mainUrl/tendencias" to "Tendencias",
        "$mainUrl/top-espana" to "Top España",
        "$mainUrl/explorar?t=tv&sort=popular" to "Series",
        "$mainUrl/explorar?g=accion&sort=popular" to "Acción",
        "$mainUrl/explorar?g=animacion&sort=popular" to "Animación",
        "$mainUrl/explorar?g=aventura&sort=popular" to "Aventura",
        "$mainUrl/explorar?g=ciencia-ficcion&sort=popular" to "Ciencia Ficción",
        "$mainUrl/explorar?g=comedia&sort=popular" to "Comedia",
        "$mainUrl/explorar?g=crimen&sort=popular" to "Crimen",
        "$mainUrl/explorar?g=drama&sort=popular" to "Drama",
        "$mainUrl/explorar?g=fantasia&sort=popular" to "Fantasía",
        "$mainUrl/explorar?g=misterio&sort=popular" to "Misterio",
        "$mainUrl/explorar?g=romance&sort=popular" to "Romance",
        "$mainUrl/explorar?g=suspense&sort=popular" to "Suspense",
        "$mainUrl/explorar?g=terror&sort=popular" to "Terror",
    )

    private val defaultHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    private fun RePelisHDCard.toSearchResponse(): SearchResponse =
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
        val targetUrl = RePelisHDBisMarkup.buildMainPageUrl(request.data, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        val doc = Jsoup.parse(res.text, res.url)
        val items = RePelisHDBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = RePelisHDBisMarkup.parseHasNext(doc, page, items.size)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = RePelisHDBisMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        val doc = Jsoup.parse(res.text, res.url)
        val items = RePelisHDBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = RePelisHDBisMarkup.parseHasNext(doc, page, items.size)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val resolved = RePelisHDBisMarkup.resolveUrl(mainUrl, url) ?: url
        val res = app.get(resolved, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        if (!res.isSuccessful) return null
        val doc = Jsoup.parse(res.text, res.url)
        val detail = RePelisHDBisMarkup.parseDetail(doc, res.url, mainUrl) ?: return null

        return if (detail.isSeries) {
            val allEpisodes = mutableListOf<RePelisHDEpisode>()
            val ini = detail.iniziale
            if (ini != null) {
                allEpisodes += ini.episodes
                val loadedSeason = ini.activeSeason ?: ini.episodes.firstOrNull()?.season
                val extraSeasons = ini.seasons.filter { it.seasonNumber != loadedSeason && it.availableCount > 0 }
                val basePath = ini.basePath
                if (basePath != null && extraSeasons.isNotEmpty()) {
                    val fetchedSeasons = extraSeasons.amap { s ->
                        val sUrl = RePelisHDBisMarkup.resolveUrl(mainUrl, "$basePath/temporada-${s.seasonNumber}/episodio-1")
                            ?: return@amap emptyList()
                        runCatching {
                            val sRes = app.get(sUrl, referer = res.url, headers = defaultHeaders, timeout = 20L)
                            if (sRes.isSuccessful) {
                                RePelisHDBisMarkup.parseIniziale(Jsoup.parse(sRes.text, sRes.url), mainUrl)?.episodes.orEmpty()
                            } else emptyList()
                        }.getOrDefault(emptyList())
                    }
                    fetchedSeasons.forEach { allEpisodes += it }
                }
            }
            val uniqueEpisodes = allEpisodes
                .distinctBy { it.season to it.episode }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .map { ep ->
                    newEpisode(ep.playDataUrl) {
                        this.name = ep.title
                        this.season = ep.season
                        this.episode = ep.episode
                        this.description = ep.overview
                        this.posterUrl = ep.posterUrl
                    }
                }

            newTvSeriesLoadResponse(detail.title, detail.url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                this.plot = detail.plot
                this.year = detail.year
                detail.rating10?.let { this.score = Score.from10(it) }
                detail.imdbId?.let { addImdbId(it) }
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) addActors(detail.actors)
                detail.trailerUrl?.let { addTrailer(it) }
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map { it.toSearchResponse() }
                }
            }
        } else {
            val movieData = detail.playerUrl ?: detail.url
            newMovieLoadResponse(detail.title, detail.url, TvType.Movie, movieData) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                this.plot = detail.plot
                this.year = detail.year
                detail.durationMinutes?.let { addDuration(it) }
                detail.rating10?.let { this.score = Score.from10(it) }
                detail.imdbId?.let { addImdbId(it) }
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
        var playlatinUrl = data.trim()
        if (playlatinUrl.contains("repelishd.surf")) {
            val pageRes = app.get(playlatinUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
            if (!pageRes.isSuccessful) return false
            val doc = Jsoup.parse(pageRes.text, pageRes.url)
            val detail = RePelisHDBisMarkup.parseDetail(doc, pageRes.url, mainUrl)
            playlatinUrl = detail?.playerUrl
                ?: detail?.iniziale?.let { ini ->
                    val s = ini.activeSeason ?: 1
                    val e = ini.activeEpisode ?: 1
                    ini.imdbId?.let { "${RePelisHDBisMarkup.PLAYLATIN_BASE}/serial/$it/$s/$e?lang=latino" }
                }
                ?: return false
        }

        val playerHeaders = defaultHeaders + mapOf("Referer" to "$mainUrl/")
        val playerRes = app.get(playlatinUrl, headers = playerHeaders, timeout = 20L)
        if (!playerRes.isSuccessful) return false
        val playerHtml = playerRes.text

        val spec = if (playlatinUrl.contains("/serial/")) {
            PlayLatinSrcParser.extractSerialRequest(playerHtml, playlatinUrl)
        } else {
            PlayLatinSrcParser.extractMovieRequest(playerHtml)
        } ?: return false

        val endpointUrl = RePelisHDBisMarkup.resolveUrl(RePelisHDBisMarkup.PLAYLATIN_BASE, spec.endpoint)
            ?: return false

        val apiHeaders = mapOf(
            "user-agent" to defaultHeaders["user-agent"]!!,
            "Referer" to playlatinUrl,
            "Origin" to RePelisHDBisMarkup.PLAYLATIN_BASE,
            "Accept" to "application/json, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val body = spec.payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val apiRes = app.post(endpointUrl, headers = apiHeaders, requestBody = body, timeout = 20L)
        if (!apiRes.isSuccessful) return false

        val resolved = PlayLatinSrcParser.parseApiResponse(apiRes.text)
        for ((lbl, subUrl) in resolved.captions) {
            subtitleCallback(newSubtitleFile(lbl, subUrl))
        }

        val emitted = AtomicBoolean(false)
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            emitted.set(true)
            callback(link)
        }

        resolved.sources.amap { src ->
            if (src.kind.equals("hls", ignoreCase = true) || src.url.contains(".m3u8")) {
                wrappedCallback(
                    newExtractorLink(source = src.name, name = src.name, url = src.url, type = ExtractorLinkType.M3U8) {
                        this.referer = playlatinUrl
                        this.quality = Qualities.P1080.value
                    },
                )
            } else {
                runCatching {
                    loadExtractor(src.url, playlatinUrl, subtitleCallback, wrappedCallback)
                }
            }
        }
        return emitted.get()
    }
}
