package com.menorah.cinecalidadmybis

import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

data class CinecalidadMYCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val is4k: Boolean = false,
)

data class CinecalidadMYDetail(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val plot: String?,
    val year: Int?,
    val rating10: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val trailerUrl: String?,
    val recommendations: List<CinecalidadMYCard>,
)

data class CinecalidadMYOnlineOption(
    val service: String,
    val label: String,
    val data: String,
    val embedUrls: List<String>,
)

object CinecalidadMYBisMarkup {
    const val DEFAULT_MAIN_URL = "https://www.cinecalidad.my"

    private val TITLE_YEAR_REGEX = Regex("""\((\d{4})\)\s*$""")
    private val IMDB_RATING_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*/\s*10""")
    private val PAGE_NUM_REGEX = Regex("""/page/(\d+)/?""")

    fun resolveUrl(baseUrl: String, href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val base = URI(baseUrl.trimEnd('/') + "/")
            val resolved = if (raw.startsWith("//")) {
                URI("https:$raw")
            } else {
                base.resolve(raw)
            }
            val s = resolved.toString()
            if (s.startsWith("http://") || s.startsWith("https://")) s else null
        }.getOrNull()
    }

    fun splitTitleAndYear(rawTitle: String): Pair<String, Int?> {
        val cleaned = rawTitle.replace(Regex("""\s+"""), " ").trim()
        val match = TITLE_YEAR_REGEX.find(cleaned)
        if (match != null) {
            val year = match.groupValues[1].toIntOrNull()
            val title = cleaned.removeRange(match.range).trim()
            if (title.isNotEmpty()) return title to year
        }
        return cleaned to null
    }

    fun normalizePosterUrl(baseUrl: String, rawPoster: String?): String? {
        val abs = resolveUrl(baseUrl, rawPoster) ?: return null
        return abs
    }

    fun buildArchivePageUrl(sectionUrl: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val cleanBase = resolveUrl(mainUrl, sectionUrl) ?: mainUrl
        if (page <= 1) return cleanBase
        val trimmed = cleanBase.trimEnd('/')
        return "$trimmed/page/$page/"
    }

    fun buildSearchUrl(query: String, page: Int = 1, mainUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = mainUrl.trimEnd('/')
        return if (page <= 1) "$root/?s=$encoded" else "$root/page/$page/?s=$encoded"
    }

    fun parseCards(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<CinecalidadMYCard> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<CinecalidadMYCard>()
        val cards = doc.select("#content_inside > div.home_post_cont, div.home_post_cont")
        for (card in cards) {
            val link = card.selectFirst("a[href]") ?: continue
            val url = resolveUrl(mainUrl, link.attr("href")) ?: continue
            if (!url.contains("/pelicula/") || !seen.add(url)) continue
            val img = card.selectFirst("img")
            val rawTitle = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: img?.attr("title")?.takeIf { it.isNotBlank() }
                ?: card.selectFirst(".in_title")?.text()
                ?: continue
            val (title, year) = splitTitleAndYear(rawTitle)
            if (title.isBlank()) continue
            val rawSrc = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
            val poster = normalizePosterUrl(mainUrl, rawSrc)
            val is4k = url.contains("-4k-", ignoreCase = true) ||
                (rawSrc?.contains("-4k-", ignoreCase = true) == true)
            out += CinecalidadMYCard(
                title = title,
                url = url,
                posterUrl = poster,
                year = year,
                is4k = is4k,
            )
        }
        return out
    }

    fun parseHasNext(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("link[rel=next], a.nextpostslink, a.next.page-numbers") != null) {
            return true
        }
        val nextPage = currentPage + 1
        return doc.select("a[href*=/page/]").any { el ->
            val num = PAGE_NUM_REGEX.find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            num != null && num >= nextPage
        }
    }

    fun parseMovieDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): CinecalidadMYDetail? {
        val root = doc.selectFirst("div.single_left") ?: return null
        val h1 = root.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val (title, year) = splitTitleAndYear(h1)

        val img = root.selectFirst("table img")
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        val poster = normalizePosterUrl(mainUrl, rawPoster)

        val infoTd = root.selectFirst("table tr td:eq(1)") ?: root.selectFirst("table td:nth-of-type(2)")
        val plotParagraphs = infoTd?.select("> p")
            ?.map { it.text().replace("\u00A0", " ").trim() }
            ?.filter { text ->
                text.length > 20 &&
                    !text.startsWith("Títulos:", ignoreCase = true) &&
                    !text.startsWith("Género:", ignoreCase = true)
            }
            .orEmpty()
        val plot = plotParagraphs.firstOrNull()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val ratingText = root.selectFirst("#imdb-box a, #imdb-box")?.text().orEmpty()
        val rating10 = IMDB_RATING_REGEX.find(ratingText)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        val genres = root.select("a[href*=/genero-peliculas/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val actors = mutableListOf<String>()
        for (span in root.select("span")) {
            val strongText = span.selectFirst("strong")?.text()?.trim().orEmpty()
            if (strongText.startsWith("Elenco", ignoreCase = true) ||
                strongText.startsWith("Director", ignoreCase = true)
            ) {
                val rawNames = span.text()
                    .removePrefix(strongText)
                    .trim()
                    .trimStart(':')
                rawNames.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { if (it !in actors) actors += it }
            }
        }

        val trailerId = root.selectFirst("a.onlinelink[service=Trailer][data]")?.attr("data")?.trim()
        val trailerUrl = trailerId?.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("http")) it else "https://www.youtube.com/watch?v=$it"
        }

        val recommendations = doc.select("ul.related-posts li").mapNotNull { li ->
            val a = li.selectFirst("a[href*=/pelicula/]") ?: return@mapNotNull null
            val recUrl = resolveUrl(mainUrl, a.attr("href")) ?: return@mapNotNull null
            val recTitleRaw = a.text().replace(Regex("""\s+online\s*$""", RegexOption.IGNORE_CASE), "").trim()
            val (recTitle, recYear) = splitTitleAndYear(recTitleRaw)
            val recPosterRaw = li.selectFirst("img")?.attr("src")?.replace("-40x55", "-220x320")
            CinecalidadMYCard(
                title = recTitle,
                url = recUrl,
                posterUrl = normalizePosterUrl(mainUrl, recPosterRaw),
                year = recYear,
            )
        }

        return CinecalidadMYDetail(
            title = title,
            url = resolveUrl(mainUrl, url) ?: url,
            posterUrl = poster,
            plot = plot,
            year = year,
            rating10 = rating10,
            genres = genres,
            actors = actors,
            trailerUrl = trailerUrl,
            recommendations = recommendations,
        )
    }

    fun mapServiceToEmbedUrls(service: String, data: String): List<String> {
        val svc = service.trim()
        val value = data.trim()
        if (value.isEmpty() || svc.equals("Trailer", ignoreCase = true)) return emptyList()
        if (value.startsWith("http://") || value.startsWith("https://")) return listOf(value)
        return when (svc) {
            "OnlineFilemoon" -> listOf(
                "https://bysedikamoum.com/e/$value",
                "https://filemoon.sx/e/$value",
            )
            "OnlineVoe" -> listOf("https://voe.sx/e/$value")
            "OnlineDoodstream" -> listOf("https://doodstream.com/e/$value")
            "OnlineMega" -> listOf("https://mega.nz/embed/$value")
            "OnlineNetu" -> listOf("https://www.zarato.top/watch_video.php?v=$value")
            "OnlineStreamwish" -> listOf("https://streamwish.to/e/$value")
            "OnlineVidhide" -> listOf("https://vidhidepro.com/v/$value")
            else -> emptyList()
        }
    }

    fun parseOnlineOptions(doc: Document): List<CinecalidadMYOnlineOption> {
        val out = mutableListOf<CinecalidadMYOnlineOption>()
        val links = doc.select("#panel_online a.link.onlinelink[service][data], a.link.onlinelink[service][data]")
        for (a in links) {
            val service = a.attr("service").trim()
            val data = a.attr("data").trim()
            if (service.equals("Trailer", ignoreCase = true) || data.isEmpty()) continue
            val embeds = mapServiceToEmbedUrls(service, data)
            if (embeds.isEmpty()) continue
            val label = a.selectFirst("li")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: service
            out += CinecalidadMYOnlineOption(
                service = service,
                label = label,
                data = data,
                embedUrls = embeds,
            )
        }
        return out
    }
}
