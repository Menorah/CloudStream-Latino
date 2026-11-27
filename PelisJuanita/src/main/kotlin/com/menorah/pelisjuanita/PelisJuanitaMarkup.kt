package com.menorah.pelisjuanita

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

internal enum class PelisJuanitaItemKind {
    Movie,
    Series,
    Episode,
}

internal data class PelisJuanitaCatalogRequest(
    val url: String,
    val postForm: Map<String, String>? = null,
    val isMovies: Boolean,
    val isRecentEpisodes: Boolean = false,
)

internal data class PelisJuanitaCatalogCard(
    val title: String,
    val url: String,
    val kind: PelisJuanitaItemKind,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
)

internal data class PelisJuanitaCatalogPage(
    val items: List<PelisJuanitaCatalogCard>,
    val currentPage: Int,
    val totalPages: Int,
    val hasNext: Boolean,
)

internal data class PelisJuanitaSearchRecord(
    val id: Long,
    val tmdbId: Long,
    val kind: PelisJuanitaItemKind,
    val title: String,
    val originalTitle: String,
    val slug: String,
    val url: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val genres: List<String>,
    val year: Int?,
    val quality: String?,
    val synopsis: String?,
    val seasonsCount: Int?,
)

internal data class PelisJuanitaSearchResultPage(
    val items: List<PelisJuanitaSearchRecord>,
    val currentPage: Int,
    val totalPages: Int,
) {
    val hasNext: Boolean get() = currentPage < totalPages && items.isNotEmpty()
}

internal data class PelisJuanitaActor(
    val name: String,
    val role: String?,
    val imageUrl: String?,
)

internal data class PelisJuanitaStreamSource(
    val serverName: String,
    val languageLabel: String,
    val qualityLabel: String,
    val url: String,
)

internal data class PelisJuanitaEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val runtimeMinutes: Int?,
    val releaseDate: String?,
    val description: String?,
)

internal data class PelisJuanitaMovieDetail(
    val url: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<PelisJuanitaActor>,
    val trailerUrl: String?,
    val recommendations: List<PelisJuanitaCatalogCard>,
    val streams: List<PelisJuanitaStreamSource>,
)

internal data class PelisJuanitaSeriesDetail(
    val url: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<PelisJuanitaActor>,
    val trailerUrl: String?,
    val recommendations: List<PelisJuanitaCatalogCard>,
    val seasons: List<Int>,
    val currentSeason: Int,
    val currentEpisode: Int,
    val episodes: List<PelisJuanitaEpisode>,
    val streams: List<PelisJuanitaStreamSource>,
)

internal data class PelisJuanitaTargetUrl(
    val isSeries: Boolean,
    val slug: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val publicUrl: String
        get() = if (isSeries) {
            if (season != null && episode != null) {
                "${PelisJuanitaMarkup.MAIN_URL}/series/ver-serie/$slug/${season.toString().padStart(2, '0')}x${episode.toString().padStart(2, '0')}"
            } else {
                "${PelisJuanitaMarkup.MAIN_URL}/series/ver-serie/$slug"
            }
        } else {
            "${PelisJuanitaMarkup.MAIN_URL}/movies/pelicula/$slug"
        }

    val parentSeriesUrl: String
        get() = "${PelisJuanitaMarkup.MAIN_URL}/series/ver-serie/$slug"
}

internal object PelisJuanitaMarkup {
    const val MAIN_URL = "https://pelisjuanita.com"
    const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"

