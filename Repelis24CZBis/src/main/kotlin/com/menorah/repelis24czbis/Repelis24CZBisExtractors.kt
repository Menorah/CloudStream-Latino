package com.menorah.repelis24czbis

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

internal object Repelis24CZPacker {
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

internal object Repelis24CZVimeosParser {
    private val M3U8_REGEX = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
    private val TRACKS_BLOCK_REGEX = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_OBJ_REGEX = Regex("""\{([^}]+)\}""")
    private val TRACK_FILE_REGEX = Regex("""file\s*:\s*["']([^"']+)["']""")
    private val TRACK_LABEL_REGEX = Regex("""label\s*:\s*["']([^"']+)["']""")
    private val TRACK_KIND_REGEX = Regex("""kind\s*:\s*["']([^"']+)["']""")

    data class Result(
        val m3u8Urls: List<String>,
        val subtitles: List<Pair<String, String>>,
    )

    fun parse(html: String, baseUrl: String): Result {
        val unpacked = Repelis24CZPacker.unpackFirst(html) ?: html
        val m3u8s = M3U8_REGEX.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
        val subs = mutableListOf<Pair<String, String>>()
        val block = TRACKS_BLOCK_REGEX.find(unpacked)?.groupValues?.getOrNull(1).orEmpty()
        for (m in TRACK_OBJ_REGEX.findAll(block)) {
            val obj = m.groupValues[1]
            val kind = TRACK_KIND_REGEX.find(obj)?.groupValues?.getOrNull(1).orEmpty()
            if (kind.isNotEmpty() && !kind.equals("captions", ignoreCase = true) && !kind.equals("subtitles", ignoreCase = true)) {
                continue
            }
            val file = TRACK_FILE_REGEX.find(obj)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (file.isEmpty() || file.contains("empty.srt", ignoreCase = true) || file.contains("_search.vtt", ignoreCase = true)) {
                continue
            }
            val abs = runCatching { URI(baseUrl).resolve(file).toString() }.getOrElse { file }
            val lbl = TRACK_LABEL_REGEX.find(obj)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty { "Español" }
            if (lbl.contains("Upload captions", ignoreCase = true)) continue
            subs += lbl to abs
        }
        return Result(m3u8s, subs)
    }
}

class Repelis24CZVimeosExtractor : ExtractorApi() {
    override val name = "Vimeos"
    override val mainUrl = "https://vimeos.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://repelis24.cz/"),
        )
        val res = app.get(url, headers = headers, timeout = 20L)
        if (!res.isSuccessful) return
        val parsed = Repelis24CZVimeosParser.parse(res.text, res.url)
        for ((lbl, subUrl) in parsed.subtitles) {
            subtitleCallback(newSubtitleFile(lbl, subUrl))
        }
        for (m3u8 in parsed.m3u8Urls) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                },
            )
        }
    }
}
