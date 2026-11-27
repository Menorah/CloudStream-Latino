package com.menorah.repelishd

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
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal const val REPELISHD_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object RePelisHDPacker {
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
                map[key] = symtab.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
            }
            out += WORD_REGEX.replace(payload) { m -> map[m.value] ?: m.value }
        }
        return out
    }

    fun unpackFirst(html: String): String? = unpackAll(html).firstOrNull()
}

internal object PlayLatinSrcParser {
    private val mapper = ObjectMapper()
    @Suppress("DEPRECATION")
    private val permissiveMapper: ObjectMapper = ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val FETCH_START_REGEX = Regex("""fetch\(\s*['"](/api/(?:embed-links|resolve))['"]""")
    private val RESOLVE_TOKEN_REGEX = Regex("""data-resolve-token=["']([^"']+)["']""")
    private val RESOLVE_ENDPOINT_REGEX = Regex("""data-resolve-endpoint=["']([^"']+)["']""")
    private val ALT_URL_REGEX = Regex("""(?:data-alt-url=|(?:const|let|var)\s+ALT_URL\s*=\s*)["'](https?:\\?/\\?/[^"']+)["']""")
    private val SERIAL_PATH_REGEX = Regex("""/serial/([^/?#]+)/(\d+)/(\d+)""")
    private val SERIAL_QUERY_REGEX = Regex("""/serial/([^/?#]+)""")
    private val MOVIE_PATH_REGEX = Regex("""/movie/([^/?#]+)""")
    private val QUERY_SEASON_REGEX = Regex("""[?&]season=(\d+)""")
    private val QUERY_EPISODE_REGEX = Regex("""[?&]episode=(\d+)""")

    data class ResolveRequestSpec(
        val endpoint: String,
        val payloadJson: String,
    )

    data class ResolvedSource(
        val name: String,
        val url: String,
        val version: String?,
        val kind: String?,
    )

    data class ResolvedMedia(
        val sources: List<ResolvedSource>,
        val captions: List<Pair<String, String>>,
    )