    val jsonMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val EPISODE_URL_REGEX = Regex("""(?i)(?:^|/)ver-serie/([^/?#]+)/(\d+)x(\d+)(?:/|$)""")
    private val SERIES_URL_REGEX = Regex("""(?i)(?:^|/)ver-serie/([^/?#]+)(?:/|$)""")
    private val MOVIE_URL_REGEX = Regex("""(?i)(?:^|/)pelicula/([^/?#]+)(?:/|$)""")
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val DURATION_REGEX = Regex("""(\d+)\s*(?:min\.?|m)\b""", RegexOption.IGNORE_CASE)
    private val DATE_DDMMYYYY_REGEX = Regex("""\b(\d{2}/\d{2}/\d{4})\b""")
    private val EPISODE_PREFIX_REGEX = Regex("""^\s*\[\s*(\d+)\s*x\s*(\d+)\s*]\s*""", RegexOption.IGNORE_CASE)
    private val EPISODE_SPAN_NUM_REGEX = Regex("""^\s*(\d+)\s*x\s*(\d+)\s*$""", RegexOption.IGNORE_CASE)
    private val SEASON_ONCLICK_REGEX = Regex("""cargarEpisodiosDeTemporada\(\s*['"]?(\d+)['"]?\s*\)""")
    private val CARGAR_SERVIDOR_REGEX = Regex("""cargarServi(?:d)?or\(\s*['"]([^'"]+)['"]\s*\)""")
    private val CARGAR_TRAILER_REGEX = Regex("""cargarTrailer\(\s*['"]([^'"]+)['"]\s*\)""")
    private val PAGE_OF_REGEX = Regex("""Página\s+\d+\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_HREF_REGEX = Regex("""(?:/page/|[?&]page=)(\d+)""", RegexOption.IGNORE_CASE)
    private val SERIE_ID_REGEX = Regex("""CURRENT_SERIE_ID\s*=\s*(\d+)""")
    private val MOVIE_ID_REGEX = Regex("""CURRENT_MOVIE_ID\s*=\s*(\d+)""")
    private val MOVIE_INFO_P_REGEX = Regex("""movieInfo\.php\?[^"'<>]*?[?&]p=(\d+)""")
    private val TMDB_FILENAME_REGEX = Regex("""^/*([A-Za-z0-9_-]{6,}\.(?:jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE)
    private val TMDB_FULL_PATH_REGEX = Regex("""^/t/p/[^/]+/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)
    private val TMDB_REL_TP_REGEX = Regex("""^/*(t/p/[^/]+/+[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE)
    private val MULTI_SLASH_REGEX = Regex("""/+""")
    private val SAVED_LOCAL_ASSETS_REGEX = Regex("""_files/([A-Za-z0-9_-]{6,}\.(?:jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE)

    fun isSiteUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return (scheme == "https" || scheme == "http") &&
            (host == "pelisjuanita.com" || host == "www.pelisjuanita.com")
    }

    fun resolveSiteUrl(raw: String?): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = runCatching {
            URI("$MAIN_URL/").resolve(clean).toString()
        }.getOrNull() ?: return null
        return resolved.takeIf { isSiteUrl(it) }
    }

    fun resolvePosterUrl(raw: String?): String? = resolveImageUrl(raw, TMDB_POSTER_BASE)

    fun resolveBackdropUrl(raw: String?): String? = resolveImageUrl(raw, TMDB_BACKDROP_BASE)

    private fun resolveImageUrl(raw: String?, tmdbBase: String): String? {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = clean.lowercase()
        if (lower.contains("logo-share.png") ||
            lower.contains("favicon.") ||
            lower.contains("no-actor.jpg") ||
            lower.endsWith("/default.png") ||
            lower.endsWith("play.png") ||
            lower == "null" ||
            lower == "undefined"
        ) {
            return null
        }
        TMDB_FILENAME_REGEX.matchEntire(clean)?.let { m ->
            return "$tmdbBase/${m.groupValues[1]}"
        }
        TMDB_REL_TP_REGEX.matchEntire(clean)?.let { m ->
            return "https://image.tmdb.org/${MULTI_SLASH_REGEX.replace(m.groupValues[1], "/")}"
        }
        val candidate = when {
            clean.startsWith("https://", ignoreCase = true) -> clean
            clean.startsWith("http://", ignoreCase = true) -> "https://${clean.substringAfter("://")}"
            clean.startsWith("//") -> "https:$clean"
            else -> {
                SAVED_LOCAL_ASSETS_REGEX.find(clean)?.let { m ->
                    return "$tmdbBase/${m.groupValues[1]}"
                }
                runCatching { URI("$MAIN_URL/movies/").resolve(clean).toString() }.getOrNull() ?: return null
            }
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.isEmpty() || rawPath.endsWith("/")) return null
        val normalizedPath = MULTI_SLASH_REGEX.replace(rawPath, "/")
        if (host.endsWith("image.tmdb.org")) {
            if (!TMDB_FULL_PATH_REGEX.matches(normalizedPath)) {
                return null
            }
            return if (normalizedPath == rawPath) {
                candidate
            } else {
                runCatching {
                    URI(uri.scheme, uri.authority, normalizedPath, uri.rawQuery, uri.rawFragment).toString()
                }.getOrDefault(candidate)
            }
        }
        return candidate
    }

    fun parseTargetUrl(url: String): PelisJuanitaTargetUrl? {
        val clean = url.trim()
        EPISODE_URL_REGEX.find(clean)?.let { m ->
            return PelisJuanitaTargetUrl(
                isSeries = true,
                slug = m.groupValues[1],
                season = m.groupValues[2].toIntOrNull(),
                episode = m.groupValues[3].toIntOrNull(),
            )
        }
        SERIES_URL_REGEX.find(clean)?.let { m ->
            return PelisJuanitaTargetUrl(isSeries = true, slug = m.groupValues[1])
        }
        MOVIE_URL_REGEX.find(clean)?.let { m ->
            return PelisJuanitaTargetUrl(isSeries = false, slug = m.groupValues[1])
        }
        return null
    }

    fun buildCatalogRequest(sectionData: String, page: Int): PelisJuanitaCatalogRequest {
        val safePage = page.coerceAtLeast(1)
        val parts = sectionData.split(':', limit = 3)
        val kind = parts.getOrNull(0).orEmpty()
        val tipo = parts.getOrNull(1).orEmpty()
        val param = parts.getOrNull(2).orEmpty()
        val encodedParam = if (param.isEmpty()) "" else URLEncoder.encode(param, "UTF-8").replace("+", "%20")

        if (kind == "series") {
            if (tipo == "ultimos-episodios") {
                return if (safePage <= 1) {
                    PelisJuanitaCatalogRequest(
                        url = "$MAIN_URL/series/apiSeries.php?ultimos-episodios=",
                        postForm = null,
                        isMovies = false,
                        isRecentEpisodes = true,
                    )
                } else {
                    PelisJuanitaCatalogRequest(
                        url = "$MAIN_URL/series/apiSeries.php",
                        postForm = mapOf("page" to safePage.toString()),
                        isMovies = false,
                        isRecentEpisodes = true,
                    )
                }
            }
            return PelisJuanitaCatalogRequest(
                url = "$MAIN_URL/series/apiSeries.php?$tipo=$encodedParam&page=$safePage",
                postForm = null,
                isMovies = false,
            )
        }

        return PelisJuanitaCatalogRequest(
            url = "$MAIN_URL/movies/movies.php?$tipo=$encodedParam&page=$safePage",
            postForm = null,
            isMovies = true,
        )
    }

    fun normalizeSearchQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed == "%") return "%"
        return trimmed
            .replace('&', ' ')
            .replace('\'', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun buildSearchApiUrl(query: String, page: Int = 1): String {
        val safePage = page.coerceAtLeast(1)
        val norm = normalizeSearchQuery(query)
        val encoded = if (norm == "%") "%25" else URLEncoder.encode(norm, "UTF-8")
        return "$MAIN_URL/tv/app/buscar.php?q=$encoded&pagina=$safePage"
    }

    fun buildSearchPageUrl(query: String, page: Int = 1): String {
        val safePage = page.coerceAtLeast(1)
        val encoded = URLEncoder.encode(normalizeSearchQuery(query), "UTF-8")
        return if (safePage <= 1) {
            "$MAIN_URL/movies/search?s=$encoded"
        } else {
            "$MAIN_URL/series/page/$safePage/search?s=$encoded"
        }
    }

    fun buildUnifiedSearchAjaxUrl(query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(normalizeSearchQuery(query), "UTF-8")
        return "$MAIN_URL/movies/apiSeries.php?s=$encoded&page=${page.coerceAtLeast(1)}"
    }

    fun buildMovieSearchAjaxUrl(query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(normalizeSearchQuery(query), "UTF-8")
        return "$MAIN_URL/movies/movies.php?s=$encoded&page=${page.coerceAtLeast(1)}"
    }

    fun buildSeriesSearchAjaxUrl(query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(normalizeSearchQuery(query), "UTF-8")
        return "$MAIN_URL/series/apiSeries.php?s=$encoded&page=${page.coerceAtLeast(1)}"
    }

    fun buildMovieShellUrl(slug: String): String =
        "$MAIN_URL/movies/pelicula/$slug"

    fun buildMovieInfoUrl(slug: String, movieId: Long? = null): String {
        val pVal = movieId?.takeIf { it > 0 }?.toString().orEmpty()
        val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        return "$MAIN_URL/movies/movieInfo.php?title=$encodedSlug&p=$pVal"
    }

    fun buildSerieShellUrl(slug: String): String =
        "$MAIN_URL/series/ver-serie/$slug"

    fun buildSerieInfoEpisodeUrl(slug: String, season: Int, episode: Int, serieId: Long?): String {
        val pVal = serieId?.takeIf { it > 0 }?.toString().orEmpty()
        val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        return "$MAIN_URL/series/serieInfo.php?nombreSerie=$encodedSlug&nroTemporada=$season&nroEpisodio=$episode&p=$pVal"
    }

    fun buildSerieInfoSeasonUrl(slug: String, season: Int, snum: Int, enumVal: Int, serieId: Long?): String {
        val pVal = serieId?.takeIf { it > 0 }?.toString().orEmpty()
        val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        return "$MAIN_URL/series/serieInfo.php?nombreSerie=$encodedSlug&temporada=$season&snum=$snum&enum=$enumVal&p=$pVal"
    }

    fun buildSerieInfoSeasonModeUrl(slug: String, season: Int, serieId: Long?): String {
        val pVal = serieId?.takeIf { it > 0 }?.toString().orEmpty()
        val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        return "$MAIN_URL/series/serieInfo.php?nombreSerie=$encodedSlug&nroTemporada=$season&nroEpisodio=0&modoTemporada=1&p=$pVal"
    }

    fun extractSeriesIdFromShell(html: String): Long? =
        SERIE_ID_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun extractMovieIdFromShell(html: String): Long? =
        MOVIE_INFO_P_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: MOVIE_ID_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun parseCatalogHtml(
        html: String,
        currentPage: Int = 1,
        isRecentEpisodes: Boolean = false,
    ): PelisJuanitaCatalogPage {
        val doc = Jsoup.parse(html, MAIN_URL)
        val cards = mutableListOf<PelisJuanitaCatalogCard>()
        val seenUrls = mutableSetOf<String>()

        for (anchor in doc.select(".grid-container .grid-item > a, .carousel article.card > a")) {
            val href = resolveSiteUrl(anchor.attr("href")) ?: continue
            val target = parseTargetUrl(href) ?: continue
            val normalizedUrl = target.publicUrl
            if (!seenUrls.add(normalizedUrl)) continue

            val img = anchor.selectFirst("img.img-cover") ?: anchor.selectFirst("img")
            val rawTitle = anchor.selectFirst("h2")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: cleanAltTitle(img?.attr("alt"))
                ?: continue
            val poster = resolvePosterUrl(
                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src"),
            )
            val year = anchor.selectFirst(".hover-info .right")?.text()?.let { extractYear(it) }
                ?: anchor.selectFirst(".fecha")?.text()?.let { extractYear(it) }
            val rating = anchor.selectFirst(".hover-info .left")?.text()
                ?.replace("★", "")
                ?.trim()
                ?.toDoubleOrNull()
            val kind = if (target.isSeries || anchor.selectFirst(".seriesTag") != null) {
                PelisJuanitaItemKind.Series
            } else {
                PelisJuanitaItemKind.Movie
            }
            cards += PelisJuanitaCatalogCard(
                title = rawTitle,
                url = normalizedUrl,
                kind = kind,
                posterUrl = poster,
                year = year,
                rating = rating,
            )
        }

        for (anchor in doc.select(".grid-container-episodios .grid-item-episodios > a, #contenedor-tira .item-tira a")) {
            val href = resolveSiteUrl(anchor.attr("href")) ?: continue
            val target = parseTargetUrl(href) ?: continue
            val normalizedUrl = target.publicUrl
            if (!seenUrls.add(normalizedUrl)) continue

            val img = anchor.selectFirst("img.img-cover-episodios") ?: anchor.selectFirst("img")
            val seriesTitle = anchor.selectFirst(".texto-tira-episodios h3")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: anchor.selectFirst("h3, h2")?.text()?.trim()
                ?: cleanAltTitle(img?.attr("alt"))
                ?: continue
            val badge = anchor.selectFirst(".texto-tira-episodios span")?.text()?.trim().orEmpty()
            val displayTitle = if (badge.isNotEmpty() && !seriesTitle.contains(badge, ignoreCase = true)) {
                "$seriesTitle $badge"
            } else {
                seriesTitle
            }
            val poster = resolvePosterUrl(
                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src"),
            )
            cards += PelisJuanitaCatalogCard(
                title = displayTitle,
                url = normalizedUrl,
                kind = PelisJuanitaItemKind.Episode,
                posterUrl = poster,
                year = null,
                rating = null,
            )
        }

        val pagText = doc.selectFirst(".pagination")?.text().orEmpty()
        var totalPages = PAGE_OF_REGEX.find(pagText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        for (el in doc.select(".pagination a[href]")) {
            val hrefNum = PAGE_HREF_REGEX.find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            val textNum = el.text().trim().toIntOrNull()
            totalPages = maxOf(totalPages, hrefNum ?: 0, textNum ?: 0)
        }
        val hasNext = when {
            totalPages > 0 -> currentPage < totalPages
            isRecentEpisodes -> cards.size >= 20
            else -> doc.selectFirst(".pagination a[href*=\"page/\"], .pagination a[href*=\"page=\"]") != null
        }

        return PelisJuanitaCatalogPage(
            items = cards,
            currentPage = currentPage,
            totalPages = totalPages.coerceAtLeast(if (cards.isNotEmpty()) currentPage else 0),
            hasNext = hasNext && cards.isNotEmpty(),
        )
    }

    fun parseSearchJson(jsonText: String): PelisJuanitaSearchResultPage? {
        val root = runCatching { jsonMapper.readTree(jsonText) }.getOrNull() ?: return null
        val status = root.get("status")?.asText()?.lowercase().orEmpty()
        if (status != "success" && !root.has("datos")) return null

        val currentPage = root.get("pagina_actual")?.asInt(1) ?: 1
        val totalPages = root.get("total_paginas")?.asInt(1) ?: 1
        val datos = root.get("datos")
        if (datos == null || !datos.isArray) {
            return PelisJuanitaSearchResultPage(emptyList(), currentPage, totalPages)
        }

        val out = mutableListOf<PelisJuanitaSearchRecord>()
        for (node in datos) {
            val tipo = node.get("tipo")?.asText()?.trim()?.lowercase().orEmpty()
            val slug = node.get("slug")?.asText()?.trim().orEmpty()
            val title = node.get("titulo")?.asText()?.trim().orEmpty()
            if (slug.isEmpty() || title.isEmpty()) continue
            val isSeries = tipo == "serie" || tipo == "series"
            val url = if (isSeries) {
                "$MAIN_URL/series/ver-serie/$slug"
            } else {
                "$MAIN_URL/movies/pelicula/$slug"
            }
            val genres = node.get("genero")?.asText()?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val rating = node.get("votos")?.asText()?.toDoubleOrNull()?.takeIf { it > 0.0 }
            val year = node.get("fecha_estreno")?.asText()?.let { extractYear(it) }
            val seasonsCount = node.get("temporadas")?.asText()?.trim()?.toIntOrNull()
            out += PelisJuanitaSearchRecord(
                id = node.get("id")?.asLong(0L) ?: 0L,
                tmdbId = node.get("TMDbId")?.asLong(0L) ?: 0L,
                kind = if (isSeries) PelisJuanitaItemKind.Series else PelisJuanitaItemKind.Movie,
                title = title,
                originalTitle = node.get("titulo_original")?.asText()?.trim().orEmpty(),
                slug = slug,
                url = url,
                posterUrl = resolvePosterUrl(node.get("img_frente")?.asText()),
                backdropUrl = resolveBackdropUrl(node.get("img_fondo")?.asText()),
                rating = rating,
                genres = genres,
                year = year,
                quality = node.get("calidad")?.asText()?.trim()?.takeIf { it.isNotEmpty() },
                synopsis = node.get("sinopsis")?.asText()?.trim()?.takeIf { it.isNotEmpty() },
                seasonsCount = seasonsCount,
            )
        }
        return PelisJuanitaSearchResultPage(out, currentPage, totalPages)
    }

    fun parseMovieDetailHtml(html: String, publicUrl: String): PelisJuanitaMovieDetail? {
        val doc = Jsoup.parse(html, publicUrl)
        val title = doc.selectFirst(".titleInfo h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val posterUrl = resolvePosterUrl(elementImageSrc(doc.selectFirst(".titleInfo .columna.imagen img")))
        val backdropUrl = resolveBackdropUrl(elementImageSrc(doc.selectFirst(".columna-principal .Image img#cpt")))
        val synopsis = doc.selectFirst(".titleInfo p.sinopsis")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val year = doc.selectFirst(".titleInfo .info-linea a[href*=\"/release/\"]")?.text()?.let { extractYear(it) }
            ?: extractYear(doc.selectFirst(".titleInfo .info-linea")?.text().orEmpty())
        val duration = doc.select(".titleInfo .info-linea span")
            .firstNotNullOfOrNull { DURATION_REGEX.find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val rating = parseRating(doc)
        val genres = doc.select("#sGenero a.badge-etiqueta")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val actors = parseActors(doc)
        val trailer = parseTrailerUrl(doc)
        val recs = parseRecommendations(doc)
        val streams = parseStreamSourcesFromDoc(doc)

        return PelisJuanitaMovieDetail(
            url = publicUrl,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            synopsis = synopsis,
            year = year,
            durationMinutes = duration,
            rating = rating,
            genres = genres,
            actors = actors,
            trailerUrl = trailer,
            recommendations = recs,
            streams = streams,
        )
    }

    fun parseSeriesDetailHtml(html: String, publicUrl: String): PelisJuanitaSeriesDetail? {
        val doc = Jsoup.parse(html, publicUrl)
        val title = doc.selectFirst(".titleInfo h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val target = parseTargetUrl(publicUrl)
        val seriesSlug = target?.slug.orEmpty()
        val posterUrl = resolvePosterUrl(elementImageSrc(doc.selectFirst(".titleInfo .columna.imagen img")))
        val backdropUrl = resolveBackdropUrl(elementImageSrc(doc.selectFirst(".columna-principal .Image img#cpt")))
        val synopsis = doc.selectFirst(".titleInfo p.sinopsis")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val year = doc.selectFirst(".titleInfo .info-linea a[href*=\"/release/\"]")?.text()?.let { extractYear(it) }
            ?: extractYear(doc.selectFirst(".titleInfo .info-linea")?.text().orEmpty())
        val duration = doc.select(".titleInfo .info-linea span")
            .firstNotNullOfOrNull { DURATION_REGEX.find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val rating = parseRating(doc)
        val genres = doc.select("#sGenero a.badge-etiqueta")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val actors = parseActors(doc)
        val trailer = parseTrailerUrl(doc)
        val recs = parseRecommendations(doc)

        val currentSeason = doc.selectFirst("meta[name=snum]")?.attr("content")?.toIntOrNull() ?: 1
        val currentEpisode = doc.selectFirst("meta[name=enum]")?.attr("content")?.toIntOrNull() ?: 1

        val seasonSet = linkedSetOf<Int>()
        for (item in doc.select("#temporadasDropdown .server-item")) {
            val onclickNum = SEASON_ONCLICK_REGEX.find(item.attr("onclick"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            val textNum = Regex("""\d+""").find(item.text())?.value?.toIntOrNull()
            val s = onclickNum ?: textNum
            if (s != null && s > 0) seasonSet += s
        }
        val episodes = parseSeasonEpisodesFromDoc(doc, seriesSlug)
        for (ep in episodes) {
            if (ep.season > 0) seasonSet += ep.season
        }
        if (seasonSet.isEmpty()) seasonSet += currentSeason.coerceAtLeast(1)

        val streams = parseStreamSourcesFromDoc(doc)
        return PelisJuanitaSeriesDetail(
            url = target?.parentSeriesUrl ?: publicUrl,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            synopsis = synopsis,
            year = year,
            durationMinutes = duration,
            rating = rating,
            genres = genres,
            actors = actors,
            trailerUrl = trailer,
            recommendations = recs,
            seasons = seasonSet.sorted(),
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            episodes = episodes,
            streams = streams,
        )
    }

    fun parseSeasonEpisodesHtml(
        html: String,
        seriesSlug: String,
        defaultSeason: Int = 1,
    ): List<PelisJuanitaEpisode> {
        val doc = Jsoup.parse(html, MAIN_URL)
        return parseSeasonEpisodesFromDoc(doc, seriesSlug, defaultSeason)
    }

    fun parseStreamSourcesHtml(html: String): List<PelisJuanitaStreamSource> {
        val doc = Jsoup.parse(html, MAIN_URL)
        return parseStreamSourcesFromDoc(doc)
    }

    private fun parseSeasonEpisodesFromDoc(
        doc: org.jsoup.nodes.Document,
        seriesSlug: String,
        defaultSeason: Int = 1,
    ): List<PelisJuanitaEpisode> {
        val out = mutableListOf<PelisJuanitaEpisode>()
        val seenKeys = mutableSetOf<Pair<Int, Int>>()

        // serieInfo.php?temporada=N returns bare <a class="episodio-item"> elements without the #ulTemporada wrapper.
        val anchors = doc.select("#ulTemporada a.episodio-item").ifEmpty { doc.select("a.episodio-item") }
        for (anchor in anchors) {
            val href = resolveSiteUrl(anchor.attr("href"))
            val parsedHref = href?.let { parseTargetUrl(it) }
            val rawHeading = anchor.selectFirst("h2.list-title")?.text()?.trim().orEmpty()
            val headingMatch = EPISODE_PREFIX_REGEX.find(rawHeading)
            val season = parsedHref?.season
                ?: headingMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: defaultSeason.coerceAtLeast(1)
            val episode = parsedHref?.episode
                ?: headingMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: continue
            if (!seenKeys.add(season to episode)) continue

            val cleanTitle = rawHeading.replace(EPISODE_PREFIX_REGEX, "").trim()
                .ifEmpty { "Episodio $episode" }
            val metaText = anchor.selectFirst(".episodio-info")?.text().orEmpty()
            val runtime = DURATION_REGEX.find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val releaseDate = DATE_DDMMYYYY_REGEX.find(metaText)?.groupValues?.getOrNull(1)
            val description = anchor.selectFirst("p.list-sinopsis")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val canonicalSlug = parsedHref?.slug?.takeIf { it.isNotEmpty() } ?: seriesSlug
            val normalizedEpUrl = "$MAIN_URL/series/ver-serie/$canonicalSlug/${season.toString().padStart(2, '0')}x${episode.toString().padStart(2, '0')}"

            out += PelisJuanitaEpisode(
                season = season,
                episode = episode,
                title = cleanTitle,
                url = normalizedEpUrl,
                runtimeMinutes = runtime,
                releaseDate = releaseDate,
                description = description,
            )
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun parseStreamSourcesFromDoc(doc: org.jsoup.nodes.Document): List<PelisJuanitaStreamSource> {
        val out = mutableListOf<PelisJuanitaStreamSource>()
        val seen = mutableSetOf<Pair<String, String>>()
        val rows = doc.select(".box-download .row-download").ifEmpty { doc.select(".row-download") }

        fun normalizeStreamUrl(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        }

        fun collectRows(strictStreamType: Boolean) {
            for (row in rows) {
                val tipo = row.attr("data-tipo").trim().lowercase()
                if (strictStreamType) {
                    if (tipo != "stream") continue
                } else {
                    if (tipo in setOf("descarga", "download", "descargas", "ayuda", "tutorial", "guide")) continue
                }

                val rawUrl = normalizeStreamUrl(
                    row.attr("data-url").trim()
                        .ifEmpty { row.attr("data-src").trim() }
                        .ifEmpty { row.attr("data-link").trim() }
                        .ifEmpty {
                            CARGAR_SERVIDOR_REGEX.find(row.attr("onclick"))?.groupValues?.getOrNull(1).orEmpty().trim()
                        },
                )
                if (!rawUrl.startsWith("http", ignoreCase = true)) continue

                val lang = normalizeLanguageLabel(row.attr("data-idioma"))
                val spans = row.select("span").map { it.text().trim() }
                val serverName = spans.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: guessServerName(rawUrl)
                val quality = spans.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "HD"

                if (seen.add(lang to rawUrl)) {
                    out += PelisJuanitaStreamSource(
                        serverName = serverName,
                        languageLabel = lang,
                        qualityLabel = quality,
                        url = rawUrl,
                    )
                }
            }
        }

        collectRows(strictStreamType = true)
        if (out.isEmpty()) {
            collectRows(strictStreamType = false)
        }

        val iframeSrc = normalizeStreamUrl(doc.selectFirst("iframe#if-video")?.attr("src").orEmpty())
        if (iframeSrc.startsWith("http", ignoreCase = true) && seen.none { it.second == iframeSrc }) {
            out += PelisJuanitaStreamSource(
                serverName = guessServerName(iframeSrc),
                languageLabel = "Latino",
                qualityLabel = "HD",
                url = iframeSrc,
            )
        }
        return out
    }

    private fun parseRating(doc: org.jsoup.nodes.Document): Double? {
        val tmdb = doc.selectFirst(".info-linea a[href*=\"themoviedb.org\"] span b")?.text()?.trim()?.toDoubleOrNull()
        if (tmdb != null && tmdb > 0.0) return tmdb
        val imdb = doc.selectFirst(".info-linea a[href*=\"imdb.com\"] span b")?.text()?.trim()?.toDoubleOrNull()
        return imdb?.takeIf { it > 0.0 }
    }

    private fun parseActors(doc: org.jsoup.nodes.Document): List<PelisJuanitaActor> {
        val out = mutableListOf<PelisJuanitaActor>()
        for (item in doc.select(".carousel-actores .carousel-actores-item")) {
            val name = item.selectFirst("p strong")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val role = item.select("p").getOrNull(1)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val img = resolvePosterUrl(elementImageSrc(item.selectFirst("img")))
            out += PelisJuanitaActor(name = name, role = role, imageUrl = img)
        }
        return out
    }

    private fun elementImageSrc(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        return el.attr("data-src").ifBlank { el.attr("data-lazy-src") }.ifBlank { el.attr("src") }
    }

    private fun parseTrailerUrl(doc: org.jsoup.nodes.Document): String? {
        for (el in doc.select("[onclick*=cargarTrailer]")) {
            val raw = CARGAR_TRAILER_REGEX.find(el.attr("onclick"))?.groupValues?.getOrNull(1) ?: continue
            if (raw.startsWith("http", ignoreCase = true)) return raw
        }
        return null
    }

    private fun parseRecommendations(doc: org.jsoup.nodes.Document): List<PelisJuanitaCatalogCard> {
        val out = mutableListOf<PelisJuanitaCatalogCard>()
        val seen = mutableSetOf<String>()
        for (anchor in doc.select(".container-carousel .carousel .card > a")) {
            val href = resolveSiteUrl(anchor.attr("href")) ?: continue
            val target = parseTargetUrl(href) ?: continue
            if (!seen.add(target.publicUrl)) continue
            val img = anchor.selectFirst("img.img-cover") ?: anchor.selectFirst("img")
            val title = anchor.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: cleanAltTitle(img?.attr("alt"))
                ?: continue
            val poster = resolvePosterUrl(elementImageSrc(img))
            out += PelisJuanitaCatalogCard(
                title = title,
                url = target.publicUrl,
                kind = if (target.isSeries) PelisJuanitaItemKind.Series else PelisJuanitaItemKind.Movie,
                posterUrl = poster,
                year = null,
                rating = null,
            )
        }
        return out
    }

    fun normalizeLanguageLabel(raw: String?): String {
        val lower = raw.orEmpty().trim().lowercase()
        return when {
            lower.contains("latino") -> "Latino"
            lower.contains("espa") || lower.contains("castellano") -> "Español"
            lower.contains("sub") -> "Subtitulado"
            else -> "Latino"
        }
    }

    private fun guessServerName(url: String): String {
        if (PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl(url)) return "Vidsrc"
        val lower = url.lowercase()
        return when {
            lower.contains("vimeus.") -> "Vimeus"
            lower.contains("vimeos.") -> "Vimeos"
            lower.contains("fortamomar.") -> "Fastserver"
            lower.contains("playmogo.") || lower.contains("dood") || lower.contains("myvidplay") || lower.contains("d000d") || lower.contains("do0od") || lower.contains("ds2play") -> "Dood"
            lower.contains("byse") || lower.contains("n1mwq.org") -> "Byse"
            lower.contains("voe.") || lower.contains("caseyseemavoid") -> "Voe"
            lower.contains("lulu") || lower.contains("lplaygo") -> "Lulustream"
            lower.contains("mixdrop") || lower.contains("mxdrop") -> "Mixdrop"
            lower.contains("vidara") || lower.contains("oisauh") -> "Vidara"
            lower.contains("savefiles") -> "Savefiles"
            lower.contains("morencius") ||
                lower.contains("callistanise") ||
                lower.contains("minochinos") ||
                lower.contains("movearnpre") ||
                lower.contains("dingtezuni") ||
                lower.contains("mivalyo") ||
                lower.contains("taylorplayer") ||
                lower.contains("vidhide") ||
                lower.contains("filelions") ||
                lower.contains("smoothpre") ||
                lower.contains("peytonepre") ||
                lower.contains("dhtpre") ||
                lower.contains("ryderjet") -> "Earnvids"
            lower.contains("streamwish") ||
                lower.contains("hglink") ||
                lower.contains("medixiru") ||
                lower.contains("vsembed") ||
                lower.contains("flaswish") ||
                lower.contains("hlswish") ||
                lower.contains("playerwish") ||
                lower.contains("strwish") ||
                lower.contains("dhcplay") -> "Streamwish"
            else -> runCatching { URI(url).host?.removePrefix("www.") ?: "Servidor" }.getOrDefault("Servidor")
        }
    }

    private fun extractYear(raw: String?): Int? =
        YEAR_REGEX.find(raw.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun cleanAltTitle(raw: String?): String? =
        raw?.trim()
            ?.removePrefix("Ver Película ")
            ?.removePrefix("Ver Serie ")
            ?.removePrefix("Ver ")
            ?.replace(Regex("""\s+Online.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
