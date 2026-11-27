package com.menorah.repelis24shopbis

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class RePelis24ShopCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isSeries: Boolean,
    val year: Int? = null,
    val plotPreview: String? = null,
)

data class RePelis24ShopSeasonLink(
    val seasonNumber: Int,
    val title: String,
    val url: String,
)

data class RePelis24ShopEpisodeLink(
    val title: String,
    val url: String,
    val season: Int,
    val episode: Int,
    val posterUrl: String?,
)

data class RePelis24ShopDetail(
    val title: String,
    val url: String,
    val isSeries: Boolean,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val genres: List<String>,
    val trailerUrl: String?,
    val seasonLinks: List<RePelis24ShopSeasonLink>,
    val episodeLinks: List<RePelis24ShopEpisodeLink>,
    val recommendations: List<RePelis24ShopCard>,
)

data class RePelis24ShopServerOption(
    val label: String,
    val url: String,
)

object RePelis24ShopBisMarkup {
    const val DEFAULT_MAIN_URL = "https://repelis24.shop"

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_TABLE = IntArray(256) { -1 }.apply {
        for (i in B64_ALPHABET.indices) this[B64_ALPHABET[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    private val YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")
    private val DURATION_HM_REGEX = Regex("""(\d+)\s*h(?:\s*(\d+)\s*min?)?""", RegexOption.IGNORE_CASE)
    private val DURATION_M_REGEX = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val PAGE_NUM_REGEX = Regex("""/page/(\d+)/?""")
    private val SEASON_NUM_REGEX = Regex("""-(\d+)/?$""")
    private val EP_SE_REGEX = Regex("""-(\d+)x(\d+)/?$""", RegexOption.IGNORE_CASE)
    private val EP_TEXT_SE_REGEX = Regex("""(\d+)x(\d+)""")
    private val EP_NUM_TEXT_REGEX = Regex("""Episodio\s+(\d+)""", RegexOption.IGNORE_CASE)

    fun decodeBase64Utf8(input: String): String {
        val cleaned = input.trim().replace("\r", "").replace("\n", "").trimEnd('=')
        if (cleaned.isEmpty()) return ""
        val out = ByteArray((cleaned.length * 3) / 4)
        var outIdx = 0
        var acc = 0
        var bits = 0
        for (ch in cleaned) {
            val code = ch.code
            val v = if (code in B64_TABLE.indices) B64_TABLE[code] else -1
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outIdx < out.size) out[outIdx++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return String(out, 0, outIdx, Charsets.UTF_8)
    }

    fun resolveUrl(baseUrl: String, href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val base = URI(baseUrl.trimEnd('/') + "/")
            val resolved = if (raw.startsWith("//")) URI("https:$raw") else base.resolve(raw)
            val s = resolved.toString()
            if (s.startsWith("http://") || s.startsWith("https://")) s else null
        }.getOrNull()
    }

    fun buildSectionPageUrl(sectionUrl: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val base = resolveUrl(mainUrl, sectionUrl)?.trimEnd('/') ?: mainUrl
        if (page <= 1) return "$base/"
        return "$base/page/$page"
    }

    fun buildSearchUrl(query: String, page: Int = 1, mainUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val root = mainUrl.trimEnd('/')
        return if (page <= 1) "$root/busqueda/$encoded" else "$root/busqueda/$encoded/page/$page"
    }

    fun parseCards(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<RePelis24ShopCard> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<RePelis24ShopCard>()
        for (item in doc.select("div.movie-item")) {
            val a = item.selectFirst("a[href]") ?: continue
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!href.contains("/pelicula/") && !href.contains("/serie/")) continue
            if (!seen.add(href)) continue

            val img = item.selectFirst("img.poster, img")
            val rawAlt = img?.attr("alt")?.trim().orEmpty()
                .replace(Regex("""^(?:Poster|Ver)\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
            val title = item.selectFirst(".item-detail p, .item-detail")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: rawAlt.takeIf { it.isNotEmpty() }
                ?: continue

            val poster = resolveUrl(mainUrl, img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src"))
            val yearText = item.selectFirst("span.year, .card-hover-year")?.text().orEmpty()
            val year = YEAR_REGEX.find(yearText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val plotPreview = item.selectFirst(".card-hover-info-overview p")?.text()?.trim()?.takeIf { it.isNotEmpty() }

            out += RePelis24ShopCard(
                title = title,
                url = href,
                posterUrl = poster,
                isSeries = href.contains("/serie/"),
                year = year,
                plotPreview = plotPreview,
            )
        }
        return out
    }

    fun parseHasNext(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("ul.pagination a.next, a.next.page-numbers, link[rel=next]") != null) {
            return true
        }
        val nextPage = currentPage + 1
        return doc.select("ul.pagination a.page-link[href], ul.pagination a[href]").any { a ->
            val p = PAGE_NUM_REGEX.find(a.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            p != null && p >= nextPage
        }
    }

    private fun cleanPageTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("""\s*-\s*Ver\s+Completa.*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s*\|\s*RePelis24.*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+es\s+una\s+(?:pel[ií]cula|serie).*$""", RegexOption.IGNORE_CASE), "")
        return t.trim()
    }

    fun parseServerOptions(doc: Document): List<RePelis24ShopServerOption> {
        val out = mutableListOf<RePelis24ShopServerOption>()
        val seen = linkedSetOf<String>()
        for (li in doc.select("li[role=presentation][data-server], li[data-server]")) {
            val b64 = li.attr("data-server").trim()
            if (b64.isEmpty()) continue
            val decoded = decodeBase64Utf8(b64).trim()
            if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) continue
            if (!seen.add(decoded)) continue
            val label = li.select("span").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: li.text().trim().ifEmpty { "Servidor" }
            out += RePelis24ShopServerOption(label = label, url = decoded)
        }
        return out
    }

    fun parseSeasonLinks(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<RePelis24ShopSeasonLink> {
        val out = mutableListOf<RePelis24ShopSeasonLink>()
        val seen = linkedSetOf<String>()
        for ((idx, a) in doc.select("a[href*=/temporada/]").withIndex()) {
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!seen.add(href)) continue
            val label = a.text().trim().ifEmpty { "Temporada ${idx + 1}" }
            val sNum = Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: SEASON_NUM_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (idx + 1)
            out += RePelis24ShopSeasonLink(seasonNumber = sNum, title = label, url = href)
        }
        return out.sortedBy { it.seasonNumber }
    }

    fun parseEpisodeLinks(doc: Document, mainUrl: String = DEFAULT_MAIN_URL, fallbackSeason: Int = 1): List<RePelis24ShopEpisodeLink> {
        val out = mutableListOf<RePelis24ShopEpisodeLink>()
        val seen = linkedSetOf<String>()
        for ((idx, a) in doc.select("a[href*=/episodio/]").withIndex()) {
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!seen.add(href)) continue

            val text = a.text().trim()
            val seMatch = EP_SE_REGEX.find(href) ?: EP_TEXT_SE_REGEX.find(text)
            val sNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallbackSeason
            val eNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: EP_NUM_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (idx + 1)

            val epPoster = resolveUrl(mainUrl, a.selectFirst("img")?.attr("src"))
            val epName = "Episodio $eNum"
            out += RePelis24ShopEpisodeLink(
                title = epName,
                url = href,
                season = sNum,
                episode = eNum,
                posterUrl = epPoster,
            )
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    fun parseDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): RePelis24ShopDetail? {
        val resolvedUrl = resolveUrl(mainUrl, url) ?: url
        val isSeries = resolvedUrl.contains("/serie/") || resolvedUrl.contains("/temporada/")

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.let(::cleanPageTitle)
        val h1Text = doc.selectFirst("h1")?.text()?.let(::cleanPageTitle)
        val headingDesc = doc.select("h2").firstOrNull {
            it.text().contains("es una pelicula", ignoreCase = true) ||
                it.text().contains("es una película", ignoreCase = true) ||
                it.text().contains("es una serie", ignoreCase = true)
        }
        val headingTitle = headingDesc?.text()?.let(::cleanPageTitle)
        val title = h1Text?.takeIf { it.isNotEmpty() }
            ?: ogTitle?.takeIf { it.isNotEmpty() }
            ?: headingTitle?.takeIf { it.isNotEmpty() }
            ?: return null

        val posterEl = doc.select("img[src*='/cdn/p/']").firstOrNull()
            ?: doc.selectFirst("img.poster")
        val posterUrl = resolveUrl(mainUrl, posterEl?.attr("src"))
        val bgUrl = resolveUrl(
            mainUrl,
            doc.selectFirst("[data-backdrop]")?.attr("data-backdrop")
                ?: doc.selectFirst("img[src*='/cdn/b/']")?.attr("src"),
        )

        val nextSibling: Element? = headingDesc?.parent()?.children()?.let { kids ->
            val idx = kids.indexOf(headingDesc)
            if (idx >= 0 && idx + 1 < kids.size) kids[idx + 1] else null
        }
        val plot = nextSibling?.takeIf { it.tagName() == "p" }?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val headingParent: Element? = headingDesc?.parent()
        val topBlockText = headingParent?.text().orEmpty() + " " + doc.select(".movie-info, .info, .details").text()
        val year = YEAR_REGEX.find(topBlockText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val duration = DURATION_HM_REGEX.find(topBlockText)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            h * 60 + min
        } ?: DURATION_M_REGEX.find(topBlockText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val excludedContainers = setOf("nav", "header", "aside")
        val genres = doc.select("a[href*=/genero/]").filter { a ->
            a.parents().none { p -> p.tagName() in excludedContainers || p.hasClass("showcase-sidebar") }
        }.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val seasonLinks = if (isSeries) parseSeasonLinks(doc, mainUrl) else emptyList()
        val episodeLinks = if (isSeries) parseEpisodeLinks(doc, mainUrl) else emptyList()

        // Recommendations sit inside .movie-item cards or a[href*=/pelicula/] under "Sugerencias"
        val recs = parseCards(doc, mainUrl).filter { it.url != resolvedUrl }.ifEmpty {
            val sugHeading = doc.select("h2").firstOrNull { it.text().contains("Sugerencias", ignoreCase = true) }
            val container: Element? = sugHeading?.parent()
            val out = mutableListOf<RePelis24ShopCard>()
            val seen = linkedSetOf<String>()
            container?.select("a[href*=/pelicula/], a[href*=/serie/]")?.forEach { a ->
                val rUrl = resolveUrl(mainUrl, a.attr("href")) ?: return@forEach
                if (rUrl == resolvedUrl || !seen.add(rUrl)) return@forEach
                val rPoster = resolveUrl(mainUrl, a.selectFirst("img")?.attr("src"))
                val rAlt = a.selectFirst("img")?.attr("alt")
                    ?.replace(Regex("""^(?:Poster|Ver)\s+""", RegexOption.IGNORE_CASE), "")
                    ?.trim()
                val rTitle = rAlt?.takeIf { it.isNotEmpty() } ?: a.text().trim().takeIf { it.isNotEmpty() } ?: return@forEach
                out += RePelis24ShopCard(
                    title = rTitle,
                    url = rUrl,
                    posterUrl = rPoster,
                    isSeries = rUrl.contains("/serie/"),
                )
            }
            out
        }

        return RePelis24ShopDetail(
            title = title,
            url = resolvedUrl,
            isSeries = isSeries,
            posterUrl = posterUrl,
            backgroundUrl = bgUrl,
            plot = plot,
            year = year,
            durationMinutes = duration,
            genres = genres,
            trailerUrl = null,
            seasonLinks = seasonLinks,
            episodeLinks = episodeLinks,
            recommendations = recs,
        )
    }

    fun extractQueryParam(url: String, paramName: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key.equals(paramName, ignoreCase = true)) {
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                return URLDecoder.decode(rawVal, "UTF-8").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
