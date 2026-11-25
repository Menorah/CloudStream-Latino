package com.menorah.pelicinehd

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Ficha de una película o serie extraída de una página de detalle. */
internal data class Details(
    val title: String,
    val poster: String?,
    val plot: String?,
    val year: Int?,
    val durationMinutes: Int?,
    val score: Double?,
    val genres: List<String>,
    val actors: List<String>,
)

/** Tarjeta de catálogo: lo mínimo que CloudStream necesita para pintar un resultado. */
internal data class Card(
    val title: String,
    val url: String,
    val poster: String?,
    val movie: Boolean,
    val year: Int?,
)

/** Un episodio del listado de una temporada. */
internal data class EpisodeItem(
    val name: String,
    val url: String,
    val season: Int,
    val episode: Int,
    val poster: String?,
)

/** Una opción de reproducción del reproductor de la ficha (iframe "trembed"). */
internal data class Player(
    val option: Int,
    val url: String,
    val server: String,
)

/**
 * Todo el conocimiento del HTML de pelicinehd.com vive aquí, sin red ni estado, para poder
 * probarlo con las páginas reales guardadas en `src/test/resources`.
 */
internal object PeliCineHDMarkup {
    const val BASE = "https://pelicinehd.com"
    const val AJAX = "$BASE/wp-admin/admin-ajax.php"

