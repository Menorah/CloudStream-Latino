package com.menorah.repelis24

import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI

internal data class Card(val title: String, val url: String, val image: String?, val movie: Boolean)
internal data class EpisodeItem(val title: String, val id: String, val season: Int, val number: Int, val image: String?)
internal data class PlayerOption(val post: String, val number: String, val type: String, val language: String)

internal object RepelisMarkup {
    private const val BASE = "https://repelis24.cz"
    fun siteUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && u.host.equals("repelis24.cz", true) && u.port in listOf(-1, 443) && u.userInfo == null
    }
    private fun resolve(base: String, raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }
        ?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() }
        ?.takeIf { runCatching { URI(it).scheme in listOf("http", "https") && URI(it).host != null }.getOrDefault(false) }
    private fun image(img: Element?): String? = img?.let {
        listOf("data-src", "data-lazy-src", "src").asSequence().map(it::attr)
            .filter { value -> value.isNotBlank() && !value.startsWith("data:") }
            .mapNotNull { value -> resolve(it.baseUri(), value) }.firstOrNull()
    }
    fun cards(doc: Element): List<Card> = doc.select("a.dpu-archive-card[href]").mapNotNull { a ->
        val url = resolve(a.baseUri(), a.attr("href"))?.takeIf(::siteUrl) ?: return@mapNotNull null
        val path = URI(url).path.orEmpty()
        val movie = path.startsWith("/peliculas/")
        if (!movie && !path.startsWith("/series/")) return@mapNotNull null
        val title = a.selectFirst(".dpu-archive-c-title")?.text()?.trim().orEmpty().ifBlank { a.attr("title").trim() }
        if (title.isBlank()) return@mapNotNull null
        Card(title, url, image(a.selectFirst("img.dpu-archive-img")), movie)
    }.distinctBy { it.url }
    /** Insert the page component before the query string, preserving filters. */
    fun pageUrl(path: String, page: Int): String {
        require(page >= 1)
        val parts = path.split('?', limit = 2)
        val base = parts[0].trimEnd('/')
        val paged = if (page > 1) "$base/page/$page/" else "$base/"
        return paged + if (parts.size == 2) "?${parts[1]}" else ""
    }
    fun hasNext(doc: Element, page: Int): Boolean {
        val current = runCatching { URI(doc.baseUri()) }.getOrNull() ?: return false
        val expectedPath = current.path.orEmpty().replace(Regex("/page/[0-9]+/?$"), "").trimEnd('/') + "/page/${page + 1}"
        return doc.select("a[href]").any { a ->
            val url = resolve(a.baseUri(), a.attr("href"))?.takeIf(::siteUrl) ?: return@any false
            val next = URI(url)
            next.path.orEmpty().trimEnd('/') == expectedPath && next.rawQuery == current.rawQuery
        }
    }
    fun episodes(doc: Element): List<EpisodeItem> = doc.select(".dpu-episode-card[data-episode][data-season-number][data-episode-number]").mapNotNull { e ->
        val id = e.attr("data-episode").takeIf { it.matches(Regex("[0-9]+")) } ?: return@mapNotNull null
        val season = e.attr("data-season-number").toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val number = e.attr("data-episode-number").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val title = e.selectFirst(".dpu-ep-title")?.text()?.trim().orEmpty().ifBlank { "Episodio $number" }
        EpisodeItem(title, id, season, number, image(e.selectFirst("img")))
    }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
    fun episodeUrl(id: String): String { require(id.matches(Regex("[0-9]+"))); return "$BASE/?p=$id" }
    fun options(doc: Element): List<PlayerOption> = doc.select("li.dooplay_player_option[data-post][data-nume][data-type]").mapNotNull { e ->
        val post = e.attr("data-post").takeIf { it.matches(Regex("[0-9]+")) } ?: return@mapNotNull null
        val number = e.attr("data-nume").takeIf { it.matches(Regex("[0-9]+")) } ?: return@mapNotNull null
        val type = e.attr("data-type").takeIf { it in listOf("movie", "tv") } ?: return@mapNotNull null
        val language = when (e.closest("[data-lang]")?.attr("data-lang")?.lowercase()) {
            "mx", "lat", "latino" -> "Latino"
            "es", "cast", "castellano" -> "Castellano"
            "en", "eng" -> "Inglés"
            "sub", "subtitulado" -> "Subtitulado"
            else -> "Idioma no indicado"
        }
        PlayerOption(post, number, type, language)
    }.distinctBy { Triple(it.post, it.number, it.type) }
    fun episodeId(url: String): String? {
        if (!siteUrl(url)) return null
        val uri = URI(url)
        if (uri.path !in listOf("", "/")) return null
        val ids = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("p=") }.map { it.substringAfter('=') }
        return ids.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) }
    }
    fun episodePlayerOptions(json: String, id: String): List<PlayerOption> = runCatching {
        val response = JSONObject(json)
        if (!response.optBoolean("success")) return@runCatching emptyList()
        val html = response.opt("data") as? String ?: return@runCatching emptyList()
        options(Jsoup.parse(html, "$BASE/")).filter { it.post == id && it.type == "tv" }
    }.getOrDefault(emptyList())
    /** Preserve playback without inventing languages if the player menu is unavailable. */
    fun fallbackEpisodeOptions(url: String): List<PlayerOption> {
        val id = episodeId(url) ?: return emptyList()
        return (1..12).map { PlayerOption(id, it.toString(), "tv", "Idioma no indicado") }
    }
    fun title(doc: Element): String? = doc.selectFirst("h1.dpu-hero-title, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    fun poster(doc: Element): String? {
        val url = image(doc.selectFirst(".dpu-hero-poster img, .dpu-poster img, .poster img, img[itemprop=image]"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { resolve(doc.baseUri(), it) }
        return url?.replace("https://image.tmdb.org/t/p/w185/", "https://image.tmdb.org/t/p/w500/")
    }
    fun backdrop(doc: Element): String? = image(doc.selectFirst(".playbox img.cover"))
    fun plot(doc: Element): String? = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
}
