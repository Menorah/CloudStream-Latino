package com.menorah.animeonline

import org.jsoup.nodes.Element
import java.net.URI

internal data class CatalogCard(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class PlayerServer(val url: String, val language: String)
internal data class EpisodeData(val url: String, val title: String, val season: Int, val number: Int, val poster: String?)

/** Pure parsing helpers. Documents must have the response URL as their base URI. */
internal object SiteMarkup {
    fun image(element: Element?): String? = element?.let { img ->
        listOf("data-src", "data-lazy-src", "src").asSequence().map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { value -> runCatching { URI(img.baseUri()).resolve(value).toString() }.getOrNull() }
    }

    /** Prefer the actual detail header, not the site's generic header h1. */
    fun detailTitle(document: Element): String? =
        document.selectFirst("#single .sheader .data h1, .sheader .data h1, .sheader h1")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun artworkUrl(base: String, value: String): String? {
        val cleaned = value.trim().takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(base).resolve(cleaned) }.getOrNull() ?: return null
        return uri.toString().takeIf {
            uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }

    /** DooPlay places the actual backdrop in the #dt_contenedor CSS rule. */
    fun backdrop(document: Element): String? {
        val rule = Regex("(?is)#dt_contenedor\\s*\\{([^}]*)\\}")
        val backgroundUrl = Regex("""(?is)background(?:-image)?\s*:[^;}]*?url\(\s*(['"]?)(.*?)\1\s*\)""")
        val inline = document.selectFirst("#dt_contenedor[style]")?.attr("style").orEmpty()
        val declarations = sequenceOf(inline) + document.select("style").asSequence().flatMap { style ->
            rule.findAll(style.data()).map { it.groupValues[1] }
        }
        val background = declarations.mapNotNull { css ->
            backgroundUrl.findAll(css).mapNotNull { match -> artworkUrl(document.baseUri(), match.groupValues[2]) }.firstOrNull()
        }.firstOrNull()
        if (background != null) return background
        // If the layout has no backdrop, the gallery is still specific to this title.
        return document.select("#dt_galery .g-item a[href]").asSequence()
            .mapNotNull { artworkUrl(it.baseUri(), it.attr("href")) }.firstOrNull()
    }

    /** WordPress thumbnails often end in -185x278; use only after it actually downloads. */
    fun originalPoster(thumbnail: String?): String? {
        val url = thumbnail?.takeIf(PosterCache::isProtectedPoster) ?: return null
        val original = url.replace(Regex("-\\d+x\\d+(?=\\.[A-Za-z0-9]+(?:[?#]|$))"), "")
        return original.takeIf { it != url }
    }

    fun displayedPoster(thumbnail: String?, original: String?, cached: Map<String, String>): String? =
        cached[original] ?: cached[thumbnail] ?: thumbnail

    fun cards(document: Element): List<CatalogCard> {
        // Archive pages also contain a large, randomly ordered featured carousel.
        // It repeats on every page and must not be mistaken for paged results.
        val content = document.selectFirst("#archive-content") ?: document
        return content.select("article.item, .result-item").mapNotNull { card ->
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
    }

    /** Only follow a next-page link belonging to the requested section. */
    fun hasNextPage(document: Element, page: Int, section: String): Boolean {
        val expected = section.trimEnd('/') + "/page/${page + 1}/"
        return document.select(".pagination a[href], .resppages a[href], a.next.page-numbers[href], link[rel=next][href]").any { link ->
            val uri = runCatching { URI(link.absUrl("href")) }.getOrNull()
            isSiteUrl(uri?.toString().orEmpty()) && uri?.path.orEmpty().trimEnd('/') == expected.trimEnd('/')
        }
    }

    fun episodes(document: Element): List<EpisodeData> =
        document.select("#seasons ul.episodios li").mapIndexedNotNull { index, item ->
            val link = item.selectFirst(".episodiotitle a[href]") ?: return@mapIndexedNotNull null
            val url = link.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            val numbers = Regex("(\\d+)\\s*[-x]\\s*(\\d+)").find(item.selectFirst(".numerando")?.text().orEmpty())
            EpisodeData(url, link.text().trim().ifBlank { "Episodio ${index + 1}" },
                numbers?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                numbers?.groupValues?.get(2)?.toIntOrNull() ?: index + 1, image(item.selectFirst("img")))
        }

    /** The current multiserver renders links in go_to_player() grouped by language. */
    fun playerServers(document: Element): List<PlayerServer> {
        val grouped = listOf("LAT" to "Latino", "ES" to "Castellano", "SUB" to "Audio original · Sub español").map { (code, label) ->
            document.select(".OD_$code li[onclick]").mapNotNull { item ->
                val match = Regex("""go_to_player\s*\(\s*(['"])(.*?)\1\s*\)""").find(item.attr("onclick")) ?: return@mapNotNull null
                val url = runCatching { URI(item.baseUri()).resolve(match.groupValues[2]).toString() }.getOrNull()
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
                PlayerServer(url, label)
            }
        }
        // Interleave languages so one group of dead hosts does not starve the others.
        val ordered = (0 until (grouped.maxOfOrNull { it.size } ?: 0)).flatMap { i ->
            grouped.mapNotNull { it.getOrNull(i) }
        }
        // CloudStream deduplicates final links by URL. A server listed under more
        // than one language must retain every label, rather than losing one.
        return ordered.groupBy { it.url }.map { (url, matches) ->
            PlayerServer(url, matches.map { it.language }.distinct().joinToString(" / "))
        }
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