    fun extractAltUrl(html: String): String? =
        ALT_URL_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun extractBalancedBrace(text: String, fromIdx: Int): String? {
        val start = text.indexOf('{', fromIdx)
        if (start < 0) return null
        var depth = 0
        var inSingle = false
        var inDouble = false
        var escape = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && (inSingle || inDouble)) {
                escape = true
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (!inSingle && !inDouble) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun extractEmbedLinksFetchSpec(html: String): ResolveRequestSpec? {
        for (m in FETCH_START_REGEX.findAll(html)) {
            val endpoint = m.groupValues[1]
            val sliceEnd = minOf(html.length, m.range.first + 1200)
            val window = html.substring(m.range.first, sliceEnd)
            val stringifyIdx = window.indexOf("JSON.stringify")
            if (stringifyIdx < 0) continue
            val objLiteral = extractBalancedBrace(window, stringifyIdx) ?: continue
            val map = runCatching {
                permissiveMapper.readValue(objLiteral, object : TypeReference<Map<String, Any?>>() {})
            }.getOrNull() ?: continue
            if (map.isEmpty()) continue
            val json = mapper.writeValueAsString(map)
            return ResolveRequestSpec(endpoint = endpoint, payloadJson = json)
        }
        return null
    }

    fun extractSeasonAndEpisode(playerUrl: String): Pair<Int?, Int?> {
        val pm = SERIAL_PATH_REGEX.find(playerUrl)
        if (pm != null) {
            return pm.groupValues[2].toIntOrNull() to pm.groupValues[3].toIntOrNull()
        }
        val s = QUERY_SEASON_REGEX.find(playerUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = QUERY_EPISODE_REGEX.find(playerUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return s to e
    }

    fun extractMovieRequest(html: String, movieUrl: String = ""): ResolveRequestSpec? {
        extractEmbedLinksFetchSpec(html)?.let { return it }
        val token = RESOLVE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val endpoint = RESOLVE_ENDPOINT_REGEX.find(html)?.groupValues?.getOrNull(1) ?: "/api/resolve"
        val movieId = MOVIE_PATH_REGEX.find(movieUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""\b(tt\d{5,10})\b""").find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val payload = mapper.writeValueAsString(
            mapOf(
                "token" to token,
                "type" to "movie",
                "id" to movieId,
            ),
        )
        return ResolveRequestSpec(endpoint = endpoint, payloadJson = payload)
    }

    fun extractSerialRequest(html: String, serialUrl: String): ResolveRequestSpec? {
        extractEmbedLinksFetchSpec(html)?.let { return it }
        val token = RESOLVE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val endpoint = RESOLVE_ENDPOINT_REGEX.find(html)?.groupValues?.getOrNull(1) ?: "/api/resolve"
        val pm = SERIAL_PATH_REGEX.find(serialUrl)
        val (seriesId, seasonNum, epNum) = if (pm != null) {
            Triple(pm.groupValues[1], pm.groupValues[2].toInt(), pm.groupValues[3].toInt())
        } else {
            val sm = SERIAL_QUERY_REGEX.find(serialUrl) ?: return null
            val s = QUERY_SEASON_REGEX.find(serialUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val e = QUERY_EPISODE_REGEX.find(serialUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            Triple(sm.groupValues[1], s, e)
        }
        val payload = mapper.writeValueAsString(
            mapOf(
                "token" to token,
                "type" to "tv",
                "id" to seriesId,
                "season" to seasonNum,
                "episode" to epNum,
            ),
        )
        return ResolveRequestSpec(endpoint = endpoint, payloadJson = payload)
    }

    fun extractResolveSpecs(html: String, playerUrl: String): List<ResolveRequestSpec> {
        val specs = mutableListOf<ResolveRequestSpec>()
        extractEmbedLinksFetchSpec(html)?.let { specs += it }
        val isSerial = playerUrl.contains("/serial/")
        val legacySpec = if (isSerial) {
            extractSerialRequest(html, playerUrl)
        } else {
            extractMovieRequest(html, playerUrl)
        }
        if (legacySpec != null && specs.none { it.endpoint == legacySpec.endpoint && it.payloadJson == legacySpec.payloadJson }) {
            specs += legacySpec
        }
        return specs
    }

    fun parseApiResponse(
        jsonBody: String,
        targetSeason: Int? = null,
        targetEpisode: Int? = null,
    ): ResolvedMedia {
        val node = runCatching { mapper.readTree(jsonBody) }.getOrNull()
            ?: return ResolvedMedia(emptyList(), emptyList())
        if (!node.path("ok").asBoolean(false)) {
            return ResolvedMedia(emptyList(), emptyList())
        }
        val sources = mutableListOf<ResolvedSource>()
        val sourcesNode = node.path("sources")
        if (sourcesNode.isArray) {
            for (s in sourcesNode) {
                val u = s.path("url").asText("").trim()
                if (!u.startsWith("http://") && !u.startsWith("https://")) continue
                sources += ResolvedSource(
                    name = s.path("name").asText("Servidor"),
                    url = u,
                    version = s.path("version").asText("").takeIf { it.isNotEmpty() },
                    kind = s.path("kind").asText("").takeIf { it.isNotEmpty() },
                )
            }
        }

        // Handle PlayLatinSRC TV response: tv.seasons[].episodes[].sources[]
        val tvNode = node.path("tv")
        if (sources.isEmpty() && tvNode.isObject) {
            val tvVersion = tvNode.path("version").asText("").takeIf { it.isNotEmpty() }
            val seasons = tvNode.path("seasons")
            if (seasons.isArray) {
                val matchingSeason = if (targetSeason != null) {
                    seasons.firstOrNull { it.path("season_number").asInt(-1) == targetSeason }
                        ?: seasons.firstOrNull()
                } else {
                    seasons.firstOrNull()
                }
                val episodes = matchingSeason?.path("episodes")
                if (episodes != null && episodes.isArray) {
                    val matchingEpisode = if (targetEpisode != null) {
                        episodes.firstOrNull { it.path("episode_number").asInt(-1) == targetEpisode }
                            ?: episodes.firstOrNull()
                    } else {
                        episodes.firstOrNull()
                    }
                    val epSources = matchingEpisode?.path("sources")
                    if (epSources != null && epSources.isArray) {
                        for (s in epSources) {
                            val u = s.path("url").asText("").trim()
                            if (!u.startsWith("http://") && !u.startsWith("https://")) continue
                            sources += ResolvedSource(
                                name = s.path("name").asText("Servidor"),
                                url = u,
                                version = s.path("version").asText("").takeIf { it.isNotEmpty() } ?: tvVersion,
                                kind = s.path("kind").asText("").takeIf { it.isNotEmpty() },
                            )
                        }
                    }
                }
            }
        }

        val captions = mutableListOf<Pair<String, String>>()
        val captionsNode = node.path("captions")
        if (captionsNode.isArray) {
            for (c in captionsNode) {
                val cu = c.path("url").asText("").ifEmpty { c.path("file").asText("") }.trim()
                if (cu.startsWith("http")) {
                    captions += c.path("label").asText("Subtítulo") to cu
                }
            }
        }
        return ResolvedMedia(sources, captions)
    }

    fun normalizeVersionLabel(version: String?, fallbackUrl: String = ""): String {
        val v = version?.trim()?.lowercase().orEmpty()
        return when {
            v.contains("latino") || v == "lat" -> "Latino"
            v.contains("castellano") || v == "cas" || v == "esp" || v == "español" -> "Castellano"
            v.contains("subtitul") || v == "sub" || v.contains("vose") -> "Subtitulado"
            v.contains("ingl") || v == "eng" -> "Inglés"
            fallbackUrl.contains("lang=castellano", ignoreCase = true) -> "Castellano"
            fallbackUrl.contains("lang=subtitulado", ignoreCase = true) -> "Subtitulado"
            else -> "Latino"
        }
    }
}

internal object PackedHlsParser {
    @Suppress("DEPRECATION")
    private val permissiveMapper: ObjectMapper = ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val LINKS_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val M3U8_REGEX = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
    private val MP4_REGEX = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
    private val TRACK_OBJ_REGEX = Regex("""\{([^}]*kind\s*:\s*["'](?:captions|subtitles)["'][^}]*)\}""")
    private val FILE_REGEX = Regex("""file\s*:\s*["']([^"']+)["']""")
    private val LABEL_REGEX = Regex("""label\s*:\s*["']([^"']+)["']""")

    data class Result(
        val m3u8Urls: List<String>,
        val mp4Urls: List<String>,
        val subtitles: List<Pair<String, String>>,
    )

    fun parse(html: String, baseUrl: String): Result {
        val origin = runCatching {
            val u = URI(baseUrl)
            "${u.scheme}://${u.authority}"
        }.getOrDefault(baseUrl)
        val blocks = RePelisHDPacker.unpackAll(html) + html
        val m3u8s = linkedSetOf<String>()
        val mp4s = linkedSetOf<String>()
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
                    if (abs.contains(".mp4", ignoreCase = true)) mp4s += abs
                    else if (abs.startsWith("http")) m3u8s += abs
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
                    if (abs.contains(".mp4", ignoreCase = true)) mp4s += abs
                    else if (abs.startsWith("http")) m3u8s += abs
                }
            }

            M3U8_REGEX.findAll(block).forEach { m3u8s += it.groupValues[1] }
            MP4_REGEX.findAll(block).forEach { mp4s += it.groupValues[1] }

            for (m in TRACK_OBJ_REGEX.findAll(block)) {
                val b = m.groupValues[1]
                val f = FILE_REGEX.find(b)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (f.isEmpty() ||
                    f.contains("empty.srt", ignoreCase = true) ||
                    f.contains("_search.vtt", ignoreCase = true)
                ) continue
                val lbl = LABEL_REGEX.find(b)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (lbl.contains("search subtitle", ignoreCase = true)) continue
                val abs = runCatching { URI(baseUrl).resolve(f).toString() }.getOrElse { f }
                subs += lbl.ifEmpty { "Español" } to abs
            }
        }
        return Result(m3u8s.toList(), mp4s.toList(), subs.distinctBy { it.second })
    }
}

internal object RePelisHDVoeDecoder {
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

class RePelisHDVoeExtractor : ExtractorApi() {
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
        var currentRef = referer ?: "https://playlatinsrc.co/"
        var finalHtml = ""
        var finalUrl = url

        for (hop in 0..2) {
            val res = runCatching {
                app.get(currentUrl, headers = mapOf("user-agent" to REPELISHD_CHROME_UA, "Referer" to currentRef), timeout = 20L)
            }.getOrNull() ?: break
            if (!res.isSuccessful) break
            finalHtml = res.text
            finalUrl = res.url
            val nextUrl = RePelisHDVoeDecoder.findRedirectUrl(finalHtml)
            if (nextUrl != null && nextUrl != currentUrl) {
                currentRef = finalUrl
                currentUrl = nextUrl
                continue
            }
            break
        }

        val parsed = if (finalHtml.isNotEmpty()) RePelisHDVoeDecoder.parseEmbedHtml(finalHtml) else null
        if (parsed != null) {
            val origin = runCatching {
                val u = URI(finalUrl)
                "${u.scheme}://${u.authority}"
            }.getOrDefault(mainUrl)
            val streamHeaders = mapOf("Origin" to origin, "Referer" to "$origin/", "user-agent" to REPELISHD_CHROME_UA)
            callback(
                newExtractorLink(name, name, parsed.streamUrl, if (parsed.isHls) ExtractorLinkType.M3U8 else INFER_TYPE) {
                    this.referer = "$origin/"
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                },
            )
            emitted.set(true)
            parsed.fallbackUrl?.let { fb ->
                if (fb != parsed.streamUrl) {
                    callback(
                        newExtractorLink(name, name, fb, if (fb.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE) {
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

internal object UnlimPlayParser {
    private val mapper = ObjectMapper()
    private val FINALIZE_MARKER = "finalizePlayer("
    private val ULP_ORIG_REGEX = Regex("""var\s+_o\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)

    data class UnlimPlayEntry(
        val language: String,
        val serverName: String,
        val url: String,
    )

    private fun extractBalancedObject(text: String, startOffset: Int): String? {
        val start = text.indexOf('{', startOffset)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\' && inString) {
                escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parseEntries(html: String): List<UnlimPlayEntry> {
        val out = mutableListOf<UnlimPlayEntry>()
        val seen = linkedSetOf<Pair<String, String>>()

        fun ingestLanguageMapJson(jsonStr: String) {
            val root = runCatching { mapper.readTree(jsonStr) }.getOrNull() ?: return
            if (!root.isObject) return
            val langFields = root.fields()
            while (langFields.hasNext()) {
                val (rawLang, serversNode) = langFields.next()
                if (!serversNode.isObject) continue
                val langLabel = PlayLatinSrcParser.normalizeVersionLabel(rawLang)
                val srvFields = serversNode.fields()
                while (srvFields.hasNext()) {
                    val (srvName, urlNode) = srvFields.next()
                    val url = urlNode.asText("").trim().replace("\\/", "/")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                    if (!seen.add(langLabel to url)) continue
                    out += UnlimPlayEntry(langLabel, srvName.trim().ifEmpty { "UnlimPlay" }, url)
                }
            }
        }

        val markerIdx = html.indexOf(FINALIZE_MARKER)
        if (markerIdx >= 0) {
            extractBalancedObject(html, markerIdx + FINALIZE_MARKER.length)?.let(::ingestLanguageMapJson)
        }
        ULP_ORIG_REGEX.find(html)?.groupValues?.getOrNull(1)?.let(::ingestLanguageMapJson)
        return out
    }
}

class RePelisHDUnlimPlayExtractor : ExtractorApi() {
    override val name = "UnlimPlay"
    override val mainUrl = "https://unlimplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extractWithLanguage(url, referer, subtitleCallback) { _, link -> callback(link) }
    }

    suspend fun extractWithLanguage(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        onLink: suspend (language: String, link: ExtractorLink) -> Unit,
    ) {
        val res = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "user-agent" to REPELISHD_CHROME_UA,
                    "Referer" to (referer ?: "https://playlatinsrc.co/"),
                ),
                timeout = 20L,
            )
        }.getOrNull() ?: return
        if (!res.isSuccessful) return

        val entries = UnlimPlayParser.parseEntries(res.text)
        val unlimHeaders = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "user-agent" to REPELISHD_CHROME_UA,
        )

        for (entry in entries) {
            val prettyHost = entry.serverName.replaceFirstChar { it.uppercaseChar() }
            if (entry.url.contains("unlimplay.com/cps.php", ignoreCase = true) ||
                entry.url.contains(".m3u8", ignoreCase = true)
            ) {
                onLink(
                    entry.language,
                    newExtractorLink(source = name, name = prettyHost, url = entry.url, type = ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = unlimHeaders
                        this.quality = Qualities.P720.value
                    },
                )
            } else {
                val found = mutableListOf<ExtractorLink>()
                runCatching {
                    when {
                        entry.url.contains("voe.", ignoreCase = true) ||
                            entry.url.contains("caseyseemavoid.", ignoreCase = true) ->
                            RePelisHDVoeExtractor().getUrl(entry.url, res.url, subtitleCallback) { found.add(it) }
                        else ->
                            loadExtractor(entry.url, res.url, subtitleCallback) { found.add(it) }
                    }
                }
                for (l in found) {
                    onLink(entry.language, l)
                }
            }
        }
    }
}

open class PackedRefererHlsExtractor(
    override val name: String,
    override val mainUrl: String,
) : ExtractorApi() {
    override val requiresReferer = true

    private suspend fun pickWorkingHlsUrl(rawUrl: String, streamHeaders: Map<String, String>): String {
        val srvMatch = Regex("""[?&]srv=([a-z0-9-]+)""", RegexOption.IGNORE_CASE).find(rawUrl)
        val initialCheck = runCatching { app.get(rawUrl, headers = streamHeaders, timeout = 8L) }.getOrNull()
        if (initialCheck != null && initialCheck.isSuccessful && initialCheck.text.contains("#EXTM3U")) {
            return rawUrl
        }
        if (srvMatch != null && initialCheck != null && !initialCheck.isSuccessful) {
            val srv = srvMatch.groupValues[1]
            val parsedUri = runCatching { URI(rawUrl) }.getOrNull()
            val host = parsedUri?.host
            if (host != null && host.contains('.')) {
                val parentDomain = host.substringAfter('.')
                val candidateUrl = rawUrl.replaceFirst("://$host", "://$srv.$parentDomain")
                if (candidateUrl != rawUrl) {
                    val fbCheck = runCatching { app.get(candidateUrl, headers = streamHeaders, timeout = 8L) }.getOrNull()
                    if (fbCheck != null && fbCheck.isSuccessful && fbCheck.text.contains("#EXTM3U")) {
                        return candidateUrl
                    }
                }
            }
        }
        return rawUrl
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val reqHeaders = mapOf(
            "user-agent" to REPELISHD_CHROME_UA,
            "Referer" to (referer ?: "https://playlatinsrc.co/"),
        )
        val res = app.get(url, headers = reqHeaders, timeout = 20L)
        if (!res.isSuccessful) return
        val parsed = PackedHlsParser.parse(res.text, res.url)
        for ((lbl, subUrl) in parsed.subtitles) {
            subtitleCallback(newSubtitleFile(lbl, subUrl))
        }
        val origin = runCatching {
            val u = URI(res.url)
            "${u.scheme}://${u.authority}"
        }.getOrDefault(mainUrl)
        val originReferer = "$origin/"
        // dr0pstream/dropcdn.io returns HTTP 403 unless Origin, Referer, AND browser User-Agent are forwarded.
        val streamHeaders = mapOf(
            "Origin" to origin,
            "Referer" to originReferer,
            "user-agent" to REPELISHD_CHROME_UA,
        )
        val emitted = AtomicBoolean(false)
        for (m3u8 in parsed.m3u8Urls) {
            val workingUrl = pickWorkingHlsUrl(m3u8, streamHeaders)
            emitted.set(true)
            callback(
                newExtractorLink(source = name, name = name, url = workingUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = originReferer
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                },
            )
        }
        for (mp4 in parsed.mp4Urls) {
            emitted.set(true)
            callback(
                newExtractorLink(source = name, name = name, url = mp4, type = ExtractorLinkType.VIDEO) {
                    this.referer = originReferer
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                },
            )
        }
        if (!emitted.get()) {
            try {
                FilemoonV2().apply {
                    this.name = this@PackedRefererHlsExtractor.name
                    this.mainUrl = origin
                }.getUrl(res.url, referer, subtitleCallback, callback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }
}

class RePelisHDDr0pstreamExtractor : PackedRefererHlsExtractor("Dropload", "https://dr0pstream.com")
class RePelisHDDroploadIoExtractor : PackedRefererHlsExtractor("Dropload", "https://dropload.io")
class RePelisHDSuperplayerExtractor : PackedRefererHlsExtractor("Supervideo", "https://superplayer.ws")
class RePelisHDSupervideoExtractor : PackedRefererHlsExtractor("Supervideo", "https://supervideo.tv")
