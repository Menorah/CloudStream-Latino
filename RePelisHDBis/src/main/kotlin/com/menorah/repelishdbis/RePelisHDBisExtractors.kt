package com.menorah.repelishdbis

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

internal object RePelisHDPacker {
    private val PACKED_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'([^']*)'\.split\('\|'\)""",
        setOf(RegexOption.DOT_MATCHES_ALL),
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

    fun unpackFirst(html: String): String? {
        val match = PACKED_REGEX.find(html) ?: return null
        val payload = match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        val radix = match.groupValues[2].toIntOrNull()?.coerceIn(2, 62) ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split('|')
        val map = HashMap<String, String>(count)
        for (i in count - 1 downTo 0) {
            val key = baseN(i, radix)
            map[key] = symtab.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
        }
        return WORD_REGEX.replace(payload) { m -> map[m.value] ?: m.value }
    }
}

internal object PlayLatinSrcParser {
    private val mapper = ObjectMapper()
    private val MOVIE_FETCH_REGEX = Regex(
        """fetch\(['"](/api/embed-links)['"][^)]*body:\s*JSON\.stringify\(\{\s*type:\s*['"]movie['"],\s*id:\s*["']([^"']+)["'],\s*token:\s*["']([^"']+)["']""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val RESOLVE_TOKEN_REGEX = Regex("""data-resolve-token=["']([^"']+)["']""")
    private val RESOLVE_ENDPOINT_REGEX = Regex("""data-resolve-endpoint=["']([^"']+)["']""")
    private val SERIAL_PATH_REGEX = Regex("""/serial/(tt\d+)/(\d+)/(\d+)""")

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

    fun extractMovieRequest(html: String): ResolveRequestSpec? {
        val m = MOVIE_FETCH_REGEX.find(html) ?: return null
        val payload = mapper.writeValueAsString(
            mapOf(
                "type" to "movie",
                "id" to m.groupValues[2],
                "token" to m.groupValues[3],
            ),
        )
        return ResolveRequestSpec(endpoint = m.groupValues[1], payloadJson = payload)
    }

    fun extractSerialRequest(html: String, serialUrl: String): ResolveRequestSpec? {
        val token = RESOLVE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val endpoint = RESOLVE_ENDPOINT_REGEX.find(html)?.groupValues?.getOrNull(1) ?: "/api/resolve"
        val pm = SERIAL_PATH_REGEX.find(serialUrl) ?: return null
        val payload = mapper.writeValueAsString(
            mapOf(
                "token" to token,
                "type" to "tv",
                "id" to pm.groupValues[1],
                "season" to pm.groupValues[2].toInt(),
                "episode" to pm.groupValues[3].toInt(),
            ),
        )
        return ResolveRequestSpec(endpoint = endpoint, payloadJson = payload)
    }

    fun parseApiResponse(jsonBody: String): ResolvedMedia {
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
}

internal object PackedHlsParser {
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
        val unpacked = RePelisHDPacker.unpackFirst(html) ?: html
        val m3u8s = M3U8_REGEX.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
        val mp4s = MP4_REGEX.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
        val subs = mutableListOf<Pair<String, String>>()
        for (m in TRACK_OBJ_REGEX.findAll(unpacked)) {
            val block = m.groupValues[1]
            val f = FILE_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (f.isEmpty() || f.contains("empty.srt", ignoreCase = true)) continue
            val abs = runCatching { URI(baseUrl).resolve(f).toString() }.getOrElse { f }
            val lbl = LABEL_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty { "Español" }
            subs += lbl to abs
        }
        return Result(m3u8s, mp4s, subs)
    }
}

open class PackedRefererHlsExtractor(
    override val name: String,
    override val mainUrl: String,
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://playlatinsrc.co/"),
        )
        val res = app.get(url, headers = headers, timeout = 20L)
        if (!res.isSuccessful) return
        val parsed = PackedHlsParser.parse(res.text, res.url)
        for ((lbl, subUrl) in parsed.subtitles) {
            subtitleCallback(newSubtitleFile(lbl, subUrl))
        }
        val originReferer = "$mainUrl/"
        for (m3u8 in parsed.m3u8Urls) {
            callback(
                newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = originReferer
                    this.quality = Qualities.P1080.value
                },
            )
        }
        for (mp4 in parsed.mp4Urls) {
            callback(
                newExtractorLink(source = name, name = name, url = mp4, type = ExtractorLinkType.VIDEO) {
                    this.referer = originReferer
                    this.quality = Qualities.P1080.value
                },
            )
        }
    }
}

class RePelisHDDr0pstreamExtractor : PackedRefererHlsExtractor("Dropload", "https://dr0pstream.com")
class RePelisHDDroploadIoExtractor : PackedRefererHlsExtractor("Dropload", "https://dropload.io")
class RePelisHDSuperplayerExtractor : PackedRefererHlsExtractor("Supervideo", "https://superplayer.ws")
class RePelisHDSupervideoExtractor : PackedRefererHlsExtractor("Supervideo", "https://supervideo.tv")
