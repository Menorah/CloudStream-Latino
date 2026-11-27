package com.menorah.latanime

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64

enum class LatanimeCardKind {
    ANIME,
    MOVIE,
    OVA,
}

enum class LatanimeShowStatus {
    ONGOING,
    COMPLETED,
}

data class LatanimeCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val badge: String? = null,
    val year: Int? = null,
    val episodeNumber: Int? = null,
    val isDub: Boolean = false,
    val kind: LatanimeCardKind = LatanimeCardKind.ANIME,
)

data class LatanimeCatalogPage(
    val items: List<LatanimeCard>,
    val hasNext: Boolean,
    val maxPage: Int = 1,
)

data class LatanimeEpisodeItem(
    val name: String,
    val url: String,
    val episode: Int,
    val posterUrl: String?,
)

data class LatanimeAnimeDetail(
    val title: String,
    val altTitle: String?,
    val url: String,
    val posterUrl: String?,
    val status: LatanimeShowStatus?,
    val year: Int?,
    val tags: List<String>,
    val plot: String?,
    val episodes: List<LatanimeEpisodeItem>,
    val kind: LatanimeCardKind,
    val isDub: Boolean,
)

data class LatanimePlayerSource(
    val label: String,
    val url: String,
    val isDownload: Boolean = false,
)

data class LatanimeEpisodePage(
    val title: String,
    val url: String,
    val parentAnimeUrl: String?,
    val players: List<LatanimePlayerSource>,
    val downloads: List<LatanimePlayerSource>,
)

