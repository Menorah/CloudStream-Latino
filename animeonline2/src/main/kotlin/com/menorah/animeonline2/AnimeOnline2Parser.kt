package com.menorah.animeonline2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AnimeCatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val isMovie: Boolean,
    val year: Int? = null,
    val rating: String? = null,
    val episodeNumber: Int? = null,
)

data class AnimeCatalogPage(
    val items: List<AnimeCatalogCard>,
    val hasNext: Boolean,
)

data class AnimeHomeModules(
    val latestEpisodes: List<AnimeCatalogCard>,
    val featuredOnAir: List<AnimeCatalogCard>,
    val latestAnime: List<AnimeCatalogCard>,
    val latestMovies: List<AnimeCatalogCard>,
    val seasons: List<AnimeCatalogCard>,
)

data class AnimePlayerOption(
    val postId: String,
    val nume: String,
    val type: String,
    val label: String,
)

data class AnimeEpisodeItem(
    val url: String,
    val name: String,
    val season: Int,
    val episode: Int,
    val posterUrl: String?,
    val date: String?,
)

data class AnimeCastMember(
    val name: String,
    val role: String?,
    val imageUrl: String?,
)

data class AnimeDetailInfo(
    val url: String,
    val title: String,
    val isMovie: Boolean,
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: String?,
    val tags: List<String>,
    val trailerUrl: String?,
    val cast: List<AnimeCastMember>,
    val episodes: List<AnimeEpisodeItem>,
    val related: List<AnimeCatalogCard>,
    val hasDirectPlayer: Boolean,
)

data class SaidoServerOption(
    val languageCode: String,
    val languageLabel: String,
    val url: String,
)

object AnimeOnline2Parser {
    const val BASE = "https://ww4.animeonline.ninja"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val jsonMapper = jacksonObjectMapper()

