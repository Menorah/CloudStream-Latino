package com.menorah.pelicinehd

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** One catalog entry (a movie or the first episode of a series). */
internal data class Card(
    val title: String,
    val url: String,
    val poster: String?,
    val movie: Boolean,
    val year: Int? = null
)

/** One row of the player list: iframe markup plus its label (`Minochinos -LATÍNO HD`). */
internal data class PlayerOption(val url: String, val language: String)

/** A series season, or one episode entry of a season. */
internal data class SeasonRef(val post: Int, val number: Int)
internal data class EpisodeItem(
    val title: String,
    val url: String,
    val season: Int,
    val number: Int,
    val poster: String?
)

/**
 * HTML parsing for pelicinehd.com.
 *
 * Everything here is pure so it can be unit tested without network access. The site runs the
 * "torofilm" WordPress theme, and its markup is:
 *
 * - catalog cards: `article.post.movies` / `article.post.series` with `h2.entry-title`,
 *   `div.post-thumbnail img`, `span.year` and `a.lnk-blk[href]`
 * - pagination: `nav.pagination a.page-link`, next page is `<current>/page/<n>/`
 * - detail: `article.post.single` with `h1.entry-title`, `figure img`, `div.description`,
 *   `span.genres a`, `span.year`, `span.duration`, `span.vote .num`
 * - players: `div.video-player#aa-options > div[id^=options-] > iframe` paired with
 *   `ul.aa-tbs-video > li > a > span.server` (`Minochinos -LATÍNO HD`). The iframe is a
 *   `?trembed=<n>&trid=<post>&trtype=1` URL on the site itself, which answers with the real
 *   hoster embed.
 * - series seasons: `div.choose-season li > a[data-post][data-season]`; the season a visitor sees
 *   is rendered server-side inside `ul#episode_by_temp`, the rest come from an
 *   `admin-ajax.php` action (`action_select_season`), which is sent back as HTML.
 * - episodes: `article.post.episodes` with `span.num-epi` (`1x1`), `h2.entry-title` and
 *   `a.lnk-blk[href]`.
 */
internal object PeliCineHDMarkup {
    const val BASE = "https://pelicinehd.com"
    const val AJAX_PATH = "/wp-admin/admin-ajax.php"

