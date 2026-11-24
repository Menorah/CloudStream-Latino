package com.menorah.repelis24shop

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal data class Card(val title: String, val url: String, val poster: String?, val background: String?, val movie: Boolean)
internal data class SeasonLink(val url: String, val number: Int)
internal data class EpisodeItem(val title: String, val url: String, val season: Int, val number: Int, val poster: String?)
internal data class PlayerOption(val url: String, val language: String, val position: Int)

internal data class NuUploadStream(val url: String, val session: String) {
    /** The player appends the session token to the manifest URL, so it is kept separately. */
    fun manifest(): String = if (session.isBlank()) url else "$url?s=$session"
}

/**
 * HTML parsing for repelis24.shop. Everything here is pure so it can be unit tested without
 * network access. The site is a WordPress/DooPlay-derived theme with a custom "plusdes" player
 * markup:
 *
 * - catalog cards: `div.movie-item > a[href]` with `img.poster` and the title in `.item-detail`
 * - movie/series pages: `.backdrop-info` with `h1.title`, `figure.poster img`, `.description p`
 * - series seasons: `p.item-season-title a[href*="/temporada/"]`
 * - season episodes: `div.item-season-episodes > a[href*="/episodio/"]`
 * - players: `li.tab-video-item` (one per audio track) containing `li[data-server]`, where
 *   `data-server` is Base64 of a `nupload.my` wrapper URL
 */
internal object RePelis24ShopMarkup {
    const val BASE = "https://repelis24.shop"
    const val NUPLOAD = "https://nupload.my"
    const val NUPLOAD_REFERER = "$NUPLOAD/"
    private const val MAX_SEASONS = 100
    private val seasonPattern = Regex("/temporada/([^/]+)-([0-9]{1,3})/?$")
    private val episodePattern = Regex("/episodio/([^/]+)-([0-9]{1,3})x([0-9]{1,4})/?$")

    // ---------------------------------------------------------------- URLs

    fun host(url: String, name: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals(name, true) &&
            uri.port in listOf(-1, 443) && uri.userInfo == null
    }.getOrDefault(false)

    fun siteUrl(url: String) = host(url, "repelis24.shop")

    fun nuploadUrl(url: String) = host(url, "nupload.my")

    fun resolve(base: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("data:") || value.startsWith("javascript:")) return null
        val absolute = runCatching { URI(base).resolve(value).toString() }.getOrNull() ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        return absolute.takeIf {
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }

