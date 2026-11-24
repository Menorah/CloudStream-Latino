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

    private suspend fun fetch(input: String): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(SoloLatinoMarkup.siteUrl(url)) { "Dirección fuera de SoloLatino" }
        val res = app.get(url, timeout = 15)
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
        return responses(SoloLatinoMarkup.cards(fetch("/buscar?q=${URLEncoder.encode(text, "UTF-8")}")))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val text = query.trim()
        if (text.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)
        val path = if (page == 1) "/buscar?q=${URLEncoder.encode(text, "UTF-8")}"
        else "/buscar?q=${URLEncoder.encode(text, "UTF-8")}&page=$page"
        val doc = fetch(path)
        return newSearchResponseList(
            responses(SoloLatinoMarkup.cards(doc)),
            hasNext = SoloLatinoMarkup.hasNext(doc, page),
        )
    }

    override suspend fun load(url: String): LoadResponse? {
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
        val page = fetch(data)
        val servers = SoloLatinoMarkup.servers(page).take(12)
        if (servers.isEmpty()) return false
        val session = withContext(Dispatchers.IO) {
            runCatching { app.get("$mainUrl/sanctum/csrf-cookie", timeout = 15) }.getOrNull()
        }
        val cookies = mutableMapOf<String, String>()
        session?.let { response ->
            for (name in listOf("set-cookie", "Set-Cookie")) {
                response.headers[name]?.split(",")?.forEach { part ->
                    val kv = part.split(";")[0].trim()
                    val eq = kv.indexOf('=')
                    if (eq > 0) cookies[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
                }
            }
        }
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
        return emitted
    }

    private fun apiHeaders(xsrf: String?, token: String, referer: String, cookieHeader: String): Map<String, String> =
        SoloLatinoChain.apiHeaders(xsrf, token, referer, System.currentTimeMillis()) +
            (if (cookieHeader.isBlank()) emptyMap() else mapOf("Cookie" to cookieHeader))

    private suspend fun resolveServer(
        server: ServerOption,
        referer: String,
        xsrf: String?,
        cookieHeader: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        var resolved = playerUrl(server.token, referer, xsrf, cookieHeader)
        if (resolved == null) resolved = playerUrl(server.token, referer, xsrf = null, cookieHeader, withRender = false)
        val (player, kind) = resolved ?: return false
        val label = "[${server.language}] ${server.title.ifBlank { "Servidor" }}"
        return when (kind) {
            SoloLatinoChain.PlayerKind.DIRECT_MP4 -> {
                emit(newExtractorLink(this.name, label, player, ExtractorLinkType.VIDEO) { this.referer = referer })
                true
            }
            SoloLatinoChain.PlayerKind.PELIS_SERIES_HOY ->
                resolvePrincipal(player, referer, label, subtitleCallback, emit)
            SoloLatinoChain.PlayerKind.EMBED69 ->
                resolveEmbed69(player, referer, label, subtitleCallback, emit)
            SoloLatinoChain.PlayerKind.IFRAME -> {
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                try { loadExtractor(player, referer, subtitleCallback) { links.add(it) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
                val found = synchronized(links) { links.toList() }
                found.forEach { emit(relabeled(it, label)) }
                found.isNotEmpty()
            }
        }
    }

    private suspend fun playerUrl(
        token: String,
        referer: String,
        xsrf: String?,
        cookieHeader: String,
        withRender: Boolean = true,
    ): Pair<String, SoloLatinoChain.PlayerKind>? {
        val headers = (if (withRender) apiHeaders(xsrf, token, referer, cookieHeader)
        else SoloLatinoChain.apiHeaders(xsrf, token, referer, System.currentTimeMillis()).filterKeys { it != "X-SL-Render" } +
            (if (cookieHeader.isBlank()) emptyMap() else mapOf("Cookie" to cookieHeader)))
        val res = runCatching {
            app.post("$mainUrl/api/player-url", headers = headers,
                requestBody = SoloLatinoChain.apiBody(token).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
        }.getOrNull() ?: return null
        if (!res.isSuccessful) return null
        val body = res.text
        val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex(""""player_url"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return null
        if (!url.startsWith("https://") || url.length > 2048) return null
        return url to SoloLatinoChain.classify(url)
    }

    private suspend fun resolvePrincipal(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(playerUrl, timeout = 15).text }.getOrNull() ?: return false
        val handshake = PrincipalHandshake.parse(html) ?: return false
        val origin = URI(playerUrl).let { "${it.scheme}://${it.host}" }
        val base = mapOf("Referer" to playerUrl, "Origin" to origin)
        val first = runCatching {
            app.post("$origin/s.php", headers = base, data = handshake.fields("1"), timeout = 15)
        }.getOrNull() ?: return false
        val options = PrincipalPlayerData.options(first.text).take(12)
        if (options.isEmpty()) return false
        runCatching { app.post("$origin/s.php", headers = base, data = handshake.fields("click"), timeout = 15) }
        var any = false
        for (option in options) {
            val response = runCatching {
                app.post("$origin/s.php", headers = base, data = handshake.fields("2", option.id), timeout = 15)
            }.getOrNull() ?: continue
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
        val html = runCatching { app.get(playerUrl, timeout = 15).text }.getOrNull() ?: return false
        var servers = Embed69Servers.parse(html)
        if (servers.isEmpty()) {
            val raw = Embed69Raw.rawEmbeds(html)
            if (raw.isEmpty()) return false
            val response = runCatching {
                app.post("https://embed69.org/api/decrypt", headers = mapOf("Referer" to playerUrl),
                    requestBody = SoloLatinoChain.decryptApiBody(raw.map { it.encrypted })
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()), timeout = 20)
            }.getOrNull() ?: return false
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
