package com.menorah.repelishdbis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

data class RePelisHDCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isSeries: Boolean,
    val rating10: Double? = null,
    val year: Int? = null,
)

data class RePelisHDSeasonMeta(
    val seasonNumber: Int,
    val name: String,
    val availableCount: Int,
)

data class RePelisHDEpisode(
    val title: String,
    val season: Int,
    val episode: Int,
    val overview: String?,
    val posterUrl: String?,
    val playDataUrl: String,
)

data class RePelisHDIniziale(
    val imdbId: String?,
    val basePath: String?,
    val title: String?,
    val activeSeason: Int?,
    val activeEpisode: Int?,
    val seasons: List<RePelisHDSeasonMeta>,
    val episodes: List<RePelisHDEpisode>,
)

data class RePelisHDDetail(
    val title: String,
    val url: String,
    val isSeries: Boolean,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating10: Double?,
    val imdbId: String?,
    val genres: List<String>,
    val actors: List<String>,
    val trailerUrl: String?,
    val playerUrl: String?,
    val iniziale: RePelisHDIniziale?,
    val recommendations: List<RePelisHDCard>,
)

object RePelisHDBisMarkup {
    const val DEFAULT_MAIN_URL = "https://repelishd.surf"
    const val PLAYLATIN_BASE = "https://playlatinsrc.co"

    private val mapper = ObjectMapper()
    private val YEAR_REGEX = Regex("""\b((?:19|20)\d{2})\b""")
    private val DURATION_HM_REGEX = Regex("""(\d+)\s*h(?:\s*(\d+)\s*m)?""", RegexOption.IGNORE_CASE)
    private val DURATION_M_REGEX = Regex("""(\d+)\s*min\b|\b(\d+)\s*m\b""", RegexOption.IGNORE_CASE)
    private val IMDB_REGEX = Regex("""\b(tt\d{5,10})\b""")
    private val PAGE_PARAM_REGEX = Regex("""[?&]page=(\d+)""")
    private val NEXT_F_PUSH_REGEX = Regex("""self\.__next_f\.push\(\[\s*\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*]\)""", RegexOption.DOT_MATCHES_ALL)
    private val YOUTUBE_URL_REGEX = Regex("""https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|embed/)|youtu\.be/)[A-Za-z0-9_-]{6,}""")

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

    fun buildMainPageUrl(sectionUrl: String, page: Int, mainUrl: String = DEFAULT_MAIN_URL): String {
        val base = resolveUrl(mainUrl, sectionUrl) ?: mainUrl
        if (page <= 1) return base
        val sep = if ('?' in base) '&' else '?'
        return "$base${sep}page=$page"
    }

