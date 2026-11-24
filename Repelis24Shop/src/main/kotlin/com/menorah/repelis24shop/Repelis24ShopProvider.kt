package com.menorah.repelis24shop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

class Repelis24ShopProvider : MainAPI() {
    override var mainUrl = "https://repelis24.shop"
    override var name = "RePelis24.shop"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/peliculas" to "Películas", "/series" to "Series", "/peliculas/estrenos" to "Estrenos",
        "/genero/accion" to "Acción", "/genero/animacion" to "Animación", "/genero/anime" to "Anime",
        "/genero/aventura" to "Aventura", "/genero/belica" to "Bélica", "/genero/ciencia-ficcion" to "Ciencia ficción",
        "/genero/comedia" to "Comedia", "/genero/crimen" to "Crimen", "/genero/documental" to "Documental",
        "/genero/drama" to "Drama", "/genero/familia" to "Familia", "/genero/fantasia" to "Fantasía"
    )
    private val ua = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(Repelis24ShopMarkup.site(url)) { "Dirección fuera de RePelis24.shop" }
        val headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
        var response = app.get(url, headers = headers, timeout = 15)
        if (response.code == 403 && url != "$mainUrl/") {
            app.get("$mainUrl/", headers = headers, timeout = 15)
            response = app.get(url, headers = headers, timeout = 15)
        } else if (response.code in listOf(502, 503, 504)) {
            delay(750)
            response = app.get(url, headers = headers, timeout = 15)
        }
        check(response.isSuccessful && Repelis24ShopMarkup.site(response.url)) { "RePelis24.shop: HTTP ${response.code}" }
        return Jsoup.parse(response.text, response.url)
    }
    private fun responses(items: List<Card>): List<SearchResponse> = items.map { item ->
        if (item.movie) newMovieSearchResponse(item.title, item.url, TvType.Movie) { posterUrl = item.poster }
        else newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) { posterUrl = item.poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(request.data + if (page > 1) "/page/$page" else "")
        return newHomePageResponse(HomePageList(request.name, responses(Repelis24ShopMarkup.cards(doc))), hasNext = Repelis24ShopMarkup.hasNext(doc, page))
    }
    override suspend fun search(query: String): List<SearchResponse> = if (query.isBlank()) emptyList() else
        responses(Repelis24ShopMarkup.cards(fetch("/buscar/${URLEncoder.encode(query.trim(), "UTF-8")}")))
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url); val article = doc.selectFirst("article.backdrop-info") ?: return null
        val title = article.selectFirst("h1 [itemprop=name]")?.text()?.replace(Regex("(?i)^ver (película|serie)\\s+"), "")?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = article.selectFirst("figure.poster img")?.absUrl("src")?.takeIf(Repelis24ShopMarkup::site)
        val description = article.selectFirst("[itemprop=description] p")?.text()?.takeIf { it.isNotBlank() }
        val year = article.selectFirst("[itemprop=datePublished]")?.text()?.trim()?.toIntOrNull()
        val tags = article.select("[itemprop=genre]").map { it.text().trim().trimEnd(',') }.filter { it.isNotBlank() }
        if (URI(url).path.orEmpty().startsWith("/pelicula/"))
            return newMovieLoadResponse(title, doc.location(), TvType.Movie, doc.location()) { posterUrl = poster; plot = description; this.year = year; this.tags = tags }
        val seasons = Repelis24ShopMarkup.seasons(doc)
        if (seasons.isEmpty()) return null
        val all = coroutineScope {
            val semaphore = Semaphore(4)
            seasons.map { season -> async { semaphore.withPermit { Repelis24ShopMarkup.episodes(fetch(season.url), season.number) } } }.awaitAll().flatten()
        }
        val episodes = all.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number })).map { item ->
            newEpisode(item.url) { name = item.title; season = item.season; episode = item.number; posterUrl = item.poster }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, doc.location(), TvType.TvSeries, episodes) { posterUrl = poster; plot = description; this.year = year; this.tags = tags }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = fetch(data); val players = Repelis24ShopMarkup.players(doc).take(24)
        val emitted = AtomicBoolean(false); val lock = Any()
        coroutineScope {
            val semaphore = Semaphore(4)
            players.map { player -> async { semaphore.withPermit {
                try {
                    withTimeout(18_000) {
                        val voe = Repelis24ShopMarkup.voeFromWrapper(player.url)
                        if (voe != null) {
                            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                            loadExtractor(voe, player.url, subtitleCallback) { links.add(it) }
                            for (link in synchronized(links) { links.toList() }) {
                                val labeled = label(link, player.language)
                                synchronized(lock) { callback(labeled); emitted.set(true) }
                            }
                        } else if (URI(player.url).path.orEmpty().startsWith("/watch/")) {
                            val res = app.get(player.url, headers = mapOf("User-Agent" to ua, "Referer" to doc.location()), timeout = 12)
                            if (!res.isSuccessful || !Repelis24ShopMarkup.nupload(res.url)) return@withTimeout
                            val media = Repelis24ShopMarkup.media(res.text) ?: return@withTimeout
                            val link = newExtractorLink(name, "[${player.language}] Nuupload", media, ExtractorLinkType.M3U8) {
                                referer = res.url; headers = mapOf("User-Agent" to ua)
                            }
                            synchronized(lock) { callback(link); emitted.set(true) }
                        }
                    }
                } catch (e: TimeoutCancellationException) { /* other player options remain */ }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* unavailable player */ }
            } } }.awaitAll()
        }
        return emitted.get()
    }
    private suspend fun label(link: ExtractorLink, language: String): ExtractorLink {
        val title = "[$language] ${link.name}"
        if (link is DrmExtractorLink) return newDrmExtractorLink(link.source, title, link.url, link.type, link.uuid) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
            kid = link.kid; key = link.key; kty = link.kty; keyRequestParameters = link.keyRequestParameters; licenseUrl = link.licenseUrl
        }
        return newExtractorLink(link.source, title, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData; audioTracks = link.audioTracks
        }
    }
}