    private val episodeNumber = Regex("""^\s*(\d{1,4})\s*[xX]\s*(\d{1,5})\s*$""")
    private val duration = Regex("""(\d{1,3})\s*h\s*(\d{1,3})?\s*m?""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- URLs

    /** Only the site's own https URLs are accepted, so nothing is fetched from a foreign host. */
    fun siteUrl(url: String?): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) &&
            uri.host?.lowercase()?.let { it == "pelicinehd.com" || it == "www.pelicinehd.com" } == true &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null
    }

    /** Resolves a relative URL against the site and keeps only http(s) results. */
    fun absolute(raw: String?, base: String = "$BASE/"): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = runCatching { URI(base).resolve(value).toString() }.getOrNull() ?: return null
        return resolved.takeIf {
            runCatching {
                URI(it).scheme in listOf("http", "https") && URI(it).host != null
            }.getOrDefault(false)
        }
    }

    fun catalogUrl(path: String, page: Int): String {
        val clean = path.trim('/')
        val base = if (clean.isEmpty()) "$BASE/" else "$BASE/$clean/"
        return if (page <= 1) base else "${base}page/$page/"
    }

    fun searchUrl(query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // WordPress paginates a search as /page/<n>/?s=<term>
        return if (page <= 1) "$BASE/?s=$encoded" else "$BASE/page/$page/?s=$encoded"
    }

    /** True when a page is the Cloudflare challenge instead of the site. */
    fun isChallenge(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        return html.contains("Just a moment") || html.contains("cf-chl") ||
            html.contains("challenge-platform")
    }

    // --------------------------------------------------------------- cards

    /** Catalog/搜索结果 cards. Ads and cross-host links are dropped because there is no `a.lnk-blk`. */
    fun cards(document: Document): List<Card> =
        document.select("article.post.movies, article.post.series, article.post.episodes")
            .mapNotNull { card(it) }
            .distinctBy { it.url }

    private fun card(article: Element): Card? {
        val link = article.selectFirst("a.lnk-blk[href]") ?: return null
        val url = absolute(link.attr("href"), article.baseUri()) ?: return null
        if (!siteUrl(url)) return null
        val title = article.selectFirst("h2.entry-title, h3.entry-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val thumbnail: Element? = article.selectFirst("div.post-thumbnail img, figure img")
        val poster = absolute(thumbnail?.let { image(it) }, article.baseUri())
        val movie = !article.hasClass("series") && !url.contains("/series/")
        return Card(title, url, poster, movie, year(article))
    }

    /** Images use `src`, but some templates only ship `data-src` (lazy loading). */
    private fun image(element: Element): String? =
        listOf("data-src", "src", "data-lazy-src", "data-original")
            .map { element.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }

    private fun year(container: Element): Int? =
        container.selectFirst("span.year")?.text()?.let { text ->
            Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
        }

    fun hasNext(document: Document, page: Int): Boolean {
        val links = document.select("nav.pagination a.page-link[href]")
        if (links.isEmpty()) return false
        val currentPage = links.firstOrNull { it.hasClass("current") }
            ?.text()?.trim()?.toIntOrNull() ?: page
        return links.any { it.text().trim().toIntOrNull()?.let { number -> number > currentPage } == true }
    }

    // -------------------------------------------------------------- details

    fun title(document: Document): String? =
        document.selectFirst("article.post.single h1.entry-title, h1.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }

    fun poster(document: Document): String? {
        val thumbnail: Element? = document.selectFirst(
            "article.post.single div.post-thumbnail img, article.post.single figure img"
        )
        return absolute(thumbnail?.let { image(it) }, document.location())
    }

    fun plot(document: Document): String? =
        document.selectFirst("article.post.single div.description")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun year(document: Document): Int? =
        document.selectFirst("article.post.single span.year")?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("article.post.single span.year")?.text()
                ?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

    fun genres(document: Document): List<String> =
        document.select("article.post.single span.genres a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun actors(document: Document): List<String> =
        document.select("article.post.single ul.cast-lst li")
            .filter { it.selectFirst("span")?.text()?.contains("Actor", true) == true }
            .flatMap { it.select("p a") }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

    fun score(document: Document): Double? =
        document.selectFirst("article.post.single span.vote span.num")
            ?.text()?.trim()?.toDoubleOrNull()

    /** Duration in minutes; the site prints `1h 49m`, `7 min.` or nothing. */
    fun durationMinutes(document: Document): Int? {
        val text = document.selectFirst("article.post.single span.duration")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        duration.find(text)?.let { match ->
            val hours = match.groupValues[1].toIntOrNull() ?: 0
            val minutes = match.groupValues[2].toIntOrNull() ?: 0
            val total = hours * 60 + minutes
            if (total > 0) return total
        }
        Regex("""(\d{1,3})\s*(?:min|mins)\b""", RegexOption.IGNORE_CASE).find(text)?.let { match ->
            val minutes = match.groupValues[1].toIntOrNull()
            if (minutes != null && minutes in 1..600) return minutes
        }
        return null
    }

    fun isSeries(document: Document): Boolean =
        document.selectFirst("article.post.single")?.hasClass("series") == true ||
            document.selectFirst("section.episodes, div.choose-season") != null ||
            document.selectFirst("meta[property=og:type]")?.attr("content")?.contains("video.episode") == true

    fun recommendations(document: Document): List<Card> =
        document.select("section.section-related article.post, div.related article.post")
            .mapNotNull { card(it) }
            .distinctBy { it.url }

    // -------------------------------------------------------------- players

    /** Player list in page order; the iframe URL is what the site feeds its own player. */
    fun players(document: Document): List<PlayerOption> {
        val options = document.select("div#aa-options div[id^=options-]")
        val labels = document.select("ul.aa-tbs-video li")
        return options.mapIndexedNotNull { index, block ->
            val iframe = block.selectFirst("iframe") ?: return@mapIndexedNotNull null
            val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            val url = absolute(raw, document.location()) ?: return@mapIndexedNotNull null
            if (!siteUrl(url)) return@mapIndexedNotNull null
            PlayerOption(url, language(labels.getOrNull(index)?.text()).ifEmpty { "Latino" })
        }.distinctBy { it.url }
    }

    /** `Minochinos -LATÍNO HD` -> `Latino`, `Voe -CASTELLANO HD` -> `Castellano`. */
    fun language(label: String?): String {
        val text = label?.uppercase() ?: return ""
        return when {
            text.contains("CASTELLAN") || text.contains("ESPAÑA") || text.contains("ESPA\\") -> "Castellano"
            text.contains("SUB") -> "Subtitulado"
            text.contains("INGL") || text.contains("ENGLISH") -> "Inglés"
            text.contains("LATIN") || text.contains("LATÍNO") -> "Latino"
            else -> ""
        }
    }

    // -------------------------------------------------------- seasons/episodes

    fun seasons(document: Document): List<SeasonRef> {
        val post = seasonsPostId(document)
        return document.select("div.choose-season li a[data-season]")
            .mapNotNull { anchor ->
                val season = anchor.attr("data-season").trim().toIntOrNull() ?: return@mapNotNull null
                val owner = anchor.attr("data-post").trim().toIntOrNull() ?: post ?: return@mapNotNull null
                SeasonRef(owner, season)
            }
            .distinctBy { it.number }
            .sortedBy { it.number }
    }

    fun seasonsPostId(document: Document): Int? {
        val season: Element? = document.selectFirst("div.choose-season li a[data-post]")
        if (season != null) return season.attr("data-post").trim().toIntOrNull()
        val meta: Element? = document.selectFirst("input[name=post_id], meta[name=post_id]")
        val raw = meta?.attr("content")?.ifBlank { meta.attr("value") } ?: return null
        return raw.trim().toIntOrNull()
    }

    /** Episodes rendered in the page (the season the site shows by default). */
    fun episodes(document: Document, seasonFallback: Int = 1): List<EpisodeItem> =
        document.select("ul#episode_by_temp article.post.episodes, article.post.episodes")
            .mapNotNull { article ->
                val link = article.selectFirst("a.lnk-blk[href]") ?: return@mapNotNull null
                val url = absolute(link.attr("href"), article.baseUri()) ?: return@mapNotNull null
                if (!siteUrl(url)) return@mapNotNull null
                val numbers = parseEpisodeNumber(article.selectFirst("span.num-epi")?.text())
                val season = numbers?.first ?: seasonFallback
                val number = numbers?.second ?: return@mapNotNull null
                val title = article.selectFirst("h2.entry-title")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Episodio $number"
                val thumbnail: Element? = article.selectFirst("div.post-thumbnail img, figure img")
                val poster = absolute(thumbnail?.let { image(it) }, article.baseUri())
                EpisodeItem(title, url, season, number, poster)
            }
            .distinctBy { it.url }
            .sortedWith(compareBy({ it.season }, { it.number }))

    /** `1x4` -> (1, 4). */
    fun parseEpisodeNumber(text: String?): Pair<Int, Int>? {
        val match = episodeNumber.find(text ?: return null) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    /** The result of a season switch is the same markup the site injects. */
    fun parseSeasonHtml(html: String?, baseUrl: String, season: Int): List<EpisodeItem> {
        if (html.isNullOrBlank()) return emptyList()
        val document = org.jsoup.Jsoup.parse(html, baseUrl)
        return episodes(document, season)
    }

    // ------------------------------------------------------------------ ajax

    /** Reads the `torofilm_Public` settings the theme prints, which the AJAX requests need. */
    fun ajaxNonce(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = Regex("""nonce["']?\s*:\s*["']([a-f0-9]{6,})["']""").find(html) ?: return null
        return match.groupValues[1]
    }

    /** `<nav>` search term echo, used to confirm a search page is a search result. */
    fun searchTerm(document: Document): String? =
        document.selectFirst("input[name=s]")?.attr("value")?.trim()?.takeIf { it.isNotEmpty() }
}
