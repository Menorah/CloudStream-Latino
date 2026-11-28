package com.menorah.gnulahd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64

internal enum class GnulaMediaKind {
    MOVIE,
    SERIES,
    ANIME,
    ANIME_MOVIE,
}

internal data class GnulaCatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val kind: GnulaMediaKind,
    val languages: List<String> = emptyList(),
)

internal data class GnulaCatalogPage(
    val items: List<GnulaCatalogCard>,
    val hasNext: Boolean,
    val nextUrl: String? = null,
)

internal data class GnulaActor(
    val name: String,
    val role: String?,
    val photoUrl: String?,
)

internal data class GnulaEpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val postId: String?,
    val token: String?,
    val posterUrl: String?,
    val durationMinutes: Int?,
    val overview: String?,
    val isoDate: String?,
)

internal data class GnulaDetail(
    val title: String,
    val url: String,
    val kind: GnulaMediaKind,
    val comingSoon: Boolean,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<GnulaActor>,
    val trailerUrl: String?,
    val recommendations: List<GnulaCatalogCard>,
    val episodes: List<GnulaEpisodeItem>,
    val playerId: String?,
    val playerToken: String?,
    val parentSeriesUrl: String?,
)

internal data class GnulaPlayerServer(
    val title: String,
    val src: String,
)

internal data class GnulaPlayerLang(
    val label: String,
    val flag: String,
    val servers: List<GnulaPlayerServer>,
)

internal data class GnulaPlayerDownload(
    val name: String,
    val language: String,
    val quality: String?,
    val url: String,
)

internal data class GnulaPlayerPayload(
    val langs: List<GnulaPlayerLang>,
    val downloads: List<GnulaPlayerDownload>,
)

internal data class GnulaPlayerTarget(
    val playerId: String?,
    val token: String?,
    val pageUrl: String?,
)

internal object GnulaHDMarkup {
    const val MAIN_URL = "https://ww3.gnulahd.nu"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val XOR_KEY = byteArrayOf('g'.code.toByte(), 'N'.code.toByte(), '7'.code.toByte(), 'd'.code.toByte())
    private val JSON = jacksonObjectMapper()

