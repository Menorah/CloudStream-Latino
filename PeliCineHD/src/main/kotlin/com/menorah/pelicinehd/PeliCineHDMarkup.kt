package com.menorah.pelicinehd

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer

internal data class PeliCineCard(
    val title: String,
    val url: String,
    val poster: String?,
    val movie: Boolean,
    val year: Int? = null,
    val quality: String? = null,
)

internal data class PeliCineSeason(
    val season: Int,
    val postId: String,
    val label: String,
)

internal data class PeliCineEpisode(
    val title: String,
    val url: String,
    val season: Int,
    val number: Int,
    val poster: String?,
)

internal data class PeliCineDetail(
    val title: String,
    val url: String,
    val movie: Boolean,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val genres: List<String>,
    val actors: List<String>,
    val rating: Double?,
    val trailer: String?,
    val recommendations: List<PeliCineCard>,
)

internal data class PeliCinePlayerOption(
    val url: String,
    val language: String,
    val qualityTag: String?,
    val serverHint: String?,
)

internal object PeliCineHDMarkup {
    const val BASE = "https://pelicinehd.com"
    private val SITE_HOSTS = setOf("pelicinehd.com", "www.pelicinehd.com")
    private val IMAGE_EXT = Regex("""\.(?:jpe?g|png|webp|gif|avif|svg)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val EPISODE_NUM_REGEX = Regex("""(\d+)\s*[xX-]\s*(\d+)""")
    private val EPISODE_URL_REGEX = Regex("""-(\d+)x(\d+)/?$""", RegexOption.IGNORE_CASE)
    private val TAB_ID_REGEX = Regex("""^#([A-Za-z0-9_-]+)$""")
    private val TMDB_POSTER_SIZE = Regex("""(https?://image\.tmdb\.org/t/p/)(?:w92|w154|w185|w300|w342)/""")
    private val TMDB_BACKDROP_SIZE = Regex("""(https?://image\.tmdb\.org/t/p/)(?:w92|w154|w185|w300|w342|w500|w780)/""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")

    val jsonMapper: ObjectMapper = ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun siteUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) &&
            uri.host?.lowercase() in SITE_HOSTS &&
            uri.port in listOf(-1, 443) &&
            uri.userInfo == null
    }

    fun validExternalUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in listOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }

    fun cleanUrlAttr(base: String, raw: String?): String? {
        val cleaned = raw?.trim()
            ?.replace("&#038;", "&")
            ?.replace("#038;", "&")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotEmpty() && !it.startsWith("data:", true) && !it.startsWith("about:", true) && !it.startsWith("javascript:", true) }
            ?: return null
        val resolved = runCatching { URI(base.ifBlank { "$BASE/" }).resolve(cleaned).toString() }.getOrNull() ?: return null
        return resolved.takeIf(::validExternalUrl)
    }

    fun imageUrl(element: Element?): String? = element?.let { img ->
        listOf("data-src", "data-lazy-src", "data-original", "src")
            .asSequence()
            .map { img.attr(it) }
            .mapNotNull { cleanUrlAttr(img.baseUri(), it) }
            .firstOrNull()
    }

    fun upgradeTmdbPoster(url: String?): String? =
        url?.replace(TMDB_POSTER_SIZE, "$1w500/")

    fun upgradeTmdbBackdrop(url: String?): String? =
        url?.replace(TMDB_BACKDROP_SIZE, "$1w1280/")

    private fun normalizeKey(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return "${uri.host?.lowercase()}${uri.path.orEmpty().trimEnd('/')}"
    }

    fun isSiteLogo(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return "logo-pelicine" in path || "cropped-logo" in path
    }

    fun isValidImage(url: String?, pageUrl: String = ""): Boolean {
        val candidate = url ?: return false
        if (!validExternalUrl(candidate) || isSiteLogo(candidate)) return false
        if (pageUrl.isNotBlank() && normalizeKey(candidate) == normalizeKey(pageUrl)) return false
        if (siteUrl(candidate)) {
            val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
            if (path.startsWith("/movies/") || path.startsWith("/series/") || path.startsWith("/episode/") || path.startsWith("/category/")) {
                return false
            }
            if (!path.contains("/wp-content/") && !IMAGE_EXT.containsMatchIn(path)) {
                return false
            }
        }
        return true
    }

    private fun isGenericAlt(alt: String): Boolean {
        val trimmed = alt.trim()
        return trimmed.isBlank() ||
            trimmed.equals("Image", true) ||
            trimmed.equals("Site", true) ||
            trimmed.equals("Image Site", true) ||
            trimmed.contains("pelicine", true)
    }

    private fun parseCard(card: Element): PeliCineCard? {
        val link = card.selectFirst("a.lnk-blk[href], .entry-title a[href], .post-thumbnail a[href], a[href]")
            ?: return null
        val url = cleanUrlAttr(link.baseUri(), link.attr("href"))?.takeIf(::siteUrl) ?: return null
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val movie = when {
            path.startsWith("/movies/") -> true
            path.startsWith("/series/") -> false
            else -> return null
        }
        val title = card.selectFirst(".entry-title, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeUnless(::isGenericAlt)
            ?: return null
        val poster = upgradeTmdbPoster(
            imageUrl(card.selectFirst(".post-thumbnail img, figure img, img"))?.takeIf { isValidImage(it, url) }
        )
        val year = card.selectFirst(".year")?.text()?.let { YEAR_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val quality = card.selectFirst(".post-ql .Qlty, .Qlty")?.text()?.trim()?.takeIf { it.isNotBlank() }
        return PeliCineCard(title, url, poster, movie, year, quality)
    }

    fun cards(doc: Document): List<PeliCineCard> {
        val raw = doc.select("main .post-lst .post, #aa-movies .post-lst .post, .post-lst .post")
            .ifEmpty { doc.select("article.post") }
        return raw.asSequence()
            .filter { !it.hasClass("single") && !it.hasClass("episodes") }
            .filter { it.closest(".post.single, .related, aside, #episode_by_temp, .wdgt-sidebar") == null }
            .mapNotNull(::parseCard)
            .distinctBy { it.url }
            .toList()
    }

    fun pageUrl(basePath: String, page: Int): String {
        val clean = basePath.trim().trimEnd('/')
        val prefix = if (clean.startsWith('/')) clean else "/$clean"
        return if (page <= 1) "$prefix/" else "$prefix/page/$page/"
    }

    fun searchUrl(query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return if (page <= 1) "/?s=$encoded" else "/page/$page/?s=$encoded"
    }

    fun hasNextPage(doc: Document, currentPage: Int, basePath: String, query: String? = null): Boolean {
        val nextPage = currentPage + 1
        val cleanBase = basePath.trim().trimEnd('/')
        val expectedPath = if (query != null) "/page/$nextPage" else "$cleanBase/page/$nextPage"
        return doc.select(".nav-links a[href], .pagination a[href], .wp-pagenavi a[href], link[rel=next][href]")
            .any { link ->
                val url = cleanUrlAttr(link.baseUri(), link.attr("href"))?.takeIf(::siteUrl) ?: return@any false
                val uri = runCatching { URI(url) }.getOrNull() ?: return@any false
                val path = uri.path.orEmpty().trimEnd('/')
                if (query != null) {
                    path == expectedPath && !uri.rawQuery.isNullOrBlank() && uri.rawQuery.contains("s=")
                } else {
                    path == expectedPath
                }
            }
    }

    fun parseDurationMinutes(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        Regex("""(\d+)\s*h\s*(\d+)\s*m""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull() ?: 0
            val mins = m.groupValues[2].toIntOrNull() ?: 0
            return (hours * 60 + mins).takeIf { it > 0 }
        }
        Regex("""(\d+)\s*h\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull() ?: 0
            return (hours * 60).takeIf { it > 0 }
        }
        Regex("""(\d+)\s*(?:min|m)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        }
        return null
    }

