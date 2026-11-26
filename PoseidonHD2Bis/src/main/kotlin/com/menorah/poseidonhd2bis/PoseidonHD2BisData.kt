package com.menorah.poseidonhd2bis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jsoup.Jsoup
import java.net.URI

internal enum class PoseidonItemKind {
    Movie,
    Series,
    Episode,
}

internal data class PoseidonCatalogItem(
    val kind: PoseidonItemKind,
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
)

internal data class PoseidonCatalogPage(
    val items: List<PoseidonCatalogItem>,
    val totalPages: Int,
)

internal data class PoseidonVideoSource(
    val languageKey: String,
    val languageLabel: String,
    val cyberlocker: String,
    val playerUrl: String,
    val quality: String?,
)

internal data class PoseidonMovieDetail(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val recommendations: List<PoseidonCatalogItem>,
    val videos: List<PoseidonVideoSource>,
)

internal data class PoseidonEpisodeEntry(
    val title: String,
    val season: Int,
    val episode: Int,
    val url: String,
    val posterUrl: String?,
    val releaseDate: String?,
)

internal data class PoseidonSeriesDetail(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val actors: List<String>,
    val episodes: List<PoseidonEpisodeEntry>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class NextDataEnvelope(
    val props: NextProps? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class NextProps(
    val pageProps: NextPageProps? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class NextPageProps(
    val movies: List<PoseidonRawItem>? = null,
    val episodes: List<PoseidonRawEpisode>? = null,
    val pages: Int? = null,
    val thisMovie: PoseidonRawMovie? = null,
    val relatedMovies: List<PoseidonRawItem>? = null,
    val otherMovies: List<PoseidonRawItem>? = null,
    val thisSerie: PoseidonRawSeries? = null,
    val serie: PoseidonRawSeries? = null,
    val episode: PoseidonRawEpisodeDetail? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawItem(
    @JsonProperty("TMDbId") val tmdbId: String? = null,
    val titles: PoseidonRawTitles? = null,
    val images: PoseidonRawImages? = null,
    val url: PoseidonRawUrl? = null,
    val releaseDate: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawTitles(
    val name: String? = null,
    val original: PoseidonRawOriginalTitle? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawOriginalTitle(
    val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawImages(
    val poster: String? = null,
    val backdrop: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawUrl(
    val slug: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawGenre(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawActor(
    val id: String? = null,
    val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawCast(
    val acting: List<PoseidonRawActor>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawRate(
    val average: Double? = null,
    val votes: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawVideoEntry(
    val cyberlocker: String? = null,
    val result: String? = null,
    val quality: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawMovie(
    @JsonProperty("TMDbId") val tmdbId: String? = null,
    val titles: PoseidonRawTitles? = null,
    val images: PoseidonRawImages? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val genres: List<PoseidonRawGenre>? = null,
    val cast: PoseidonRawCast? = null,
    val rate: PoseidonRawRate? = null,
    val url: PoseidonRawUrl? = null,
    val releaseDate: String? = null,
    val videos: Map<String, List<PoseidonRawVideoEntry>>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawEpisode(
    @JsonProperty("TMDbId") val tmdbId: String? = null,
    val title: String? = null,
    val number: Int? = null,
    val releaseDate: String? = null,
    val image: String? = null,
    val url: PoseidonRawUrl? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawSeason(
    val number: Int? = null,
    val episodes: List<PoseidonRawEpisode>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawSeries(
    @JsonProperty("TMDbId") val tmdbId: String? = null,
    val titles: PoseidonRawTitles? = null,
    val images: PoseidonRawImages? = null,
    val overview: String? = null,
    val genres: List<PoseidonRawGenre>? = null,
    val cast: PoseidonRawCast? = null,
    val rate: PoseidonRawRate? = null,
    val url: PoseidonRawUrl? = null,
    val releaseDate: String? = null,
    val seasons: List<PoseidonRawSeason>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PoseidonRawEpisodeDetail(
    @JsonProperty("TMDbId") val tmdbId: String? = null,
    val title: String? = null,
    val number: Int? = null,
    val videos: Map<String, List<PoseidonRawVideoEntry>>? = null,
)

internal object PoseidonHD2BisData {
    const val MAIN_URL = "https://www.poseidonhd2.co"
    const val PLAYER_REFERER = "$MAIN_URL/"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val NEXT_DATA_REGEX = Regex(
        """(?s)<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
    )
    private val PLAYER_VAR_URL_REGEX = Regex(
        """(?s)\b(?:var|let|const)\s+(?:url|link|src)\s*=\s*['"](https?://[^'"]+)['"]""",
    )
    private val PLAYER_REDIRECT_REGEX = Regex(
        """(?s)window\.location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""",
    )
    private val ANY_URL_REGEX = Regex("""['"](https?://[^'"]+)['"]""")
    private val EPISODE_PATH_REGEX = Regex("""(?i)^(/serie/[^/]+/[^/?#]+)/temporada/\d+/episodio/\d+.*$""")
    private val SLUG_EPISODE_REGEX = Regex("""^series/([^/]+)/([^/]+)/seasons/(\d+)/episodes/(\d+)$""")
    private val YEAR_PREFIX_REGEX = Regex("""^(\d{4})""")

    private val AD_OR_TRACKING_HOSTS = listOf(
        "doo.lat",
        "yandex.",
        "adme2.",
        "cloudflare",
        "googletagmanager",
        "doubleclick",
        "poseidonhd2.",
    )

    val jsonMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun extractNextDataJson(html: String): String? {
        val regexMatch = NEXT_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!regexMatch.isNullOrEmpty()) return regexMatch
        val doc = Jsoup.parse(html)
        return doc.selectFirst("script#__NEXT_DATA__")?.data()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun parsePageProps(html: String): NextPageProps? {
        val rawJson = extractNextDataJson(html) ?: return null
        return runCatching {
            jsonMapper.readValue(rawJson, NextDataEnvelope::class.java).props?.pageProps
        }.getOrNull()
    }

    fun buildPagedUrl(basePath: String, page: Int): String {
        val cleanBase = basePath.trimEnd('/')
        val fullBase = if (cleanBase.startsWith("http", ignoreCase = true)) {
            cleanBase
        } else {
            val normalizedPath = if (cleanBase.startsWith("/")) cleanBase else "/$cleanBase"
            if (normalizedPath == "/") MAIN_URL else "$MAIN_URL$normalizedPath"
        }
        return if (page <= 1) fullBase else "$fullBase/page/$page"
    }

    fun slugToPublicUrl(rawSlug: String?): String? {
        val slug = rawSlug?.trim()?.trimStart('/') ?: return null
        if (slug.isEmpty()) return null
        if (slug.startsWith("http://", ignoreCase = true) || slug.startsWith("https://", ignoreCase = true)) {
            return resolveSiteUrl(slug)
        }
        val epMatch = SLUG_EPISODE_REGEX.matchEntire(slug)
        if (epMatch != null) {
            val (tmdb, nameSlug, season, ep) = epMatch.destructured
            return "$MAIN_URL/serie/$tmdb/$nameSlug/temporada/$season/episodio/$ep"
        }
        return when {
            slug.startsWith("movies/") -> "$MAIN_URL/pelicula/${slug.removePrefix("movies/")}"
            slug.startsWith("series/") -> "$MAIN_URL/serie/${slug.removePrefix("series/")}"
            slug.startsWith("pelicula/") || slug.startsWith("serie/") -> "$MAIN_URL/$slug"
            else -> "$MAIN_URL/$slug"
        }
    }

    fun resolveSiteUrl(url: String?): String? {
        val clean = url?.trim() ?: return null
        if (clean.isEmpty()) return null
        if (clean.startsWith("movies/") || clean.startsWith("series/")) {
            return slugToPublicUrl(clean)
        }
        val resolved = runCatching { URI("$MAIN_URL/").resolve(clean).toString() }.getOrNull() ?: return null
        val path = runCatching { URI(resolved).rawPath.orEmpty() }.getOrNull().orEmpty()
        return if (resolved.startsWith("http", ignoreCase = true)) {
            if (resolved.contains("poseidonhd2.", ignoreCase = true)) "$MAIN_URL$path" else resolved
        } else {
            null
        }
    }

    fun isMovieUrl(url: String): Boolean = url.contains("/pelicula/", ignoreCase = true)

    fun extractSeriesBaseUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val path = uri.rawPath.orEmpty()
        val match = EPISODE_PATH_REGEX.matchEntire(path)
        return if (match != null) {
            "$MAIN_URL${match.groupValues[1]}"
        } else {
            "$MAIN_URL${path.trimEnd('/')}"
        }
    }

    fun resolveImageUrl(raw: String?): String? {
        val clean = raw?.trim() ?: return null
        if (clean.isEmpty()) return null
        return when {
            clean.startsWith("https://", ignoreCase = true) ||
                clean.startsWith("http://", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "https://image.tmdb.org/t/p/original$clean"
            else -> "https://image.tmdb.org/t/p/original/$clean"
        }
    }

    fun parseYear(releaseDate: String?): Int? {
        val text = releaseDate?.trim() ?: return null
        return YEAR_PREFIX_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun mapLanguageLabel(langKey: String): String = when (langKey.trim().lowercase()) {
        "latino" -> "Latino"
        "spanish", "español", "espanol", "castellano" -> "Español"
        "english", "subtitulado", "sub" -> "Subtitulado"
        else -> langKey.trim().replaceFirstChar { it.uppercaseChar() }.ifEmpty { "Latino" }
    }

    fun parseCatalogPage(html: String): PoseidonCatalogPage {
        val pp = parsePageProps(html) ?: return PoseidonCatalogPage(emptyList(), 1)
        val out = mutableListOf<PoseidonCatalogItem>()

        val movies = pp.movies.orEmpty()
        for (item in movies) {
            val rawSlug = item.url?.slug.orEmpty()
            val publicUrl = slugToPublicUrl(rawSlug) ?: continue
            val title = item.titles?.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.titles?.original?.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
            val kind = when {
                SLUG_EPISODE_REGEX.matches(rawSlug.trimStart('/')) ||
                    publicUrl.contains("/temporada/", ignoreCase = true) -> PoseidonItemKind.Episode
                rawSlug.startsWith("series/", ignoreCase = true) ||
                    publicUrl.contains("/serie/", ignoreCase = true) -> PoseidonItemKind.Series
                else -> PoseidonItemKind.Movie
            }
            out += PoseidonCatalogItem(
                kind = kind,
                title = title,
                url = publicUrl,
                posterUrl = resolveImageUrl(item.images?.poster ?: item.images?.backdrop),
                year = parseYear(item.releaseDate),
            )
        }

        if (out.isEmpty()) {
            for (ep in pp.episodes.orEmpty()) {
                val publicUrl = slugToPublicUrl(ep.url?.slug) ?: continue
                val title = ep.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                out += PoseidonCatalogItem(
                    kind = PoseidonItemKind.Episode,
                    title = title,
                    url = publicUrl,
                    posterUrl = resolveImageUrl(ep.image),
                    year = parseYear(ep.releaseDate),
                )
            }
        }

        return PoseidonCatalogPage(
            items = out.distinctBy { it.url },
            totalPages = (pp.pages ?: 1).coerceAtLeast(1),
        )
    }

    fun mapVideosMap(videos: Map<String, List<PoseidonRawVideoEntry>>?): List<PoseidonVideoSource> {
        if (videos.isNullOrEmpty()) return emptyList()
        val results = mutableListOf<PoseidonVideoSource>()
        for ((langKey, entries) in videos) {
            val langLabel = mapLanguageLabel(langKey)
            for (entry in entries) {
                val playerUrl = entry.result?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: continue
                val cyber = entry.cyberlocker?.trim()?.lowercase()?.ifEmpty { null } ?: "server"
                results += PoseidonVideoSource(
                    languageKey = langKey,
                    languageLabel = langLabel,
                    cyberlocker = cyber,
                    playerUrl = playerUrl,
                    quality = entry.quality?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return results
    }

    fun parseMovieDetail(html: String, fallbackUrl: String): PoseidonMovieDetail? {
        val pp = parsePageProps(html) ?: return null
        val movie = pp.thisMovie ?: return null
        val publicUrl = slugToPublicUrl(movie.url?.slug) ?: fallbackUrl
        val title = movie.titles?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: movie.titles?.original?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val recs = (pp.relatedMovies ?: pp.otherMovies).orEmpty().mapNotNull { rel ->
            val relUrl = slugToPublicUrl(rel.url?.slug) ?: return@mapNotNull null
            val relTitle = rel.titles?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = if (relUrl.contains("/serie/", ignoreCase = true)) PoseidonItemKind.Series else PoseidonItemKind.Movie
            PoseidonCatalogItem(
                kind = kind,
                title = relTitle,
                url = relUrl,
                posterUrl = resolveImageUrl(rel.images?.poster ?: rel.images?.backdrop),
                year = parseYear(rel.releaseDate),
            )
        }.distinctBy { it.url }

        val validRate = movie.rate?.average?.takeIf { it > 0.0 && (movie.rate.votes ?: 1) > 0 }
        return PoseidonMovieDetail(
            title = title,
            url = publicUrl,
            posterUrl = resolveImageUrl(movie.images?.poster),
            backgroundPosterUrl = resolveImageUrl(movie.images?.backdrop ?: movie.images?.poster),
            plot = movie.overview?.trim()?.takeIf { it.isNotEmpty() },
            year = parseYear(movie.releaseDate),
            durationMinutes = movie.runtime?.takeIf { it > 0 },
            rating = validRate,
            genres = movie.genres.orEmpty().mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } },
            actors = movie.cast?.acting.orEmpty().mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } },
            recommendations = recs,
            videos = mapVideosMap(movie.videos),
        )
    }

    fun parseSeriesDetail(html: String, fallbackUrl: String): PoseidonSeriesDetail? {
        val pp = parsePageProps(html) ?: return null
        val serie = pp.thisSerie ?: pp.serie ?: return null
        val publicUrl = slugToPublicUrl(serie.url?.slug) ?: extractSeriesBaseUrl(fallbackUrl)
        val title = serie.titles?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: serie.titles?.original?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val episodes = mutableListOf<PoseidonEpisodeEntry>()
        for (seasonObj in serie.seasons.orEmpty()) {
            val epList = seasonObj.episodes.orEmpty()
            if (epList.isEmpty()) continue
            val seasonNum = seasonObj.number ?: 1
            for (ep in epList) {
                val epUrl = slugToPublicUrl(ep.url?.slug) ?: continue
                val epNum = ep.number ?: continue
                val epTitle = ep.title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "$title ${seasonNum}x$epNum"
                episodes += PoseidonEpisodeEntry(
                    title = epTitle,
                    season = seasonNum,
                    episode = epNum,
                    url = epUrl,
                    posterUrl = resolveImageUrl(ep.image ?: serie.images?.backdrop),
                    releaseDate = ep.releaseDate,
                )
            }
        }

        val validRate = serie.rate?.average?.takeIf { it > 0.0 && (serie.rate.votes ?: 1) > 0 }
        return PoseidonSeriesDetail(
            title = title,
            url = publicUrl,
            posterUrl = resolveImageUrl(serie.images?.poster),
            backgroundPosterUrl = resolveImageUrl(serie.images?.backdrop ?: serie.images?.poster),
            plot = serie.overview?.trim()?.takeIf { it.isNotEmpty() },
            year = parseYear(serie.releaseDate),
            rating = validRate,
            genres = serie.genres.orEmpty().mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } },
            actors = serie.cast?.acting.orEmpty().mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } },
            episodes = episodes
                .distinctBy { it.url }
                .sortedWith(compareBy<PoseidonEpisodeEntry> { it.season }.thenBy { it.episode }),
        )
    }

    fun parseWatchPageVideos(html: String): List<PoseidonVideoSource> {
        val pp = parsePageProps(html) ?: return emptyList()
        val fromMovie = mapVideosMap(pp.thisMovie?.videos)
        if (fromMovie.isNotEmpty()) return fromMovie
        return mapVideosMap(pp.episode?.videos)
    }

    fun extractPlayerEmbedUrl(playerHtml: String): String? {
        PLAYER_VAR_URL_REGEX.find(playerHtml)?.groupValues?.getOrNull(1)?.let { found ->
            if (isCandidateEmbedUrl(found)) return found
        }
        PLAYER_REDIRECT_REGEX.find(playerHtml)?.groupValues?.getOrNull(1)?.let { found ->
            if (isCandidateEmbedUrl(found)) return found
        }
        val doc = Jsoup.parse(playerHtml)
        doc.selectFirst("iframe[src]")?.attr("src")?.trim()?.let { src ->
            if (isCandidateEmbedUrl(src)) return src
        }
        for (match in ANY_URL_REGEX.findAll(playerHtml)) {
            val candidate = match.groupValues[1]
            if (isCandidateEmbedUrl(candidate) && !candidate.endsWith(".js", ignoreCase = true)) {
                return candidate
            }
        }
        return null
    }

    private fun isCandidateEmbedUrl(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val lower = url.lowercase()
        return AD_OR_TRACKING_HOSTS.none { lower.contains(it) }
    }
}
