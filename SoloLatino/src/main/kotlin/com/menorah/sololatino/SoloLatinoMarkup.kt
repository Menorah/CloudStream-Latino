package com.menorah.sololatino

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal enum class SoloLatinoMediaKind {
    MOVIE,
    SERIES,
    ANIME,
    ANIME_MOVIE,
    DORAMA,
}

internal data class SoloLatinoCatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val kind: SoloLatinoMediaKind,
    val episodeBadge: String? = null,
)

internal data class SoloLatinoCatalogPage(
    val items: List<SoloLatinoCatalogCard>,
    val hasNext: Boolean,
)

internal data class SoloLatinoEpisodeItem(
    val url: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val description: String?,
    val posterUrl: String?,
    val releaseDate: String?,
)

internal data class SoloLatinoActorItem(
    val name: String,
    val role: String?,
    val imageUrl: String?,
)

internal data class SoloLatinoDetail(
    val canonicalUrl: String,
    val title: String,
    val kind: SoloLatinoMediaKind,
    val isSeriesLike: Boolean,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val statusOngoing: Boolean?,
    val genres: List<String>,
    val actors: List<SoloLatinoActorItem>,
    val trailerUrl: String?,
    val recommendations: List<SoloLatinoCatalogCard>,
    val episodes: List<SoloLatinoEpisodeItem>,
    val parentSeriesUrl: String?,
)

internal data class SoloLatinoPlayerButton(
    val langKey: String,
    val langLabel: String,
    val label: String,
    val token: String,
    val resolvedUrl: String?,
    val resolvedType: String?,
)

internal data class SoloLatinoPlayerSection(
    val csrfToken: String?,
    val buttons: List<SoloLatinoPlayerButton>,
    val lazyEmbedToken: String?,
    val lazyEmbedLang: String?,
    val directIframeUrls: List<String>,
)

internal data class SoloLatinoPlayerApiResult(
    val url: String?,
    val type: String?,
)

internal data class SoloLatinoEmbed69Mirror(
    val langCode: String,
    val langLabel: String,
    val serverName: String,
    val url: String,
    val isDownload: Boolean = false,
)

internal data class SoloLatinoPelisSeriesHoyInit(
    val token: String,
    val qc: String,
    val qk: List<String>,
    val qv: String,
    val qt: String,
    val originUrl: String,
    val ao: String,
    val qsrc: String = "",
)

internal data class SoloLatinoPelisSeriesHoyServer(
    val label: String,
    val url: String,
    val langLabel: String,
)