    fun detail(doc: Document): PeliCineDetail? {
        val pageUrl = doc.location().ifBlank { doc.baseUri() }
        val root = doc.selectFirst(".post.single, article.single, main > article.post") ?: doc
        val title = root.selectFirst("h1.entry-title, .entry-header h1, .entry-title, h1")
            ?.text()?.trim()?.takeIf { it.isNotBlank() && !it.contains("Telegram", true) }
            ?: doc.select("h1.entry-title, h1").asSequence()
                .map { it.text().trim() }
                .firstOrNull { it.isNotBlank() && !it.contains("Telegram", true) }
            ?: return null

        val path = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")
        val isMovie = when {
            path.startsWith("/movies/") -> true
            path.startsWith("/series/") -> false
            else -> doc.selectFirst(".choose-season, #episode_by_temp, .sel-temp") == null
        }

        val poster = upgradeTmdbPoster(
            (imageUrl(root.selectFirst(".post-thumbnail img, figure img"))
                ?: doc.selectFirst("meta[property=og:image][content]")?.let { cleanUrlAttr(it.baseUri(), it.attr("content")) })
                ?.takeIf { isValidImage(it, pageUrl) }
        )

        val backdropCandidate = doc.select(".bghd img, .backdrop img")
            .asSequence()
            .mapNotNull(::imageUrl)
            .firstOrNull { isValidImage(it, pageUrl) }
            ?: doc.selectFirst("meta[property=og:image][content]")
                ?.let { cleanUrlAttr(it.baseUri(), it.attr("content")) }
                ?.takeIf { isValidImage(it, pageUrl) }
        val backdrop = upgradeTmdbBackdrop(backdropCandidate) ?: poster

        val plotParagraphs = root.select(".description p:not([class])")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("Telegram", true) }
        val plot = if (plotParagraphs.isNotEmpty()) {
            plotParagraphs.joinToString("\n\n")
        } else {
            root.selectFirst(".description")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }

