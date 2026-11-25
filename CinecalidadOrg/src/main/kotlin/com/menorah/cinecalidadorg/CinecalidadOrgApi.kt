package com.menorah.cinecalidadorg

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

internal data class TaxEntry(
    val id: Int,
    val name: String,
    val slug: String,
    val tax: String,
)

internal data class TaxCatalog(
    val genresBySlug: Map<String, TaxEntry>,
    val genresById: Map<Int, TaxEntry>,
    val langsBySlug: Map<String, TaxEntry>,
    val yearsBySlug: Map<String, TaxEntry>,
)

internal data class OrgCard(
    val id: Long,
    val title: String,
    val slug: String,
    val postType: String,
    val url: String,
    val poster: String?,
    val backdrop: String?,
    val year: Int?,
    val rating: Double?,
)

internal data class OrgListing(
    val items: List<OrgCard>,
    val hasNext: Boolean,
)

internal data class OrgDetail(
    val id: Long,
    val title: String,
    val slug: String,
    val postType: String,
    val url: String,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val rating: Double?,
    val trailerUrl: String?,
    val genreIds: List<Int>,
)

internal data class OrgEpisode(
    val id: Long,
    val title: String,
    val slug: String,
    val season: Int,
    val number: Int,
    val poster: String?,
    val overview: String?,
    val durationMinutes: Int?,
    val dataUrl: String,
)

internal data class OrgEpisodesPage(
    val episodes: List<OrgEpisode>,
    val seasons: List<Int>,
    val hasNext: Boolean,
    val lastPage: Int,
)

internal data class OrgActor(
    val name: String,
    val imageUrl: String?,
    val role: String?,
)

internal data class OrgEmbed(
    val url: String,
    val server: String,
    val languageTag: String,
    val qualityTag: String?,
)

internal object CinecalidadOrgApi {
    const val BASE = "https://cinecalidad.org"
    const val API_BASE = "$BASE/wp-api/v1"
    const val UPLOADS_BASE = "$BASE/wp-content/uploads"
    const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
    const val PAGE_SIZE = 24
    const val EPISODE_PAGE_SIZE = 100

    private val mapper = jacksonObjectMapper()

    private val postTypeToSegment = mapOf(
        "movies" to "peliculas",
        "tvshows" to "series",
        "animes" to "animes",
        "novels" to "novelas",
        "wwe" to "wwe",
        "episodes" to "episodio",
    )

    private val segmentToPostType = postTypeToSegment.entries.associate { (k, v) -> v to k }

    private val defaultGenres = listOf(
        TaxEntry(26, "Acción", "accion", "genres"),
        TaxEntry(91, "Animación", "animacion", "genres"),
        TaxEntry(25, "Aventura", "aventura", "genres"),
        TaxEntry(376, "Bélica", "belica", "genres"),
        TaxEntry(27, "Ciencia ficción", "ciencia-ficcion", "genres"),
        TaxEntry(122, "Comedia", "comedia", "genres"),
        TaxEntry(337, "Crimen", "crimen", "genres"),
        TaxEntry(9262, "Documental", "documental", "genres"),
        TaxEntry(123, "Drama", "drama", "genres"),
        TaxEntry(92, "Familia", "familia", "genres"),
        TaxEntry(310, "Fantasía", "fantasia", "genres"),
        TaxEntry(799, "Historia", "historia", "genres"),
        TaxEntry(221, "Misterio", "misterio", "genres"),
        TaxEntry(611, "Música", "musica", "genres"),
        TaxEntry(8534, "Película de TV", "pelicula-de-tv", "genres"),
        TaxEntry(124, "Romance", "romance", "genres"),
        TaxEntry(492, "Suspense", "suspense", "genres"),
        TaxEntry(1702, "Terror", "terror", "genres"),
        TaxEntry(432, "Western", "western", "genres"),
        TaxEntry(1203, "Action & Adventure", "action-adventure", "genres"),
        TaxEntry(1253, "Kids", "kids", "genres"),
        TaxEntry(1204, "Sci-Fi & Fantasy", "sci-fi-fantasy", "genres"),
        TaxEntry(4195, "War & Politics", "war-politics", "genres"),
        TaxEntry(9279, "Reality", "reality", "genres"),
        TaxEntry(46218, "Soap", "soap", "genres"),
    )

