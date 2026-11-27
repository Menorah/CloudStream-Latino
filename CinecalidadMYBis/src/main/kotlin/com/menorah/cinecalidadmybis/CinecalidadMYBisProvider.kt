package com.menorah.cinecalidadmybis

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class CinecalidadMYBisProvider : MainAPI() {
    override var mainUrl = CinecalidadMYBisMarkup.DEFAULT_MAIN_URL
    override var name = "cinecalidad.my bis"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimas Películas",
        "$mainUrl/genero-peliculas/destacada/" to "Destacadas",
        "$mainUrl/peliculas/4k-ultra-hd/" to "4K UHD",
        "$mainUrl/genero-peliculas/accion/" to "Acción",
        "$mainUrl/genero-peliculas/animacion/" to "Animación",
        "$mainUrl/genero-peliculas/aventura/" to "Aventura",
        "$mainUrl/genero-peliculas/biografia/" to "Biografía",
        "$mainUrl/genero-peliculas/ciencia-ficcion/" to "Ciencia Ficción",
        "$mainUrl/genero-peliculas/comedia/" to "Comedia",
        "$mainUrl/genero-peliculas/crimen/" to "Crimen",
        "$mainUrl/genero-peliculas/drama/" to "Drama",
        "$mainUrl/genero-peliculas/familiar/" to "Familiar",
        "$mainUrl/genero-peliculas/fantasia/" to "Fantasía",
        "$mainUrl/genero-peliculas/historia/" to "Historia",
        "$mainUrl/genero-peliculas/misterio/" to "Misterio",
        "$mainUrl/genero-peliculas/romance/" to "Romance",
        "$mainUrl/genero-peliculas/suspenso/" to "Suspenso",
        "$mainUrl/genero-peliculas/terror/" to "Terror",
    )

    private val defaultHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
    )

    private fun CinecalidadMYCard.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = this@toSearchResponse.posterUrl
            this.year = this@toSearchResponse.year
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = CinecalidadMYBisMarkup.buildArchivePageUrl(request.data, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        val doc = Jsoup.parse(res.text, res.url)
        val items = CinecalidadMYBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && CinecalidadMYBisMarkup.parseHasNext(doc, page)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val targetUrl = CinecalidadMYBisMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        val doc = Jsoup.parse(res.text, res.url)
        val items = CinecalidadMYBisMarkup.parseCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && CinecalidadMYBisMarkup.parseHasNext(doc, page)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val resolved = CinecalidadMYBisMarkup.resolveUrl(mainUrl, url) ?: url
        val res = app.get(resolved, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        if (!res.isSuccessful) return null
        val doc = Jsoup.parse(res.text, res.url)
        val detail = CinecalidadMYBisMarkup.parseMovieDetail(doc, res.url, mainUrl) ?: return null
        return newMovieLoadResponse(detail.title, detail.url, TvType.Movie, detail.url) {
            this.posterUrl = detail.posterUrl
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val targetUrl = CinecalidadMYBisMarkup.resolveUrl(mainUrl, data) ?: data
        val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 20L)
        if (!res.isSuccessful) return false
        val doc = Jsoup.parse(res.text, res.url)
        val options = CinecalidadMYBisMarkup.parseOnlineOptions(doc, mainUrl)
        val emitted = AtomicBoolean(false)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lock = Any()

        options.amap { option ->
            for (embedUrl in option.embedUrls) {
                val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                runCatching {
                    when {
                        embedUrl.contains("bysedikamoum.com", ignoreCase = true) ->
                            CinecalidadMYBysedikamoumExtractor().getUrl(embedUrl, targetUrl, subtitleCallback) { found.add(it) }
                        embedUrl.contains("voe.", ignoreCase = true) ||
                            embedUrl.contains("caseyseemavoid.", ignoreCase = true) ->
                            CinecalidadMYVoeExtractor().getUrl(embedUrl, targetUrl, subtitleCallback) { found.add(it) }
                        else ->
                            loadExtractor(embedUrl, targetUrl, subtitleCallback) { found.add(it) }
                    }
                }
                val links = synchronized(found) { found.toList() }
                if (links.isNotEmpty()) {
                    for (link in links) {
                        val labeled = labelLink(link, option.language, option.label)
                        if (!seen.add("${labeled.url}|${labeled.name}")) continue
                        synchronized(lock) {
                            emitted.set(true)
                            callback(labeled)
                        }
                    }
                    break
                }
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
