package com.menorah.cinecalidadmy

import org.jsoup.nodes.Document
import java.net.URI

internal data class Film(val title: String, val url: String, val image: String?)
internal data class Player(val url: String, val language: String)
internal object CinecalidadMarkup {
    const val BASE = "https://www.cinecalidad.my"
    fun siteUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && u.host.equals("www.cinecalidad.my", true) && u.port in listOf(-1,443) && u.userInfo == null
    }
    fun cards(doc: Document): List<Film> = doc.select(".home_post_cont.post_box a[href]").mapNotNull { a ->
        val url = a.absUrl("href"); if (!siteUrl(url) || !URI(url).path.orEmpty().startsWith("/pelicula/")) return@mapNotNull null
        val img = a.selectFirst("img") ?: return@mapNotNull null
        val name = img.attr("title").ifBlank { img.attr("alt") }.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Film(name,url,img.absUrl("src").takeIf { it.startsWith("https://") })
    }.distinctBy { it.url }
    fun hasNext(doc: Document, page: Int): Boolean {
        val curr = URI(doc.location())
        val path = curr.path.orEmpty().replace(Regex("/page/[0-9]+/?$"), "").trimEnd('/') + "/page/${page + 1}"
        return doc.select("a[href]").any { a ->
            val url = a.absUrl("href")
            if (!siteUrl(url)) false else URI(url).let { it.path.trimEnd('/') == path && it.rawQuery == curr.rawQuery }
        }
    }
    fun players(doc: Document): List<Player> = doc.select("#panel_online a.onlinelink[service][data]").mapNotNull { a ->
        val id = a.attr("data").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) } ?: return@mapNotNull null
        val host = when (a.attr("service")) {
            "OnlineFilemoon" -> "https://bysedikamoum.com/e/"
            "OnlineVoe" -> "https://voe.sx/e/"
            "OnlineDoodstream" -> "https://doodstream.com/e/"
            "OnlineNetu" -> "https://www.zarato.top/watch_video.php?v="
            else -> return@mapNotNull null
        }
        val description = a.closest(".pane")?.selectFirst(".pane_descripcion")?.text().orEmpty().lowercase()
        val language = when {
            "latino" in description -> "Latino"
            "castellano" in description -> "Castellano"
            "subtitul" in description -> "Subtitulado"
            else -> "Idioma no indicado"
        }
        Player(host + id, language)
    }.distinctBy { it.url to it.language }
    fun title(doc: Document): String? = doc.selectFirst(".single_left h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
    fun poster(doc: Document): String? = doc.selectFirst(".single_left table img")?.absUrl("src")?.takeIf { it.startsWith("https://") }
    fun plot(doc: Document): String? = doc.selectFirst(".single_left table td:nth-child(2) p")?.text()?.trim()?.takeIf { it.isNotBlank() }
}