    private val YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")
    private val FLOAT_REGEX = Regex("""([0-9]+(?:[.,][0-9]+)?)""")
    private val BG_URL_REGEX = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val EP_SE_NUM_REGEX = Regex("""(\d+)\s*[xX×]\s*(\d+)""")
    private val EP_HREF_REGEX = Regex("""-(\d+)x(\d+)/?$""", RegexOption.IGNORE_CASE)
    private val DURATION_HR_MIN_REGEX = Regex("""(\d+)\s*h(?:r|oras?)?\.?\s*(?:(\d+)\s*m(?:in)?\.?)?""", RegexOption.IGNORE_CASE)
    private val DURATION_MIN_REGEX = Regex("""(\d+)\s*m(?:in)?\.?""", RegexOption.IGNORE_CASE)
    private val SPANISH_DATE_REGEX = Regex("""(\d{1,2})\s+([A-Za-záéíóúñÁÉÍÓÚÑ]+)\s+(\d{4})""")
    private val INLINE_PID_TOK_REGEX = Regex(
        """_gnrdPid\s*=\s*(\d+)\s*,\s*_gnrdTok\s*=\s*["']([^"']+)["']""",
    )
    private val INLINE_PID_ALT_REGEX = Regex("""_gnrdPid\s*=\s*(\d+)""")
    private val INLINE_TOK_ALT_REGEX = Regex("""_gnrdTok\s*=\s*["']([^"']+)["']""")

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
        return Regex("""\p{InCombiningDiacriticalMarks}+""").replace(decomposed, "").lowercase().trim()
    }

    fun hostBaseUrl(url: String, fallback: String = MAIN_URL): String {
        return runCatching {
            val u = URI(url.trim())
            val scheme = u.scheme?.ifBlank { "https" } ?: "https"
            val auth = u.authority?.takeIf { it.isNotBlank() } ?: return@runCatching fallback
            "$scheme://$auth"
        }.getOrDefault(fallback)
    }

    fun stripFragment(url: String): String = url.trim().substringBefore('#').trim()

    fun resolveUrl(baseUrl: String, raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
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

    fun buildPagedUrl(basePathOrUrl: String, page: Int, mainUrl: String = MAIN_URL): String {
        val resolved = resolveUrl(mainUrl, basePathOrUrl) ?: mainUrl
        if (page <= 1) return resolved
        val sep = if ('?' in resolved) '&' else '?'
        return "${resolved}${sep}page=$page"
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

    fun buildQuickSearchUrl(query: String, mainUrl: String = MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return "${mainUrl.trimEnd('/')}/wp-json/gnrd/v1/search?q=$encoded"
    }

    fun buildPlayerApiUrl(playerId: String, token: String, mainUrl: String = MAIN_URL): String {
        val encodedTok = URLEncoder.encode(token.trim(), StandardCharsets.UTF_8.name())
        return "${mainUrl.trimEnd('/')}/wp-json/gnrd/v1/player?id=${playerId.trim()}&t=$encodedTok"
    }

    fun encodePlayerTarget(playerId: String?, token: String?, pageUrl: String?): String {
        if (playerId.isNullOrBlank() || token.isNullOrBlank()) {
            return pageUrl.orEmpty()
        }
        val base = buildPlayerApiUrl(playerId, token, MAIN_URL)
        val ref = pageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        val encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8.name())
        return "$base&ref=$encodedRef"
    }

    fun parsePlayerTarget(data: String, mainUrl: String = MAIN_URL): GnulaPlayerTarget {
        val trimmed = data.trim()
        if (!trimmed.contains("/wp-json/gnrd/v1/player", ignoreCase = true)) {
            val resolved = resolveUrl(mainUrl, trimmed) ?: trimmed
            return GnulaPlayerTarget(playerId = null, token = null, pageUrl = resolved)
        }
        val query = trimmed.substringAfter('?', "")
        val params = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            params[k] = runCatching { URLDecoder.decode(v, StandardCharsets.UTF_8.name()) }.getOrDefault(v)
        }
        return GnulaPlayerTarget(
            playerId = params["id"]?.takeIf { it.isNotBlank() },
            token = params["t"]?.takeIf { it.isNotBlank() },
            pageUrl = params["ref"]?.let { resolveUrl(mainUrl, it) ?: it },
        )
    }

    fun decodeGnrdPayload(encodedBase64: String): String? {
        val cleaned = encodedBase64.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return null
        val standard = cleaned.replace('-', '+').replace('_', '/')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        val raw = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            out[i] = (raw[i].toInt() xor XOR_KEY[i and 3].toInt()).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    fun extractPlayerApiCiphertext(apiResponseText: String): String? {
        val trimmed = apiResponseText.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val node = runCatching { JSON.readTree(trimmed) }.getOrNull() ?: return null
            val p = node.get("p")?.asText()?.trim()
            if (!p.isNullOrEmpty()) return p
            return null
        }
        return trimmed.removeSurrounding("\"")
    }

    fun normalizeLanguageLabel(rawLabel: String?, rawFlag: String? = null): String {
        val norm = normalizeAscii(rawLabel.orEmpty())
        val flag = rawFlag.orEmpty()
        return when {
            norm.contains("latino") || norm == "lat" || flag.contains("🇲🇽") -> "Latino"
            norm.contains("castellano") || norm.contains("espana") || norm == "cast" || flag.contains("🇪🇸") -> "Castellano"
            norm.contains("subtitul") || norm.contains("sub") || flag.contains("🇺🇸") -> "Subtitulado"
            !rawLabel.isNullOrBlank() -> rawLabel.trim()
            else -> "Latino"
        }
    }

    fun parsePlayerPayload(decodedJson: String): GnulaPlayerPayload? {
        val root = runCatching { JSON.readTree(decodedJson) }.getOrNull() ?: return null
        val langs = mutableListOf<GnulaPlayerLang>()
        val langsNode = root.get("langs")
        if (langsNode != null && langsNode.isArray) {
            for (langNode in langsNode) {
                val label = langNode.path("label").asText("").trim()
                val flag = langNode.path("flag").asText("").trim()
                val normLang = normalizeLanguageLabel(label, flag)
                val servers = mutableListOf<GnulaPlayerServer>()
                val srvsNode = langNode.get("servers")
                if (srvsNode != null && srvsNode.isArray) {
                    for (srv in srvsNode) {
                        val rawSrc = srv.path("src").asText("").trim()
                        val resolvedSrc = resolveUrl(MAIN_URL, rawSrc) ?: rawSrc
                        if (!resolvedSrc.startsWith("http", ignoreCase = true)) continue
                        val title = srv.path("title").asText("").trim().ifEmpty { hostBaseUrl(resolvedSrc, "") }
                        servers += GnulaPlayerServer(title = title, src = resolvedSrc)
                    }
                }
                if (servers.isNotEmpty()) {
                    langs += GnulaPlayerLang(label = normLang, flag = flag, servers = servers)
                }
            }
        }

        val downloads = mutableListOf<GnulaPlayerDownload>()
        val dlNode = root.get("dl")
        if (dlNode != null && dlNode.isArray) {
            for (d in dlNode) {
                val u = d.path("url").asText("").trim()
                if (!u.startsWith("http", ignoreCase = true)) continue
                val dName = d.path("name").asText("").trim().ifEmpty { "Descarga" }
                val dLang = normalizeLanguageLabel(d.path("lang").asText("").trim())
                val dQual = d.path("qual").asText("").trim().takeIf { it.isNotEmpty() }
                downloads += GnulaPlayerDownload(name = dName, language = dLang, quality = dQual, url = u)
            }
        }

        if (langs.isEmpty() && downloads.isEmpty()) return null
        return GnulaPlayerPayload(langs = langs, downloads = downloads)
    }

    fun inferKindFromHint(text: String?, defaultKind: GnulaMediaKind = GnulaMediaKind.MOVIE): GnulaMediaKind {
        val norm = normalizeAscii(text.orEmpty())
        return when {
            norm.contains("anime") && (norm.contains("pelicula") || norm.contains("movie")) -> GnulaMediaKind.ANIME_MOVIE
            norm.contains("anime") -> GnulaMediaKind.ANIME
            norm.contains("serie") || norm.contains("tvseries") || norm.contains("tv series") -> GnulaMediaKind.SERIES
            norm.contains("pelicula") || norm.contains("movie") -> GnulaMediaKind.MOVIE
            else -> defaultKind
        }
    }

    fun parseCardElement(el: Element, mainUrl: String = MAIN_URL, kindHint: GnulaMediaKind = GnulaMediaKind.MOVIE): GnulaCatalogCard? {
        val rawHref = el.attr("href").trim()
        val url = resolveUrl(mainUrl, rawHref) ?: return null

        val title = el.selectFirst("span.gnrd-card-title, h3.gnrd-card-title, .gnrd-card-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: el.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val img: Element? = el.selectFirst("img")
        val rawPoster = if (img != null) {
            img.attr("data-src").trim()
                .ifEmpty { img.attr("data-lazy-src").trim() }
                .ifEmpty { img.attr("src").trim() }
        } else {
            null
        }
        val posterUrl = resolveUrl(mainUrl, rawPoster)

        val ratingText = el.selectFirst("span.gnrd-rating, .gnrd-cm-rating")?.text().orEmpty()
        val rating = FLOAT_REGEX.find(ratingText)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.1..10.0 }

        val yearTokens = el.select(".gnrd-card-metaline span, .gnrd-card-metaline, .gnest-ecard-date, .gnrd-card-year")
            .joinToString(" ") { it.text() }
        val year = YEAR_REGEX.find(yearTokens)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val langs = el.select(".gnrd-langs span.gnrd-lang")
            .mapNotNull { it.text().trim().uppercase().takeIf { t -> t.isNotEmpty() } }

        val tipoAttr = el.attr("data-tipo").trim().takeIf { it.isNotEmpty() }
            ?: el.selectFirst(".gnest-ecard-dias")?.text()?.trim()
        val kind = if (!tipoAttr.isNullOrBlank()) {
            inferKindFromHint(tipoAttr, kindHint)
        } else {
            kindHint
        }

        return GnulaCatalogCard(
            title = title,
            url = url,
            posterUrl = posterUrl,
            year = year,
            rating = rating,
            kind = kind,
            languages = langs,
        )
    }

    fun parseCards(elements: Iterable<Element>, mainUrl: String = MAIN_URL, kindHint: GnulaMediaKind = GnulaMediaKind.MOVIE): List<GnulaCatalogCard> {
        val seen = linkedMapOf<String, GnulaCatalogCard>()
        for (el in elements) {
            val card = parseCardElement(el, mainUrl, kindHint) ?: continue
            val key = card.url.trimEnd('/')
            if (!seen.containsKey(key)) {
                seen[key] = card
            }
        }
        return seen.values.toList()
    }

    fun parseHomeTrendingWeek(doc: Document, mainUrl: String = MAIN_URL): List<GnulaCatalogCard> =
        parseCards(doc.select(".gnrd-trend-pane[data-pane=sem] a.gnrd-card"), mainUrl)

    fun parseHomeTrendingToday(doc: Document, mainUrl: String = MAIN_URL): List<GnulaCatalogCard> =
        parseCards(doc.select(".gnrd-trend-pane[data-pane=hoy] a.gnrd-card"), mainUrl)

    fun parseCatalogPage(doc: Document, mainUrl: String = MAIN_URL, kindHint: GnulaMediaKind = GnulaMediaKind.MOVIE): GnulaCatalogPage {
        val primary = doc.select("main a.gnrd-card, .gnrd-grid a.gnrd-card, .gnest-grid a.gnrd-card")
        val elements = if (primary.isNotEmpty()) {
            primary
        } else {
            doc.select("a.gnrd-card").filter { el ->
                el.parents().none { p -> p.hasClass("gnrd-rail") || p.id() == "rel-rail" || p.tagName() == "header" || p.tagName() == "footer" }
            }
        }
        val items = parseCards(elements, mainUrl, kindHint)
        val moreLink = doc.selectFirst("div.gnrd-more-wrap a.gnrd-loadmore[href], a.gnrd-loadmore[href], a.next.page-numbers[href]")
        val nextUrl = moreLink?.attr("href")?.let { resolveUrl(mainUrl, it) }
        return GnulaCatalogPage(
            items = items,
            hasNext = nextUrl != null,
            nextUrl = nextUrl,
        )
    }

    fun parseQuickSearchJson(jsonText: String, mainUrl: String = MAIN_URL): List<GnulaCatalogCard> {
        val root = runCatching { JSON.readTree(jsonText.trim()) }.getOrNull() ?: return emptyList()
        val arrayNode: JsonNode? = when {
            root.isArray -> root
            root.has("results") && root.get("results").isArray -> root.get("results")
            root.has("items") && root.get("items").isArray -> root.get("items")
            root.has("data") && root.get("data").isArray -> root.get("data")
            else -> null
        }
        if (arrayNode == null) return emptyList()

        val seen = linkedMapOf<String, GnulaCatalogCard>()
        for (item in arrayNode) {
            val rawUrl = (item.get("url") ?: item.get("link") ?: item.get("permalink"))?.asText()?.trim()
            val url = resolveUrl(mainUrl, rawUrl) ?: continue
            val title = (item.get("title") ?: item.get("name"))?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val rawPoster = (item.get("poster") ?: item.get("img") ?: item.get("image") ?: item.get("thumb"))?.asText()?.trim()
            val poster = resolveUrl(mainUrl, rawPoster)
            val year = item.get("year")?.let { node ->
                if (node.isNumber) node.asInt() else YEAR_REGEX.find(node.asText(""))?.groupValues?.getOrNull(1)?.toIntOrNull()
            }?.takeIf { it in 1900..2100 }
            val rating = (item.get("rating") ?: item.get("score"))?.let { node ->
                if (node.isNumber) node.asDouble() else FLOAT_REGEX.find(node.asText(""))?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            }?.takeIf { it in 0.1..10.0 }
            val typeRaw = (item.get("type") ?: item.get("post_type") ?: item.get("kind") ?: item.get("eyebrow"))?.asText()
            val kind = inferKindFromHint(typeRaw, GnulaMediaKind.MOVIE)
            val key = url.trimEnd('/')
            if (!seen.containsKey(key)) {
                seen[key] = GnulaCatalogCard(
                    title = title,
                    url = url,
                    posterUrl = poster,
                    year = year,
                    rating = rating,
                    kind = kind,
                )
            }
        }
        return seen.values.toList()
    }

    fun parseDurationMinutes(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        DURATION_HR_MIN_REGEX.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val mins = m.groupValues[2].toIntOrNull() ?: 0
            val total = h * 60 + mins
            if (total > 0) return total
        }
        DURATION_MIN_REGEX.find(text)?.let { m ->
            val mins = m.groupValues[1].toIntOrNull()
            if (mins != null && mins > 0) return mins
        }
        return text.toIntOrNull()?.takeIf { it > 0 }
    }

    fun parseSpanishDateToIso(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val m = SPANISH_DATE_REGEX.find(text) ?: return null
        val day = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val monthKey = normalizeAscii(m.groupValues[2])
        val month = SPANISH_MONTHS[monthKey] ?: SPANISH_MONTHS[monthKey.take(3)] ?: return null
        val year = m.groupValues[3].toIntOrNull()?.takeIf { it in 1900..2100 } ?: return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun extractBackgroundUrl(style: String?, mainUrl: String): String? {
        if (style.isNullOrBlank()) return null
        val raw = BG_URL_REGEX.find(style)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return resolveUrl(mainUrl, raw)
    }

    private fun extractJsonLdObjects(doc: Document): List<JsonNode> {
        val nodes = mutableListOf<JsonNode>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val txt = script.data().trim().ifEmpty { script.html().trim() }
            if (txt.isEmpty()) continue
            val root = runCatching { JSON.readTree(txt) }.getOrNull() ?: continue
            when {
                root.isObject -> {
                    val graph = root.get("@graph")
                    if (graph != null && graph.isArray) {
                        for (g in graph) if (g.isObject) nodes.add(g)
                    } else {
                        nodes.add(root)
                    }
                }
                root.isArray -> {
                    for (item in root) if (item.isObject) nodes.add(item)
                }
            }
        }
        return nodes
    }

    fun parseEpisodeCards(doc: Document, mainUrl: String = MAIN_URL): List<GnulaEpisodeItem> {
        val raw = mutableListOf<GnulaEpisodeItem>()
        for (el in doc.select("a.gnrd-epc")) {
            val href = resolveUrl(mainUrl, el.attr("href")) ?: continue
            val badgeText = el.selectFirst(".gnrd-epc-n")?.text().orEmpty()
            val badgeMatch = EP_SE_NUM_REGEX.find(badgeText)
            val hrefMatch = EP_HREF_REGEX.find(href)

            val attrS = el.attr("data-s").trim().toIntOrNull()?.takeIf { it > 0 }
            val attrE = el.attr("data-e").trim().toIntOrNull()?.takeIf { it > 0 }

            val badgeS = badgeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            val badgeE = badgeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }

            val hrefS = hrefMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            val hrefE = hrefMatch?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }

            val season = attrS ?: badgeS ?: hrefS ?: 1
            val episode = attrE ?: badgeE ?: hrefE ?: continue

            val title = el.selectFirst(".gnrd-epc-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Episodio $episode"
            val poster = extractBackgroundUrl(el.selectFirst(".gnrd-epc-thumb")?.attr("style"), mainUrl)
            val durMins = parseDurationMinutes(el.selectFirst(".gnrd-epc-dur")?.text())
            val ov = el.selectFirst("p.gnrd-epc-ov, .gnrd-epc-ov")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val isoDate = parseSpanishDateToIso(el.selectFirst(".gnrd-epc-date")?.text())
            val pid = el.attr("data-id").trim().takeIf { it.isNotEmpty() }
            val tok = el.attr("data-t").trim().takeIf { it.isNotEmpty() }

            raw += GnulaEpisodeItem(
                season = season,
                episode = episode,
                title = title,
                url = href,
                postId = pid,
                token = tok,
                posterUrl = poster,
                durationMinutes = durMins,
                overview = ov,
                isoDate = isoDate,
            )
        }

        val sorted = raw.sortedWith(compareBy<GnulaEpisodeItem> { it.season }.thenBy { it.episode })
        val dedup = linkedMapOf<Pair<Int, Int>, GnulaEpisodeItem>()
        for (ep in sorted) {
            val key = ep.season to ep.episode
            val prev = dedup[key]
            if (prev == null || (prev.token.isNullOrBlank() && !ep.token.isNullOrBlank())) {
                dedup[key] = ep
            }
        }
        return dedup.values.toList()
    }

    fun extractInlinePlayerCredentials(html: String): Pair<String, String>? {
        INLINE_PID_TOK_REGEX.find(html)?.let { m ->
            val pid = m.groupValues[1].trim()
            val tok = m.groupValues[2].trim()
            if (pid.isNotEmpty() && tok.isNotEmpty()) return pid to tok
        }
        val pid = INLINE_PID_ALT_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        val tok = INLINE_TOK_ALT_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!pid.isNullOrEmpty() && !tok.isNullOrEmpty()) return pid to tok
        return null
    }

    fun parseDetail(doc: Document, pageUrl: String, mainUrl: String = MAIN_URL): GnulaDetail? {
        val html = doc.outerHtml()
        val title = doc.selectFirst("h1.gnrd-fi-title, h1.gnest-title, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val ldObjects = extractJsonLdObjects(doc)
        val primaryLd = ldObjects.firstOrNull { node ->
            val t = node.path("@type").asText("")
            t.equals("Movie", ignoreCase = true) || t.equals("TVSeries", ignoreCase = true)
        }

        val eyebrowRaw = doc.selectFirst(".gnrd-eyebrow, .gnest-eyebrow, .gnest-badge")?.text()?.trim().orEmpty()
        val eyebrowNorm = normalizeAscii(eyebrowRaw)

        val posterImg: Element? = doc.selectFirst(".gnrd-fi-poster img, .gnest-poster img")
        val posterImgAttr = if (posterImg != null) {
            posterImg.attr("data-src").ifBlank { posterImg.attr("src") }
        } else {
            null
        }
        val posterUrl = resolveUrl(mainUrl, doc.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: resolveUrl(mainUrl, posterImgAttr)
            ?: resolveUrl(mainUrl, doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: resolveUrl(mainUrl, primaryLd?.get("image")?.asText())

        val backgroundUrl = extractBackgroundUrl(
            doc.selectFirst(".gnrd-fi-bg, .gnest-hero__bg")?.attr("style"),
            mainUrl,
        ) ?: posterUrl

        val synEl: Element? = doc.selectFirst("#gnrd-syn, .gnest-synopsis")
        val synText = if (synEl != null) {
            val cloned: Element = synEl.clone()
            cloned.select("button, .gnrd-fi-more, .gnest-synopsis-toggle").remove()
            cloned.text().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val plot = synText
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val qfMap = linkedMapOf<String, String>()
        for (qf in doc.select(".gnrd-qfacts .qf")) {
            val k = normalizeAscii(qf.selectFirst(".qf-l")?.text().orEmpty())
            val v = qf.selectFirst(".qf-v")?.text()?.trim().orEmpty()
            if (k.isNotEmpty() && v.isNotEmpty()) qfMap[k] = v
        }

        val infoMap = linkedMapOf<String, Element>()
        for (row in doc.select(".gnrd-info-row")) {
            val k = normalizeAscii(row.selectFirst(".gnrd-info-k")?.text().orEmpty())
            val vEl = row.selectFirst(".gnrd-info-v")
            if (k.isNotEmpty() && vEl != null) infoMap[k] = vEl
        }

        val year = YEAR_REGEX.find(eyebrowRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: qfMap["ano"]?.let { YEAR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: infoMap["ano"]?.text()?.let { YEAR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: infoMap["estreno"]?.text()?.let { YEAR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: primaryLd?.path("datePublished")?.asText("")?.let { YEAR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val durationMinutes = parseDurationMinutes(qfMap["duracion"])
            ?: parseDurationMinutes(infoMap["duracion"]?.text())

        val ratingText = doc.selectFirst(".gnrd-fi-rating, .gnrd-rating, [itemprop=ratingValue]")?.text().orEmpty()
        val rating = FLOAT_REGEX.find(ratingText)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 0.1..10.0 }
            ?: primaryLd?.path("aggregateRating")?.path("ratingValue")?.asText("")?.replace(',', '.')
                ?.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }

        val genresSet = linkedSetOf<String>()
        for (a in doc.select(".gnrd-fi-genres a, .gnrd-info-row a[href*='/genres/'], .gnrd-info-row a[href*='/tag/'], .gnest-genres a, .gnest-genres span")) {
            val g = a.text().trim()
            if (g.isNotEmpty()) genresSet += g
        }
        val ldGenre = primaryLd?.get("genre")
        if (ldGenre != null) {
            if (ldGenre.isArray) {
                for (g in ldGenre) g.asText("").trim().takeIf { it.isNotEmpty() }?.let { genresSet += it }
            } else if (ldGenre.isTextual) {
                ldGenre.asText().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { genresSet += it }
            }
        }

        val actors = doc.select(".gnrd-castc").mapNotNull { c ->
            val actorName = c.selectFirst(".gnrd-castc-name")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val role = c.selectFirst(".gnrd-castc-char")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val photoImg: Element? = c.selectFirst(".gnrd-castc-ph img")
            val photo = if (photoImg != null) {
                resolveUrl(mainUrl, photoImg.attr("data-src").ifBlank { photoImg.attr("src") })
            } else {
                null
            }
            GnulaActor(name = actorName, role = role, photoUrl = photo)
        }

        val trailerYt = doc.selectFirst(".gnrd-trailer[data-yt], .gnest-tr[data-yt], [data-yt]")
            ?.attr("data-yt")?.trim()?.takeIf { it.isNotEmpty() }
        val trailerUrl = if (trailerYt != null) {
            if (trailerYt.startsWith("http", ignoreCase = true)) trailerYt else "https://www.youtube.com/watch?v=$trailerYt"
        } else {
            doc.selectFirst("iframe[src*='youtube.com/embed/'], iframe[src*='youtu.be/']")?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
        }

        val episodes = parseEpisodeCards(doc, mainUrl)
        val inlineCreds = extractInlinePlayerCredentials(html)
        val parentSeriesUrl = doc.selectFirst("a.gnrd-btn-play[href*='/ver/'], .gnrd-epbar a[href*='/ver/']")
            ?.attr("href")
            ?.let { resolveUrl(mainUrl, it) }
            ?.takeIf { it.trimEnd('/') != pageUrl.trimEnd('/') }

        val isAnime = eyebrowNorm.contains("anime") ||
            doc.select(".gnrd-crumb a[href*='/ver/anime/'], .breadcrumb a[href*='/ver/anime/']").isNotEmpty() ||
            genresSet.any { normalizeAscii(it) == "anime" }
        val isSeriesText = eyebrowNorm.contains("serie") ||
            primaryLd?.path("@type")?.asText("")?.equals("TVSeries", ignoreCase = true) == true

        val kind = when {
            episodes.isNotEmpty() -> if (isAnime) GnulaMediaKind.ANIME else GnulaMediaKind.SERIES
            inlineCreds != null -> if (isAnime) GnulaMediaKind.ANIME_MOVIE else GnulaMediaKind.MOVIE
            isAnime -> GnulaMediaKind.ANIME
            isSeriesText -> GnulaMediaKind.SERIES
            else -> GnulaMediaKind.MOVIE
        }

        val comingSoon = episodes.isEmpty() && inlineCreds == null && parentSeriesUrl == null

        val recommendations = parseCards(
            doc.select("#rel-rail a.gnrd-card, .gnrd-rail a.gnrd-card"),
            mainUrl,
            kindHint = if (kind == GnulaMediaKind.ANIME || kind == GnulaMediaKind.ANIME_MOVIE) GnulaMediaKind.ANIME else if (kind == GnulaMediaKind.SERIES) GnulaMediaKind.SERIES else GnulaMediaKind.MOVIE,
        )

        return GnulaDetail(
            title = title,
            url = pageUrl,
            kind = kind,
            comingSoon = comingSoon,
            posterUrl = posterUrl,
            backgroundPosterUrl = backgroundUrl,
            plot = plot,
            year = year,
            durationMinutes = durationMinutes,
            rating = rating,
            genres = genresSet.toList(),
            actors = actors,
            trailerUrl = trailerUrl,
            recommendations = recommendations,
            episodes = episodes,
            playerId = inlineCreds?.first,
            playerToken = inlineCreds?.second,
            parentSeriesUrl = parentSeriesUrl,
        )
    }
}
