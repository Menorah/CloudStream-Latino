package com.menorah.verani

import org.jsoup.nodes.Element
import java.net.URI

data class VerAniCard(val title: String, val url: String, val poster: String?, val movie: Boolean)
data class VerAniEpisode(
    val title: String, val url: String, val poster: String?, val season: Int, val number: Int,
    val seasonLabel: String? = null,
)
data class VerAniServer(val url: String, val language: String)
internal data class RankingPage(val cards: List<VerAniCard>, val hasNext: Boolean)

internal object VerAniMarkup {
    private fun validUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
    private fun absolute(base: String, value: String): String? =
        value.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { URI(base).resolve(raw).toString() }.getOrNull()?.takeIf(::validUrl)
        }

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

    /** Each top panel contains its complete ranking, in site order. */
    fun ranking(doc: Element, key: String, movie: Boolean): List<VerAniCard> {
        if (!key.matches(Regex("[a-z_]+"))) return emptyList()
        val panel = doc.selectFirst("#tops-panel-body-$key") ?: return emptyList()
        return panel.select("a.tops-card[href]").mapNotNull { link ->
            val url = absolute(link.baseUri(), link.attr("href"))?.takeIf(::isSiteUrl) ?: return@mapNotNull null
            val title = link.selectFirst(".tops-card__title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            VerAniCard(title, url, image(link.selectFirst("img")), movie)
        }.distinctBy { it.url }
    }

    fun rankingPage(entries: List<VerAniCard>, page: Int): RankingPage {
        val start = (page.toLong() - 1).coerceAtLeast(0) * 30
        if (start >= entries.size) return RankingPage(emptyList(), false)
        val from = start.toInt()
        val end = minOf(from + 30, entries.size)
        return RankingPage(entries.subList(from, end), end < entries.size)
    }

    fun hasNext(doc: Element): Boolean = doc.select(".pagination-wrapper a.next[href], .pagination-nav a.next[href]")
        .any { absolute(it.baseUri(), it.attr("href"))?.let(::isSiteUrl) == true }

    private data class EpisodeCandidate(val url: String, val title: String, val poster: String?, val number: Int?)

    /** The page groups cards by season; URLs may be named, numeric, or capitulo-prefixed. */
    fun episodes(doc: Element): List<VerAniEpisode> {
        val chapterNumber = Regex("(?i)^(?:cap[ií]tulo|episodio|ep\\.?|chapter)\\s*#?\\s*(\\d+)\\b")
        val slugNumber = Regex("(?i)^(?:(?:capitulo|episodio)-)?(\\d+)(?:-|$)")
        val seasonNumber = Regex("\\d+")
        val episodes = mutableListOf<VerAniEpisode>()
        for (group in doc.select(".temporada-group")) {
            val badge = group.selectFirst(".temporada-badge")?.text().orEmpty().trim()
            val seasonName = group.selectFirst(".temporada-name")?.text().orEmpty().trim()
            val season = seasonNumber.find(badge)?.value?.toIntOrNull()
                ?: Regex("(?i)temporada\\s*(\\d+)").find(seasonName)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (badge.equals("O", true) || Regex("(?i)\\b(?:ovas?|oads?|especiales?)\\b").containsMatchIn(seasonName)) 0 else 1
            val customLabel = seasonName.replace(Regex("(?i)^temporada\\s*\\d+\\s*[:–-]?\\s*"), "")
                .trim().takeIf { it.isNotEmpty() }
            val candidates = group.select("a.capitulo-card-link[href]").mapNotNull { link ->
                if (link.closest(".pelicula-card") != null) return@mapNotNull null
                val url = absolute(link.baseUri(), link.attr("href"))?.takeIf(::isSiteUrl) ?: return@mapNotNull null
                val path = URI(url).path.orEmpty()
                if (path.split('/').any { it.equals("pelicula", true) }) return@mapNotNull null
                val heading = link.selectFirst(".capitulo-card-title")?.text()?.trim().orEmpty()
                val subtitle = link.selectFirst(".capitulo-card-subtitle")?.text()?.trim().orEmpty()
                val number = chapterNumber.find(heading)?.groupValues?.get(1)?.toIntOrNull()
                    ?: slugNumber.find(path.trimEnd('/').substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
                val title = subtitle.ifBlank { heading }.ifBlank { number?.let { "Episodio $it" }.orEmpty() }
                if (title.isBlank()) return@mapNotNull null
                EpisodeCandidate(url, title, image(link.selectFirst("img")), number)
            }.distinctBy { it.url }

            // Unnumbered specials are presented newest-first by VerAni. Number
            // them in reverse card order without using incidental numbers in titles.
            val used = candidates.mapNotNull { it.number }.toMutableSet()
            val assigned = mutableMapOf<String, Int>()
            for ((index, candidate) in candidates.withIndex().toList().asReversed()) {
                if (candidate.number != null) continue
                var number = candidates.size - index
                while (number in used) number++
                assigned[candidate.url] = number
                used.add(number)
            }
            episodes += candidates.map { candidate ->
                VerAniEpisode(candidate.title, candidate.url, candidate.poster, season,
                    candidate.number ?: assigned.getValue(candidate.url), customLabel)
            }
        }
        return episodes.distinctBy { it.season to it.url }
            .sortedWith(compareBy<VerAniEpisode> { it.season }.thenBy { it.number })
    }

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

    private fun imageKey(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase()
            .replace(Regex("-\\d+x\\d+(?=\\.[^.]+$)"), "")
            .replace(Regex("-e\\d+(?=\\.[^.]+$)"), "")
        return "${uri.host?.lowercase()}$path"
    }

    private fun isSiteLogo(doc: Element, candidate: String): Boolean {
        val key = imageKey(candidate) ?: return false
        return doc.select(".site-logo img, img.site-logo-img, .custom-logo").mapNotNull(::image)
            .any { imageKey(it) == key }
    }

    fun backdrop(doc: Element): String? = image(doc.selectFirst(".anime-hero-bg-image, .pelicula-backdrop-image"))
        ?.takeUnless { isSiteLogo(doc, it) }

    fun poster(doc: Element): String? {
        val explicit = image(doc.selectFirst(".pelicula-poster-image, .anime-poster-image"))
            ?.takeUnless { isSiteLogo(doc, it) }
        if (explicit != null) return explicit
        val metadata = doc.select("meta[property=og:image][content], meta[name=twitter:image][content]")
            .asSequence().mapNotNull { absolute(it.baseUri(), it.attr("content")) }
            .firstOrNull { !isSiteLogo(doc, it) }
        return metadata ?: backdrop(doc)
    }
    fun plot(doc: Element): String? = doc.selectFirst(".anime-hero-description, .pelicula-overview-text")?.text()?.trim()?.takeIf { it.isNotEmpty() }
}
