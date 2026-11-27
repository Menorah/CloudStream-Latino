package com.menorah.repelis24czbis

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

data class Repelis24CZCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isSeries: Boolean,
    val year: Int? = null,
    val rating10: Double? = null,
    val languages: List<String> = emptyList(),
)

data class Repelis24CZEpisode(
    val episodeId: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val posterUrl: String?,
    val airDate: String? = null,
)

data class Repelis24CZAjaxOption(
    val serverName: String,
    val postId: String,
    val type: String,
    val nume: String,
    val language: String? = null,
)

data class Repelis24CZDetail(
    val title: String,
    val url: String,
    val isSeries: Boolean,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating10: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val trailerUrl: String?,
    val episodes: List<Repelis24CZEpisode>,
    val recommendations: List<Repelis24CZCard>,
)

object Repelis24CZBisMarkup {
    const val DEFAULT_MAIN_URL = "https://repelis24.cz"
    const val EPISODE_DATA_PREFIX = "repelis24cz-ep:"

    private val mapper = ObjectMapper()
    private val YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")
    private val DURATION_MIN_REGEX = Regex("""(\d+)\s*Min\b""", RegexOption.IGNORE_CASE)
    private val RATING_REGEX = Regex("""(\d+(?:[.,]\d+)?)""")
    private val HERO_RATING_REGEX = Regex("""(?:IMDb|TMDb)\s+(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val PAGE_NUM_REGEX = Regex("""/page/(\d+)/?""")
    private val SEASON_PANE_NUM_REGEX = Regex("""dpu-season-(\d+)""")
    private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSS_BG_URL_REGEX = Regex("""background-image\s*:\s*[^;]*url\(\s*['"]?([^'")\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val ARIA_EP_TITLE_REGEX = Regex("""episodio\s+\d+\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun isChallengePage(statusCode: Int, html: String): Boolean {
        if (statusCode == 403 || statusCode == 503) return true
        val lower = html.lowercase()
        return lower.contains("one moment, please") ||
            lower.contains("_cf_chl_opt") ||
            lower.contains("challenge-platform") ||
            lower.contains("just a moment") ||
            lower.contains("checking your browser")
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

    fun buildArchivePageUrl(sectionUrl: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val resolved = resolveUrl(mainUrl, sectionUrl) ?: mainUrl
        if (page <= 1) return resolved
        val qIdx = resolved.indexOf('?')
        val pathPart = (if (qIdx >= 0) resolved.substring(0, qIdx) else resolved).trimEnd('/')
        val queryPart = if (qIdx >= 0) resolved.substring(qIdx) else ""
        return "$pathPart/page/$page/$queryPart"
    }

    fun buildSearchUrl(query: String, page: Int = 1, mainUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = mainUrl.trimEnd('/')
        return if (page <= 1) "$root/?s=$encoded" else "$root/page/$page/?s=$encoded"
    }

    fun encodeEpisodeData(episodeId: String, seriesUrl: String): String =
        "$EPISODE_DATA_PREFIX$episodeId|$seriesUrl"

    fun decodeEpisodeData(data: String): Pair<String, String>? {
        if (!data.startsWith(EPISODE_DATA_PREFIX)) return null
        val rest = data.removePrefix(EPISODE_DATA_PREFIX)
        val sep = rest.indexOf('|')
        if (sep <= 0) return null
        val epId = rest.substring(0, sep).trim()
        val seriesUrl = rest.substring(sep + 1).trim()
        return if (epId.isNotEmpty() && seriesUrl.isNotEmpty()) epId to seriesUrl else null
    }

    fun normalizeLanguageLabel(raw: String?): String? {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return null
        return when {
            v == "mx" || v == "lat" || v == "latino" || v.contains("latino") || v.contains("méxico") || v.contains("mexico") || v.endsWith("/mx.png") -> "Latino"
            v == "es" || v == "esp" || v == "cast" || v == "castellano" || v.contains("castellano") || v.endsWith("/es.png") || v == "español" || v == "espanol" -> "Castellano"
            v == "en" || v == "eng" || v.contains("inglés") || v.contains("ingles") || v == "english" || v.endsWith("/en.png") -> "Inglés"
            v == "sub" || v == "vose" || v.contains("subtitul") -> "Subtitulado"
            else -> null
        }
    }

    fun parseCards(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<Repelis24CZCard> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Repelis24CZCard>()
        val items = doc.select("article.dpu-archive-item, .dpu-archive-grid article")
        for (article in items) {
            val a = article.selectFirst("a.dpu-archive-card[href], a[href]") ?: continue
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!href.contains("/peliculas/") && !href.contains("/series/")) continue
            if (!seen.add(href)) continue

            val img = article.selectFirst("img.dpu-archive-img, .dpu-archive-thumb img, img")
            val title = article.selectFirst(".dpu-archive-c-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue

            val poster = resolveUrl(
                mainUrl,
                img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src"),
            )
            val metaText = article.select(".dpu-archive-meta-info span").joinToString(" ") { it.text() }
            val year = YEAR_REGEX.find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val ratingText = article.selectFirst(".dpu-card-rating-premium")?.text().orEmpty()
            val rating = RATING_REGEX.find(ratingText)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()

            val cardLangs = article.select("span.dpu-card-languages img").mapNotNull { flag ->
                normalizeLanguageLabel(flag.attr("title").ifEmpty { flag.attr("alt") }.ifEmpty { flag.attr("src") })
            }.distinct()

            out += Repelis24CZCard(
                title = title,
                url = href,
                posterUrl = poster,
                isSeries = href.contains("/series/"),
                year = year,
                rating10 = rating,
                languages = cardLangs,
            )
        }
        return out
    }

    fun parseHasNext(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("link[rel=next], div.dpu-pagination-ultra a.next, a.next.page-numbers") != null) {
            return true
        }
        val nextPage = currentPage + 1
        return doc.select("div.dpu-pagination-ultra a.page-numbers[href], ul.page-numbers a[href]").any { a ->
            val p = PAGE_NUM_REGEX.find(a.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            p != null && p >= nextPage
        }
    }

    fun parsePlayerOptions(doc: Document): List<Repelis24CZAjaxOption> {
        val out = mutableListOf<Repelis24CZAjaxOption>()
        val seen = linkedSetOf<Triple<String, String, String>>()
        for (li in doc.select("li.dooplay_player_option[data-post][data-nume], li.dooplay_player_option")) {
            val nume = li.attr("data-nume").trim()
            if (nume.isEmpty() || nume.equals("trailer", ignoreCase = true)) continue
            val postId = li.attr("data-post").trim()
            val type = li.attr("data-type").trim().ifEmpty { "movie" }
            if (postId.isEmpty()) continue
            val key = Triple(type, postId, nume)
            if (!seen.add(key)) continue

            // dpu-dropdown always preserves data-lang ("mx", "es", "en") and flag img even after JS overwrites .dpu-trigger-label.
            val dropdown: Element? = li.parents().firstOrNull { it.hasClass("dpu-dropdown") }
            val flagImg = dropdown?.selectFirst("img.dpu-mini-flag")
            val triggerLabel = dropdown?.selectFirst(".dpu-trigger-label")?.text()?.trim()
            val lang = normalizeLanguageLabel(dropdown?.attr("data-lang"))
                ?: normalizeLanguageLabel(flagImg?.attr("src"))
                ?: normalizeLanguageLabel(flagImg?.attr("alt"))
                ?: normalizeLanguageLabel(triggerLabel)

            val spans = li.select("> span")
            val rawServer = spans.lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: li.text().trim().ifEmpty { "Servidor $nume" }
            val serverName = rawServer.replace(Regex("""^\d+\.\s*"""), "").trim().ifEmpty { rawServer }

            out += Repelis24CZAjaxOption(
                serverName = serverName,
                postId = postId,
                type = type,
                nume = nume,
                language = lang,
            )
        }
        return out
    }

    fun parseSeriesEpisodes(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<Repelis24CZEpisode> {
        val out = mutableListOf<Repelis24CZEpisode>()
        val seen = linkedSetOf<String>()
        for (card in doc.select(".dpu-episode-card[data-episode]")) {
            val epId = card.attr("data-episode").trim()
            if (epId.isEmpty() || !seen.add(epId)) continue

            val seasonPane: Element? = card.parents().firstOrNull { it.hasClass("dpu-season-pane") }
            val paneSeason = seasonPane?.id()?.let {
                SEASON_PANE_NUM_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            val sNum = card.attr("data-season-number").toIntOrNull() ?: paneSeason ?: 1
            val eNum = card.attr("data-episode-number").toIntOrNull()
                ?: Regex("""\d+""").find(card.selectFirst(".dpu-ep-number")?.text().orEmpty())?.value?.toIntOrNull()
                ?: (out.size + 1)

            val ariaTitle = ARIA_EP_TITLE_REGEX.find(card.attr("aria-label"))?.groupValues?.getOrNull(1)?.trim()
            val epTitle = card.selectFirst(".dpu-ep-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: ariaTitle?.takeIf { it.isNotEmpty() }
                ?: "Episodio $eNum"
            val epImg = card.selectFirst(".dpu-ep-card-image img, img")
            val epPoster = resolveUrl(
                mainUrl,
                epImg?.attr("data-src")?.ifEmpty { epImg.attr("src") } ?: epImg?.attr("src"),
            )
            val airDate = card.selectFirst(".dpu-ep-date")?.text()?.trim()?.takeIf { it.isNotEmpty() }

            out += Repelis24CZEpisode(
                episodeId = epId,
                title = epTitle,
                season = sNum,
                episode = eNum,
                posterUrl = epPoster,
                airDate = airDate,
            )
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    fun parseDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): Repelis24CZDetail? {
        val resolvedUrl = resolveUrl(mainUrl, url) ?: url
        val isSeries = resolvedUrl.contains("/series/") || doc.selectFirst(".dpu-episode-card, #seasons") != null

        val h1El: Element? = doc.selectFirst("h1.dpu-hero-title, h1")
        val h1 = h1El?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+Online\s+Gratis.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
        val title = h1 ?: ogTitle?.takeIf { it.isNotEmpty() } ?: return null

        val hero: Element? = h1El?.parents()?.firstOrNull {
            it.hasClass("dpu-hero-section") || it.hasClass("dpu-hero-ultra") || it.hasClass("dpu-hero-content") || it.tagName() == "div"
        } ?: doc.body()
        val posterImg = doc.selectFirst(
            ".dpu-poster-frame img, .dpu-hero-poster img, img[src*='image.tmdb.org/t/p/w185'], img[src*='image.tmdb.org/t/p/w342'], img[src*='image.tmdb.org/t/p/w500']",
        )
        val posterUrl = resolveUrl(
            mainUrl,
            posterImg?.attr("data-src")?.ifEmpty { posterImg.attr("src") } ?: posterImg?.attr("src"),
        )?.replace("/w185/", "/w500/")

        val globalBgRaw = doc.selectFirst(".dpu-global-bg[style]")?.attr("style")?.let { styleAttr ->
            CSS_BG_URL_REGEX.find(styleAttr)?.groupValues?.getOrNull(1)
        }
        val bgUrl = resolveUrl(
            mainUrl,
            doc.selectFirst("#fakeplayer img.cover, .playbox img.cover")?.attr("src")
                ?: doc.selectFirst("img[src*='image.tmdb.org/t/p/original']")?.attr("src")
                ?: globalBgRaw,
        )

        val shortSyn = doc.selectFirst(".dpu-hero-synopsis-short")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val ogSyn = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.replace(Regex("""\s*\[\s*…\s*]\s*$"""), "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val entrySyn = doc.selectFirst("#info .dpu-entry-content p, .dpu-entry-content p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val plot = when {
            shortSyn != null && !shortSyn.endsWith("…") && !shortSyn.endsWith("...") -> shortSyn
            ogSyn != null && (shortSyn == null || ogSyn.length >= shortSyn.length) -> ogSyn
            shortSyn != null -> shortSyn
            else -> entrySyn
        }

        val heroText = hero?.text().orEmpty()
        val year = YEAR_REGEX.find(heroText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val duration = DURATION_MIN_REGEX.find(heroText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = HERO_RATING_REGEX.find(heroText)
            ?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        val navTags = setOf("nav", "header", "footer")
        val genres = doc.select("a[href*=/genero/][rel=tag], .dpu-hero-genres a[href*=/genero/]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty {
                doc.select("a[href*=/genero/]").filter { el ->
                    el.parents().none { it.tagName() in navTags }
                }.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            }

        val actors = doc.select("#cast .person .name a, a[href*=/reparto/]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val trailerUrl = doc.selectFirst("#source-player-trailer iframe[src]")?.attr("src")?.trim()
            ?.let { resolveUrl(mainUrl, it) }

        val episodes = if (isSeries) parseSeriesEpisodes(doc, mainUrl) else emptyList()

        val recs = mutableListOf<Repelis24CZCard>()
        val seenRec = linkedSetOf<String>()
        for (card in doc.select("article.dpu-related-card, #dpu_related_ultra_grid article")) {
            val a = card.selectFirst("a[href]") ?: continue
            val rUrl = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (rUrl == resolvedUrl || !seenRec.add(rUrl)) continue
            val rImg = card.selectFirst("img")
            val rPoster = resolveUrl(mainUrl, rImg?.attr("data-src")?.ifEmpty { rImg.attr("src") } ?: rImg?.attr("src"))
            val rTitle = card.selectFirst(".dpu-rel-title, h3, strong")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: rImg?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
            val rYear = YEAR_REGEX.find(card.selectFirst(".dpu-rel-year")?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val rRating = RATING_REGEX.find(card.selectFirst(".dpu-rel-rating")?.text().orEmpty())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            recs += Repelis24CZCard(
                title = rTitle,
                url = rUrl,
                posterUrl = rPoster,
                isSeries = rUrl.contains("/series/"),
                year = rYear,
                rating10 = rRating,
            )
        }
        if (recs.isEmpty()) {
            for (a in doc.select("a[href*=/peliculas/], a[href*=/series/]")) {
                if (a.parents().any { it.tagName() in navTags || it.hasClass("dpu-hero-actions") }) continue
                if (a.hasClass("dpu-archive-card")) continue
                val rUrl = resolveUrl(mainUrl, a.attr("href")) ?: continue
                if (rUrl == resolvedUrl || !seenRec.add(rUrl)) continue
                val rImg = a.selectFirst("img") ?: continue
                val rPoster = resolveUrl(mainUrl, rImg.attr("src")) ?: continue
                val rTitle = a.selectFirst("strong, h3, .title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: rImg.attr("alt").trim().takeIf { it.isNotEmpty() }
                    ?: continue
                recs += Repelis24CZCard(
                    title = rTitle,
                    url = rUrl,
                    posterUrl = rPoster,
                    isSeries = rUrl.contains("/series/"),
                )
            }
        }

        return Repelis24CZDetail(
            title = title,
            url = resolvedUrl,
            isSeries = isSeries,
            posterUrl = posterUrl,
            backgroundUrl = bgUrl,
            plot = plot,
            year = year,
            durationMinutes = duration,
            rating10 = rating,
            genres = genres,
            actors = actors,
            trailerUrl = trailerUrl,
            episodes = episodes,
            recommendations = recs,
        )
    }

    fun parseDooPlayerAjaxResponse(body: String, mainUrl: String = DEFAULT_MAIN_URL): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: return null
            val rawEmbed = node.path("embed_url").asText("").trim()
            if (rawEmbed.isEmpty()) return null
            val iframeSrc = IFRAME_SRC_REGEX.find(rawEmbed)?.groupValues?.getOrNull(1)
            val candidate = iframeSrc ?: rawEmbed
            val resolved = resolveUrl(mainUrl, candidate) ?: return null
            if (resolved.contains("directyp.org", ignoreCase = true)) return null
            return resolved
        }
        val iframeSrc = IFRAME_SRC_REGEX.find(trimmed)?.groupValues?.getOrNull(1) ?: return null
        val resolved = resolveUrl(mainUrl, iframeSrc) ?: return null
        return if (resolved.contains("directyp.org", ignoreCase = true)) null else resolved
    }
}
