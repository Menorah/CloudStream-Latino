package com.menorah.verani

import org.jsoup.nodes.Element
import java.net.URI

data class VerAniCard(val title: String, val url: String, val poster: String?, val movie: Boolean)
data class VerAniEpisode(val title: String, val url: String, val poster: String?, val season: Int, val number: Int)
data class VerAniServer(val url: String, val language: String)

internal object VerAniMarkup {
    private fun validUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
    private fun absolute(base: String, value: String): String? =
        runCatching { URI(base).resolve(value.trim()).toString() }.getOrNull()?.takeIf(::validUrl)

    fun image(element: Element?): String? = element?.let { img ->
        listOf("data-src", "data-lazy-src", "src").asSequence().map { img.attr(it) }
            .filter { it.isNotBlank() && !it.startsWith("data:") }
            .mapNotNull { absolute(img.baseUri(), it) }.firstOrNull()
    }

    fun cards(doc: Element): List<VerAniCard> = doc.select(".content-card").mapNotNull { card ->
        val link = card.selectFirst("a.card-link[href]") ?: return@mapNotNull null
        val url = absolute(link.baseUri(), link.attr("href"))?.takeIf(::isSiteUrl) ?: return@mapNotNull null
        val title = card.selectFirst(".card-title")?.text()?.trim().orEmpty().ifBlank { card.selectFirst("img[alt]")?.attr("alt").orEmpty() }
        if (title.isBlank()) return@mapNotNull null
        val movie = URI(url).path.orEmpty().contains("/pelicula/") || card.hasClass("pelicula-card")
        VerAniCard(title, url, image(card.selectFirst("img")), movie)
    }.distinctBy { it.url }

    fun hasNext(doc: Element): Boolean = doc.select(".pagination-wrapper a.next[href], .pagination-nav a.next[href]")
        .any { absolute(it.baseUri(), it.attr("href"))?.let(::isSiteUrl) == true }

    fun episodes(doc: Element): List<VerAniEpisode> = doc.select(".temporada-group a.capitulo-card-link[href]").mapNotNull { link ->
        val url = absolute(link.baseUri(), link.attr("href"))?.takeIf(::isSiteUrl) ?: return@mapNotNull null
        if (!URI(url).path.orEmpty().contains("/capitulo-")) return@mapNotNull null
        val group = link.closest(".temporada-group")
        val seasonText = group?.selectFirst(".temporada-name, .temporada-badge")?.text().orEmpty()
        val season = Regex("\\d+").find(seasonText)?.value?.toIntOrNull() ?: 1
        val chapter = link.selectFirst(".capitulo-card-title")?.text().orEmpty()
        val number = Regex("\\d+").find(chapter)?.value?.toIntOrNull()
            ?: Regex("/capitulo-(\\d+)").find(URI(url).path.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: return@mapNotNull null
        val title = link.selectFirst(".capitulo-card-subtitle")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: chapter.ifBlank { "Episodio $number" }
        VerAniEpisode(title, url, image(link.selectFirst("img")), season, number)
    }.distinctBy { it.url }.sortedWith(compareBy<VerAniEpisode> { it.season }.thenBy { it.number })

    fun servers(doc: Element): List<VerAniServer> {
        val raw = doc.select(".servidor-tab[data-url]").mapNotNull { tab ->
            val url = absolute(tab.baseUri(), tab.attr("data-url")) ?: return@mapNotNull null
            val audio = tab.attr("data-idioma").trim()
            val subtitles = tab.attr("data-subtitulos").trim()
            val label = listOf(audio, subtitles.takeIf { it.isNotBlank() }?.let { "Sub $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Idioma no especificado" }
            VerAniServer(url, label)
        }
        // CloudStream deduplicates by URL, so retain every language for shared URLs.
        return raw.groupBy { it.url }.map { (url, choices) -> VerAniServer(url, choices.map { it.language }.distinct().joinToString(" / ")) }
    }

    fun poster(doc: Element): String? = doc.selectFirst("meta[property=og:image][content]")
        ?.let { absolute(it.baseUri(), it.attr("content")) }
    fun plot(doc: Element): String? = doc.selectFirst(".anime-hero-description, .pelicula-overview-text")?.text()?.trim()?.takeIf { it.isNotEmpty() }
}
