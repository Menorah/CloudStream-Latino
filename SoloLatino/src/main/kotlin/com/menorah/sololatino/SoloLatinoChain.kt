package com.menorah.sololatino

import java.net.URI
import java.net.URLDecoder

/**
 * Pure helpers of the SoloLatino network chain. Every function here is free of I/O so
 * the exact bytes the provider will send can be unit tested. The chain has three
 * stages, documented from the live site and from public third-party implementations:
 *
 *  1. `GET /sanctum/csrf-cookie` issues the Laravel session and the `XSRF-TOKEN`
 *     cookie, sent back percent-decoded as `X-XSRF-TOKEN`.
 *  2. `POST /api/player-url` with the `data-player-token` of a server button and the
 *     optional `X-SL-Render` header resolves `{url, type}` for one player.
 *  3. The player itself is either a direct mp4, a `player.pelisserieshoy.com` chain
 *     (handled by [PrincipalHandshake] / [PrincipalPlayerData]) or an Embed69 page
 *     (handled by [Embed69Servers]).
 */
internal object SoloLatinoChain {
    /** Laravel's cookie must be percent-decoded once; `+` is data, not a space. */
    fun csrfToken(cookies: Map<String, String>): String? {
        val raw = cookies.entries.firstOrNull { it.key.equals("XSRF-TOKEN", true) }?.value
            ?.takeIf { it.length in 1..4096 } ?: return null
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: raw
        return decoded.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) }
    }

    fun apiHeaders(xsrf: String?, token: String, referer: String, nowMillis: Long): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("Origin", SoloLatinoMarkup.BASE)
        put("Referer", referer)
        put("X-Requested-With", "XMLHttpRequest")
        if (xsrf != null) put("X-XSRF-TOKEN", xsrf)
        put("X-SL-Render", renderHeader(token, nowMillis))
    }

    fun apiBody(token: String): String =
        "{\"t\":" + token.let {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } + "}"

    enum class PlayerKind { DIRECT_MP4, PELIS_SERIES_HOY, EMBED69, IFRAME }

    /** The API answers `{url, type}`; the type is authoritative and the host is a fallback. */
    fun classify(playerUrl: String, apiType: String? = null): PlayerKind {
        val declared = apiType?.trim()?.lowercase().orEmpty()
        if (declared == "mp4" || declared == "video") return PlayerKind.DIRECT_MP4
        if (declared.contains("pelisserieshoy")) return PlayerKind.PELIS_SERIES_HOY
        if (declared.contains("embed69")) return PlayerKind.EMBED69
        val host = runCatching { URI(playerUrl).host?.lowercase() }.getOrNull().orEmpty()
        val path = runCatching { URI(playerUrl).path.orEmpty() }.getOrNull().orEmpty()
        return when {
            path.endsWith(".mp4") -> PlayerKind.DIRECT_MP4
            host.endsWith("pelisserieshoy.com") -> PlayerKind.PELIS_SERIES_HOY
            host.contains("embed69") -> PlayerKind.EMBED69
            else -> PlayerKind.IFRAME
        }
    }

    /** Accepts the field names seen in public implementations plus a bare player URL. */
    fun extractPlayerUrl(body: String): Pair<String, String?>? {
        val field = Regex(""""(?:url|player_url|link|src)"\s*:\s*"([^"]+)"""").find(body)
            ?.groupValues?.get(1)?.replace("\\/", "/")
        val bare = Regex("""https?://[^\s"'\\]+""").find(body)?.value?.replace("\\/", "/")
        val url = (field ?: bare)?.takeIf { it.startsWith("https://") && it.length <= 2048 } ?: return null
        val type = Regex(""""type"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        return url to type
    }

    /** The Embed69 player can decrypt server-side; the local key path stays as fallback. */
    fun decryptApiBody(links: List<String>): String = buildString {
        append("{\"links\":[")
        links.forEachIndexed { index, link ->
            if (index > 0) append(',')
            append('"').append(link.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        }
        append("]}")
    }

    /** Map of mirror hosts to the canonical domains known by the CloudStream extractors. */
    fun fixHost(url: String): String = url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}
