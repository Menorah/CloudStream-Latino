package com.menorah.cinecalidadmybis

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal const val MY_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object CinecalidadMYVoeDecoder {
    private val mapper = ObjectMapper()
    private val REDIRECT_URL_REGEX = Regex("""window\.location\.href\s*=\s*["'](https?://[^"']+)["']""")
    private val JSON_SCRIPT_REGEX = Regex("""<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HLS_FIELD_REGEX = Regex("""["']hls["']\s*:\s*["']([^"']+)["']""")
    private val MP4_FIELD_REGEX = Regex("""["']mp4["']\s*:\s*["']([^"']+)["']""")
    private val JUNK_TOKENS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_TABLE = IntArray(256) { -1 }.apply {
        for (i in B64_ALPHABET.indices) this[B64_ALPHABET[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    data class DecodedVoe(val streamUrl: String, val fallbackUrl: String? = null, val isHls: Boolean = true)

    private fun decodeBase64Raw(input: String): ByteArray {
        val cleaned = input.trim().replace("\r", "").replace("\n", "").replace(" ", "").trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ByteArray((cleaned.length * 3) / 4)
        var outIdx = 0
        var acc = 0
        var bits = 0
        for (ch in cleaned) {
            val code = ch.code
            val v = if (code in B64_TABLE.indices) B64_TABLE[code] else -1
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outIdx < out.size) out[outIdx++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outIdx)
    }

    private fun rot13(input: String): String = buildString(input.length) {
        for (c in input) {
            when (c) {
                in 'a'..'m', in 'A'..'M' -> append((c.code + 13).toChar())
                in 'n'..'z', in 'N'..'Z' -> append((c.code - 13).toChar())
                else -> append(c)
            }
        }
    }

    fun decodeMkgMaToken(token: String): DecodedVoe? {
        var step = rot13(token.trim())
        for (junk in JUNK_TOKENS) step = step.replace(junk, "_")
        step = step.replace("_", "")
        val firstBytes = decodeBase64Raw(step)
        if (firstBytes.isEmpty()) return null
        val shifted = StringBuilder(firstBytes.size)
        for (b in firstBytes) shifted.append((((b.toInt() and 0xFF) - 3) and 0xFF).toChar())
        val secondBytes = decodeBase64Raw(shifted.reverse().toString())
        if (secondBytes.isEmpty()) return null
        val node = runCatching { mapper.readTree(String(secondBytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        val src = node.path("source").asText("").trim().replace("\\/", "/")
        val fallback = node.path("fallback").asText("").trim().replace("\\/", "/").takeIf { it.startsWith("http") }
        if (!src.startsWith("http://") && !src.startsWith("https://")) return null
        return DecodedVoe(src, fallback, src.contains(".m3u8", ignoreCase = true) || !src.contains(".mp4", ignoreCase = true))
    }

    fun findRedirectUrl(html: String): String? =
        REDIRECT_URL_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()

    fun parseEmbedHtml(html: String): DecodedVoe? {
        for (m in JSON_SCRIPT_REGEX.findAll(html)) {
            val raw = m.groupValues[1].trim()
            if (!raw.startsWith("[")) continue
            val arr = runCatching { mapper.readTree(raw) }.getOrNull() ?: continue
            if (!arr.isArray || arr.size() == 0) continue
            val encoded = arr.get(0)?.asText("")?.trim().orEmpty()
            if (encoded.length >= 16) decodeMkgMaToken(encoded)?.let { return it }
        }
        val hlsRaw = HLS_FIELD_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!hlsRaw.isNullOrEmpty()) {
            val resolved = if (hlsRaw.startsWith("http")) hlsRaw else String(decodeBase64Raw(hlsRaw), Charsets.UTF_8)
            if (resolved.startsWith("http") && !resolved.contains("test-videos.co.uk")) return DecodedVoe(resolved, isHls = true)
        }
        val mp4Raw = MP4_FIELD_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!mp4Raw.isNullOrEmpty()) {
            val resolved = if (mp4Raw.startsWith("http")) mp4Raw else String(decodeBase64Raw(mp4Raw), Charsets.UTF_8)
            if (resolved.startsWith("http") && !resolved.contains("test-videos.co.uk")) return DecodedVoe(resolved, isHls = false)
        }
        return null
    }
}

class CinecalidadMYVoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val normalizedUrl = url.trim().let { raw ->
            val match = Regex("""^(https?://[^/]+)/([A-Za-z0-9]+)/?$""").find(raw)
            if (match != null) "${match.groupValues[1]}/e/${match.groupValues[2]}" else raw
        }
        val candidates = if (normalizedUrl != url) listOf(normalizedUrl, url) else listOf(url)
        val emitted = AtomicBoolean(false)
        var lastFinalUrl = url
        var lastRef = referer ?: "https://www.cinecalidad.my/"

        for (startUrl in candidates) {
            var currentUrl = startUrl
            var currentRef = referer ?: "https://www.cinecalidad.my/"
            var finalHtml = ""
            var finalUrl = startUrl

            for (hop in 0..2) {
                val res = runCatching {
                    app.get(currentUrl, headers = mapOf("user-agent" to MY_CHROME_UA, "Referer" to currentRef), timeout = 20L)
                }.getOrNull() ?: break
                if (!res.isSuccessful) break
                finalHtml = res.text
                finalUrl = res.url
                val nextUrl = CinecalidadMYVoeDecoder.findRedirectUrl(finalHtml)
                if (nextUrl != null && nextUrl != currentUrl) {
                    currentRef = finalUrl
                    currentUrl = nextUrl
                    continue
                }
                break
            }
            lastFinalUrl = finalUrl
            lastRef = currentRef

            val parsed = if (finalHtml.isNotEmpty()) CinecalidadMYVoeDecoder.parseEmbedHtml(finalHtml) else null
            if (parsed != null) {
                val origin = runCatching {
                    val u = URI(finalUrl)
                    "${u.scheme}://${u.authority}"
                }.getOrDefault(mainUrl)
                val streamHeaders = mapOf("Origin" to origin, "Referer" to "$origin/", "user-agent" to MY_CHROME_UA)
                callback(
                    newExtractorLink(name, name, parsed.streamUrl, if (parsed.isHls) ExtractorLinkType.M3U8 else INFER_TYPE) {
                        this.referer = "$origin/"
                        this.headers = streamHeaders
                        this.quality = Qualities.P1080.value
                    },
                )
                emitted.set(true)
                break
            }
        }
        if (!emitted.get()) {
            try {
                loadExtractor(lastFinalUrl, lastRef, subtitleCallback, callback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * cinecalidad.my serves OnlineFilemoon IDs via https://bysedikamoum.com/d/<id> (or /e/<id>),
 * which runs the ByseSX /api/videos/<id>/embed/details -> /playback AES-GCM flow.
 */
class CinecalidadMYBysedikamoumExtractor : ByseSX() {
    override val name = "Filemoon"
    override val mainUrl = "https://bysedikamoum.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val emitted = AtomicBoolean(false)
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            emitted.set(true)
            callback(link)
        }
        try {
            super.getUrl(url, referer, subtitleCallback, trackingCallback)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Fall through to /e/ variant if /d/ failed.
        }
        if (!emitted.get() && url.contains("/d/", ignoreCase = true)) {
            val embedVariant = url.replace("/d/", "/e/", ignoreCase = true)
            try {
                super.getUrl(embedVariant, referer, subtitleCallback, trackingCallback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
        if (!emitted.get()) {
            try {
                FilemoonV2().apply {
                    this.name = this@CinecalidadMYBysedikamoumExtractor.name
                    this.mainUrl = this@CinecalidadMYBysedikamoumExtractor.mainUrl
                }.getUrl(url.replace("/d/", "/e/", ignoreCase = true), referer, subtitleCallback, trackingCallback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }
}
