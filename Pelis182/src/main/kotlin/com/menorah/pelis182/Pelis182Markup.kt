package com.menorah.pelis182

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64

internal enum class Pelis182MediaKind {
    MOVIE,
    SERIES,
    ANIME,
    ANIME_MOVIE,
}

internal data class Pelis182CatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val kind: Pelis182MediaKind,
    val isSeries: Boolean,
)

internal data class Pelis182CatalogPage(
    val items: List<Pelis182CatalogCard>,
    val hasNext: Boolean,
    val nextUrl: String? = null,
)

internal data class Pelis182Actor(
    val name: String,
    val role: String?,
    val photoUrl: String?,
)

internal data class Pelis182EmbedSource(
    val label: String,
    val url: String,
)

internal data class Pelis182EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val embeds: List<Pelis182EmbedSource>,
    val posterUrl: String? = null,
    val isoDate: String? = null,
)

internal data class Pelis182Detail(
    val title: String,
    val seasonDisplayTitle: String?,
    val url: String,
    val kind: Pelis182MediaKind,
    val isSeries: Boolean,
    val seasonNumber: Int?,
    val comingSoon: Boolean,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<Pelis182Actor>,
    val trailerUrl: String?,
    val recommendations: List<Pelis182CatalogCard>,
    val episodes: List<Pelis182EpisodeItem>,
    val movieEmbeds: List<Pelis182EmbedSource>,
    val isoDate: String?,
)

internal data class Pelis182PlayerTarget(
    val pageUrl: String?,
    val embeds: List<Pelis182EmbedSource>,
)

internal object Pelis182Markup {
    const val MAIN_URL = "https://pelis182.net"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val JSON = jacksonObjectMapper()

