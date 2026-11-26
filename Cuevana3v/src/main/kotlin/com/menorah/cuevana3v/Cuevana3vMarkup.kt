package com.menorah.cuevana3v

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

internal enum class CuevanaItemKind {
    Movie,
    Series,
    Episode,
}

internal data class CuevanaCatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val kind: CuevanaItemKind,
)

internal data class CuevanaHomeSection(
    val title: String,
    val horizontal: Boolean,
    val items: List<CuevanaCatalogCard>,
)

internal data class CuevanaSeasonRef(
    val season: Int,
    val url: String,
)

internal data class CuevanaEpisodeCard(
    val title: String,
    val url: String,
    val season: Int,
    val episode: Int,
    val posterUrl: String?,
)

internal data class CuevanaMovieDetail(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val recommendations: List<CuevanaCatalogCard>,
)

internal data class CuevanaSeriesDetail(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val seasons: List<CuevanaSeasonRef>,
    val directEpisodes: List<CuevanaEpisodeCard>,
)

internal data class CuevanaPlayerSource(
    val language: String,
    val quality: String?,
    val serverName: String,
    val rawServerUrl: String,
)

internal data class TungServerConfig(
    val servers: Map<Char, String>,
    val xorKey: String,
)

internal object Cuevana3vMarkup {
    const val MAIN_URL = "https://cuevana3v.pro"
    const val TUNG_URL = "https://tungtungsahur.cuevana3v.pro"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val defaultTungConfig = TungServerConfig(
        servers = mapOf(
            '1' to "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/",
            '2' to "https://filemoon.sx/e/",
            '3' to "https://vibuxer.com/e/",
            '4' to "https://dood.li/e/",
        ),
        xorKey = "a45f04ce-2394-47c3-b718-0ecd97ce51d6",
    )

