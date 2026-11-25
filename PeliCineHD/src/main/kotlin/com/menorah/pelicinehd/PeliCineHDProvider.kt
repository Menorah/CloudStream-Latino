package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class PeliCineHDProvider(context: Context) : MainAPI() {
    override var mainUrl = PeliCineHDMarkup.BASE
    override var name = "PeliCineHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val getMainPageTimeoutMs: Long? = 120_000L
    override val searchTimeoutMs: Long? = 45_000L
    override val quickSearchTimeoutMs: Long? = 45_000L
    override val loadTimeoutMs: Long? = 60_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    override val mainPage = mainPageOf(
        "/movies/" to "Películas",
        "/series/" to "Series",
        "/category/accion/" to "Acción",
        "/category/animacion/" to "Animación",
        "/category/aventura/" to "Aventura",
        "/category/belica/" to "Bélica",
        "/category/ciencia-ficcion/" to "Ciencia ficción",
        "/category/comedia/" to "Comedia",
        "/category/crimen/" to "Crimen",
        "/category/documental/" to "Documental",
        "/category/drama/" to "Drama",
        "/category/familia/" to "Familia",
        "/category/fantasia/" to "Fantasía",
        "/category/historia/" to "Historia",
        "/category/kids/" to "Kids",
        "/category/misterio/" to "Misterio",
        "/category/musica/" to "Música",
        "/category/pelicula-de-tv/" to "Película de TV",
        "/category/romance/" to "Romance",
        "/category/suspense/" to "Suspense",
        "/category/terror/" to "Terror",
        "/category/western/" to "Western",
        "/category/action-adventure/" to "Action & Adventure",
        "/category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
        "/category/war-politics/" to "War & Politics",
    )

    private val site = SiteClient.create(context)
    private val catalogGate = Semaphore(6)

    private suspend fun fetch(input: String, headers: Map<String, String> = emptyMap()): Document {
        val target = URI("$mainUrl/").resolve(input).toString()
        require(PeliCineHDMarkup.siteUrl(target)) { "Dirección fuera de PeliCineHD" }
        val response = site.get(target, headers)
        if (!response.isSuccessful) throw IllegalStateException("PeliCineHD no respondió correctamente (HTTP ${response.code}).")
        if (SiteClient.looksLikeChallenge(response.text)) {
            throw ErrorLoadingException("PeliCineHD solicitó verificación anti-bots.")
        }
        return Jsoup.parse(response.text, response.url)
    }

    private suspend fun postAjax(form: Map<String, String>, referer: String): Document {
        val target = "$mainUrl/wp-admin/admin-ajax.php"
        val headers = mapOf(
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val response = site.post(target, form, headers)
        if (!response.isSuccessful) throw IllegalStateException("Error AJAX de PeliCineHD (HTTP ${response.code}).")
        return Jsoup.parse(response.text, referer)
    }

    private fun responses(cards: List<PeliCineCard>): List<SearchResponse> = cards.map { card ->
        if (card.movie) {
            newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                posterUrl = card.poster
            }
        } else {
            newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                posterUrl = card.poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = try {
            catalogGate.withPermit {
                fetch(PeliCineHDMarkup.pageUrl(request.data, page))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep primary sections noisy on hard failures while preventing one empty category from breaking home.
            if (request.data.startsWith("/category/")) {
                return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            }
            throw e
        }
        val items = responses(PeliCineHDMarkup.cards(doc))
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = PeliCineHDMarkup.hasNextPage(doc, page, request.data),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = fetch(PeliCineHDMarkup.searchUrl(query))
        return responses(PeliCineHDMarkup.cards(doc))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = fetch(PeliCineHDMarkup.searchUrl(query, page))
        return newSearchResponseList(
            responses(PeliCineHDMarkup.cards(doc)),
            hasNext = PeliCineHDMarkup.hasNextPage(doc, page, "", query),
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val detail = PeliCineHDMarkup.detail(doc) ?: return null
        val recs = responses(detail.recommendations)

        if (detail.movie) {
            return newMovieLoadResponse(detail.title, detail.url, TvType.Movie, detail.url) {
                posterUrl = detail.poster
                backgroundPosterUrl = detail.backdrop
                plot = detail.plot
                year = detail.year
                duration = detail.durationMinutes
                tags = detail.genres
                score = Score.from10(detail.rating)
                recommendations = recs
                addActors(detail.actors)
                addTrailer(detail.trailer)
            }
        }

        val initialEpisodes = PeliCineHDMarkup.episodes(doc)
        val seasons = PeliCineHDMarkup.seasons(doc)
        val alreadyLoadedSeasons = initialEpisodes.map { it.season }.toSet()
        val remainingSeasons = seasons.filter { it.season !in alreadyLoadedSeasons }

        val ajaxEpisodes = if (remainingSeasons.isNotEmpty()) {
            val permits = Semaphore(4)
            coroutineScope {
                remainingSeasons.map { season ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            try {
                                val fragment = postAjax(
                                    mapOf(
                                        "action" to "action_select_season",
                                        "season" to season.season.toString(),
                                        "post" to season.postId,
                                    ),
                                    doc.location(),
                                )
                                PeliCineHDMarkup.episodes(fragment, season.season)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
        } else {
            emptyList()
        }

        val allEpisodes = (initialEpisodes + ajaxEpisodes)
            .distinctBy { it.url }
            .distinctBy { it.season to it.number }
            .sortedWith(compareBy({ it.season }, { it.number }))

        if (allEpisodes.isEmpty()) return null

        val episodeList = allEpisodes.map { ep ->
            newEpisode(ep.url) {
                name = ep.title
                season = ep.season
                episode = ep.number
                posterUrl = ep.poster
            }
        }

        val seasonNamesList = seasons
            .filter { it.season > 0 && it.label.isNotBlank() }
            .map { SeasonData(it.season, it.label, it.season) }

        return newTvSeriesLoadResponse(detail.title, detail.url, TvType.TvSeries, episodeList) {
            posterUrl = detail.poster
            backgroundPosterUrl = detail.backdrop
            plot = detail.plot
            year = detail.year
            duration = detail.durationMinutes
            tags = detail.genres
            score = Score.from10(detail.rating)
            recommendations = recs
            if (seasonNamesList.isNotEmpty()) seasonNames = seasonNamesList
            addActors(detail.actors)
            addTrailer(detail.trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = fetch(data)
        val options = PeliCineHDMarkup.playerOptions(doc).take(24)
        if (options.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        val lock = Any()
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val permits = Semaphore(5)

        withTimeoutOrNull(45_000L) {
            coroutineScope {
                options.map { option ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            withTimeoutOrNull(18_000L) {
                                val resolved = resolveOption(option, doc.location(), subtitleCallback)
                                for (link in resolved) {
                                    val labeled = labelLink(link, option)
                                    if (!seen.add("${labeled.url}|${labeled.name}")) continue
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

    private suspend fun resolveOption(
        option: PeliCinePlayerOption,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        var target = option.url
        var referer = pageUrl

        if (PeliCineHDMarkup.siteUrl(target)) {
            val wrapperRes = try {
                site.get(target, mapOf("Referer" to pageUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return emptyList()
            }
            if (!wrapperRes.isSuccessful) return emptyList()
            if (!PeliCineHDMarkup.siteUrl(wrapperRes.url)) {
                target = wrapperRes.url
                referer = pageUrl
            } else {
                referer = wrapperRes.url
                target = PeliCineHDMarkup.unwrapWrapperUrl(Jsoup.parse(wrapperRes.text, wrapperRes.url))
                    ?: return emptyList()
            }
        }

        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(target, referer, subtitleCallback) { collected.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        val links = synchronized(collected) { collected.toList() }
        if (links.isNotEmpty()) return links

        // Generic VidHide/EarnVids fallback in case the site rotates to an unlisted mirror host.
        val fallback = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            val hostLabel = runCatching { URI(target).host.orEmpty().substringBefore('.') }
                .getOrDefault("")
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "EarnVids" }
            EarnVidsHlsExtractor.extract(target, referer, hostLabel, subtitleCallback) { fallback.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return synchronized(fallback) { fallback.toList() }
    }

    private suspend fun labelLink(link: ExtractorLink, option: PeliCinePlayerOption): ExtractorLink {
        val tag = if (option.qualityTag != null) "${option.language} · ${option.qualityTag}" else option.language
        val label = "[$tag] ${link.name}"
        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, label, link.url, link.type, link.uuid) {
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
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }
}