    /** Solo se aceptan enlaces https del propio dominio, sin credenciales ni puertos alternos. */
    fun siteUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (uri.host == "pelicinehd.com" || uri.host == "www.pelicinehd.com")
    }.getOrDefault(false)

    /** Normaliza una URL de imagen: solo https y sin espacios sueltos. */
    private fun https(url: String?): String? =
        url?.trim()?.replace(" ", "%20")?.takeIf { it.startsWith("https://") && !it.contains(' ') }

    private fun path(url: String): String = runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    fun isMoviePath(url: String): Boolean = path(url).startsWith("/movies/")

    fun isSeriesPath(url: String): Boolean = path(url).startsWith("/series/")

    // ---------------------------------------------------------------- catálogo

    /** Tarjetas dentro de un contenedor; se descartan las que no apunten a una ficha del sitio. */
    fun cards(root: Element): List<Card> = root.select("article.post a.lnk-blk[href]").mapNotNull { link ->
        val url = link.absUrl("href")
        if (!siteUrl(url) || !(isMoviePath(url) || isSeriesPath(url))) return@mapNotNull null
        val article = link.closest("article") ?: return@mapNotNull null
        val title = article.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val poster = https(article.selectFirst("div.post-thumbnail img")?.absUrl("src"))
        val year = article.selectFirst("span.year")?.text()?.let(::yearOf)
        Card(title, url, poster, isMoviePath(url), year)
    }.distinctBy { it.url }

    /** En la portada cada bloque `<section>` con `h3.section-title` es un carrusel. */
    fun sections(doc: Document): List<Pair<String, List<Card>>> {
        val main = doc.selectFirst("main.main-site") ?: return emptyList()
        return main.select("section.section").mapNotNull { section ->
            val name = section.selectFirst("h3.section-title, h1.section-title")?.text()?.trim()
            if (name.isNullOrBlank()) return@mapNotNull null
            val items = cards(section)
            if (items.isEmpty()) return@mapNotNull null
            name to items
        }.distinctBy { it.first }
    }

    /** Las tarjetas del listado principal; si no hay `<main>` se usa el documento completo. */
    fun catalogue(doc: Document): List<Card> {
        val main = doc.selectFirst("main.main-site")
        val items = if (main != null) cards(main) else emptyList()
        return items.ifEmpty { cards(doc) }
    }

    /** ¿Existe la página siguiente? Se busca el enlace `/page/<siguiente>/` del paginador. */
    fun hasNext(doc: Document, page: Int): Boolean {
        val next = "/page/${page + 1}/"
        return doc.select("nav.pagination a[href], a.page-link[href]").any { a ->
            val url = a.absUrl("href")
            siteUrl(url) && path(url).endsWith(next)
        }
    }

    fun totalPages(doc: Document): Int? = doc.select("nav.pagination a.page-link[href]")
        .mapNotNull { a -> Regex("""/page/(\d+)/""").find(a.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()

    // ---------------------------------------------------------------- detalle

    fun poster(doc: Document): String? = https(
        doc.selectFirst("article.post.single div.post-thumbnail img")?.absUrl("src")
            ?: doc.selectFirst("meta[property=og:image]")?.absUrl("content")
    )

    fun title(doc: Document): String? =
        doc.selectFirst("article.post.single h1.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }

    fun plot(doc: Document): String? = doc.selectFirst("article.post.single div.description")
        ?.text()?.trim()?.takeIf { it.isNotBlank() }

    fun yearOf(text: String): Int? = Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()

    fun year(doc: Document): Int? = doc.selectFirst("article.post.single span.year")?.text()?.let(::yearOf)

    fun genres(doc: Document): List<String> = doc.select("article.post.single div.entry-meta span.genres a")
        .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

    /** "2h 52m" → 172; "45m" → 45; "min." → null. */
    fun duration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return total.takeIf { it in 1..1440 }
    }

    fun durationOf(doc: Document): Int? = duration(doc.selectFirst("article.post.single span.duration")?.text())

    /** Nota de TMDB, p. ej. `6.98`. */
    fun score(doc: Document): Double? = doc.selectFirst("article.post.single span.vote .num")
        ?.text()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

    /** Reparto: `ul.cast-lst li` trae la etiqueta (`Director`, `Actores`) y los nombres enlazados. */
    fun actors(doc: Document): List<String> = doc.select("article.post.single ul.cast-lst li p a")
        .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

    fun details(doc: Document): Details? {
        val name = title(doc) ?: return null
        return Details(
            title = name,
            poster = poster(doc),
            plot = plot(doc),
            year = year(doc),
            durationMinutes = durationOf(doc),
            score = score(doc),
            genres = genres(doc),
            actors = actors(doc),
        )
    }

    /** En la ficha de serie el `<link rel=canonical>` es la referencia más estable. */
    fun canonical(doc: Document): String? = doc.selectFirst("link[rel=canonical]")?.absUrl("href")
        ?.takeIf(::siteUrl) ?: doc.location().takeIf(::siteUrl)

    fun isSeries(doc: Document): Boolean {
        val url = canonical(doc).orEmpty()
        if (url.isNotEmpty()) return isSeriesPath(url)
        return doc.selectFirst("div.choose-season") != null
    }

    // ---------------------------------------------------------------- temporadas y episodios

    /** Temporadas declaradas en el desplegable `elegir temporada`. */
    fun seasons(doc: Document): List<Int> = doc.select("div.choose-season li.sel-temp a[data-season]")
        .mapNotNull { it.attr("data-season").trim().toIntOrNull() }
        .filter { it >= 0 }.distinct().sorted()

    /** Identificador interno del post que espera la acción `action_select_season`. */
    fun postId(doc: Document): String? = doc.select("div.choose-season li.sel-temp a[data-post]")
        .firstOrNull()?.attr("data-post")?.trim()?.takeIf { it.isNotEmpty() }

    /** Temporada cuyos episodios ya vienen incluidos en el HTML. */
    fun currentSeason(doc: Document): Int? = doc.selectFirst("div.choose-season dt.n_s")
        ?.text()?.trim()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    fun episodes(root: Element): List<EpisodeItem> = root.select("article.post.episodes").mapNotNull { article ->
        val code = article.selectFirst("span.num-epi")?.text()?.trim().orEmpty()
        val match = Regex("""(\d+)\s*[xX]\s*(\d+)""").find(code) ?: return@mapNotNull null
        val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val url = article.selectFirst("a.lnk-blk[href]")?.absUrl("href") ?: return@mapNotNull null
        if (!siteUrl(url) || !path(url).startsWith("/episode/")) return@mapNotNull null
        val name = article.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
            .ifBlank { "Temporada $season capítulo $episode" }
        EpisodeItem(name, url, season, episode, https(article.selectFirst("div.post-thumbnail img")?.absUrl("src")))
    }.distinctBy { it.season to it.episode }

    /** Episodios de la temporada activa tal como aparecen en la ficha. */
    fun currentEpisodes(doc: Document): List<EpisodeItem> {
        val season = currentSeason(doc)
        val items = episodes(doc)
        return if (season == null) items else items.filter { it.season == season }
    }

    // ---------------------------------------------------------------- reproductores

    /**
     * Opciones del reproductor. El `<a href="...#options-N">` aporta el nombre del servidor y
     * `div#options-N iframe[data-src]` la URL que hay que resolver después.
     */
    fun players(doc: Document): List<Player> {
        val frames = doc.select("div[id^=options-] iframe").mapNotNull { frame ->
            val container = frame.parents().firstOrNull { it.id().startsWith("options-") } ?: return@mapNotNull null
            val id = container.id().removePrefix("options-").toIntOrNull() ?: return@mapNotNull null
            val url = frame.absUrl("data-src").ifBlank { frame.absUrl("src") }
            if (!url.startsWith("https://")) return@mapNotNull null
            id to url
        }.distinctBy { it.first }.toMap()

        val options = doc.select("aside.video-options ul.aa-tbs-video li a[href*=options-]").mapNotNull { link ->
            val option = Regex("""options-(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val url = frames[option] ?: return@mapNotNull null
            val server = link.selectFirst("span.server")?.text().orEmpty().replace(Regex("""\s+"""), " ").trim()
            Player(option, url, server)
        }
        if (options.isNotEmpty()) return options.distinctBy { it.url }

        return frames.entries.sortedBy { it.key }.map { Player(it.key, it.value, "") }
    }

    /** Idioma deducido de la etiqueta del servidor (`Morencius - LAT HD`, `Voe - CAST`...). */
    fun language(server: String): String? {
        val text = server.uppercase()
        return when {
            text.contains("LAT") -> "Latino"
            text.contains("CAST") -> "Castellano"
            text.contains("SUB") -> "Subtitulado"
            else -> null
        }
    }

    /** El `?trembed=...` devuelve un HTML mínimo con un único iframe hacia el host real. */
    fun wrappedUrl(html: String, base: String): String? {
        val doc = org.jsoup.Jsoup.parse(html, base)
        val frame = doc.selectFirst("div.Video iframe[src]")
            ?: doc.selectFirst("iframe[src]")
            ?: return null
        val url = frame.absUrl("src").ifBlank { frame.attr("src") }
        return https(url) ?: url.takeIf { it.startsWith("https://") }
    }

    // ---------------------------------------------------------------- JS empaquetado del host

    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun token(value: Int, radix: Int): String? {
        if (radix < 2 || radix > ALPHABET.length) return null
        if (value == 0) return ALPHABET[0].toString()
        var remaining = value
        val out = StringBuilder()
        while (remaining > 0) {
            out.insert(0, ALPHABET[remaining % radix])
            remaining /= radix
        }
        return out.toString()
    }

    /**
     * Desempaqueta el `eval(function(p,a,c,k,e,d){...})` de los hosts tipo VidHide.
     * Se recorre el diccionario al revés y se sustituyen solo palabras completas, igual que el
     * cargador original; se devuelve `null` si el bloque no es reconocible.
     */
    fun unpack(source: String): String? {
        val match = packedRegex.find(source) ?: return null
        var payload = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val words = match.groupValues[4].split('|')
        if (count <= 0 || count > 5000 || radix !in 2..ALPHABET.length) return null
        for (index in count - 1 downTo 0) {
            val word = words.getOrNull(index) ?: continue
            if (word.isEmpty()) continue
            val key = token(index, radix) ?: continue
            payload = payload.replace(Regex("""\b${Regex.escape(key)}\b"""), Regex.escapeReplacement(word))
        }
        return payload
    }

    private val urlRegex = Regex("""https?://[^\s"'\\<>()]+""")

    /**
     * URLs reproducibles del setup de JW Player. El sitio declara `hls2` (m3u8), `hls3` (playlist
     * `.txt`) y a veces `hls4`; se devuelven primero las `.m3u8` y luego los respaldos.
     */
    fun streams(script: String): List<String> {
        val all = urlRegex.findAll(script).map { it.value.trimEnd(',', ';', '.') }
            .filter { it.startsWith("https://") }
            .filter { url ->
                val lower = url.lowercase()
                lower.contains(".m3u8") || lower.endsWith(".txt") || lower.contains("/master.")
            }
            .distinct()
            .toList()
        return all.sortedBy { if (it.lowercase().contains(".m3u8")) 0 else 1 }
    }

    /**
     * Marcas de la página de verificación de Cloudflare (la del reto «Just a moment…»). Cloudflare
     * también la sirve con **HTTP 200**, así que el estado por sí solo no basta: `CloudflareKiller`
     * únicamente mira 403/503 y se le escapa ese caso.
     */
    private val challengeMarks = listOf(
        "Just a moment",
        "cf-browser-verification",
        "challenge-platform",
        "cf_chl_opt",
        "_cf_chl_opt",
        "cf-challenge",
        "Checking your browser",
        "Enable JavaScript and cookies to continue",
        "Verificando el navegador",
    )

    /** ¿La respuesta es el reto de Cloudflare y no la página del sitio? */
    fun isChallenge(code: Int, body: String): Boolean {
        if (code == 403 || code == 503 || code == 429) return true
        if (code !in 200..299 || body.isBlank()) return false
        // La página del sitio también puede nombrar Cloudflare en un script; solo cuenta si el
        // cuerpo contiene la estructura del reto, nunca si es un HTML largo y con contenido real.
        if (body.length > 60_000) return false
        return challengeMarks.any { body.contains(it, ignoreCase = true) }
    }

    /** Segundo paso: desde la página del host hasta las URLs de vídeo. */
    fun streamsFromHost(html: String): List<String> =
        (unpack(html)?.let { streams(it) } ?: emptyList()).ifEmpty { streams(html) }
}
