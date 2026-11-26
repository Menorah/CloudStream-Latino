package com.menorah.cuevana3v

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

internal data class CuevanaMediaStream(
    val key: String,
    val url: String,
)

internal data class CuevanaMediaSubtitle(
    val label: String,
    val url: String,
)

internal data class CuevanaVidHideParsed(
    val finalOrigin: String,
    val streams: List<CuevanaMediaStream>,
    val subtitles: List<CuevanaMediaSubtitle>,
)

internal object CuevanaVidHideParser {
    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PACKER_REGEX = Regex(
        """(?s)eval\(\s*function\(p,a,c,k,e,[dr]\)\{.*?return p\}\(\s*'([^'\\]*(?:\\.[^'\\]*)*)'\s*,\s*(\d+)\s*,\s*\d+\s*,\s*'([^'\\]*(?:\\.[^'\\]*)*)'\.split\('\|'\)""",
    )
    private val LINKS_OBJECT_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_ARRAY_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val TRACKS_ARRAY_REGEX = Regex("""(?s)["']?tracks["']?\s*:\s*(\[[^\]]*\])""")
    private val DIRECT_MEDIA_REGEX = Regex(
        """(?i)["']((?:https?:)?//[^\s"'<>]+|/[A-Za-z0-9_./~%-]+\.(?:m3u8|txt|mp4)(?:\?[^\s"'<>]*)?)["']""",
    )
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
        if (clean.isEmpty()) return null
        return runCatching {
            val resolved = URI("$origin/").resolve(clean).toString()
            val scheme = URI(resolved).scheme?.lowercase()
            resolved.takeIf { scheme == "http" || scheme == "https" }
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

    fun parse(html: String, finalUrl: String): CuevanaVidHideParsed {
        val origin = originOf(finalUrl)
        val doc = Jsoup.parse(html, finalUrl)
        val scripts = doc.select("script").map { it.data() }.filter { it.isNotBlank() }
        val blocks = mutableListOf<String>()
        for (script in scripts) {
            blocks += unpackAll(script)
            blocks += script
        }
        if (scripts.isEmpty()) {
            blocks += unpackAll(html)
            blocks += html
        }

        val linksMap = linkedMapOf<String, String>()
        val sourceUrls = mutableListOf<String>()
        val subtitles = mutableListOf<CuevanaMediaSubtitle>()

        for (block in blocks) {
            for (match in LINKS_OBJECT_REGEX.findAll(block)) {
                val rawObj = match.groupValues[1].replace("\\/", "/")
                val parsed = runCatching {
                    Cuevana3vMarkup.jsonMapper.readValue(
                        rawObj,
                        object : TypeReference<Map<String, String>>() {},
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
                    Cuevana3vMarkup.jsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {},
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val rawFile = entry["file"]?.toString() ?: continue
                    if (rawFile.startsWith("http", ignoreCase = true) || rawFile.startsWith("/")) {
                        resolveAgainstOrigin(origin, rawFile)?.let { sourceUrls += it }
                    }
                }
            }

            for (match in TRACKS_ARRAY_REGEX.findAll(block)) {
                val arrText = match.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    Cuevana3vMarkup.jsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {},
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val kind = entry["kind"]?.toString()?.lowercase().orEmpty()
                    if (!kind.contains("caption") && !kind.contains("subtitle")) continue
                    val file = entry["file"]?.toString()?.let { resolveAgainstOrigin(origin, it) } ?: continue
                    val label = entry["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                    subtitles += CuevanaMediaSubtitle(label, file)
                }
            }
        }

        // Priority order verified empirically against Hyper & Nebula:
        // 1. hls2: direct CDN HLS with standard 0x47 MPEG-TS segments (HTTP 200)
        // 2. hls / explicit sources / other non-hls3/non-hls4 links
        // 3. hls4: same-origin /stream/... (carries 70-byte PNG prefix on TS packets)
        // 4. hls3: master.txt often returns HTTP 403 Forbidden
        val ordered = mutableListOf<CuevanaMediaStream>()
        val seenUrls = mutableSetOf<String>()
        fun addStream(key: String, u: String?) {
            if (u != null && seenUrls.add(u)) ordered += CuevanaMediaStream(key, u)
        }

        addStream("hls2", linksMap["hls2"])
        addStream("hls", linksMap["hls"])
        for (src in sourceUrls) addStream("source", src)
        for ((k, v) in linksMap) {
            if (k !in setOf("hls2", "hls", "hls3", "hls4")) addStream(k, v)
        }
        addStream("hls4", linksMap["hls4"])
        addStream("hls3", linksMap["hls3"])

        if (ordered.isEmpty()) {
            for (block in blocks) {
                for (m in DIRECT_MEDIA_REGEX.findAll(block)) {
                    val raw = m.groupValues[1]
                    if (raw.contains("get_slides", ignoreCase = true) ||
                        raw.contains(".jpg", ignoreCase = true) ||
                        raw.contains(".png", ignoreCase = true)
                    ) {
                        continue
                    }
                    addStream("regex", resolveAgainstOrigin(origin, raw))
                }
            }
        }

        return CuevanaVidHideParsed(
            finalOrigin = origin,
            streams = ordered,
            subtitles = subtitles.distinctBy { it.url },
        )
    }
}

class HyperVidHideExtractor : CuevanaVidHideExtractor() {
    override val name = "Hyper"
    override val mainUrl = "https://lkhjerbhye3wikhoduh5xiczuvd.lol"
}

class NebulaVidHideExtractor : CuevanaVidHideExtractor() {
    override val name = "Nebula"
    override val mainUrl = "https://vibuxer.com"
}

open class CuevanaVidHideExtractor : ExtractorApi() {
    override val name = "CuevanaVidHide"
    override val mainUrl = "https://lkhjerbhye3wikhoduh5xiczuvd.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extract(url, referer, name, subtitleCallback, callback)
    }

    companion object {
        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val target = CuevanaVidHideParser.normalizeEmbedUrl(url)
            val reqHeaders = mapOf(
                "User-Agent" to Cuevana3vMarkup.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val effectiveReferer = referer?.takeIf { it.isNotBlank() } ?: "${Cuevana3vMarkup.TUNG_URL}/"
            val response = app.get(
                target,
                referer = effectiveReferer,
                headers = reqHeaders,
                timeout = 18,
            )
            if (!response.isSuccessful) return false
            return extractFromHtml(response.text, response.url, sourceName, subtitleCallback, callback)
        }

        suspend fun extractFromHtml(
            html: String,
            finalUrl: String,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val parsed = CuevanaVidHideParser.parse(html, finalUrl)
            if (parsed.streams.isEmpty()) return false

            for (sub in parsed.subtitles) {
                subtitleCallback(newSubtitleFile(lang = sub.label, url = sub.url))
            }

            val ref = "${parsed.finalOrigin}/"
            val streamHeaders = mapOf(
                "Origin" to parsed.finalOrigin,
                "Referer" to ref,
                "User-Agent" to Cuevana3vMarkup.DEFAULT_USER_AGENT,
            )

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
                        },
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
