package com.menorah.pelicinehd

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.CancellationException

internal data class EarnVidsStream(val key: String, val url: String)
internal data class EarnVidsSubtitle(val label: String, val url: String)
internal data class EarnVidsParsed(
    val finalOrigin: String,
    val streams: List<EarnVidsStream>,
    val subtitles: List<EarnVidsSubtitle>,
)

internal object EarnVidsParser {
    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PACKER_REGEX = Regex("""(?s)\}\s*\('([^'\\]*(?:\\.[^'\\]*)*)'\s*,\s*(\d+)\s*,\s*\d+\s*,\s*'([^'\\]*(?:\\.[^'\\]*)*)'\.split\('\|'\)""")
    private val LINKS_OBJECT_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_ARRAY_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val TRACKS_ARRAY_REGEX = Regex("""(?s)["']?tracks["']?\s*:\s*(\[[^\]]*\])""")
    private val DIRECT_MEDIA_REGEX = Regex("""(?i)["']((?:https?:)?//[^\s"'<>]+|/[A-Za-z0-9_./~%-]+\.(?:m3u8|txt|mp4)(?:\?[^\s"'<>]*)?)["']""")
    private val WORD_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")

    fun normalizeEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        url.contains("/f/") -> url.replace("/f/", "/v/")
        else -> url
    }

    fun originOf(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.authority}"
    }

    private fun resolveAgainstOrigin(origin: String, raw: String): String? {
        val clean = raw.trim().replace("\\/", "/")
        if (clean.isBlank()) return null
        return runCatching {
            val resolved = URI("$origin/").resolve(clean).toString()
            resolved.takeIf(PeliCineHDMarkup::validExternalUrl)
        }.getOrNull()
    }

    private fun decodeToken(token: String, radix: Int): Int? {
        if (radix < 2 || radix > ALPHABET_62.length) return null
        val allowed = ALPHABET_62.substring(0, radix)
        var acc = 0
        for (ch in token) {
            val idx = allowed.indexOf(ch)
            if (idx < 0) return null
            acc = acc * radix + idx
        }
        return acc
    }

    fun unpackAll(text: String): List<String> {
        val unpacked = mutableListOf<String>()
        for (match in PACKER_REGEX.findAll(text)) {
            val payload = match.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val radix = match.groupValues[2].toIntOrNull() ?: continue
            val words = match.groupValues[3].split('|')
            val decoded = WORD_REGEX.replace(payload) { m ->
                val idx = decodeToken(m.value, radix)
                if (idx != null && idx in words.indices && words[idx].isNotEmpty()) words[idx] else m.value
            }
            unpacked += decoded
        }
        return unpacked
    }

    fun parse(html: String, finalUrl: String): EarnVidsParsed {
        val origin = originOf(finalUrl)
        val doc = Jsoup.parse(html, finalUrl)
        val scripts = doc.select("script").map { it.data() }.filter { it.isNotBlank() }
        val candidateBlocks = mutableListOf<String>()
        for (script in scripts) {
            candidateBlocks += unpackAll(script)
            candidateBlocks += script
        }
        if (scripts.isEmpty()) {
            candidateBlocks += unpackAll(html)
            candidateBlocks += html
        }

        val linksMap = linkedMapOf<String, String>()
        val sourceUrls = mutableListOf<String>()
        val subtitles = mutableListOf<EarnVidsSubtitle>()

        for (block in candidateBlocks) {
            for (match in LINKS_OBJECT_REGEX.findAll(block)) {
                val rawObj = match.groupValues[1].replace("\\/", "/")
                val parsed = runCatching {
                    PeliCineHDMarkup.jsonMapper.readValue(
                        rawObj,
                        object : TypeReference<Map<String, String>>() {}
                    )
                }.getOrNull().orEmpty()
                for ((k, v) in parsed) {
                    val resolved = resolveAgainstOrigin(origin, v) ?: continue
                    linksMap.putIfAbsent(k.lowercase(), resolved)
                }
            }

            for (match in SOURCES_ARRAY_REGEX.findAll(block)) {
                val arrText = match.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    PeliCineHDMarkup.jsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {}
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val rawFile = entry["file"]?.toString() ?: continue
                    val resolved = resolveAgainstOrigin(origin, rawFile) ?: continue
                    if (rawFile.startsWith("http", true) || rawFile.startsWith("/")) {
                        sourceUrls += resolved
                    }
                }
            }

            for (match in TRACKS_ARRAY_REGEX.findAll(block)) {
                val arrText = match.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    PeliCineHDMarkup.jsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {}
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val kind = entry["kind"]?.toString()?.lowercase().orEmpty()
                    if (!kind.contains("caption") && !kind.contains("subtitle")) continue
                    val file = entry["file"]?.toString()?.let { resolveAgainstOrigin(origin, it) } ?: continue
                    val label = entry["label"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "Subtítulos"
                    subtitles += EarnVidsSubtitle(label, file)
                }
            }
        }

        // Priority: clean MPEG-TS `hls2` first, then `hls`, then explicit `sources`, then `hls3`, then `hls4`.
        val ordered = mutableListOf<EarnVidsStream>()
        val seenUrls = mutableSetOf<String>()
        fun addStream(key: String, u: String?) {
            if (u != null && seenUrls.add(u)) ordered += EarnVidsStream(key, u)
        }
        addStream("hls2", linksMap["hls2"])
        addStream("hls", linksMap["hls"])
        for (src in sourceUrls) addStream("source", src)
        addStream("hls3", linksMap["hls3"])
        addStream("hls4", linksMap["hls4"])
        for ((k, v) in linksMap) addStream(k, v)

        if (ordered.isEmpty()) {
            for (block in candidateBlocks) {
                for (m in DIRECT_MEDIA_REGEX.findAll(block)) {
                    val raw = m.groupValues[1]
                    if (raw.contains("get_slides", true)) continue
                    addStream("regex", resolveAgainstOrigin(origin, raw))
                }
            }
        }

        return EarnVidsParsed(
            finalOrigin = origin,
            streams = ordered,
            subtitles = subtitles.distinctBy { it.url },
        )
    }
}

