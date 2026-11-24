package com.menorah.pelicinehd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

/**
 * PeliCineHD provider (Dooplay WordPress theme). Site mechanics documented from the
 * public pages and from public third-party scrapers:
 *
 *  - Cards: `article[class*=movie]`; search `/?s=`; catalogs `/peliculas/`, `/series/`.
 *  - Series seasons come from `action_select_season` on `wp-admin/admin-ajax.php`.
 *  - Each `span.server` player is `/?trembed=<n>&trid=<post>&trtype=<1|2>`, a page
 *    whose iframe points at the hoster.
 *
 * Cloudflare fronts the site; like the rest of the web it must be reached from a
 * residential network (the browser extension ecosystem reaches it the same way).
 */
class PeliCineProvider(private val context: android.content.Context? = null) : MainAPI() {
    override var mainUrl = PeliCineMarkup.BASE
    override var name = "PeliCineHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/peliculas" to "Películas",
        "/series" to "Series",
    )

    private val browser by lazy { context?.let { PeliCineBrowser(it) } }

    private fun challenged(text: String, code: Int): Boolean =
        code == 403 || code == 503 || text.contains("Just a moment", true) || text.contains("cf-chl", true)

    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(PeliCineMarkup.siteUrl(url)) { "Dirección fuera de PeliCineHD" }
        val res = app.get(url, timeout = 15)
        var text = res.text
        var finalUrl = res.url
        if (!res.isSuccessful || !PeliCineMarkup.siteUrl(finalUrl) || challenged(text, res.code)) {
            // Cloudflare fronts the site; the device WebView passes its interactive check.
            val page = browser?.fetch(url)
            if (page != null && page.text.isNotBlank() && PeliCineMarkup.siteUrl(page.url)) {
                text = page.text
                finalUrl = page.url
            } else {
                throw IllegalStateException("PeliCineHD no respondió correctamente (HTTP ${res.code}).")
            }
        }
        return Jsoup.parse(text, finalUrl)
    }

    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { card ->
        val type = if (card.url.contains("/movies/")) TvType.Movie else TvType.TvSeries
        newMovieSearchResponse(card.title, card.url, type) { posterUrl = card.poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val path = if (page == 1) "$base/" else "$base/page/$page/"
        val doc = fetch(path)
        return newHomePageResponse(
            HomePageList(request.name, responses(PeliCineMarkup.cards(doc))),
            hasNext = PeliCineMarkup.hasNext(doc),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        return responses(PeliCineMarkup.cards(fetch("/?s=${URLEncoder.encode(text, "UTF-8")}")))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val text = query.trim()
        if (text.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = fetch("/page/$page/?s=${URLEncoder.encode(text, "UTF-8")}")
        return newSearchResponseList(responses(PeliCineMarkup.cards(doc)), hasNext = PeliCineMarkup.hasNext(doc))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val title = PeliCineMarkup.detailTitle(doc) ?: return null
        val poster = PeliCineMarkup.detailPoster(doc)
        val plot = PeliCineMarkup.detailPlot(doc)
        val year = PeliCineMarkup.detailYear(doc)
        val genres = PeliCineMarkup.detailGenres(doc)
        val isMovie = URI(url).path.startsWith("/movies/")
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.plot = plot; this.year = year
                if (genres.isNotEmpty()) this.tags = genres
            }
        }
        val post = PeliCineMarkup.postId(doc)
        val chapters = mutableListOf<Episode>()
        if (post != null) {
            for (entry in PeliCineMarkup.seasons(doc).take(40)) {
                val html = try {
                    app.post("$mainUrl/wp-admin/admin-ajax.php", referer = url,
                        data = mapOf("action" to "action_select_season", "season" to entry.season.toString(), "post" to post),
                        timeout = 15).text
                } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                if (html == null) continue
                PeliCineMarkup.seasonEpisodes(html, url).forEachIndexed { index, ref ->
                    chapters.add(newEpisode(ref.url) {
                        name = ref.title
                        season = entry.season
                        episode = index + 1
                    })
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, chapters) {
            posterUrl = poster; this.plot = plot; this.year = year
            if (genres.isNotEmpty()) this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = fetch(data)
        val post = PeliCineMarkup.postId(page) ?: return false
        val tv = !URI(data).path.startsWith("/movies/")
        val servers = PeliCineMarkup.servers(page)
        if (servers.isEmpty()) return false
        var emitted = false
        for (server in servers) {
            val resolved = try {
                val player = app.get(PeliCineMarkup.trembedUrl(server.index, post, tv),
                    referer = data, timeout = 15)
                PeliCineMarkup.trembedTarget(player.text)
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
            if (resolved == null) continue
            val label = "[${PeliCineMarkup.language(server.language)}] ${server.name}"
            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            try {
                loadExtractor(resolved, data, subtitleCallback) { link -> links.add(link) }
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            val found = synchronized(links) { links.toList() }
            for (link in found) callback(relabeled(link, label))
            if (found.isNotEmpty()) emitted = true
        }
        return emitted
    }

    private suspend fun relabeled(link: ExtractorLink, label: String): ExtractorLink {
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData
        }
    }
}
