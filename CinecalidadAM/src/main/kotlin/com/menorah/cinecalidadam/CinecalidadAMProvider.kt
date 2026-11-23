package com.menorah.cinecalidadam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class CinecalidadAMProvider : MainAPI() {
    override var mainUrl = CinecalidadAMMarkup.BASE
    override var name = "Cinecalidad.am"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val latestYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).coerceIn(2026, 2100)
    override val mainPage = mainPageOf(
        "/" to "Películas y series", "#destacado" to "Destacados", "/ver-serie/" to "Series",
        "/genero-de-la-pelicula/accion/" to "Acción", "/genero-de-la-pelicula/animacion/" to "Animación",
        "/genero-de-la-pelicula/aventura/" to "Aventura", "/genero-de-la-pelicula/belica/" to "Bélica",
        "/genero-de-la-pelicula/ciencia-ficcion/" to "Ciencia Ficción", "/genero-de-la-pelicula/comedia/" to "Comedia",
        "/genero-de-la-pelicula/crimen/" to "Crimen", "/genero-de-la-pelicula/documental/" to "Documental",
        "/genero-de-la-pelicula/drama/" to "Drama", "/genero-de-la-pelicula/familia/" to "Familia",
        "/genero-de-la-pelicula/fantasia/" to "Fantasía", "/genero-de-la-pelicula/historia/" to "Historia",
        "/genero-de-la-pelicula/musica/" to "Música", "/genero-de-la-pelicula/misterio/" to "Misterio",
        "/genero-de-la-pelicula/terror/" to "Terror", "/genero-de-la-pelicula/suspense/" to "Suspenso",
        "/genero-de-la-pelicula/romance/" to "Romance"
    ) + (latestYear downTo latestYear - 7).map { year -> mainPage("/release/$year/", "Por año · $year") }

    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(CinecalidadAMMarkup.siteUrl(url)) { "Dirección fuera de Cinecalidad.am" }
        val res = app.get(url, timeout = 15)
        if (!res.isSuccessful || !CinecalidadAMMarkup.siteUrl(res.url)) throw IllegalStateException("Cinecalidad.am no respondió correctamente (HTTP ${res.code}).")
        return Jsoup.parse(res.text, res.url)
    }
    private fun responses(items: List<Item>): List<SearchResponse> = items.map { item ->
        if (item.movie) newMovieSearchResponse(item.title, item.url, TvType.Movie) { posterUrl = item.poster }
        else newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) { posterUrl = item.poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "#destacado") {
            if (page != 1) return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            return newHomePageResponse(HomePageList(request.name, responses(CinecalidadAMMarkup.featured(fetch("/")))), hasNext = false)
        }
        val base = request.data.trimEnd('/')
        val requestedYear = Regex("^/release/([0-9]{4})$").matchEntire(base)?.groupValues?.get(1)
        val doc = try { fetch(if (page == 1) "$base/" else "$base/page/$page/") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A newly started year can be absent from the site; don't break every home section.
                if (requestedYear != null) return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
                throw e
            }
        if (requestedYear != null && !CinecalidadAMMarkup.isYearArchive(doc, requestedYear))
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        return newHomePageResponse(HomePageList(request.name, responses(CinecalidadAMMarkup.cards(doc))), hasNext = CinecalidadAMMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items.orEmpty()
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val yearPath = CinecalidadAMMarkup.yearQueryPath(query, page)
        val path = if (page > 1) "/page/$page/" else "/"
        val doc = try { fetch(yearPath ?: "$path?s=${URLEncoder.encode(query.trim(), "UTF-8")}") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (yearPath != null) return newSearchResponseList(emptyList(), hasNext = false)
                throw e
            }
        if (yearPath != null && !CinecalidadAMMarkup.isYearArchive(doc, query.trim()))
            return newSearchResponseList(emptyList(), hasNext = false)
        return newSearchResponseList(responses(CinecalidadAMMarkup.cards(doc)), hasNext = CinecalidadAMMarkup.hasNext(doc, page))
    }
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url); val title = CinecalidadAMMarkup.title(doc) ?: return null
        val poster = CinecalidadAMMarkup.poster(doc); val description = CinecalidadAMMarkup.plot(doc)
        if (URI(url).path.startsWith("/ver-pelicula/")) return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; plot = description }
        val episodes = CinecalidadAMMarkup.episodes(doc).map { item ->
            newEpisode(item.url) { name = item.title; season = item.season; episode = item.number; posterUrl = item.poster }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; plot = description }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = fetch(data); val options = CinecalidadAMMarkup.players(doc).take(20)
        val emitted = AtomicBoolean(false); val lock = Any()
        withTimeoutOrNull(40_000L) { coroutineScope {
            options.map { option -> async {
                withTimeoutOrNull(18_000L) {
                    val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try {
                        var target = option.url
                        var ref = doc.location()
                        if (CinecalidadAMMarkup.safeHost(target, "videoapp.zip")) {
                            val res = app.get(target, headers = mapOf("Referer" to ref), timeout = 8)
                            if (!res.isSuccessful || !CinecalidadAMMarkup.safeHost(res.url, "videoapp.zip")) return@withTimeoutOrNull
                            ref = res.url
                            target = CinecalidadAMMarkup.vimeosFromWrapper(Jsoup.parse(res.text, res.url)) ?: return@withTimeoutOrNull
                        }
                        loadExtractor(target, ref, subtitleCallback) { links.add(it) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    for (link in synchronized(links) { links.toList() }) {
                        val labeled = label(link, option.language)
                        synchronized(lock) { emitted.set(true); callback(labeled) }
                    }
                }
            } }.awaitAll()
        } }
        return emitted.get()
    }
    private suspend fun label(link: ExtractorLink, language: String): ExtractorLink {
        val label = "[$language] ${link.name}"
        if (link is DrmExtractorLink) return newDrmExtractorLink(link.source, label, link.url, link.type, link.uuid) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
            kid = link.kid; key = link.key; kty = link.kty; keyRequestParameters = link.keyRequestParameters; licenseUrl = link.licenseUrl
        }
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
        }
    }
}