class MorenciusExtractor : EarnVidsHlsExtractor() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"
}

class MinochinosExtractor : EarnVidsHlsExtractor() {
    override val name = "Minochinos"
    override val mainUrl = "https://minochinos.com"
}

class CallistaniseExtractor : EarnVidsHlsExtractor() {
    override val name = "Minochinos"
    override val mainUrl = "https://callistanise.com"
}

open class EarnVidsHlsExtractor : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, name, subtitleCallback, callback)
    }

    companion object {
        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            val target = EarnVidsParser.normalizeEmbedUrl(url)
            val headers = mapOf("User-Agent" to SiteClient.FALLBACK_CHROME_UA)
            val response = app.get(target, referer = referer, headers = headers, timeout = 15)
            if (!response.isSuccessful) return false
            return extractFromHtml(response.text, response.url, sourceName, subtitleCallback, callback)
        }

        suspend fun extractFromHtml(
            html: String,
            finalUrl: String,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            val parsed = EarnVidsParser.parse(html, finalUrl)
            if (parsed.streams.isEmpty()) return false

            for (sub in parsed.subtitles) {
                subtitleCallback(newSubtitleFile(lang = sub.label, url = sub.url))
            }

            val streamHeaders = mapOf(
                "Origin" to parsed.finalOrigin,
                "Referer" to "${parsed.finalOrigin}/",
                "User-Agent" to SiteClient.FALLBACK_CHROME_UA,
            )
            val ref = "${parsed.finalOrigin}/"

            for (stream in parsed.streams) {
                val path = runCatching { URI(stream.url).path.orEmpty().lowercase() }.getOrDefault("")
                if (path.endsWith(".mp4")) {
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = sourceName,
                            url = stream.url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = ref
                            this.headers = streamHeaders
                        }
                    )
                    return true
                }
                val generated = try {
                    M3u8Helper.generateM3u8(
                        source = sourceName,
                        streamUrl = stream.url,
                        referer = ref,
                        headers = streamHeaders,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                    return true
                }
            }
            return false
        }
    }
}
