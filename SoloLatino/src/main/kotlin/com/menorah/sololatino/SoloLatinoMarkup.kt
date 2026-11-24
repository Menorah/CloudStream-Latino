package com.menorah.sololatino

import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

internal data class Card(val title: String, val url: String, val poster: String?, val year: Int?, val type: String)
internal data class Chapter(val title: String?, val url: String, val season: Int, val number: Int, val poster: String?)
internal data class ServerOption(val token: String, val language: String, val title: String)

/** Parsing only: this experimental module is not a usable provider or part of the release. */
internal object SoloLatinoMarkup {
    const val BASE = "https://sololatino.net"
    private val titlePath = Regex("^/(?:pelicula|serie)/[^/]+/?$")
    private val episodePath = Regex("^/serie/([^/]+)/temporada-([0-9]+)/episodio-([0-9]+)/?$")

    fun siteUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && u.host.equals("sololatino.net", true) && u.port in listOf(-1, 443) && u.userInfo == null
    }
    private fun image(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { u.scheme.equals("https", true) && !u.host.isNullOrBlank() && u.userInfo == null && u.port in listOf(-1, 443) }
    }
    fun cards(doc: Document): List<Card> = doc.select("div.card").mapNotNull { block ->
        val link = block.selectFirst("a[href]") ?: return@mapNotNull null
        val url = link.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
        val path = URI(url).path.orEmpty()
        if (!titlePath.matches(path)) return@mapNotNull null
        val img = block.selectFirst("img.card__poster")
        val title = block.selectFirst(".card__title")?.text()?.trim().orEmpty().ifBlank { img?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return@mapNotNull null
        val kind = when {
            path.startsWith("/pelicula/") -> "movie"
            block.selectFirst(".badge-anime") != null -> "anime"
            else -> "series"
        }
        Card(title, url, img?.absUrl("src")?.let(::image), block.selectFirst(".card__year")?.text()?.trim()?.toIntOrNull(), kind)
    }.distinctBy { it.url }

    private fun query(uri: URI): Map<String, List<String>> = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.mapNotNull {
        val split = it.split('=', limit = 2)
        runCatching { URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split.getOrElse(1) { "" }, "UTF-8") }.getOrNull()
    }.groupBy({ it.first }, { it.second })

    fun hasNext(doc: Document, page: Int): Boolean {
        val current = runCatching { URI(doc.location()) }.getOrNull() ?: return false
        return doc.select("link[rel~=next][href], a[rel~=next][href]").any {
            val url = it.absUrl("href")
            if (!siteUrl(url)) false else {
                val next = URI(url)
                val q = query(next); val original = query(current)
                next.path.trimEnd('/') == current.path.trimEnd('/') &&
                    q["page"] == listOf((page + 1).toString()) &&
                    q.filterKeys { key -> key != "page" } == original.filterKeys { key -> key != "page" }
            }
        }
    }

    fun episodes(doc: Document): List<Chapter> {
        val series = runCatching { URI(doc.location()) }.getOrNull() ?: return emptyList()
        if (!siteUrl(series.toString()) || !series.path.orEmpty().startsWith("/serie/")) return emptyList()
        val slug = series.path.trimEnd('/').substringAfterLast('/')
        return doc.select("div[data-season-panel] a.ep-item[href]").mapNotNull { a ->
            val url = a.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
            val match = episodePath.matchEntire(URI(url).path.orEmpty()) ?: return@mapNotNull null
            val season = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val number = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val panel = a.closest("[data-season-panel]")?.attr("data-season-panel")?.toIntOrNull()
            if (match.groupValues[1] != slug || panel != season) return@mapNotNull null
            Chapter(a.selectFirst("p.text-sm.font-semibold")?.text()?.trim()?.takeIf { it.isNotEmpty() }, url, season, number, a.selectFirst("img.ep-thumb")?.absUrl("src")?.let(::image))
        }.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    fun servers(doc: Document): List<ServerOption> {
        val buttons = doc.select("[data-player-token]")
        if (buttons.isEmpty()) return emptyList()
        return buttons.mapNotNull { button ->
            val token = button.attr("data-player-token").trim()
                .takeIf { it.length in 1..4096 && it.none(Char::isISOControl) } ?: return@mapNotNull null
            val group = button.closest("[data-lang-group]") ?: button.parent()
            val raw = group?.attr("data-lang-group").orEmpty().ifBlank { group?.attr("data-lang").orEmpty() }
            val language = when (raw.lowercase()) {
                "mx", "latino", "lat", "1" -> "Latino"
                "es", "castellano", "esp", "2" -> "Castellano"
                "sub", "subtitulado", "vose", "3" -> "Subtitulado"
                else -> "Idioma no indicado"
            }
            ServerOption(token, language, button.text().trim())
        }.distinctBy { it.token to it.language }
    }
}
