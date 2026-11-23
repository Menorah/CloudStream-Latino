package com.menorah.repelishd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class RePelisHDProvider : MainAPI() {
    override var mainUrl = RePelisHDMarkup.BASE
    override var name = "RePelisHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/explorar?t=movie" to "Películas", "/explorar?t=tv" to "Series",
        "/explorar?sort=popular" to "Populares", "/explorar?sort=nuevo" to "Estrenos",
        "/explorar?sort=valoracion" to "Mejor valoradas", "/explorar?sort=titulo" to "A–Z",
        "/explorar?ver=latino" to "Latino", "/explorar?ver=castellano" to "Castellano", "/explorar?ver=subtitulado" to "Subtituladas",
        "/explorar?g=accion" to "Acción", "/explorar?g=accion-y-aventura" to "Acción y aventura",
        "/explorar?g=animacion" to "Animación", "/explorar?g=aventura" to "Aventura", "/explorar?g=belica" to "Bélica",
        "/explorar?g=ciencia-ficcion" to "Ciencia ficción", "/explorar?g=ciencia-ficcion-y-fantasia" to "Ciencia ficción y fantasía",
        "/explorar?g=comedia" to "Comedia", "/explorar?g=crimen" to "Crimen", "/explorar?g=documental" to "Documental",
        "/explorar?g=drama" to "Drama", "/explorar?g=familia" to "Familia", "/explorar?g=fantasia" to "Fantasía",
        "/explorar?g=guerra-y-politica" to "Guerra y política", "/explorar?g=historia" to "Historia", "/explorar?g=infantil" to "Infantil",
        "/explorar?g=misterio" to "Misterio", "/explorar?g=musica" to "Música", "/explorar?g=noticias" to "Noticias",
        "/explorar?g=pelicula-de-tv" to "Película de TV", "/explorar?g=romance" to "Romance", "/explorar?g=suspense" to "Suspense",
        "/explorar?g=talk-show" to "Talk show", "/explorar?g=telenovela" to "Telenovela", "/explorar?g=telerrealidad" to "Telerrealidad",
        "/explorar?g=terror" to "Terror", "/explorar?g=western" to "Western"
    )
    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(RePelisHDMarkup.siteUrl(url)) { "Dirección fuera de RePelisHD" }
        val res = app.get(url, timeout = 18)
        if (!res.isSuccessful || !RePelisHDMarkup.siteUrl(res.url)) throw IllegalStateException("RePelisHD no respondió (HTTP ${res.code}).")
        return Jsoup.parse(res.text, res.url)
    }
    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map {
        if (it.movie) newMovieSearchResponse(it.title, it.url, TvType.Movie) { posterUrl = it.poster }
        else newTvSeriesSearchResponse(it.title, it.url, TvType.TvSeries) { posterUrl = it.poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(request.data + if (page > 1) "&page=$page" else "")
        return newHomePageResponse(HomePageList(request.name, responses(RePelisHDMarkup.cards(doc))), hasNext = RePelisHDMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> = if (query.isBlank()) emptyList() else responses(RePelisHDMarkup.cards(fetch("/buscar?q=${URLEncoder.encode(query.trim(), "UTF-8")}")))
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val title = doc.selectFirst(".chh-dati h1")?.ownText()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = RePelisHDMarkup.poster(doc)
        val background = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("https://") }
        val description = RePelisHDMarkup.plot(doc)
        val publishedYear = RePelisHDMarkup.year(doc); val genres = RePelisHDMarkup.genres(doc)
        if (URI(doc.location()).path.startsWith("/ver-pelicula/")) return newMovieLoadResponse(title, doc.location(), TvType.Movie, doc.location()) {
            posterUrl = poster; backgroundPosterUrl = background; plot = description; year = publishedYear; tags = genres
        }
        val state = RePelisHDMarkup.initial(doc) ?: return null
        val seasons = RePelisHDMarkup.seasons(state)
        if (seasons.isEmpty() || seasons.size > 100) return null
        val seriesUrl = URI(doc.location()).let { "${it.scheme}://${it.authority}${it.path.trimEnd('/')}" }
        val permits = Semaphore(4)
        val collected = coroutineScope {
            seasons.map { (number, expected) -> async { permits.withPermit {
                val seasonState = if (state.path("stagione").asInt(-1) == number) state else RePelisHDMarkup.initial(fetch("$seriesUrl/temporada-$number"))
                    ?: throw IllegalStateException("No se pudo cargar la temporada $number de $title")
                val episodes = RePelisHDMarkup.episodes(seasonState).filter { it.season == number }
                if (episodes.size < expected) throw IllegalStateException("Temporada $number incompleta: ${episodes.size} de $expected capítulos disponibles")
                episodes
            } } }.awaitAll().flatten()
        }
        val episodes = collected.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number })).map { item ->
            newEpisode("$seriesUrl/temporada-${item.season}/episodio-${item.number}") {
                name = item.title; season = item.season; episode = item.number; posterUrl = item.poster; this.description = item.description
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) { posterUrl = poster; backgroundPosterUrl = background; plot = description; year = publishedYear; tags = genres }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = fetch(data)
        val imdb = RePelisHDMarkup.imdb(doc) ?: return false
        val ep = Regex("/temporada-([0-9]+)/episodio-([0-9]+)").find(URI(doc.location()).path.orEmpty())
        val season = ep?.groupValues?.get(1)?.toIntOrNull(); val number = ep?.groupValues?.get(2)?.toIntOrNull()
        val type = if (ep != null) "tv" else "movie"
        val player = if (type == "movie") "${RePelisHDMarkup.PLAYER}/movie/$imdb?lang=latino" else "${RePelisHDMarkup.PLAYER}/serial/$imdb?season=$season&episode=$number&lang=latino"
        val response = app.get(player, referer = doc.location(), timeout = 15)
        if (!response.isSuccessful || !RePelisHDMarkup.playerUrl(response.url)) return false
        val token = RePelisHDMarkup.token(response.text) ?: return false
        if (token.type != type || token.id != imdb) return false
        val api = app.post("${RePelisHDMarkup.PLAYER}/api/embed-links", json = mapOf("type" to type, "id" to imdb, "token" to token.token), headers = mapOf("Referer" to response.url, "Origin" to RePelisHDMarkup.PLAYER), timeout = 18)
        if (!api.isSuccessful || !RePelisHDMarkup.playerUrl(api.url)) return false
        val parsed = RePelisHDMarkup.parseJson(api.text); if (!parsed.path("ok").asBoolean(false)) return false
        val sources = RePelisHDMarkup.sources(parsed, season, number).take(20)
        val emitted = AtomicBoolean(false); val lock = Any(); val permits = Semaphore(5)
        withTimeoutOrNull(45_000L) { coroutineScope {
            sources.map { source -> async { permits.withPermit {
                withTimeoutOrNull(15_000L) {
                    val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    try { loadExtractor(source.url, response.url, subtitleCallback) { links.add(it) } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    for (link in synchronized(links) { links.toList() }) {
                        val labeled = label(link, source.language)
                        synchronized(lock) { emitted.set(true); callback(labeled) }
                    }
                }
            } } }.awaitAll()
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
