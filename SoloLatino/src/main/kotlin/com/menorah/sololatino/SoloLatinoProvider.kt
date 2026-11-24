package com.menorah.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * SoloLatino provider. Site mechanics documented from the live site (November 2026) and
 * from public third-party implementations:
 *
 *  - Server buttons carry `data-player-token`; `POST /api/player-url` resolves each one
 *    to a player URL of kind mp4, pelisserieshoy or embed69 ([SoloLatinoChain]).
 *  - The `player.pelisserieshoy.com` chain uses [PrincipalHandshake] and
 *    [PrincipalPlayerData]; the signed `p.php` proxy is required to play HLS.
 *  - Embed69 pages are decrypted locally ([Embed69Servers]) or via the player's
 *    `POST /api/decrypt` fallback ([Embed69Raw]).
 */
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = SoloLatinoMarkup.BASE
    override var name = "SoloLatino"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf(
        "/peliculas" to "Películas",
        "/series" to "Series",
        "/animes" to "Animes",
    )

    /** Browser-like headers: the site may serve degraded markup to bare HTTP clients. */
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "es-419,es;q=0.9",
    )

    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(SoloLatinoMarkup.siteUrl(url)) { "Dirección fuera de SoloLatino" }
        val res = app.get(url, headers = browserHeaders, timeout = 15)
        if (!res.isSuccessful || !SoloLatinoMarkup.siteUrl(res.url)) {
            throw IllegalStateException("SoloLatino no respondió correctamente (HTTP ${res.code}).")
        }
        return Jsoup.parse(res.text, res.url)
    }

    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { card ->
        val type = if (card.type == "movie") TvType.Movie else TvType.TvSeries
        newMovieSearchResponse(card.title, card.url, type) { posterUrl = card.poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val path = if (page == 1) base else "$base?page=$page"
        val doc = fetch(path)
        return newHomePageResponse(
            HomePageList(request.name, responses(SoloLatinoMarkup.cards(doc))),
            hasNext = SoloLatinoMarkup.hasNext(doc, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        if (text.equals("diagnostico", true) || text.equals("diagnóstico", true)) {
            return listOf(newMovieSearchResponse("🧪 Diagnóstico de enlaces (no es contenido)", "diagnostico://sololatino", TvType.Movie))
        }
        return responses(SoloLatinoMarkup.cards(fetch("/buscar?q=${URLEncoder.encode(text, "UTF-8")}")))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val text = query.trim()
        if (text.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        if (page == 1 && text.contains("diag", ignoreCase = true)) {
            // La app usa esta variante paginada; el 🧪 debe aparecer aquí también.
            val card = newMovieSearchResponse("🧪 Diagnóstico de enlaces (no es contenido)", "diagnostico://sololatino", TvType.Movie)
            return newSearchResponseList(listOf(card), hasNext = false)
        }
        val path = if (page == 1) "/buscar?q=${URLEncoder.encode(text, "UTF-8")}"
        else "/buscar?q=${URLEncoder.encode(text, "UTF-8")}&page=$page"
        val doc = fetch(path)
        return newSearchResponseList(
            responses(SoloLatinoMarkup.cards(doc)),
            hasNext = SoloLatinoMarkup.hasNext(doc, page),
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("diagnostico://")) {
            return newMovieLoadResponse("🧪 Diagnóstico de enlaces (no es contenido)", url, TvType.Movie, url) {
                plot = "Reproduce este título para ver en la lista de fuentes el resultado de cada paso de la cadena de servidores de SoloLatino. No transmite vídeo."
            }
        }
        val doc = fetch(url)
        val detail = SoloLatinoDetails.detail(doc) ?: return null
        val chapters = SoloLatinoMarkup.episodes(doc)
        return if (URI(url).path.startsWith("/pelicula/") || chapters.isEmpty()) {
            newMovieLoadResponse(detail.title, url, TvType.Movie, url) {
                posterUrl = detail.poster; backgroundPosterUrl = detail.background
                plot = detail.plot; year = detail.year
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
            }
        } else {
            newTvSeriesLoadResponse(detail.title, url, TvType.TvSeries, chapters.map { chapter ->
                newEpisode(chapter.url) {
                    name = chapter.title
                    season = chapter.season
                    episode = chapter.number
                    posterUrl = chapter.poster
                }
            }) {
                posterUrl = detail.poster; backgroundPosterUrl = detail.background
                plot = detail.plot; year = detail.year
                if (detail.genres.isNotEmpty()) this.tags = detail.genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.startsWith("diagnostico://")) return diagnose(callback)
        val page = fetch(data)
        val servers = SoloLatinoMarkup.servers(page).take(12)
        if (servers.isEmpty()) return false
        val session = try {
            app.get("$mainUrl/sanctum/csrf-cookie", timeout = 15)
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
        val cookies = session?.cookies ?: emptyMap()
        val xsrf = SoloLatinoChain.csrfToken(cookies)
        val cookieHeader = cookies.entries.joinToString("; ") { entry -> entry.key + "=" + entry.value }
        var emitted = false
        for (server in servers) {
            withTimeoutOrNull(30_000L) {
                try {
                    if (resolveServer(server, data, xsrf, cookieHeader, subtitleCallback, callback)) emitted = true
                } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            }
        }
        if (!emitted) {
            // Fallback: direct embeds that the page carries outside the token buttons.
            val embeds = page.select("iframe[src], [data-iframe], [data-embed]").mapNotNull { element ->
                val value = sequenceOf("src", "data-iframe", "data-embed").map { element.absUrl(it).ifBlank { element.attr(it) } }
                    .firstOrNull { it.startsWith("https://") } ?: return@mapNotNull null
                value.takeIf { SoloLatinoMarkup.siteUrl(it) || !it.contains("sololatino.net.") }
            }.distinct().take(6)
            for (embed in embeds) {
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try { loadExtractor(embed, data, subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                val found = synchronized(links) { links.toList() }
                found.forEach { callback(relabeled(it, "[Embebido]")) }
                if (found.isNotEmpty()) emitted = true
            }
        }
        return emitted
    }

    private fun apiHeaders(xsrf: String?, token: String, referer: String, cookieHeader: String): Map<String, String> =
        SoloLatinoChain.apiHeaders(xsrf, token, referer, System.currentTimeMillis()) +
            (if (cookieHeader.isBlank()) emptyMap() else mapOf("Cookie" to cookieHeader))

    /**
     * Runs the live chain against a known episode and reports each step as a source
     * entry, so the state of every stage is visible on the device without logs.
     */
    private suspend fun diagnose(callback: (ExtractorLink) -> Unit): Boolean {
        val steps = mutableListOf<String>()
        fun step(text: String) {
            val clean = text.replace(Regex("\\s+"), " ").trim().take(110)
            steps.add(clean)
        }
        val target = "$mainUrl/serie/black-clover/temporada-1/episodio-1"
        try {
            val res = app.get(target, timeout = 15)
            step("ficha HTTP ${res.code}")
            val doc = Jsoup.parse(res.text, res.url)
            val servers = SoloLatinoMarkup.servers(doc)
            step("botones=${servers.size} idiomas=${servers.map { it.language }.distinct()}")
            val session = try { app.get("$mainUrl/sanctum/csrf-cookie", timeout = 15) } catch (e: CancellationException) { throw e } catch (t: Throwable) { null }
            val cookies = session?.cookies ?: emptyMap()
            val xsrf = SoloLatinoChain.csrfToken(cookies)
            step("csrf HTTP ${session?.code ?: "err"} cookies=${cookies.keys} xsrf=${if (xsrf != null) "sí" else "no"}")
            for (server in servers.take(2)) {
                val attempts = listOf(true to true, false to true, true to false, false to false)
                var answered = false
                for ((render, useXsrf) in attempts) {
                    val resolved = playerUrl(server.token, res.url, xsrf, cookies.entries.joinToString("; ") { e -> e.key + "=" + e.value }, render, useXsrf)
                    if (resolved != null) {
                        step("api render=$render xsrf=$useXsrf → ${resolved.second} ${resolved.first.take(60)}")
                        answered = true
                        break
                    }
                }
                if (!answered) step("api render=todo falló para token ${server.token.take(12)}…")
            }
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            step("error ${t.javaClass.simpleName}: ${t.message?.take(60)}")
        }
        step("pasos=${steps.size} (fin)")
        steps.forEachIndexed { index, text ->
            callback(newExtractorLink(this.name, "%02d) %s".format(index + 1, text), "https://sololatino.net/", ExtractorLinkType.VIDEO) {})
        }
        return true
    }

    private suspend fun resolveServer(
        server: ServerOption,
        referer: String,
        xsrf: String?,
        cookieHeader: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        var resolved = playerUrl(server.token, referer, xsrf, cookieHeader, withRender = true, withXsrf = true)
            ?: playerUrl(server.token, referer, xsrf, cookieHeader, withRender = false, withXsrf = true)
            ?: playerUrl(server.token, referer, xsrf, cookieHeader, withRender = true, withXsrf = false)
            ?: playerUrl(server.token, referer, xsrf, cookieHeader, withRender = false, withXsrf = false)
        val (player, kind) = resolved ?: return false
        val label = "[${server.language}] ${server.title.ifBlank { "Servidor" }}"
        var any = false
        when (kind) {
            SoloLatinoChain.PlayerKind.DIRECT_MP4 -> {
                val type = if (player.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                emit(newExtractorLink(this.name, label, player, type) { this.referer = referer })
                any = true
            }
            SoloLatinoChain.PlayerKind.PELIS_SERIES_HOY ->
                any = resolvePrincipal(player, referer, label, subtitleCallback, emit)
            SoloLatinoChain.PlayerKind.EMBED69 ->
                any = resolveEmbed69(player, referer, label, subtitleCallback, emit)
            SoloLatinoChain.PlayerKind.IFRAME -> {
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try { loadExtractor(player, referer, subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                val found = synchronized(links) { links.toList() }
                found.forEach { emit(relabeled(it, label)) }
                any = found.isNotEmpty()
            }
        }
        if (!any && kind != SoloLatinoChain.PlayerKind.IFRAME) {
            // Last resort: the player URL may still be a generic embed CloudStream knows.
            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            try { loadExtractor(player, referer, subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            val found = synchronized(links) { links.toList() }
            found.forEach { emit(relabeled(it, label)) }
            any = found.isNotEmpty()
        }
        return any
    }

    private suspend fun playerUrl(
        token: String,
        referer: String,
        xsrf: String?,
        cookieHeader: String,
        withRender: Boolean,
        withXsrf: Boolean,
    ): Pair<String, SoloLatinoChain.PlayerKind>? {
        var headers = SoloLatinoChain.apiHeaders(if (withXsrf) xsrf else null, token, referer, System.currentTimeMillis())
        if (!withRender) headers = headers.filterKeys { it != "X-SL-Render" }
        if (cookieHeader.isNotBlank()) headers = headers + mapOf("Cookie" to cookieHeader)
        val res = try {
            app.post("$mainUrl/api/player-url", headers = headers,
                requestBody = SoloLatinoChain.apiBody(token).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { return null }
        if (!res.isSuccessful) return null
        val (url, type) = SoloLatinoChain.extractPlayerUrl(res.text) ?: return null
        return url to SoloLatinoChain.classify(url, type)
    }

    private suspend fun resolvePrincipal(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val html = try { app.get(playerUrl, timeout = 15).text } catch (e: CancellationException) { throw e } catch (_: Throwable) { return false }
        val handshake = PrincipalHandshake.parse(html) ?: return false
        val origin = URI(playerUrl).let { "${it.scheme}://${it.host}" }
        val base = mapOf("Referer" to playerUrl, "Origin" to origin)
        val first = try {
            app.post("$origin/s.php", headers = base, data = handshake.fields("1"), timeout = 15)
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { return false }
        val options = PrincipalPlayerData.options(first.text).take(12)
        if (options.isEmpty()) return false
        try { app.post("$origin/s.php", headers = base, data = handshake.fields("click"), timeout = 15) } catch (_: Throwable) { }
        var any = false
        for (option in options) {
            val response = try {
                app.post("$origin/s.php", headers = base, data = handshake.fields("2", option.id), timeout = 15)
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { continue }
            val source = PrincipalPlayerData.source(response.text) ?: continue
            if (source.type == "signed-proxy") {
                emit(newExtractorLink(this.name, "$label [${option.language}] ${option.label}", source.url, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.headers = mapOf("Referer" to playerUrl, "Origin" to origin)
                })
                any = true
            } else {
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try { loadExtractor(source.url, playerUrl, subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                val found = synchronized(links) { links.toList() }
                found.forEach { emit(relabeled(it, "$label [${option.language}] ${option.label}")) }
                any = any || found.isNotEmpty()
            }
        }
        return any
    }

    private suspend fun resolveEmbed69(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val html = try { app.get(playerUrl, timeout = 15).text } catch (e: CancellationException) { throw e } catch (_: Throwable) { return false }
        var servers = Embed69Servers.parse(html)
        if (servers.isEmpty()) {
            val raw = Embed69Raw.rawEmbeds(html)
            if (raw.isEmpty()) return false
            val response = try {
                app.post("https://embed69.org/api/decrypt", headers = mapOf("Referer" to playerUrl),
                    requestBody = SoloLatinoChain.decryptApiBody(raw.map { it.encrypted })
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()), timeout = 20)
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { return false }
            val links = Embed69Raw.decryptedLinks(response.text) ?: return false
            servers = raw.mapIndexedNotNull { index, embed ->
                links.getOrNull(index)?.let { EmbeddedServer(embed.language, embed.name, it) }
            }
        }
        if (servers.isEmpty()) return false
        var any = false
        for (server in servers.take(12)) {
            val target = SoloLatinoChain.fixHost(server.url)
            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            try { loadExtractor(target, playerUrl, subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
            val found = synchronized(links) { links.toList() }
            found.forEach { emit(relabeled(it, "$label [${server.language}] ${server.name}")) }
            any = any || found.isNotEmpty()
        }
        return any
    }

    private suspend fun relabeled(link: ExtractorLink, label: String): ExtractorLink {
        if (link is DrmExtractorLink) return newDrmExtractorLink(link.source, label, link.url, link.type, link.uuid) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData
            audioTracks = link.audioTracks; kid = link.kid; key = link.key; kty = link.kty
            keyRequestParameters = link.keyRequestParameters; licenseUrl = link.licenseUrl
        }
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer; quality = link.quality; headers = link.headers; extractorData = link.extractorData
        }
    }
}
