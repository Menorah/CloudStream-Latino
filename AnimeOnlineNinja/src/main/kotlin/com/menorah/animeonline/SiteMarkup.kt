package com.menorah.animeonline

import org.jsoup.nodes.Element
import java.net.URI

internal data class CatalogCard(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class EpisodeData(val url: String, val title: String, val season: Int, val number: Int, val poster: String?)

/** Pure parsing helpers. Documents must have the response URL as their base URI. */
internal object SiteMarkup {
    fun image(element: Element?): String? = element?.let { img ->
        listOf("data-src", "data-lazy-src", "src").asSequence().map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { value -> runCatching { URI(img.baseUri()).resolve(value).toString() }.getOrNull() }
    }

    fun cards(document: Element): List<CatalogCard> =
        document.select("article.item, .result-item").mapNotNull { card ->
            val link = card.selectFirst(".data h3 a[href], .details .title a[href], h3.title a[href], .image a[href], .poster a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            val movie = when {
                path.startsWith("/pelicula/") -> true
                path.startsWith("/online/") -> false
                else -> return@mapNotNull null
            }
            val title = link.text().trim().ifBlank { card.selectFirst("img[alt]")?.attr("alt").orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            CatalogCard(title, url, image(card.selectFirst("img")), movie)
        }.distinctBy { it.url }

    fun episodes(document: Element): List<EpisodeData> =
        document.select("#seasons ul.episodios li").mapIndexedNotNull { index, item ->
            val link = item.selectFirst(".episodiotitle a[href]") ?: return@mapIndexedNotNull null
            val url = link.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            val numbers = Regex("(\\d+)\\s*[-x]\\s*(\\d+)").find(item.selectFirst(".numerando")?.text().orEmpty())
            EpisodeData(url, link.text().trim().ifBlank { "Episodio ${index + 1}" },
                numbers?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                numbers?.groupValues?.get(2)?.toIntOrNull() ?: index + 1, image(item.selectFirst("img")))
        }

    /** The site's root is an unrelated landing page; only follow its explicit entry. */
    fun landingEntry(document: Element, finalUrl: String): String? {
        val uri = runCatching { URI(finalUrl) }.getOrNull() ?: return null
        if (uri.host?.lowercase() != "ver2.animeonline.ninja" || uri.path !in listOf("", "/")) return null
        return document.select("a[href]").asSequence().map { it.absUrl("href") }
            .firstOrNull { link ->
                val target = runCatching { URI(link) }.getOrNull()
                target?.scheme == "https" && target.host == "ww4.animeonline.ninja" && target.path.orEmpty().trimEnd('/') == "/inicio"
            }
    }
}
