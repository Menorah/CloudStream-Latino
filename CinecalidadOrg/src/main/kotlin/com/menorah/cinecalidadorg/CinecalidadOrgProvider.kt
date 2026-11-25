package com.menorah.cinecalidadorg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class CinecalidadOrgProvider : MainAPI() {
    override var mainUrl = CinecalidadOrgApi.BASE
    override var name = "Cinecalidad.org"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val getMainPageTimeoutMs: Long? = 120_000L
    override val searchTimeoutMs: Long? = 45_000L
    override val quickSearchTimeoutMs: Long? = 30_000L
    override val loadTimeoutMs: Long? = 60_000L
    override val loadLinksTimeoutMs: Long? = 60_000L

    private val latestYear = Calendar.getInstance().get(Calendar.YEAR).coerceIn(2026, 2100)

    override val mainPage = mainPageOf(
        "latest" to "Novedades",
        "tops:movies" to "Top Películas",
        "tops:tvshows" to "Top Series",
        "listing:movies" to "Películas",
        "listing:tvshows" to "Series",
        "listing:animes" to "Animes",
        "listing:novels" to "Novelas",
        "listing:wwe" to "WWE",
        "filter:lang:latino" to "Audio Latino",
        "filter:lang:castellano" to "Español Castellano",
        "filter:lang:subtitulado" to "Subtitulado",
        "filter:genres:accion" to "Acción",
        "filter:genres:animacion" to "Animación",
        "filter:genres:aventura" to "Aventura",
        "filter:genres:belica" to "Bélica",
        "filter:genres:ciencia-ficcion" to "Ciencia ficción",
        "filter:genres:comedia" to "Comedia",
        "filter:genres:crimen" to "Crimen",
        "filter:genres:documental" to "Documental",
        "filter:genres:drama" to "Drama",
        "filter:genres:familia" to "Familia",
        "filter:genres:fantasia" to "Fantasía",
        "filter:genres:historia" to "Historia",
        "filter:genres:misterio" to "Misterio",
        "filter:genres:musica" to "Música",
        "filter:genres:pelicula-de-tv" to "Película de TV",
        "filter:genres:romance" to "Romance",
        "filter:genres:suspense" to "Suspense",
        "filter:genres:terror" to "Terror",
        "filter:genres:western" to "Western",
        "filter:genres:action-adventure" to "Action & Adventure",
        "filter:genres:kids" to "Kids",
        "filter:genres:sci-fi-fantasy" to "Sci-Fi & Fantasy",
        "filter:genres:war-politics" to "War & Politics",
    ) + (latestYear downTo latestYear - 7).map { year ->
        mainPage("filter:years:$year", "Por año · $year")
    }

    private val catalogGate = Semaphore(6)
    private val taxMutex = Mutex()
    @Volatile
    private var taxCatalog: TaxCatalog = CinecalidadOrgApi.DEFAULT_TAXONOMIES
    @Volatile
    private var taxLoaded = false

    private val apiHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend fun getJson(url: String): String {
        require(CinecalidadOrgApi.siteUrl(url)) { "Dirección fuera de Cinecalidad.org" }
        val response = app.get(url, headers = apiHeaders, timeout = 20)
        if (!response.isSuccessful) {
            throw IllegalStateException("Cinecalidad.org no respondió correctamente (HTTP ${response.code}).")
        }
        return response.text
    }

    private suspend fun ensureTaxCatalog(forceRefresh: Boolean = false): TaxCatalog {
        if (taxLoaded && !forceRefresh) return taxCatalog
        return taxMutex.withLock {
            if (taxLoaded && !forceRefresh) return@withLock taxCatalog
            val html = runCatching {
                app.get(
                    "$mainUrl/",
                    headers = mapOf("Accept-Language" to "es-MX,es;q=0.9,en;q=0.8"),
                    timeout = 15,
                ).takeIf { it.isSuccessful }?.text
            }.getOrNull()
            if (!html.isNullOrBlank()) {
                taxCatalog = CinecalidadOrgApi.parseSiteConfigTaxonomies(html)
                taxLoaded = true
            }
            taxCatalog
        }
    }

    private suspend fun resolveTermId(tax: String, slug: String): Int? {
        val current = taxCatalog
        val cached = when (tax) {
            "genres" -> current.genresBySlug[slug]?.id
            "lang" -> current.langsBySlug[slug]?.id
            "years" -> current.yearsBySlug[slug]?.id
            else -> null
        }
        if (cached != null) return cached

        val refreshed = ensureTaxCatalog(forceRefresh = !taxLoaded)
        val afterHome = when (tax) {
            "genres" -> refreshed.genresBySlug[slug]?.id
            "lang" -> refreshed.langsBySlug[slug]?.id
            "years" -> refreshed.yearsBySlug[slug]?.id
            else -> null
        }
        if (afterHome != null) return afterHome

        val taxJson = runCatching { getJson(CinecalidadOrgApi.buildTaxMetadataUrl(tax, slug)) }.getOrNull()
        return taxJson?.let(CinecalidadOrgApi::parseTaxTermId)
    }

    private fun responses(cards: List<OrgCard>): List<SearchResponse> = cards.map { card ->
        when {
            CinecalidadOrgApi.isMovieType(card.postType) ->
                newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                    posterUrl = card.poster
                }
            CinecalidadOrgApi.isAnimeType(card.postType) ->
                newTvSeriesSearchResponse(card.title, card.url, TvType.Anime) {
                    posterUrl = card.poster
                }
            else ->
                newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                    posterUrl = card.poster
                }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        if (data == "latest" && page > 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }

        val listing = try {
            catalogGate.withPermit {
                when {
                    data == "latest" -> {
                        CinecalidadOrgApi.parseListing(getJson(CinecalidadOrgApi.buildLatestUrl()))
                            .copy(hasNext = false)
                    }
                    data.startsWith("tops:") -> {
                        val postType = data.removePrefix("tops:")
                        CinecalidadOrgApi.parseListing(getJson(CinecalidadOrgApi.buildTopsUrl(postType, page)))
                    }
                    data.startsWith("listing:") -> {
                        val postType = data.removePrefix("listing:")
                        CinecalidadOrgApi.parseListing(getJson(CinecalidadOrgApi.buildListingUrl(postType, page)))
                    }
                    data.startsWith("filter:") -> {
                        val parts = data.split(':', limit = 3)
                        if (parts.size != 3) {
                            OrgListing(emptyList(), hasNext = false)
                        } else {
                            val termId = resolveTermId(parts[1], parts[2])
                            if (termId == null) {
                                OrgListing(emptyList(), hasNext = false)
                            } else {
                                CinecalidadOrgApi.parseListing(
                                    getJson(CinecalidadOrgApi.buildFilterListingUrl(parts[1], termId, page)),
                                )
                            }
                        }
                    }
                    else -> OrgListing(emptyList(), hasNext = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Secondary genre/language/year shelves should not take down the home screen if empty or stale.
            if (data.startsWith("filter:")) {
                return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            }
            throw e
        }

        return newHomePageResponse(
            HomePageList(request.name, responses(listing.items)),
            hasNext = listing.hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items.orEmpty()

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val trimmed = query.trim()

        val year = CinecalidadOrgApi.yearQuery(trimmed)
        if (year != null) {
            val termId = resolveTermId("years", year)
            if (termId != null) {
                val filtered = runCatching {
                    CinecalidadOrgApi.parseListing(getJson(CinecalidadOrgApi.buildFilterListingUrl("years", termId, page)))
                }.getOrNull()
                if (filtered != null && (filtered.items.isNotEmpty() || page > 1)) {
                    return newSearchResponseList(responses(filtered.items), hasNext = filtered.hasNext)
                }
            }
        }

        val listing = CinecalidadOrgApi.parseListing(getJson(CinecalidadOrgApi.buildSearchUrl(trimmed, page)))
        return newSearchResponseList(responses(listing.items), hasNext = listing.hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (parsedType, slug) = CinecalidadOrgApi.parseContentUrl(url) ?: return null

        val detail = if (parsedType == "episodes") {
            val epJson = getJson(CinecalidadOrgApi.buildSingleUrl("episodes", slug))
            CinecalidadOrgApi.parseEpisodeSingleIds(epJson).second ?: return null
        } else {
            val singleJson = getJson(CinecalidadOrgApi.buildSingleUrl(parsedType, slug))
            CinecalidadOrgApi.parseSingle(singleJson, parsedType) ?: return null
        }

        val catalog = runCatching { ensureTaxCatalog() }.getOrDefault(taxCatalog)
        val genreTags = detail.genreIds.mapNotNull { catalog.genresById[it]?.name }.distinct()

        return coroutineScope {
            val castDeferred = async(Dispatchers.IO) {
                runCatching {
                    CinecalidadOrgApi.parseCast(getJson(CinecalidadOrgApi.buildCastUrl(detail.postType, detail.id)))
                }.getOrDefault(emptyList())
            }
            val relatedDeferred = async(Dispatchers.IO) {
                runCatching {
                    CinecalidadOrgApi.parseListing(getJson(CinecalidadOrgApi.buildRelatedUrl(detail.id))).items
                }.getOrDefault(emptyList())
            }

            if (CinecalidadOrgApi.isMovieType(detail.postType)) {
                val actors = castDeferred.await().map { ActorData(Actor(it.name, it.imageUrl), roleString = it.role) }
                val recommendations = responses(relatedDeferred.await().filter { it.url != detail.url })
                return@coroutineScope newMovieLoadResponse(
                    detail.title,
                    detail.url,
                    TvType.Movie,
                    CinecalidadOrgApi.buildPlayerUrl(detail.id),
                ) {
                    posterUrl = detail.poster
                    backgroundPosterUrl = detail.backdrop
                    plot = detail.overview
                    year = detail.year
                    duration = detail.durationMinutes
                    tags = genreTags
                    score = Score.from10(detail.rating)
                    this.recommendations = recommendations
                    if (actors.isNotEmpty()) addActors(actors)
                    addTrailer(detail.trailerUrl)
                }
            }

            val episodes = loadAllEpisodes(detail.id)
            if (episodes.isEmpty()) return@coroutineScope null

            val actors = castDeferred.await().map { ActorData(Actor(it.name, it.imageUrl), roleString = it.role) }
            val recommendations = responses(relatedDeferred.await().filter { it.url != detail.url })
            val tvType = if (CinecalidadOrgApi.isAnimeType(detail.postType)) TvType.Anime else TvType.TvSeries

            val episodeItems = episodes.map { ep ->
                newEpisode(ep.dataUrl) {
                    name = ep.title
                    season = ep.season
                    episode = ep.number
                    posterUrl = ep.poster
                    description = ep.overview
                    runTime = ep.durationMinutes
                }
            }
            val seasonsList = episodes.asSequence()
                .map { it.season }
                .filter { it > 0 }
                .distinct()
                .sorted()
                .map { s -> SeasonData(s, "Temporada $s", s) }
                .toList()

            newTvSeriesLoadResponse(detail.title, detail.url, tvType, episodeItems) {
                posterUrl = detail.poster
                backgroundPosterUrl = detail.backdrop
                plot = detail.overview
                year = detail.year
                duration = detail.durationMinutes
                tags = genreTags
                score = Score.from10(detail.rating)
                this.recommendations = recommendations
                if (seasonsList.isNotEmpty()) seasonNames = seasonsList
                if (actors.isNotEmpty()) addActors(actors)
                addTrailer(detail.trailerUrl)
            }
        }
    }

    private suspend fun loadAllEpisodes(showId: Long): List<OrgEpisode> = coroutineScope {
        val firstPageJson = getJson(CinecalidadOrgApi.buildEpisodesListUrl(showId, season = 1, page = 1))
        val firstPage = CinecalidadOrgApi.parseEpisodesPage(firstPageJson)

        val collected = Collections.synchronizedList(mutableListOf<OrgEpisode>())
        collected.addAll(firstPage.episodes)

        val firstSeasonReturned = firstPage.episodes.firstOrNull()?.season
        if (firstPage.hasNext && firstSeasonReturned != null) {
            for (p in 2..firstPage.lastPage) {
                val nextPage = runCatching {
                    CinecalidadOrgApi.parseEpisodesPage(
                        getJson(CinecalidadOrgApi.buildEpisodesListUrl(showId, firstSeasonReturned, p)),
                    )
                }.getOrNull() ?: break
                collected.addAll(nextPage.episodes)
            }
        }

        val remainingSeasons = firstPage.seasons.filter { it != 1 }

        if (remainingSeasons.isNotEmpty()) {
            val permits = Semaphore(4)
            remainingSeasons.map { seasonNum ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val seasonFirst = runCatching {
                            CinecalidadOrgApi.parseEpisodesPage(
                                getJson(CinecalidadOrgApi.buildEpisodesListUrl(showId, seasonNum, 1)),
                            )
                        }.getOrNull() ?: return@withPermit emptyList()
                        val items = seasonFirst.episodes.toMutableList()
                        if (seasonFirst.hasNext) {
                            for (p in 2..seasonFirst.lastPage) {
                                val more = runCatching {
                                    CinecalidadOrgApi.parseEpisodesPage(
                                        getJson(CinecalidadOrgApi.buildEpisodesListUrl(showId, seasonNum, p)),
                                    )
                                }.getOrNull() ?: break
                                items.addAll(more.episodes)
                            }
                        }
                        items
                    }
                }
            }.awaitAll().forEach { collected.addAll(it) }
        }

        collected.toList()
            .distinctBy { it.season to it.number }
            .sortedWith(compareBy({ it.season }, { it.number }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerUrl = resolvePlayerApiUrl(data) ?: return false
        val playerJson = getJson(playerUrl)
        val rawEmbeds = CinecalidadOrgApi.parsePlayerEmbeds(playerJson)
        val embeds = rawEmbeds.sortedBy { CinecalidadOrgApi.safeHost(it.url, "videoapp.zip") }.take(24)
        if (embeds.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        val lock = Any()
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val permits = Semaphore(5)

        withTimeoutOrNull(45_000L) {
            coroutineScope {
                embeds.map { embed ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            withTimeoutOrNull(18_000L) {
                                val resolved = resolveEmbed(embed, subtitleCallback)
                                for (link in resolved) {
                                    val labeled = labelLink(link, embed)
                                    val key = "${labeled.url}|${embed.languageTag}"
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

    private suspend fun resolvePlayerApiUrl(data: String): String? {
        CinecalidadOrgApi.parsePlayerPostId(data)?.let { id ->
            return CinecalidadOrgApi.buildPlayerUrl(id)
        }
        val (postType, slug) = CinecalidadOrgApi.parseContentUrl(data) ?: return null
        val id = if (postType == "episodes") {
            val epJson = getJson(CinecalidadOrgApi.buildSingleUrl("episodes", slug))
            CinecalidadOrgApi.parseEpisodeSingleIds(epJson).first
        } else {
            val singleJson = getJson(CinecalidadOrgApi.buildSingleUrl(postType, slug))
            CinecalidadOrgApi.parseSingle(singleJson, postType)?.id
        } ?: return null
        return CinecalidadOrgApi.buildPlayerUrl(id)
    }

    private suspend fun resolveEmbed(
        embed: OrgEmbed,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        var target = embed.url
        var referer = "$mainUrl/"

        if (CinecalidadOrgApi.safeHost(target, "videoapp.zip")) {
            val wrapperRes = try {
                app.get(target, headers = mapOf("Referer" to referer), timeout = 10)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return emptyList()
            }
            if (!wrapperRes.isSuccessful) return emptyList()
            referer = wrapperRes.url
            target = CinecalidadOrgApi.unwrapWrapperIframe(Jsoup.parse(wrapperRes.text, wrapperRes.url))
                ?: return emptyList()
        }

        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(target, referer, subtitleCallback) { collected.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return synchronized(collected) { collected.toList() }
    }

    private suspend fun labelLink(link: ExtractorLink, embed: OrgEmbed): ExtractorLink {
        val label = CinecalidadOrgApi.formatSourceLabel(embed, link.name)
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
