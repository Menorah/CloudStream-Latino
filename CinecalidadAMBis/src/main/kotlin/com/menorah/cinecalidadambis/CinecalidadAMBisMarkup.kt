package com.menorah.cinecalidadambis

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class CinecalidadAMCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isSeries: Boolean,
    val rating10: Double? = null,
    val year: Int? = null,
)

data class CinecalidadAMEpisode(
    val title: String,
    val url: String,
    val season: Int,
    val episode: Int,
    val posterUrl: String?,
)

data class CinecalidadAMDetail(
    val title: String,
    val url: String,
    val isSeries: Boolean,
    val posterUrl: String?,
    val plot: String?,
    val rating10: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val trailerUrl: String?,
    val episodes: List<CinecalidadAMEpisode>,
    val recommendations: List<CinecalidadAMCard>,
)

data class CinecalidadAMPlayerOption(
    val name: String,
    val url: String,
)

object CinecalidadAMBisMarkup {
    const val DEFAULT_MAIN_URL = "https://www.cinecalidad.am"

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_TABLE = IntArray(256) { -1 }.apply {
        for (i in B64_ALPHABET.indices) this[B64_ALPHABET[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    private val SEASON_EP_NUM_REGEX = Regex("""S(\d+)\s*[-xX]\s*E(\d+)""", RegexOption.IGNORE_CASE)
    private val SLUG_SE_REGEX = Regex("""-(\d+)x(\d+)/?$""", RegexOption.IGNORE_CASE)
    private val SLUG_TMDB_SE_REGEX = Regex("""-s(\d+)-e(\d+)/?$""", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")
    private val PAGE_NUM_REGEX = Regex("""/page/(\d+)/?""")
    private val DOUBLE_PATH_SLASH = Regex("""(?<!:)/{2,}""")

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

    fun extractImageUrl(img: Element?, baseUrl: String = DEFAULT_MAIN_URL): String? {
        if (img == null) return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src"),
        )
        for (c in candidates) {
            val trimmed = c.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) continue
            val resolved = resolveUrl(baseUrl, trimmed) ?: continue
            return resolved.replace(DOUBLE_PATH_SLASH, "/")
        }
        return null
    }

    fun buildArchivePageUrl(sectionUrl: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val cleanBase = resolveUrl(mainUrl, sectionUrl) ?: mainUrl
        if (page <= 1) return cleanBase
        return "${cleanBase.trimEnd('/')}/page/$page/"
    }

    fun buildSearchUrl(query: String, page: Int = 1, mainUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = mainUrl.trimEnd('/')
        return if (page <= 1) "$root/?s=$encoded" else "$root/page/$page/?s=$encoded"
    }

    fun parseCards(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<CinecalidadAMCard> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<CinecalidadAMCard>()
        val items = doc.select("#archive-content > article.item:not(.naadb), article.item.movies:not(.naadb)")
        for (article in items) {
            val anchor = article.selectFirst("h3.hover_caption_caption a[href], a[href]") ?: continue
            val href = resolveUrl(mainUrl, anchor.attr("href")) ?: continue
            if (!href.contains("/ver-pelicula/") && !href.contains("/ver-serie/")) continue
            if (!seen.add(href)) continue

            val img = article.selectFirst("img")
            val title = article.selectFirst(".in_title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("promo", ignoreCase = true) }
                ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: continue

            val selt = article.selectFirst(".selt")?.text()?.trim().orEmpty()
            val isSeries = href.contains("/ver-serie/") ||
                selt.contains("Serie", ignoreCase = true) ||
                article.selectFirst(".last-ep, .last-s") != null

            val poster = extractImageUrl(img, mainUrl)
            val rating = article.selectFirst(".rating")?.text()?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val year = YEAR_REGEX.find(article.select(".hover_caption_caption p").text())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

            out += CinecalidadAMCard(
                title = title,
                url = href,
                posterUrl = poster,
                isSeries = isSeries,
                rating10 = rating,
                year = year,
            )
        }
        return out
    }

    fun parseHasNext(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("link[rel=next], .pagination a.next, a.next.page-numbers") != null) {
            return true
        }
        val nextPage = currentPage + 1
        return doc.select(".pagination a[href], .nav-links a[href], a[href*=/page/]").any { el ->
            val n = PAGE_NUM_REGEX.find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            n != null && n >= nextPage
        }
    }

    fun unwrapEmbedOption(rawOption: String): String? {
        val trimmed = rawOption.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("zopass=", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq).equals("zopass", ignoreCase = true)) {
                    val encoded = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    val decoded = decodeBase64Utf8(encoded).trim()
                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        return decoded
                    }
                }
            }
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else null
    }

    fun parsePlayerOptions(doc: Document): Pair<List<CinecalidadAMPlayerOption>, String?> {
        val options = mutableListOf<CinecalidadAMPlayerOption>()
        var trailerUrl: String? = null
        val seen = linkedSetOf<String>()

        for (li in doc.select("#playeroptionsul li.dooplay_player_option[data-option], li.dooplay_player_option[data-option]")) {
            val rawOpt = li.attr("data-option").trim()
            if (rawOpt.isEmpty()) continue
            val isTrailer = li.hasClass("dooplay_player_option_trailer") ||
                li.id().contains("trailer", ignoreCase = true) ||
                li.text().contains("Trailer", ignoreCase = true)
            val unwrapped = unwrapEmbedOption(rawOpt) ?: continue
            if (isTrailer) {
                if (trailerUrl == null) trailerUrl = unwrapped
                continue
            }
            if (!seen.add(unwrapped)) continue
            val label = li.ownText().trim().ifEmpty { li.text().trim() }.ifEmpty { "Servidor" }
            options += CinecalidadAMPlayerOption(name = label, url = unwrapped)
        }
        return options to trailerUrl
    }

    fun parseSeriesEpisodes(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<CinecalidadAMEpisode> {
        val out = mutableListOf<CinecalidadAMEpisode>()
        val seen = linkedSetOf<String>()
        val seasonContainers = doc.select("#seasons div#jstab[data-tab], #seasons div.se-c[data-tab]")
        for ((tabIdx, seasonEl) in seasonContainers.withIndex()) {
            val tabSeason = seasonEl.attr("data-tab").toIntOrNull()
            val headerSeason = Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(seasonEl.selectFirst("h2")?.text().orEmpty())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val defaultSeason = headerSeason ?: tabSeason ?: (tabIdx + 1)

            val epItems = seasonEl.select("ul.episodios li")
            for ((epIdx, li) in epItems.withIndex()) {
                val anchor = li.selectFirst(".episodiotitle a[href], a[href]") ?: continue
                val epUrl = resolveUrl(mainUrl, anchor.attr("href")) ?: continue
                if (!seen.add(epUrl)) continue

                val epTitle = anchor.text().trim().ifEmpty { "Episodio ${epIdx + 1}" }
                val numText = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
                val mNum = SEASON_EP_NUM_REGEX.find(numText)
                    ?: SLUG_SE_REGEX.find(epUrl)
                    ?: SLUG_TMDB_SE_REGEX.find(epUrl)
                val seasonNum = mNum?.groupValues?.getOrNull(1)?.toIntOrNull() ?: defaultSeason
                val epNum = mNum?.groupValues?.getOrNull(2)?.toIntOrNull() ?: (epIdx + 1)
                val epPoster = extractImageUrl(li.selectFirst(".imagen img"), mainUrl)

                out += CinecalidadAMEpisode(
                    title = epTitle,
                    url = epUrl,
                    season = seasonNum,
                    episode = epNum,
                    posterUrl = epPoster,
                )
            }
        }
        return out
    }

    fun parseDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): CinecalidadAMDetail? {
        val root = doc.selectFirst("div.single_left") ?: return null
        val title = root.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolvedUrl = resolveUrl(mainUrl, url) ?: url
        val isSeries = resolvedUrl.contains("/ver-serie/") || doc.selectFirst("#episodes #serie_contenido") != null

        val poster = extractImageUrl(root.selectFirst("table img"), mainUrl)
        val infoTd = root.selectFirst("table tr td:eq(1)") ?: root.selectFirst("table td:nth-of-type(2)")
        val plot = infoTd?.select("> p")
            ?.map { it.text().replace("\u00A0", " ").trim() }
            ?.firstOrNull { p ->
                p.length > 20 &&
                    !p.startsWith("Títulos:", ignoreCase = true) &&
                    !p.startsWith("Género:", ignoreCase = true)
            }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val rating10 = root.selectFirst("#star-rating-box")?.parent()?.selectFirst("b")?.text()
            ?.replace(',', '.')?.toDoubleOrNull()

        val genres = root.select("a[href*=/genero-de-la-pelicula/], a[href*=/genero/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val actors = root.select("a[href*=/reparto/], a[href*=/director/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val (_, trailerUrl) = parsePlayerOptions(doc)
        val episodes = if (isSeries) parseSeriesEpisodes(doc, mainUrl) else emptyList()

        val recs = mutableListOf<CinecalidadAMCard>()
        val seenRec = linkedSetOf<String>()
        for (a in doc.select("div.yarpp-related a[href], ul.related-posts a[href]")) {
            val recUrl = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!recUrl.contains("/ver-pelicula/") && !recUrl.contains("/ver-serie/")) continue
            if (!seenRec.add(recUrl)) continue
            val recTitle = a.text().replace(Regex("""\s+online\s*$""", RegexOption.IGNORE_CASE), "").trim()
            if (recTitle.isEmpty()) continue
            val li = a.parent()
            val recImg = extractImageUrl(li?.selectFirst("img"), mainUrl)
            recs += CinecalidadAMCard(
                title = recTitle,
                url = recUrl,
                posterUrl = recImg,
                isSeries = recUrl.contains("/ver-serie/"),
            )
        }

        return CinecalidadAMDetail(
            title = title,
            url = resolvedUrl,
            isSeries = isSeries,
            posterUrl = poster,
            plot = plot,
            rating10 = rating10,
            genres = genres,
            actors = actors,
            trailerUrl = trailerUrl,
            episodes = episodes,
            recommendations = recs,
        )
    }
}