internal data class SoloLatinoPelisSeriesHoyResolution(
    val type: String?,
    val url: String?,
    val u: String?,
    val sig: String?,
    val ctx: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class SoloLatinoSearchSuggestItem(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("badge") val badge: String? = null,
)

internal object SoloLatinoMarkup {
    const val MAIN_URL = "https://sololatino.net"
    const val RENDER_SHIELD_SECRET = "sololatino_render_shield_2026"
    const val SECTION_HOME_RECENT_EPISODES = "home:recent_episodes"
    const val SECTION_HOME_RECENT_ADDED = "home:recent_added"

    val SITE_HOSTS = setOf(
        "sololatino.net",
        "www.sololatino.net",
        "sololatino.co",
        "www.sololatino.co",
    )

    val PELISSERIESHOY_HOSTS = setOf(
        "player.pelisserieshoy.com",
        "pelisserieshoy.com",
        "www.pelisserieshoy.com",
    )

    val jsonMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)

    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val FLOAT_REGEX = Regex("""(\d+(?:[.,]\d+)?)""")
    private val BG_URL_REGEX = Regex("""url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
    private val SERIES_EPISODE_PATH_REGEX = Regex("""^/serie/([^/]+)/temporada-(\d+)/episodio-(\d+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
    private val SERIES_PATH_REGEX = Regex("""^/serie/([^/]+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
    private val MOVIE_PATH_REGEX = Regex("""^/pelicula/([^/]+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
    private val SLASH_DATE_REGEX = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")
    private val ISO_DATE_PREFIX_REGEX = Regex("""^(\d{4}-\d{2}-\d{2})""")
    private val ISO_DURATION_REGEX = Regex("""^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""", RegexOption.IGNORE_CASE)
    private val HM_DURATION_REGEX = Regex("""(?:(\d+)\s*h)?\s*(?:(\d+)\s*(?:m|min))?""", RegexOption.IGNORE_CASE)
    private val PAGE_PARAM_REGEX = Regex("""[?&]page=(\d+)""")

    // Embed69 extraction regexes
    private val E69_DATA_LINK_REGEX = Regex("""(?:let|var|const)\s+dataLink\s*=\s*(\[[\s\S]*?\])\s*;""")
    private val E69_CHALLENGE_REGEX = Regex("""(?:let|var|const)\s+LINK_CHALLENGE\s*=\s*['"]([^'"]+)['"]""")
    private val E69_SALT_REGEX = Regex("""(?:let|var|const)\s+LINK_SALT\s*=\s*['"]([^'"]+)['"]""")

    // PelisSeriesHoy extraction regexes
    private val PSH_T_REGEX = Regex("""\b(?:var|let|const)?\s*_t\s*=\s*['"]([^'"]+)['"]""")
    private val PSH_QC_REGEX = Regex("""\b(?:var|let|const)?\s*_qc\s*=\s*['"]([^'"]+)['"]""")
    private val PSH_QK_REGEX = Regex("""\b(?:var|let|const)?\s*_qk\s*=\s*(\[[^\]]+\])""")
    private val PSH_AO_REGEX = Regex("""\b(?:var|let|const)?\s*_?ao\s*=\s*['"]([^'"]*)['"]""")
    private val PSH_SRC_REGEX = Regex("""\b(?:var|let|const)?\s*_qsrc\s*=\s*['"]([^'"]*)['"]""")

    fun mainPageCategories(): List<Pair<String, String>> = listOf(
        SECTION_HOME_RECENT_EPISODES to "Últimos Episodios",
        SECTION_HOME_RECENT_ADDED to "Recién Añadidos",
        "peliculas?sort=updated" to "Películas Recientes",
        "peliculas?sort=popular" to "Películas Populares",
        "series?sort=updated" to "Series Recientes",
        "series?sort=popular" to "Series Populares",
        "animes?sort=updated" to "Animes Recientes",
        "doramas?sort=updated" to "Doramas",
        "red/netflix" to "Netflix",
        "red/amazon-prime-video" to "Prime Video",
        "red/disney" to "Disney+",
        "red/hbo-max" to "Max (HBO)",
        "red/apple-tv" to "Apple TV+",
        "genero/accion" to "Acción",
        "genero/animacion" to "Animación",
        "genero/aventura" to "Aventura",
        "genero/ciencia-ficcion" to "Ciencia Ficción",
        "genero/comedia" to "Comedia",
        "genero/crimen" to "Crimen",
        "genero/documental" to "Documental",
        "genero/drama" to "Drama",
        "genero/familia" to "Familia",
        "genero/fantasia" to "Fantasía",
        "genero/misterio" to "Misterio",
        "genero/romance" to "Romance",
        "genero/suspense" to "Suspenso",
        "genero/terror" to "Terror",
    )

    fun hostOf(url: String?): String =
        url?.trim()?.let { runCatching { URI(it).host?.lowercase(Locale.ROOT) }.getOrNull() }.orEmpty()

    fun isSiteUrl(url: String?): Boolean {
        val h = hostOf(url)
        return h in SITE_HOSTS || h.endsWith(".sololatino.net")
    }

    fun isPelisSeriesHoyUrl(url: String?): Boolean {
        val h = hostOf(url)
        return h in PELISSERIESHOY_HOSTS || h.endsWith(".pelisserieshoy.com")
    }

    fun resolveUrl(baseUrl: String, raw: String?): String? {
        val cleaned = raw?.trim()?.replace("\\/", "/")?.ifEmpty { null } ?: return null
        val lower = cleaned.lowercase(Locale.ROOT)
        if (cleaned == "#" ||
            lower.startsWith("javascript:") ||
            lower.startsWith("about:") ||
            lower.startsWith("data:") ||
            lower.startsWith("mailto:") ||
            lower.contains("_files/")
        ) {
            return null
        }
        val effectiveBase = baseUrl.trimEnd('/') + "/"
        val resolved = runCatching {
            when {
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("http://", ignoreCase = true) ||
                    cleaned.startsWith("https://", ignoreCase = true) -> cleaned
                else -> URI(effectiveBase).resolve(cleaned).toString()
            }
        }.getOrNull() ?: return null
        val scheme = runCatching { URI(resolved).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") return null
        return resolved
    }

    fun cleanImageUrl(raw: String?, baseUrl: String = MAIN_URL, preferWidth: String? = null): String? {
        val resolved = resolveUrl(baseUrl, raw) ?: return null
        val lower = resolved.lowercase(Locale.ROOT)
        if (lower.endsWith("/no-poster.jpg") || lower.endsWith("/no-poster.png") || lower.contains("_files/")) {
            return null
        }
        if (preferWidth != null &&
            lower.contains("image.tmdb.org/t/p/") &&
            !lower.substringBefore('?').endsWith(".svg")
        ) {
            val currentSize = Regex("""/t/p/(w\d+|h\d+|original)/""").find(lower)?.groupValues?.getOrNull(1)
            val shouldRewrite = when (preferWidth) {
                "w500" -> currentSize in setOf("w45", "w92", "w154", "w185", "w300", "w342", "original")
                "w1280" -> currentSize in setOf("w300", "w500", "w780", "original")
                "w300" -> currentSize == "original"
                else -> false
            }
            if (shouldRewrite) {
                return resolved.replace(Regex("""/t/p/(?:w\d+|h\d+|original)/"""), "/t/p/$preferWidth/")
            }
        }
        return resolved
    }

    fun extractImageUrl(img: Element?, baseUrl: String = MAIN_URL, preferWidth: String? = null): String? {
        if (img == null) return null
        val attrs = listOf("data-src", "data-lazy-src", "data-lazy", "data-original", "src")
        for (attr in attrs) {
            val candidate = cleanImageUrl(img.attr(attr), baseUrl, preferWidth)
            if (candidate != null) return candidate
        }
        val srcsetAttrs = listOf("data-srcset", "srcset")
        for (attr in srcsetAttrs) {
            val rawSet = img.attr(attr).trim()
            if (rawSet.isEmpty()) continue
            val candidates = rawSet.split(',')
                .mapNotNull { part -> part.trim().split(Regex("""\s+""")).firstOrNull()?.takeIf { it.isNotEmpty() } }
            for (rawUrl in candidates.asReversed()) {
                val candidate = cleanImageUrl(rawUrl, baseUrl, preferWidth)
                if (candidate != null) return candidate
            }
        }
        val inlineBg = img.attr("style")
        if (inlineBg.isNotBlank()) {
            val bgUrl = BG_URL_REGEX.find(inlineBg)?.groupValues?.getOrNull(1)
            val candidate = cleanImageUrl(bgUrl, baseUrl, preferWidth)
            if (candidate != null) return candidate
        }
        return null
    }

    fun buildCatalogUrl(data: String, page: Int, baseUrl: String = MAIN_URL): String {
        val cleanBase = baseUrl.trimEnd('/')
        if (data == SECTION_HOME_RECENT_EPISODES || data == SECTION_HOME_RECENT_ADDED) {
            return "$cleanBase/"
        }
        val cleanPath = data.trimStart('/')
        val full = "$cleanBase/$cleanPath"
        if (page <= 1) return full
        val sep = if ('?' in full) "&" else "?"
        return "$full${sep}page=$page"
    }

    fun buildSearchUrl(query: String, page: Int, baseUrl: String = MAIN_URL): String {
        val cleanBase = baseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return if (page <= 1) "$cleanBase/buscar?q=$encoded" else "$cleanBase/buscar?q=$encoded&page=$page"
    }

    fun buildQuickSearchUrl(query: String, baseUrl: String = MAIN_URL): String {
        val cleanBase = baseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return "$cleanBase/api/search/suggest?q=$encoded"
    }

    fun normalizeSeriesOrEpisodeTarget(url: String, baseUrl: String = MAIN_URL): Pair<String, String?> {
        val resolved = resolveUrl(baseUrl, url) ?: url
        val path = runCatching { URI(resolved).rawPath.orEmpty() }.getOrDefault("")
        val epMatch = SERIES_EPISODE_PATH_REGEX.matchEntire(path)
        if (epMatch != null) {
            val slug = epMatch.groupValues[1]
            val cleanBase = baseUrl.trimEnd('/')
            return resolved to "$cleanBase/serie/$slug"
        }
        return resolved to null
    }

    fun parseEpisodeCoordinatesFromUrl(url: String): Pair<Int, Int>? {
        val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault(url)
        val m = SERIES_EPISODE_PATH_REGEX.matchEntire(path) ?: return null
        val season = m.groupValues[2].toIntOrNull() ?: return null
        val episode = m.groupValues[3].toIntOrNull() ?: return null
        return season to episode
    }

    fun defaultKindForData(data: String): SoloLatinoMediaKind = when {
        data.startsWith("animes", ignoreCase = true) -> SoloLatinoMediaKind.ANIME
        data.startsWith("doramas", ignoreCase = true) -> SoloLatinoMediaKind.DORAMA
        data.startsWith("series", ignoreCase = true) -> SoloLatinoMediaKind.SERIES
        else -> SoloLatinoMediaKind.MOVIE
    }

    fun classifyCardKind(badgeText: String, badgeClasses: String, url: String, defaultKind: SoloLatinoMediaKind): SoloLatinoMediaKind {
        val lowerText = badgeText.lowercase(Locale.ROOT)
        val lowerClasses = badgeClasses.lowercase(Locale.ROOT)
        val path = runCatching { URI(url).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
        val isMoviePath = MOVIE_PATH_REGEX.matches(path)
        val isSeriesPath = SERIES_PATH_REGEX.matches(path) || SERIES_EPISODE_PATH_REGEX.matches(path)

        return when {
            lowerClasses.contains("badge-anime") || lowerText.contains("anime") ->
                if (isMoviePath && !isSeriesPath) SoloLatinoMediaKind.ANIME_MOVIE else SoloLatinoMediaKind.ANIME
            lowerClasses.contains("badge-dorama") || lowerText.contains("dorama") ->
                SoloLatinoMediaKind.DORAMA
            lowerClasses.contains("badge-series") || lowerText.contains("serie") ->
                SoloLatinoMediaKind.SERIES
            lowerClasses.contains("badge-movie") || lowerText.contains("pelíc") || lowerText.contains("pelic") ->
                SoloLatinoMediaKind.MOVIE
            isSeriesPath -> when (defaultKind) {
                SoloLatinoMediaKind.ANIME -> SoloLatinoMediaKind.ANIME
                SoloLatinoMediaKind.DORAMA -> SoloLatinoMediaKind.DORAMA
                else -> SoloLatinoMediaKind.SERIES
            }
            isMoviePath -> if (defaultKind == SoloLatinoMediaKind.ANIME) SoloLatinoMediaKind.ANIME_MOVIE else SoloLatinoMediaKind.MOVIE
            else -> defaultKind
        }
    }

    fun parseCatalogCard(
        card: Element,
        baseUrl: String = MAIN_URL,
        defaultKind: SoloLatinoMediaKind = SoloLatinoMediaKind.MOVIE,
    ): SoloLatinoCatalogCard? {
        val anchor: Element = card.selectFirst("a[href]") ?: return null
        val url = resolveUrl(baseUrl, anchor.attr("href")) ?: return null
        val path = runCatching { URI(url).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (!path.startsWith("/pelicula/") && !path.startsWith("/serie/")) return null

        val imgEl: Element? = card.selectFirst("img.card__poster") ?: card.selectFirst("img")
        val titleEl: Element? = card.selectFirst(".card__title, h3, h2")
        val title = titleEl?.text()?.trim()?.ifBlank { null }
            ?: imgEl?.attr("alt")?.trim()?.ifBlank { null }
            ?: anchor.attr("aria-label").trim().ifBlank { null }
            ?: return null

        val posterUrl = extractImageUrl(imgEl, baseUrl, "w500")
        val badgeEl: Element? = card.selectFirst(".card__badge, .badge")
        val badgeText = badgeEl?.text()?.trim().orEmpty()
        val badgeClasses = badgeEl?.className().orEmpty()
        val kind = classifyCardKind(badgeText, badgeClasses, url, defaultKind)

        val ratingEl: Element? = card.selectFirst(".card__rating")
        val rating = ratingEl?.text()?.let {
            FLOAT_REGEX.find(it)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        }?.takeIf { it in 0.1..10.0 }

        var year: Int? = null
        val metaCandidates = card.select(".card__meta span, .card__meta, .card__year, .card__body span, .card__body p")
        for (el in metaCandidates) {
            if (el.hasClass("card__title") || el.hasClass("card__rating") || el.hasClass("card__badge")) continue
            val txt = el.text().trim()
            if (txt.isEmpty()) continue
            val m = YEAR_REGEX.find(txt)
            if (m != null) {
                year = m.groupValues[1].toIntOrNull()
                break
            }
        }
        if (year == null) {
            val tailYear = anchor.text().trim()
            val matches = YEAR_REGEX.findAll(tailYear).toList()
            if (matches.isNotEmpty()) {
                val candidate = matches.last().groupValues[1]
                if (!title.contains(candidate)) {
                    year = candidate.toIntOrNull()
                }
            }
        }

        return SoloLatinoCatalogCard(
            title = title,
            url = url,
            posterUrl = posterUrl,
            year = year,
            rating = rating,
            kind = kind,
        )
    }

    fun parseCatalogPage(
        doc: Document,
        baseUrl: String = MAIN_URL,
        currentPage: Int = 1,
        defaultKind: SoloLatinoMediaKind = SoloLatinoMediaKind.MOVIE,
    ): SoloLatinoCatalogPage {
        val seen = HashSet<String>()
        val items = mutableListOf<SoloLatinoCatalogCard>()
        val cards = doc.select("div.card")
        for (card in cards) {
            val parsed = parseCatalogCard(card, baseUrl, defaultKind) ?: continue
            val key = parsed.url.trimEnd('/').lowercase(Locale.ROOT)
            if (seen.add(key)) {
                items.add(parsed)
            }
        }
        val hasNext = detectHasNext(doc, currentPage)
        return SoloLatinoCatalogPage(items = items, hasNext = hasNext)
    }

    private fun detectHasNext(doc: Document, currentPage: Int): Boolean {
        val relNext: Element? = doc.selectFirst("link[rel=next][href]")
        if (relNext != null) return true
        for (a in doc.select("nav.pagination .page-item:not(.disabled) a[href]")) {
            val href = a.attr("href")
            val m = PAGE_PARAM_REGEX.find(href)
            val pageNum = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (pageNum != null && pageNum > currentPage) return true
            val label = a.text().trim()
            if (label == "›" || label == "»" || label.contains("Siguiente", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun parseHomeRecentEpisodes(doc: Document, baseUrl: String = MAIN_URL): List<SoloLatinoCatalogCard> {
        val out = mutableListOf<SoloLatinoCatalogCard>()
        val seen = HashSet<String>()
        for (epCard in doc.select("a.ep-card[href]")) {
            val url = resolveUrl(baseUrl, epCard.attr("href")) ?: continue
            val coords = parseEpisodeCoordinatesFromUrl(url)
            val imgEl: Element? = epCard.selectFirst("img")
            val titleNode: Element? = epCard.selectFirst(".ep-card__title, h3, h4, p")
            val seriesName = imgEl?.attr("alt")?.trim()?.ifBlank { null }
                ?: titleNode?.text()?.trim()?.ifBlank { null }
                ?: continue
            val posterUrl = extractImageUrl(imgEl, baseUrl, "w300")
            val badgeNode: Element? = epCard.selectFirst(".ep-card__badge, .ep-card__ep, span")
            val epTag = coords?.let { (s, e) -> "T$s · E$e" }
                ?: badgeNode?.text()?.trim()?.ifBlank { null }
            val displayTitle = if (!epTag.isNullOrBlank() && !seriesName.contains(epTag, ignoreCase = true)) {
                "$seriesName ($epTag)"
            } else {
                seriesName
            }
            val key = url.trimEnd('/').lowercase(Locale.ROOT)
            if (seen.add(key)) {
                out.add(
                    SoloLatinoCatalogCard(
                        title = displayTitle,
                        url = url,
                        posterUrl = posterUrl,
                        year = null,
                        rating = null,
                        kind = SoloLatinoMediaKind.SERIES,
                        episodeBadge = epTag,
                    ),
                )
            }
        }
        return out
    }

    fun parseHomeRecentAdded(doc: Document, baseUrl: String = MAIN_URL): List<SoloLatinoCatalogCard> {
        val sections = doc.select("section")
        for (sec in sections) {
            val heading: Element? = sec.selectFirst("h2, h3")
            val headingText = heading?.text()?.trim().orEmpty()
            if (headingText.contains("Recién Añadidos", ignoreCase = true) ||
                headingText.contains("Recien Anadidos", ignoreCase = true)
            ) {
                val out = mutableListOf<SoloLatinoCatalogCard>()
                val seen = HashSet<String>()
                for (card in sec.select("div.card")) {
                    val parsed = parseCatalogCard(card, baseUrl, SoloLatinoMediaKind.MOVIE) ?: continue
                    val key = parsed.url.trimEnd('/').lowercase(Locale.ROOT)
                    if (seen.add(key)) out.add(parsed)
                }
                if (out.isNotEmpty()) return out
            }
        }
        // Fallback: all .card on home
        return parseCatalogPage(doc, baseUrl, 1, SoloLatinoMediaKind.MOVIE).items
    }

    fun parseQuickSearchJson(rawJson: String, baseUrl: String = MAIN_URL): List<SoloLatinoCatalogCard> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { jsonMapper.readTree(trimmed) }.getOrNull() ?: return emptyList()
        val arr = when {
            root.isArray -> root
            root.has("results") && root.get("results").isArray -> root.get("results")
            root.has("items") && root.get("items").isArray -> root.get("items")
            root.has("data") && root.get("data").isArray -> root.get("data")
            else -> return emptyList()
        }
        val out = mutableListOf<SoloLatinoCatalogCard>()
        val seen = HashSet<String>()
        for (node in arr) {
            val item = runCatching { jsonMapper.treeToValue(node, SoloLatinoSearchSuggestItem::class.java) }.getOrNull()
                ?: continue
            val rawType = (item.type ?: item.mediaType ?: "").lowercase(Locale.ROOT)
            if (rawType == "person" || rawType == "actor") continue
            val rawUrl = item.url?.trim()?.ifBlank { null } ?: item.slug?.trim()?.let { slug ->
                when {
                    rawType.contains("serie") || rawType.contains("tv") || rawType.contains("anime") || rawType.contains("dorama") ->
                        "/serie/$slug"
                    else -> "/pelicula/$slug"
                }
            } ?: continue
            val url = resolveUrl(baseUrl, rawUrl) ?: continue
            val path = runCatching { URI(url).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            if (!path.startsWith("/pelicula/") && !path.startsWith("/serie/")) continue
            val title = (item.title ?: item.name)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val poster = cleanImageUrl(item.poster ?: item.image, baseUrl, "w500")
            val badgeText = item.badge ?: rawType
            val defaultKind = when {
                rawType.contains("anime") -> if (path.startsWith("/pelicula/")) SoloLatinoMediaKind.ANIME_MOVIE else SoloLatinoMediaKind.ANIME
                rawType.contains("dorama") -> SoloLatinoMediaKind.DORAMA
                rawType.contains("serie") || rawType.contains("tv") -> SoloLatinoMediaKind.SERIES
                else -> SoloLatinoMediaKind.MOVIE
            }
            val kind = classifyCardKind(badgeText, "", url, defaultKind)
            val key = url.trimEnd('/').lowercase(Locale.ROOT)
            if (seen.add(key)) {
                out.add(
                    SoloLatinoCatalogCard(
                        title = title,
                        url = url,
                        posterUrl = poster,
                        year = item.year?.takeIf { it in 1900..2100 },
                        rating = item.rating?.takeIf { it in 0.1..10.0 },
                        kind = kind,
                    ),
                )
            }
        }
        return out
    }

    fun parseSlashOrIsoDate(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        ISO_DATE_PREFIX_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        val m = SLASH_DATE_REGEX.find(text) ?: return null
        val d = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val mo = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val y = m.groupValues[3].toIntOrNull()?.takeIf { it in 1900..2100 } ?: return null
        return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)
    }

    fun parseDurationMinutes(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        ISO_DURATION_REGEX.matchEntire(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val tot = h * 60 + min
            if (tot > 0) return tot
        }
        val m = HM_DURATION_REGEX.find(text)
        if (m != null) {
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val tot = h * 60 + min
            if (tot > 0) return tot
        }
        return null
    }

    private fun parseJsonLdNodes(doc: Document): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        for (sc in doc.select("script[type=application/ld+json]")) {
            val raw = sc.data().ifBlank { sc.html() }.trim()
            if (raw.isEmpty()) continue
            val root = runCatching { jsonMapper.readTree(raw) }.getOrNull() ?: continue
            collectLd(root, out)
        }
        return out
    }

    private fun collectLd(node: JsonNode, out: MutableList<JsonNode>) {
        when {
            node.isArray -> node.forEach { collectLd(it, out) }
            node.isObject -> {
                val type = node.get("@type")?.asText().orEmpty()
                if (type.equals("Movie", true) ||
                    type.equals("TVSeries", true) ||
                    type.equals("TVEpisode", true)
                ) {
                    out.add(node)
                }
                node.get("@graph")?.let { collectLd(it, out) }
            }
        }
    }

    private fun extractLdStringOrUrl(node: JsonNode?, baseUrl: String): String? {
        if (node == null || node.isNull) return null
        val raw = when {
            node.isTextual -> node.asText()
            node.isObject -> node.get("url")?.asText() ?: node.get("@id")?.asText()
            node.isArray && node.size() > 0 -> extractLdStringOrUrl(node.get(0), baseUrl)
            else -> null
        }
        return cleanImageUrl(raw, baseUrl)
    }

    fun parseDetail(
        doc: Document,
        pageUrl: String,
        baseUrl: String = MAIN_URL,
    ): SoloLatinoDetail? {
        val canonicalEl: Element? = doc.selectFirst("link[rel=canonical][href]")
        val canonicalUrl = resolveUrl(baseUrl, canonicalEl?.attr("href"))
            ?: resolveUrl(baseUrl, pageUrl)
            ?: pageUrl
        val ldNodes = parseJsonLdNodes(doc)
        val primaryLd = ldNodes.firstOrNull {
            val t = it.get("@type")?.asText().orEmpty()
            t.equals("Movie", true) || t.equals("TVSeries", true)
        } ?: ldNodes.firstOrNull()

        val path = runCatching { URI(canonicalUrl).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val epCoords = parseEpisodeCoordinatesFromUrl(canonicalUrl)

        var parentSeriesUrl: String? = null
        if (epCoords != null) {
            for (a in doc.select("nav[aria-label=breadcrumb] a[href*='/serie/'], a[href*='/serie/']")) {
                val candidate = resolveUrl(baseUrl, a.attr("href")) ?: continue
                val cPath = runCatching { URI(candidate).rawPath.orEmpty() }.getOrDefault("")
                if (SERIES_PATH_REGEX.matches(cPath) && !SERIES_EPISODE_PATH_REGEX.matches(cPath)) {
                    parentSeriesUrl = candidate
                    break
                }
            }
            if (parentSeriesUrl == null) {
                parentSeriesUrl = normalizeSeriesOrEpisodeTarget(canonicalUrl, baseUrl).second
            }
        }

        val h1El: Element? = doc.selectFirst("h1")
        val h1Text = h1El?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals("SoloLatino", true) }
        val heroAltEl: Element? = doc.selectFirst(".detail-hero img[alt], .detail-poster img[alt]")
        val heroAltText = heroAltEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() && !it.equals("SoloLatino", true) }
        val ldName = primaryLd?.get("name")?.asText()?.trim()?.takeIf { it.isNotBlank() }
        val ogTitleEl: Element? = doc.selectFirst("meta[property=og:title]")
        val ogTitle = ogTitleEl?.attr("content")?.substringBefore(" — ")?.substringBefore(" | ")?.trim()?.takeIf { it.isNotBlank() }
        val docTitle = doc.title().substringBefore(" — ").substringBefore(" | ").trim().takeIf { it.isNotBlank() }
        val title = h1Text ?: ldName ?: heroAltText ?: ogTitle ?: docTitle ?: return null

        val bgEl: Element? = doc.selectFirst(".detail-hero__bg[style]")
        val bgStyle = bgEl?.attr("style").orEmpty()
        val bgFromStyle = BG_URL_REGEX.find(bgStyle)?.groupValues?.getOrNull(1)?.let {
            cleanImageUrl(it, baseUrl, "w1280")
        }
        val ldImage = extractLdStringOrUrl(primaryLd?.get("image"), baseUrl)
            ?: extractLdStringOrUrl(primaryLd?.get("thumbnailUrl"), baseUrl)
        val ogImageEl: Element? = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
        val ogImage = cleanImageUrl(ogImageEl?.attr("content"), baseUrl)

        val posterImgEl: Element? = doc.selectFirst(
            "img.detail-poster, .detail-poster img, .detail-hero__poster img, .z-10 .flex-shrink-0 img, .z-10 img.rounded-2xl:not(.max-h-16):not(.max-w-xs):not([style*=max-height]), main img.rounded-2xl:not(.max-h-16):not(.max-w-xs):not([style*=max-height])",
        )
        val posterUrl = extractImageUrl(posterImgEl, baseUrl, "w500")
            ?: cleanImageUrl(ldImage, baseUrl, "w500")
            ?: cleanImageUrl(ogImage, baseUrl, "w500")
            ?: cleanImageUrl(bgFromStyle, baseUrl, null)
        val backgroundPosterUrl = bgFromStyle
            ?: cleanImageUrl(ogImage, baseUrl, null)
            ?: cleanImageUrl(ldImage, baseUrl, null)
            ?: posterUrl

        val plotEl: Element? = doc.selectFirst(".detail-synopsis, .detail-overview, .detail-description, p.text-gray-300")
        val plot = plotEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: primaryLd?.get("description")?.asText()?.trim()?.takeIf { it.isNotBlank() }

        // Year & Duration & Status from detail header metadata
        var year: Int? = null
        var durationMinutes: Int? = null
        var statusOngoing: Boolean? = null
        val metaCandidates = doc.select(".detail-hero span, .detail-meta span, .detail-badges span, .badge")
        for (span in metaCandidates) {
            val txt = span.text().trim()
            if (txt.isEmpty()) continue
            if (year == null) {
                YEAR_REGEX.matchEntire(txt)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { year = it }
            }
            if (durationMinutes == null && (txt.contains("min", ignoreCase = true) || Regex("""\d+\s*h\b""").containsMatchIn(txt))) {
                parseDurationMinutes(txt)?.let { durationMinutes = it }
            }
            if (statusOngoing == null) {
                when {
                    txt.contains("En emisión", ignoreCase = true) ||
                        txt.contains("Returning", ignoreCase = true) -> statusOngoing = true
                    txt.contains("Finalizada", ignoreCase = true) ||
                        txt.contains("Ended", ignoreCase = true) -> statusOngoing = false
                }
            }
        }
        if (year == null) {
            val ldDate = primaryLd?.get("datePublished")?.asText() ?: primaryLd?.get("startDate")?.asText()
            year = ldDate?.let { YEAR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        }
        if (durationMinutes == null) {
            durationMinutes = parseDurationMinutes(primaryLd?.get("duration")?.asText())
        }

        // Rating: prefer IMDb / rating-badge__val / JSON-LD
        var rating: Double? = null
        val ratingBadges = doc.select(".rating-badge")
        val orderedBadges = ratingBadges.sortedByDescending {
            it.text().contains("IMDb", ignoreCase = true)
        }
        for (badge in orderedBadges) {
            val valEl: Element? = badge.selectFirst(".rating-badge__val") ?: badge
            val candidate = valEl?.text()?.let {
                FLOAT_REGEX.find(it)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            }?.takeIf { it in 0.1..10.0 }
            if (candidate != null) {
                rating = candidate
                break
            }
        }
        if (rating == null) {
            rating = primaryLd?.get("aggregateRating")?.get("ratingValue")?.asDouble()
                ?.takeIf { it in 0.1..10.0 }
        }

        // Genres
        val genres = linkedSetOf<String>()
        for (g in doc.select(".detail-hero a[href*='/genero/'], a.genre-chip[href*='/genero/'], a[href*='/genero/'].badge, .detail-genres a[href*='/genero/']")) {
            val gText = g.text().trim()
            if (gText.isNotEmpty() && gText.length < 40) genres.add(gText)
        }
        val ldGenres = primaryLd?.get("genre")
        if (ldGenres != null) {
            if (ldGenres.isArray) {
                ldGenres.forEach { n -> n.asText().trim().takeIf { it.isNotEmpty() }?.let { genres.add(it) } }
            } else if (ldGenres.isTextual) {
                ldGenres.asText().split(',').forEach { s -> s.trim().takeIf { it.isNotEmpty() }?.let { genres.add(it) } }
            }
        }

        // Actors
        val actors = mutableListOf<SoloLatinoActorItem>()
        val seenActors = HashSet<String>()
        for (card in doc.select(".cast-card")) {
            val imgEl: Element? = card.selectFirst("img")
            val nameEl: Element? = card.selectFirst("p.font-semibold, p.font-medium, p")
            val name = nameEl?.text()?.trim()?.ifBlank { null }
                ?: imgEl?.attr("alt")?.trim()?.ifBlank { null }
                ?: continue
            val roleEl: Element? = card.select("p").drop(1).firstOrNull()
            val role = roleEl?.text()?.trim()?.ifBlank { null }
            val photo = extractImageUrl(imgEl, baseUrl, "w185")
            if (seenActors.add(name.lowercase(Locale.ROOT))) {
                actors.add(SoloLatinoActorItem(name = name, role = role, imageUrl = photo))
            }
        }
        if (actors.isEmpty()) {
            val ldActors = primaryLd?.get("actor")
            if (ldActors != null && ldActors.isArray) {
                for (a in ldActors) {
                    val name = a.get("name")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    if (seenActors.add(name.lowercase(Locale.ROOT))) {
                        actors.add(SoloLatinoActorItem(name = name, role = null, imageUrl = null))
                    }
                }
            }
        }

        // Trailer
        val trailerBtn: Element? = doc.selectFirst("button[data-trailer]")
        val trailerAttr = trailerBtn?.attr("data-trailer")?.trim().orEmpty()
        val trailerUrl = when {
            trailerAttr.startsWith("http", ignoreCase = true) -> trailerAttr
            trailerAttr.isNotEmpty() -> "https://www.youtube.com/watch?v=$trailerAttr"
            else -> {
                val ytFrame: Element? = doc.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")
                ytFrame?.let { resolveUrl(baseUrl, it.attr("src")) }
            }
        }

        // Episodes from all [data-season-panel] sections
        val episodes = parseSeriesEpisodes(doc, baseUrl)

        // Classification
        val ldType = primaryLd?.get("@type")?.asText().orEmpty()
        val seasonPanelEl: Element? = doc.selectFirst("[data-season-panel]")
        val isSeriesLike = epCoords != null ||
            episodes.isNotEmpty() ||
            SERIES_PATH_REGEX.matches(path) ||
            ldType.equals("TVSeries", true) ||
            seasonPanelEl != null

        val allBadgeTexts = doc.select(".detail-hero .badge, nav[aria-label=breadcrumb] a").joinToString(" ") { it.text() }
        val hasAnime = allBadgeTexts.contains("Anime", ignoreCase = true) || genres.any { it.equals("Anime", ignoreCase = true) }
        val hasDorama = allBadgeTexts.contains("Dorama", ignoreCase = true) || genres.any { it.equals("Dorama", ignoreCase = true) }
        val kind = when {
            hasAnime && isSeriesLike -> SoloLatinoMediaKind.ANIME
            hasAnime -> SoloLatinoMediaKind.ANIME_MOVIE
            hasDorama && isSeriesLike -> SoloLatinoMediaKind.DORAMA
            isSeriesLike -> SoloLatinoMediaKind.SERIES
            else -> SoloLatinoMediaKind.MOVIE
        }

        // Recommendations (avoid hero/main elements)
        val recommendations = mutableListOf<SoloLatinoCatalogCard>()
        val seenRecs = HashSet<String>()
        val recDefaultKind = if (isSeriesLike) SoloLatinoMediaKind.SERIES else SoloLatinoMediaKind.MOVIE
        for (card in doc.select("div.card")) {
            val parsed = parseCatalogCard(card, baseUrl, recDefaultKind) ?: continue
            val norm = parsed.url.trimEnd('/').lowercase(Locale.ROOT)
            if (norm != canonicalUrl.trimEnd('/').lowercase(Locale.ROOT) && seenRecs.add(norm)) {
                recommendations.add(parsed)
            }
        }

        return SoloLatinoDetail(
            canonicalUrl = canonicalUrl,
            title = title,
            kind = kind,
            isSeriesLike = isSeriesLike,
            posterUrl = posterUrl,
            backgroundPosterUrl = backgroundPosterUrl,
            plot = plot,
            year = year,
            durationMinutes = durationMinutes,
            rating = rating,
            statusOngoing = statusOngoing,
            genres = genres.toList(),
            actors = actors,
            trailerUrl = trailerUrl,
            recommendations = recommendations,
            episodes = episodes,
            parentSeriesUrl = parentSeriesUrl,
        )
    }

    fun parseSeriesEpisodes(doc: Document, baseUrl: String = MAIN_URL): List<SoloLatinoEpisodeItem> {
        val byKey = LinkedHashMap<Pair<Int, Int>, SoloLatinoEpisodeItem>()
        val panels = doc.select("[data-season-panel]")
        if (panels.isNotEmpty()) {
            for (panel in panels) {
                val panelSeason = panel.attr("data-season-panel").trim().toIntOrNull()
                for ((idx, item) in panel.select("a.ep-item[href]").withIndex()) {
                    val epUrl = resolveUrl(baseUrl, item.attr("href")) ?: continue
                    val coords = parseEpisodeCoordinatesFromUrl(epUrl)
                    val epNumEl: Element? = item.selectFirst(".ep-num")
                    val numFromBadge = epNumEl?.text()?.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    val season = coords?.first ?: panelSeason ?: 1
                    val episode = coords?.second ?: numFromBadge ?: (idx + 1)

                    val titleEl: Element? = item.selectFirst("p.text-sm.font-semibold, p.font-semibold, h4, h3")
                    val rawTitle = titleEl?.text()?.trim().orEmpty()
                    val cleanTitle = cleanEpisodeTitle(rawTitle, episode)
                    val descEl: Element? = item.selectFirst("p.line-clamp-2")
                    val description = descEl?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals(cleanTitle, true) }
                    val imgEl: Element? = item.selectFirst("img.ep-thumb, img")
                    val posterUrl = extractImageUrl(imgEl, baseUrl, "w300")
                    val releaseDate = parseSlashOrIsoDate(item.text())

                    byKey.putIfAbsent(
                        season to episode,
                        SoloLatinoEpisodeItem(
                            url = epUrl,
                            season = season,
                            episode = episode,
                            title = cleanTitle,
                            description = description,
                            posterUrl = posterUrl,
                            releaseDate = releaseDate,
                        ),
                    )
                }
            }
        }
        // Fallback: any a.ep-item outside [data-season-panel] or episode links
        if (byKey.isEmpty()) {
            for (item in doc.select("a.ep-item[href], a[href*='/temporada-'][href*='/episodio-']")) {
                val epUrl = resolveUrl(baseUrl, item.attr("href")) ?: continue
                val coords = parseEpisodeCoordinatesFromUrl(epUrl) ?: continue
                val titleEl: Element? = item.selectFirst("p.text-sm.font-semibold, p.font-semibold, h4")
                val cleanTitle = cleanEpisodeTitle(titleEl?.text()?.trim().orEmpty(), coords.second)
                val descEl: Element? = item.selectFirst("p.line-clamp-2")
                val description = descEl?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals(cleanTitle, true) }
                val imgEl: Element? = item.selectFirst("img")
                val posterUrl = extractImageUrl(imgEl, baseUrl, "w500")
                val releaseDate = parseSlashOrIsoDate(item.text())
                byKey.putIfAbsent(
                    coords,
                    SoloLatinoEpisodeItem(
                        url = epUrl,
                        season = coords.first,
                        episode = coords.second,
                        title = cleanTitle,
                        description = description,
                        posterUrl = posterUrl,
                        releaseDate = releaseDate,
                    ),
                )
            }
        }
        return byKey.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { it.value }
    }

    private fun cleanEpisodeTitle(raw: String, episodeNumber: Int): String {
        val stripped = raw
            .replace(Regex("""^(?:T\d+\s*[·xX-]?\s*)?E(?:p\.?\s*)?\d+\s*[:·\-–—]?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
        return stripped.ifBlank { "Episodio $episodeNumber" }
    }

    fun normalizeLanguageLabel(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return "Latino"
        val lower = clean.lowercase(Locale.ROOT)
        return when {
            lower == "mx" || lower == "lat" || lower.contains("latino") || lower.contains("mx") -> "Latino"
            lower == "es" || lower == "esp" || lower.contains("español") || lower.contains("castellano") -> "Español"
            lower == "sub" || lower == "eng" || lower == "en" || lower.contains("sub") || lower.contains("vose") -> "Subtitulado"
            else -> clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    fun parsePlayerSection(doc: Document, baseUrl: String = MAIN_URL): SoloLatinoPlayerSection {
        val csrfEl: Element? = doc.selectFirst("meta[name=csrf-token]")
        val csrfToken = csrfEl?.attr("content")?.trim()?.ifBlank { null }

        val langLabelsByKey = LinkedHashMap<String, String>()
        for (tab in doc.select("[data-lang-tab]")) {
            val key = tab.attr("data-lang-tab").trim()
            val label = tab.text().trim()
            if (key.isNotEmpty() && label.isNotEmpty()) {
                langLabelsByKey[key] = normalizeLanguageLabel(label)
            }
        }

        val buttons = mutableListOf<SoloLatinoPlayerButton>()
        val seenTokens = HashSet<String>()

        fun collectButton(btn: Element, langKey: String, langLabel: String) {
            val token = btn.attr("data-player-token").trim()
            val resolvedUrl = resolveUrl(baseUrl, btn.attr("data-resolved-url"))
            if (token.isEmpty() && resolvedUrl == null) return
            val dedupKey = token.ifEmpty { "url:${resolvedUrl}" }
            if (!seenTokens.add(dedupKey)) return
            val label = btn.text().trim().replace(Regex("""\s+"""), " ").ifBlank { "Servidor" }
            val resolvedType = btn.attr("data-resolved-type").trim().ifBlank { null }
            buttons.add(
                SoloLatinoPlayerButton(
                    langKey = langKey,
                    langLabel = langLabel,
                    label = label,
                    token = token,
                    resolvedUrl = resolvedUrl,
                    resolvedType = resolvedType,
                ),
            )
        }

        for (group in doc.select("[data-lang-group]")) {
            val key = group.attr("data-lang-group").trim()
            val langLabel = langLabelsByKey[key] ?: normalizeLanguageLabel(key)
            for (btn in group.select("button[data-player-token], [data-player-token]")) {
                collectButton(btn, key, langLabel)
            }
        }

        for (btn in doc.select("button[data-player-token], [data-player-token]")) {
            val group: Element? = btn.closest("[data-lang-group]")
            if (group != null) continue
            val fallbackLangKey = btn.attr("data-lang").trim().ifBlank { "lat" }
            collectButton(btn, fallbackLangKey, normalizeLanguageLabel(fallbackLangKey))
        }

        val playerFrame: Element? = doc.selectFirst("#player-frame")
        val lazyEmbedToken = playerFrame?.attr("data-lazy-embed")?.trim()?.ifBlank { null }
        val lazyLangRaw = playerFrame?.attr("data-lang")?.trim()?.ifBlank { null }
            ?: buttons.firstOrNull()?.langLabel
        val lazyEmbedLang = lazyLangRaw?.let { normalizeLanguageLabel(it) }

        val directIframeUrls = doc.select("#player-frame iframe[src], .player-container iframe[src]")
            .mapNotNull { resolveUrl(baseUrl, it.attr("src")) }
            .distinct()

        return SoloLatinoPlayerSection(
            csrfToken = csrfToken,
            buttons = buttons,
            lazyEmbedToken = lazyEmbedToken,
            lazyEmbedLang = lazyEmbedLang,
            directIframeUrls = directIframeUrls,
        )
    }

    fun computeRenderShieldHeader(
        token: String,
        serverOffsetMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val adjustedMs = nowMs + serverOffsetMs
        val slot = Math.floorDiv(adjustedMs, 60_000L)
        val raw = "$token:$slot:$RENDER_SHIELD_SECRET"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return sb.substring(0, 16)
    }

    fun parseHttpDateMillis(dateHeader: String?): Long? {
        val raw = dateHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

    fun parsePlayerApiResponse(rawJson: String): SoloLatinoPlayerApiResult? {
        val node = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return null
        if (!node.isObject) return null
        val url = node.get("url")?.asText()?.trim()?.replace("\\/", "/")?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val type = node.get("type")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        if (url == null && type == null) return null
        return SoloLatinoPlayerApiResult(url = url, type = type)
    }

    fun decodeCookieValue(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
    }

    fun decryptEmbed69Page(html: String): List<SoloLatinoEmbed69Mirror> {
        val dataLinkRaw = E69_DATA_LINK_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val challenge = E69_CHALLENGE_REGEX.find(html)?.groupValues?.getOrNull(1)
        val salt = E69_SALT_REGEX.find(html)?.groupValues?.getOrNull(1)
        val aesKey: SecretKeySpec? = if (!challenge.isNullOrEmpty() && !salt.isNullOrEmpty()) {
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest((challenge + salt).toByteArray(StandardCharsets.UTF_8))
                SecretKeySpec(digest, "AES")
            }.getOrNull()
        } else {
            null
        }

        val root = runCatching { jsonMapper.readTree(dataLinkRaw) }.getOrNull() ?: return emptyList()
        if (!root.isArray) return emptyList()

        val sortedOut = mutableListOf<SoloLatinoEmbed69Mirror>()
        val downloadOut = mutableListOf<SoloLatinoEmbed69Mirror>()
        val seen = HashSet<String>()

        fun decryptUrl(rawLink: String?): String? {
            val clean = rawLink?.trim()?.ifEmpty { null } ?: return null
            if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                return clean.trim().trim('`', ' ', '\u0000')
            }
            val key = aesKey ?: return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(clean)
                if (bytes.size <= 16) return@runCatching null
                val iv = bytes.copyOfRange(0, 16)
                val ct = bytes.copyOfRange(16, bytes.size)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
                val decrypted = String(cipher.doFinal(ct), StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .trim()
                    .trim('`', ' ', '\'', '"')
                decrypted.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            }.getOrNull()
        }

        fun collectList(arr: JsonNode?, langCode: String, langLabel: String, isDownload: Boolean) {
            if (arr == null || !arr.isArray) return
            for (entry in arr) {
                val server = entry.get("servername")?.asText()?.trim()?.ifBlank { null } ?: "Servidor"
                val decrypted = decryptUrl(entry.get("link")?.asText()) ?: continue
                if (!seen.add("$langCode|$decrypted")) continue
                val mirror = SoloLatinoEmbed69Mirror(
                    langCode = langCode,
                    langLabel = langLabel,
                    serverName = server,
                    url = decrypted,
                    isDownload = isDownload,
                )
                if (isDownload) downloadOut.add(mirror) else sortedOut.add(mirror)
            }
        }

        for (langNode in root) {
            val rawLang = langNode.get("video_language")?.asText()?.trim().orEmpty().uppercase(Locale.ROOT)
            val langLabel = normalizeLanguageLabel(rawLang)
            collectList(langNode.get("sortedEmbeds"), rawLang, langLabel, isDownload = false)
            collectList(langNode.get("downloadEmbeds"), rawLang, langLabel, isDownload = true)
        }
        return sortedOut.ifEmpty { downloadOut }
    }

    fun fnv1a32Hex(input: String): String {
        var h = 0x811c9dc5u
        for (ch in input) {
            h = h xor ch.code.toUInt()
            h *= 0x01000193u
        }
        return h.toString(16)
    }

    fun originFromUrl(url: String?, fallback: String = MAIN_URL): String {
        val uri = url?.trim()?.let { runCatching { URI(it) }.getOrNull() }
        return if (uri != null && !uri.scheme.isNullOrBlank() && !uri.authority.isNullOrBlank()) {
            "${uri.scheme}://${uri.authority}"
        } else {
            fallback
        }
    }

    fun parsePelisSeriesHoyInit(
        html: String,
        pageUrl: String,
        refererUrl: String = MAIN_URL,
    ): SoloLatinoPelisSeriesHoyInit? {
        val t = PSH_T_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: return null
        val qc = PSH_QC_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: return null
        val qkRaw = PSH_QK_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val qk = runCatching {
            jsonMapper.readValue(qkRaw, object : TypeReference<List<String>>() {})
        }.getOrNull() ?: return null
        if (qk.size < 4) return null
        val qv = qk[1] + qk[3] + qk[0] + qk[2]
        val qt = fnv1a32Hex("$qv|clk|$qc")
        val defaultAo = originFromUrl(refererUrl, MAIN_URL)
        val ao = PSH_AO_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: defaultAo
        val qsrc = PSH_SRC_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val originUrl = originFromUrl(pageUrl, "https://player.pelisserieshoy.com")
        return SoloLatinoPelisSeriesHoyInit(
            token = t,
            qc = qc,
            qk = qk,
            qv = qv,
            qt = qt,
            originUrl = originUrl,
            ao = ao,
            qsrc = qsrc,
        )
    }

    fun parsePelisSeriesHoyStep1(rawJson: String): List<SoloLatinoPelisSeriesHoyServer> {
        val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return emptyList()
        if (!root.isObject) return emptyList()
        val out = mutableListOf<SoloLatinoPelisSeriesHoyServer>()
        val seen = HashSet<String>()

        fun addServer(label: String, url: String, langLabel: String) {
            val cleanUrl = url.trim()
            if (cleanUrl.isEmpty() || !seen.add(cleanUrl)) return
            out.add(SoloLatinoPelisSeriesHoyServer(label = label.trim().ifBlank { "Servidor" }, url = cleanUrl, langLabel = langLabel))
        }

        val langsNode = root.get("langs_s")
        if (langsNode != null && langsNode.isObject) {
            val fields = langsNode.fields()
            while (fields.hasNext()) {
                val (rawLang, arr) = fields.next()
                val langLabel = normalizeLanguageLabel(rawLang)
                if (arr.isArray) {
                    for (entry in arr) {
                        if (entry.isArray && entry.size() >= 2) {
                            addServer(entry.get(0).asText(), entry.get(1).asText(), langLabel)
                        } else if (entry.isObject) {
                            val lbl = entry.get("label")?.asText() ?: entry.get("name")?.asText() ?: "Servidor"
                            val u = entry.get("url")?.asText() ?: continue
                            addServer(lbl, u, langLabel)
                        }
                    }
                }
            }
        }

        val flatNode = root.get("s")
        if (flatNode != null && flatNode.isArray) {
            for (entry in flatNode) {
                if (entry.isArray && entry.size() >= 2) {
                    addServer(entry.get(0).asText(), entry.get(1).asText(), "Latino")
                }
            }
        }
        return out
    }

    fun parsePelisSeriesHoyStep2(rawJson: String): SoloLatinoPelisSeriesHoyResolution? {
        val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return null
        if (!root.isObject) return null
        val type = root.get("type")?.asText()?.trim()?.ifBlank { null }
        val url = root.get("url")?.asText()?.trim()?.replace("\\/", "/")?.ifBlank { null }
        val u = root.get("u")?.asText()?.trim()?.replace("\\/", "/")?.ifBlank { null }
        val sig = root.get("sig")?.asText()?.trim()?.ifBlank { null }
        val ctx = root.get("ctx")?.asText()?.trim()?.ifBlank { null }
        if (url == null && u == null) return null
        return SoloLatinoPelisSeriesHoyResolution(type = type, url = url, u = u, sig = sig, ctx = ctx)
    }

    fun buildPelisSeriesHoyHlsProxyUrl(
        originUrl: String,
        u: String,
        sig: String?,
        ctx: String? = null,
    ): String {
        val cleanOrigin = originUrl.trimEnd('/')
        val encU = URLEncoder.encode(u, StandardCharsets.UTF_8.name())
        val encSig = URLEncoder.encode(sig.orEmpty(), StandardCharsets.UTF_8.name())
        return buildString {
            append(cleanOrigin).append("/p.php?url=").append(encU).append("&sig=").append(encSig)
            if (!ctx.isNullOrBlank()) {
                append("&ctx=").append(URLEncoder.encode(ctx, StandardCharsets.UTF_8.name()))
            }
        }
    }

    private val GDRIVE_FILE_ID_REGEX = Regex("""(?:/file/d/|/d/|[?&]id=)([A-Za-z0-9_-]{12,})""")

    fun extractGDriveFileId(url: String): String? =
        GDRIVE_FILE_ID_REGEX.find(url)?.groupValues?.getOrNull(1)
}