object LatanimeMarkup {
    const val DEFAULT_MAIN_URL = "https://latanime.org"
    const val SECTION_HOME_EPISODES = "section:home-episodes"
    const val SECTION_CALENDAR = "section:calendario"

    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9\s]+""")
    private val MULTISPACE_REGEX = Regex("""\s+""")
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val URL_EPISODE_REGEX = Regex("""-(?:episodio|capitulo|ova|ona|pelicula|especial|corto)-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val TEXT_EPISODE_REGEX = Regex("""(?:episodio|cap[ií]tulo|ep\.?|pr[óo]ximo)\s*[-:#]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val TRAILING_EP_REGEX = Regex("""\s*-\s*(\d+)\s*$""")
    private val PARENT_FROM_VER_REGEX = Regex("""/ver/([^/?#]+?)-(?:episodio|capitulo)-\d+""", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER_IMAGE_MARKERS = listOf("capblank.png", "loader.gif", "blank.png", "placeholder")
    private val P_PARAM_REGEX = Regex("""([?&])p=\d+""")

    fun mainPageCategories(baseUrl: String = DEFAULT_MAIN_URL): List<Pair<String, String>> {
        val cleanBase = baseUrl.trimEnd('/')
        return listOf(
            SECTION_HOME_EPISODES to "Últimos Capítulos",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=latino" to "Audio Latino",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=Pel%C3%ADcula%20Latino" to "Películas Latino",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=ova-latino" to "OVAs Latino",
            "$cleanBase/emision" to "En Emisión",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=castellano" to "Castellano",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=Pel%C3%ADcula%20Castellano" to "Películas Castellano",
            "$cleanBase/animes" to "Catálogo Reciente",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=Pel%C3%ADcula" to "Películas",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=ova" to "OVAs",
            "$cleanBase/animes?fecha=false&genero=false&letra=false&categoria=sin-censura" to "Sin Censura",
            SECTION_CALENDAR to "Calendario Semanal",
            "$cleanBase/animes?fecha=false&genero=accion&letra=false&categoria=false" to "Acción",
            "$cleanBase/animes?fecha=false&genero=aventura&letra=false&categoria=false" to "Aventura",
            "$cleanBase/animes?fecha=false&genero=comedia&letra=false&categoria=false" to "Comedia",
            "$cleanBase/animes?fecha=false&genero=drama&letra=false&categoria=false" to "Drama",
            "$cleanBase/animes?fecha=false&genero=fantasia&letra=false&categoria=false" to "Fantasía",
            "$cleanBase/animes?fecha=false&genero=isekai&letra=false&categoria=false" to "Isekai",
            "$cleanBase/animes?fecha=false&genero=romance&letra=false&categoria=false" to "Romance",
            "$cleanBase/animes?fecha=false&genero=shonen&letra=false&categoria=false" to "Shonen",
            "$cleanBase/animes?fecha=false&genero=sobrenatural&letra=false&categoria=false" to "Sobrenatural",
            "$cleanBase/animes?fecha=false&genero=ciencia-ficcion&letra=false&categoria=false" to "Sci-Fi",
        )
    }

    fun resolveUrl(baseUrl: String, raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        if (trimmed.startsWith("data:", ignoreCase = true)) return null
        val cleanBase = baseUrl.trimEnd('/')
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$cleanBase$trimmed"
            else -> "$cleanBase/$trimmed"
        }
    }

    fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")

    fun isOwnedSiteUrl(url: String, baseUrl: String = DEFAULT_MAIN_URL): Boolean {
        val host = hostOf(url)
        val mainHost = hostOf(baseUrl).removePrefix("www.")
        if (host.isEmpty() || mainHost.isEmpty()) return false
        return host == mainHost || host == "www.$mainHost" || host.endsWith(".$mainHost")
    }

    fun isOwnedAnimeUrl(url: String, baseUrl: String = DEFAULT_MAIN_URL): Boolean {
        if (!isOwnedSiteUrl(url, baseUrl)) return false
        val path = runCatching { URI(url).path }.getOrDefault("")
        return path.startsWith("/anime/") && path.removePrefix("/anime/").trim('/').isNotEmpty()
    }

    fun isOwnedEpisodeUrl(url: String, baseUrl: String = DEFAULT_MAIN_URL): Boolean {
        if (!isOwnedSiteUrl(url, baseUrl)) return false
        val path = runCatching { URI(url).path }.getOrDefault("")
        return path.startsWith("/ver/") && path.removePrefix("/ver/").trim('/').isNotEmpty()
    }

    fun slugFromUrl(url: String): String {
        val path = runCatching { URI(url).path.trimEnd('/') }.getOrDefault("")
        return path.substringAfterLast('/')
    }

    fun buildPagedUrl(targetUrl: String, page: Int, baseUrl: String = DEFAULT_MAIN_URL): String {
        val cleanBase = baseUrl.trimEnd('/')
        val absolute = resolveUrl(cleanBase, targetUrl) ?: cleanBase
        if (page <= 1) {
            return P_PARAM_REGEX.replace(absolute) { m -> if (m.groupValues[1] == "?") "?" else "" }
                .trimEnd('?', '&')
        }
        val replaced = P_PARAM_REGEX.replace(absolute) { m -> "${m.groupValues[1]}p=$page" }
        if (replaced != absolute) return replaced
        val separator = if ('?' in absolute) '&' else '?'
        return "$absolute${separator}p=$page"
    }

    fun buildSearchUrl(query: String, page: Int, baseUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val base = "${baseUrl.trimEnd('/')}/buscar?q=$encoded"
        return if (page <= 1) base else "$base&p=$page"
    }

    fun buildLetterFilterUrl(letter: String, page: Int = 1, baseUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(letter, StandardCharsets.UTF_8.name())
        val base = "${baseUrl.trimEnd('/')}/animes?fecha=false&genero=false&letra=$encoded&categoria=false"
        return if (page <= 1) base else "$base&p=$page"
    }

    fun decodeBase64Player(encoded: String?): String? {
        val raw = encoded?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val cleaned = raw.replace("\n", "").replace("\r", "").replace(" ", "")
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
            ?: return null
        val decoded = String(bytes, StandardCharsets.UTF_8).trim()
        val normalized = if (decoded.startsWith("//")) "https:$decoded" else decoded
        return normalized.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    fun pickBestImageUrl(img: Element?, baseUrl: String = DEFAULT_MAIN_URL): String? {
        if (img == null) return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-original"),
            img.attr("data-lazy-src"),
            img.attr("src"),
        )
        for (candidate in candidates) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            if (PLACEHOLDER_IMAGE_MARKERS.any { lower.contains(it) }) continue
            val resolved = resolveUrl(baseUrl, trimmed)
            if (!resolved.isNullOrBlank()) return resolved
        }
        return null
    }

    fun inferIsDub(badge: String?, title: String?, url: String?): Boolean {
        val combined = listOfNotNull(badge, title, url).joinToString(" ").lowercase()
        return combined.contains("latino") ||
            combined.contains("castellano") ||
            combined.contains("español") ||
            combined.contains("espanol")
    }

    fun inferCardKind(badge: String?, title: String?, url: String?): LatanimeCardKind {
        val badgeLower = badge?.lowercase().orEmpty()
        val combined = "$badgeLower ${title?.lowercase().orEmpty()} ${url?.lowercase().orEmpty()}"
        if (badgeLower.contains("pelicula") || badgeLower.contains("película") || badgeLower.contains("corto")) {
            return LatanimeCardKind.MOVIE
        }
        if (badgeLower.contains("ova") || badgeLower.contains("especial") || badgeLower.contains("ona")) {
            return LatanimeCardKind.OVA
        }
        if (combined.contains("-ova") || Regex("""\bova\b""").containsMatchIn(title?.lowercase().orEmpty())) {
            return LatanimeCardKind.OVA
        }
        if (Regex("""\b(pel[ií]cula|movie)\b""").containsMatchIn(title?.lowercase().orEmpty()) &&
            !Regex("""\bpel[ií]culas\b""").containsMatchIn(title?.lowercase().orEmpty())
        ) {
            return LatanimeCardKind.MOVIE
        }
        return LatanimeCardKind.ANIME
    }

    fun isMovieOrOvaFromBadge(kind: LatanimeCardKind): String = kind.name

    fun parseEpisodeNumber(url: String, text: String?): Int? {
        URL_EPISODE_REGEX.find(url)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()?.let { return it }
        val cleanText = text.orEmpty()
        TEXT_EPISODE_REGEX.find(cleanText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        TRAILING_EP_REGEX.find(cleanText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun cleanHomeEpisodeTitle(rawText: String, episodeNum: Int?): String {
        var title = rawText.replace('\u00A0', ' ').replace(MULTISPACE_REGEX, " ").trim()
        title = title.replace(Regex("""(?i)^(?:episodio|cap[ií]tulo|ep\.?)\s*\d+(?:\.\d+)?\s*[-:]*\s*"""), "")
        title = title.replace(Regex("""(?i)\s*-\s*(?:episodio|cap[ií]tulo)\s*\d+.*$"""), "")
        title = title.replace(Regex("""(?i)\s+(?:episodio|cap[ií]tulo)\s*\d+.*$"""), "")
        if (episodeNum != null) {
            title = title.replace(Regex("""\s*-\s*$episodeNum\s*$"""), "")
            title = title.replace(Regex("""^$episodeNum\s*[-:]\s*"""), "")
        }
        return title.trim().ifEmpty { rawText.trim() }
    }

    fun parseHomeRecentEpisodes(doc: Document, baseUrl: String = DEFAULT_MAIN_URL): List<LatanimeCard> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<LatanimeCard>()
        val links = doc.select("a[href*=/ver/]")
        for (a in links) {
            if (a.parents().any { it.id() == "carouselExampleCaptions" || it.tagName() in setOf("nav", "header", "footer") }) {
                continue
            }
            val href = resolveUrl(baseUrl, a.attr("href")) ?: continue
            if (!isOwnedEpisodeUrl(href, baseUrl) || !seen.add(href)) continue

            val epNum = parseEpisodeNumber(href, a.text())
            val titleEl = a.selectFirst("h2, h3, h4, .cap_title, .title")
            val imgEl = a.selectFirst("img")
            val rawHeading = titleEl?.text()?.trim().orEmpty()
                .ifEmpty { imgEl?.attr("alt")?.trim().orEmpty() }
                .ifEmpty { a.text().trim() }
            val title = cleanHomeEpisodeTitle(rawHeading, epNum)
            if (title.isBlank()) continue

            val poster = pickBestImageUrl(imgEl, baseUrl)
            val isDub = inferIsDub(null, "$title $href", href)
            results.add(
                LatanimeCard(
                    title = title,
                    url = href,
                    posterUrl = poster,
                    episodeNumber = epNum,
                    isDub = isDub,
                    kind = LatanimeCardKind.ANIME,
                )
            )
        }
        return results
    }

    fun parseCatalogCards(doc: Document, baseUrl: String = DEFAULT_MAIN_URL, currentPage: Int = 1): LatanimeCatalogPage {
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<LatanimeCard>()

        val anchors = doc.select("a[href*=/anime/]")
        for (a in anchors) {
            if (a.parents().any { it.id() == "carouselExampleCaptions" || it.tagName() in setOf("nav", "header", "footer") }) {
                continue
            }
            val url = resolveUrl(baseUrl, a.attr("href")) ?: continue
            if (!isOwnedAnimeUrl(url, baseUrl) || !seen.add(url)) continue

            val badge = a.selectFirst(".seriedetails span.opacity-75, .seriedetails span, span.badge, span.opacity-75")
                ?.text()?.trim()?.ifEmpty { null }
            val year = a.select(".seriedetails span")
                .mapNotNull { YEAR_REGEX.find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
                .firstOrNull()
            val rawTitle = a.selectFirst("h3, h2, h4, .seriedetails .my-1")?.text()?.trim().orEmpty()
                .ifEmpty { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifEmpty {
                    val cloneText = a.text().trim()
                    if (!badge.isNullOrBlank() && cloneText.startsWith(badge)) {
                        cloneText.removePrefix(badge).trim()
                    } else {
                        cloneText
                    }
                }
            if (rawTitle.isBlank()) continue

            val poster = pickBestImageUrl(a.selectFirst("img"), baseUrl)
            val epNum = badge?.let { TEXT_EPISODE_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val kind = inferCardKind(badge, rawTitle, url)
            val isDub = inferIsDub(badge, rawTitle, url)

            items.add(
                LatanimeCard(
                    title = rawTitle,
                    url = url,
                    posterUrl = poster,
                    badge = badge,
                    year = year,
                    episodeNumber = epNum,
                    isDub = isDub,
                    kind = kind,
                )
            )
        }

        val maxPage = extractMaxPage(doc, currentPage)
        val hasNext = items.isNotEmpty() && hasNextPagination(doc, currentPage, maxPage)
        return LatanimeCatalogPage(items = items, hasNext = hasNext, maxPage = maxPage)
    }

    fun parseCalendarCards(doc: Document, baseUrl: String = DEFAULT_MAIN_URL): List<LatanimeCard> {
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<LatanimeCard>()
        val panes = doc.select("div#Calendario div.tab-pane, div.tab-pane")
        val containers = if (panes.isNotEmpty()) panes else listOf(doc)
        for (container in containers) {
            for (a in container.select("a[href*=/anime/]")) {
                val url = resolveUrl(baseUrl, a.attr("href")) ?: continue
                if (!isOwnedAnimeUrl(url, baseUrl) || !seen.add(url)) continue
                val title = a.selectFirst("h3, h2, h4")?.text()?.trim().orEmpty()
                    .ifEmpty { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                if (title.isBlank()) continue
                val badge = a.selectFirst(".bg-calendario span.badge, span.badge")?.text()?.trim()
                val epNum = badge?.let { TEXT_EPISODE_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                val poster = pickBestImageUrl(a.selectFirst("img"), baseUrl)
                val kind = inferCardKind(badge, title, url)
                val isDub = inferIsDub(badge, title, url)
                items.add(
                    LatanimeCard(
                        title = title,
                        url = url,
                        posterUrl = poster,
                        badge = badge,
                        episodeNumber = epNum,
                        isDub = isDub,
                        kind = kind,
                    )
                )
            }
        }
        return items
    }

    private fun extractMaxPage(doc: Document, currentPage: Int): Int {
        var maxPage = currentPage
        for (a in doc.select("ul.pagination a.page-link, ul.pagination a")) {
            val txtNum = a.text().trim().toIntOrNull()
            if (txtNum != null && txtNum > maxPage) maxPage = txtNum
            val href = a.attr("href")
            Regex("""[?&]p=(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                if (it > maxPage) maxPage = it
            }
        }
        return maxPage
    }

    private fun hasNextPagination(doc: Document, currentPage: Int, maxPage: Int): Boolean {
        if (maxPage > currentPage) return true
        val pagItems = doc.select("ul.pagination li.page-item")
        if (pagItems.isEmpty()) return false
        for (li in pagItems) {
            if (li.hasClass("disabled")) continue
            val a = li.selectFirst("a") ?: continue
            val txt = a.text().trim()
            if (txt == "›" || txt == "»" || txt.equals("Next", ignoreCase = true) || txt.equals("Siguiente", ignoreCase = true)) {
                return true
            }
            val num = txt.toIntOrNull()
            if (num != null && num > currentPage) return true
        }
        return false
    }

    fun parseAnimeDetail(
        doc: Document,
        pageUrl: String,
        baseUrl: String = DEFAULT_MAIN_URL,
        cachedKind: LatanimeCardKind? = null,
    ): LatanimeAnimeDetail? {
        val title = doc.selectFirst("div.col-lg-9 h2, .container h2")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val altTitle = doc.selectFirst("div.col-lg-9 h3.fs-6, .container h3.fs-6")?.text()?.trim()?.ifBlank { null }
        val posterUrl = pickBestImageUrl(doc.selectFirst(".serieimgficha img, img.img-fluid2"), baseUrl)

        val statusText = doc.selectFirst(".btn-estado")?.text()?.trim()?.lowercase().orEmpty()
        val status = when {
            statusText.contains("estreno") || statusText.contains("emision") || statusText.contains("emisión") -> LatanimeShowStatus.ONGOING
            statusText.contains("finalizado") -> LatanimeShowStatus.COMPLETED
            else -> null
        }

        val yearText = doc.selectFirst(".span-tiempo")?.text().orEmpty()
        val year = YEAR_REGEX.find(yearText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = doc.select("a[href*=/genero/] .btn, a[href*=/genero/]")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()

        val rawPlot = doc.selectFirst("p.my-2.opacity-75, .col-lg-9 p.opacity-75")?.text()?.trim()?.ifBlank { null }
        val plot = buildString {
            if (!altTitle.isNullOrBlank() && !altTitle.equals(title, ignoreCase = true)) {
                append("Título alternativo: ")
                append(altTitle)
                if (!rawPlot.isNullOrBlank()) append("\n\n")
            }
            if (!rawPlot.isNullOrBlank()) append(rawPlot)
        }.ifBlank { null }

        val epContainer = doc.selectFirst("div[style*=overflow-y]") ?: doc
        val seenEp = LinkedHashSet<String>()
        val rawEpisodes = mutableListOf<Pair<Int, LatanimeEpisodeItem>>()
        epContainer.select("a[href*=/ver/]").forEachIndexed { index, a ->
            val epUrl = resolveUrl(baseUrl, a.attr("href")) ?: return@forEachIndexed
            if (!isOwnedEpisodeUrl(epUrl, baseUrl) || !seenEp.add(epUrl)) return@forEachIndexed

            val rawText = a.text().replace('\u00A0', ' ').replace(MULTISPACE_REGEX, " ").trim()
            val epNum = parseEpisodeNumber(epUrl, rawText) ?: (index + 1)
            val epPoster = pickBestImageUrl(a.selectFirst("img"), baseUrl) ?: posterUrl
            val epName = formatEpisodeName(rawText, title, epNum)
            rawEpisodes.add(
                index to LatanimeEpisodeItem(
                    name = epName,
                    url = epUrl,
                    episode = epNum,
                    posterUrl = epPoster,
                )
            )
        }
        val episodes = rawEpisodes
            .sortedWith(compareBy<Pair<Int, LatanimeEpisodeItem>> { it.second.episode }.thenBy { it.first })
            .map { it.second }

        val isDub = inferIsDub(null, "$title ${altTitle.orEmpty()}", pageUrl)
        val rawKind = cachedKind ?: inferCardKind(null, "$title ${altTitle.orEmpty()}", pageUrl)
        val resolvedKind = when {
            episodes.size > 1 && rawKind == LatanimeCardKind.MOVIE -> LatanimeCardKind.ANIME
            episodes.size == 1 && rawKind == LatanimeCardKind.MOVIE && status == LatanimeShowStatus.ONGOING -> LatanimeCardKind.ANIME
            else -> rawKind
        }

        return LatanimeAnimeDetail(
            title = title,
            altTitle = altTitle,
            url = pageUrl,
            posterUrl = posterUrl,
            status = status,
            year = year,
            tags = tags,
            plot = plot,
            episodes = episodes,
            kind = resolvedKind,
            isDub = isDub,
        )
    }

    private fun formatEpisodeName(rawText: String, animeTitle: String, epNum: Int): String {
        val withoutTitle = if (rawText.startsWith(animeTitle, ignoreCase = true)) {
            rawText.substring(animeTitle.length).trim().trimStart('-', ':').trim()
        } else {
            rawText
        }
        if (withoutTitle.isBlank()) return "Episodio $epNum"
        val normalized = withoutTitle.replace(Regex("""(?i)^cap[ií]tulo\s+(\d+)$"""), "Episodio $1")
        return normalized.ifBlank { "Episodio $epNum" }
    }

    fun inferParentAnimeUrl(episodeUrl: String, baseUrl: String = DEFAULT_MAIN_URL): String? {
        val m = PARENT_FROM_VER_REGEX.find(episodeUrl) ?: return null
        val slug = m.groupValues[1].trim('/')
        if (slug.isEmpty()) return null
        return "${baseUrl.trimEnd('/')}/anime/$slug"
    }

    fun parseEpisodePage(doc: Document, pageUrl: String, baseUrl: String = DEFAULT_MAIN_URL): LatanimeEpisodePage {
        val title = doc.selectFirst("h1, h2, .container h3")?.text()?.trim().orEmpty()
            .ifEmpty { slugFromUrl(pageUrl).replace('-', ' ').replaceFirstChar { it.uppercase() } }

        val parentAnimeUrl = doc.select("a[href*=/anime/]")
            .firstOrNull { a ->
                a.parents().none { it.tagName() in setOf("nav", "header", "footer") } &&
                    isOwnedAnimeUrl(resolveUrl(baseUrl, a.attr("href")).orEmpty(), baseUrl)
            }
            ?.let { resolveUrl(baseUrl, it.attr("href")) }
            ?: inferParentAnimeUrl(pageUrl, baseUrl)

        val seenKeys = HashSet<String>()
        val players = mutableListOf<LatanimePlayerSource>()
        for (el in doc.select("[data-player]")) {
            val decoded = decodeBase64Player(el.attr("data-player")) ?: continue
            val label = el.text().trim().ifBlank { hostOf(decoded) }
            val dedupKey = sourceDedupKey(decoded)
            if (seenKeys.add(dedupKey)) {
                players.add(LatanimePlayerSource(label = label, url = decoded, isDownload = false))
            }
        }

        val downloads = mutableListOf<LatanimePlayerSource>()
        for (a in doc.select("a.direct-link[href]")) {
            val href = resolveUrl(baseUrl, a.attr("href")) ?: continue
            if (!href.startsWith("http://", ignoreCase = true) && !href.startsWith("https://", ignoreCase = true)) {
                continue
            }
            val label = a.text().trim().ifBlank { hostOf(href) }
            val dedupKey = sourceDedupKey(href)
            if (seenKeys.add(dedupKey)) {
                downloads.add(LatanimePlayerSource(label = label, url = href, isDownload = true))
            }
        }

        return LatanimeEpisodePage(
            title = title,
            url = pageUrl,
            parentAnimeUrl = parentAnimeUrl,
            players = players,
            downloads = downloads,
        )
    }

    internal fun sourceDedupKey(url: String): String {
        val trimmed = url.trim().substringBefore('#')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.lowercase()
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val path = uri.path.orEmpty().trim('/')
        // Normalize embed vs download mirrors of the same host/file code (e.g. savefiles /e/X vs /d/X)
        val bucketHost = when {
            host.startsWith("savefiles.") -> "savefiles"
            host.startsWith("byse") || host == "n1mwq.org" -> "byse"
            host.startsWith("uqload.") -> "uqload"
            host.startsWith("mixdrop.") || host.startsWith("mxdrop.") -> "mixdrop"
            host == "playmogo.com" || host == "do7go.com" || host == "dsvplay.com" -> "dood"
            else -> host
        }
        val code = when {
            path.startsWith("e/") || path.startsWith("d/") -> path.substring(2).substringBefore('/')
            path.startsWith("embed-") -> path.removePrefix("embed-").substringBefore('.')
            else -> path
        }
        return "$bucketHost:$code"
    }

    fun normalizeForSearch(text: String): String {
        val decomposed = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        val noDiacritics = DIACRITICS_REGEX.replace(decomposed, "")
        return MULTISPACE_REGEX.replace(NON_ALNUM_REGEX.replace(noDiacritics.lowercase(), " "), " ").trim()
    }

    fun candidateLetters(query: String): List<String> {
        val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val letters = LinkedHashSet<String>()
        fun tokenToFilterLetter(tok: String): String {
            val ch = tok.first()
            return if (ch.isDigit()) "09" else ch.uppercaseChar().toString()
        }
        letters.add(tokenToFilterLetter(tokens.first()))
        tokens.filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .forEach { tok ->
                if (letters.size < 2) letters.add(tokenToFilterLetter(tok))
            }
        return letters.toList()
    }

    fun scoreSearchMatch(card: LatanimeCard, query: String): Int {
        val normQuery = normalizeForSearch(query)
        if (normQuery.isEmpty()) return 0
        val tokens = normQuery.split(' ').filter { it.isNotEmpty() }
        val normTitle = normalizeForSearch(card.title)
        val normSlug = normalizeForSearch(slugFromUrl(card.url).replace('-', ' '))

        if (normTitle == normQuery || normSlug == normQuery) return 1000
        if (normTitle.startsWith(normQuery)) return 700
        if (normTitle.contains(normQuery)) return 550
        if (normSlug.contains(normQuery)) return 450

        val allInTitle = tokens.all { normTitle.contains(it) }
        if (allInTitle) return 350

        val allInCombined = tokens.all { normTitle.contains(it) || normSlug.contains(it) }
        if (allInCombined) return 220

        return 0
    }

    fun filterAndRankCards(cards: Iterable<LatanimeCard>, query: String): List<LatanimeCard> {
        return cards
            .distinctBy { it.url }
            .mapNotNull { card ->
                val score = scoreSearchMatch(card, query)
                if (score > 0) score to card else null
            }
            .sortedWith(compareByDescending<Pair<Int, LatanimeCard>> { it.first }.thenBy { it.second.title.length })
            .map { it.second }
    }

    fun isCloudflareChallenge(code: Int, body: String, cfMitigatedHeader: String? = null): Boolean {
        if (cfMitigatedHeader.equals("challenge", ignoreCase = true)) return true
        val lower = body.lowercase()
        val hasMarker = lower.contains("just a moment") ||
            lower.contains("un momento") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("_cf_chl_opt")
        return hasMarker && (code == 403 || code == 503 || !lower.contains("/anime/"))
    }
}