    private val defaultLangs = listOf(
        TaxEntry(89581, "Latino", "latino", "lang"),
        TaxEntry(89583, "Castellano", "castellano", "lang"),
        TaxEntry(89605, "Subtitulado", "subtitulado", "lang"),
        TaxEntry(89580, "Inglés", "ingles", "lang"),
        TaxEntry(89589, "Japonés", "japones", "lang"),
        TaxEntry(89621, "Coreano", "coreano", "lang"),
    )

    private val defaultYears = listOf(
        TaxEntry(19805, "2026", "2026", "years"),
        TaxEntry(25705, "2025", "2025", "years"),
        TaxEntry(9030, "2024", "2024", "years"),
        TaxEntry(12403, "2023", "2023", "years"),
        TaxEntry(9294, "2022", "2022", "years"),
        TaxEntry(9278, "2021", "2021", "years"),
        TaxEntry(7585, "2020", "2020", "years"),
        TaxEntry(11601, "2019", "2019", "years"),
        TaxEntry(9423, "2018", "2018", "years"),
        TaxEntry(11410, "2017", "2017", "years"),
        TaxEntry(10451, "2016", "2016", "years"),
        TaxEntry(8698, "2015", "2015", "years"),
    )

    val DEFAULT_TAXONOMIES = TaxCatalog(
        genresBySlug = defaultGenres.associateBy { it.slug },
        genresById = defaultGenres.associateBy { it.id },
        langsBySlug = defaultLangs.associateBy { it.slug },
        yearsBySlug = defaultYears.associateBy { it.slug },
    )

    fun siteUrl(url: String): Boolean = runCatching {
        URI(url).let {
            it.scheme.equals("https", true) &&
                (it.host.equals("cinecalidad.org", true) || it.host.equals("www.cinecalidad.org", true)) &&
                it.port in listOf(-1, 443) &&
                it.userInfo == null
        }
    }.getOrDefault(false)

    fun safeHost(url: String, host: String): Boolean = runCatching {
        URI(url).let {
            it.scheme.equals("https", true) &&
                it.host.equals(host, true) &&
                it.port in listOf(-1, 443) &&
                it.userInfo == null
        }
    }.getOrDefault(false)