    fun buildSearchUrl(query: String, page: Int = 1, mainUrl: String = DEFAULT_MAIN_URL): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = mainUrl.trimEnd('/')
        return if (page <= 1) "$root/buscar?q=$encoded" else "$root/buscar?q=$encoded&page=$page"
    }

    fun parseCards(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): List<RePelisHDCard> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<RePelisHDCard>()
        for (a in doc.select("a.cv[href]")) {
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!href.contains("/ver-pelicula/") && !href.contains("/ver-serie/")) continue
            if (!seen.add(href)) continue

            val title = a.selectFirst(".cv-t")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: continue

            val poster = resolveUrl(mainUrl, a.selectFirst(".cv-art img")?.attr("src"))
            val meta = a.selectFirst(".cv-m")?.text()?.trim().orEmpty()
            val isSeries = href.contains("/ver-serie/") || meta.contains("Serie", ignoreCase = true)
            val year = YEAR_REGEX.find(meta)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val ratingStr = a.attr("data-voto").ifEmpty { a.selectFirst(".rate")?.text().orEmpty() }
            val rating = Regex("""(\d+(?:[.,]\d+)?)""")
                .find(ratingStr)
                ?.groupValues?.getOrNull(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()

            out += RePelisHDCard(
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

    fun parseHasNext(doc: Document, currentPage: Int, itemCount: Int): Boolean {
        if (itemCount == 0) return false
        if (doc.selectFirst("link[rel=next]") != null) return true
        val nextPage = currentPage + 1
        val hasExplicitNext = doc.select("a[href]").any { el ->
            val p = PAGE_PARAM_REGEX.find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            p != null && p >= nextPage
        }
        if (hasExplicitNext) return true
        return itemCount >= 40
    }

    fun decodeJsonStringLiteral(escaped: String): String {
        val sb = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c == '\\' && i + 1 < escaped.length) {
                when (val next = escaped[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'u' -> {
                        if (i + 5 < escaped.length) {
                            val hex = escaped.substring(i + 2, i + 6).toIntOrNull(16)
                            if (hex != null) {
                                sb.append(hex.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append("\\u")
                        i += 2
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractJsonObjectAfterKey(text: String, keyLiteral: String): String? {
        val keyIdx = text.indexOf(keyLiteral)
        if (keyIdx < 0) return null
        val start = text.indexOf('{', keyIdx + keyLiteral.length)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inString) {
                escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun extractNextFlightText(doc: Document): String {
        val sb = StringBuilder()
        for (script in doc.select("script")) {
            val data = script.data()
            if (!data.contains("__next_f.push")) continue
            var foundPush = false
            for (m in NEXT_F_PUSH_REGEX.findAll(data)) {
                foundPush = true
                sb.append(decodeJsonStringLiteral(m.groupValues[1])).append('\n')
            }
            if (!foundPush) sb.append(data).append('\n')
        }
        return sb.toString()
    }

    fun parseIniziale(doc: Document, mainUrl: String = DEFAULT_MAIN_URL): RePelisHDIniziale? {
        val decoded = extractNextFlightText(doc)
        val jsonStr = extractJsonObjectAfterKey(decoded, "\"iniziale\":") ?: return null
        val root: JsonNode = runCatching { mapper.readTree(jsonStr) }.getOrNull() ?: return null

        val imdbId = root.path("imdb").asText("").takeIf { it.startsWith("tt") }
        val basePath = root.path("base").asText("").takeIf { it.isNotEmpty() }
        val titolo = root.path("titolo").asText("").takeIf { it.isNotEmpty() }
        val activeSeason = root.get("stagione")?.takeIf { it.isNumber }?.asInt()
        val activeEpisode = root.get("episodio")?.takeIf { it.isNumber }?.asInt()

        val seasons = mutableListOf<RePelisHDSeasonMeta>()
        val stagioniNode = root.path("stagioni")
        if (stagioniNode.isArray) {
            for (s in stagioniNode) {
                val num = s.path("season_number").asInt(-1)
                if (num < 0) continue
                seasons += RePelisHDSeasonMeta(
                    seasonNumber = num,
                    name = s.path("name").asText("Temporada $num"),
                    availableCount = s.path("disponibili").asInt(0),
                )
            }
        }

        val episodes = mutableListOf<RePelisHDEpisode>()
        val episodiNode = root.path("episodi")
        if (episodiNode.isArray) {
            for (e in episodiNode) {
                val sNum = e.path("season_number").asInt(activeSeason ?: 1)
                val eNum = e.path("episode_number").asInt(-1)
                if (eNum <= 0) continue
                val epName = e.path("name").asText("").trim().ifEmpty { "Episodio $eNum" }
                val overview = e.path("overview").asText("").trim().takeIf { it.isNotEmpty() }
                val epPoster = e.path("sfondo").asText("").ifEmpty { e.path("foto").asText("") }
                    .trim().takeIf { it.startsWith("http") }

                val playDataUrl = when {
                    imdbId != null -> "$PLAYLATIN_BASE/serial/$imdbId/$sNum/$eNum?lang=latino"
                    basePath != null -> resolveUrl(mainUrl, "$basePath/temporada-$sNum/episodio-$eNum") ?: ""
                    else -> ""
                }
                if (playDataUrl.isEmpty()) continue

                episodes += RePelisHDEpisode(
                    title = epName,
                    season = sNum,
                    episode = eNum,
                    overview = overview,
                    posterUrl = epPoster,
                    playDataUrl = playDataUrl,
                )
            }
        }

        return RePelisHDIniziale(
            imdbId = imdbId,
            basePath = basePath,
            title = titolo,
            activeSeason = activeSeason,
            activeEpisode = activeEpisode,
            seasons = seasons,
            episodes = episodes,
        )
    }

    fun parseDetail(doc: Document, url: String, mainUrl: String = DEFAULT_MAIN_URL): RePelisHDDetail? {
        val resolvedUrl = resolveUrl(mainUrl, url) ?: url
        val iniziale = parseIniziale(doc, mainUrl)
        val isSeries = resolvedUrl.contains("/ver-serie/") || iniziale != null

        val h1El = doc.selectFirst("h1")
        val h1Clean = h1El?.clone()?.apply { select("span.chh-h1sub").remove() }?.text()?.trim()
        val title = h1Clean?.takeIf { it.isNotEmpty() }
            ?: iniziale?.title
            ?: return null

        val poster = resolveUrl(
            mainUrl,
            doc.selectFirst(".chh-poster img, article.ch2 img[src*='tmdb']")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content"),
        )
        val flightText = extractNextFlightText(doc)
        val bgMatch: String? = Regex(""""sfondo"\s*:\s*"(https?://[^"]+)"""").find(flightText)?.groupValues?.getOrNull(1)

        val plot = doc.selectFirst("p.sin-t")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val badgesText = doc.select(".chh-badge li").joinToString(" ") { it.text() }
        val year = YEAR_REGEX.find(badgesText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val duration = DURATION_HM_REGEX.find(badgesText)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            h * 60 + min
        } ?: DURATION_M_REGEX.find(badgesText)?.let { m ->
            (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
        }

        val votoText = doc.selectFirst("b.chh-voto")?.text()
            ?: doc.selectFirst(".chh-badge li.b-nudo")?.text()
        val rating = Regex("""(\d+(?:[.,]\d+)?)""")
            .find(votoText.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        val playerIframeSrc = doc.selectFirst("iframe[src*='playlatinsrc.co']")?.attr("src")?.trim()
        val imdbId = iniziale?.imdbId
            ?: IMDB_REGEX.find(playerIframeSrc.orEmpty())?.groupValues?.getOrNull(1)
            ?: IMDB_REGEX.find(flightText)?.groupValues?.getOrNull(1)

        val genres = doc.select("a.chh-gpill").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val actors = doc.select(".chh-lista a[href*=/actores/], .chh-lista a[href*=/director/]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val trailerUrl = YOUTUBE_URL_REGEX.find(flightText)?.value
        val resolvedPlayerUrl = playerIframeSrc ?: imdbId?.takeIf { !isSeries }?.let {
            "$PLAYLATIN_BASE/movie/$it?lang=latino"
        }

        val recs = parseCardsFromContainer(doc, "section.chn a.cv[href]", mainUrl)

        return RePelisHDDetail(
            title = title,
            url = resolvedUrl,
            isSeries = isSeries,
            posterUrl = poster,
            backgroundUrl = bgMatch,
            plot = plot,
            year = year,
            durationMinutes = duration,
            rating10 = rating,
            imdbId = imdbId,
            genres = genres,
            actors = actors,
            trailerUrl = trailerUrl,
            playerUrl = resolvedPlayerUrl,
            iniziale = iniziale,
            recommendations = recs,
        )
    }

    private fun parseCardsFromContainer(doc: Document, selector: String, mainUrl: String): List<RePelisHDCard> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<RePelisHDCard>()
        for (a in doc.select(selector)) {
            val href = resolveUrl(mainUrl, a.attr("href")) ?: continue
            if (!seen.add(href)) continue
            val title = a.selectFirst(".cv-t")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: continue
            val poster = resolveUrl(mainUrl, a.selectFirst(".cv-art img")?.attr("src"))
            val meta = a.selectFirst(".cv-m")?.text()?.trim().orEmpty()
            val isSeries = href.contains("/ver-serie/") || meta.contains("Serie", ignoreCase = true)
            out += RePelisHDCard(title = title, url = href, posterUrl = poster, isSeries = isSeries)
        }
        return out
    }
}
