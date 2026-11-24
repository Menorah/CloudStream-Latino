package com.menorah.pelicinehd

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal data class Card(val title: String, val url: String, val poster: String?, val year: Int?)
internal data class SeasonEntry(val post: String, val season: Int)
internal data class EpisodeRef(val title: String?, val url: String)
internal data class ServerLabel(val index: Int, val name: String, val language: String)

/**
 * Dooplay-theme parsing for PeliCineHD, verified against the live site (November 2026):
 * titles live at `/movies/<slug>/` and `/series/<slug>/` (`/pelicula/...` 301s to `/movies/...`),
 * catalogs at `/peliculas/` and `/series/`, search at `/?s=`. Players are Dooplay trembed
 * pages: `/?trembed=<n>&trid=<post>&trtype=<1|2>`.
 */
internal object PeliCineMarkup {
    const val BASE = "https://pelicinehd.com"
    private val titlePath = Regex("^/(?:movies|tvshows|series|peliculas?)/[^/]+/?$")
    private val episodePath = Regex("^(?:/episodios?/[^/]+/?|/episode/[^/]+/?|/[^/]+-\\d+x\\d+/?)$")
    private val blocked = "aside, .sidebar, .widget-area, .related, #relacionados, footer, nav, .navbar"

    fun siteUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && u.host.equals("pelicinehd.com", true) && u.port in listOf(-1, 443) && u.userInfo == null
    }

    private fun imgOf(element: Element?): String? {
        if (element == null) return null
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val raw = element.attr(attr).trim()
            if (raw.isEmpty()) continue
            val value = element.absUrl(attr).ifBlank { raw }
            if (value.startsWith("http") && !value.startsWith("data:")) return value
        }
        val style = element.attr("style")
        Regex("url\\([\"']?(https?://[^\"')]+)").find(style)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun titleOf(container: Element, link: Element, img: Element?): String {
        container.selectFirst("h2.entry-title, h1.entry-title, .titulo, h3, h2")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val alt = img?.attr("alt")?.trim().orEmpty()
            .removePrefix("Image ").removePrefix("Poster ").trim()
        if (alt.isNotBlank() && !alt.equals("Image", true) && !alt.equals("Poster", true)) return alt
        return link.text().trim().replace(Regex("\\s*Ver (pel[ií]cula|Serie|episodio)\\s*$", RegexOption.IGNORE_CASE), "").trim()
    }

    /** Catalog/search cards. Works with `article` items and with generic link blocks. */
    fun cards(doc: Document): List<Card> {
        val out = mutableListOf<Card>()
        val seen = mutableSetOf<String>()
        val containers = doc.select("article, .movie-item, .item, .post-item, li")
        val pool = if (containers.isNotEmpty()) containers else doc.select("a[href]")
        for (container in pool) {
            if (container.`is`("a")) {
                val card = cardFrom(container.parent() ?: continue, container, seen) ?: continue
                out.add(card)
            } else {
                val link = container.selectFirst("a[href]") ?: continue
                val card = cardFrom(container, link, seen) ?: continue
                out.add(card)
            }
        }
        return out
    }

    private fun cardFrom(container: Element, link: Element, seen: MutableSet<String>): Card? {
        if (container.`is`(blocked) || link.`is`(blocked) || link.closest(blocked) != null) return null
        val url = link.absUrl("href").takeIf(::siteUrl) ?: return null
        val path = URI(url).path.orEmpty()
        if (!titlePath.matches(path)) return null
        if (!seen.add(url)) return null
        val img = container.selectFirst("img") ?: link.selectFirst("img")
        val title = titleOf(container, link, img)
        if (title.isBlank()) return null
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(container.text())?.value?.toIntOrNull()
        return Card(title, url, imgOf(img), year)
    }

    fun hasNext(doc: Document): Boolean =
        doc.select(".nav-links a, a.nextpostslink").any {
            it.text().trim().equals("SIGUIENTE", true) || it.text().trim().equals("Next", true) || it.hasClass("nextpostslink")
        }

    fun detailTitle(doc: Document): String? = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    fun detailPoster(doc: Document): String? {
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
        return imgOf(doc.selectFirst(".poster img, .simg img, img[alt*=Image], img[alt*=Poster]"))
    }
    fun detailPlot(doc: Document): String? = doc.selectFirst(".description, .sinopsis, .wp-content p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    fun detailYear(doc: Document): Int? {
        for (element in doc.select("span.fa-calendar, .date, .info1 span, .dato")) {
            val year = Regex("\\b(19|20)\\d{2}\\b").find(element.text())?.value?.toIntOrNull()
            if (year != null) return year
        }
        return null
    }
    fun detailGenres(doc: Document): List<String> = doc.select(".genres > a, .sgeneros a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

    /** Seasons of a series page: Dooplay `a[data-post][data-season]`, plus generic links. */
    fun seasons(doc: Document): List<SeasonEntry> {
        val out = mutableListOf<SeasonEntry>()
        val seen = mutableSetOf<Int>()
        val fallbackPost = postId(doc)
        for (a in doc.select("a[data-season]")) {
            val season = a.attr("data-season").trim().toIntOrNull() ?: continue
            val post = a.attr("data-post").trim().ifBlank { fallbackPost.orEmpty() }
                .takeIf { it.matches(Regex("\\d{1,12}")) } ?: continue
            if (seen.add(season)) out.add(SeasonEntry(post, season))
        }
        if (out.isEmpty()) {
            for (a in doc.select("a[href*=/temporada/]")) {
                val season = Regex("(?i)temporada[\\s-]*(\\d{1,3})").find(a.text() + " " + a.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val post = fallbackPost ?: continue
                if (seen.add(season)) out.add(SeasonEntry(post, season))
            }
        }
        return out
    }

    /** Episodes returned by `action_select_season`, or listed directly on custom themes. */
    fun seasonEpisodes(html: String, base: String): List<EpisodeRef> {
        val doc = org.jsoup.Jsoup.parse(html, base)
        return doc.select("li, article, .episode").mapNotNull { li ->
            val link = li.selectFirst("a.lnk-blk[href], a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
            if (!episodePath.matches(URI(url).path.orEmpty())) return@mapNotNull null
            val title = li.selectFirst("h2.entry-title, .entry-title, .titulo")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: link.text().trim().takeIf { it.isNotEmpty() }
            EpisodeRef(title, url)
        }.distinctBy { it.url }
    }

    /** Player rows: `span.server` texts look like "Nombre - LATÍNO ...". */
    fun servers(doc: Document): List<ServerLabel> = doc.select("span.server").mapIndexedNotNull { index, span ->
        val parts = span.text().trim().split('-')
        val name = parts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 } ?: return@mapIndexedNotNull null
        val language = parts.getOrNull(1)?.trim()?.split(' ')?.firstOrNull()?.orEmpty().orEmpty().take(20)
        ServerLabel(index, name, language)
    }.take(20)

    /** Post id of the page from the body class (`postid-123`) or Dooplay data attributes. */
    fun postId(doc: Document): String? =
        Regex("\\b(?:postid|term)-(\\d{1,12})\\b").find(doc.selectFirst("body")?.attr("class").orEmpty())?.groupValues?.get(1)
            ?: doc.selectFirst("[data-post]")?.attr("data-post")?.trim()?.takeIf { it.matches(Regex("\\d{1,12}")) }

    fun trembedUrl(index: Int, postId: String, tv: Boolean): String =
        "$BASE/?trembed=$index&trid=$postId&trtype=${if (tv) 2 else 1}"

    /** The trembed page embeds the hoster with an iframe; VOE inlines `data-options` JSON. */
    fun trembedTarget(html: String, base: String = BASE): String? {
        val doc = org.jsoup.Jsoup.parse(html, base)
        doc.selectFirst("iframe[src]")?.let { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            if (src.startsWith("http")) return src
        }
        manifest(html)?.let { return it }
        return Regex("http-equiv=[\"']refresh[\"'][^>]*url=([^\"'>\\s]+)").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
    }

    /** VOE and friends inline a JSON blob: `data-options='{"hls_manifest_url":...}'`. */
    fun manifest(html: String): String? {
        for (match in Regex("data-options=([\"'])(.*?)\\1").findAll(html)) {
            val json = org.jsoup.parser.Parser.unescapeEntities(match.groupValues[2], false)
            for (key in listOf("hls_manifest_url", "hls", "mp4_url", "mp4", "file", "src")) {
                val value = Regex("\"$key\"\\s*:\\s*\"(https?://[^\"]+)\"").find(json)?.groupValues?.get(1)
                if (value != null) return value.replace("\\/", "/")
            }
        }
        return null
    }

    fun language(label: String): String = when (label.uppercase()) {
        "LATÍNO", "LATINO" -> "Latino"
        "CASTELLANO", "ESPAÑOL" -> "Castellano"
        "SUBTITULADO", "VOSE" -> "Subtitulado"
        else -> label.ifBlank { "Idioma no indicado" }
    }
}
