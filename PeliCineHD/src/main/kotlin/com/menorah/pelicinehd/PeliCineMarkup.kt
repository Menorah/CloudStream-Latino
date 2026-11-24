package com.menorah.pelicinehd

import org.jsoup.nodes.Document
import java.net.URI

internal data class Card(val title: String, val url: String, val poster: String?, val year: Int?)
internal data class SeasonEntry(val post: String, val season: Int)
internal data class EpisodeRef(val title: String?, val url: String)
internal data class ServerLabel(val index: Int, val name: String, val language: String)

/**
 * Dooplay-theme parsing for PeliCineHD. Documented from the site's public pages and
 * from public third-party scrapers (mangayomi-extensions, alfa-addon): the server list
 * is `span.server` on each title page and each player is `/?trembed=<n>&trid=<post>&trtype=<1|2>`.
 */
internal object PeliCineMarkup {
    const val BASE = "https://pelicinehd.com"
    private val titlePath = Regex("^/(?:movies|tvshows|series)/[^/]+/?$")
    private val episodePath = Regex("^/episodios/[^/]+/?$")

    fun siteUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && u.host.equals("pelicinehd.com", true) && u.port in listOf(-1, 443) && u.userInfo == null
    }

    fun cards(doc: Document): List<Card> = doc.select("article[class*=movie]").mapNotNull { item ->
        val link = item.selectFirst("a[href]") ?: return@mapNotNull null
        val url = link.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
        if (!titlePath.matches(URI(url).path.orEmpty())) return@mapNotNull null
        val title = item.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
            .ifBlank { item.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return@mapNotNull null
        val img = item.selectFirst("img")
        Card(title, url, img?.attr("src")?.takeIf { it.startsWith("http") } ?: img?.absUrl("src")?.takeIf { it.startsWith("http") },
            item.selectFirst(".year, .fa-calendar")?.text()?.trim()?.take(4)?.toIntOrNull())
    }.distinctBy { it.url }

    fun hasNext(doc: Document): Boolean =
        doc.select(".nav-links a, a.nextpostslink").any { it.text().trim().equals("SIGUIENTE", true) || it.hasClass("nextpostslink") }

    fun detailTitle(doc: Document): String? = doc.selectFirst("h1.entry-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    fun detailPoster(doc: Document): String? {
        val img = doc.selectFirst("img[alt*=Image], .poster img, .simg img") ?: return null
        val direct = img.attr("src").takeIf { it.startsWith("http") }
        return direct ?: img.absUrl("src").takeIf { it.startsWith("http") }
    }
    fun detailPlot(doc: Document): String? = doc.selectFirst(".description, .wp-content p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    fun detailYear(doc: Document): Int? {
        for (element in doc.select("span.fa-calendar, .date, .info1 span")) {
            val year = Regex("\\b(19|20)\\d{2}\\b").find(element.text())?.value?.toIntOrNull()
            if (year != null) return year
        }
        return null
    }
    fun detailGenres(doc: Document): List<String> = doc.select(".genres > a, .sgeneros a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

    /** Seasons of a series page: `ul.aa-cnt > li > a[data-post][data-season]`. */
    fun seasons(doc: Document): List<SeasonEntry> = doc.select("ul.aa-cnt > li > a[data-post][data-season]").mapNotNull { a ->
        val post = a.attr("data-post").trim().takeIf { it.matches(Regex("\\d{1,12}")) } ?: return@mapNotNull null
        val season = a.attr("data-season").trim().toIntOrNull() ?: return@mapNotNull null
        SeasonEntry(post, season)
    }.distinctBy { it.season }

    /** Episodes returned by `action_select_season`: `li` items with a block link. */
    fun seasonEpisodes(html: String, base: String): List<EpisodeRef> {
        val doc = org.jsoup.Jsoup.parse(html, base)
        return doc.select("li").mapNotNull { li ->
            val link = li.selectFirst("a.lnk-blk[href], a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
            if (!episodePath.matches(URI(url).path.orEmpty())) return@mapNotNull null
            EpisodeRef(li.selectFirst("h2.entry-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }, url)
        }.distinctBy { it.url }
    }

    /** Player rows: `span.server` texts look like "Nombre - LATÍNO ..." */
    fun servers(doc: Document): List<ServerLabel> = doc.select("span.server").mapIndexedNotNull { index, span ->
        val parts = span.text().trim().split('-')
        val name = parts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 } ?: return@mapIndexedNotNull null
        val language = parts.getOrNull(1)?.trim()?.split(' ')?.firstOrNull()?.orEmpty().orEmpty().take(20)
        ServerLabel(index, name, language)
    }.take(20)

    /** Post id of the page from the body class (`postid-123`). */
    fun postId(doc: Document): String? =
        Regex("\\b(?:postid|term)-(\\d{1,12})\\b").find(doc.selectFirst("body")?.attr("class").orEmpty())?.groupValues?.get(1)

    fun trembedUrl(index: Int, postId: String, tv: Boolean): String =
        "$BASE/?trembed=$index&trid=$postId&trtype=${if (tv) 2 else 1}"

    /** The trembed page embeds the hoster with an iframe; fall back to meta refresh. */
    fun trembedTarget(html: String): String? {
        val iframe = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
        if (iframe != null && iframe.startsWith("http")) return iframe
        return Regex("http-equiv=[\"']refresh[\"'][^>]*url=([^\"'>\\s]+)").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
    }

    fun language(label: String): String = when (label.uppercase()) {
        "LATÍNO", "LATINO" -> "Latino"
        "CASTELLANO", "ESPAÑOL" -> "Castellano"
        "SUBTITULADO", "VOSE" -> "Subtitulado"
        else -> label.ifBlank { "Idioma no indicado" }
    }
}
