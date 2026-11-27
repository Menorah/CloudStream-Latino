package com.menorah.latanime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LatanimeProvider : MainAPI() {
    override var mainUrl = LatanimeMarkup.DEFAULT_MAIN_URL
    override var name = "Latanime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "es-419"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val getMainPageTimeoutMs: Long? = 45_000L
    override val searchTimeoutMs: Long? = 45_000L
    override val quickSearchTimeoutMs: Long? = 30_000L
    override val loadTimeoutMs: Long? = 30_000L
    override val loadLinksTimeoutMs: Long? = 45_000L

    override val mainPage = mainPageOf(
        *LatanimeMarkup.mainPageCategories(mainUrl).toTypedArray()
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to LatanimeHttpDefaults.UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
    )

    private data class CachedCards(
        val items: List<LatanimeCard>,
        val fetchedAtMs: Long,
    )

    private val kindCache = ConcurrentHashMap<String, LatanimeCardKind>()
    private val letterCache = ConcurrentHashMap<String, CachedCards>()
    @Volatile
    private var fullCatalogCache: CachedCards? = null

    private companion object {
        const val CACHE_TTL_MS = 20L * 60L * 1000L
        const val SEARCH_PAGE_SIZE = 30
    }

    private fun rememberKinds(cards: List<LatanimeCard>) {
        for (card in cards) {
            val slug = LatanimeMarkup.slugFromUrl(card.url)
            if (slug.isNotBlank() && LatanimeMarkup.isOwnedAnimeUrl(card.url, mainUrl)) {
                kindCache[slug] = card.kind
            }
        }
    }

    private fun LatanimeCard.toSearchResponse(): SearchResponse {
        val tvType = LatanimeMarkup.kindToTvType(kind)
        return if (tvType == TvType.AnimeMovie) {
            newMovieSearchResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = this@toSearchResponse.posterUrl
            }
        } else {
            newAnimeSearchResponse(title, url, tvType) {
                this.posterUrl = this@toSearchResponse.posterUrl
                val ep = this@toSearchResponse.episodeNumber
                if (ep != null && ep > 0) {
                    addDubStatus(isDub = this@toSearchResponse.isDub, episodes = ep)
                } else if (this@toSearchResponse.isDub) {
                    addDubStatus(isDub = true, episodes = null)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        when (request.data) {
            LatanimeMarkup.SECTION_HOME_EPISODES -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
                val res = app.get("$mainUrl/", headers = defaultHeaders, timeout = 15L)
                if (!res.isSuccessful) return newHomePageResponse(request, emptyList(), hasNext = false)
                val doc = Jsoup.parse(res.text, res.url)
                val cards = LatanimeMarkup.parseHomeRecentEpisodes(doc, mainUrl)
                return newHomePageResponse(request, cards.map { it.toSearchResponse() }, hasNext = false)
            }

            LatanimeMarkup.SECTION_CALENDAR -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
                val res = app.get("$mainUrl/calendario", referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
                if (!res.isSuccessful) return newHomePageResponse(request, emptyList(), hasNext = false)
                val doc = Jsoup.parse(res.text, res.url)
                val cards = LatanimeMarkup.parseCalendarCards(doc, mainUrl)
                rememberKinds(cards)
                return newHomePageResponse(request, cards.map { it.toSearchResponse() }, hasNext = false)
            }

            else -> {
                val targetUrl = LatanimeMarkup.buildPagedUrl(request.data, page, mainUrl)
                val res = app.get(targetUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
                if (!res.isSuccessful) return newHomePageResponse(request, emptyList(), hasNext = false)
                val doc = Jsoup.parse(res.text, res.url)
                val paged = LatanimeMarkup.parseCatalogCards(doc, mainUrl, page)
                rememberKinds(paged.items)
                return newHomePageResponse(
                    request,
                    paged.items.map { it.toSearchResponse() },
                    hasNext = paged.hasNext,
                )
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)

        // Tier 1: Try native /buscar endpoint first when not challenged by Cloudflare
        val directUrl = LatanimeMarkup.buildSearchUrl(trimmed, page, mainUrl)
        val directResp = runCatching {
            app.get(directUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 10L)
        }.getOrNull()
        if (directResp != null &&
            directResp.isSuccessful &&
            !LatanimeMarkup.isCloudflareChallenge(
                directResp.code,
                directResp.text,
                directResp.headers["cf-mitigated"],
            )
        ) {
            val doc = Jsoup.parse(directResp.text, directResp.url)
            val parsed = LatanimeMarkup.parseCatalogCards(doc, mainUrl, page)
            if (parsed.items.isNotEmpty()) {
                rememberKinds(parsed.items)
                return newSearchResponseList(parsed.items.map { it.toSearchResponse() }, hasNext = parsed.hasNext)
            }
        }

        // Tier 2: Fast letter-bucket scan via /animes?letra=... (never Cloudflare-challenged)
        val pool = LinkedHashMap<String, LatanimeCard>()
        val letters = LatanimeMarkup.candidateLetters(trimmed)
        if (letters.isNotEmpty()) {
            val letterCards = fetchLettersParallel(letters)
            for (card in letterCards) pool[card.url] = card
        }

        var ranked = LatanimeMarkup.filterAndRankCards(pool.values, trimmed)

        // Tier 3: Full directory scan (cached for 20 min) if query matches mid-title words
        if (ranked.isEmpty() || (ranked.size < 3 && LatanimeMarkup.normalizeForSearch(trimmed).length >= 4)) {
            val catalogCards = fetchOrGetFullCatalog()
            for (card in catalogCards) pool[card.url] = card
            ranked = LatanimeMarkup.filterAndRankCards(pool.values, trimmed)
        }

        rememberKinds(ranked)
        val offset = ((page - 1).coerceAtLeast(0)) * SEARCH_PAGE_SIZE
        val slice = ranked.drop(offset).take(SEARCH_PAGE_SIZE)
        val hasNext = ranked.size > offset + SEARCH_PAGE_SIZE
        return newSearchResponseList(slice.map { it.toSearchResponse() }, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    private suspend fun fetchLettersParallel(letters: List<String>): List<LatanimeCard> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<LatanimeCard>()
        val missingLetters = mutableListOf<String>()
        for (letter in letters) {
            val cached = letterCache[letter]
            if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) {
                results.addAll(cached.items)
            } else {
                missingLetters.add(letter)
            }
        }
        if (missingLetters.isEmpty()) return results

        val gate = Semaphore(8)
        coroutineScope {
            val fetched = missingLetters.map { letter ->
                async(Dispatchers.IO) {
                    val url1 = LatanimeMarkup.buildLetterFilterUrl(letter, 1, mainUrl)
                    val res1 = runCatching {
                        gate.withPermit {
                            app.get(url1, referer = "$mainUrl/", headers = defaultHeaders, timeout = 12L)
                        }
                    }.getOrNull() ?: return@async emptyList()
                    if (!res1.isSuccessful) return@async emptyList()
                    val page1 = LatanimeMarkup.parseCatalogCards(Jsoup.parse(res1.text, res1.url), mainUrl, 1)
                    val all = page1.items.toMutableList()
                    val maxP = page1.maxPage.coerceAtMost(20)
                    if (maxP > 1) {
                        val rest = (2..maxP).map { p ->
                            async(Dispatchers.IO) {
                                val u = LatanimeMarkup.buildLetterFilterUrl(letter, p, mainUrl)
                                runCatching {
                                    gate.withPermit {
                                        val r = app.get(u, referer = "$mainUrl/", headers = defaultHeaders, timeout = 12L)
                                        if (r.isSuccessful) {
                                            LatanimeMarkup.parseCatalogCards(Jsoup.parse(r.text, r.url), mainUrl, p).items
                                        } else {
                                            emptyList()
                                        }
                                    }
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll().flatten()
                        all.addAll(rest)
                    }
                    val distinct = all.distinctBy { it.url }
                    letterCache[letter] = CachedCards(distinct, System.currentTimeMillis())
                    distinct
                }
            }.awaitAll().flatten()
            results.addAll(fetched)
        }
        return results
    }

    private suspend fun fetchOrGetFullCatalog(): List<LatanimeCard> {
        val now = System.currentTimeMillis()
        fullCatalogCache?.let { cached ->
            if (now - cached.fetchedAtMs < CACHE_TTL_MS) return cached.items
        }
        val baseCatalogUrl = "$mainUrl/animes"
        val res1 = runCatching {
            app.get(baseCatalogUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        }.getOrNull() ?: return emptyList()
        if (!res1.isSuccessful) return emptyList()

        val page1 = LatanimeMarkup.parseCatalogCards(Jsoup.parse(res1.text, res1.url), mainUrl, 1)
        val collected = LinkedHashMap<String, LatanimeCard>()
        for (c in page1.items) collected[c.url] = c

        val maxPage = page1.maxPage.coerceAtLeast(1)
        val gate = Semaphore(12)
        var current = 2
        while (current <= maxPage) {
            val batchEnd = minOf(current + 23, maxPage)
            val batch = coroutineScope {
                (current..batchEnd).map { p ->
                    async(Dispatchers.IO) {
                        val url = LatanimeMarkup.buildPagedUrl(baseCatalogUrl, p, mainUrl)
                        runCatching {
                            gate.withPermit {
                                val r = app.get(url, referer = "$mainUrl/", headers = defaultHeaders, timeout = 12L)
                                if (r.isSuccessful) {
                                    LatanimeMarkup.parseCatalogCards(Jsoup.parse(r.text, r.url), mainUrl, p).items
                                } else {
                                    emptyList()
                                }
                            }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            var anyNonEmpty = false
            for (items in batch) {
                if (items.isNotEmpty()) {
                    anyNonEmpty = true
                    for (c in items) collected[c.url] = c
                }
            }
            if (!anyNonEmpty) break
            current = batchEnd + 1
        }

        val finalList = collected.values.toList()
        if (finalList.isNotEmpty()) {
            fullCatalogCache = CachedCards(finalList, System.currentTimeMillis())
        }
        return finalList
    }

    override suspend fun load(url: String): LoadResponse? =
        loadInternal(url, allowParentRedirect = true)

    private suspend fun loadInternal(url: String, allowParentRedirect: Boolean): LoadResponse? {
        val resolvedUrl = LatanimeMarkup.resolveUrl(mainUrl, url) ?: url
        val res = app.get(resolvedUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        if (!res.isSuccessful) return null
        val finalUrl = res.url
        val doc = Jsoup.parse(res.text, finalUrl)

        if (LatanimeMarkup.isOwnedEpisodeUrl(finalUrl, mainUrl)) {
            val epPage = LatanimeMarkup.parseEpisodePage(doc, finalUrl, mainUrl)
            val parentUrl = epPage.parentAnimeUrl
            if (allowParentRedirect && !parentUrl.isNullOrBlank() && parentUrl.trimEnd('/') != finalUrl.trimEnd('/')) {
                val parentResp = runCatching { loadInternal(parentUrl, allowParentRedirect = false) }.getOrNull()
                if (parentResp != null) return parentResp
            }
            return newMovieLoadResponse(epPage.title, finalUrl, TvType.AnimeMovie, finalUrl)
        }

        val slug = LatanimeMarkup.slugFromUrl(finalUrl)
        val detail = LatanimeMarkup.parseAnimeDetail(doc, finalUrl, mainUrl, kindCache[slug]) ?: return null

        if (detail.kind == LatanimeCardKind.MOVIE && detail.episodes.size == 1) {
            return newMovieLoadResponse(detail.title, finalUrl, TvType.AnimeMovie, detail.episodes.first().url) {
                this.posterUrl = detail.posterUrl
                this.backgroundPosterUrl = detail.posterUrl
                this.year = detail.year
                this.plot = detail.plot
                this.tags = detail.tags
            }
        }

        val episodes = detail.episodes.map { ep ->
            newEpisode(ep.url) {
                this.name = ep.name
                this.episode = ep.episode
                this.posterUrl = ep.posterUrl ?: detail.posterUrl
            }
        }
        val tvType = LatanimeMarkup.kindToTvType(detail.kind)
        val dubStatus = if (detail.isDub) DubStatus.Dubbed else DubStatus.Subbed

        return newAnimeLoadResponse(detail.title, finalUrl, tvType) {
            this.posterUrl = detail.posterUrl
            this.backgroundPosterUrl = detail.posterUrl
            this.year = detail.year
            this.plot = detail.plot
            this.tags = detail.tags
            this.showStatus = detail.status
            addEpisodes(dubStatus, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resolvedUrl = LatanimeMarkup.resolveUrl(mainUrl, data) ?: return false
        var res = runCatching {
            app.get(resolvedUrl, referer = "$mainUrl/", headers = defaultHeaders, timeout = 15L)
        }.getOrNull() ?: return false
        if (!res.isSuccessful) return false

        var doc = Jsoup.parse(res.text, res.url)
        if (LatanimeMarkup.isOwnedAnimeUrl(res.url, mainUrl)) {
            val detail = LatanimeMarkup.parseAnimeDetail(doc, res.url, mainUrl)
            val firstEpUrl = detail?.episodes?.firstOrNull()?.url ?: return false
            res = runCatching {
                app.get(firstEpUrl, referer = res.url, headers = defaultHeaders, timeout = 15L)
            }.getOrNull() ?: return false
            if (!res.isSuccessful) return false
            doc = Jsoup.parse(res.text, res.url)
        }

        val epPage = LatanimeMarkup.parseEpisodePage(doc, res.url, mainUrl)
        val allSources = epPage.players + epPage.downloads
        if (allSources.isEmpty()) return false

        val emittedAny = AtomicBoolean(false)
        allSources.amap { source ->
            LatanimeExtractorDispatcher.extractSource(
                source = source,
                refererPageUrl = res.url,
                subtitleCallback = subtitleCallback,
                callback = { link ->
                    emittedAny.set(true)
                    callback(link)
                },
            )
        }
        return emittedAny.get()
    }
}
