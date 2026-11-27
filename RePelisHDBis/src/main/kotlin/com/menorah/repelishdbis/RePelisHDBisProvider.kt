package com.menorah.repelishdbis

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class RePelisHDBisProvider : MainAPI() {
    override var mainUrl = RePelisHDBisMarkup.DEFAULT_MAIN_URL
    override var name = "repelishd.hair bis"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/explorar?sort=popular" to "Populares",
        "$mainUrl/explorar?sort=reciente" to "Recientes",
        "$mainUrl/cine" to "En Cines",
        "$mainUrl/tendencias" to "Tendencias",
        "$mainUrl/top-espana" to "Top España",
        "$mainUrl/explorar?t=movie&sort=popular" to "Películas",
        "$mainUrl/explorar?t=tv&sort=popular" to "Series",
        "$mainUrl/explorar?ver=latino&sort=popular" to "Audio Latino",
        "$mainUrl/explorar?ver=castellano&sort=popular" to "Audio Castellano",
        "$mainUrl/explorar?ver=subtitulado&sort=popular" to "Subtitulado",
        "$mainUrl/explorar?g=accion&sort=popular" to "Acción",
        "$mainUrl/explorar?g=animacion&sort=popular" to "Animación",
        "$mainUrl/explorar?g=aventura&sort=popular" to "Aventura",
        "$mainUrl/explorar?g=belica&sort=popular" to "Bélica",
        "$mainUrl/explorar?g=ciencia-ficcion&sort=popular" to "Ciencia Ficción",
        "$mainUrl/explorar?g=comedia&sort=popular" to "Comedia",
        "$mainUrl/explorar?g=crimen&sort=popular" to "Crimen",
        "$mainUrl/explorar?g=documental&sort=popular" to "Documental",
        "$mainUrl/explorar?g=drama&sort=popular" to "Drama",
        "$mainUrl/explorar?g=familia&sort=popular" to "Familia",
        "$mainUrl/explorar?g=fantasia&sort=popular" to "Fantasía",
        "$mainUrl/explorar?g=historia&sort=popular" to "Historia",
        "$mainUrl/explorar?g=kids&sort=popular" to "Infantil",
        "$mainUrl/explorar?g=misterio&sort=popular" to "Misterio",
        "$mainUrl/explorar?g=musica&sort=popular" to "Música",
        "$mainUrl/explorar?g=romance&sort=popular" to "Romance",
        "$mainUrl/explorar?g=suspense&sort=popular" to "Suspense",
        "$mainUrl/explorar?g=terror&sort=popular" to "Terror",
        "$mainUrl/explorar?g=western&sort=popular" to "Western",
    )

    private val cfKiller by lazy { CloudflareKiller() }

    private val defaultHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    private suspend fun getSiteDocument(url: String, referer: String = "$mainUrl/") =
        app.get(url, referer = referer, headers = defaultHeaders, interceptor = cfKiller, timeout = 25L)

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
        val res = getSiteDocument(targetUrl)
        val doc = Jsoup.parse(res.text, res.url)
        val items = RePelisHDBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = RePelisHDBisMarkup.parseHasNext(doc, page, items.size)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = RePelisHDBisMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val res = getSiteDocument(targetUrl)
        val doc = Jsoup.parse(res.text, res.url)
        val items = RePelisHDBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = RePelisHDBisMarkup.parseHasNext(doc, page, items.size)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val resolved = RePelisHDBisMarkup.resolveUrl(mainUrl, url) ?: url
        val res = getSiteDocument(resolved)
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
                            val sRes = getSiteDocument(sUrl, referer = res.url)
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
                detail.durationMinutes?.let { this.duration = it }
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
        var detailFallback: RePelisHDDetail? = null
        if (!playlatinUrl.contains("playlatinsrc.co", ignoreCase = true)) {
            val pageRes = getSiteDocument(playlatinUrl)
            if (!pageRes.isSuccessful) return false
            val doc = Jsoup.parse(pageRes.text, pageRes.url)
            detailFallback = RePelisHDBisMarkup.parseDetail(doc, pageRes.url, mainUrl)
            playlatinUrl = detailFallback?.playerUrl
                ?: detailFallback?.iniziale?.let { ini ->
                    val s = ini.activeSeason ?: 1
                    val e = ini.activeEpisode ?: 1
                    ini.imdbId?.let { "${RePelisHDBisMarkup.PLAYLATIN_BASE}/serial/$it/$s/$e?lang=latino" }
                }
                ?: ""
        }

        val emitted = AtomicBoolean(false)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lock = Any()

        suspend fun emitLabeled(link: ExtractorLink, langTag: String, fallbackHost: String) {
            val hostPart = link.name.trim().ifEmpty { fallbackHost }
            val labeled = labelLink(link, langTag, hostPart)
            if (!seen.add("${labeled.url}|${labeled.name}")) return
            synchronized(lock) {
                emitted.set(true)
                callback(labeled)
            }
        }

        if (playlatinUrl.isNotEmpty()) {
            val playerHeaders = defaultHeaders + mapOf("Referer" to "$mainUrl/")
            val playerRes = app.get(playlatinUrl, headers = playerHeaders, timeout = 20L)
            if (playerRes.isSuccessful) {
                val playerHtml = playerRes.text
                val specs = PlayLatinSrcParser.extractResolveSpecs(playerHtml, playlatinUrl)
                val allSources = mutableListOf<PlayLatinSrcParser.ResolvedSource>()

                for (spec in specs) {
                    val endpointUrl = RePelisHDBisMarkup.resolveUrl(RePelisHDBisMarkup.PLAYLATIN_BASE, spec.endpoint)
                        ?: continue
                    val apiHeaders = mapOf(
                        "user-agent" to defaultHeaders["user-agent"]!!,
                        "Referer" to playlatinUrl,
                        "Origin" to RePelisHDBisMarkup.PLAYLATIN_BASE,
                        "Accept" to "application/json, */*",
                        "X-Requested-With" to "XMLHttpRequest",
                    )
                    val body = spec.payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                    val apiRes = runCatching {
                        app.post(endpointUrl, headers = apiHeaders, requestBody = body, timeout = 20L)
                    }.getOrNull() ?: continue
                    if (!apiRes.isSuccessful) continue

                    val resolved = PlayLatinSrcParser.parseApiResponse(apiRes.text)
                    for ((lbl, subUrl) in resolved.captions) {
                        subtitleCallback(newSubtitleFile(lbl, subUrl))
                    }
                    if (resolved.sources.isNotEmpty()) {
                        allSources += resolved.sources
                        break
                    }
                }

                if (allSources.isEmpty()) {
                    PlayLatinSrcParser.extractAltUrl(playerHtml)?.let { alt ->
                        allSources += PlayLatinSrcParser.ResolvedSource(
                            name = "Servidor",
                            url = alt,
                            version = PlayLatinSrcParser.normalizeVersionLabel(null, playlatinUrl),
                            kind = "embed",
                        )
                    }
                }

                allSources.amap { src ->
                    val langTag = PlayLatinSrcParser.normalizeVersionLabel(src.version, playlatinUrl)
                    if (src.kind.equals("hls", ignoreCase = true) || src.url.contains(".m3u8")) {
                        val raw = newExtractorLink(source = src.name, name = src.name, url = src.url, type = ExtractorLinkType.M3U8) {
                            this.referer = playlatinUrl
                            this.quality = Qualities.P1080.value
                        }
                        emitLabeled(raw, langTag, src.name)
                    } else {
                        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        runCatching {
                            when {
                                src.url.contains("dr0pstream.com", ignoreCase = true) ->
                                    RePelisHDDr0pstreamExtractor().getUrl(src.url, playlatinUrl, subtitleCallback) { found.add(it) }
                                src.url.contains("dropload.io", ignoreCase = true) ->
                                    RePelisHDDroploadIoExtractor().getUrl(src.url, playlatinUrl, subtitleCallback) { found.add(it) }
                                src.url.contains("superplayer.ws", ignoreCase = true) ->
                                    RePelisHDSuperplayerExtractor().getUrl(src.url, playlatinUrl, subtitleCallback) { found.add(it) }
                                src.url.contains("supervideo.tv", ignoreCase = true) ->
                                    RePelisHDSupervideoExtractor().getUrl(src.url, playlatinUrl, subtitleCallback) { found.add(it) }
                                else ->
                                    loadExtractor(src.url, playlatinUrl, subtitleCallback) { found.add(it) }
                            }
                        }
                        for (link in synchronized(found) { found.toList() }) {
                            emitLabeled(link, langTag, src.name)
                        }
                    }
                }
            }
        }

        // Optional direct host download mirrors (.dl-box a.dl-btn) if present and not yet resolved
        detailFallback?.downloadLinks.orEmpty().amap { dl ->
            val langTag = dl.language ?: "Latino"
            val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            runCatching {
                loadExtractor(dl.url, detailFallback?.url ?: "$mainUrl/", subtitleCallback) { found.add(it) }
            }
            for (link in synchronized(found) { found.toList() }) {
                emitLabeled(link, langTag, dl.host)
            }
        }

        return emitted.get()
    }

    private suspend fun labelLink(link: ExtractorLink, language: String, hostFallback: String): ExtractorLink {
        val hostPart = link.name.trim().ifEmpty { hostFallback }
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
