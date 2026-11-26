package com.menorah.poseidonhd2bis

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PoseidonHD2BisProvider : MainAPI() {
    override var mainUrl = PoseidonHD2BisData.MAIN_URL
    override var name = "PoseidonHD2 bis"
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
        "/peliculas" to "Películas",
        "/peliculas/estrenos" to "Películas Estrenos",
        "/peliculas/tendencias/dia" to "Películas Tendencias (Día)",
        "/peliculas/tendencias/semana" to "Películas Tendencias (Semana)",
        "/series" to "Series",
        "/series/estrenos" to "Series Estrenos",
        "/series/tendencias/dia" to "Series Tendencias (Día)",
        "/series/tendencias/semana" to "Series Tendencias (Semana)",
        "/episodios" to "Últimos Episodios",
        "/genero/accion" to "Acción",
        "/genero/animacion" to "Animación",
        "/genero/aventura" to "Aventura",
        "/genero/ciencia-ficcion" to "Ciencia Ficción",
        "/genero/comedia" to "Comedia",
        "/genero/crimen" to "Crimen",
        "/genero/documental" to "Documental",
        "/genero/drama" to "Drama",
        "/genero/familia" to "Familia",
        "/genero/fantasia" to "Fantasía",
        "/genero/guerra" to "Guerra",
        "/genero/historia" to "Historia",
        "/genero/misterio" to "Misterio",
        "/genero/musica" to "Música",
        "/genero/romance" to "Romance",
        "/genero/suspenso" to "Suspenso",
        "/genero/terror" to "Terror",
        "/genero/western" to "Western",
    )

    private data class CachedEntry(val value: String, val storedAtMs: Long)

    private val htmlCache = ConcurrentHashMap<String, CachedEntry>()
    private val playerRedirectCache = ConcurrentHashMap<String, CachedEntry>()
    private val nextRequestAllowedAtMs = AtomicLong(0L)
    private val siteGate = Semaphore(3)
    private val linkGate = Semaphore(5)

    private val siteHeaders = mapOf(
        "User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT,
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
                return cached.value
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
                        maxOf(prev, System.currentTimeMillis() + 80L)
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
                            val retrySec = res.headers["Retry-After"]?.trim()?.toLongOrNull()
                            retryWaitMs = ((retrySec?.times(1000L)) ?: (1400L * (attempt + 1)))
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
                    htmlCache[url] = CachedEntry(html, System.currentTimeMillis())
                }
                return html
            }
            if (attempt < 3) {
                kotlinx.coroutines.delay(if (retryWaitMs > 0) retryWaitMs else 700L * (attempt + 1))
            }
        }
        return null
    }

    private fun toSearchResponse(item: PoseidonCatalogItem): SearchResponse {
        return when (item.kind) {
            PoseidonItemKind.Movie -> newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                this.posterUrl = item.posterUrl
                this.year = item.year
            }
            PoseidonItemKind.Series, PoseidonItemKind.Episode ->
                newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.year = item.year
                }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val targetUrl = PoseidonHD2BisData.buildPagedUrl(request.data, page)
        val html = getHtml(targetUrl) ?: return newHomePageResponse(request, emptyList(), hasNext = false)
        val catalog = PoseidonHD2BisData.parseCatalogPage(html)
        val responses = catalog.items.map(::toSearchResponse)
        val hasNext = responses.isNotEmpty() && page < catalog.totalPages
        return newHomePageResponse(request, responses, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || page > 1) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val targetUrl = "$mainUrl/search?q=$encoded"
        val html = getHtml(targetUrl) ?: return newSearchResponseList(emptyList(), hasNext = false)
        val catalog = PoseidonHD2BisData.parseCatalogPage(html)
        // Site search (/search?q=...) always returns a single page (up to 24 results) with no server pagination.
        return newSearchResponseList(catalog.items.map(::toSearchResponse), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = PoseidonHD2BisData.resolveSiteUrl(url) ?: return null
        if (PoseidonHD2BisData.isMovieUrl(normalized)) {
            val html = getHtml(normalized) ?: return null
            val movie = PoseidonHD2BisData.parseMovieDetail(html, normalized) ?: return null
            return newMovieLoadResponse(movie.title, movie.url, TvType.Movie, movie.url) {
                this.posterUrl = movie.posterUrl
                this.backgroundPosterUrl = movie.backgroundPosterUrl
                this.plot = movie.plot
                this.year = movie.year
                this.duration = movie.durationMinutes
                this.score = Score.from10(movie.rating)
                if (movie.genres.isNotEmpty()) this.tags = movie.genres
                if (movie.actors.isNotEmpty()) addActors(movie.actors)
                if (movie.recommendations.isNotEmpty()) {
                    this.recommendations = movie.recommendations.map(::toSearchResponse)
                }
            }
        }

        // Episodes opened from /episodios promote cleanly to their parent /serie/<tmdb>/<slug> page.
        val seriesUrl = PoseidonHD2BisData.extractSeriesBaseUrl(normalized)
        val html = getHtml(seriesUrl) ?: return null
        val series = PoseidonHD2BisData.parseSeriesDetail(html, seriesUrl) ?: return null
        val episodes = series.episodes.map { ep ->
            newEpisode(ep.url) {
                this.name = ep.title
                this.season = ep.season
                this.episode = ep.episode
                this.posterUrl = ep.posterUrl
                this.addDate(ep.releaseDate)
            }
        }
        return newTvSeriesLoadResponse(series.title, series.url, TvType.TvSeries, episodes) {
            this.posterUrl = series.posterUrl
            this.backgroundPosterUrl = series.backgroundPosterUrl
            this.plot = series.plot
            this.year = series.year
            this.score = Score.from10(series.rating)
            if (series.genres.isNotEmpty()) this.tags = series.genres
            if (series.actors.isNotEmpty()) addActors(series.actors)
        }
    }

    private suspend fun resolvePlayerRedirect(playerUrl: String, watchReferer: String): String? {
        val now = System.currentTimeMillis()
        playerRedirectCache[playerUrl]?.let { cached ->
            if (now - cached.storedAtMs < 300_000L) return cached.value
        }

        val playerHtml = getHtml(
            playerUrl,
            referer = watchReferer,
            timeoutSec = 14L,
            useCache = false,
        ) ?: return null
        val embedUrl = PoseidonHD2BisData.extractPlayerEmbedUrl(playerHtml) ?: return null
        if (playerRedirectCache.size > 256) playerRedirectCache.clear()
        playerRedirectCache[playerUrl] = CachedEntry(embedUrl, System.currentTimeMillis())
        return embedUrl
    }

    private suspend fun labelExtractorLink(
        langLabel: String,
        cyberlocker: String,
        reportedQuality: String?,
        link: ExtractorLink,
    ): ExtractorLink {
        val cleanSource = link.name.ifBlank { cyberlocker.replaceFirstChar { it.uppercaseChar() } }
        val taggedName = if (cleanSource.startsWith("[")) cleanSource else "[$langLabel] $cleanSource"
        val resolvedQuality = if (link.quality != Qualities.Unknown.value) {
            link.quality
        } else {
            getQualityFromName(reportedQuality)
        }

        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, taggedName, link.url, link.type, link.uuid) {
                referer = link.referer
                quality = resolvedQuality
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
            quality = resolvedQuality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }

    private suspend fun resolveSingleEmbed(
        source: PoseidonVideoSource,
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val sink: (ExtractorLink) -> Unit = { collected.add(it) }
        val playerReferer = "https://player.poseidonhd2.co/"
        val lowerUrl = embedUrl.lowercase()
        val lowerLocker = source.cyberlocker.lowercase()

        try {
            when {
                lowerLocker == "voesx" || lowerUrl.contains("voe.") || lowerUrl.contains("caseyseemavoid") -> {
                    PoseidonVoeExtractor.extract(embedUrl, playerReferer, "Voe", subtitleCallback, sink)
                }
                lowerLocker == "vidhide" ||
                    lowerUrl.contains("vidhide") ||
                    lowerUrl.contains("callistanise") ||
                    lowerUrl.contains("peytonepre") ||
                    lowerUrl.contains("smoothpre") ||
                    lowerUrl.contains("ryderjet") ||
                    lowerUrl.contains("dhtpre") ||
                    lowerUrl.contains("dinisglows") ||
                    lowerUrl.contains("mivalyo") ||
                    lowerUrl.contains("movearnpre") -> {
                    PoseidonVidHideExtractor.extract(embedUrl, playerReferer, "VidHide", subtitleCallback, sink)
                }
                lowerLocker == "streamwish" ||
                    lowerUrl.contains("streamwish") ||
                    lowerUrl.contains("hglink") ||
                    lowerUrl.contains("medixiru") ||
                    lowerUrl.contains("vsembed") ||
                    lowerUrl.contains("flaswish") ||
                    lowerUrl.contains("hlswish") -> {
                    PoseidonStreamWishExtractor.extract(embedUrl, playerReferer, "StreamWish", subtitleCallback, sink)
                }
                lowerUrl.contains("bysejikuar") -> {
                    PoseidonByseExtractor().getUrl(embedUrl, playerReferer, subtitleCallback, sink)
                    if (collected.isEmpty()) {
                        loadExtractor(embedUrl, playerReferer, subtitleCallback, sink)
                    }
                }
                else -> {
                    loadExtractor(embedUrl, playerReferer, subtitleCallback, sink)
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
        val watchUrl = PoseidonHD2BisData.resolveSiteUrl(data) ?: return false
        val html = getHtml(watchUrl) ?: return false
        val rawVideos = PoseidonHD2BisData.parseWatchPageVideos(html)
        if (rawVideos.isEmpty()) return false

        val langPriority = mapOf(
            "Latino" to 0,
            "Español" to 1,
            "Subtitulado" to 2,
        )
        val serverPriority = mapOf(
            "vidhide" to 0,
            "voesx" to 1,
            "vidara" to 2,
            "doodstream" to 3,
            "streamwish" to 4,
            "hglink" to 5,
            "filemoon" to 6,
            "streamtape" to 7,
            "netu" to 99,
        )

        val orderedVideos = rawVideos
            .filterNot { it.cyberlocker == "netu" }
            .sortedWith(
                compareBy<PoseidonVideoSource> { langPriority[it.languageLabel] ?: 3 }
                    .thenBy { serverPriority[it.cyberlocker] ?: 20 },
            )
            .ifEmpty { rawVideos }

        val collectedResults = Collections.synchronizedList(mutableListOf<Pair<Int, ExtractorLink>>())
        withTimeoutOrNull(36_000L) {
            coroutineScope {
                orderedVideos.mapIndexed { idx, video ->
                    async(Dispatchers.IO) {
                        linkGate.withPermit {
                            withTimeoutOrNull(14_000L) {
                                val embedUrl = resolvePlayerRedirect(video.playerUrl, watchUrl) ?: return@withTimeoutOrNull
                                val links = resolveSingleEmbed(video, embedUrl, subtitleCallback)
                                for (link in links) {
                                    collectedResults.add(
                                        idx to labelExtractorLink(
                                            video.languageLabel,
                                            video.cyberlocker,
                                            video.quality,
                                            link,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val seenKeys = hashSetOf<String>()
        var emitted = false
        for ((_, link) in collectedResults.sortedBy { it.first }) {
            val dedupKey = "${link.name}|${link.url}"
            if (seenKeys.add(dedupKey)) {
                callback(link)
                emitted = true
            }
        }
        return emitted
    }
}
