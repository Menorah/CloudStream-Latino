package com.menorah.repelis24shopbis

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal const val SHOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object NuploadMyDecoder {
    private val ARRAY_VAR_REGEX = Regex("""var\s+([A-Za-z_]\w*)\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL)
    private val QUOTED_STR_REGEX = Regex("""["']([^"']+)["']""")
    private val FOREACH_OFFSET_REGEX = Regex(
        """\b([A-Za-z_]\w*)\.forEach\([^;{}]*\{[^;{}]*parseInt\(atob\([^;{}]*?-\s*(\d+)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val GENERIC_OFFSET_REGEX = Regex("""parseInt\(atob\([^;{}]*?-\s*(\d+)""")
    private val SESZ_REGEX = Regex("""var\s+sesz\s*=\s*["']([^"']+)["']""")
    private val DYNAMIC_TOKEN_REF_REGEX = Regex("""["']\?s=["']\s*\+\s*([A-Za-z_]\w*)""")
    private val NON_DIGIT_REGEX = Regex("""\D+""")

    fun parseStreamUrl(html: String): String? {
        val foreachMatch = FOREACH_OFFSET_REGEX.find(html)
        val arrayName = foreachMatch?.groupValues?.getOrNull(1)
        val offset = foreachMatch?.groupValues?.getOrNull(2)?.toLongOrNull()
            ?: GENERIC_OFFSET_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null

        val arrayContents = ARRAY_VAR_REGEX.findAll(html)
            .firstOrNull { arrayName == null || it.groupValues[1] == arrayName }
            ?.groupValues?.getOrNull(2)
            ?: return null

        val items = QUOTED_STR_REGEX.findAll(arrayContents).map { it.groupValues[1] }.toList()
        if (items.isEmpty()) return null

        val sb = StringBuilder(items.size)
        for (item in items) {
            val decoded = RePelis24ShopBisMarkup.decodeBase64Utf8(item)
            val digits = decoded.replace(NON_DIGIT_REGEX, "")
            val codePoint = digits.toLongOrNull()?.minus(offset) ?: return null
            if (codePoint !in 0..0xFFFF) return null
            sb.append(codePoint.toInt().toChar())
        }
        val baseUrl = sb.toString().trim()
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

        val sesz = SESZ_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty {
            val tokenVar = DYNAMIC_TOKEN_REF_REGEX.find(html)?.groupValues?.getOrNull(1)
            if (tokenVar != null) {
                Regex("""\bvar\s+${Regex.escape(tokenVar)}\s*=\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            } else {
                ""
            }
        }
        return if (sesz.isEmpty()) {
            baseUrl
        } else {
            val sep = if ('?' in baseUrl) '&' else '?'
            "$baseUrl${sep}s=$sesz"
        }
    }
}

internal object ShopVoeDecoder {
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

    data class DecodedVoe(
        val streamUrl: String,
        val fallbackUrl: String? = null,
        val isHls: Boolean = true,
    )

    fun decodeBase64Raw(input: String): ByteArray {
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

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in 'a'..'m', in 'A'..'M' -> sb.append((c.code + 13).toChar())
                in 'n'..'z', in 'N'..'Z' -> sb.append((c.code - 13).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun decodeMkgMaToken(token: String): DecodedVoe? {
        var step = rot13(token.trim())
        for (junk in JUNK_TOKENS) {
            step = step.replace(junk, "_")
        }
        step = step.replace("_", "")
        val firstBytes = decodeBase64Raw(step)
        if (firstBytes.isEmpty()) return null

        val shifted = StringBuilder(firstBytes.size)
        for (i in firstBytes.indices) {
            val shiftedByte = ((firstBytes[i].toInt() and 0xFF) - 3) and 0xFF
            shifted.append(shiftedByte.toChar())
        }
        val secondBytes = decodeBase64Raw(shifted.reverse().toString())
        if (secondBytes.isEmpty()) return null

        val jsonText = String(secondBytes, Charsets.UTF_8)
        val node = runCatching { mapper.readTree(jsonText) }.getOrNull() ?: return null
        val src = node.path("source").asText("").trim().replace("\\/", "/")
        val fallback = node.path("fallback").asText("").trim().replace("\\/", "/")
            .takeIf { it.startsWith("http") }
        if (!src.startsWith("http://") && !src.startsWith("https://")) return null
        return DecodedVoe(
            streamUrl = src,
            fallbackUrl = fallback,
            isHls = src.contains(".m3u8", ignoreCase = true) || !src.contains(".mp4", ignoreCase = true),
        )
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
            if (encoded.length < 16) continue
            decodeMkgMaToken(encoded)?.let { return it }
        }
        val hlsRaw = HLS_FIELD_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!hlsRaw.isNullOrEmpty()) {
            val resolved = if (hlsRaw.startsWith("http")) hlsRaw else RePelis24ShopBisMarkup.decodeBase64Utf8(hlsRaw)
            if (resolved.startsWith("http") && !resolved.contains("test-videos.co.uk")) {
                return DecodedVoe(streamUrl = resolved, isHls = true)
            }
        }
        val mp4Raw = MP4_FIELD_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!mp4Raw.isNullOrEmpty()) {
            val resolved = if (mp4Raw.startsWith("http")) mp4Raw else RePelis24ShopBisMarkup.decodeBase64Utf8(mp4Raw)
            if (resolved.startsWith("http") && !resolved.contains("test-videos.co.uk")) {
                return DecodedVoe(streamUrl = resolved, isHls = false)
            }
        }
        return null
    }
}

class RePelis24ShopVoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val emitted = AtomicBoolean(false)
        var currentUrl = url
        var currentRef = referer ?: "https://repelis24.shop/"
        var finalHtml = ""
        var finalUrl = url

        for (hop in 0..2) {
            val res = runCatching {
                app.get(
                    currentUrl,
                    headers = mapOf(
                        "user-agent" to SHOP_CHROME_UA,
                        "Referer" to currentRef,
                    ),
                    timeout = 20L,
                )
            }.getOrNull() ?: break
            if (!res.isSuccessful) break
            finalHtml = res.text
            finalUrl = res.url
            val nextUrl = ShopVoeDecoder.findRedirectUrl(finalHtml)
            if (nextUrl != null && nextUrl != currentUrl) {
                currentRef = finalUrl
                currentUrl = nextUrl
                continue
            }
            break
        }

        val parsed = if (finalHtml.isNotEmpty()) ShopVoeDecoder.parseEmbedHtml(finalHtml) else null
        if (parsed != null) {
            val origin = runCatching {
                val u = URI(finalUrl)
                "${u.scheme}://${u.authority}"
            }.getOrDefault(mainUrl)
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
                "user-agent" to SHOP_CHROME_UA,
            )
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = parsed.streamUrl,
                    type = if (parsed.isHls) ExtractorLinkType.M3U8 else INFER_TYPE,
                ) {
                    this.referer = "$origin/"
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                },
            )
            emitted.set(true)
            parsed.fallbackUrl?.let { fb ->
                if (fb != parsed.streamUrl) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = fb,
                            type = if (fb.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE,
                        ) {
                            this.referer = "$origin/"
                            this.headers = streamHeaders
                            this.quality = Qualities.P720.value
                        },
                    )
                }
            }
        }

        if (!emitted.get()) {
            try {
                loadExtractor(finalUrl, currentRef, subtitleCallback, callback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }
}

class NuploadMyExtractor : ExtractorApi() {
    override val name = "Nupload"
    override val mainUrl = "https://nupload.my"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val reqHeaders = mapOf(
            "user-agent" to SHOP_CHROME_UA,
            "Referer" to (referer ?: "https://repelis24.shop/"),
        )

        // Wrapper URL: https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2F...
        val queryWrapped = RePelis24ShopBisMarkup.extractQueryParam(url, "url")
        if (queryWrapped != null && (queryWrapped.startsWith("http://") || queryWrapped.startsWith("https://"))) {
            dispatchWrapped(queryWrapped, url, subtitleCallback, callback)
            return
        }

        val res = app.get(url, headers = reqHeaders, timeout = 20L)
        if (!res.isSuccessful) return

        if (url.contains("/iframe/", ignoreCase = true)) {
            val iframeSrc = Jsoup.parse(res.text, res.url)
                .selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!iframeSrc.isNullOrEmpty() && (iframeSrc.startsWith("http://") || iframeSrc.startsWith("https://"))) {
                dispatchWrapped(iframeSrc, res.url, subtitleCallback, callback)
                return
            }
        }

        val m3u8Url = NuploadMyDecoder.parseStreamUrl(res.text) ?: return
        val origin = runCatching {
            val u = URI(res.url)
            "${u.scheme}://${u.authority}"
        }.getOrDefault(mainUrl)
        val originReferer = "$origin/"
        // sv5.ibra.lat requires BOTH Origin and Referer = https://nupload.my on .ts segments (returns HTTP 403 otherwise).
        val streamHeaders = mapOf(
            "Origin" to origin,
            "Referer" to originReferer,
            "user-agent" to SHOP_CHROME_UA,
        )

        val check = runCatching {
            app.get(m3u8Url, headers = streamHeaders, timeout = 10L)
        }.getOrNull()
        if (check != null && (!check.isSuccessful || !check.text.contains("#EXTM3U"))) {
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = originReferer
                this.headers = streamHeaders
                this.quality = Qualities.P1080.value
            },
        )
    }

    private suspend fun dispatchWrapped(
        wrappedUrl: String,
        wrapperReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (wrappedUrl.contains("voe.", ignoreCase = true) ||
            wrappedUrl.contains("caseyseemavoid.", ignoreCase = true)
        ) {
            RePelis24ShopVoeExtractor().getUrl(wrappedUrl, wrapperReferer, subtitleCallback, callback)
            return
        }
        val emitted = AtomicBoolean(false)
        try {
            loadExtractor(wrappedUrl, wrapperReferer, subtitleCallback) { link ->
                emitted.set(true)
                callback(link)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
        if (!emitted.get()) {
            RePelis24ShopVoeExtractor().getUrl(wrappedUrl, wrapperReferer, subtitleCallback, callback)
        }
    }
}
