package com.menorah.repelis24shop

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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

class RePelis24ShopProvider : MainAPI() {
    override var mainUrl = RePelis24ShopMarkup.DEFAULT_MAIN_URL
    override var name = "RePelis24.shop"
    override val hasMainPage = true
    override var lang = "es-419"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Destacados",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/peliculas/estrenos" to "Estrenos",
        "$mainUrl/genero/accion" to "Acción",
        "$mainUrl/genero/animacion" to "Animación",
        "$mainUrl/genero/anime" to "Anime",
        "$mainUrl/genero/aventura" to "Aventura",
        "$mainUrl/genero/belica" to "Bélica",
        "$mainUrl/genero/ciencia-ficcion" to "Ciencia Ficción",
        "$mainUrl/genero/comedia" to "Comedia",
        "$mainUrl/genero/crimen" to "Crimen",
        "$mainUrl/genero/dc" to "DC Comics",
        "$mainUrl/genero/disney" to "Disney+",
        "$mainUrl/genero/documental" to "Documental",
        "$mainUrl/genero/drama" to "Drama",
        "$mainUrl/genero/familia" to "Familia",
        "$mainUrl/genero/fantasia" to "Fantasía",
        "$mainUrl/genero/marvel" to "Marvel",
        "$mainUrl/genero/musical" to "Musical",
        "$mainUrl/genero/romance" to "Romance",
        "$mainUrl/genero/suspense" to "Suspense",
        "$mainUrl/genero/terror" to "Terror",
    )

    private val cfKiller by lazy { CloudflareKiller() }

    private val defaultHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    private fun buildPosterHeaders(targetUrl: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Referer"] = "$mainUrl/"
        runCatching {
            val cfHeaders = cfKiller.getCookieHeaders(targetUrl ?: mainUrl)
            val cookie = cfHeaders["cookie"] ?: cfHeaders["Cookie"]
            if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
            val ua = cfHeaders["user-agent"] ?: cfHeaders["User-Agent"]
            if (!ua.isNullOrBlank()) h["User-Agent"] = ua
        }
        return h
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/") =
        app.get(
            url,
            referer = referer,
            headers = defaultHeaders,
            interceptor = cfKiller,
            timeout = 25L,
        )

    private fun RePelis24ShopCard.toSearchResponse(pHeaders: Map<String, String>): SearchResponse =
        if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.posterHeaders = pHeaders
                this.year = this@toSearchResponse.year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.posterHeaders = pHeaders
                this.year = this@toSearchResponse.year
            }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = RePelis24ShopMarkup.buildSectionPageUrl(request.data, page, mainUrl)
        val res = getDocument(targetUrl)
        val doc = Jsoup.parse(res.text, res.url)
        val pHeaders = buildPosterHeaders(res.url)
        val items = RePelis24ShopMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse(pHeaders) }
        val hasNext = items.isNotEmpty() && RePelis24ShopMarkup.parseHasNext(doc, page)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = RePelis24ShopMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val res = getDocument(targetUrl)
        val doc = Jsoup.parse(res.text, res.url)
        val pHeaders = buildPosterHeaders(res.url)
        val items = RePelis24ShopMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse(pHeaders) }
        val hasNext = items.isNotEmpty() && RePelis24ShopMarkup.parseHasNext(doc, page)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val resolved = RePelis24ShopMarkup.resolveUrl(mainUrl, url) ?: url
        val res = getDocument(resolved)
        if (!res.isSuccessful) return null
        val doc = Jsoup.parse(res.text, res.url)
        val detail = RePelis24ShopMarkup.parseDetail(doc, res.url, mainUrl) ?: return null
        val pHeaders = buildPosterHeaders(res.url)

        return if (detail.isSeries) {
            val allEpisodes = mutableListOf<RePelis24ShopEpisodeLink>()
            if (detail.seasonLinks.isNotEmpty()) {
                val fetched = detail.seasonLinks.amap { s ->
                    runCatching {
                        val sRes = getDocument(s.url, referer = res.url)
                        if (sRes.isSuccessful) {
                            val sDoc = Jsoup.parse(sRes.text, sRes.url)
                            RePelis24ShopMarkup.parseEpisodeLinks(sDoc, mainUrl, fallbackSeason = s.seasonNumber)
                        } else emptyList()
                    }.getOrDefault(emptyList())
                }
                fetched.forEach { allEpisodes += it }
            }
            if (allEpisodes.isEmpty()) {
                allEpisodes += detail.episodeLinks
            }
            // Episode thumbnails on /cdn/e/ require Referer and Episode has no posterHeaders; omit broken thumbnails.
            val episodes = allEpisodes
                .distinctBy { it.season to it.episode }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.title
                        this.season = ep.season
                        this.episode = ep.episode
                    }
                }

            newTvSeriesLoadResponse(detail.title, detail.url, TvType.TvSeries, episodes) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                this.posterHeaders = pHeaders
                this.plot = detail.plot
                this.year = detail.year
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) addActors(detail.actors)
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map { it.toSearchResponse(pHeaders) }
                }
            }
        } else {
            newMovieLoadResponse(detail.title, detail.url, TvType.Movie, detail.url) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundUrl ?: detail.posterUrl
                this.posterHeaders = pHeaders
                this.plot = detail.plot
                this.year = detail.year
                detail.durationMinutes?.let { this.duration = it }
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) addActors(detail.actors)
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map { it.toSearchResponse(pHeaders) }
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
        val targetUrl = RePelis24ShopMarkup.resolveUrl(mainUrl, data) ?: data
        val res = getDocument(targetUrl)
        if (!res.isSuccessful) return false
        val doc = Jsoup.parse(res.text, res.url)
        val options = RePelis24ShopMarkup.parseServerOptions(doc)
        val emitted = AtomicBoolean(false)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lock = Any()

        options.amap { opt ->
            val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            runCatching {
                when {
                    opt.url.contains("nupload.my", ignoreCase = true) ->
                        NuploadMyExtractor().getUrl(opt.url, targetUrl, subtitleCallback) { found.add(it) }
                    opt.url.contains("voe.", ignoreCase = true) ||
                        opt.url.contains("caseyseemavoid.", ignoreCase = true) ->
                        RePelis24ShopVoeExtractor().getUrl(opt.url, targetUrl, subtitleCallback) { found.add(it) }
                    else ->
                        loadExtractor(opt.url, targetUrl, subtitleCallback) { found.add(it) }
                }
            }
            for (link in synchronized(found) { found.toList() }) {
                val labeled = labelLink(link, opt.language, opt.label)
                if (!seen.add("${labeled.url}|${labeled.name}")) continue
                synchronized(lock) {
                    emitted.set(true)
                    callback(labeled)
                }
            }
        }
        return emitted.get()
    }

    private suspend fun labelLink(link: ExtractorLink, language: String, optionLabel: String): ExtractorLink {
        val hostPart = link.name.trim().ifEmpty { optionLabel }
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
