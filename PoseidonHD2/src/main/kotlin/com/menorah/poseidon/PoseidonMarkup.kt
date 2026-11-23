package com.menorah.poseidon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

internal data class Card(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class EpisodeItem(val name: String, val season: Int, val number: Int, val poster: String?)
internal data class Player(val url: String, val language: String)

internal object PoseidonMarkup {
    private val mapper = ObjectMapper()
    fun siteUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("www.poseidonhd2.co", true) && uri.port in listOf(-1,443) && uri.userInfo == null
    }
    fun wrapperUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https",true) && uri.host.equals("player.poseidonhd2.co",true) && uri.port in listOf(-1,443) && uri.userInfo == null && uri.path == "/player.php"
    }
    private fun image(src: String, base: String): String? {
        var value = src.trim()
        if (value.startsWith("/_next/image?")) {
            val raw = value.substringAfter('?').split('&').firstOrNull { it.startsWith("url=") }?.substringAfter('=') ?: return null
            value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        }
        val url = runCatching { URI(base).resolve(value).toString() }.getOrNull() ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && !value.startsWith("data:") }
    }
    fun cards(doc: Document): List<Card> = doc.select("a[href]").mapNotNull { link ->
        val url = link.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
        val path = URI(url).path.orEmpty()
        val movie = path.startsWith("/pelicula/")
        if ((!movie && !path.startsWith("/serie/")) || path.contains("/episodio/")) return@mapNotNull null
        val img = link.selectFirst("img") ?: return@mapNotNull null
        val title = link.selectFirst(".Title")?.text()?.trim().orEmpty().ifBlank { img.attr("alt").trim() }
        if (title.isBlank()) return@mapNotNull null
        Card(title, url, image(img.attr("src"), doc.location()), movie)
    }.distinctBy { it.url }
    fun hasNext(doc: Document, page: Int): Boolean = doc.select("a[href]").any { a ->
        val url = a.absUrl("href"); siteUrl(url) && URI(url).path.orEmpty().trimEnd('/').endsWith("/page/${page + 1}")
    }
    fun state(doc: Document): JsonNode? = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let {
        runCatching { mapper.readTree(it).path("props").path("pageProps") }.getOrNull()
    }
    fun media(state: JsonNode, movie: Boolean): JsonNode = state.path(if (movie) "thisMovie" else "thisSerie")
    fun episodes(media: JsonNode): List<EpisodeItem> = media.path("seasons").flatMap { season ->
        val s = season.path("number").asInt(-1)
        if (s < 0) emptyList() else season.path("episodes").mapNotNull { e ->
            val n = e.path("number").asInt(-1)
            if (n < 1) return@mapNotNull null
            EpisodeItem(e.path("title").asText("").ifBlank { "Episodio $n" }, s, n, e.path("image").asText("").takeIf { it.startsWith("https://") })
        }
    }.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
    fun missingSeasons(media: JsonNode): List<Int> = media.path("seasons").mapNotNull { s ->
        val n = s.path("number").asInt(-1)
        n.takeIf { it >= 0 && s.path("episodes").isArray && s.path("episodes").isEmpty }
    }.distinct().take(30)
    fun players(state: JsonNode): List<Player> {
        val source = if (!state.path("thisMovie").isMissingNode) state.path("thisMovie") else state.path("episode")
        val videos = source.path("videos")
        return listOf("latino" to "Latino", "spanish" to "Castellano", "english" to "Subtitulado").flatMap { (key,label) ->
            videos.path(key).mapNotNull { x ->
                val url = x.path("result").asText("")
                if (wrapperUrl(url)) Player(url, label) else null
            }
        }.distinctBy { it.url to it.language }
    }
    /** The player wrapper's own variable holds the selected host; ignore all ad scripts. */
    fun destination(html: String): String? {
        val url = Regex("""(?:var|let|const)\s+url\s*=\s*['\"](https?://[^'\"]+)['\"]""").find(html)?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }
}