        val year = root.selectFirst(".entry-meta .year, .year, .entry-meta")?.text()
            ?.let { YEAR_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val duration = parseDurationMinutes(
            root.selectFirst(".entry-meta .duration, .duration, .runtime")?.text()
                ?: root.selectFirst(".entry-meta")?.text()
        )

        val genres = (root.select(".genres a") + root.select(".tagcloud a"))
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = root.select(".cast-lst a, .cast a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val rating = root.selectFirst(".vote-cn .vote .num, .vote .num")?.text()
            ?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

        val trailer = doc.select("#mdl-trailer iframe[data-src], #mdl-trailer iframe[src], .mdl-trailer iframe[data-src], .mdl-trailer iframe[src], iframe[src*=youtube], iframe[data-src*=youtube]")
            .asSequence()
            .mapNotNull { iframe ->
                cleanUrlAttr(iframe.baseUri(), iframe.attr("data-src").ifBlank { iframe.attr("src") })
            }
            .firstOrNull()

        val recommendations = doc.select(".related .post, section .post-lst .post")
            .asSequence()
            .filter { !it.hasClass("single") && !it.hasClass("episodes") }
            .mapNotNull(::parseCard)
            .filter { normalizeKey(it.url) != normalizeKey(pageUrl) }
            .distinctBy { it.url }
            .toList()

        return PeliCineDetail(
            title = title,
            url = pageUrl,
            movie = isMovie,
            poster = poster,
            backdrop = backdrop,
            plot = plot,
            year = year,
            durationMinutes = duration,
            genres = genres,
            actors = actors,
            rating = rating,
            trailer = trailer,
            recommendations = recommendations,
        )
    }

    fun seasons(doc: Document): List<PeliCineSeason> {
        val anchorSeasons = doc.select(".choose-season .sel-temp a[data-season][data-post], .sel-temp a[data-season][data-post]")
            .mapNotNull { a ->
                val season = a.attr("data-season").trim().toIntOrNull()
                    ?: Regex("\\d+").find(a.text())?.value?.toIntOrNull()
                    ?: return@mapNotNull null
                val postId = a.attr("data-post").trim().takeIf { it.matches(Regex("\\d+")) }
                    ?: return@mapNotNull null
                PeliCineSeason(season, postId, a.text().trim())
            }
        val list = if (anchorSeasons.isNotEmpty()) {
            anchorSeasons
        } else {
            doc.select(".choose-season select, select.sel-temp").flatMap { select ->
                val parentPost = select.attr("data-post").trim().takeIf { it.matches(Regex("\\d+")) }
                select.select("option").mapNotNull { opt ->
                    val season = opt.attr("data-season").ifBlank { opt.attr("value") }.trim().toIntOrNull()
                        ?: Regex("\\d+").find(opt.text())?.value?.toIntOrNull()
                        ?: return@mapNotNull null
                    val postId = opt.attr("data-post").trim().takeIf { it.matches(Regex("\\d+")) }
                        ?: parentPost
                        ?: return@mapNotNull null
                    PeliCineSeason(season, postId, opt.text().trim())
                }
            }
        }
        return list.distinctBy { it.season }.sortedBy { it.season }
    }

    fun episodes(container: Element, fallbackSeason: Int = 1): List<PeliCineEpisode> {
        val items = container.select("#episode_by_temp li, #episode_by_temp .post.episodes")
            .ifEmpty { container.select(".post.episodes, li:has(a[href*=/episode/])") }
        val seenUrls = mutableSetOf<String>()
        val result = mutableListOf<PeliCineEpisode>()
        items.forEachIndexed { index, item ->
            val link = item.selectFirst("a.lnk-blk[href], .entry-title a[href], a[href*='/episode/']")
                ?: return@forEachIndexed
            val url = cleanUrlAttr(link.baseUri(), link.attr("href"))?.takeIf(::siteUrl) ?: return@forEachIndexed
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            if (!path.contains("/episode/")) return@forEachIndexed
            if (!seenUrls.add(url)) return@forEachIndexed

            val numMatch = EPISODE_NUM_REGEX.find(item.selectFirst(".num-epi")?.text().orEmpty())
                ?: EPISODE_URL_REGEX.find(path)
            val season = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: fallbackSeason
            val number = numMatch?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)

            val rawTitle = item.selectFirst(".entry-title, h2, h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: "Episodio $number"
            val poster = upgradeTmdbPoster(
                imageUrl(item.selectFirst(".post-thumbnail img, figure img, img"))?.takeIf { isValidImage(it, url) }
            )
            result += PeliCineEpisode(rawTitle, url, season, number, poster)
        }
        return result.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    fun normalizeLanguage(raw: String): String {
        val normalized = COMBINING_MARKS.replace(Normalizer.normalize(raw, Normalizer.Form.NFD), "").lowercase()
        return when {
            normalized.contains("latino") || Regex("\\blat\\b").containsMatchIn(normalized) -> "Latino"
            normalized.contains("castellano") || normalized.contains("espanol") || Regex("\\bcas\\b").containsMatchIn(normalized) -> "Castellano"
            normalized.contains("subtitul") || normalized.contains("vose") || Regex("\\bsub\\b").containsMatchIn(normalized) -> "Subtitulado"
            else -> "Idioma no indicado"
        }
    }

    private fun normalizeQuality(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            Regex("\\bhd-?ts\\b").containsMatchIn(lower) -> "HD-TS"
            Regex("\\bcam\\b").containsMatchIn(lower) -> "CAM"
            else -> null
        }
    }

    private fun isYoutube(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host == "youtube-nocookie.com"
    }

    fun playerOptions(doc: Document): List<PeliCinePlayerOption> {
        val options = mutableListOf<PeliCinePlayerOption>()
        val tabs = doc.select(".video-options .aa-tbs a[href], ul.aa-tbs-video a[href]")
        for (tab in tabs) {
            val href = tab.attr("href").trim()
            val tabId = TAB_ID_REGEX.matchEntire(href)?.groupValues?.get(1) ?: continue
            val pane = doc.getElementById(tabId) ?: continue
            val iframe = pane.selectFirst("iframe[data-src], iframe[src]") ?: continue
            val rawUrl = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            val url = cleanUrlAttr(iframe.baseUri().ifBlank { doc.baseUri() }, rawUrl) ?: continue
            if (isYoutube(url)) continue
            val labelText = tab.text().trim().ifBlank { tab.parent()?.text().orEmpty().trim() }
            options += PeliCinePlayerOption(
                url = url,
                language = normalizeLanguage(labelText),
                qualityTag = normalizeQuality(labelText),
                serverHint = labelText.takeIf { it.isNotBlank() },
            )
        }
        if (options.isEmpty()) {
            for (pane in doc.select("div[id^=options-], div[id^=option-]")) {
                val iframe = pane.selectFirst("iframe[data-src], iframe[src]") ?: continue
                val rawUrl = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                val url = cleanUrlAttr(iframe.baseUri().ifBlank { doc.baseUri() }, rawUrl) ?: continue
                if (isYoutube(url)) continue
                options += PeliCinePlayerOption(url, "Idioma no indicado", null, null)
            }
        }
        return options.distinctBy { it.url to it.language }
    }

    fun unwrapWrapperUrl(doc: Document): String? {
        val base = doc.location().ifBlank { doc.baseUri() }
        val iframeUrl = doc.select("iframe[src], iframe[data-src]")
            .asSequence()
            .mapNotNull { iframe ->
                cleanUrlAttr(iframe.baseUri().ifBlank { base }, iframe.attr("src").ifBlank { iframe.attr("data-src") })
            }
            .firstOrNull { !isYoutube(it) && !siteUrl(it) }
        if (iframeUrl != null) return iframeUrl

        val jsRedirect = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*(['"])(.*?)\1|location\.replace\s*\(\s*(['"])(.*?)\3\s*\)""")
        for (script in doc.select("script")) {
            for (match in jsRedirect.findAll(script.data())) {
                val candidate = match.groupValues[2].ifBlank { match.groupValues[4] }
                val resolved = cleanUrlAttr(base, candidate) ?: continue
                if (!siteUrl(resolved) && !isYoutube(resolved)) return resolved
            }
        }
        return null
    }
}
