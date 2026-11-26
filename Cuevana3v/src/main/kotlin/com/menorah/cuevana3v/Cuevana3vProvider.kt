package com.menorah.cuevana3v

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Cuevana3vProvider : MainAPI() {
    override var mainUrl = Cuevana3vMarkup.MAIN_URL
    override var name = "Cuevana3v"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val getMainPageTimeoutMs: Long? = 75_000L
    override val searchTimeoutMs: Long? = 30_000L
    override val quickSearchTimeoutMs: Long? = 25_000L
    override val loadTimeoutMs: Long? = 45_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    override val mainPage = mainPageOf(
        "/episodios/recientes" to "Últimos Episodios",
        "/peliculas" to "Películas Online",
        "/peliculas?orden=tendencias" to "Películas Destacadas",
        "/series" to "Series Online",
        "/series?orden=tendencias" to "Series Destacadas",
        "/series?genero=anime" to "Anime",
        "/peliculas?genero=accion" to "Acción",
        "/peliculas?genero=aventura" to "Aventura",
        "/peliculas?genero=animacion" to "Animación",
        "/peliculas?genero=ciencia-ficcion" to "Ciencia Ficción",
        "/peliculas?genero=comedia" to "Comedia",
        "/peliculas?genero=drama" to "Drama",
        "/peliculas?genero=suspenso" to "Suspenso",
        "/peliculas?genero=terror" to "Terror",
        "/peliculas?anio=2026" to "Estrenos 2026",
    )

    private data class CachedHtml(val text: String, val storedAtMs: Long)

    private val tungConfigRef = AtomicReference(Cuevana3vMarkup.defaultTungConfig)
    private val tungConfigMutex = Mutex()
    private val tungRefreshedOnce = AtomicReference(false)
    private val htmlCache = ConcurrentHashMap<String, CachedHtml>()
    private val nextRequestAllowedAtMs = AtomicLong(0L)
    private val siteGate = Semaphore(3)
    private val seasonGate = Semaphore(3)
    private val linkGate = Semaphore(4)

    private val siteHeaders = mapOf(
        "User-Agent" to Cuevana3vMarkup.DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend fun getHtml(
        url: String,
        referer: String = "$mainUrl/",
        timeoutSec: Long = 18L,
        useCache: Boolean = true,
    ): String? {
        val now = System.currentTimeMillis()
        if (useCache) {
            val cached = htmlCache[url]
            if (cached != null && now - cached.storedAtMs < 120_000L) {
                return cached.text
            }
        }

        for (attempt in 0..3) {
            val waitUntil = nextRequestAllowedAtMs.get()
            val delayMs = waitUntil - System.currentTimeMillis()
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)

            var retryWaitMs = 0L
            val html = try {
                siteGate.withPermit {
                    val cooldown = nextRequestAllowedAtMs.get() - System.currentTimeMillis()
                    if (cooldown > 0) kotlinx.coroutines.delay(cooldown)
                    nextRequestAllowedAtMs.updateAndGet { prev ->
                        maxOf(prev, System.currentTimeMillis() + 90L)
                    }
                    val res = app.get(
                        url,
                        referer = referer,
                        headers = siteHeaders,
                        timeout = timeoutSec,
                    )
                    when {
                        res.isSuccessful -> res.text
                        res.code == 429 || res.code in 500..599 -> {
                            val retryHeaderSec = res.headers["Retry-After"]?.trim()?.toLongOrNull()
                            retryWaitMs = ((retryHeaderSec?.times(1000L)) ?: (1400L * (attempt + 1)))
                                .coerceIn(1000L, 6000L)
                            nextRequestAllowedAtMs.updateAndGet { prev ->
                                maxOf(prev, System.currentTimeMillis() + retryWaitMs)
                            }
                            null
                        }
                        else -> null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                retryWaitMs = 600L * (attempt + 1)
                null
            }

            if (html != null) {
                if (useCache) {
                    if (htmlCache.size > 96) htmlCache.clear()
                    htmlCache[url] = CachedHtml(html, System.currentTimeMillis())
                }
                return html
            }
            if (attempt < 3) {
                kotlinx.coroutines.delay(if (retryWaitMs > 0) retryWaitMs else 700L * (attempt + 1))
            }
        }
        return null
    }

    private fun toSearchResponse(card: CuevanaCatalogCard): SearchResponse {
        return when (card.kind) {
            CuevanaItemKind.Movie -> newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                this.posterUrl = card.posterUrl
                this.year = card.year
            }
            CuevanaItemKind.Series, CuevanaItemKind.Episode ->
                newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                    this.posterUrl = card.posterUrl
                    this.year = card.year
                }
        }
    }

    private fun buildPagedUrl(basePath: String, page: Int): String {
        val base = if (basePath.startsWith("http", ignoreCase = true)) basePath else "$mainUrl$basePath"
        if (page <= 1) return base
        val sep = if ('?' in base) '&' else '?'
        return "${base}${sep}page=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1 && request.data.contains("orden=tendencias", ignoreCase = true)) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }
        val targetUrl = buildPagedUrl(request.data, page)
        val html = getHtml(targetUrl) ?: return newHomePageResponse(request, emptyList(), hasNext = false)
        val cards = Cuevana3vMarkup.parseCatalogItems(html)
        val items = cards.map(::toSearchResponse)
        val hasNext = Cuevana3vMarkup.hasNextPage(html, targetUrl, page, cards.size)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val targetUrl = buildPagedUrl("/explorar?s=$encoded", page)
        val html = getHtml(targetUrl) ?: return newSearchResponseList(emptyList(), hasNext = false)
        val cards = Cuevana3vMarkup.parseCatalogItems(html)
        val hasNext = Cuevana3vMarkup.hasNextPage(html, targetUrl, page, cards.size)
        return newSearchResponseList(cards.map(::toSearchResponse), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = Cuevana3vMarkup.resolveSiteUrl(url) ?: return null
        if (Cuevana3vMarkup.isMovieUrl(normalized)) {
            val html = getHtml(normalized) ?: return null
            val detail = Cuevana3vMarkup.parseMovieDetail(html, normalized)
            return newMovieLoadResponse(detail.title, detail.url, TvType.Movie, detail.url) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backgroundPosterUrl
                this.plot = detail.plot
                this.year = detail.year
                this.duration = detail.durationMinutes
                this.score = Score.from10(detail.rating)
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
                if (detail.actors.isNotEmpty()) addActors(detail.actors)
                if (detail.recommendations.isNotEmpty()) {
                    this.recommendations = detail.recommendations.map(::toSearchResponse)
                }
            }
        }

        // Episodes opened from "Últimos Episodios" resolve to their parent series page so all seasons/episodes load.
        val seriesUrl = Cuevana3vMarkup.extractSeriesUrlFromUrl(normalized)
        val html = getHtml(seriesUrl) ?: return null
        val detail = Cuevana3vMarkup.parseSeriesDetail(html, seriesUrl)

        val seasonEpisodes = if (detail.seasons.isNotEmpty()) {
            coroutineScope {
                detail.seasons.map { seasonRef ->
                    async(Dispatchers.IO) {
                        seasonGate.withPermit {
                            val seasonHtml = getHtml(seasonRef.url, referer = detail.url) ?: return@withPermit emptyList()
                            Cuevana3vMarkup.parseSeasonEpisodes(seasonHtml, seasonRef.season)
                        }
                    }
                }.awaitAll().flatten()
            }
        } else {
            detail.directEpisodes
        }

        val episodes = seasonEpisodes
            .distinctBy { it.url }
            .sortedWith(compareBy<CuevanaEpisodeCard> { it.season }.thenBy { it.episode })
            .map { ep ->
                newEpisode(ep.url) {
                    this.name = ep.title
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = ep.posterUrl
                }
            }

        return newTvSeriesLoadResponse(detail.title, detail.url, TvType.TvSeries, episodes) {
            this.posterUrl = detail.posterUrl
            this.backgroundPosterUrl = detail.backgroundPosterUrl
            this.plot = detail.plot
            this.year = detail.year
            this.score = Score.from10(detail.rating)
            if (detail.genres.isNotEmpty()) this.tags = detail.genres
            if (detail.actors.isNotEmpty()) addActors(detail.actors)
        }
    }

    private suspend fun refreshTungConfigIfNeeded(rawServerUrl: String): TungServerConfig {
        val current = tungConfigRef.get()
        val token = Cuevana3vMarkup.extractTokenParam(rawServerUrl)
        val needsRefresh = token != null &&
            token.isNotEmpty() &&
            !current.servers.containsKey(token[0]) &&
            !tungRefreshedOnce.get()
        if (!needsRefresh) return current

        return tungConfigMutex.withLock {
            val latest = tungConfigRef.get()
            if (token != null && token.isNotEmpty() && latest.servers.containsKey(token[0])) {
                return@withLock latest
            }
            if (tungRefreshedOnce.compareAndSet(false, true)) {
                val html = getHtml("${Cuevana3vMarkup.TUNG_URL}/", referer = "$mainUrl/", timeoutSec = 10L)
                val parsed = html?.let { Cuevana3vMarkup.parseTungConfig(it) }
                if (parsed != null) {
                    tungConfigRef.set(parsed)
                    return@withLock parsed
                }
            }
            latest
        }
    }

    private suspend fun labelExtractorLink(langLabel: String, serverFallbackName: String, link: ExtractorLink): ExtractorLink {
        val baseName = link.name.ifBlank { serverFallbackName }
        val taggedName = if (baseName.startsWith("[")) baseName else "[$langLabel] $baseName"
        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, taggedName, link.url, link.type, link.uuid) {
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
        return newExtractorLink(link.source, taggedName, link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }

    private suspend fun resolveSingleSource(
        source: CuevanaPlayerSource,
        targetUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val sink: (ExtractorLink) -> Unit = { collected.add(it) }
        val tungReferer = "${Cuevana3vMarkup.TUNG_URL}/"
        val lowerUrl = targetUrl.lowercase()
        val lowerSrv = source.serverName.lowercase()

        try {
            when {
                lowerUrl.contains("lkhjerbhye3wikhoduh5xiczuvd.lol") || lowerSrv == "hyper" -> {
                    CuevanaVidHideExtractor.extract(targetUrl, tungReferer, "Hyper", subtitleCallback, sink)
                }
                lowerUrl.contains("vibuxer.com") || lowerSrv == "nebula" -> {
                    CuevanaVidHideExtractor.extract(targetUrl, tungReferer, "Nebula", subtitleCallback, sink)
                }
                else -> {
                    loadExtractor(targetUrl, tungReferer, subtitleCallback, sink)
                    if (collected.isEmpty() && lowerUrl.contains("filemoon")) {
                        CuevanaVidHideExtractor.extract(targetUrl, tungReferer, "FileMoon", subtitleCallback, sink)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        return synchronized(collected) { collected.toList() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = Cuevana3vMarkup.resolveSiteUrl(data) ?: return false
        val html = getHtml(pageUrl) ?: return false
        val rawSources = Cuevana3vMarkup.parsePlayerTabs(html)
        if (rawSources.isEmpty()) return false

        val langPriority = mapOf(
            "Latino" to 0,
            "Castellano" to 1,
            "Subtitulado" to 2,
            "Multilenguaje" to 3,
        )
        val serverPriority = mapOf(
            "hyper" to 0,
            "nebula" to 1,
            "filemoon" to 2,
            "dood" to 3,
        )

        val orderedSources = rawSources.sortedWith(
            compareBy<CuevanaPlayerSource> { langPriority[it.language] ?: 4 }
                .thenBy { serverPriority[it.serverName.lowercase()] ?: 5 },
        )

        val decodedPairs = orderedSources.mapNotNull { src ->
            val cfg = refreshTungConfigIfNeeded(src.rawServerUrl)
            val resolved = Cuevana3vMarkup.decodeTungUrl(src.rawServerUrl, cfg) ?: return@mapNotNull null
            src to resolved
        }.distinctBy { (src, resolvedUrl) -> "${src.language}|$resolvedUrl" }

        if (decodedPairs.isEmpty()) return false

        val collectedResults = Collections.synchronizedList(mutableListOf<Pair<Int, ExtractorLink>>())
        withTimeoutOrNull(45_000L) {
            coroutineScope {
                decodedPairs.mapIndexed { idx, (src, resolvedUrl) ->
                    async(Dispatchers.IO) {
                        linkGate.withPermit {
                            withTimeoutOrNull(16_000L) {
                                val links = resolveSingleSource(src, resolvedUrl, subtitleCallback)
                                for (link in links) {
                                    collectedResults.add(idx to labelExtractorLink(src.language, src.serverName, link))
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val seenUrls = hashSetOf<String>()
        var emitted = false
        for ((_, link) in collectedResults.sortedBy { it.first }) {
            val key = "${link.name}|${link.url}"
            if (seenUrls.add(key)) {
                callback(link)
                emitted = true
            }
        }
        return emitted
    }
}