    fun parseJsonMap(jsonText: String): Map<String, Any?>? {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            jsonMapper.readValue(jsonText, Map::class.java) as? Map<String, Any?>
        }.getOrNull()
    }

    private val siteHostRegex = Regex("""^https?://(?:www\d*|ww\d*|ver\d*\.)?animeonline\.ninja(?=/|$)""", RegexOption.IGNORE_CASE)
    private val anySubdomainAnimeOnlineRegex = Regex("""^https?://[a-z0-9.-]*animeonline\.ninja(?=/|$)""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""\b((?:19|20)\d{2})\b""")
    private val durationRegex = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val epNumRegex = Regex("""(\d+)""")
    private val numerandoRegex = Regex("""(\d+)\s*-\s*(\d+)""")
    private val goToPlayerRegex = Regex("""go_to_player\(\s*['"]([^'"]+)['"]""")
    private val selLangRegex = Regex("""SelLang\(\s*this\s*,\s*['"]([^'"]+)['"]""")
    private val odLangClassRegex = Regex("""\bOD_([A-Za-z0-9_-]+)\b""")
    private val guploadPartsRegex = Regex("""var\s+_p\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val quotedStringRegex = Regex("""'([^']*)'|"([^"]*)"""")
    private val guploadPayloadRegex = Regex("""['"]([A-Za-z0-9+/=_-]+~[A-Za-z0-9+/=_-]+)['"]""")

    private val seoSpamTokens = setOf(
        "animeflv", "anime flv", "animeid", "anime id", "animeyt", "anime yt",
        "jkanime", "jk anime", "tioanime", "tio anime", "monoschinos", "monos chinos",
        "anime4mega", "animemovil", "anime movil", "animeonline", "anime online",
        "animetv", "anime tv", "animekodai", "anime kodai", "animefenix", "anime fenix",
        "ver anime", "veranime", "otakutv", "otaku tv", "henaojara", "animeblix",
        "crunchyroll", "animehd", "anime hd", "sub anime", "subanime", "gnula",
        "pelisplus", "cuevana", "animeonegai", "latanime", "mundoanime",
    )

    fun normalizeSiteUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val abs = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$BASE$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "$BASE/${trimmed.trimStart('/')}"
        }
        return abs.replace(anySubdomainAnimeOnlineRegex, BASE)
    }

    fun isSiteUrl(rawUrl: String): Boolean {
        val norm = normalizeSiteUrl(rawUrl)
        return norm.startsWith("$BASE/") || norm == BASE || siteHostRegex.containsMatchIn(rawUrl)
    }

    fun buildArchivePageUrl(path: String, page: Int): String {
        val cleanPath = "/" + path.trim().trim('/') + "/"
        return if (page <= 1) "$BASE$cleanPath" else "$BASE${cleanPath}page/$page/"
    }

    fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return if (page <= 1) {
            "$BASE/?s=$encoded"
        } else {
            "$BASE/search/page/$page/?s=$encoded"
        }
    }

    fun isCloudflareChallenge(code: Int, html: String): Boolean {
        if (code == 403 || code == 503) {
            if (html.contains("Just a moment", ignoreCase = true) ||
                html.contains("cf-chl", ignoreCase = true) ||
                html.contains("challenge-platform", ignoreCase = true)
            ) {
                return true
            }
        }
        return html.contains("window._cf_chl_opt", ignoreCase = true) ||
            (html.contains("<title>Just a moment", ignoreCase = true) && html.contains("cloudflare", ignoreCase = true))
    }

    fun normalizeImageUrl(rawUrl: String?): String? {
        var clean = rawUrl?.trim().orEmpty()
        if (clean.isEmpty() || clean.startsWith("data:", ignoreCase = true)) return null
        if (clean.contains(" ")) {
            clean = clean.replace(" ", "%20")
        }
        val abs = when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$BASE$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> "$BASE/${clean.trimStart('/')}"
        }
        return when {
            siteHostRegex.containsMatchIn(abs) ->
                abs.replace(siteHostRegex, BASE)
                    .replaceFirst("http://", "https://", ignoreCase = true)
            anySubdomainAnimeOnlineRegex.containsMatchIn(abs) ->
                abs.replaceFirst("http://", "https://", ignoreCase = true)
            abs.startsWith("http://", ignoreCase = true) &&
                !abs.startsWith("http://127.0.0.1", ignoreCase = true) &&
                !abs.startsWith("http://localhost", ignoreCase = true) ->
                "https://" + abs.substring(7)
            else -> abs
        }
    }

    fun extractImgUrl(img: Element?): String? {
        if (img == null) return null
        val srcsetUrl = listOf(img.attr("data-srcset"), img.attr("srcset"))
            .firstNotNullOfOrNull { set ->
                set.split(",")
                    .mapNotNull { entry -> entry.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } }
                    .firstOrNull { !it.startsWith("data:", ignoreCase = true) }
            }
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("src"),
            srcsetUrl.orEmpty(),
        )
        for (raw in candidates) {
            val normalized = normalizeImageUrl(raw)
            if (normalized != null) return normalized
        }
        return null
    }

    fun parseYear(text: String?): Int? {
        val m = yearRegex.find(text.orEmpty()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    fun parseDurationMinutes(text: String?): Int? {
        val m = durationRegex.find(text.orEmpty()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    fun parseArticleCard(article: Element): AnimeCatalogCard? {
        val classes = article.classNames()
        val isEpisode = classes.contains("episodes") || article.selectFirst(".epiposter") != null
        val isSeason = classes.contains("seasons") || article.selectFirst(".season_m span.a") != null
        val isMovie = classes.contains("movies")

        val rawHref = when {
            isEpisode || isSeason -> article.selectFirst(".season_m a[href]")?.attr("href")
                ?: article.selectFirst(".data h3 a[href]")?.attr("href")
                ?: article.selectFirst("a[href]")?.attr("href")
            else -> article.selectFirst(".poster > a[href]")?.attr("href")
                ?: article.selectFirst(".data h3 a[href]")?.attr("href")
                ?: article.selectFirst("a[href]")?.attr("href")
        }
        val url = normalizeSiteUrl(rawHref)
        if (url.isBlank() || url == "$BASE/" || url == "$BASE/#") return null

        val img = article.selectFirst(".poster img") ?: article.selectFirst("img")
        val posterUrl = extractImgUrl(img)
        val rating = article.selectFirst(".rating")?.text()?.trim()?.ifBlank { null }

        return when {
            isEpisode || url.contains("/episodio/") -> {
                val baseTitle = article.selectFirst(".data h3")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: ""
                val epText = article.selectFirst(".epiposter h4")?.text()?.trim().orEmpty()
                val epNum = epNumRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()
                if (baseTitle.isBlank()) return null
                AnimeCatalogCard(
                    title = baseTitle,
                    url = url,
                    posterUrl = posterUrl,
                    isMovie = false,
                    year = null,
                    rating = rating,
                    episodeNumber = epNum,
                )
            }

            isSeason || url.contains("/temporada/") -> {
                val showName = article.selectFirst(".season_m a span.c")?.text()?.trim().orEmpty()
                val seasonNum = article.selectFirst(".season_m a span.b")?.text()?.trim().orEmpty()
                val title = when {
                    showName.isNotBlank() && seasonNum.isNotBlank() -> "$showName - Temporada $seasonNum"
                    showName.isNotBlank() -> showName
                    else -> img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                        ?: article.selectFirst(".data h3")?.text()?.trim().orEmpty()
                }
                if (title.isBlank()) return null
                val year = parseYear(article.selectFirst(".data > span")?.text())
                AnimeCatalogCard(
                    title = title,
                    url = url,
                    posterUrl = posterUrl,
                    isMovie = false,
                    year = year,
                    rating = rating,
                )
            }

            isMovie || url.contains("/pelicula/") -> {
                val title = article.selectFirst(".data h3 a")?.text()?.trim()
                    ?: article.selectFirst(".data h3")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: ""
                if (title.isBlank()) return null
                val year = parseYear(article.selectFirst(".data > span")?.text())
                AnimeCatalogCard(
                    title = title,
                    url = url,
                    posterUrl = posterUrl,
                    isMovie = true,
                    year = year,
                    rating = rating,
                )
            }

            else -> {
                val title = article.selectFirst(".data h3 a")?.text()?.trim()
                    ?: article.selectFirst(".data h3")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: ""
                if (title.isBlank()) return null
                val year = parseYear(article.selectFirst(".data > span")?.text())
                AnimeCatalogCard(
                    title = title,
                    url = url,
                    posterUrl = posterUrl,
                    isMovie = false,
                    year = year,
                    rating = rating,
                )
            }
        }
    }

    fun parseHomeModules(html: String): AnimeHomeModules {
        val doc = Jsoup.parse(html)
        val latestEpisodes = doc.select("article.item.se.episodes")
            .mapNotNull(::parseArticleCard)
            .distinctBy { it.url }

        val featuredOnAir = doc.select("#featured-titles article.item")
            .mapNotNull(::parseArticleCard)
            .distinctBy { it.url }

        val latestAnime = doc.select("article.item.tvshows")
            .filter { it.parents().none { p -> p.id() == "featured-titles" || p.hasClass("srelacionados") } }
            .mapNotNull(::parseArticleCard)
            .distinctBy { it.url }

        val latestMovies = doc.select("article.item.movies")
            .filter { it.parents().none { p -> p.id() == "featured-titles" || p.hasClass("srelacionados") } }
            .mapNotNull(::parseArticleCard)
            .distinctBy { it.url }

        val seasons = doc.select("#dt-seasons article.item, article.item.se.seasons")
            .mapNotNull(::parseArticleCard)
            .distinctBy { it.url }

        return AnimeHomeModules(
            latestEpisodes = latestEpisodes,
            featuredOnAir = featuredOnAir,
            latestAnime = latestAnime,
            latestMovies = latestMovies,
            seasons = seasons,
        )
    }

    fun parseArchivePage(html: String, currentPage: Int): AnimeCatalogPage {
        val doc = Jsoup.parse(html)
        val mainContainer = doc.selectFirst(".module .content, #archive-content, .items") ?: doc
        val articles = mainContainer.select("article.item")
            .ifEmpty { doc.select(".module .content article.item, article.item") }
        val items = articles
            .filter { it.parents().none { p -> p.hasClass("srelacionados") } }
            .mapNotNull(::parseArticleCard)
            .distinctBy { it.url }
        val hasNext = hasNextPage(doc, currentPage)
        return AnimeCatalogPage(items = items, hasNext = hasNext && items.isNotEmpty())
    }

    fun parseSearchPage(html: String, currentPage: Int): AnimeCatalogPage {
        val doc = Jsoup.parse(html)
        val items = doc.select(".result-item article").mapNotNull { article ->
            val linkEl = article.selectFirst(".details .title a[href]")
                ?: article.selectFirst(".thumbnail a[href]")
                ?: return@mapNotNull null
            val url = normalizeSiteUrl(linkEl.attr("href"))
            val title = linkEl.text().trim()
                .ifBlank { article.selectFirst(".thumbnail img")?.attr("alt")?.trim().orEmpty() }
            if (url.isBlank() || title.isBlank()) return@mapNotNull null
            val img = article.selectFirst(".thumbnail img")
            val posterUrl = extractImgUrl(img)
            val isMovie = url.contains("/pelicula/") || article.selectFirst(".thumbnail span.movies") != null
            val year = parseYear(article.selectFirst(".meta .year")?.text())
            val rating = article.selectFirst(".meta .rating")?.text()
                ?.replace("IMDb", "", ignoreCase = true)
                ?.trim()
                ?.ifBlank { null }
            AnimeCatalogCard(
                title = title,
                url = url,
                posterUrl = posterUrl,
                isMovie = isMovie,
                year = year,
                rating = rating,
            )
        }.distinctBy { it.url }
        val hasNext = hasNextPage(doc, currentPage)
        return AnimeCatalogPage(items = items, hasNext = hasNext && items.isNotEmpty())
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        val nextMarker = "/page/${currentPage + 1}/"
        if (doc.selectFirst(".pagination a[href*='$nextMarker']") != null) return true
        val activeNext = doc.selectFirst(".pagination span.current + a[href]")
        if (activeNext != null) return true
        val resppagesNext = doc.select(".resppages a[href]")
            .any { it.attr("href").contains(nextMarker) }
        return resppagesNext
    }

    fun findParentSeriesUrlFromEpisode(html: String): String? {
        val doc = Jsoup.parse(html)
        val pagLinks = doc.select(".pag_episodes a[href]")
        for (a in pagLinks) {
            val txt = a.text().trim().lowercase()
            val href = normalizeSiteUrl(a.attr("href"))
            if ((txt.contains("lista de episodios") || href.contains("/online/")) &&
                href.startsWith("$BASE/online/")
            ) {
                return href
            }
        }
        val breadcrumb = doc.selectFirst(".dt_breadcrumb a[href*='/online/']")
        if (breadcrumb != null) {
            val href = normalizeSiteUrl(breadcrumb.attr("href"))
            if (href.startsWith("$BASE/online/")) return href
        }
        return null
    }

    fun cleanGenreTags(elements: List<Element>): List<String> {
        val seen = LinkedHashSet<String>()
        for (el in elements) {
            val text = el.text().trim()
            if (text.isBlank()) continue
            val norm = text.lowercase()
            if (norm in seoSpamTokens) continue
            if (norm.contains("anime") && norm !in setOf("audio castellano", "award-winning anime", "anime castellano")) {
                if (norm.contains("flv") || norm.contains("online") || norm.contains("yt") ||
                    norm.contains("movil") || norm.contains("kodai") || norm.contains("tv") ||
                    norm.contains("id") || norm.contains("hd") || norm.contains("jk") ||
                    norm.contains("tio") || norm.contains("4mega") || norm.contains("ninja") ||
                    norm.contains("fenix") || norm.contains("blix")
                ) {
                    continue
                }
            }
            val href = el.attr("href").lowercase()
            if (href.contains("/tag/") || href.contains("animeflv") || href.contains("jkanime")) continue
            seen.add(text)
            if (seen.size >= 12) break
        }
        return seen.toList()
    }

    fun parseDetailPage(url: String, html: String): AnimeDetailInfo {
        val doc = Jsoup.parse(html)
        val normalizedUrl = normalizeSiteUrl(url)
        val isMovie = normalizedUrl.contains("/pelicula/")

        val title = doc.selectFirst(".sheader .data h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("#single .sheader h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.substringBefore(" - Ver Anime")?.trim()
            ?.ifBlank { null }
            ?: "Anime"

        val posterUrl = extractImgUrl(doc.selectFirst(".sheader .poster img"))
            ?: normalizeImageUrl(doc.selectFirst("meta[property='og:image']")?.attr("content"))

        val backdropUrl = normalizeImageUrl(doc.selectFirst("#dt_galery a[href], .g-item a[href]")?.attr("href"))
            ?: posterUrl

        val plotParagraphs = doc.select("#info .wp-content p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val plot = if (plotParagraphs.isNotEmpty()) {
            plotParagraphs.joinToString("\n\n")
        } else {
            doc.selectFirst("#info .wp-content")?.text()?.trim()?.ifBlank { null }
        }

        val year = parseYear(doc.selectFirst(".sheader .extra .date")?.text())
        val durationMinutes = parseDurationMinutes(doc.selectFirst(".sheader .extra .runtime")?.text())
        val rating = doc.selectFirst(".dt_rating_vgs")?.text()?.trim()?.ifBlank { null }
        val tags = cleanGenreTags(doc.select(".sheader .sgeneros a, .sgeneros a"))

        val trailerEl: Element? = doc.selectFirst("#trailer iframe[src]")
        val trailerSrc = if (trailerEl != null) {
            val s = trailerEl.attr("data-src").ifBlank { trailerEl.attr("src") }.trim()
            if (s.startsWith("//")) "https:$s" else s.ifBlank { null }
        } else {
            null
        }

        val cast = doc.select("#cast .person").mapNotNull { person ->
            val actorName = person.selectFirst(".data .name a, .data .name")?.text()?.trim().orEmpty()
            if (actorName.isBlank()) return@mapNotNull null
            val role = person.selectFirst(".data .caracter")?.text()?.trim()?.ifBlank { null }
            val imageUrl = extractImgUrl(person.selectFirst(".img img"))
            AnimeCastMember(name = actorName, role = role, imageUrl = imageUrl)
        }

        val rawEpisodes = mutableListOf<AnimeEpisodeItem>()
        val seenEpUrls = HashSet<String>()
        val seasonContainers = doc.select("#seasons .se-c")
        if (seasonContainers.isNotEmpty()) {
            for ((sIdx, sec) in seasonContainers.withIndex()) {
                val containerSeason = sec.selectFirst(".se-q .se-t")?.text()?.trim()?.toIntOrNull() ?: (sIdx + 1)
                val rows = sec.select(".se-a ul.episodios li")
                for ((eIdx, li) in rows.withIndex()) {
                    val linkEl = li.selectFirst(".episodiotitle a[href]") ?: continue
                    val epUrl = normalizeSiteUrl(linkEl.attr("href"))
                    if (epUrl.isBlank() || !seenEpUrls.add(epUrl)) continue

                    val numerando = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
                    val numMatch = numerandoRegex.find(numerando)
                    val epSeason = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: containerSeason
                    val epNum = numMatch?.groupValues?.get(2)?.toIntOrNull() ?: (eIdx + 1)
                    val epName = linkEl.text().trim().ifBlank { "Episodio $epNum" }
                    val epPoster = extractImgUrl(li.selectFirst(".imagen img"))
                    val epDate = li.selectFirst(".episodiotitle .date")?.text()?.trim()?.ifBlank { null }

                    rawEpisodes.add(
                        AnimeEpisodeItem(
                            url = epUrl,
                            name = epName,
                            season = epSeason,
                            episode = epNum,
                            posterUrl = epPoster,
                            date = epDate,
                        )
                    )
                }
            }
        } else if (!isMovie) {
            // Fallback for direct episode lists without .se-c wrapper
            doc.select("ul.episodios li").forEachIndexed { idx, li ->
                val linkEl = li.selectFirst(".episodiotitle a[href]") ?: return@forEachIndexed
                val epUrl = normalizeSiteUrl(linkEl.attr("href"))
                if (epUrl.isBlank() || !seenEpUrls.add(epUrl)) return@forEachIndexed
                val numerando = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
                val numMatch = numerandoRegex.find(numerando)
                val epSeason = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = numMatch?.groupValues?.get(2)?.toIntOrNull() ?: (idx + 1)
                val epName = linkEl.text().trim().ifBlank { "Episodio $epNum" }
                rawEpisodes.add(
                    AnimeEpisodeItem(
                        url = epUrl,
                        name = epName,
                        season = epSeason,
                        episode = epNum,
                        posterUrl = extractImgUrl(li.selectFirst(".imagen img")),
                        date = li.selectFirst(".episodiotitle .date")?.text()?.trim()?.ifBlank { null },
                    )
                )
            }
        }

        val sortedEpisodes = rawEpisodes.sortedWith(compareBy<AnimeEpisodeItem>({ it.season }, { it.episode }))

        val related = doc.select(".srelacionados article").mapNotNull { art ->
            val linkEl = art.selectFirst("a[href]") ?: return@mapNotNull null
            val relUrl = normalizeSiteUrl(linkEl.attr("href"))
            if (relUrl.isBlank()) return@mapNotNull null
            val img = art.selectFirst("img")
            val relTitle = img?.attr("alt")?.trim()?.ifBlank { null }
                ?: linkEl.attr("title").trim().ifBlank { null }
                ?: return@mapNotNull null
            AnimeCatalogCard(
                title = relTitle,
                url = relUrl,
                posterUrl = extractImgUrl(img),
                isMovie = relUrl.contains("/pelicula/"),
            )
        }.distinctBy { it.url }

        val hasDirectPlayer = parsePlayerOptions(html).isNotEmpty()

        return AnimeDetailInfo(
            url = normalizedUrl,
            title = title,
            isMovie = isMovie,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            plot = plot,
            year = year,
            durationMinutes = durationMinutes,
            rating = rating,
            tags = tags,
            trailerUrl = trailerSrc,
            cast = cast,
            episodes = sortedEpisodes,
            related = related,
            hasDirectPlayer = hasDirectPlayer,
        )
    }

    fun parsePlayerOptions(html: String): List<AnimePlayerOption> {
        val doc = Jsoup.parse(html)
        return doc.select("#playeroptionsul li[data-post]").mapNotNull { li ->
            if (li.hasClass("dooplay_player_option_trailer")) return@mapNotNull null
            val postId = li.attr("data-post").trim()
            val nume = li.attr("data-nume").trim()
            val type = li.attr("data-type").trim().ifBlank { "tv" }
            if (postId.isBlank() || nume.isBlank() || nume.equals("trailer", ignoreCase = true)) {
                return@mapNotNull null
            }
            val label = li.selectFirst(".title")?.text()?.trim()
                ?: li.text().trim()
            AnimePlayerOption(
                postId = postId,
                nume = nume,
                type = type,
                label = label,
            )
        }
    }

    fun parseDooplayerEmbedUrl(jsonText: String): String? {
        val parsed = parseJsonMap(jsonText) ?: return null
        val raw = parsed["embed_url"]?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        val fixed = raw.replace("\\/", "/")
        return if (fixed.startsWith("//")) "https:$fixed" else fixed
    }

    fun normalizeLanguageLabel(code: String, rawLabel: String?): String {
        return when (code.trim().uppercase()) {
            "LAT", "LATINO", "MX" -> "Latino"
            "ES", "ESP", "CAST", "CASTELLANO" -> "Castellano"
            "SUB", "SUBTITULADO", "VOSE" -> "Subtitulado"
            "ENG", "EN" -> "Inglés"
            else -> rawLabel?.trim()?.takeIf { it.isNotBlank() } ?: code.trim().ifBlank { "Servidor" }
        }
    }

    fun parseSaidochestoOptions(html: String): List<SaidoServerOption> {
        val doc = Jsoup.parse(html)
        val tabLabels = mutableMapOf<String, String>()
        for (li in doc.select(".SelectLangDisp li")) {
            val oc = li.attr("onclick")
            val code = selLangRegex.find(oc)?.groupValues?.get(1)?.trim() ?: continue
            val spanText = li.selectFirst("span")?.text()?.trim() ?: li.text().trim()
            tabLabels[code.uppercase()] = normalizeLanguageLabel(code, spanText)
        }

        val out = mutableListOf<SaidoServerOption>()
        val seenUrls = HashSet<String>()
        for (container in doc.select(".OptionsLangDisp .OD")) {
            val code = container.classNames()
                .firstNotNullOfOrNull { odLangClassRegex.find(it)?.groupValues?.get(1) }
                ?.uppercase()
                ?: "SUB"
            val langLabel = tabLabels[code] ?: normalizeLanguageLabel(code, null)
            for (li in container.select("li[onclick]")) {
                val oc = li.attr("onclick")
                val rawUrl = goToPlayerRegex.find(oc)?.groupValues?.get(1)?.trim().orEmpty()
                if (rawUrl.isBlank()) continue
                val url = when {
                    rawUrl.startsWith("//") -> "https:$rawUrl"
                    else -> rawUrl
                }
                if (url.startsWith("http") && seenUrls.add("$code|$url")) {
                    out.add(
                        SaidoServerOption(
                            languageCode = code,
                            languageLabel = langLabel,
                            url = url,
                        )
                    )
                }
            }
        }

        // Fallback if layout doesn't group inside .OD
        if (out.isEmpty()) {
            for (li in doc.select("[onclick*='go_to_player']")) {
                val rawUrl = goToPlayerRegex.find(li.attr("onclick"))?.groupValues?.get(1)?.trim().orEmpty()
                val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                if (url.startsWith("http") && seenUrls.add(url)) {
                    out.add(SaidoServerOption("SUB", "Subtitulado", url))
                }
            }
        }
        return out
    }

    /**
     * Decodes gupload.site XOR-obfuscated player payload into the videoUrl (usually .m3u8 or .mp4).
     */
    fun extractGuploadVideoUrl(html: String): String? {
        val doc = Jsoup.parse(html)
        val keys = LinkedHashSet<String>()
        for (sc in doc.select("script")) {
            val data = sc.data()
            val m = guploadPartsRegex.find(data)
            if (m != null) {
                val joined = quotedStringRegex.findAll(m.groupValues[1])
                    .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    .joinToString("")
                if (joined.isNotBlank()) keys.add(joined)
            }
        }
        keys.add("G7#kP!2qZxV9mRwL")

        for (match in guploadPayloadRegex.findAll(html)) {
            val encoded = match.groupValues[1].substringAfter("~", "")
            if (encoded.length < 16) continue
            val rawBytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                continue
            }
            for (key in keys) {
                val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                val out = ByteArray(rawBytes.size)
                for (i in rawBytes.indices) {
                    out[i] = (rawBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
                }
                val decoded = String(out, StandardCharsets.UTF_8)
                if (decoded.contains("\"videoUrl\"")) {
                    val map = parseJsonMap(decoded)
                    val videoUrl = map?.get("videoUrl")?.toString()?.replace("\\/", "/")?.trim()
                    if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
                        return videoUrl
                    }
                }
            }
        }
        return null
    }

    fun decryptBysePlayback(ivB64Url: String, payloadB64Url: String, keyParts: List<String>): String? {
        if (keyParts.size < 2) return null
        fun decodeUrlB64(s: String): ByteArray {
            val fixed = s.replace('-', '+').replace('_', '/')
            val pad = (4 - fixed.length % 4) % 4
            return Base64.getDecoder().decode(fixed + "=".repeat(pad))
        }
        val keyBytes = decodeUrlB64(keyParts[0]) + decodeUrlB64(keyParts[1])
        val ivBytes = decodeUrlB64(ivB64Url)
        val cipherBytes = decodeUrlB64(payloadB64Url)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
        var plain = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (plain.startsWith("\uFEFF")) plain = plain.substring(1)
        val root = parseJsonMap(plain) ?: return null
        @Suppress("UNCHECKED_CAST")
        val sources = root["sources"] as? List<Map<String, Any?>> ?: return null
        return sources.firstOrNull()?.get("url")?.toString()?.trim()?.ifBlank { null }
    }
}