    val jsonMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val HOURS_MINUTES_REGEX = Regex("""(\d+)\s*h(?:\s*(\d+)\s*m)?""", RegexOption.IGNORE_CASE)
    private val MINUTES_ONLY_REGEX = Regex("""(\d+)\s*m(?:in)?\b""", RegexOption.IGNORE_CASE)
    private val EPISODE_URL_REGEX = Regex("""/episodio-(\d+)x(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
    private val EPISODE_TEXT_REGEX = Regex("""\b(\d+)\s*x\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val SEASON_URL_REGEX = Regex("""/temporada-(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
    private val PAGE_PARAM_REGEX = Regex("""[?&]page=(\d+)""")
    private val TMDB_SIZE_REGEX = Regex("""(https?://image\.tmdb\.org/t/p/)(w\d+|original)(/.*)""", RegexOption.IGNORE_CASE)
    private val TUNG_SERVERS_BLOCK_REGEX = Regex("""const\s+servers\s*=\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL)
    private val TUNG_SERVER_ENTRY_REGEX = Regex("""['"]?([0-9A-Za-z])['"]?\s*:\s*['"]([^'"]+)['"]""")
    private val TUNG_KEY_REGEX = Regex("""const\s+key\s*=\s*['"]([^'"]+)['"]""")

    fun resolveSiteUrl(raw: String?, base: String = MAIN_URL): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (clean.startsWith("#") || clean.startsWith("javascript:", ignoreCase = true)) return null
        return runCatching {
            val uri = URI("$base/").resolve(clean)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            uri.toString().removeSuffix("/")
        }.getOrNull()
    }

    fun normalizeImageUrl(raw: String?, promoteSmallPoster: Boolean = true): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) } ?: return null
        val abs = if (clean.startsWith("//")) "https:$clean" else resolveSiteUrl(clean, MAIN_URL) ?: return null
        if (!promoteSmallPoster) return abs
        val match = TMDB_SIZE_REGEX.matchEntire(abs) ?: return abs
        val size = match.groupValues[2].lowercase()
        return if (size in setOf("w92", "w154", "w185", "w200", "w300", "w342")) {
            "${match.groupValues[1]}w500${match.groupValues[3]}"
        } else {
            abs
        }
    }

    fun extractSeriesUrlFromUrl(rawUrl: String): String {
        val normalized = resolveSiteUrl(rawUrl) ?: rawUrl.trimEnd('/')
        val idxEp = normalized.indexOf("/episodio-", ignoreCase = true)
        if (idxEp > 0) return normalized.substring(0, idxEp)
        val idxSeason = normalized.indexOf("/temporada-", ignoreCase = true)
        if (idxSeason > 0) return normalized.substring(0, idxSeason)
        return normalized
    }

    fun isEpisodeUrl(url: String): Boolean = url.contains("/episodio-", ignoreCase = true)

    fun isSeriesUrl(url: String): Boolean = url.contains("/serie/", ignoreCase = true)

    fun isMovieUrl(url: String): Boolean = url.contains("/pelicula/", ignoreCase = true)

    /**
     * Pure Kotlin Base64 decoder (works identically on JVM tests and Android API 21+).
     */
    fun decodeBase64Bytes(input: String): ByteArray {
        val clean = input.trim()
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        val out = ArrayList<Byte>((padded.length * 3) / 4)
        var i = 0
        while (i + 3 < padded.length) {
            val c0 = base64Value(padded[i])
            val c1 = base64Value(padded[i + 1])
            val p2 = padded[i + 2]
            val p3 = padded[i + 3]
            if (c0 < 0 || c1 < 0) return ByteArray(0)
            out.add((((c0 shl 2) or (c1 shr 4)) and 0xFF).toByte())
            if (p2 != '=') {
                val c2 = base64Value(p2)
                if (c2 < 0) return ByteArray(0)
                out.add((((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte())
                if (p3 != '=') {
                    val c3 = base64Value(p3)
                    if (c3 < 0) return ByteArray(0)
                    out.add((((c2 and 0x03) shl 6) or c3).toByte())
                }
            }
            i += 4
        }
        return out.toByteArray()
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c.code - 'A'.code
        in 'a'..'z' -> c.code - 'a'.code + 26
        in '0'..'9' -> c.code - '0'.code + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    fun parseTungConfig(html: String): TungServerConfig? {
        val serversBlock = TUNG_SERVERS_BLOCK_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val servers = linkedMapOf<Char, String>()
        for (m in TUNG_SERVER_ENTRY_REGEX.findAll(serversBlock)) {
            val id = m.groupValues[1].firstOrNull() ?: continue
            val prefix = m.groupValues[2].trim().replace("\\/", "/")
            if (prefix.startsWith("http", ignoreCase = true)) {
                servers[id] = prefix
            }
        }
        if (servers.isEmpty()) return null
        val key = TUNG_KEY_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?: defaultTungConfig.xorKey
        return TungServerConfig(servers = servers, xorKey = key)
    }

    fun extractTokenParam(serverUrl: String): String? = queryParam(serverUrl, "token")

    fun decodeTungUrl(
        rawServerUrl: String,
        config: TungServerConfig = defaultTungConfig,
    ): String? {
        val trimmed = rawServerUrl.trim()
        if (trimmed.isEmpty()) return null

        val token = queryParam(trimmed, "token")
        if (!token.isNullOrBlank() && token.length >= 2) {
            val serverKey = token[0]
            val prefix = config.servers[serverKey] ?: return null
            val bytes = decodeBase64Bytes(token.substring(1))
            if (bytes.isEmpty()) return null
            val xorKey = config.xorKey
            if (xorKey.isEmpty()) return null
            val sb = StringBuilder(bytes.size)
            for (i in bytes.indices) {
                val decodedCode = (bytes[i].toInt() and 0xFF) xor xorKey[i % xorKey.length].code
                sb.append(decodedCode.toChar())
            }
            val id = sb.toString().trim()
            if (id.isEmpty()) return null
            return prefix + id
        }

        val vParam = queryParam(trimmed, "v")
        if (!vParam.isNullOrBlank()) {
            val decoded = String(decodeBase64Bytes(vParam), Charsets.UTF_8).trim()
                .replace("vidsrc.pm", "vidsrc.me", ignoreCase = true)
            return resolveSiteUrl(decoded, MAIN_URL)
        }

        return if (!trimmed.contains("tungtungsahur.cuevana3v.pro", ignoreCase = true)) {
            resolveSiteUrl(trimmed, MAIN_URL)
        } else {
            null
        }
    }

    private fun queryParam(url: String, paramName: String): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            if (k == paramName) {
                val v = if (idx >= 0) pair.substring(idx + 1) else ""
                return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrElse { v }
            }
        }
        return null
    }

    fun parseCatalogItems(html: String): List<CuevanaCatalogCard> {
        val doc = Jsoup.parse(html, MAIN_URL)
        return parseCatalogContainer(doc.selectFirst("main") ?: doc.body())
    }

    fun parseHomeSections(html: String): List<CuevanaHomeSection> {
        val doc = Jsoup.parse(html, MAIN_URL)
        val sections = mutableListOf<CuevanaHomeSection>()

        val episodesSection = doc.selectFirst("section.home-series.home-episodes")
        val seriesSection = doc.selectFirst("section.home-series:not(.home-episodes)")

        val movieCards = doc.select("div.TPost.C, article.TPost.C")
            .filter { el -> el.parents().none { it == episodesSection || it == seriesSection } }
            .mapNotNull { parseLegacyCardElement(it) }
            .distinctBy { it.url }

        if (movieCards.isNotEmpty()) {
            sections += CuevanaHomeSection("Películas Online", false, movieCards)
        }

        if (episodesSection != null) {
            val epCards = parseCatalogContainer(episodesSection)
            if (epCards.isNotEmpty()) {
                sections += CuevanaHomeSection("Últimos Episodios", true, epCards)
            }
        }

        if (seriesSection != null) {
            val sCards = parseCatalogContainer(seriesSection)
            if (sCards.isNotEmpty()) {
                sections += CuevanaHomeSection("Series Online", false, sCards)
            }
        }

        return sections
    }

    private fun parseCatalogContainer(container: Element?): List<CuevanaCatalogCard> {
        if (container == null) return emptyList()
        val cards = linkedMapOf<String, CuevanaCatalogCard>()

        for (el in container.select("div.TPost.C, article.TPost.C")) {
            val card = parseLegacyCardElement(el) ?: continue
            cards.putIfAbsent(card.url, card)
        }

        for (anchor in container.select("a[href*='/pelicula/'], a[href*='/serie/']")) {
            if (anchor.parents().any { it.id() == "player" || it.hasClass("TPost") }) continue
            val card = parseAnchorCard(anchor) ?: continue
            cards.putIfAbsent(card.url, card)
        }

        return cards.values.toList()
    }

    private fun parseLegacyCardElement(el: Element): CuevanaCatalogCard? {
        val anchor = el.selectFirst("a[href*='/pelicula/'], a[href*='/serie/']") ?: return null
        val url = resolveSiteUrl(anchor.attr("href")) ?: return null
        if (url.contains("/temporada-", ignoreCase = true)) return null
        val img = el.selectFirst("img")
        val rawTitle = el.selectFirst(".Title, h2, h3, .item-detail p")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: cleanImgAlt(img?.attr("alt"))
            ?: return null
        val yearText = el.selectFirst("span.year, .Date, .Qlty")?.text().orEmpty()
        val year = YEAR_REGEX.find(yearText)?.groupValues?.get(1)?.toIntOrNull()
        val kind = when {
            isEpisodeUrl(url) -> CuevanaItemKind.Episode
            isSeriesUrl(url) -> CuevanaItemKind.Series
            else -> CuevanaItemKind.Movie
        }
        val posterUrl = normalizeImageUrl(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"),
            promoteSmallPoster = kind != CuevanaItemKind.Episode,
        )
        return CuevanaCatalogCard(
            title = rawTitle,
            url = url,
            posterUrl = posterUrl,
            year = year,
            kind = kind,
        )
    }

    private fun parseAnchorCard(anchor: Element): CuevanaCatalogCard? {
        val url = resolveSiteUrl(anchor.attr("href")) ?: return null
        if (url.contains("/temporada-", ignoreCase = true)) return null
        val img = anchor.selectFirst("img") ?: return null
        val yearText = anchor.selectFirst("span.year")?.text()?.trim().orEmpty()
        val year = YEAR_REGEX.find(yearText)?.groupValues?.get(1)?.toIntOrNull()
        val rawTitle = anchor.selectFirst(".item-detail p, p")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: cleanImgAlt(img.attr("alt"))
            ?: anchor.ownText().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val kind = when {
            isEpisodeUrl(url) -> CuevanaItemKind.Episode
            isSeriesUrl(url) -> CuevanaItemKind.Series
            else -> CuevanaItemKind.Movie
        }
        val posterUrl = normalizeImageUrl(
            img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src"),
            promoteSmallPoster = kind != CuevanaItemKind.Episode,
        )
        return CuevanaCatalogCard(
            title = rawTitle,
            url = url,
            posterUrl = posterUrl,
            year = year,
            kind = kind,
        )
    }

    private fun cleanImgAlt(alt: String?): String? {
        val raw = alt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = raw.lowercase()
        if (lower.startsWith("poster del episodio")) return null
        return raw.replace(Regex("""^(?:ver|pel[ií]cula|serie)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+online$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun hasNextPage(
        html: String,
        requestUrl: String,
        currentPage: Int,
        parsedCount: Int,
    ): Boolean {
        if (parsedCount == 0) return false
        if (requestUrl.contains("orden=tendencias", ignoreCase = true)) return false
        val doc = Jsoup.parse(html, MAIN_URL)
        val links = doc.select("a[href*='page=']")
        for (a in links) {
            val text = a.text().trim()
            if (text.contains("siguiente", ignoreCase = true)) return true
            val pageNum = PAGE_PARAM_REGEX.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            if (pageNum != null && pageNum > currentPage) return true
        }
        return false
    }

    fun parseMovieDetail(html: String, pageUrl: String): CuevanaMovieDetail {
        val doc = Jsoup.parse(html, MAIN_URL)
        val cleanUrl = resolveSiteUrl(pageUrl) ?: pageUrl
        val ld = parsePrimaryJsonLd(doc, "Movie")

        val h1 = doc.selectFirst("h1.title.page-title-hero, h1.title, h1")
        val voteText = h1?.selectFirst(".voteav")?.text()?.trim()
            ?: doc.selectFirst("span.voteav")?.text()?.trim()
        val rawTitle = h1?.clone()?.apply { select(".voteav").remove() }?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ld?.path("name")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: cleanOgTitle(doc.selectFirst("meta[property='og:title']")?.attr("content"))
            ?: "Película"

        val posterRaw = imgSrc(doc.selectFirst(".backdrop-info figure img"))
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val bgRaw = imgSrc(doc.selectFirst(".backdrop-image figure img, figure.backdrop img"))

        val plot = doc.selectFirst(".backdrop-info p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ld?.path("description")?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        val sections = sectionMapBySubtitle(doc)
        val year = sections.entries.firstOrNull { it.key.contains("año") }?.value
            ?.let { YEAR_REGEX.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.let { YEAR_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val durationText = sections.entries.firstOrNull { it.key.contains("dura") }?.value?.text()
        val durationMinutes = parseDurationMinutes(durationText)

        val actorsFromSection = sections.entries.firstOrNull { it.key.contains("actores") }?.value
            ?.selectFirst("p")?.text()?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val actorsFromLd = extractJsonLdActors(ld)
        val actors = (actorsFromSection + actorsFromLd).distinct()

        val genres = doc.select("a[href*='genero=']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.distinct()

        val rating = parseRating(voteText, ld)

        val relatedContainer = doc.selectFirst("section:has(h2:matchesOwn((?i)interesar))")
            ?: doc.selectFirst("div.hero-carousel")
        val recommendations = parseCatalogContainer(relatedContainer)
            .filter { it.url != cleanUrl }

        return CuevanaMovieDetail(
            title = rawTitle,
            url = cleanUrl,
            posterUrl = normalizeImageUrl(posterRaw, promoteSmallPoster = true),
            backgroundPosterUrl = normalizeImageUrl(bgRaw, promoteSmallPoster = false),
            plot = plot,
            year = year,
            durationMinutes = durationMinutes,
            rating = rating,
            genres = genres,
            actors = actors,
            recommendations = recommendations,
        )
    }

    fun parseSeriesDetail(html: String, pageUrl: String): CuevanaSeriesDetail {
        val doc = Jsoup.parse(html, MAIN_URL)
        val cleanUrl = extractSeriesUrlFromUrl(pageUrl)
        val ld = parsePrimaryJsonLd(doc, "TVSeries")

        val h1Text = doc.selectFirst(".title-detail-hero-details h1.title, h1.title, h1")?.clone()?.apply {
            select(".voteav").remove()
        }?.text()?.trim()
        val cleanedH1 = h1Text?.replace(Regex("""^Serie\s+""", RegexOption.IGNORE_CASE), "")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val title = ld?.path("name")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: cleanedH1
            ?: cleanOgTitle(doc.selectFirst("meta[property='og:title']")?.attr("content"))
            ?: "Serie"

        val posterRaw = imgSrc(doc.selectFirst(".title-detail-hero-details figure.poster img, .backdrop-info figure img"))
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        val bgRaw = imgSrc(doc.selectFirst(".title-detail-hero-background figure.backdrop img, figure.backdrop img"))

        val sections = sectionMapBySubtitle(doc)
        val rawSummary = sections.entries.firstOrNull { it.key.contains("resumen") }?.value
            ?.selectFirst("p")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val ldDesc = ld?.path("description")?.asText()?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("Sin descripción", ignoreCase = true) }
        val metaDesc = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.removePrefix("$title - ")?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("Sin descripción", ignoreCase = true) }
        val plot = rawSummary ?: ldDesc ?: metaDesc

        val year = sections.entries.firstOrNull { it.key.contains("año") }?.value
            ?.let { YEAR_REGEX.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.let { YEAR_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val actorsFromSection = sections.entries.firstOrNull { it.key.contains("actores") }?.value
            ?.selectFirst("p")?.text()?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val actors = (actorsFromSection + extractJsonLdActors(ld)).distinct()

        val genres = doc.select("a[href*='genero=']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.distinct()

        val voteText = doc.selectFirst("span.voteav")?.text()?.trim()
        val rating = parseRating(voteText, ld)

        val seasonsMap = linkedMapOf<Int, CuevanaSeasonRef>()
        for (a in doc.select("a[href*='/temporada-']")) {
            val sUrl = resolveSiteUrl(a.attr("href")) ?: continue
            val sNum = SEASON_URL_REGEX.find(sUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: a.selectFirst("span")?.text()?.trim()?.toIntOrNull()
                ?: continue
            seasonsMap.putIfAbsent(sNum, CuevanaSeasonRef(sNum, sUrl))
        }
        val seasons = seasonsMap.values.sortedBy { it.season }

        val directEpisodes = if (seasons.isEmpty()) parseSeasonEpisodes(html, fallbackSeason = 1) else emptyList()

        return CuevanaSeriesDetail(
            title = title,
            url = cleanUrl,
            posterUrl = normalizeImageUrl(posterRaw, promoteSmallPoster = true),
            backgroundPosterUrl = normalizeImageUrl(bgRaw, promoteSmallPoster = false),
            plot = plot,
            year = year,
            rating = rating,
            genres = genres,
            actors = actors,
            seasons = seasons,
            directEpisodes = directEpisodes,
        )
    }

    fun parseSeasonEpisodes(html: String, fallbackSeason: Int): List<CuevanaEpisodeCard> {
        val doc = Jsoup.parse(html, MAIN_URL)
        val seen = linkedSetOf<String>()
        val result = mutableListOf<CuevanaEpisodeCard>()
        var autoIdx = 1

        for (a in doc.select("main a[href*='/episodio-'], a[href*='/episodio-']")) {
            if (a.parents().any { it.id() == "player" }) continue
            val epUrl = resolveSiteUrl(a.attr("href")) ?: continue
            if (!seen.add(epUrl)) continue

            val text = a.text().trim()
            val urlMatch = EPISODE_URL_REGEX.find(epUrl)
            val txtMatch = EPISODE_TEXT_REGEX.find(text)
            val seasonNum = urlMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: txtMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: fallbackSeason
            val epNum = urlMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: txtMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: autoIdx
            autoIdx = maxOf(autoIdx + 1, epNum + 1)

            val rawEpTitle = a.selectFirst(".item-detail p, p, h2, h3")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: text
            val cleanedTitle = rawEpTitle
                .replace(Regex("""^\d+\s*x\s*\d+\s*[-–:.]?\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf { it.isNotEmpty() && !it.equals("${seasonNum}x${epNum}", ignoreCase = true) }
                ?: "Episodio $epNum"

            val img = a.selectFirst("img")
            val thumb = normalizeImageUrl(
                img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"),
                promoteSmallPoster = false,
            )

            result += CuevanaEpisodeCard(
                title = cleanedTitle,
                url = epUrl,
                season = seasonNum,
                episode = epNum,
                posterUrl = thumb,
            )
        }

        return result.sortedWith(compareBy<CuevanaEpisodeCard> { it.season }.thenBy { it.episode })
    }

    fun parsePlayerTabs(html: String): List<CuevanaPlayerSource> {
        val doc = Jsoup.parse(html, MAIN_URL)
        val out = mutableListOf<CuevanaPlayerSource>()
        val seen = linkedSetOf<String>()

        for (tabItem in doc.select("#player ul.tabs-video > li.tab-video-item")) {
            val rawTabHeader = tabItem.selectFirst(".tab-item-name")?.text()?.trim().orEmpty()
            val (langLabel, qualityLabel) = normalizeTabHeader(rawTabHeader)

            for (srv in tabItem.select("li[data-server]")) {
                val rawUrl = srv.attr("data-server").trim()
                if (rawUrl.isEmpty() || !seen.add("$langLabel|$rawUrl")) continue
                val serverName = normalizeServerName(srv.text())
                out += CuevanaPlayerSource(
                    language = langLabel,
                    quality = qualityLabel,
                    serverName = serverName,
                    rawServerUrl = rawUrl,
                )
            }
        }

        if (out.isEmpty()) {
            for (srv in doc.select("#player li[data-server], li[data-server]")) {
                val rawUrl = srv.attr("data-server").trim()
                if (rawUrl.isEmpty() || !seen.add("Latino|$rawUrl")) continue
                out += CuevanaPlayerSource(
                    language = "Latino",
                    quality = null,
                    serverName = normalizeServerName(srv.text()),
                    rawServerUrl = rawUrl,
                )
            }
        }

        return out
    }

    fun normalizeTabHeader(raw: String): Pair<String, String?> {
        val lower = raw.lowercase()
        val lang = when {
            lower.contains("latino") -> "Latino"
            lower.contains("castellano") || lower.contains("español") || lower.contains("espanol") -> "Castellano"
            lower.contains("subtitulad") || lower.contains("vose") -> "Subtitulado"
            lower.contains("multilenguaje") || lower.contains("multi") -> "Multilenguaje"
            lower.contains("ingl") -> "Inglés"
            else -> raw.substringBefore("Calidad").substringBefore("·").trim().ifEmpty { "Latino" }
        }
        val quality = when {
            lower.contains("4k") || lower.contains("2160") -> "4K"
            lower.contains("1080") || lower.contains("full hd") -> "1080p"
            lower.contains("hd") -> "HD"
            lower.contains("premiere") -> "Premiere"
            lower.contains("cam") -> "CAM"
            else -> null
        }
        return lang to quality
    }

    fun normalizeServerName(rawText: String): String {
        val clean = rawText
            .replace(Regex("""^\s*Servidor\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Reproducir\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bServidor\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bReproducir\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return clean.ifEmpty { "Servidor" }
    }

    fun parseDurationMinutes(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hm = HOURS_MINUTES_REGEX.find(text)
        if (hm != null) {
            val hours = hm.groupValues[1].toIntOrNull() ?: 0
            val mins = hm.groupValues[2].toIntOrNull() ?: 0
            val total = hours * 60 + mins
            if (total > 0) return total
        }
        val mOnly = MINUTES_ONLY_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return mOnly?.takeIf { it > 0 }
    }

    private fun imgSrc(el: Element?): String? {
        if (el == null) return null
        return el.attr("data-src").ifBlank { el.attr("src") }.takeIf { it.isNotBlank() }
    }

    private fun sectionMapBySubtitle(doc: Document): Map<String, Element> {
        val out = linkedMapOf<String, Element>()
        for (sec in doc.select("div.section, section.section")) {
            val subtitle = sec.selectFirst("h2.subtitle, h2")?.text()?.trim()?.lowercase() ?: continue
            out.putIfAbsent(subtitle, sec)
        }
        return out
    }

    private fun parsePrimaryJsonLd(doc: Document, expectedType: String): JsonNode? {
        for (script in doc.select("script[type='application/ld+json']")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val node = runCatching { jsonMapper.readTree(raw) }.getOrNull() ?: continue
            val type = node.path("@type").asText()
            if (type.equals(expectedType, ignoreCase = true)) return node
        }
        return null
    }

    private fun extractJsonLdActors(ld: JsonNode?): List<String> {
        if (ld == null) return emptyList()
        val actorNode = ld.path("actor")
        if (!actorNode.isArray) return emptyList()
        return actorNode.mapNotNull { it.path("name").asText().trim().takeIf(String::isNotEmpty) }
    }

    private fun parseRating(voteText: String?, ld: JsonNode?): Double? {
        val fromBadge = voteText?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }
        if (fromBadge != null) return fromBadge
        if (ld == null) return null
        val agg = ld.path("aggregateRating")
        val value = agg.path("ratingValue").asDouble(0.0)
        val count = agg.path("ratingCount").asInt(0)
        return if (value > 0.0 && value <= 10.0 && (count > 1 || value > 1.0)) value else null
    }

    private fun cleanOgTitle(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return s.replace(Regex("""^(?:Pel[ií]cula|Serie)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Gratis.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
