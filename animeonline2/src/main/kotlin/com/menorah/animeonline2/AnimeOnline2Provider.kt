package com.menorah.animeonline2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class AnimeOnline2Provider : MainAPI() {
    override var mainUrl = AnimeOnline2Parser.BASE
    override var name = "AnimeOnline2"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val getMainPageTimeoutMs: Long? = 90_000L
    override val searchTimeoutMs: Long? = 30_000L
    override val quickSearchTimeoutMs: Long? = 25_000L
    override val loadTimeoutMs: Long? = 45_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    override val mainPage = mainPageOf(
        "archive:/episodio/" to "Últimos Episodios",
        "featured" to "En Emisión · Recomendados",
        "archive:/online/" to "Animes",
        "archive:/pelicula/" to "Películas",
        "archive:/temporada/" to "Temporadas",
        "archive:/genero/audio-latino/" to "Audio Latino",
        "archive:/genero/anime-castellano/" to "Audio Castellano",
        "archive:/tendencias/" to "Populares",
        "archive:/ratings/" to "Mejores Valorados",
        "archive:/genero/en-emision-1/" to "En Emisión",
        "archive:/genero/blu-ray-dvd-2/" to "Blu-Ray / DVD",
        "archive:/genero/sin-censura/" to "Sin Censura",
        "archive:/genero/live-action/" to "Live Action",
        "archive:/genero/award-winning-anime/" to "Ganadores de Premios",
        "archive:/genero/proximamente/" to "Próximamente",
    )

    private val cfKiller by lazy { CloudflareKiller() }
    private val linkGate = Semaphore(6)

    private val baseHeaders = mapOf(
        "User-Agent" to AnimeOnline2Parser.USER_AGENT,
        "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
    )

    private suspend fun getPageHtml(url: String, referer: String? = "$mainUrl/inicio/", allow404: Boolean = false): String? {
        val norm = AnimeOnline2Parser.normalizeSiteUrl(url)
        val hdrs = if (referer.isNullOrBlank()) baseHeaders else baseHeaders + ("Referer" to referer)
        var resp = app.get(norm, headers = hdrs, timeout = 25)
        if (allow404 && resp.code == 404) return null
        if (!resp.isSuccessful || AnimeOnline2Parser.isCloudflareChallenge(resp.code, resp.text)) {
            resp = app.get(norm, headers = hdrs, interceptor = cfKiller, timeout = 30)
            if (allow404 && resp.code == 404) return null
        }
        if (!resp.isSuccessful) {
            throw IllegalStateException("AnimeOnline2 HTTP ${resp.code} en $norm")
        }
        return resp.text
    }

    private suspend fun getExternalHtml(url: String, referer: String): String {
        val hdrs = baseHeaders + ("Referer" to referer)
        val resp = app.get(url, headers = hdrs, timeout = 25)
        if (!resp.isSuccessful) {
            throw IllegalStateException("Servidor de enlace HTTP ${resp.code} en $url")
        }
        return resp.text
    }

    private fun AnimeCatalogCard.toSearchResponse(): SearchResponse {
        val isMovie = tvType == TvType.AnimeMovie
        return if (isMovie) {
            newAnimeSearchResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this.score = Score.from10(this@toSearchResponse.rating)
            }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this.score = Score.from10(this@toSearchResponse.rating)
                this@toSearchResponse.episodeNumber?.let { ep ->
                    addDubStatus(isDub = false, episodes = ep)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val catalogPage = when {
            data == "featured" -> {
                if (page <= 1) {
                    val html = getPageHtml("$mainUrl/inicio/") ?: ""
                    val modules = AnimeOnline2Parser.parseHomeModules(html)
                    AnimeCatalogPage(items = modules.featuredOnAir, hasNext = true)
                } else {
                    val url = AnimeOnline2Parser.buildArchivePageUrl("/genero/en-emision-1/", page)
                    val html = getPageHtml(url, allow404 = true)
                    if (html == null) AnimeCatalogPage(emptyList(), false)
                    else AnimeOnline2Parser.parseArchivePage(html, page)
                }
            }

            data == "archive:/episodio/" && page <= 1 -> {
                val html = getPageHtml("$mainUrl/inicio/") ?: ""
                val eps = AnimeOnline2Parser.parseHomeModules(html).latestEpisodes
                if (eps.isNotEmpty()) {
                    AnimeCatalogPage(items = eps, hasNext = true)
                } else {
                    val fallback = getPageHtml(AnimeOnline2Parser.buildArchivePageUrl("/episodio/", page), allow404 = true)
                    if (fallback == null) AnimeCatalogPage(emptyList(), false)
                    else AnimeOnline2Parser.parseArchivePage(fallback, page)
                }
            }

            data.startsWith("archive:") -> {
                val path = data.removePrefix("archive:")
                val url = AnimeOnline2Parser.buildArchivePageUrl(path, page)
                val html = getPageHtml(url, allow404 = true)
                if (html == null) AnimeCatalogPage(emptyList(), false)
                else AnimeOnline2Parser.parseArchivePage(html, page)
            }

            else -> AnimeCatalogPage(emptyList(), false)
        }

        val list = HomePageList(
            name = request.name,
            list = catalogPage.items.map { it.toSearchResponse() },
            isHorizontalImages = request.horizontalImages,
        )
        return newHomePageResponse(list = list, hasNext = catalogPage.hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val url = AnimeOnline2Parser.buildSearchUrl(trimmed, page)
        val html = getPageHtml(url, allow404 = true) ?: return newSearchResponseList(emptyList(), hasNext = false)
        val parsed = AnimeOnline2Parser.parseSearchPage(html, page)
        return newSearchResponseList(
            list = parsed.items.map { it.toSearchResponse() },
            hasNext = parsed.hasNext,
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val normUrl = AnimeOnline2Parser.normalizeSiteUrl(url)
        var targetUrl = normUrl
        var html = getPageHtml(targetUrl, allow404 = true) ?: return null

        // When user clicks an episode card from "Últimos Episodios", promote to the parent anime series
        // if the page provides a "lista de episodios" link so they get full seasons, plot & HD poster.
        if (targetUrl.contains("/episodio/")) {
            val parentSeriesUrl = AnimeOnline2Parser.findParentSeriesUrlFromEpisode(html)
            if (!parentSeriesUrl.isNullOrBlank() && parentSeriesUrl != targetUrl) {
                val parentHtml = getPageHtml(parentSeriesUrl, referer = targetUrl, allow404 = true)
                if (!parentHtml.isNullOrBlank()) {
                    targetUrl = parentSeriesUrl
                    html = parentHtml
                }
            }
        }

        val detail = AnimeOnline2Parser.parseDetailPage(targetUrl, html)
        val recs = detail.related.map { it.toSearchResponse() }
        val actorData = AnimeOnline2Parser.toActorData(detail.cast)

        if (detail.isMovie) {
            return newMovieLoadResponse(detail.title, detail.url, TvType.AnimeMovie, detail.url) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.backdropUrl ?: detail.posterUrl
                this.plot = detail.plot
                this.year = detail.year
                this.duration = detail.durationMinutes
                this.score = Score.from10(detail.rating)
                this.tags = detail.tags
                this.recommendations = recs
                this.actors = actorData
                addTrailer(detail.trailerUrl)
            }
        }

        val episodeObjects = if (detail.episodes.isNotEmpty()) {
            detail.episodes.map { ep ->
                newEpisode(ep.url) {
                    this.name = ep.name
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = ep.posterUrl ?: detail.posterUrl
                    addDate(ep.date)
                }
            }
        } else if (detail.hasDirectPlayer) {
            listOf(
                newEpisode(detail.url) {
                    this.name = detail.title
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = detail.posterUrl
                }
            )
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(detail.title, detail.url, TvType.Anime) {
            this.posterUrl = detail.posterUrl
            this.backgroundPosterUrl = detail.backdropUrl ?: detail.posterUrl
            this.plot = detail.plot
            this.year = detail.year
            this.duration = detail.durationMinutes
            this.score = Score.from10(detail.rating)
            this.tags = detail.tags
            this.recommendations = recs
            this.actors = actorData
            addTrailer(detail.trailerUrl)
            addEpisodes(DubStatus.Subbed, episodeObjects)
        }
    }

    private suspend fun fetchPlayerEmbedUrl(pageUrl: String, option: AnimePlayerOption): String? {
        val wpJsonUrl = "$mainUrl/wp-json/dooplayer/v1/post/${option.postId}?type=${option.type}&source=${option.nume}"
        val jsonResp = try {
            app.get(
                wpJsonUrl,
                headers = baseHeaders + mapOf(
                    "Accept" to "application/json, */*",
                    "Referer" to pageUrl,
                ),
                timeout = 20,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (jsonResp != null && jsonResp.isSuccessful) {
            AnimeOnline2Parser.parseDooplayerEmbedUrl(jsonResp.text)?.let { return it }
        }

        val ajaxResp = try {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to option.postId,
                    "nume" to option.nume,
                    "type" to option.type,
                ),
                headers = baseHeaders + mapOf(
                    "Accept" to "application/json, */*",
                    "Referer" to pageUrl,
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                timeout = 20,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (ajaxResp != null && ajaxResp.isSuccessful) {
            return AnimeOnline2Parser.parseDooplayerEmbedUrl(ajaxResp.text)
        }
        return null
    }

    private suspend fun labelExtractorLink(langLabel: String, link: ExtractorLink): ExtractorLink {
        val taggedName = if (link.name.startsWith("[")) link.name else "[$langLabel] ${link.name}"
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

    private suspend fun resolveSingleHost(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val sink: (ExtractorLink) -> Unit = { collected.add(it) }
        val lower = embedUrl.lowercase()
        try {
            when {
                lower.contains("gupload.site") ->
                    GUploadSiteExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("savefiles.top") ->
                    SavefilesExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("uqload.vc") ->
                    UqloadVcExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("peeplayer.com") ->
                    PeeplayerExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("mxdrop.sx") ->
                    MxdropSxExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("mxdrop.click") ->
                    MxdropClickExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("mixdropjmk.pw") ->
                    MixdropJmkExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("filemoon0.top") ->
                    Filemoon0TopExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("filemooon.link") ->
                    FilemooonLinkExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                lower.contains("luluvid.com") ->
                    LuluvidExtractor().getUrl(embedUrl, referer, subtitleCallback, sink)
                else ->
                    loadExtractor(embedUrl, referer, subtitleCallback, sink)
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
        val pageUrl = AnimeOnline2Parser.normalizeSiteUrl(data)
        val html = getPageHtml(pageUrl) ?: return false
        val playerOptions = AnimeOnline2Parser.parsePlayerOptions(html)
        if (playerOptions.isEmpty()) return false

        val targets = mutableListOf<Triple<String, String, String>>()
        for (option in playerOptions) {
            val embedUrl = fetchPlayerEmbedUrl(pageUrl, option) ?: continue
            if (embedUrl.contains("saidochesto.top", ignoreCase = true) || embedUrl.contains("/embed.php")) {
                val saidoHtml = try {
                    getExternalHtml(embedUrl, referer = pageUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                for (srv in AnimeOnline2Parser.parseSaidochestoOptions(saidoHtml)) {
                    targets.add(Triple(srv.url, embedUrl, srv.languageLabel))
                }
            } else {
                val label = option.label.ifBlank { "Servidor" }
                targets.add(Triple(embedUrl, pageUrl, label))
            }
        }

        if (targets.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        val lock = Any()
        val seen = Collections.synchronizedSet(mutableSetOf<String>())

        withTimeoutOrNull(50_000L) {
            coroutineScope {
                targets.map { (url, ref, langLabel) ->
                    async(Dispatchers.IO) {
                        linkGate.withPermit {
                            withTimeoutOrNull(18_000L) {
                                val resolved = resolveSingleHost(url, ref, subtitleCallback)
                                for (link in resolved) {
                                    val labeled = labelExtractorLink(langLabel, link)
                                    val key = "${labeled.url}|$langLabel"
                                    if (!seen.add(key)) continue
                                    synchronized(lock) {
                                        emitted.set(true)
                                        callback(labeled)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return emitted.get()
    }
}