    fun normalizeHttpsUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.startsWith("data:", ignoreCase = true)) return null
        val normalized = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> return null
        }
        return runCatching {
            val uri = URI(normalized)
            if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) normalized else null
        }.getOrNull()
    }

    fun resolveUploadOrTmdbImage(raw: String?, preferUploads: Boolean, tmdbSize: String): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value.startsWith("data:", ignoreCase = true)) return null
        normalizeHttpsUrl(value)?.let { return it }
        if (!value.startsWith("/")) return null
        return when {
            value.startsWith("/wp-content/") -> "$BASE$value"
            preferUploads ||
                value.startsWith("/thumbs/") ||
                value.startsWith("/backdrops/") ||
                value.startsWith("/logos/") -> "$UPLOADS_BASE$value"
            else -> "$TMDB_IMAGE_BASE/$tmdbSize$value"
        }
    }

    fun isMovieType(postType: String): Boolean = postType.equals("movies", ignoreCase = true)

    fun isAnimeType(postType: String): Boolean = postType.equals("animes", ignoreCase = true)

    fun postUrl(postType: String, slug: String): String? {
        val cleanSlug = slug.trim().trim('/')
        if (cleanSlug.isEmpty() || '/' in cleanSlug || '?' in cleanSlug || '#' in cleanSlug) return null
        val segment = postTypeToSegment[postType.lowercase()] ?: return null
        return "$BASE/$segment/$cleanSlug/"
    }

    fun parseContentUrl(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!siteUrl(url)) return null
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val postType = segmentToPostType[parts[0].lowercase()] ?: return null
        val slug = parts[1].trim()
        if (slug.isEmpty()) return null
        return postType to slug
    }

    fun parsePlayerPostId(data: String): Long? {
        val uri = runCatching { URI(data) }.getOrNull() ?: return null
        if (!siteUrl(data) || !uri.path.orEmpty().endsWith("/player")) return null
        val query = uri.rawQuery.orEmpty()
        val match = Regex("(?:^|&)postId=([0-9]+)(?:&|$)").find(query) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }

    fun buildPlayerUrl(postId: Long): String = "$API_BASE/player?postId=$postId&demo=0"

    fun buildSearchUrl(query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return "$API_BASE/search?postType=any&q=$encoded&page=$page&postsPerPage=$PAGE_SIZE"
    }

    fun buildFilterListingUrl(tax: String, termId: Int, page: Int = 1): String {
        val filterJson = "{\"$tax\":[$termId]}"
        val encodedFilter = URLEncoder.encode(filterJson, StandardCharsets.UTF_8.name())
        return "$API_BASE/listing/any?page=$page&orderBy=latest&order=desc&postType=any&postsPerPage=$PAGE_SIZE&filter=$encodedFilter"
    }

    fun buildListingUrl(postType: String, page: Int = 1): String =
        "$API_BASE/listing/$postType?page=$page&orderBy=latest&order=desc&postType=$postType&postsPerPage=$PAGE_SIZE"

    fun buildTopsUrl(postType: String, page: Int = 1, range: String = "week"): String =
        "$API_BASE/tops?page=$page&postType=$postType&postsPerPage=$PAGE_SIZE&range=$range"

    fun buildLatestUrl(): String =
        "$API_BASE/listing/latest?postTypes=movies,tvshows,animes,novels,wwe&postsPerPage=$PAGE_SIZE"

    fun buildSingleUrl(postType: String, slug: String): String {
        val encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8.name())
        return "$API_BASE/single/$postType?slug=$encoded&postType=$postType"
    }

    fun buildEpisodesListUrl(showId: Long, season: Int, page: Int = 1): String =
        "$API_BASE/single/episodes/list?_id=$showId&season=$season&page=$page&postsPerPage=$EPISODE_PAGE_SIZE"

    fun buildRelatedUrl(postId: Long): String =
        "$API_BASE/single/related?postId=$postId&page=1&tab=connections&postsPerPage=12"

    fun buildCastUrl(postType: String, postId: Long): String =
        "$API_BASE/cast/$postType/$postId"

    fun buildTaxMetadataUrl(tax: String, slug: String): String =
        "$API_BASE/listing/tax/${URLEncoder.encode(tax, StandardCharsets.UTF_8.name())}/${URLEncoder.encode(slug, StandardCharsets.UTF_8.name())}"

    fun yearQuery(query: String): String? {
        val trimmed = query.trim()
        if (!trimmed.matches(Regex("[0-9]{4}"))) return null
        val y = trimmed.toIntOrNull() ?: return null
        return if (y in 1888..2100) trimmed else null
    }

    fun parseSiteConfigTaxonomies(html: String): TaxCatalog {
        val genres = parseTaxObject(html, "genres", "genres").ifEmpty { defaultGenres }
        val langs = parseTaxObject(html, "langs", "lang").ifEmpty { defaultLangs }
        val years = parseTaxObject(html, "years", "years").ifEmpty { defaultYears }
        val mergedGenres = (defaultGenres + genres).associateBy { it.slug }
        val mergedLangs = (defaultLangs + langs).associateBy { it.slug }
        val mergedYears = (defaultYears + years).associateBy { it.slug }
        return TaxCatalog(
            genresBySlug = mergedGenres,
            genresById = mergedGenres.values.associateBy { it.id },
            langsBySlug = mergedLangs,
            yearsBySlug = mergedYears,
        )
    }

    private fun parseTaxObject(html: String, key: String, fallbackTax: String): List<TaxEntry> {
        val jsonBlock = extractBalancedJsonObject(html, key) ?: return emptyList()
        val node = runCatching { mapper.readTree(jsonBlock) }.getOrNull() ?: return emptyList()
        if (!node.isObject) return emptyList()
        val result = mutableListOf<TaxEntry>()
        val fields = node.fields()
        while (fields.hasNext()) {
            val (idStr, value) = fields.next()
            val id = idStr.toIntOrNull() ?: continue
            val name = decodeText(value.path("name").asText(""))
            val slug = value.path("slug").asText("").trim().lowercase()
            val tax = value.path("tax").asText(fallbackTax).trim().ifBlank { fallbackTax }
            if (name.isNotBlank() && slug.isNotBlank()) {
                result += TaxEntry(id, name, slug, tax)
            }
        }
        return result
    }

    private fun extractBalancedJsonObject(source: String, key: String): String? {
        val match = Regex("""\b$key\s*:\s*\{""").find(source) ?: return null
        val start = match.range.last
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until source.length) {
            val ch = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    fun parseTaxTermId(json: String): Int? {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val data = root.path("data")
        val id = data.path("term_id").asInt(0)
        return id.takeIf { it > 0 }
    }

    fun parseListing(json: String): OrgListing {
        val root = runCatching { mapper.readTree(json) }.getOrNull()
            ?: return OrgListing(emptyList(), hasNext = false)
        val data = root.path("data")
        val postsNode = when {
            data.path("posts").isArray -> data.path("posts")
            data.isArray -> data
            else -> return OrgListing(emptyList(), hasNext = false)
        }
        val items = mutableListOf<OrgCard>()
        for (post in postsNode) {
            parseCard(post)?.let { items += it }
        }
        val pag = data.path("pagination")
        val hasNext = if (pag.isObject) {
            val current = pag.path("current_page").asInt(0)
            val last = pag.path("last_page").asInt(0)
            val nextUrl = pag.path("next_page_url").takeIf { !it.isNull }?.asText("").orEmpty()
            (current > 0 && last > current) || nextUrl.isNotBlank()
        } else {
            false
        }
        return OrgListing(items.distinctBy { it.url }, hasNext)
    }

    private fun parseCard(node: JsonNode): OrgCard? {
        val id = node.path("_id").asLong(0L)
        val title = decodeText(node.path("title").asText(""))
        val slug = node.path("slug").asText("").trim()
        val type = node.path("type").asText("").trim().lowercase()
        val url = postUrl(type, slug) ?: return null
        if (title.isBlank()) return null

        val images = node.path("images")
        val poster = resolveUploadOrTmdbImage(images.path("poster").asTextOrNull(), true, "w342")
            ?: resolveUploadOrTmdbImage(node.path("poster_path").asTextOrNull(), false, "w342")
        val backdrop = resolveUploadOrTmdbImage(images.path("backdrop").asTextOrNull(), true, "w780")
            ?: resolveUploadOrTmdbImage(node.path("backdrop_path").asTextOrNull(), false, "w780")

        return OrgCard(
            id = id,
            title = title,
            slug = slug,
            postType = type,
            url = url,
            poster = poster,
            backdrop = backdrop,
            year = parseYear(node.path("release_date").asTextOrNull(), title),
            rating = parseRating(node.path("vote_average")),
        )
    }

    fun parseSingle(json: String, expectedType: String? = null): OrgDetail? {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val data = root.path("data")
        if (data.isMissingNode || data.isNull) return null
        val postNode = when {
            data.path("_id").asLong(0L) > 0L -> data
            data.path("serie").path("_id").asLong(0L) > 0L -> data.path("serie")
            data.path("post").path("_id").asLong(0L) > 0L -> data.path("post")
            else -> return null
        }

        val id = postNode.path("_id").asLong(0L)
        val title = decodeText(postNode.path("title").asText(""))
        val slug = postNode.path("slug").asText("").trim()
        val type = postNode.path("type").asText(expectedType.orEmpty()).trim().lowercase()
        val url = postUrl(type, slug) ?: return null
        if (id <= 0L || title.isBlank()) return null

        val images = postNode.path("images")
        val poster = resolveUploadOrTmdbImage(images.path("poster").asTextOrNull(), true, "w342")
            ?: resolveUploadOrTmdbImage(postNode.path("poster_path").asTextOrNull(), false, "w342")
        val backdrop = resolveUploadOrTmdbImage(images.path("backdrop").asTextOrNull(), true, "w780")
            ?: resolveUploadOrTmdbImage(postNode.path("backdrop_path").asTextOrNull(), false, "w780")

        val genreIds = postNode.path("genres")
            .takeIf { it.isArray }
            ?.mapNotNull { it.asInt(0).takeIf { g -> g > 0 } }
            .orEmpty()

        return OrgDetail(
            id = id,
            title = title,
            slug = slug,
            postType = type,
            url = url,
            overview = decodeText(postNode.path("overview").asText("")).takeIf { it.isNotBlank() },
            poster = poster,
            backdrop = backdrop,
            year = parseYear(postNode.path("release_date").asTextOrNull(), title),
            durationMinutes = parseDurationMinutes(postNode.path("runtime")),
            rating = parseRating(postNode.path("vote_average")),
            trailerUrl = normalizeTrailer(postNode.path("trailer").asTextOrNull()),
            genreIds = genreIds,
        )
    }

    fun parseEpisodeSingleIds(json: String): Pair<Long?, OrgDetail?> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null to null
        val data = root.path("data")
        val epId = data.path("episode").path("_id").asLong(0L).takeIf { it > 0L }
        val serieNode = data.path("serie")
        val serieDetail = if (serieNode.path("_id").asLong(0L) > 0L) {
            val wrapped = mapper.createObjectNode().apply { set<JsonNode>("data", serieNode) }
            parseSingle(mapper.writeValueAsString(wrapped))
        } else {
            null
        }
        return epId to serieDetail
    }

    fun parseEpisodesPage(json: String): OrgEpisodesPage {
        val root = runCatching { mapper.readTree(json) }.getOrNull()
            ?: return OrgEpisodesPage(emptyList(), emptyList(), hasNext = false, lastPage = 1)
        val data = root.path("data")

        val seasons = data.path("seasons")
            .takeIf { it.isArray }
            ?.mapNotNull { node ->
                val text = node.asText("").trim()
                text.toIntOrNull()?.takeIf { it >= 0 }
            }
            ?.distinct()
            ?.sorted()
            .orEmpty()

        val episodes = mutableListOf<OrgEpisode>()
        val postsNode = data.path("posts")
        if (postsNode.isArray) {
            for (ep in postsNode) {
                val id = ep.path("_id").asLong(0L)
                val slug = ep.path("slug").asText("").trim()
                val seasonNum = ep.path("season_number").asInt(-1)
                val episodeNum = ep.path("episode_number").asInt(0)
                if (id <= 0L || seasonNum < 0 || episodeNum <= 0) continue

                val rawTitle = decodeText(ep.path("title").asText(""))
                val title = rawTitle.ifBlank { "Episodio $episodeNum" }
                val still = resolveUploadOrTmdbImage(ep.path("still_path").asTextOrNull(), false, "w342")
                val overview = decodeText(ep.path("overview").asText("")).takeIf { it.isNotBlank() }
                val runtime = parseDurationMinutes(ep.path("runtime"))
                episodes += OrgEpisode(
                    id = id,
                    title = title,
                    slug = slug,
                    season = seasonNum,
                    number = episodeNum,
                    poster = still,
                    overview = overview,
                    durationMinutes = runtime,
                    dataUrl = buildPlayerUrl(id),
                )
            }
        }

        val pag = data.path("pagination")
        val current = if (pag.isObject) pag.path("current_page").asInt(1) else 1
        val last = if (pag.isObject) pag.path("last_page").asInt(1).coerceAtLeast(1) else 1
        val hasNext = pag.isObject && (current < last || !pag.path("next_page_url").isNull && pag.path("next_page_url").asText("").isNotBlank())

        return OrgEpisodesPage(
            episodes = episodes,
            seasons = seasons,
            hasNext = hasNext,
            lastPage = last,
        )
    }

    fun parseCast(json: String): List<OrgActor> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        val data = root.path("data")
        if (!data.isArray) return emptyList()
        val result = mutableListOf<OrgActor>()
        for (entry in data) {
            val name = decodeText(entry.path("term_name").asText(""))
            if (name.isBlank()) continue
            val meta = entry.path("meta")
            val imageUrl = resolveUploadOrTmdbImage(meta.path("profile_path").asTextOrNull(), false, "w185")
            val role = decodeText(meta.path("character").asText("")).takeIf { it.isNotBlank() }
            result += OrgActor(name = name, imageUrl = imageUrl, role = role)
        }
        return result.distinctBy { it.name to it.role }
    }

    fun parsePlayerEmbeds(json: String): List<OrgEmbed> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        val embedsNode = root.path("data").path("embeds")
        if (!embedsNode.isArray) return emptyList()

        val result = mutableListOf<OrgEmbed>()
        for (item in embedsNode) {
            val url = normalizeHttpsUrl(item.path("url").asTextOrNull()) ?: continue
            val server = decodeText(item.path("server").asText(""))
            val rawLang = decodeText(item.path("lang").asText(""))
            val rawQuality = decodeText(item.path("quality").asText("")).takeIf { it.isNotBlank() }
            result += OrgEmbed(
                url = url,
                server = server,
                languageTag = formatLanguageTag(rawLang),
                qualityTag = rawQuality,
            )
        }
        return result.distinctBy { it.url }
    }

    fun unwrapWrapperIframe(doc: Document): String? =
        doc.select("iframe[src]")
            .asSequence()
            .mapNotNull { normalizeHttpsUrl(it.absUrl("src").ifBlank { it.attr("src") }) }
            .firstOrNull { !safeHost(it, "videoapp.zip") && !siteUrl(it) }

    fun formatLanguageTag(rawLang: String): String {
        val low = rawLang.lowercase().trim()
        return when {
            "latino" in low -> "Latino"
            "castellano" in low || "español" in low || "espanol" in low -> "Castellano"
            "subtitul" in low -> "Subtitulado"
            "ingles" in low || "inglés" in low || "english" in low -> "Inglés"
            rawLang.isNotBlank() -> rawLang.replaceFirstChar { it.uppercase() }
            else -> "Idioma no indicado"
        }
    }

    fun formatSourceLabel(embed: OrgEmbed, extractorName: String): String {
        val tag = if (!embed.qualityTag.isNullOrBlank()) {
            "${embed.languageTag} · ${embed.qualityTag}"
        } else {
            embed.languageTag
        }
        return "[$tag] $extractorName"
    }

    private fun normalizeTrailer(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        normalizeHttpsUrl(trimmed)?.let { return it }
        if (trimmed.matches(Regex("[A-Za-z0-9_-]{6,32}"))) {
            return "https://www.youtube.com/watch?v=$trimmed"
        }
        return null
    }

    private fun parseYear(releaseDate: String?, title: String): Int? {
        val fromDate = releaseDate?.trim()?.let {
            Regex("^([0-9]{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (fromDate != null && fromDate in 1888..2100) return fromDate
        val fromTitle = Regex("\\(([0-9]{4})\\)\\s*$").find(title.trim())?.groupValues?.get(1)?.toIntOrNull()
        return fromTitle?.takeIf { it in 1888..2100 }
    }

    private fun parseDurationMinutes(node: JsonNode): Int? {
        if (node.isMissingNode || node.isNull) return null
        val value = when {
            node.isNumber -> node.asDouble()
            else -> node.asText("").trim().toDoubleOrNull() ?: return null
        }
        val rounded = value.roundToInt()
        return rounded.takeIf { it > 0 }
    }

    private fun parseRating(node: JsonNode): Double? {
        if (node.isMissingNode || node.isNull) return null
        val value = when {
            node.isNumber -> node.asDouble()
            else -> node.asText("").trim().toDoubleOrNull() ?: return null
        }
        return value.takeIf { it in 0.1..10.0 }
    }

    private fun decodeText(raw: String): String =
        Parser.unescapeEntities(raw, false).trim()

    private fun JsonNode.asTextOrNull(): String? =
        if (isMissingNode || isNull) null else asText("").trim().takeIf { it.isNotBlank() }
}
