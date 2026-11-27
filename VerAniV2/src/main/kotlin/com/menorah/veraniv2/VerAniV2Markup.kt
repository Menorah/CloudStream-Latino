package com.menorah.veraniv2

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.ShowStatus
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class VerAniPageKind {
    ANIME,
    MOVIE,
    CHAPTER,
    UNKNOWN,
}

internal data class VerAniCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isMovie: Boolean,
    val year: Int? = null,
    val episodesCount: Int? = null,
    val latestEpisode: Int? = null,
    val isDub: Boolean = false,
)

internal data class VerAniPagedResult(
    val items: List<VerAniCard>,
    val hasNext: Boolean,
)

internal data class VerAniEpisodeItem(
    val url: String,
    val name: String?,
    val season: Int,
    val episode: Int,
    val posterUrl: String?,
)

internal data class VerAniSeasonMeta(
    val season: Int,
    val name: String,
)

internal data class VerAniAnimeDetail(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val year: Int?,
    val plot: String?,
    val status: ShowStatus?,
    val tags: List<String>,
    val trailerUrl: String?,
    val seasons: List<VerAniSeasonMeta>,
    val episodes: List<VerAniEpisodeItem>,
    val recommendations: List<VerAniCard>,
)

internal data class VerAniMovieDetail(
    val title: String,
    val originalTitle: String?,
    val url: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val year: Int?,
    val plot: String?,
    val tags: List<String>,
)

internal data class VerAniChapterDetail(
    val title: String,
    val url: String,
    val parentAnimeUrl: String?,
    val backdropUrl: String?,
)

internal data class VerAniPlayerTab(
    val url: String,
    val label: String,
    val servidorId: String,
    val idioma: String,
    val subtitulos: String,
    val badge: String,
)

internal object VerAniV2Markup {
    const val DEFAULT_MAIN_URL = "https://verani.me"
    const val SECTION_WEEKLY = "home:estrenos"

