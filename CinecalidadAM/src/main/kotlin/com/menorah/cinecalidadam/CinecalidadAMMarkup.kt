package com.menorah.cinecalidadam
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal data class Item(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class EpisodeItem(val title: String, val url: String, val season: Int, val number: Int, val poster: String?)
internal data class Player(val url: String, val language: String)
internal object CinecalidadAMMarkup {
    const val BASE = "https://www.cinecalidad.am"
    fun siteUrl(url: String): Boolean = runCatching { URI(url).let { it.scheme == "https" && it.host.equals("www.cinecalidad.am", true) && it.port in listOf(-1,443) && it.userInfo == null } }.getOrDefault(false)
    fun safeHost(url: String, host: String): Boolean = runCatching { URI(url).let { it.scheme == "https" && it.host.equals(host, true) && it.port in listOf(-1,443) && it.userInfo == null } }.getOrDefault(false)
    fun img(e: Element?): String? {
        if (e == null) return null
        return listOf(e.attr("data-src"), e.attr("src")).firstNotNullOfOrNull { x -> runCatching { URI(e.baseUri()).resolve(x).toString() }.getOrNull()?.takeIf { x.isNotBlank() && !x.startsWith("data:") && it.startsWith("https://") } }
    }
    fun cards(doc: Document): List<Item> = doc.select("article.item:not(.naadb)").mapNotNull { e ->
        val link = e.select("a[href]").firstOrNull { a -> val url = a.absUrl("href"); siteUrl(url) && (URI(url).path.startsWith("/ver-pelicula/") || URI(url).path.startsWith("/ver-serie/")) } ?: return@mapNotNull null
        val url = link.absUrl("href"); val title = e.selectFirst(".in_title")?.text()?.trim().orEmpty().ifBlank { e.selectFirst("img")?.attr("alt").orEmpty().trim() }
        if (title.isEmpty()) return@mapNotNull null
        Item(title, url, img(e.selectFirst(".poster img")), URI(url).path.startsWith("/ver-pelicula/"))
    }.distinctBy { it.url }
    /** The featured widget is separate from the regular article grid. */
    fun featured(doc: Document): List<Item> = doc.selectFirst("#destacado")?.parent()
        ?.select("ul.destacados-widget a[href]")?.mapNotNull { link ->
            val url = link.absUrl("href"); if (!siteUrl(url)) return@mapNotNull null
            val path = URI(url).path.orEmpty(); val movie = path.startsWith("/ver-pelicula/")
            if (!movie && !path.startsWith("/ver-serie/")) return@mapNotNull null
            val title = link.attr("title").trim().ifBlank { link.selectFirst("img")?.attr("alt").orEmpty().trim() }
            if (title.isBlank()) return@mapNotNull null
            val poster = img(link.selectFirst("img"))?.replace("image.tmdb.org/t/p/w185/", "image.tmdb.org/t/p/w342/")
            Item(title, url, poster, movie)
        }?.distinctBy { it.url }.orEmpty()
    fun yearQueryPath(query: String, page: Int): String? {
        val value = query.trim()
        if (!value.matches(Regex("[0-9]{4}")) || value.toInt() !in 1888..2100 || page < 1) return null
        return "/release/$value/" + if (page > 1) "page/$page/" else ""
    }
    fun isYearArchive(doc: Document, year: String): Boolean = runCatching {
        val u = URI(doc.location())
        siteUrl(doc.location()) && u.path.orEmpty().matches(Regex("/release/$year/(?:page/[0-9]+/)?"))
    }.getOrDefault(false)
    fun hasNext(doc: Document, page: Int): Boolean {
        val uri = URI(doc.location()); val path = uri.path.orEmpty().replace(Regex("/page/[0-9]+/?$"), "").trimEnd('/') + "/page/${page+1}"
        return doc.select("a[href]").any { a -> val u=a.absUrl("href"); siteUrl(u) && URI(u).path.trimEnd('/') == path && (uri.rawQuery == null || URI(u).rawQuery == uri.rawQuery) }
    }
    fun episodes(doc: Document): List<EpisodeItem> = doc.select("a[href*=/ver-el-episodio/]").mapNotNull { a ->
        val url = a.absUrl("href"); if (!siteUrl(url)) return@mapNotNull null
        val path = URI(url).path.orEmpty()
        if (!path.startsWith("/ver-el-episodio/")) return@mapNotNull null
        // Both slug-1x2 and tmdb-123-s1-e2 are used in the site's episode URLs.
        val m = Regex("-(?:s([0-9]+)-e([0-9]+)|([0-9]+)x([0-9]+))/?$", RegexOption.IGNORE_CASE).find(path) ?: return@mapNotNull null
        val season = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: return@mapNotNull null
        val number = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: return@mapNotNull null
        if (number < 1) return@mapNotNull null
        val container = a.closest("li") ?: a.parent()
        EpisodeItem(a.text().trim().ifBlank { "Episodio $number" }, url, season, number, img(container?.selectFirst("img")))
    }.distinctBy { it.url }.sortedWith(compareBy({ it.season }, { it.number }))
    fun players(doc: Document): List<Player> = doc.select("#panel_online li.dooplay_player_option[data-option]:not(.dooplay_player_option_trailer)").mapNotNull { e ->
        val url = e.attr("data-option"); if (!safeHost(url,"vimeos.net") && !safeHost(url,"videoapp.zip")) return@mapNotNull null
        val text = doc.selectFirst("#panel_online .pane_descripcion")?.text().orEmpty().lowercase()
        val language = if ("latino" in text) "Latino" else if ("castellano" in text) "Castellano" else if ("subtitul" in text) "Subtitulado" else "Idioma no indicado"
        Player(url,language)
    }.distinctBy { it.url }
    fun vimeosFromWrapper(doc: Document): String? = doc.select("iframe[src]").asSequence().map { it.absUrl("src") }.firstOrNull { safeHost(it,"vimeos.net") }
    fun title(doc: Document): String? = doc.selectFirst(".single_left h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
    fun poster(doc: Document): String? = img(doc.selectFirst(".single_left table img"))
    fun plot(doc: Document): String? = doc.select(".single_left table td:nth-child(2) p").firstOrNull { it.text().trim().isNotBlank() }?.text()?.trim()
}
