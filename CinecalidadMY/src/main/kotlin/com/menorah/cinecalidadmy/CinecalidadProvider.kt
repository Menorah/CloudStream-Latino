package com.menorah.cinecalidadmy

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

class CinecalidadProvider : MainAPI() {
    override var mainUrl = CinecalidadMarkup.BASE
    override var name = "Cinecalidad.my"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage = mainPageOf(
        "/" to "Películas", "/genero-peliculas/destacada/" to "Destacadas",
        "/genero-peliculas/accion/" to "Acción", "/genero-peliculas/animacion/" to "Animación",
        "/genero-peliculas/aventura/" to "Aventura", "/genero-peliculas/ciencia-ficcion/" to "Ciencia ficción",
        "/genero-peliculas/comedia/" to "Comedia", "/genero-peliculas/crimen/" to "Crimen",
        "/genero-peliculas/drama/" to "Drama", "/genero-peliculas/familiar/" to "Familiar",
        "/genero-peliculas/fantasia/" to "Fantasía", "/genero-peliculas/misterio/" to "Misterio",
        "/genero-peliculas/romance/" to "Romance", "/genero-peliculas/suspenso/" to "Suspenso",
        "/genero-peliculas/terror/" to "Terror"
    )
    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(CinecalidadMarkup.siteUrl(url)) { "Dirección fuera de Cinecalidad.my" }
        val res = app.get(url, timeout = 15)
        if (!res.isSuccessful || !CinecalidadMarkup.siteUrl(res.url)) throw IllegalStateException("Cinecalidad.my no respondió correctamente (HTTP ${res.code}).")
        return Jsoup.parse(res.text, res.url)
    }
    private fun responses(films: List<Film>) = films.map { film -> newMovieSearchResponse(film.title, film.url, TvType.Movie) { posterUrl = film.image } }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val doc = fetch(if (page == 1) "$base/" else "$base/page/$page/")
        return newHomePageResponse(HomePageList(request.name, responses(CinecalidadMarkup.cards(doc))), hasNext = CinecalidadMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> = if (query.isBlank()) emptyList() else responses(CinecalidadMarkup.cards(fetch("/?s=${URLEncoder.encode(query.trim(), "UTF-8")}")))
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val path = if (page > 1) "/page/$page/" else "/"
        val doc = fetch("$path?s=${URLEncoder.encode(query.trim(), "UTF-8")}")
        return newSearchResponseList(responses(CinecalidadMarkup.cards(doc)), hasNext = CinecalidadMarkup.hasNext(doc, page))
    }
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url); val title = CinecalidadMarkup.title(doc) ?: return null
        val poster = CinecalidadMarkup.poster(doc); val description = CinecalidadMarkup.plot(doc)
        return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; plot = description }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = fetch(data); val options = CinecalidadMarkup.players(doc).take(20)
        val emitted = AtomicBoolean(false); val lock = Any()
        withTimeoutOrNull(40_000L) { coroutineScope {
            options.map { option -> async {
                withTimeoutOrNull(18_000L) {
                    val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try { loadExtractor(option.url, doc.location(), subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
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