    val jsonMapper: ObjectMapper by lazy {
        ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val FIRST_INT_REGEX = Regex("""(\d+)""")
    private val CAP_NUM_REGEX = Regex("""(?:cap[ií]tulo|episodio|ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val GENERIC_EP_NAME_REGEX = Regex("""^(?:cap[ií]tulo|episodio|ep\.?)\s*\d+$""", RegexOption.IGNORE_CASE)
    private val SUPPORTED_DOWNLOAD_HOSTS = setOf(
        "voe.sx",
        "mp4upload.com",
        "vidara.so",
        "mxdrop.sx",
        "mxdrop.click",
        "animeav1.uns.bio",
        "turboembed.com",
        "turbovio.com",
        "player.zilla-networks.com",
        "byselapuix.com",
        "bysekoze.com",
        "byse.sx",
        "bysebuho.com",
        "playmogo.com",
    )

    fun buildArchivePageUrl(rawUrl: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val baseMain = mainUrl.trimEnd('/')
        if (rawUrl == SECTION_WEEKLY) return "$baseMain/"
        val resolved = resolveUrl(baseMain, rawUrl) ?: return rawUrl
        if (page <= 1) return resolved

        val qIdx = resolved.indexOf('?')
        val pathPart = if (qIdx >= 0) resolved.substring(0, qIdx) else resolved
        val queryPart = if (qIdx >= 0) resolved.substring(qIdx) else ""
        val cleanPath = pathPart.replace(Regex("""/page/\d+/?$"""), "").trimEnd('/')
        return "$cleanPath/page/$page/$queryPart"
    }

    fun buildSearchUrl(query: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val baseMain = mainUrl.trimEnd('/')
        return if (page <= 1) {
            "$baseMain/?s=$encoded"
        } else {
            "$baseMain/?s=$encoded&paged=$page"
        }
    }

    fun resolveUrl(baseUrl: String, raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned == "#" || cleaned.startsWith("javascript:", ignoreCase = true) || cleaned.startsWith("data:", ignoreCase = true)) {
            return null
        }
        if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)) {
            return cleaned
        }
        if (cleaned.startsWith("//")) {
            val scheme = runCatching { URI(baseUrl).scheme }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "https"
            return "$scheme:$cleaned"
        }
        return runCatching {
            URI(baseUrl.trimEnd('/') + "/").resolve(cleaned).toString()
        }.getOrNull()
    }

    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    }

    fun isOwnedContentUrl(url: String?, mainUrl: String = DEFAULT_MAIN_URL): Boolean {
        if (url.isNullOrBlank()) return false
        val targetHost = hostOf(url).removePrefix("www.")
        val mainHost = hostOf(mainUrl).ifEmpty { "verani.me" }.removePrefix("www.")
        if (targetHost.isEmpty() || targetHost != mainHost) return false

        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        if (path.isEmpty() || path == "/") return false
        if (path == "/animes/" ||
            path == "/peliculas/" ||
            path.startsWith("/animes/") ||
            path.startsWith("/genero/") ||
            path.startsWith("/author/") ||
            path.startsWith("/wp-") ||
            path.startsWith("/page/")
        ) {
            return false
        }
        return true
    }

    fun isSiteLogoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val low = url.lowercase()
        return low.contains("gemini_generated_image") || low.contains("site-logo")
    }

    fun extractImageUrl(el: Element?, baseUrl: String): String? {
        if (el == null) return null
        val candidates = listOf(
            el.attr("data-src"),
            el.attr("data-lazy-src"),
            el.attr("data-original"),
            el.attr("src"),
        )
        for (c in candidates) {
            val resolved = resolveUrl(baseUrl, c)
            if (!resolved.isNullOrBlank() && !isSiteLogoUrl(resolved)) {
                return resolved
            }
        }
        return null
    }

    fun parseHomeWeeklyUpdates(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<VerAniCard> {
        val section = doc.selectFirst("section.capitulos-section") ?: return emptyList()
        val cards = section.select(".capitulos-grid .capitulo-card, .capitulo-card")
        val seen = linkedSetOf<String>()
        val result = mutableListOf<VerAniCard>()
        for (card in cards) {
            val linkEl = card.selectFirst("a.card-link[href]") ?: card.selectFirst("a[href]")
            val href = resolveUrl(mainUrl, linkEl?.attr("href")) ?: continue
            if (!isOwnedContentUrl(href, mainUrl) || !seen.add(href)) continue

            val title = (card.selectFirst(".card-title")?.text()
                ?: linkEl?.attr("title")
                ?: card.selectFirst("img")?.attr("alt"))
                .orEmpty()
                .trim()
            if (title.isEmpty()) continue

            val poster = extractImageUrl(card.selectFirst("img.card-img, .card-image img, img"), mainUrl)
            val epBadge = card.selectFirst(".badge-ep")?.text().orEmpty()
            val subText = card.selectFirst(".card-subtitle")?.text().orEmpty()
            val epNum = CAP_NUM_REGEX.find("$epBadge $subText")?.groupValues?.get(1)?.toIntOrNull()
                ?: FIRST_INT_REGEX.find(epBadge)?.groupValues?.get(1)?.toIntOrNull()
                ?: FIRST_INT_REGEX.find(subText)?.groupValues?.get(1)?.toIntOrNull()

            val langBadge = card.select(".cap-lang-badge").text().uppercase()
            val isDub = langBadge.contains("LAT") || langBadge.contains("ESP") || langBadge.contains("DUB")

            result += VerAniCard(
                title = title,
                url = href,
                posterUrl = poster,
                isMovie = false,
                latestEpisode = epNum,
                isDub = isDub,
            )
        }
        return result
    }

    fun parseArchiveCards(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): VerAniPagedResult {
        val container = doc.selectFirst(
            "div.animes-archive-grid, div.peliculas-archive-grid, div.search-results-grid, section.animes-section .animes-grid, section.peliculas-section .peliculas-grid, main#main",
        ) ?: doc.body()
        val cardNodes = container.select(".content-card")
        val seen = linkedSetOf<String>()
        val items = mutableListOf<VerAniCard>()

        for (card in cardNodes) {
            // Skip news/activity promo widgets if container fell back to <main>
            if (card.closest(".home-news-section, .home-activity, .top-animes-sidebar, aside") != null) {
                continue
            }
            val linkEl = card.selectFirst("a.card-link[href]") ?: card.selectFirst("a[href]")
            val href = resolveUrl(mainUrl, linkEl?.attr("href")) ?: continue
            if (!isOwnedContentUrl(href, mainUrl) || !seen.add(href)) continue

            val title = (card.selectFirst(".card-title")?.text()
                ?: linkEl?.attr("title")
                ?: card.selectFirst("img")?.attr("alt"))
                .orEmpty()
                .trim()
            if (title.isEmpty()) continue

            val poster = extractImageUrl(card.selectFirst(".card-image img, img.card-img, img"), mainUrl)
            val isMovie = card.hasClass("pelicula-card") || href.lowercase().contains("/pelicula/")
            val metaText = card.selectFirst(".overlay-meta")?.text().orEmpty()
            val year = YEAR_REGEX.find(metaText)?.groupValues?.get(1)?.toIntOrNull()
            val epCount = FIRST_INT_REGEX.find(card.selectFirst(".chapters-count")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()

            items += VerAniCard(
                title = title,
                url = href,
                posterUrl = poster,
                isMovie = isMovie,
                year = year,
                episodesCount = epCount,
            )
        }

        val hasNext = doc.selectFirst("nav.pagination-nav a.next.page-numbers, ul.page-numbers a.next.page-numbers, a.next.page-numbers") != null
        return VerAniPagedResult(items = items, hasNext = hasNext)
    }

    fun detectPageKind(doc: Document): VerAniPageKind {
        if (doc.selectFirst("section.pelicula-hero-section") != null) {
            return VerAniPageKind.MOVIE
        }
        if (doc.selectFirst("section.anime-hero-section, .temporada-group") != null) {
            return VerAniPageKind.ANIME
        }
        if (doc.selectFirst("section.pelicula-reproductores-section") != null) {
            return VerAniPageKind.MOVIE
        }
        if (doc.selectFirst("section.capitulo-hero-section") != null) {
            return VerAniPageKind.CHAPTER
        }
        if (doc.selectFirst("section.capitulo-reproductores-section, button.servidor-tab") != null) {
            return VerAniPageKind.CHAPTER
        }
        if (doc.selectFirst("section.anime-capitulos-section, .capitulo-card-link") != null) {
            return VerAniPageKind.ANIME
        }
        return VerAniPageKind.UNKNOWN
    }

    fun parseAnimeDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): VerAniAnimeDetail? {
        val title = doc.selectFirst("h1.anime-hero-title, h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val backdrop = extractImageUrl(doc.selectFirst("img.anime-hero-bg-image, .anime-hero-background img"), mainUrl)
        val ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.let { resolveUrl(mainUrl, it) }
            ?.takeUnless { isSiteLogoUrl(it) }
        val poster = ogImg ?: backdrop

        val yearText = doc.selectFirst(".anime-hero-year")?.text().orEmpty()
            .ifBlank { findInfoCardValue(doc, "Año") }
        val year = YEAR_REGEX.find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val plot = doc.selectFirst(".anime-hero-description")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

        val statusRaw = (doc.selectFirst(".anime-hero-status")?.text().orEmpty()
            .ifBlank { findInfoCardValue(doc, "Estado") }).lowercase()
        val showStatus = when {
            statusRaw.contains("emisi") || statusRaw.contains("próxim") || statusRaw.contains("proxim") -> ShowStatus.Ongoing
            statusRaw.contains("finaliz") -> ShowStatus.Completed
            else -> null
        }

        val infoCards = doc.select(".anime-info-section .anime-info-card")
        val genreCard = infoCards.firstOrNull {
            it.selectFirst(".anime-info-label")?.text()?.contains("Género", ignoreCase = true) == true
        }
        val tags = genreCard?.select(".anime-info-value a")
            ?.mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            ?.distinct()
            .orEmpty()

        val trailerUrl = doc.selectFirst("iframe.anime-trailer-iframe[src]")?.attr("src")?.trim()
            ?.let { resolveUrl(mainUrl, it) }

        val recommendations = mutableListOf<VerAniCard>()
        val seenRecs = linkedSetOf<String>()
        for (card in doc.select(".anime-recommendations-section .content-card")) {
            val href = resolveUrl(mainUrl, card.selectFirst("a.card-link[href], a[href]")?.attr("href")) ?: continue
            if (!isOwnedContentUrl(href, mainUrl) || !seenRecs.add(href)) continue
            val rTitle = card.selectFirst(".card-title")?.text()?.trim().orEmpty()
            if (rTitle.isEmpty()) continue
            val rImg = extractImageUrl(card.selectFirst("img.card-img, .card-image img, img"), mainUrl)
            recommendations += VerAniCard(
                title = rTitle,
                url = href,
                posterUrl = rImg,
                isMovie = card.hasClass("pelicula-card") || href.lowercase().contains("/pelicula/"),
            )
        }

        val seasonsList = mutableListOf<VerAniSeasonMeta>()
        val episodesList = mutableListOf<VerAniEpisodeItem>()
        val relatedMovieCards = mutableListOf<Element>()
        val seenEpisodeUrls = linkedSetOf<String>()

        val epSections = doc.select("section.anime-capitulos-section")
        var fallbackSeasonNum = 1
        for (sec in epSections) {
            val secTitle = sec.selectFirst("h2.section-title .title-text, h2.section-title")?.text().orEmpty()
            val isMovieSection = secTitle.contains("Películas", ignoreCase = true) ||
                secTitle.contains("Peliculas", ignoreCase = true) ||
                sec.selectFirst(".peliculas-grid") != null

            if (isMovieSection) {
                relatedMovieCards += sec.select(".capitulo-card")
                continue
            }

            val groups = sec.select(".temporada-group")
            if (groups.isNotEmpty()) {
                for (group in groups) {
                    val nameEl = group.selectFirst(".temporada-name")
                    val rawSeasonName = nameEl?.text()?.trim().orEmpty()
                    val seasonNum = nameEl?.attr("data-season-num")?.toIntOrNull()
                        ?: FIRST_INT_REGEX.find(group.selectFirst(".temporada-badge")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                        ?: FIRST_INT_REGEX.find(rawSeasonName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: fallbackSeasonNum++
                    val seasonName = rawSeasonName.ifEmpty { "Temporada $seasonNum" }
                    seasonsList += VerAniSeasonMeta(season = seasonNum, name = seasonName)

                    val parsedInGroup = parseEpisodeCardsInContainer(group, seasonNum, mainUrl, seenEpisodeUrls)
                    episodesList += parsedInGroup
                }
            } else {
                val parsedFlat = parseEpisodeCardsInContainer(sec, 1, mainUrl, seenEpisodeUrls)
                if (parsedFlat.isNotEmpty()) {
                    seasonsList += VerAniSeasonMeta(season = 1, name = "Temporada 1")
                    episodesList += parsedFlat
                }
            }
        }

        // Order seasons ascending and deduplicate season metadata
        val mergedSeasons = seasonsList
            .distinctBy { it.season }
            .sortedBy { it.season }
            .toMutableList()

        // Append related movies (e.g. Boruto's movie section) as a separate non-zero season
        if (relatedMovieCards.isNotEmpty()) {
            val movieSeasonNum = (mergedSeasons.maxOfOrNull { it.season } ?: 1).coerceAtLeast(1) + 1
            mergedSeasons += VerAniSeasonMeta(season = movieSeasonNum, name = "Películas")
            var movieIdx = 1
            for (card in relatedMovieCards) {
                val epUrl = resolveUrl(mainUrl, card.selectFirst("a.capitulo-card-link[href], a[href]")?.attr("href")) ?: continue
                if (!isOwnedContentUrl(epUrl, mainUrl) || !seenEpisodeUrls.add(epUrl)) continue
                val img = extractImageUrl(card.selectFirst("img.capitulo-card-img, .capitulo-card-image img, img"), mainUrl)
                val mTitle = card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: card.selectFirst(".capitulo-card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Película $movieIdx"
                episodesList += VerAniEpisodeItem(
                    url = epUrl,
                    name = mTitle,
                    season = movieSeasonNum,
                    episode = movieIdx++,
                    posterUrl = img ?: poster,
                )
            }
        }

        val sortedEpisodes = episodesList.sortedWith(
            compareBy<VerAniEpisodeItem> { it.season }.thenBy { it.episode },
        )

        return VerAniAnimeDetail(
            title = title,
            url = url,
            posterUrl = poster,
            backgroundUrl = backdrop,
            year = year,
            plot = plot,
            status = showStatus,
            tags = tags,
            trailerUrl = trailerUrl,
            seasons = mergedSeasons,
            episodes = sortedEpisodes,
            recommendations = recommendations,
        )
    }

    private fun parseEpisodeCardsInContainer(
        container: Element,
        seasonNum: Int,
        mainUrl: String,
        seenUrls: MutableSet<String>,
    ): List<VerAniEpisodeItem> {
        val rawCards = container.select(".capitulo-card")
        if (rawCards.isEmpty()) return emptyList()

        // Site renders chapters newest-first; reverse to restore chronological order
        val orderedCards = rawCards.reversed()
        val parsed = mutableListOf<VerAniEpisodeItem>()
        var fallbackEpNum = 1
        for (card in orderedCards) {
            val epUrl = resolveUrl(mainUrl, card.selectFirst("a.capitulo-card-link[href], a[href]")?.attr("href")) ?: continue
            if (!isOwnedContentUrl(epUrl, mainUrl) || !seenUrls.add(epUrl)) continue

            val titleRaw = card.selectFirst(".capitulo-card-title")?.text()?.trim().orEmpty()
            val subRaw = card.selectFirst(".capitulo-card-subtitle")?.text()?.trim().orEmpty()
            val epNum = CAP_NUM_REGEX.find("$titleRaw $subRaw")?.groupValues?.get(1)?.toIntOrNull()
                ?: FIRST_INT_REGEX.find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
                ?: FIRST_INT_REGEX.find(subRaw)?.groupValues?.get(1)?.toIntOrNull()
                ?: fallbackEpNum
            fallbackEpNum = epNum + 1

            val descriptiveName = when {
                subRaw.isNotEmpty() && !GENERIC_EP_NAME_REGEX.matches(subRaw) -> subRaw
                titleRaw.isNotEmpty() && !GENERIC_EP_NAME_REGEX.matches(titleRaw) -> titleRaw
                else -> null
            }

            val img = extractImageUrl(card.selectFirst("img.capitulo-card-img, .capitulo-card-image img, img"), mainUrl)
            parsed += VerAniEpisodeItem(
                url = epUrl,
                name = descriptiveName,
                season = seasonNum,
                episode = epNum,
                posterUrl = img,
            )
        }
        return parsed
    }

    private fun findInfoCardValue(doc: Document, labelKeyword: String): String {
        val card = doc.select(".anime-info-section .anime-info-card").firstOrNull {
            it.selectFirst(".anime-info-label")?.text()?.contains(labelKeyword, ignoreCase = true) == true
        } ?: return ""
        return card.selectFirst(".anime-info-value")?.text()?.trim().orEmpty()
    }

    fun parseMovieDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): VerAniMovieDetail? {
        val h1 = doc.selectFirst("h1.pelicula-title, h1") ?: return null
        val yearText = h1.selectFirst(".pelicula-year-line")?.text().orEmpty()
        val year = YEAR_REGEX.find(yearText)?.groupValues?.get(1)?.toIntOrNull()
        val h1Clone = h1.clone()
        h1Clone.select(".pelicula-year-line").remove()
        val title = h1Clone.text().trim()
        if (title.isEmpty()) return null

        val originalTitle = doc.selectFirst(".pelicula-original-title")?.text()?.trim()?.ifEmpty { null }
        val backdrop = extractImageUrl(doc.selectFirst("img.pelicula-backdrop-image, .pelicula-hero-backdrop img"), mainUrl)
        val poster = extractImageUrl(doc.selectFirst("img.pelicula-poster-image, .pelicula-poster-wrapper img"), mainUrl)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.let { resolveUrl(mainUrl, it) }
                ?.takeUnless { isSiteLogoUrl(it) }
            ?: backdrop

        val plot = doc.selectFirst(".pelicula-overview-text")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

        val tags = doc.selectFirst(".pelicula-meta-generos .pelicula-meta-item")?.text()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return VerAniMovieDetail(
            title = title,
            originalTitle = originalTitle,
            url = url,
            posterUrl = poster,
            backgroundUrl = backdrop,
            year = year,
            plot = plot,
            tags = tags,
        )
    }

    fun parseChapterDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): VerAniChapterDetail {
        val seriesTitle = doc.selectFirst(".capitulo-hero-breadcrumb a.breadcrumb-text")?.text()?.trim().orEmpty()
        val epLine1 = doc.selectFirst(".capitulo-hero-line1")?.text()?.trim().orEmpty()
        val epSub = doc.selectFirst(".capitulo-hero-subtitle")?.text()?.trim().orEmpty()
        val combined = listOf(seriesTitle, epLine1, epSub).filter { it.isNotEmpty() }.joinToString(" · ")
        val title = combined.ifEmpty {
            doc.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { "Capítulo" }
        }
        val parentAnimeUrl = resolveUrl(
            mainUrl,
            doc.selectFirst(".capitulo-hero-breadcrumb a.breadcrumb-text[href]")?.attr("href")
                ?: doc.selectFirst(".capitulo-hero-breadcrumb a.breadcrumb-back-btn[href]")?.attr("href"),
        )
        val backdrop = extractImageUrl(doc.selectFirst("img.capitulo-hero-bg-image, .capitulo-hero-background img"), mainUrl)
        return VerAniChapterDetail(
            title = title,
            url = url,
            parentAnimeUrl = parentAnimeUrl,
            backdropUrl = backdrop,
        )
    }

    fun formatLanguageBadge(idioma: String, subtitulos: String): String {
        val cleanLang = idioma.trim()
        val cleanSubs = subtitulos.trim()
        val low = cleanLang.lowercase()
        return when {
            low.contains("latino") -> "Latino"
            low.contains("españa") || low.contains("espana") || low.contains("castellano") -> "Castellano"
            low.contains("japon") || low.contains("sub") -> {
                if (cleanSubs.isNotEmpty() && !cleanSubs.equals("No", ignoreCase = true)) {
                    "Sub $cleanSubs"
                } else {
                    "Sub Español"
                }
            }
            cleanLang.isNotEmpty() && cleanSubs.isNotEmpty() -> "$cleanLang · Sub $cleanSubs"
            cleanLang.isNotEmpty() -> cleanLang
            cleanSubs.isNotEmpty() -> "Sub $cleanSubs"
            else -> ""
        }
    }

    fun isBrokenEmbedPlaceholder(url: String): Boolean {
        val low = url.lowercase().trim()
        return low.isEmpty() ||
            low == "#" ||
            low.contains("embed-undef.html") ||
            low.endsWith("/e/") ||
            low.endsWith("/m3u8/")
    }

    fun parsePlayerTabs(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<VerAniPlayerTab> {
        val tabs = mutableListOf<VerAniPlayerTab>()
        val seenUrls = linkedSetOf<String>()

        for (btn in doc.select("section.capitulo-reproductores-section button.servidor-tab[data-url], button.servidor-tab[data-url]")) {
            val rawUrl = resolveUrl(mainUrl, btn.attr("data-url")) ?: continue
            if (isBrokenEmbedPlaceholder(rawUrl) || !seenUrls.add(rawUrl)) continue

            val label = btn.attr("data-label").trim().ifEmpty {
                btn.selectFirst(".servidor-tab-label")?.text()?.trim().orEmpty()
            }
            val servidorId = btn.attr("data-servidor").trim()
            val idioma = btn.attr("data-idioma").trim()
            val subtitulos = btn.attr("data-subtitulos").trim()
            tabs += VerAniPlayerTab(
                url = rawUrl,
                label = label.ifEmpty { "Servidor" },
                servidorId = servidorId,
                idioma = idioma,
                subtitulos = subtitulos,
                badge = formatLanguageBadge(idioma, subtitulos),
            )
        }

        // Fallback: active/embedded iframe if a legacy post has no button.servidor-tab
        for (iframe in doc.select(".capitulo-reproductores-section .iframe-wrapper iframe")) {
            val src = resolveUrl(mainUrl, iframe.attr("data-src").ifBlank { iframe.attr("src") }) ?: continue
            if (isBrokenEmbedPlaceholder(src) || !seenUrls.add(src)) continue
            tabs += VerAniPlayerTab(
                url = src,
                label = "Reproductor",
                servidorId = "iframe",
                idioma = "",
                subtitulos = "",
                badge = "",
            )
        }

        // Supplementary supported video download mirrors from .capitulo-descargas-section
        for (a in doc.select("section.capitulo-descargas-section a[href]")) {
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (isBrokenEmbedPlaceholder(href)) continue
            val host = hostOf(href).removePrefix("www.")
            if (SUPPORTED_DOWNLOAD_HOSTS.none { host == it || host.endsWith(".$it") }) continue
            if (!seenUrls.add(href)) continue
            val dlLang = a.closest("[data-idioma]")?.attr("data-idioma")?.trim().orEmpty()
            tabs += VerAniPlayerTab(
                url = href,
                label = "Descarga",
                servidorId = "download",
                idioma = dlLang,
                subtitulos = "",
                badge = formatLanguageBadge(dlLang, ""),
            )
        }

        return tabs
    }
}