    private val YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")
    private val FLOAT_REGEX = Regex("""([0-9]+(?:[.,][0-9]+)?)""")
    private val DURATION_MIN_REGEX = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val ISO_DURATION_REGEX = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""", RegexOption.IGNORE_CASE)
    private val SEASON_TEXT_REGEX = Regex("""(?i)\btemporada\s+(\d+)\b""")
    private val TRAILING_SEASON_REGEX = Regex("""(?i)\s*[–—\-:|]+\s*temporada\s+\d+.*$""")
    private val TRAILING_PAREN_YEAR_REGEX = Regex("""\s*\(\s*(?:19|20)\d{2}\s*\)\s*$""")
    private val EP_TX_REGEX = Regex("""(?i)\b[TS]?(\d+)\s*[xXeE]\s*(\d+)\b""")
    private val EP_SINGLE_REGEX = Regex("""(?i)(?:episodio|cap(?:itulo|ítulo)?|ep|e)\s*#?\s*(\d+)""")
    private val SPANISH_FULL_DATE_REGEX =
        Regex("""(\d{1,2})\s+de\s+([A-Za-záéíóúñÁÉÍÓÚÑ]+)\s+de\s+(\d{4})""", RegexOption.IGNORE_CASE)
    private val ISO_DATE_PREFIX_REGEX = Regex("""(\d{4}-\d{2}-\d{2})""")
    private val BG_URL_REGEX = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val TMDB_SIZE_REGEX = Regex("""/t/p/(?:w\d+|original)/""")
    private val JUICY_CODES_REGEX = Regex("""JuicyCodes\.Run\(\s*(['"])([A-Za-z0-9+/=\s]+)\1\s*\)""")
    private val JW_SOURCES_BLOCK_REGEX = Regex("""sources\s*:\s*\[([\s\S]*?)\]""")
    private val JW_TRACKS_BLOCK_REGEX = Regex("""tracks\s*:\s*\[([\s\S]*?)\]""")
    private val JW_FILE_REGEX = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+)["']""")
    private val OBJECT_BRACES_REGEX = Regex("""\{([^{}]+)\}""")
    private val LABEL_PROP_REGEX = Regex("""["']?label["']?\s*:\s*["']([^"']+)["']""")
    private val KIND_PROP_REGEX = Regex("""["']?kind["']?\s*:\s*["']([^"']+)["']""")
    private val TYPE_PROP_REGEX = Regex("""["']?type["']?\s*:\s*["']([^"']+)["']""")

    private val SPANISH_MONTHS = mapOf(
        "ene" to 1, "enero" to 1,
        "feb" to 2, "febrero" to 2,
        "mar" to 3, "marzo" to 3,
        "abr" to 4, "abril" to 4,
        "may" to 5, "mayo" to 5,
        "jun" to 6, "junio" to 6,
        "jul" to 7, "julio" to 7,
        "ago" to 8, "agosto" to 8,
        "sep" to 9, "sept" to 9, "set" to 9, "septiembre" to 9,
        "oct" to 10, "octubre" to 10,
        "nov" to 11, "noviembre" to 11,
        "dic" to 12, "diciembre" to 12,
    )

    fun normalizeAscii(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return Regex("""\p{InCombiningDiacriticalMarks}+""").replace(decomposed, "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    fun hostBaseUrl(url: String, fallback: String = MAIN_URL): String {
        return runCatching {
            val u = URI(url.trim())
            val scheme = u.scheme?.ifBlank { "https" } ?: "https"
            val auth = u.authority?.takeIf { it.isNotBlank() } ?: return@runCatching fallback
            "$scheme://$auth"
        }.getOrDefault(fallback)
    }

    fun resolveUrl(baseUrl: String, raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return upgradeHttpSiteUrl(trimmed)
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        if (trimmed.startsWith("./") || trimmed.startsWith("../")) {
            // Avoid returning browser "Saved Page" relative asset paths like ./Page_files/foo.jpg
            if (trimmed.contains("_files/")) return null
        }
        val origin = hostBaseUrl(baseUrl, MAIN_URL).trimEnd('/')
        if (trimmed.startsWith("/")) {
            return "$origin$trimmed"
        }
        if (trimmed.startsWith("?")) {
            val baseNoQuery = baseUrl.substringBefore('?')
            return "$baseNoQuery$trimmed"
        }
        return runCatching { URI(baseUrl.trimEnd('/') + "/").resolve(trimmed).toString() }
            .getOrElse { "$origin/$trimmed" }
    }

    private fun upgradeHttpSiteUrl(url: String): String {
        return if (url.startsWith("http://pelis182.net", ignoreCase = true)) {
            "https://" + url.substring("http://".length)
        } else {
            url
        }
    }

    fun upgradeTmdbImage(url: String?, targetSize: String = "w500"): String? {
        val clean = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
        if (!clean.contains("image.tmdb.org/t/p/")) return clean
        return TMDB_SIZE_REGEX.replace(clean, "/t/p/$targetSize/")
    }

    fun buildPagedUrl(basePathOrUrl: String, page: Int, mainUrl: String = MAIN_URL): String {
        val resolved = resolveUrl(mainUrl, basePathOrUrl) ?: mainUrl
        if (page <= 1) return resolved
        val qIdx = resolved.indexOf('?')
        val pathPart = (if (qIdx >= 0) resolved.substring(0, qIdx) else resolved).trimEnd('/')
        val queryPart = if (qIdx >= 0) resolved.substring(qIdx) else ""
        val cleanPath = pathPart.replace(Regex("""/page/\d+$"""), "")
        return "$cleanPath/page/$page/$queryPart"
    }

    fun buildSearchUrl(query: String, page: Int, mainUrl: String = MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val base = mainUrl.trimEnd('/')
        return if (page <= 1) {
            "$base/?s=$encoded"
        } else {
            "$base/page/$page/?s=$encoded"
        }
    }

    private fun pickSrcsetImage(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        val candidates = srcset.split(",")
            .mapNotNull { entry ->
                val parts = entry.trim().split(Regex("""\s+"""))
                val u = parts.firstOrNull()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@mapNotNull null
                val width = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                u to width
            }
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun extractImageUrl(img: Element?, baseUrl: String, preferredTmdbSize: String = "w500"): String? {
        if (img == null) return null
        val fromSrcset = pickSrcsetImage(img.attr("srcset"))
        val fromAttr = listOf("data-src", "data-lazy-src", "src")
            .firstNotNullOfOrNull { attr -> resolveUrl(baseUrl, img.attr(attr)) }
        val chosen = fromSrcset ?: fromAttr
        return upgradeTmdbImage(chosen, preferredTmdbSize) ?: chosen
    }

    fun stripSeasonSuffix(title: String): String {
        var clean = title.trim()
        clean = TRAILING_PAREN_YEAR_REGEX.replace(clean, "").trim()
        clean = TRAILING_SEASON_REGEX.replace(clean, "").trim()
        return clean.ifEmpty { title.trim() }
    }

    fun extractSeasonNumber(text: String?, url: String? = null): Int? {
        if (!text.isNullOrBlank()) {
            SEASON_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        if (!url.isNullOrBlank()) {
            Regex("""(?i)-temporada-(\d+)/?$""").find(url.trimEnd('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let { return it }
        }
        return null
    }

    fun parseCatalogCard(
        card: Element,
        baseUrl: String = MAIN_URL,
        defaultKind: Pelis182MediaKind = Pelis182MediaKind.MOVIE,
    ): Pelis182CatalogCard? {
        val anchor = card.selectFirst("a[href]") ?: return null
        val url = resolveUrl(baseUrl, anchor.attr("href")) ?: return null
        val titleEl = card.selectFirst("h3") ?: anchor
        val title = titleEl.text().trim().ifBlank { anchor.attr("aria-label").trim() }
        if (title.isBlank()) return null

        val imgEl = card.selectFirst("img")
        val posterUrl = extractImageUrl(imgEl, baseUrl, "w500")

        val badgeText = card.selectFirst(".p182-card__badge")?.text()?.trim().orEmpty()
        val hasSeasonMarker = extractSeasonNumber(title, url) != null
        val isSeries = badgeText.contains("SERIE", ignoreCase = true) ||
            defaultKind == Pelis182MediaKind.SERIES ||
            defaultKind == Pelis182MediaKind.ANIME ||
            hasSeasonMarker

        val resolvedKind = when {
            defaultKind == Pelis182MediaKind.ANIME || defaultKind == Pelis182MediaKind.ANIME_MOVIE ->
                if (isSeries) Pelis182MediaKind.ANIME else Pelis182MediaKind.ANIME_MOVIE
            isSeries -> Pelis182MediaKind.SERIES
            else -> Pelis182MediaKind.MOVIE
        }

        var year: Int? = null
        var rating: Double? = null
        for (span in card.select(".p182-card__meta span")) {
            val txt = span.text().trim()
            if (txt.isEmpty()) continue
            val ym = YEAR_REGEX.matchEntire(txt)
            if (ym != null && year == null) {
                year = ym.groupValues[1].toIntOrNull()
                continue
            }
            val fm = FLOAT_REGEX.find(txt)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            if (fm != null && fm in 0.1..10.0 && rating == null) {
                rating = fm
            }
        }

        return Pelis182CatalogCard(
            title = title,
            url = url,
            posterUrl = posterUrl,
            year = year,
            rating = rating,
            kind = resolvedKind,
            isSeries = isSeries,
        )
    }

    fun parseCatalogPage(
        doc: Document,
        baseUrl: String = MAIN_URL,
        defaultKind: Pelis182MediaKind = Pelis182MediaKind.MOVIE,
    ): Pelis182CatalogPage {
        val seenUrls = HashSet<String>()
        val items = mutableListOf<Pelis182CatalogCard>()
        val containerCards = doc.select("section.catalog-shell article.p182-card, .card-grid article.p182-card")
        val targetCards = if (containerCards.isNotEmpty()) {
            containerCards
        } else {
            doc.select("article.p182-card").filter { it.closest("section.related") == null }
        }
        for (card in targetCards) {
            val parsed = parseCatalogCard(card, baseUrl, defaultKind) ?: continue
            val normKey = parsed.url.trimEnd('/').lowercase()
            if (seenUrls.add(normKey)) {
                items.add(parsed)
            }
        }
        val nextAnchor = doc.selectFirst("a.next.page-numbers[href], link[rel=next][href]")
        val nextUrl = if (nextAnchor != null) resolveUrl(baseUrl, nextAnchor.attr("href")) else null
        return Pelis182CatalogPage(
            items = items,
            hasNext = nextUrl != null,
            nextUrl = nextUrl,
        )
    }

    fun parseSpanishOrIsoDate(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        ISO_DATE_PREFIX_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        val m = SPANISH_FULL_DATE_REGEX.find(text) ?: return null
        val day = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val monthKey = m.groupValues[2].lowercase()
        val month = SPANISH_MONTHS[monthKey] ?: return null
        val year = m.groupValues[3].toIntOrNull()?.takeIf { it in 1900..2100 } ?: return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun parseDurationMinutes(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        ISO_DURATION_REGEX.matchEntire(text)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull() ?: 0
            val mins = m.groupValues[2].toIntOrNull() ?: 0
            val total = hours * 60 + mins
            if (total > 0) return total
        }
        DURATION_MIN_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun parseJsonLdNodes(doc: Document): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        for (sc in doc.select("script[type=application/ld+json]")) {
            val raw = sc.data().ifBlank { sc.html() }.trim()
            if (raw.isEmpty()) continue
            val root = runCatching { JSON.readTree(raw) }.getOrNull() ?: continue
            collectLdObjects(root, out)
        }
        return out
    }

    private fun collectLdObjects(node: JsonNode, out: MutableList<JsonNode>) {
        when {
            node.isArray -> node.forEach { collectLdObjects(it, out) }
            node.isObject -> {
                val type = node.get("@type")?.asText().orEmpty()
                if (type.equals("Movie", true) || type.equals("TVSeries", true) || type.equals("TVSeason", true)) {
                    out.add(node)
                }
                val graph = node.get("@graph")
                if (graph != null) collectLdObjects(graph, out)
            }
        }
    }

    private fun parseIframeSrcFromAttribute(rawAttr: String, baseUrl: String): String? {
        val trimmed = rawAttr.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http", ignoreCase = true) || trimmed.startsWith("//")) {
            return resolveUrl(baseUrl, trimmed)
        }
        val frag = Jsoup.parseBodyFragment(trimmed, baseUrl)
        val iframe = frag.selectFirst("iframe") ?: return null
        val src = iframe.attr("data-uvpi-src").ifBlank { iframe.attr("src") }
        return resolveUrl(baseUrl, src)?.takeIf { isValidEmbedUrl(it) }
    }

    private fun isValidEmbedUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith("/saved_resource.html") || lower.startsWith("about:")) return false
        if (lower.contains("untimely-hello.com") || lower.contains("srv224.com")) return false
        return true
    }

    private fun parseEpisodeNumbers(label: String, defaultSeason: Int, fallbackEpisode: Int): Pair<Int, Int> {
        EP_TX_REGEX.find(label)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null) return s to e
        }
        EP_SINGLE_REGEX.find(label)?.let { m ->
            val e = m.groupValues[1].toIntOrNull()
            if (e != null) return defaultSeason to e
        }
        label.trim().toIntOrNull()?.let { e ->
            return defaultSeason to e
        }
        return defaultSeason to fallbackEpisode
    }

    private fun formatEpisodeTitle(rawLabel: String, season: Int, episode: Int): String {
        val clean = rawLabel.trim()
        if (clean.isEmpty() ||
            EP_TX_REGEX.matches(clean) ||
            clean.matches(Regex("""^\d+$"""))
        ) {
            return "Episodio $episode"
        }
        return clean
    }

    fun isMatchingSeasonCard(
        baseSeriesTitle: String,
        cardTitle: String,
        currentUrl: String? = null,
        cardUrl: String? = null,
    ): Boolean {
        val cardSeason = extractSeasonNumber(cardTitle, cardUrl) ?: return false
        if (cardSeason <= 0) return false
        val normBase = normalizeAscii(stripSeasonSuffix(baseSeriesTitle))
        val normCard = normalizeAscii(stripSeasonSuffix(cardTitle))
        if (normBase.isNotEmpty() && normBase == normCard) return true
        if (!currentUrl.isNullOrBlank() && !cardUrl.isNullOrBlank()) {
            val curSlug = URI(currentUrl).path.trim('/').substringBefore("-temporada-")
            val cardSlug = URI(cardUrl).path.trim('/').substringBefore("-temporada-")
            if (curSlug.isNotEmpty() && curSlug == cardSlug) return true
        }
        return false
    }

    fun parseDetail(
        doc: Document,
        pageUrl: String,
        baseUrl: String = MAIN_URL,
    ): Pelis182Detail? {
        val article = doc.selectFirst("article.watch-page") ?: doc.selectFirst("main#main-content") ?: return null
        val canonicalUrl = doc.selectFirst("link[rel=canonical][href]")
            ?.let { resolveUrl(baseUrl, it.attr("href")) }
            ?: resolveUrl(baseUrl, pageUrl)
            ?: pageUrl

        val ldNodes = parseJsonLdNodes(doc)
        val primaryLd = ldNodes.firstOrNull()

        val kicker = article.selectFirst(".watch-kicker")?.text()?.trim().orEmpty()
        val seasonText = article.selectFirst(".watch-season")?.text()?.trim()?.ifEmpty { null }
        val h1Text = article.selectFirst("h1.watch-title")?.text()?.trim()
            ?: article.selectFirst("h1")?.text()?.trim()
            ?: primaryLd?.get("name")?.asText()?.trim()
            ?: doc.title().substringBefore(" online").trim()
        if (h1Text.isBlank()) return null

        val cleanTitle = stripSeasonSuffix(h1Text)
        val seasonNumber = extractSeasonNumber(seasonText, canonicalUrl)
            ?: extractSeasonNumber(h1Text, canonicalUrl)

        val episodeRowButtons = article.select(".server-row.episode-row button.server-button[data-iframe]")
        val hasSeriesBreadcrumbs = article.select(".breadcrumbs a[href*='/category/series/']").isNotEmpty()
        val hasAnimeHint = article.select(".breadcrumbs a[href*='/category/anime/']").isNotEmpty() ||
            article.select(".watch-genres a[href*='/category/anime/']").isNotEmpty()

        val isSeries = kicker.equals("SERIE", ignoreCase = true) ||
            seasonNumber != null ||
            episodeRowButtons.isNotEmpty() ||
            hasSeriesBreadcrumbs ||
            primaryLd?.get("@type")?.asText().equals("TVSeason", true) ||
            primaryLd?.get("@type")?.asText().equals("TVSeries", true)

        val kind = when {
            hasAnimeHint && isSeries -> Pelis182MediaKind.ANIME
            hasAnimeHint -> Pelis182MediaKind.ANIME_MOVIE
            isSeries -> Pelis182MediaKind.SERIES
            else -> Pelis182MediaKind.MOVIE
        }

        val posterImg = article.selectFirst(".watch-heading__poster img")
        val posterUrl = extractImageUrl(posterImg, baseUrl, "w500")
            ?: primaryLd?.get("image")?.asText()?.let { upgradeTmdbImage(resolveUrl(baseUrl, it), "w500") }

        val uvpiPoster = article.selectFirst(".uvpi-poster[style]")
        val bgFromStyle = uvpiPoster?.attr("style")?.let { style ->
            BG_URL_REGEX.findAll(style)
                .mapNotNull { resolveUrl(baseUrl, it.groupValues[1]) }
                .firstOrNull()
        }
        val ldImage = primaryLd?.get("image")?.asText()?.let { resolveUrl(baseUrl, it) }
        val backgroundPosterUrl = bgFromStyle ?: upgradeTmdbImage(ldImage, "w1280") ?: posterUrl

        val watchMetaSpans = article.select(".watch-meta span")
        var year: Int? = null
        var durationMinutes: Int? = null
        for (span in watchMetaSpans) {
            if (span.hasClass("watch-rating") || span.closest(".watch-rating") != null) continue
            val t = span.text().trim()
            if (year == null) {
                YEAR_REGEX.matchEntire(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { year = it }
            }
            if (durationMinutes == null) {
                parseDurationMinutes(t)?.let { durationMinutes = it }
            }
        }
        if (durationMinutes == null) {
            durationMinutes = parseDurationMinutes(primaryLd?.get("duration")?.asText())
        }

        val ratingEl = article.selectFirst(".watch-rating")
        val rating = ratingEl?.text()
            ?.substringBefore("/")
            ?.let { FLOAT_REGEX.find(it)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() }
            ?.takeIf { it in 0.1..10.0 }

        val genres = linkedSetOf<String>()
        for (g in article.select(".watch-genres a, .watch-genres span")) {
            val txt = g.text().trim()
            if (txt.isNotEmpty()) genres.add(txt)
        }
        val ldGenres = primaryLd?.get("genre")
        if (ldGenres != null) {
            if (ldGenres.isArray) {
                ldGenres.forEach { n -> n.asText().trim().takeIf { it.isNotEmpty() }?.let { genres.add(it) } }
            } else if (ldGenres.isTextual) {
                ldGenres.asText().trim().takeIf { it.isNotEmpty() }?.let { genres.add(it) }
            }
        }

        val plotEl = article.selectFirst("#sinopsis p.p182ms-text") ?: article.selectFirst("#sinopsis p")
        val plot = plotEl?.text()?.trim()?.ifEmpty { null }
            ?: primaryLd?.get("description")?.asText()?.trim()?.ifEmpty { null }

        var isoDate: String? = null
        for (dd in article.select(".entry-facts dd")) {
            parseSpanishOrIsoDate(dd.text())?.let {
                isoDate = it
                break
            }
        }
        if (isoDate == null) {
            isoDate = parseSpanishOrIsoDate(primaryLd?.get("dateCreated")?.asText())
                ?: parseSpanishOrIsoDate(primaryLd?.get("datePublished")?.asText())
        }
        if (year == null && isoDate != null) {
            year = isoDate.substring(0, 4).toIntOrNull()
        }

        val actors = mutableListOf<Pelis182Actor>()
        val seenActors = HashSet<String>()
        for (person in article.select(".entry-cast__person")) {
            val actorName = person.selectFirst("b")?.text()?.trim().orEmpty()
            if (actorName.isEmpty() || !seenActors.add(actorName.lowercase())) continue
            val role = person.selectFirst("small")?.text()?.trim()?.ifEmpty { null }
            val photoUrl = extractImageUrl(person.selectFirst("img"), baseUrl, "w185")
            actors.add(Pelis182Actor(actorName, role, photoUrl))
        }
        if (actors.isEmpty()) {
            val ldActors = primaryLd?.get("actor")
            if (ldActors != null && ldActors.isArray) {
                for (a in ldActors) {
                    val actorName = a.get("name")?.asText()?.trim().orEmpty()
                    if (actorName.isEmpty() || !seenActors.add(actorName.lowercase())) continue
                    actors.add(Pelis182Actor(actorName, null, null))
                }
            }
        }

        val trailerUrl = article.select("iframe[data-src], iframe[src], a[href*='youtu']")
            .asSequence()
            .mapNotNull { el ->
                val raw = if (el.tagName().equals("a", true)) el.attr("href")
                else el.attr("data-src").ifBlank { el.attr("src") }
                resolveUrl(baseUrl, raw)
            }
            .firstOrNull { it.contains("youtube.com", true) || it.contains("youtu.be", true) }

        val recommendations = article.select(".related article.p182-card")
            .mapNotNull { parseCatalogCard(it, baseUrl, if (isSeries) Pelis182MediaKind.SERIES else Pelis182MediaKind.MOVIE) }

        val theaterIframeUrl = article.select("#p182-theater iframe")
            .asSequence()
            .mapNotNull { f ->
                val raw = f.attr("data-uvpi-src").ifBlank { f.attr("src") }
                resolveUrl(baseUrl, raw)?.takeIf { isValidEmbedUrl(it) }
            }
            .firstOrNull()

        val episodes = mutableListOf<Pelis182EpisodeItem>()
        val movieEmbeds = mutableListOf<Pelis182EmbedSource>()

        if (isSeries) {
            val effSeason = seasonNumber ?: 1
            if (episodeRowButtons.isNotEmpty()) {
                val seenEpPairs = HashSet<Pair<Int, Int>>()
                episodeRowButtons.forEachIndexed { idx, btn ->
                    val label = btn.text().trim()
                    val iframeUrl = parseIframeSrcFromAttribute(btn.attr("data-iframe"), baseUrl)
                        ?: return@forEachIndexed
                    var (epSeason, epNum) = parseEpisodeNumbers(label, effSeason, idx + 1)
                    if (!seenEpPairs.add(epSeason to epNum)) {
                        epNum = idx + 1
                        while (!seenEpPairs.add(epSeason to epNum)) epNum++
                    }
                    episodes.add(
                        Pelis182EpisodeItem(
                            season = epSeason,
                            episode = epNum,
                            title = formatEpisodeTitle(label, epSeason, epNum),
                            embeds = listOf(Pelis182EmbedSource("Pelis182", iframeUrl)),
                            posterUrl = posterUrl,
                            isoDate = isoDate,
                        ),
                    )
                }
            } else if (theaterIframeUrl != null) {
                episodes.add(
                    Pelis182EpisodeItem(
                        season = effSeason,
                        episode = 1,
                        title = "Episodio 1",
                        embeds = listOf(Pelis182EmbedSource("Pelis182", theaterIframeUrl)),
                        posterUrl = posterUrl,
                        isoDate = isoDate,
                    ),
                )
            }
        } else {
            val seenMovieUrls = HashSet<String>()
            val serverButtons = article.select("#p182-theater .server-row:not(.episode-row) button.server-button[data-iframe]")
            for (btn in serverButtons) {
                val iframeUrl = parseIframeSrcFromAttribute(btn.attr("data-iframe"), baseUrl) ?: continue
                if (seenMovieUrls.add(iframeUrl)) {
                    val lbl = btn.text().trim().ifEmpty { "Pelis182" }
                    movieEmbeds.add(Pelis182EmbedSource(lbl, iframeUrl))
                }
            }
            if (theaterIframeUrl != null && seenMovieUrls.add(theaterIframeUrl)) {
                movieEmbeds.add(Pelis182EmbedSource("Pelis182", theaterIframeUrl))
            }
        }

        val comingSoon = if (isSeries) episodes.isEmpty() else movieEmbeds.isEmpty()

        return Pelis182Detail(
            title = cleanTitle,
            seasonDisplayTitle = seasonText,
            url = canonicalUrl,
            kind = kind,
            isSeries = isSeries,
            seasonNumber = seasonNumber,
            comingSoon = comingSoon,
            posterUrl = posterUrl,
            backgroundPosterUrl = backgroundPosterUrl,
            plot = plot,
            year = year,
            durationMinutes = durationMinutes,
            rating = rating,
            genres = genres.toList(),
            actors = actors,
            trailerUrl = trailerUrl,
            recommendations = recommendations,
            episodes = episodes,
            movieEmbeds = movieEmbeds,
            isoDate = isoDate,
        )
    }

    fun encodePlayerTarget(pageUrl: String, embeds: List<Pelis182EmbedSource>): String {
        val payload = mapOf(
            "pageUrl" to pageUrl,
            "embeds" to embeds.map { mapOf("label" to it.label, "url" to it.url) },
        )
        return JSON.writeValueAsString(payload)
    }

    fun parsePlayerTarget(raw: String, baseUrl: String = MAIN_URL): Pelis182PlayerTarget {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            val node = runCatching { JSON.readTree(trimmed) }.getOrNull()
            if (node != null && node.isObject) {
                val pageUrl = resolveUrl(baseUrl, node.get("pageUrl")?.asText())
                val embeds = mutableListOf<Pelis182EmbedSource>()
                val embedsNode = node.get("embeds")
                if (embedsNode != null && embedsNode.isArray) {
                    for (item in embedsNode) {
                        val u = item.get("url")?.asText()?.trim().orEmpty()
                        if (!u.startsWith("http", ignoreCase = true)) continue
                        val l = item.get("label")?.asText()?.trim()?.ifEmpty { "Pelis182" } ?: "Pelis182"
                        embeds.add(Pelis182EmbedSource(l, u))
                    }
                }
                return Pelis182PlayerTarget(pageUrl = pageUrl, embeds = embeds)
            }
        }
        val direct = resolveUrl(baseUrl, trimmed)
        return if (direct != null) {
            if (direct.contains("paulinito.com", ignoreCase = true)) {
                Pelis182PlayerTarget(pageUrl = "${baseUrl.trimEnd('/')}/", embeds = listOf(Pelis182EmbedSource("Pelis182", direct)))
            } else {
                Pelis182PlayerTarget(pageUrl = direct, embeds = emptyList())
            }
        } else {
            Pelis182PlayerTarget(pageUrl = null, embeds = emptyList())
        }
    }

    fun decodeJuicyCodesFromHtml(html: String): String {
        val sb = StringBuilder(html)
        for (m in JUICY_CODES_REGEX.findAll(html)) {
            val b64 = m.groupValues[2].replace(Regex("""[^A-Za-z0-9+/=]"""), "")
            if (b64.isBlank()) continue
            val decoded = runCatching {
                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                sb.append('\n').append(decoded)
            }
        }
        return sb.toString()
    }

    data class PaulinitoSource(val file: String, val label: String?, val type: String?)
    data class PaulinitoTrack(val file: String, val label: String, val kind: String?)
    data class PaulinitoConfig(val sources: List<PaulinitoSource>, val tracks: List<PaulinitoTrack>)

    fun parsePaulinitoConfig(html: String): PaulinitoConfig {
        val searchCorpus = decodeJuicyCodesFromHtml(html)
        val sources = mutableListOf<PaulinitoSource>()
        val seenSrc = HashSet<String>()

        for (blockMatch in JW_SOURCES_BLOCK_REGEX.findAll(searchCorpus)) {
            val block = blockMatch.groupValues[1]
            val jsonCandidate = runCatching { JSON.readTree("[$block]") }.getOrNull()
            if (jsonCandidate != null && jsonCandidate.isArray) {
                for (item in jsonCandidate) {
                    val f = item.get("file")?.asText()?.trim().orEmpty()
                    if (f.startsWith("http", ignoreCase = true) && seenSrc.add(f)) {
                        sources.add(
                            PaulinitoSource(
                                file = f,
                                label = item.get("label")?.asText()?.trim()?.ifEmpty { null },
                                type = item.get("type")?.asText()?.trim()?.ifEmpty { null },
                            ),
                        )
                    }
                }
            } else {
                for (obj in OBJECT_BRACES_REGEX.findAll(block)) {
                    val inner = obj.groupValues[1]
                    val f = JW_FILE_REGEX.find(inner)?.groupValues?.getOrNull(1)?.trim() ?: continue
                    if (seenSrc.add(f)) {
                        sources.add(
                            PaulinitoSource(
                                file = f,
                                label = LABEL_PROP_REGEX.find(inner)?.groupValues?.getOrNull(1),
                                type = TYPE_PROP_REGEX.find(inner)?.groupValues?.getOrNull(1),
                            ),
                        )
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            for (m in Regex("""["']?(https?://[^"'\s]+\.(?:m3u8|mp4)(?:\?[^"'\s]*)?)["']?""", RegexOption.IGNORE_CASE).findAll(searchCorpus)) {
                val f = m.groupValues[1]
                if (seenSrc.add(f)) {
                    sources.add(PaulinitoSource(f, null, null))
                }
            }
        }

        val tracks = mutableListOf<PaulinitoTrack>()
        val seenTrk = HashSet<String>()
        for (blockMatch in JW_TRACKS_BLOCK_REGEX.findAll(searchCorpus)) {
            val block = blockMatch.groupValues[1]
            val jsonCandidate = runCatching { JSON.readTree("[$block]") }.getOrNull()
            if (jsonCandidate != null && jsonCandidate.isArray) {
                for (item in jsonCandidate) {
                    val f = item.get("file")?.asText()?.trim().orEmpty()
                    val kind = item.get("kind")?.asText()?.trim()
                    if (!f.startsWith("http", ignoreCase = true)) continue
                    if (kind.equals("thumbnails", ignoreCase = true) || f.contains("thumbnail.vtt", ignoreCase = true)) {
                        continue
                    }
                    if (seenTrk.add(f)) {
                        val rawLabel = item.get("label")?.asText()?.trim().orEmpty()
                        tracks.add(PaulinitoTrack(f, normalizeSubtitleLabel(rawLabel), kind))
                    }
                }
            } else {
                for (obj in OBJECT_BRACES_REGEX.findAll(block)) {
                    val inner = obj.groupValues[1]
                    val f = JW_FILE_REGEX.find(inner)?.groupValues?.getOrNull(1)?.trim() ?: continue
                    val kind = KIND_PROP_REGEX.find(inner)?.groupValues?.getOrNull(1)
                    if (kind.equals("thumbnails", ignoreCase = true) || f.contains("thumbnail.vtt", ignoreCase = true)) {
                        continue
                    }
                    if (seenTrk.add(f)) {
                        val rawLabel = LABEL_PROP_REGEX.find(inner)?.groupValues?.getOrNull(1).orEmpty()
                        tracks.add(PaulinitoTrack(f, normalizeSubtitleLabel(rawLabel), kind))
                    }
                }
            }
        }

        return PaulinitoConfig(sources = sources, tracks = tracks)
    }

    fun normalizeSubtitleLabel(rawLabel: String): String {
        val clean = rawLabel.trim()
        if (clean.isEmpty() || clean.matches(Regex("""(?i)^sub(?:titles?|t[íi]tulos?)?(?:[-_\s]*\d+)?$"""))) {
            return "Español ($clean)".takeIf { clean.isNotEmpty() } ?: "Español"
        }
        return when (clean.lowercase()) {
            "es", "spa", "es-419", "lat", "latino" -> "Español"
            "en", "eng" -> "English"
            else -> clean
        }
    }
}