    /** The site serves catalog images with protocol-relative URLs (`//host/file.webp`). */
    fun image(base: String, raw: String?): String? = resolve(base, raw)?.let { url ->
        when {
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
    }

    fun pageUrl(path: String, page: Int): String {
        require(page >= 1)
        val parts = path.split('?', limit = 2)
        val base = parts[0].trimEnd('/')
        val paged = if (page > 1) "$base/page/$page" else base
        return paged + if (parts.size == 2) "?${parts[1]}" else ""
    }

    fun hasNext(doc: Document): Boolean = doc.select("a.next.page-numbers").any { a ->
        siteUrl(a.absUrl("href"))
    }

    fun searchUrl(query: String): String = "/busqueda/${encodeSegment(query.trim())}"

    fun searchPageUrl(query: String, page: Int): String {
        require(page >= 1)
        val base = searchUrl(query)
        return if (page > 1) "$base/page/$page" else base
    }

    /**
     * Percent-encodes one path segment. `URLEncoder` produces form data (`+` for spaces, `%2F`
     * rules are wrong for paths), so everything except RFC 3986 unreserved characters is escaped.
     */
    fun encodeSegment(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && char.code < 128 || char in "-._~") out.append(char)
            else out.append('%').append("%02X".format(byte))
        }
        return out.toString()
    }

    fun decodeSegment(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    fun seasonNumber(url: String): Int? {
        if (!siteUrl(url)) return null
        return seasonPattern.find(runCatching { URI(url).path.orEmpty() }.getOrDefault(""))
            ?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 0..MAX_SEASONS }
    }

    fun seasonIsSeries(url: String, slug: String): Boolean {
        val match = seasonPattern.find(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) ?: return false
        return match.groupValues[1] == slug
    }

    fun seasonUrl(slug: String, number: Int): String {
        require(number in 0..MAX_SEASONS)
        return "$BASE/temporada/$slug-$number/"
    }

    fun episodeKey(url: String): Pair<Int, Int>? {
        if (!siteUrl(url)) return null
        val match = episodePattern.find(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) ?: return null
        val season = match.groupValues[2].toIntOrNull() ?: return null
        val number = match.groupValues[3].toIntOrNull() ?: return null
        return season to number
    }

    // ------------------------------------------------------------- catalog

    /**
     * Cloudflare's interstitial answers with HTTP 200 and no catalog, so it has to be detected
     * from the body instead of the status code.
     */
    fun isChallenge(html: String): Boolean {
        val head = html.take(4000).lowercase()
        return "just a moment" in head && ("cf-browser-verification" in html.lowercase() || "challenge-platform" in html.lowercase())
    }

    fun cards(doc: Element): List<Card> = cards(doc, doc.baseUri())

    fun cards(doc: Element, location: String): List<Card> = doc.select("div.movie-item a[href]").mapNotNull { link ->
        val base = doc.baseUri().ifBlank { location }
        val url = resolve(base, link.attr("href"))?.takeIf(::siteUrl) ?: return@mapNotNull null
        val path = URI(url).path.orEmpty()
        val movie = path.startsWith("/pelicula/")
        if (!movie && !path.startsWith("/serie/")) return@mapNotNull null
        val image = link.selectFirst("img.poster") ?: link.selectFirst("img")
        val title = link.selectFirst(".item-detail p")?.text()?.trim().orEmpty()
            .ifBlank { image?.attr("alt")?.trim().orEmpty() }
            .ifBlank { link.attr("title").trim() }
        if (title.isBlank()) return@mapNotNull null
        Card(
            title = title,
            url = url,
            poster = image(image?.baseUri() ?: base, image?.attr("src") ?: image?.attr("data-src")),
            background = link.selectFirst(".card-hover-backdrop")?.attr("data-backdrop")?.let { image(base, it) },
            movie = movie
        )
    }.distinctBy { it.url }

    // -------------------------------------------------------------- details

    fun isMovie(doc: Document): Boolean = URI(doc.location()).path.orEmpty().startsWith("/pelicula/")

    fun title(doc: Document): String? {
        doc.selectFirst(".backdrop-info h1.title span[itemprop=name]")?.text()?.trim()
            ?.removePrefix("Ver Película ")?.removePrefix("Ver Serie ")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return doc.selectFirst("h1.title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun poster(doc: Document): String? = image(
        doc.location(),
        doc.selectFirst(".backdrop-info figure.poster img[itemprop=image]")?.attr("src")
            ?: doc.selectFirst("figure.poster img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
    )

    fun plot(doc: Document): String? = doc.selectFirst(".description p")?.text()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    fun year(doc: Document): Int? = doc.selectFirst("[itemprop=datePublished]")?.text()?.trim()
        ?.let { Regex("[0-9]{4}").find(it)?.value }?.toIntOrNull()
        ?: doc.selectFirst(".card-hover-year")?.text()?.trim()?.toIntOrNull()

    fun genres(doc: Document): List<String> = doc.select(".info-list li")
        .firstOrNull { it.text().contains("Géneros", ignoreCase = true) || it.text().contains("Generos", ignoreCase = true) }
        ?.select("a span[itemprop=genre]")?.map { it.text().trim().trimEnd(',') }
        ?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** Actors are plain `a[href=/buscar/<name>]` links in the same info list as the genres. */
    fun actors(doc: Document): List<String> = doc.select(".info-list li")
        .firstOrNull { it.selectFirst("strong")?.text()?.contains("Actores", ignoreCase = true) == true }
        ?.select("a")?.mapNotNull { anchor ->
            val name = anchor.text().trim().trimEnd(',').trim()
            if (name.isEmpty()) null else name
        }?.distinct().orEmpty()

    fun durationMinutes(doc: Document): Int? {
        val text = doc.selectFirst("[itemprop=duration]")?.text()?.trim() ?: return null
        val hours = Regex("([0-9]{1,2})\\s*h", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = Regex("([0-9]{1,3})\\s*min", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        val total = (hours ?: 0) * 60 + (minutes ?: 0)
        return total.takeIf { it > 0 }
    }

    fun recommendations(doc: Document): List<Card> {
        val section = doc.select("section.section-separator")
            .firstOrNull { it.selectFirst("dt.section-title")?.text()?.contains("Sugerencias", ignoreCase = true) == true }
            ?: return emptyList()
        return cards(section)
    }

    // ---------------------------------------------------------------- series

    /** The series page links to each season as `/temporada/<slug>-<number>/`. */
    fun seasons(doc: Document): List<SeasonLink> {
        val slug = seriesSlug(doc.location())
        return doc.select("p.item-season-title a[href*='/temporada/']").mapNotNull { anchor ->
            val url = resolve(doc.location(), anchor.attr("href"))?.takeIf(::siteUrl) ?: return@mapNotNull null
            val number = seasonNumber(url) ?: return@mapNotNull null
            if (slug != null && !seasonIsSeries(url, slug)) return@mapNotNull null
            SeasonLink(url.trimEnd('/') + "/", number)
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    fun seriesSlug(url: String): String? = Regex("/serie/([^/]+)/?").find(
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")
    )?.groupValues?.get(1)

    fun episodes(doc: Document, season: Int): List<EpisodeItem> =
        doc.select("div.item-season-episodes a[href*='/episodio/']").mapNotNull { anchor ->
            val url = resolve(doc.location(), anchor.attr("href"))?.takeIf(::siteUrl) ?: return@mapNotNull null
            val key = episodeKey(url) ?: return@mapNotNull null
            if (key.first != season) return@mapNotNull null
            val img = anchor.selectFirst("img")
            val name = anchor.selectFirst("span[itemprop=name]")?.text()?.trim().orEmpty()
            EpisodeItem(
                title = name.ifBlank { "Episodio ${key.second}" },
                url = url,
                season = key.first,
                number = key.second,
                poster = image(anchor.baseUri().ifBlank { doc.location() }, img?.attr("src") ?: img?.attr("data-src"))
            )
        }.distinctBy { it.season to it.number }.sortedBy { it.number }

    // --------------------------------------------------------------- players

    /** Reads the audio label of a `li.tab-video-item` block ("Latino", "Español Castellano", ...). */
    fun language(tab: Element): String {
        val name = tab.selectFirst(".tab-item-name")?.text()?.trim().orEmpty()
        val cleaned = name.split("Calidad").first().replace(Regex("\\s+"), " ").trim()
        val lowered = cleaned.lowercase()
        return when {
            cleaned.isEmpty() -> "Idioma no indicado"
            "latino" in lowered -> "Latino"
            "castellano" in lowered || lowered == "español" || lowered == "espanol" -> "Español Castellano"
            "subtitul" in lowered -> "Subtitulado"
            "ingl" in lowered || "original" in lowered -> "Inglés"
            else -> cleaned.take(40)
        }
    }

    /**
     * Player options of a movie or episode page. Each tab holds one audio track and each
     * `li[data-server]` is Base64 of the wrapper URL the site opens in its own player.
     */
    fun players(doc: Document): List<PlayerOption> {
        val options = doc.select("li.tab-video-item").flatMap { tab ->
            val language = language(tab)
            tab.select("li[data-server]").mapNotNull { item ->
                decodeServer(item.attr("data-server"))?.let { url ->
                    PlayerOption(url, language, item.elementSiblingIndex())
                }
            }
        }
        if (options.isNotEmpty()) return options.distinctBy { it.url }

        // Fallback: pages without the tab markup keep the raw encoded payload.
        return doc.select("li[data-server]").mapNotNull { item ->
            decodeServer(item.attr("data-server"))?.let { PlayerOption(it, "Idioma no indicado", item.elementSiblingIndex()) }
        }.distinctBy { it.url }
    }

    fun decodeServer(raw: String): String? {
        val value = raw.trim()
        if (value.length < 8) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(Base64.getMimeDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
            ?: return null
        val trimmed = decoded.trim()
        return trimmed.takeIf { nuploadUrl(it) || siteUrl(it) }
    }

    /**
     * Resolves a `nupload.my` player URL: the `/iframe/?url=` wrapper for external hosts and
     * `/watch/<uid>` pages, which decode their own stream base through the `?s=` session token.
     */
    fun nuploadTarget(url: String): String? {
        if (!nuploadUrl(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.path.orEmpty().startsWith("/iframe")) {
            val target = uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("url=") }
                ?.substringAfter('=')
                ?.let { decodeSegment(it) }
                ?: return null
            val host = runCatching { URI(target).host }.getOrNull() ?: return null
            if (host.equals("nupload.my", true) || host.equals("nupload.me", true)) return null
            return target.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        // Only `/watch/<uid>` pages hold a decodable player; an empty uid can never play.
        val path = uri.path.orEmpty()
        if (!path.startsWith("/watch/") || path.removePrefix("/watch/").trim('/').isEmpty()) return null
        return url
    }

    /**
     * Decodes a `/watch/<uid>` page. The page concatenates one obfuscated Base64 array into a
     * manifest URL: every item is Base64 of decimal digits and subtracting a per-page offset
     * yields one character. The player then appends a `?s=` session token.
     */
    fun nuploadStream(html: String, pageUrl: String): NuUploadStream? {
        if (!nuploadUrl(pageUrl)) return null
        val offset = Regex("fromCharCode\\(parseInt\\(atob\\(value\\)\\.replace\\(/\\\\D/g,''?\\)\\)\\s*-\\s*([0-9]+)")
            .find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val chars = Regex("var\\s+[A-Za-z0-9_$]+\\s*=\\s*\\[([^\\]]{16,})]").findAll(html)
            .map { array -> Regex("\"([A-Za-z0-9+/=]{8,})\"").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList() }
            .map { values -> values.map { decodeChar(it, offset) } }
            .firstOrNull { decoded -> decoded.isNotEmpty() && decoded.all { it != '\u0000' } }
            ?.joinToString("")
            ?.takeIf { it.startsWith("http") }
            ?: return null
        val session = Regex("var\\s+[A-Za-z0-9_$]+\\s*=\\s*\"([A-Za-z0-9]{24,})\"")
            .findAll(html).map { it.groupValues[1] }
            .firstOrNull { token -> token.any { it.isDigit() } && token.any { it.isLetter() } }
            ?: return null
        val absolute = resolve("$NUPLOAD/", chars) ?: return null
        if (URI(absolute).host.isNullOrBlank() || !absolute.startsWith("https://")) return null
        return NuUploadStream(absolute, session)
    }

    private fun decodeChar(value: String, offset: Long): Char {
        val digits = runCatching { String(Base64.getDecoder().decode(value), Charsets.ISO_8859_1) }
            .getOrNull()?.filter { it.isDigit() }.orEmpty()
        val code = digits.toLongOrNull()?.minus(offset) ?: return '\u0000'
        return if (code in 32..0x10FFFF) code.toInt().toChar() else '\u0000'
    }
}
