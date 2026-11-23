package com.menorah.repelishd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URI

internal data class Card(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class EpisodeItem(val title: String, val season: Int, val number: Int, val poster: String?, val description: String?)
internal data class Source(val url: String, val language: String)
internal data class Token(val type: String, val id: String, val token: String)

internal object RePelisHDMarkup {
    const val BASE = "https://repelishd.surf"
    const val PLAYER = "https://playlatinsrc.co"
    private val mapper = ObjectMapper()
    private fun host(url: String, name: String): Boolean = runCatching {
        URI(url).let { it.scheme == "https" && it.host.equals(name, true) && it.port in listOf(-1, 443) && it.userInfo == null }
    }.getOrDefault(false)
    fun siteUrl(url: String) = host(url, "repelishd.surf")
    fun playerUrl(url: String) = host(url, "playlatinsrc.co")
    private fun mediaUrl(url: String): Boolean = listOf("dr0pstream.com", "superplayer.ws", "doodstream.com", "mxdrop.to").any { host(url, it) }
    fun cards(doc: Document): List<Card> = doc.select("a.cv[href]").mapNotNull { link ->
        val url = link.absUrl("href").takeIf(::siteUrl) ?: return@mapNotNull null
        val path = URI(url).path.orEmpty()
        val movie = path.startsWith("/ver-pelicula/")
        if (!movie && !path.startsWith("/ver-serie/")) return@mapNotNull null
        val title = link.selectFirst(".cv-t")?.text().orEmpty().ifBlank { link.attr("title").trim() }
        if (title.isBlank()) return@mapNotNull null
        Card(title, url, link.selectFirst("img")?.absUrl("src")?.takeIf { it.startsWith("https://") }, movie)
    }.distinctBy { it.url }
    fun poster(doc: Document): String? = doc.selectFirst(".chh img")?.absUrl("src")?.takeIf { it.startsWith("https://") }
    fun plot(doc: Document): String? = doc.selectFirst(".chh .sin-t")?.text()?.trim()?.takeIf { it.isNotBlank() }
    fun year(doc: Document): Int? = doc.select(".chh-badge li").firstNotNullOfOrNull { it.text().trim().takeIf { v -> v.matches(Regex("[0-9]{4}")) }?.toIntOrNull() }
    fun genres(doc: Document): List<String> = doc.select(".chh-gen a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
    fun hasNext(doc: Document, page: Int): Boolean = doc.select("a[href]").any { a ->
        val url = a.absUrl("href"); siteUrl(url) && URI(url).rawQuery.orEmpty().split('&').contains("page=${page + 1}")
    }
    fun initial(doc: Document): JsonNode? {
        val combined = doc.select("script").mapNotNull { script ->
            val raw = script.data(); val prefix = "self.__next_f.push("
            if (!raw.startsWith(prefix)) return@mapNotNull null
            val end = raw.lastIndexOf(')'); if (end <= prefix.length) return@mapNotNull null
            val node = runCatching { mapper.readTree(raw.substring(prefix.length, end)) }.getOrNull()
            if (node?.path(0)?.asInt() == 1) node.path(1).asText() else null
        }.joinToString("")
        val match = Regex("\"iniziale\"\\s*:\\s*\\{").find(combined) ?: return null
        return runCatching { mapper.readTree(combined.substring(match.range.last)) }.getOrNull()
    }
    fun seasons(state: JsonNode): List<Pair<Int, Int>> = state.path("stagioni").mapNotNull { n ->
        val season = n.path("season_number").asInt(-1); val count = n.path("disponibili").asInt(0)
        if (season >= 0 && count > 0) season to count else null
    }.distinctBy { it.first }
    fun episodes(state: JsonNode): List<EpisodeItem> = state.path("episodi").mapNotNull { n ->
        val season = n.path("season_number").asInt(-1); val number = n.path("episode_number").asInt(-1)
        if (season < 0 || number <= 0 || n.path("stream_available").asInt(0) != 1) return@mapNotNull null
        EpisodeItem(n.path("name").asText("").ifBlank { "Episodio $number" }, season, number,
            n.path("foto").asText("").takeIf { it.startsWith("https://") }, n.path("overview").asText("").takeIf { it.isNotBlank() })
    }.distinctBy { it.season to it.number }
    fun imdb(doc: Document): String? {
        val fromInitial = initial(doc)?.path("imdb")?.asText("")
        if (fromInitial != null && fromInitial.matches(Regex("tt[0-9]+"))) return fromInitial
        return doc.select("script[type=application/ld+json]").asSequence().mapNotNull { e ->
            val json = runCatching { mapper.readTree(e.data()) }.getOrNull() ?: return@mapNotNull null
            val url = json.path("embedUrl").asText("")
            if (!playerUrl(url)) null else Regex("/(tt[0-9]+)(?:[/?#]|$)").find(url)?.groupValues?.get(1)
        }.firstOrNull()
    }
    fun token(html: String): Token? {
        val m = Regex("""JSON\.stringify\(\{\s*type:\s*['\"](movie|tv)['\"],\s*id:\s*['\"](tt[0-9]+)['\"],\s*token:\s*['\"]([A-Za-z0-9._-]+)['\"]""").find(html) ?: return null
        return Token(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }
    fun sources(json: JsonNode, season: Int?, episode: Int?): List<Source> {
        val tv = json.path("tv")
        val selected = if (season != null && episode != null) tv.path("seasons").firstOrNull { it.path("season_number").asInt(-1) == season }
            ?.path("episodes")?.firstOrNull { it.path("episode_number").asInt(-1) == episode }?.path("sources") else json.path("sources")
        val tvLang = tv.path("version").asText("")
        return selected?.mapNotNull { n ->
            val url = n.path("url").asText(""); if (!mediaUrl(url)) return@mapNotNull null
            val version = n.path("version").asText("").ifBlank { tvLang }.lowercase()
            val language = when (version) { "latino" -> "Latino"; "castellano" -> "Castellano"; "subtitulado" -> "Subtitulado"; else -> "Idioma no indicado" }
            Source(url, language)
        }?.distinctBy { it.url to it.language }.orEmpty()
    }
    fun parseJson(text: String): JsonNode = mapper.readTree(text)
}
