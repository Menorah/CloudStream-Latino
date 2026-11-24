package com.menorah.sololatino

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URI

internal data class SoloDetail(val title: String, val poster: String?, val background: String?, val plot: String?, val year: Int?, val genres: List<String>)

internal object SoloLatinoDetails {
    private val mapper = ObjectMapper()
    private fun parse(text: String): JsonNode? = if (text.length > 2_000_000) null else runCatching { mapper.readTree(text) }.getOrNull()
    private fun image(input: String): String? {
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        return input.takeIf { uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in listOf(-1, 443) }
    }
    private fun titlePage(url: String): Boolean {
        if (!SoloLatinoMarkup.siteUrl(url)) return false
        return Regex("^/(?:pelicula|serie)/[^/]+/?$").matches(URI(url).path.orEmpty())
    }
    fun suggestions(body: String): List<Card> {
        val list = parse(body) ?: return emptyList()
        if (!list.isArray || list.size() > 500) return emptyList()
        return list.mapNotNull { item ->
            val url = item.path("url").asText("").takeIf(::titlePage) ?: return@mapNotNull null
            val title = item.path("title").asText("").trim().takeIf { it.isNotEmpty() && it.length <= 300 } ?: return@mapNotNull null
            val type = when {
                URI(url).path.startsWith("/pelicula/") -> "movie"
                item.path("type").asText("").equals("anime", true) -> "anime"
                else -> "series"
            }
            val year = item.path("year").asInt(0).takeIf { it in 1800..2100 }
            Card(title, url, image(item.path("poster").asText("")), year, type)
        }.distinctBy { it.url }
    }
    fun detail(doc: Document): SoloDetail? {
        if (!titlePage(doc.location())) return null
        val type = if (URI(doc.location()).path.startsWith("/pelicula/")) "Movie" else "TVSeries"
        val info = doc.select("script[type=application/ld+json]").asSequence().flatMap { element ->
            val root = parse(element.data()) ?: return@flatMap emptySequence()
            val graph = root.path("@graph")
            if (graph.isArray) graph.elements().asSequence() else sequenceOf(root)
        }.firstOrNull { it.path("@type").asText() == type }
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.path("name")?.asText("")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val year = info?.path("datePublished")?.asText("")?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2100 }
        val genres = info?.path("genre")?.takeIf { it.isArray }?.mapNotNull { it.asText("").trim().takeIf(String::isNotBlank) }?.distinct() ?: emptyList()
        val plot = doc.selectFirst("p.text-sm.leading-relaxed")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.path("description")?.asText("")?.trim()?.takeIf { it.isNotEmpty() }
        return SoloDetail(title, doc.selectFirst("img.w-44")?.absUrl("src")?.let(::image),
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.let(::image), plot, year, genres)
    }
}
