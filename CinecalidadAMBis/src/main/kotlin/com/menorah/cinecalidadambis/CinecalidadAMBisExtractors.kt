package com.menorah.cinecalidadambis

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.newSubtitleFile
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

internal const val AM_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object CinecalidadAMPacker {
    private val EVAL_START_REGEX = Regex("""eval\(\s*function\(p,a,c,k,e,[dr]\)\{""")
    private val PARAMS_REGEX = Regex(
        """\}\(\s*'([^'\\]*(?:\\.[^'\\]*)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^'\\]*(?:\\.[^'\\]*)*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val WORD_REGEX = Regex("""\b\w+\b""")
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun baseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(ALPHABET[n % radix])
            n /= radix
        }
        return sb.reverse().toString()
    }

    fun unpackAll(html: String): List<String> {
        val out = mutableListOf<String>()
        for (startMatch in EVAL_START_REGEX.findAll(html)) {
            val sliceEnd = minOf(html.length, startMatch.range.first + 160_000)
            val window = html.substring(startMatch.range.first, sliceEnd)
            val match = PARAMS_REGEX.find(window) ?: continue
            val payload = match.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val radix = match.groupValues[2].toIntOrNull()?.coerceIn(2, 62) ?: continue
            val count = match.groupValues[3].toIntOrNull() ?: continue
            val symtab = match.groupValues[4].split('|')
            val map = HashMap<String, String>(count)
            for (i in count - 1 downTo 0) {
                val key = baseN(i, radix)
                val word = symtab.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
                map[key] = word
            }
            out += WORD_REGEX.replace(payload) { m -> map[m.value] ?: m.value }
        }
        return out
    }

    fun unpackFirst(html: String): String? = unpackAll(html).firstOrNull()
}

internal object CinecalidadAMVimeosParser {
    @Suppress("DEPRECATION")
    private val permissiveMapper: ObjectMapper = ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val LINKS_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val SOURCE_M3U8_REGEX = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
    private val TRACKS_BLOCK_REGEX = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_OBJ_REGEX = Regex("""\{([^}]+)\}""")
    private val TRACK_FILE_REGEX = Regex("""file\s*:\s*["']([^"']+)["']""")
    private val TRACK_LABEL_REGEX = Regex("""label\s*:\s*["']([^"']+)["']""")
    private val TRACK_KIND_REGEX = Regex("""kind\s*:\s*["']([^"']+)["']""")
    private val SRV_PARAM_REGEX = Regex("""[?&]srv=([a-z0-9-]+)""", RegexOption.IGNORE_CASE)

    data class ParsedVimeos(
        val m3u8Urls: List<String>,
        val subtitles: List<Pair<String, String>>,
    )

    fun stripBrokenCfParam(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val base = url.substring(0, qIdx)
        val query = url.substring(qIdx + 1)
        val kept = query.split('&').filter { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            !(key.equals("cf", ignoreCase = true) && (value.isEmpty() || value.contains('*')))
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    fun buildSrvFallbackCandidates(rawUrl: String): List<String> {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return listOf(rawUrl)
        val host = uri.host ?: return listOf(rawUrl)
        val candidates = linkedSetOf(rawUrl)
        val srv = SRV_PARAM_REGEX.find(rawUrl)?.groupValues?.getOrNull(1)
        if (srv != null && host.contains('.')) {
            val domain = host.substringAfter('.')
            candidates += rawUrl.replaceFirst("://$host", "://$srv.$domain")
            candidates += rawUrl.replaceFirst("://$host", "://$srv.vimeos.zip")
            candidates += rawUrl.replaceFirst("://$host", "://$srv.vimeos.net")
        }
        if (host.startsWith("p8.", ignoreCase = true)) {
            candidates += rawUrl.replaceFirst("://$host", "://p9.vimeos.zip")
            candidates += rawUrl.replaceFirst("://$host", "://s1.vimeos.net")
        }
        return candidates.toList()
    }

    fun parse(html: String, baseUrl: String): ParsedVimeos {
        val origin = runCatching {
            val u = URI(baseUrl)
            "${u.scheme}://${u.authority}"
        }.getOrDefault(baseUrl)
        val blocks = CinecalidadAMPacker.unpackAll(html) + html
        val m3u8s = linkedSetOf<String>()
        val subs = mutableListOf<Pair<String, String>>()

        for (block in blocks) {
            for (m in LINKS_REGEX.findAll(block)) {
                val parsed = runCatching {
                    permissiveMapper.readValue(
                        m.groupValues[1].replace("\\/", "/"),
                        object : TypeReference<Map<String, String>>() {},
                    )
                }.getOrNull().orEmpty()
                val preferred = listOfNotNull(parsed["hls2"], parsed["hls"]) +
                    parsed.filterKeys { it !in setOf("hls2", "hls") }.values
                for (raw in preferred) {
                    val abs = runCatching { URI("$origin/").resolve(raw.trim()).toString() }.getOrNull() ?: continue
                    if (abs.startsWith("http")) m3u8s += abs
                }
            }

            for (m in SOURCES_REGEX.findAll(block)) {
                val entries = runCatching {
                    permissiveMapper.readValue(
                        m.groupValues[1].replace("\\/", "/"),
                        object : TypeReference<List<Map<String, Any?>>>() {},
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val raw = entry["file"]?.toString()?.trim().orEmpty()
                    if (raw.isEmpty()) continue
                    val abs = runCatching { URI("$origin/").resolve(raw).toString() }.getOrNull() ?: continue
                    if (abs.startsWith("http")) m3u8s += abs
                }
            }

            SOURCE_M3U8_REGEX.findAll(block).forEach { m3u8s += it.groupValues[1] }

            val tracksBlock = TRACKS_BLOCK_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty()
            for (objMatch in TRACK_OBJ_REGEX.findAll(tracksBlock)) {
                val obj = objMatch.groupValues[1]
                val kind = TRACK_KIND_REGEX.find(obj)?.groupValues?.getOrNull(1).orEmpty()
                if (kind.isNotEmpty() && !kind.equals("captions", ignoreCase = true) && !kind.equals("subtitles", ignoreCase = true)) {
                    continue
                }
                val file = TRACK_FILE_REGEX.find(obj)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (file.isEmpty() || file.contains("empty.srt", ignoreCase = true) || file.contains("_search.vtt", ignoreCase = true)) {
                    continue
                }
                val absFile = runCatching { URI(baseUrl).resolve(file).toString() }.getOrElse { file }
                val label = TRACK_LABEL_REGEX.find(obj)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    .ifEmpty { "Español" }
                if (label.contains("Upload captions", ignoreCase = true)) continue
                subs += label to absFile
            }
        }
        return ParsedVimeos(m3u8s.toList(), subs.distinctBy { it.second })
    }
}

internal object CinecalidadAMVoeDecoder {
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

class CinecalidadAMVoeExtractor : ExtractorApi() {
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
        var currentRef = referer ?: "https://www.cinecalidad.am/"
        var finalHtml = ""
        var finalUrl = url

        for (hop in 0..2) {
            val res = runCatching {
                app.get(currentUrl, headers = mapOf("user-agent" to AM_CHROME_UA, "Referer" to currentRef), timeout = 20L)
            }.getOrNull() ?: break
            if (!res.isSuccessful) break
            finalHtml = res.text
            finalUrl = res.url
            val nextUrl = CinecalidadAMVoeDecoder.findRedirectUrl(finalHtml)
            if (nextUrl != null && nextUrl != currentUrl) {
                currentRef = finalUrl
                currentUrl = nextUrl
                continue
            }
            break
        }

        val parsed = if (finalHtml.isNotEmpty()) CinecalidadAMVoeDecoder.parseEmbedHtml(finalHtml) else null
        if (parsed != null) {
            val origin = runCatching {
                val u = URI(finalUrl)
                "${u.scheme}://${u.authority}"
            }.getOrDefault(mainUrl)
            val streamHeaders = mapOf("Origin" to origin, "Referer" to "$origin/", "user-agent" to AM_CHROME_UA)
            callback(
                newExtractorLink(name, name, parsed.streamUrl, if (parsed.isHls) ExtractorLinkType.M3U8 else INFER_TYPE) {
                    this.referer = "$origin/"
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                },
            )
            emitted.set(true)
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

class CinecalidadAMVimeosExtractor : ExtractorApi() {
    override val name = "Vimeos"
    override val mainUrl = "https://vimeos.net"
    override val requiresReferer = true

    private suspend fun pickWorkingVimeosUrl(rawUrl: String, streamHeaders: Map<String, String>): String {
        val candidates = CinecalidadAMVimeosParser.buildSrvFallbackCandidates(rawUrl)
        var anyHttpResponse = false
        for (candidate in candidates) {
            val check = runCatching { app.get(candidate, headers = streamHeaders, timeout = 8L) }.getOrNull()
                ?: continue
            anyHttpResponse = true
            if (check.isSuccessful && check.text.contains("#EXTM3U")) {
                return candidate
            }
        }
        return if (anyHttpResponse) candidates.lastOrNull() ?: rawUrl else rawUrl
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = CinecalidadAMVimeosParser.stripBrokenCfParam(url)
        val effReferer = referer ?: "https://www.cinecalidad.am/"
        val headers = mapOf(
            "user-agent" to AM_CHROME_UA,
            "Referer" to effReferer,
        )
        var res = runCatching { app.get(cleanUrl, headers = headers, timeout = 20L) }.getOrNull()
        if (res == null || !res.isSuccessful) return

        var parsed = CinecalidadAMVimeosParser.parse(res.text, res.url)
        if (parsed.m3u8Urls.isEmpty() && cleanUrl.contains('?')) {
            val bareUrl = cleanUrl.substringBefore('?')
            val retry = runCatching {
                app.get(
                    bareUrl,
                    headers = mapOf("user-agent" to AM_CHROME_UA, "Referer" to "https://www.cinecalidad.am/"),
                    timeout = 20L,
                )
            }.getOrNull()
            if (retry != null && retry.isSuccessful) {
                res = retry
                parsed = CinecalidadAMVimeosParser.parse(res.text, res.url)
            }
        }

        for ((label, subUrl) in parsed.subtitles) {
            subtitleCallback(newSubtitleFile(label, subUrl))
        }

        val origin = runCatching {
            val u = URI(res.url)
            "${u.scheme}://${u.authority}"
        }.getOrDefault(mainUrl)
        val originReferer = "$origin/"
        val streamHeaders = mapOf(
            "Origin" to origin,
            "Referer" to originReferer,
            "user-agent" to AM_CHROME_UA,
        )

        val emitted = AtomicBoolean(false)
        for (m3u8 in parsed.m3u8Urls) {
            // p8.vimeos.zip often returns HTTP 502; resolve to working &srv= mirror (e.g. p9.vimeos.zip) before handing to ExoPlayer.
            val workingUrl = pickWorkingVimeosUrl(m3u8, streamHeaders)
            emitted.set(true)
            // Emit master playlist directly so ExoPlayer keeps #EXT-X-MEDIA:TYPE=AUDIO (Español + English).
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = workingUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = originReferer
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                },
            )
        }

        if (!emitted.get()) {
            try {
                FilemoonV2().apply {
                    this.name = this@CinecalidadAMVimeosExtractor.name
                    this.mainUrl = origin
                }.getUrl(res.url, effReferer, subtitleCallback, callback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }
}

class CinecalidadAMVideoappExtractor : ExtractorApi() {
    override val name = "Videoapp"
    override val mainUrl = "https://videoapp.zip"
    override val requiresReferer = true

    private val iframeRegex = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "user-agent" to AM_CHROME_UA,
            "Referer" to (referer ?: "https://www.cinecalidad.am/"),
        )
        val res = app.get(url, headers = headers, timeout = 20L)
        if (!res.isSuccessful) return
        val rawTarget = iframeRegex.find(res.text)?.groupValues?.getOrNull(1) ?: return
        val target = CinecalidadAMVimeosParser.stripBrokenCfParam(rawTarget)
        if (target.contains("vimeos.", ignoreCase = true)) {
            val emitted = AtomicBoolean(false)
            CinecalidadAMVimeosExtractor().getUrl(target, res.url, subtitleCallback) {
                emitted.set(true)
                callback(it)
            }
            if (!emitted.get()) {
                CinecalidadAMVimeosExtractor().getUrl(target, "https://www.cinecalidad.am/", subtitleCallback, callback)
            }
        } else if (target.contains("voe.", ignoreCase = true) || target.contains("caseyseemavoid.", ignoreCase = true)) {
            CinecalidadAMVoeExtractor().getUrl(target, res.url, subtitleCallback, callback)
        } else {
            loadExtractor(target, res.url, subtitleCallback, callback)
        }
    }
}
