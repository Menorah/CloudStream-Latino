package com.menorah.pelisjuanita

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Vidara
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.math.BigInteger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class PelisJuanitaVimeusEmbed(
    val server: String,
    val lang: String,
    val quality: String,
    val url: String,
)

internal data class PelisJuanitaParsedStream(
    val key: String,
    val url: String,
)

internal data class PelisJuanitaParsedSubtitle(
    val label: String,
    val url: String,
)

internal data class PelisJuanitaParsedEmbed(
    val origin: String,
    val streams: List<PelisJuanitaParsedStream>,
    val subtitles: List<PelisJuanitaParsedSubtitle>,
)

internal object PelisJuanitaVimeusParser {
    fun parseEmbeds(html: String): List<PelisJuanitaVimeusEmbed> {
        val doc = Jsoup.parse(html)
        val jsonText = doc.selectFirst("script#data")?.data()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val root = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(jsonText) }.getOrNull() ?: return emptyList()
        val embeds = root.get("embeds")
        if (embeds == null || !embeds.isArray) return emptyList()
        val out = mutableListOf<PelisJuanitaVimeusEmbed>()
        for (node in embeds) {
            val url = node.get("url")?.asText()?.trim().orEmpty()
            if (!url.startsWith("http", ignoreCase = true)) continue
            out += PelisJuanitaVimeusEmbed(
                server = node.get("server")?.asText()?.trim().orEmpty(),
                lang = node.get("lang")?.asText()?.trim().orEmpty(),
                quality = node.get("quality")?.asText()?.trim().orEmpty(),
                url = url,
            )
        }
        return out
    }
}

internal object PelisJuanitaVidHideParser {
    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PACKER_REGEX = Regex(
        """(?s)eval\(\s*function\(p,a,c,k,e,[dr]\)\{.*?return p\}\(\s*'([^'\\]*(?:\\.[^'\\]*)*)'\s*,\s*(\d+)\s*,\s*\d+\s*,\s*'([^'\\]*(?:\\.[^'\\]*)*)'\.split\('\|'\)""",
    )
    private val LINKS_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val TRACKS_REGEX = Regex("""(?s)["']?tracks["']?\s*:\s*(\[[^\]]*\])""")
    private val SINGLE_FILE_REGEX = Regex("""\bfile\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val WORD_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")
    private val MEDIA_URL_REGEX = Regex(
        """(?i)["']((?:(?:https?:)?//[^\s"'<>]+|/[A-Za-z0-9_./~%-]+)\.(?:m3u8|txt|mp4)(?:\?[^\s"'<>]*)?)["']""",
    )

    @Suppress("DEPRECATION")
    private val permissiveJsonMapper: ObjectMapper = ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

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
            unpacked += WORD_REGEX.replace(payload) { m ->
                val idx = decodeToken(m.value, radix)
                if (idx != null && idx in words.indices && words[idx].isNotEmpty()) words[idx] else m.value
            }
        }
        return unpacked
    }

    fun parse(html: String, finalUrl: String): PelisJuanitaParsedEmbed {
        val origin = originOf(finalUrl)
        val doc = Jsoup.parse(html, finalUrl)
        val scripts = doc.select("script").map { it.data() }.filter { it.isNotBlank() }
        val blocks = mutableListOf<String>()
        for (s in scripts) {
            blocks += unpackAll(s)
            blocks += s
        }
        if (scripts.isEmpty()) {
            blocks += unpackAll(html)
            blocks += html
        }

        val linksMap = linkedMapOf<String, String>()
        val sourceUrls = mutableListOf<String>()
        val subtitles = mutableListOf<PelisJuanitaParsedSubtitle>()

        for (block in blocks) {
            for (match in LINKS_REGEX.findAll(block)) {
                val rawObj = match.groupValues[1].replace("\\/", "/")
                val parsed = runCatching {
                    permissiveJsonMapper.readValue(
                        rawObj,
                        object : TypeReference<Map<String, String>>() {},
                    )
                }.getOrNull().orEmpty()
                for ((k, v) in parsed) {
                    val resolved = resolveAgainstOrigin(origin, v) ?: continue
                    linksMap.putIfAbsent(k.lowercase(), resolved)
                }
            }

            for (match in SOURCES_REGEX.findAll(block)) {
                val arrText = match.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    permissiveJsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {},
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val file = entry["file"]?.toString() ?: continue
                    if (file.startsWith("http", ignoreCase = true) || file.startsWith("/")) {
                        resolveAgainstOrigin(origin, file)?.let { sourceUrls += it }
                    }
                }
            }

            for (match in TRACKS_REGEX.findAll(block)) {
                val arrText = match.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    permissiveJsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {},
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val kind = entry["kind"]?.toString()?.lowercase().orEmpty()
                    if (!kind.contains("caption") && !kind.contains("subtitle")) continue
                    val rawFile = entry["file"]?.toString().orEmpty()
                    if (rawFile.contains("empty.srt", ignoreCase = true)) continue
                    val file = resolveAgainstOrigin(origin, rawFile) ?: continue
                    val label = entry["label"]?.toString()?.trim()?.takeIf {
                        it.isNotEmpty() && !it.equals("Upload captions", ignoreCase = true)
                    } ?: "Subtítulos"
                    subtitles += PelisJuanitaParsedSubtitle(label, file)
                }
            }

            for (match in SINGLE_FILE_REGEX.findAll(block)) {
                val candidate = match.groupValues[1].trim()
                if (candidate.startsWith("http", ignoreCase = true)) {
                    sourceUrls += candidate
                }
            }
        }

        val ordered = mutableListOf<PelisJuanitaParsedStream>()
        val seenUrls = mutableSetOf<String>()
        fun addStream(key: String, u: String?) {
            if (u != null && seenUrls.add(u)) ordered += PelisJuanitaParsedStream(key, u)
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
                for (m in MEDIA_URL_REGEX.findAll(block)) {
                    val raw = m.groupValues[1]
                    if (raw.contains("get_slides", ignoreCase = true) ||
                        raw.endsWith(".jpg", ignoreCase = true) ||
                        raw.endsWith(".png", ignoreCase = true)
                    ) {
                        continue
                    }
                    addStream("regex", resolveAgainstOrigin(origin, raw))
                }
            }
        }

        return PelisJuanitaParsedEmbed(
            origin = origin,
            streams = ordered,
            subtitles = subtitles.distinctBy { it.url },
        )
    }
}

internal object PelisJuanitaFastserverParser {
    private val SETUP_FILE_REGEX = Regex(
        """(?s)jwplayer\s*\([^)]*\)\s*\.setup\s*\(\s*\{.*?\bfile\s*:\s*["'](https?://[^"']+)["']""",
    )
    private val GENERIC_FILE_REGEX = Regex(
        """\bfile\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun parseStreamUrl(html: String): String? =
        SETUP_FILE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: GENERIC_FILE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
}

class PelisJuanitaVimeusExtractor : ExtractorApi() {
    override val name = "Vimeus"
    override val mainUrl = "https://vimeus.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
        )
        val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
        val res = app.get(url, referer = effReferer, headers = headers, timeout = 12L)
        if (!res.isSuccessful) return
        val embeds = PelisJuanitaVimeusParser.parseEmbeds(res.text)
        for (embed in embeds) {
            val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val sink: (ExtractorLink) -> Unit = { collected.add(it) }
            try {
                if (embed.url.contains("vimeos.", ignoreCase = true)) {
                    PelisJuanitaVimeosExtractor.extract(
                        embed.url,
                        res.url,
                        sourceName = name,
                        subtitleCallback = subtitleCallback,
                        callback = sink,
                    )
                } else {
                    loadExtractor(embed.url, res.url, subtitleCallback, sink)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            val parsedQuality = parseQualityHint(embed.quality)
            val snapshot = synchronized(collected) { collected.toList() }
            for (link in snapshot) {
                if (link.quality == Qualities.Unknown.value && parsedQuality != Qualities.Unknown.value) {
                    callback(
                        newExtractorLink(link.source, link.name, link.url, link.type) {
                            this.referer = link.referer
                            this.quality = parsedQuality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                            this.audioTracks = link.audioTracks
                        },
                    )
                } else {
                    callback(link)
                }
            }
        }
    }

    private fun parseQualityHint(raw: String): Int {
        val lower = raw.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") || lower.contains("full hd") || lower.contains("fullhd") -> Qualities.P1080.value
            lower.contains("720") || lower.contains("hd") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            else -> getQualityFromName(raw)
        }
    }
}

class PelisJuanitaVimeosWwwExtractor : PelisJuanitaVimeosExtractor() {
    override val mainUrl = "https://www.vimeos.net"
}

open class PelisJuanitaVimeosExtractor : ExtractorApi() {
    override val name = "Vimeos"
    override val mainUrl = "https://vimeos.net"
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
            sourceName: String = "Vimeos",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val headers = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "https://vimeus.net/"
            val res = app.get(url, referer = effReferer, headers = headers, timeout = 12L)
            if (!res.isSuccessful) return false

            val parsed = PelisJuanitaVidHideParser.parse(res.text, res.url)
            if (parsed.streams.isEmpty()) return false

            for (sub in parsed.subtitles) {
                subtitleCallback(newSubtitleFile(sub.label, sub.url))
            }

            val streamHeaders = mapOf(
                "Origin" to parsed.origin,
                "Referer" to "${parsed.origin}/",
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            )

            var emitted = false
            for (stream in parsed.streams) {
                if (stream.url.contains(".m3u8", ignoreCase = true)) {
                    val links = withTimeoutOrNull(5_000L) {
                        runCatching {
                            M3u8Helper.generateM3u8(
                                sourceName,
                                stream.url,
                                "${parsed.origin}/",
                                headers = streamHeaders,
                            )
                        }.getOrElse { emptyList() }
                    }.orEmpty()
                    if (links.isNotEmpty()) {
                        links.forEach {
                            callback(it)
                            emitted = true
                        }
                        break
                    } else {
                        callback(
                            newExtractorLink(sourceName, sourceName, stream.url, ExtractorLinkType.M3U8) {
                                this.referer = "${parsed.origin}/"
                                this.headers = streamHeaders
                                this.quality = Qualities.Unknown.value
                            },
                        )
                        emitted = true
                        break
                    }
                } else {
                    callback(
                        newExtractorLink(sourceName, sourceName, stream.url, INFER_TYPE) {
                            this.referer = "${parsed.origin}/"
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    emitted = true
                    break
                }
            }
            return emitted
        }
    }
}

class PelisJuanitaFastserverSeriesExtractor : PelisJuanitaFastserverMoviesExtractor() {
    override val mainUrl = "https://seriesplayer.fortamomar.workers.dev"
}

open class PelisJuanitaFastserverMoviesExtractor : ExtractorApi() {
    override val name = "Fastserver"
    override val mainUrl = "https://pelisplayer.fortamomar.workers.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
        )
        val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
        val res = app.get(url, referer = effReferer, headers = headers, timeout = 12L)
        if (!res.isSuccessful) return

        val streamUrl = PelisJuanitaFastserverParser.parseStreamUrl(res.text) ?: return
        val origin = runCatching { PelisJuanitaVidHideParser.originOf(res.url) }.getOrDefault(mainUrl)
        val streamHeaders = mapOf(
            "Origin" to origin,
            "Referer" to "$origin/",
            "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
        )
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            val links = withTimeoutOrNull(5_000L) {
                runCatching {
                    M3u8Helper.generateM3u8(
                        name,
                        streamUrl,
                        "$origin/",
                        headers = streamHeaders,
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()
            if (links.isNotEmpty()) {
                links.forEach(callback)
                return
            }
            callback(
                newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                },
            )
        } else {
            callback(
                newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$origin/"
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                },
            )
        }
    }
}

class PelisJuanitaLplaygoExtractor : PelisJuanitaSavefilesExtractor() {
    override val name = "Lulustream"
    override val mainUrl = "https://lplaygo.com"
}

open class PelisJuanitaSavefilesExtractor : LuluStream() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fileCode = runCatching {
            URI(url).rawPath.orEmpty().trimEnd('/').substringAfterLast('/').removeSuffix(".html").substringAfterLast('-')
        }.getOrDefault(url.substringAfterLast("/").removeSuffix(".html").substringAfterLast('-'))
        if (fileCode.isBlank()) return

        val origOrigin = runCatching { PelisJuanitaVidHideParser.originOf(url) }.getOrDefault(mainUrl)
        val postOrigins = buildList {
            if (url.contains("lulustream.", ignoreCase = true) || url.contains("lplaygo.", ignoreCase = true)) {
                add("https://lplaygo.com")
                add("https://lulustream.com")
            }
            if (!contains(origOrigin)) add(origOrigin)
        }.distinct()
        val headers = mapOf(
            "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
        )
        val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"

        suspend fun emitParsed(parsed: PelisJuanitaParsedEmbed): Boolean {
            if (parsed.streams.isEmpty()) return false
            for (sub in parsed.subtitles) {
                subtitleCallback(newSubtitleFile(sub.label, sub.url))
            }
            val streamHeaders = mapOf(
                "Origin" to parsed.origin,
                "Referer" to "${parsed.origin}/",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            )
            for (stream in parsed.streams) {
                if (stream.url.contains(".m3u8", ignoreCase = true)) {
                    val links = withTimeoutOrNull(4_500L) {
                        runCatching {
                            M3u8Helper.generateM3u8(name, stream.url, "${parsed.origin}/", headers = streamHeaders)
                        }.getOrElse { emptyList() }
                    }.orEmpty()
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        return true
                    }
                    callback(
                        newExtractorLink(name, name, stream.url, ExtractorLinkType.M3U8) {
                            this.referer = "${parsed.origin}/"
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    return true
                }
                callback(
                    newExtractorLink(name, name, stream.url, INFER_TYPE) {
                        this.referer = "${parsed.origin}/"
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    },
                )
                return true
            }
            return false
        }

        var lastPostText: String? = null
        var lastPostUrl: String = url
        for (postOrigin in postOrigins) {
            val postRes = runCatching {
                app.post(
                    "$postOrigin/dl",
                    referer = "$postOrigin/e/$fileCode",
                    headers = headers + ("Origin" to postOrigin),
                    data = mapOf(
                        "op" to "embed",
                        "file_code" to fileCode,
                        "auto" to "1",
                        "referer" to effReferer,
                    ),
                    timeout = 8L,
                )
            }.getOrNull() ?: continue
            lastPostText = postRes.text
            lastPostUrl = postRes.url
            val parsed = PelisJuanitaVidHideParser.parse(postRes.text, postRes.url)
            if (emitParsed(parsed)) return
        }

        if (!lastPostText.isNullOrBlank()) {
            val doc = Jsoup.parse(lastPostText, lastPostUrl)
            val scriptData = doc.selectFirst("script:containsData(vplayer)")?.data()
                ?: doc.select("script").firstOrNull { it.data().contains("sources:") || it.data().contains("jwplayer(") }?.data()
            if (!scriptData.isNullOrBlank()) {
                var emitted = false
                JwPlayerHelper.extractStreamLinks(
                    scriptData,
                    name,
                    mainUrl,
                    { emitted = true; callback(it) },
                    subtitleCallback,
                )
                if (emitted) return
            }
        }

        val getRes = runCatching { app.get(url, referer = effReferer, headers = headers, timeout = 8L) }.getOrNull()
        if (getRes != null && getRes.isSuccessful) {
            val parsedGet = PelisJuanitaVidHideParser.parse(getRes.text, getRes.url)
            if (emitParsed(parsedGet)) return
        }

        runCatching { super.getUrl(url, referer, subtitleCallback, callback) }
    }
}

internal data class PelisJuanitaByseSource(
    val url: String,
    val height: Int,
    val bitrateKbps: Int,
    val label: String?,
)

internal data class PelisJuanitaBysePlaybackData(
    val sources: List<PelisJuanitaByseSource>,
    val tracks: List<PelisJuanitaParsedSubtitle>,
)

internal object PelisJuanitaBysePow {
    fun hashNonce(input: ByteArray): IntArray {
        val e = intArrayOf(
            0x6a09e667,
            0xbb67ae85.toInt(),
            0x3c6ef372,
            0xa54ff53a.toInt(),
        )

        fun mix() {
            e[0] = e[0] + e[1]; e[3] = (e[3] xor e[0]).rotateLeft(16)
            e[2] = e[2] + e[3]; e[1] = (e[1] xor e[2]).rotateLeft(12)
            e[0] = e[0] + e[1]; e[3] = (e[3] xor e[0]).rotateLeft(8)
            e[2] = e[2] + e[3]; e[1] = (e[1] xor e[2]).rotateLeft(7)
        }

        for (b in input) {
            e[0] = (e[0] + (b.toInt() and 0xFF)).rotateLeft(7)
            mix()
        }
        repeat(8) { mix() }

        val r = IntArray(512)
        for (i in 0 until 512) {
            mix()
            r[i] = e[0] xor e[2]
        }

        val mult1 = 0x9e3779b1.toInt() // 2654435761
        val mult2 = 0x85ebca77.toInt() // 2246822519
        repeat(2) {
            for (s in 0 until 512) {
                val a = r[s] and 511
                var c = (r[s] + r[a]).rotateLeft(13)
                c = c xor (r[(s + 1) and 511] * mult1)
                r[s] = c
                e[0] = e[0] xor c
                mix()
            }
        }

        val out = IntArray(8)
        for (i in 0 until 8) {
            mix()
            var s = e[0]
            val base = i * 64
            for (c in 0 until 64) {
                val d = r[base + c]
                s = (s + d).rotateLeft(5)
                s = s xor (d * mult2)
            }
            out[i] = s xor e[2]
        }
        return out
    }

    fun countLeadingZeroBits(words: IntArray): Int {
        var bits = 0
        for (w in words) {
            if (w == 0) {
                bits += 32
            } else {
                return bits + Integer.numberOfLeadingZeros(w)
            }
        }
        return bits
    }

    fun solveBlocking(nonce: String, difficulty: Int, maxAttempts: Int = 500_000): String? {
        val target = difficulty.coerceIn(1, 28)
        val prefix = "$nonce:".toByteArray(StandardCharsets.UTF_8)
        val digits = ByteArray(10)
        for (attempt in 0 until maxAttempts) {
            var n = attempt
            var len = 0
            if (n == 0) {
                digits[0] = '0'.code.toByte()
                len = 1
            } else {
                while (n > 0) {
                    digits[len++] = ('0'.code + (n % 10)).toByte()
                    n /= 10
                }
            }
            val buf = ByteArray(prefix.size + len)
            System.arraycopy(prefix, 0, buf, 0, prefix.size)
            for (i in 0 until len) {
                buf[prefix.size + i] = digits[len - 1 - i]
            }
            if (countLeadingZeroBits(hashNonce(buf)) >= target) {
                return attempt.toString()
            }
        }
        return null
    }

    suspend fun solve(nonce: String, difficulty: Int, maxAttempts: Int = 500_000): String? =
        withContext(Dispatchers.Default) {
            solveBlocking(nonce, difficulty, maxAttempts)
        }
}

internal object PelisJuanitaByseCrypto {
    fun base64UrlNoPad(bytes: ByteArray): String =
        PelisJuanitaCrypto.encodeBase64(bytes)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')

    fun ecKeyPairP256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    fun toFixedUnsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    fun toJwkPublicKey(pub: ECPublicKey): Map<String, String> = mapOf(
        "kty" to "EC",
        "crv" to "P-256",
        "x" to base64UrlNoPad(toFixedUnsigned32(pub.w.affineX)),
        "y" to base64UrlNoPad(toFixedUnsigned32(pub.w.affineY)),
    )

    fun derSignatureToP1363(der: ByteArray, componentSize: Int = 32): ByteArray {
        require(der.size >= 8 && der[0].toInt() == 0x30) { "Invalid DER ECDSA signature" }
        var pos = 1
        val seqLenByte = der[pos++].toInt() and 0xFF
        if ((seqLenByte and 0x80) != 0) {
            pos += (seqLenByte and 0x7F)
        }
        require(pos < der.size && der[pos++].toInt() == 0x02) { "Missing DER R integer" }
        val rLen = der[pos++].toInt() and 0xFF
        val rBytes = der.copyOfRange(pos, pos + rLen)
        pos += rLen
        require(pos < der.size && der[pos++].toInt() == 0x02) { "Missing DER S integer" }
        val sLen = der[pos++].toInt() and 0xFF
        val sBytes = der.copyOfRange(pos, pos + sLen)

        val out = ByteArray(componentSize * 2)
        fun copyUnsigned(src: ByteArray, dstOffset: Int) {
            var start = 0
            while (start < src.size - 1 && src[start].toInt() == 0) start++
            val len = src.size - start
            val copyLen = minOf(len, componentSize)
            System.arraycopy(src, start + (len - copyLen), out, dstOffset + (componentSize - copyLen), copyLen)
        }
        copyUnsigned(rBytes, 0)
        copyUnsigned(sBytes, componentSize)
        return out
    }

    fun signChallengeNonce(nonce: String, keyPair: KeyPair): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(nonce.toByteArray(StandardCharsets.UTF_8))
        return base64UrlNoPad(derSignatureToP1363(sig.sign()))
    }

    private fun resolveAesKeyBytes(keyParts: List<String>, version: String?): ByteArray? {
        if (keyParts.size < 2) return null
        val verNum = version?.trim()?.toIntOrNull()
        if (verNum != null && verNum in 1..20) {
            val idx1 = verNum - 1
            val idx2 = 30 - verNum
            if (idx1 in keyParts.indices && idx2 in keyParts.indices) {
                return PelisJuanitaCrypto.decodeBase64Flexible(keyParts[idx1]) +
                    PelisJuanitaCrypto.decodeBase64Flexible(keyParts[idx2])
            }
        }
        val combined = java.io.ByteArrayOutputStream()
        for (part in keyParts.take(2)) {
            combined.write(PelisJuanitaCrypto.decodeBase64Flexible(part))
        }
        return combined.toByteArray()
    }

    fun decryptPlaybackDetails(
        ivB64Url: String,
        payloadB64Url: String,
        keyParts: List<String>,
        version: String? = null,
    ): PelisJuanitaBysePlaybackData? {
        val keyBytes = resolveAesKeyBytes(keyParts, version) ?: return null
        val ivBytes = PelisJuanitaCrypto.decodeBase64Flexible(ivB64Url)
        val cipherBytes = PelisJuanitaCrypto.decodeBase64Flexible(payloadB64Url)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
        var plain = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (plain.startsWith("\uFEFF")) plain = plain.substring(1)
        val root = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(plain) }.getOrNull() ?: return null
        val sourcesNode = root.get("sources") ?: return null
        if (!sourcesNode.isArray) return null

        val parsedSources = mutableListOf<PelisJuanitaByseSource>()
        for (node in sourcesNode) {
            val u = node.get("url")?.asText()?.trim().orEmpty()
            if (!u.startsWith("http", ignoreCase = true)) continue
            parsedSources += PelisJuanitaByseSource(
                url = u,
                height = node.get("height")?.asInt(0) ?: 0,
                bitrateKbps = node.get("bitrate_kbps")?.asInt(0) ?: node.get("bitrate")?.asInt(0) ?: 0,
                label = node.get("label")?.asText()?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        if (parsedSources.isEmpty()) return null
        val orderedSources = parsedSources.sortedWith(
            compareByDescending<PelisJuanitaByseSource> { it.height }
                .thenByDescending { it.bitrateKbps },
        )

        val tracks = mutableListOf<PelisJuanitaParsedSubtitle>()
        val tracksNode = root.get("tracks") ?: root.get("subtitles")
        if (tracksNode != null && tracksNode.isArray) {
            for (sub in tracksNode) {
                val subUrl = (sub.get("url")?.asText() ?: sub.get("file")?.asText())?.trim().orEmpty()
                if (!subUrl.startsWith("http", ignoreCase = true)) continue
                val subLabel = (sub.get("label")?.asText() ?: sub.get("language")?.asText() ?: sub.get("title")?.asText())
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                tracks += PelisJuanitaParsedSubtitle(label = subLabel, url = subUrl)
            }
        }

        return PelisJuanitaBysePlaybackData(sources = orderedSources, tracks = tracks)
    }

    fun decryptPlayback(
        ivB64Url: String,
        payloadB64Url: String,
        keyParts: List<String>,
        version: String? = null,
    ): String? = decryptPlaybackDetails(ivB64Url, payloadB64Url, keyParts, version)?.sources?.firstOrNull()?.url
}

open class PelisJuanitaByseExtractor : ByseSX() {
    override val name = "Byse"
    override val mainUrl = "https://bysesukior.com"

    private data class AttestedOriginState(
        val keyPair: KeyPair,
        val fingerprint: Map<String, Any?>,
        val cookies: MutableMap<String, String>,
        val attestedAtMs: Long,
    )

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val originStates = ConcurrentHashMap<String, AttestedOriginState>()
        private val originMutexes = ConcurrentHashMap<String, Mutex>()
        private const val ATTEST_TTL_MS = 20 * 60_000L

        private fun jsonBody(payload: Any) =
            PelisJuanitaMarkup.jsonMapper.writeValueAsString(payload).toRequestBody(JSON_MEDIA)

        private suspend fun getOrCreateAttestedState(
            origin: String,
            referer: String,
            forceRefresh: Boolean = false,
        ): AttestedOriginState? {
            val existing = originStates[origin]
            if (!forceRefresh && existing != null && (System.currentTimeMillis() - existing.attestedAtMs) < ATTEST_TTL_MS) {
                return existing
            }
            val mutex = originMutexes.getOrPut(origin) { Mutex() }
            return mutex.withLock {
                val rechecked = originStates[origin]
                if (!forceRefresh && rechecked != null && (System.currentTimeMillis() - rechecked.attestedAtMs) < ATTEST_TTL_MS) {
                    return@withLock rechecked
                }
                val cookies = if (forceRefresh) linkedMapOf() else rechecked?.cookies?.toMutableMap() ?: linkedMapOf()
                val keyPair = if (forceRefresh) PelisJuanitaByseCrypto.ecKeyPairP256() else rechecked?.keyPair ?: PelisJuanitaByseCrypto.ecKeyPairP256()
                val baseHeaders = mapOf(
                    "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                    "Accept" to "application/json, */*",
                    "Origin" to origin,
                    "Referer" to referer,
                )

                val chalRes = app.post(
                    "$origin/api/videos/access/challenge",
                    headers = baseHeaders,
                    cookies = cookies,
                    requestBody = jsonBody(emptyMap<String, Any>()),
                    timeout = 12L,
                )
                cookies.putAll(chalRes.cookies)
                if (!chalRes.isSuccessful) return@withLock null
                val chalNode = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(chalRes.text) }.getOrNull() ?: return@withLock null
                val challengeId = chalNode.get("challenge_id")?.asText()?.trim().orEmpty()
                val nonce = chalNode.get("nonce")?.asText()?.trim().orEmpty()
                if (challengeId.isEmpty() || nonce.isEmpty()) return@withLock null

                val signature = PelisJuanitaByseCrypto.signChallengeNonce(nonce, keyPair)
                val pubJwk = PelisJuanitaByseCrypto.toJwkPublicKey(keyPair.public as ECPublicKey)
                val attestPayload = linkedMapOf<String, Any?>(
                    "viewer_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("viewer_id"),
                    "device_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("device_id"),
                    "challenge_id" to challengeId,
                    "nonce" to nonce,
                    "signature" to signature,
                    "public_key" to pubJwk,
                    "client" to mapOf(
                        "user_agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                        "platform" to "Linux armv8l",
                        "languages" to listOf("es-MX", "es"),
                        "timezone" to "America/Mexico_City",
                    ),
                    "storage" to emptyMap<String, Any>(),
                    "attributes" to mapOf("entropy" to "low"),
                )
                val attestRes = app.post(
                    "$origin/api/videos/access/attest",
                    headers = baseHeaders,
                    cookies = cookies,
                    requestBody = jsonBody(attestPayload),
                    timeout = 12L,
                )
                cookies.putAll(attestRes.cookies)
                if (!attestRes.isSuccessful) return@withLock null

                val attNode = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(attestRes.text) }.getOrNull() ?: return@withLock null
                val viewerId = attNode.get("viewer_id")?.asText()?.trim().orEmpty()
                val deviceId = attNode.get("device_id")?.asText()?.trim().orEmpty()
                val token = attNode.get("token")?.asText()?.trim().orEmpty()
                if (viewerId.isEmpty() || token.isEmpty()) return@withLock null

                val fp = linkedMapOf<String, Any?>(
                    "viewer_id" to viewerId,
                    "device_id" to deviceId.ifEmpty { viewerId },
                    "token" to token,
                    "confidence" to (attNode.get("confidence")?.asDouble(0.8) ?: 0.8),
                )
                val state = AttestedOriginState(
                    keyPair = keyPair,
                    fingerprint = fp,
                    cookies = Collections.synchronizedMap(cookies),
                    attestedAtMs = System.currentTimeMillis(),
                )
                originStates[origin] = state
                state
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = runCatching { PelisJuanitaVidHideParser.originOf(url) }.getOrDefault(mainUrl)
        val code = runCatching {
            URI(url).rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
        }.getOrDefault(url.substringAfterLast('/'))

        if (code.isNotBlank()) {
            val baseHeaders = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "*/*",
            )
            val detailsRes = runCatching {
                app.get("$base/api/videos/$code/embed/details", referer = url, headers = baseHeaders, timeout = 10L)
            }.getOrNull()?.takeIf { it.isSuccessful } ?: runCatching {
                app.get("$base/api/videos/$code/details", referer = url, headers = baseHeaders, timeout = 10L)
            }.getOrNull()?.takeIf { it.isSuccessful }

            val embedFrameUrl = detailsRes?.text?.let { raw ->
                runCatching { PelisJuanitaMarkup.jsonMapper.readTree(raw).get("embed_frame_url")?.asText()?.trim() }.getOrNull()
            }?.takeIf { it.startsWith("http", ignoreCase = true) }

            val targetPairs = buildList {
                if (!embedFrameUrl.isNullOrBlank()) {
                    val fOrigin = runCatching { PelisJuanitaVidHideParser.originOf(embedFrameUrl) }.getOrDefault(base)
                    val fCode = runCatching { URI(embedFrameUrl).rawPath.orEmpty().trimEnd('/').substringAfterLast('/') }.getOrDefault(code)
                    add(Triple(fOrigin, fCode, embedFrameUrl))
                }
                if (embedFrameUrl == null || !embedFrameUrl.startsWith(base, ignoreCase = true)) {
                    add(Triple(base, code, url))
                }
            }

            for ((targetOrigin, targetCode, targetRef) in targetPairs) {
                val effRef = targetRef.ifBlank { "$targetOrigin/" }
                val wasCached = originStates[targetOrigin]?.let {
                    (System.currentTimeMillis() - it.attestedAtMs) < ATTEST_TTL_MS
                } == true
                val state = getOrCreateAttestedState(targetOrigin, effRef) ?: continue

                suspend fun requestPlayback(activeState: AttestedOriginState, isEmbedMode: Boolean): PelisJuanitaBysePlaybackData? {
                    val prefix = if (isEmbedMode) "embed/" else ""
                    val embedParent = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
                    val embedParentOrigin = runCatching { PelisJuanitaVidHideParser.originOf(embedParent) }.getOrDefault(PelisJuanitaMarkup.MAIN_URL)
                    val pbHeaders = linkedMapOf(
                        "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                        "Accept" to "application/json, */*",
                        "Origin" to targetOrigin,
                        "Referer" to effRef,
                    ).apply {
                        if (isEmbedMode) {
                            put("X-Embed-Origin", URI(embedParentOrigin).host ?: "pelisjuanita.com")
                            put("X-Embed-Referer", embedParent)
                            put("X-Embed-Parent", embedParent)
                        }
                    }

                    val bodyMap = mapOf("fingerprint" to activeState.fingerprint)
                    var pbRes = app.post(
                        "$targetOrigin/api/videos/$targetCode/${prefix}playback",
                        headers = pbHeaders,
                        cookies = activeState.cookies,
                        requestBody = jsonBody(bodyMap),
                        timeout = 12L,
                    )
                    activeState.cookies.putAll(pbRes.cookies)

                    if (pbRes.code == 428 || pbRes.text.contains("captcha_required", ignoreCase = true)) {
                        val capRes = app.post(
                            "$targetOrigin/api/videos/$targetCode/${prefix}captcha",
                            headers = pbHeaders,
                            cookies = activeState.cookies,
                            requestBody = jsonBody(bodyMap),
                            timeout = 12L,
                        )
                        activeState.cookies.putAll(capRes.cookies)
                        if (!capRes.isSuccessful) return null

                        val capNode = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(capRes.text) }.getOrNull() ?: return null
                        val powNonce = capNode.get("pow_nonce")?.asText().orEmpty()
                        val powDiff = capNode.get("pow_difficulty")?.asInt(12) ?: 12
                        val powToken = capNode.get("pow_token")?.asText().orEmpty()
                        if (powNonce.isEmpty() || powToken.isEmpty()) return null

                        val solution = PelisJuanitaBysePow.solve(powNonce, powDiff) ?: return null
                        val verifyPayload = mapOf(
                            "pow_token" to powToken,
                            "solution" to solution,
                            "fingerprint" to activeState.fingerprint,
                        )
                        val verRes = app.post(
                            "$targetOrigin/api/videos/$targetCode/${prefix}captcha/verify",
                            headers = pbHeaders,
                            cookies = activeState.cookies,
                            requestBody = jsonBody(verifyPayload),
                            timeout = 12L,
                        )
                        activeState.cookies.putAll(verRes.cookies)
                        if (!verRes.isSuccessful) return null

                        val captchaToken = runCatching {
                            PelisJuanitaMarkup.jsonMapper.readTree(verRes.text).get("token")?.asText()?.trim()
                        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

                        pbRes = app.post(
                            "$targetOrigin/api/videos/$targetCode/${prefix}playback",
                            headers = pbHeaders + ("X-Captcha-Token" to captchaToken),
                            cookies = activeState.cookies,
                            requestBody = jsonBody(bodyMap),
                            timeout = 12L,
                        )
                        activeState.cookies.putAll(pbRes.cookies)
                    }

                    if (!pbRes.isSuccessful) return null
                    val root = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(pbRes.text) }.getOrNull() ?: return null
                    val playback = root.get("playback") ?: return null
                    val iv = playback.get("iv")?.asText().orEmpty()
                    val payload = playback.get("payload")?.asText().orEmpty()
                    val version = playback.get("version")?.asText()
                    val keyParts = playback.get("key_parts")?.mapNotNull { it.asText() }.orEmpty()
                    return runCatching {
                        PelisJuanitaByseCrypto.decryptPlaybackDetails(iv, payload, keyParts, version)
                    }.getOrNull()
                }

                // Prefer watch-mode /playback (avoids embed domain allowlist 403), then fall back to /embed/playback for private embeds.
                var decrypted = runCatching { requestPlayback(state, isEmbedMode = false) }.getOrNull()
                    ?: runCatching { requestPlayback(state, isEmbedMode = true) }.getOrNull()
                if (decrypted == null && wasCached) {
                    originStates.remove(targetOrigin, state)
                    val refreshed = getOrCreateAttestedState(targetOrigin, effRef, forceRefresh = true)
                    if (refreshed != null) {
                        decrypted = runCatching { requestPlayback(refreshed, isEmbedMode = false) }.getOrNull()
                            ?: runCatching { requestPlayback(refreshed, isEmbedMode = true) }.getOrNull()
                    }
                }

                if (decrypted != null && decrypted.sources.isNotEmpty()) {
                    for (sub in decrypted.tracks) {
                        subtitleCallback(newSubtitleFile(sub.label, sub.url))
                    }
                    val streamHeaders = mapOf(
                        "Origin" to targetOrigin,
                        "Referer" to "$targetOrigin/",
                        "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                    )
                    var emittedAny = false
                    for (src in decrypted.sources) {
                        val isM3u8 = src.url.contains(".m3u8", ignoreCase = true) ||
                            src.url.contains("/hls/", ignoreCase = true) ||
                            src.url.contains("master", ignoreCase = true)
                        if (isM3u8) {
                            val links = withTimeoutOrNull(4_500L) {
                                runCatching {
                                    M3u8Helper.generateM3u8(name, src.url, "$targetOrigin/", headers = streamHeaders)
                                }.getOrElse { emptyList() }
                            }.orEmpty()
                            if (links.isNotEmpty()) {
                                links.forEach { callback(it); emittedAny = true }
                                break
                            }
                        }
                        val fallbackQuality = if (src.height > 0) src.height else getQualityFromName(src.label)
                        callback(
                            newExtractorLink(
                                name,
                                src.label?.let { "$name ($it)" } ?: name,
                                src.url,
                                if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE,
                            ) {
                                this.referer = "$targetOrigin/"
                                this.headers = streamHeaders
                                this.quality = fallbackQuality
                            },
                        )
                        emittedAny = true
                        break
                    }
                    if (emittedAny) return
                }
            }
        }

        try {
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

class PelisJuanitaBysejikuarExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysejikuar.com"
}

class PelisJuanitaByseSxExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://byse.sx"
}

class PelisJuanitaByseBuhoExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysebuho.com"
}

class PelisJuanitaByseZejataosExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysezejataos.com"
}

class PelisJuanitaByseVepoinExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysevepoin.com"
}

class PelisJuanitaByseQekahoExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://byseqekaho.com"
}

class PelisJuanitaN1mwqExtractor : PelisJuanitaByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://n1mwq.org"
}

open class PelisJuanitaMixDropTopExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.top"
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
        private val MIRRORS = listOf(
            "https://mxdrop.click",
            "https://mixdrop.top",
            "https://mixdrop.co",
            "https://mixdrop.ag",
            "https://mixdrop.ps",
            "https://mixdrop.si",
        )
        private val WURL_REGEX = Regex("""(?:MDCore\.)?wurl\s*=\s*["']([^"']+)["']""")
        private val SUB_REGEX = Regex("""(?:MDCore\.)?sub\s*=\s*["']([^"']+)["']""")

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "MixDrop",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val embedUrl = url.replace("/f/", "/e/", ignoreCase = true)
            val parsedUri = runCatching { URI(embedUrl) }.getOrNull()
            val pathWithQuery = buildString {
                append(parsedUri?.rawPath?.ifEmpty { "/" } ?: "/")
                parsedUri?.rawQuery?.takeIf { it.isNotEmpty() }?.let { append("?$it") }
            }
            val origOrigin = runCatching { PelisJuanitaVidHideParser.originOf(embedUrl) }.getOrNull()
            val candidateUrls = buildList {
                add(embedUrl)
                for (mirror in MIRRORS) {
                    if (!mirror.equals(origOrigin, ignoreCase = true)) {
                        add("$mirror$pathWithQuery")
                    }
                }
            }
            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
            val headers = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )

            for (candidate in candidateUrls) {
                val res = try {
                    app.get(candidate, referer = effReferer, headers = headers, timeout = 6L)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (!res.isSuccessful) continue

                val csUnpacked = runCatching { getAndUnpack(res.text) }.getOrNull()?.takeIf { it != res.text }
                val sourcesToScan: List<String> = listOfNotNull(csUnpacked) + PelisJuanitaVidHideParser.unpackAll(res.text) + res.text
                val rawWurl = sourcesToScan.firstNotNullOfOrNull { text ->
                    WURL_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                } ?: continue
                val videoUrl = when {
                    rawWurl.startsWith("//") -> "https:$rawWurl"
                    rawWurl.startsWith("http", ignoreCase = true) -> rawWurl
                    else -> continue
                }
                val resolvedOrigin = runCatching { PelisJuanitaVidHideParser.originOf(res.url) }.getOrDefault(mainUrlFallback(candidate))
                val rawSub = sourcesToScan.firstNotNullOfOrNull { text ->
                    SUB_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (rawSub != null) {
                    val subUrl = when {
                        rawSub.startsWith("//") -> "https:$rawSub"
                        rawSub.startsWith("http", ignoreCase = true) -> rawSub
                        else -> null
                    }
                    if (subUrl != null) subtitleCallback(newSubtitleFile("Subtítulos", subUrl))
                }
                callback(
                    newExtractorLink(sourceName, sourceName, videoUrl, INFER_TYPE) {
                        this.referer = "$resolvedOrigin/"
                        this.headers = mapOf(
                            "Referer" to "$resolvedOrigin/",
                            "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                        )
                        this.quality = Qualities.Unknown.value
                    },
                )
                return true
            }
            return false
        }

        private fun mainUrlFallback(url: String): String =
            runCatching { PelisJuanitaVidHideParser.originOf(url) }.getOrDefault("https://mxdrop.click")
    }
}

class PelisJuanitaMxDropClickExtractor : PelisJuanitaMixDropTopExtractor() {
    override val name = "MixDrop"
    override val mainUrl = "https://mxdrop.click"
}

class PelisJuanitaMixDropAgExtractor : PelisJuanitaMixDropTopExtractor() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.ag"
}

class PelisJuanitaMixDropCoExtractor : PelisJuanitaMixDropTopExtractor() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
}

class PelisJuanitaMixDropPsExtractor : PelisJuanitaMixDropTopExtractor() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.ps"
}

class PelisJuanitaOisauhExtractor : PelisJuanitaVidaraExtractor() {
    override val mainUrl = "https://oisauh.com"
}

open class PelisJuanitaVidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extract(url, referer, name, subtitleCallback, callback)
    }

    companion object {
        private val MIRROR_ORIGINS = listOf("https://vidara.to", "https://oisauh.com")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "Vidara",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val fileCode = runCatching {
                URI(url).rawPath.orEmpty().trimEnd('/').substringAfterLast('/').substringBefore('?')
            }.getOrDefault(url.substringAfterLast('/').substringBefore('?'))
            if (fileCode.isBlank()) return false

            val urlOrigin = runCatching { PelisJuanitaVidHideParser.originOf(url) }.getOrNull()
            val candidateOrigins = buildList {
                if (!urlOrigin.isNullOrBlank()) add(urlOrigin)
                MIRROR_ORIGINS.forEach { if (!it.equals(urlOrigin, ignoreCase = true)) add(it) }
            }.distinct()

            val payload = PelisJuanitaMarkup.jsonMapper.writeValueAsString(
                mapOf("filecode" to fileCode, "device" to "desktop"),
            )
            val baseHeaders = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
            )

            for (origin in candidateOrigins) {
                val res = try {
                    app.post(
                        "$origin/api/stream",
                        referer = url,
                        headers = baseHeaders + ("Origin" to origin),
                        requestBody = payload.toRequestBody(JSON_MEDIA),
                        timeout = 8L,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (!res.isSuccessful) continue

                val root = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(res.text) }.getOrNull() ?: continue
                val streamUrl = root.get("streaming_url")?.asText()?.trim()
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: continue

                val subs = root.get("subtitles")
                if (subs != null && subs.isArray) {
                    for (sub in subs) {
                        val subUrl = sub.get("file_path")?.asText()?.trim()
                            ?: sub.get("url")?.asText()?.trim()
                            ?: sub.get("file")?.asText()?.trim()
                            ?: continue
                        val subLang = sub.get("language")?.asText()?.trim()
                            ?: sub.get("label")?.asText()?.trim()
                            ?: "Subtítulos"
                        if (subUrl.startsWith("http", ignoreCase = true)) {
                            subtitleCallback(newSubtitleFile(subLang, subUrl))
                        }
                    }
                }

                val streamHeaders = mapOf(
                    "Origin" to origin,
                    "Referer" to "$origin/",
                    "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                )
                val links = withTimeoutOrNull(4_500L) {
                    runCatching {
                        M3u8Helper.generateM3u8(sourceName, streamUrl, "$origin/", headers = streamHeaders)
                    }.getOrElse { emptyList() }
                }.orEmpty()
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                } else {
                    callback(
                        newExtractorLink(sourceName, sourceName, streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = "$origin/"
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        },
                    )
                }
                return true
            }

            var fallbackOk = false
            runCatching {
                Vidara().getUrl(url, referer, subtitleCallback) { link ->
                    fallbackOk = true
                    callback(link)
                }
            }
            return fallbackOk
        }
    }
}

internal object PelisJuanitaCrypto {
    private val SHA256_H0 = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    private val SHA256_K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    private class Pbkdf2Sha256Scratch {
        val keyInts = IntArray(16)
        val ipadState = IntArray(8)
        val opadState = IntArray(8)
        val u = IntArray(8)
        val t = IntArray(8)
        val tmp = IntArray(8)
        val w = IntArray(64)
        val blockBuf = ByteArray(64)
    }

    private fun packBlock(bytes: ByteArray, offset: Int, w: IntArray) {
        var i = 0
        var off = offset
        while (i < 16) {
            w[i] = ((bytes[off].toInt() and 0xFF) shl 24) or
                ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                (bytes[off + 3].toInt() and 0xFF)
            i++
            off += 4
        }
    }

    private fun sha256Compress(state: IntArray, w: IntArray) {
        for (i in 16..63) {
            val w15 = w[i - 15]
            val w2 = w[i - 2]
            val s0 = w15.rotateRight(7) xor w15.rotateRight(18) xor (w15 ushr 3)
            val s1 = w2.rotateRight(17) xor w2.rotateRight(19) xor (w2 ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in 0..63) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private fun sha256FullMessage(msg: ByteArray, outState: IntArray, w: IntArray, blockBuf: ByteArray) {
        System.arraycopy(SHA256_H0, 0, outState, 0, 8)
        var pos = 0
        for (b in msg) {
            blockBuf[pos++] = b
            if (pos == 64) {
                packBlock(blockBuf, 0, w)
                sha256Compress(outState, w)
                pos = 0
            }
        }
        blockBuf[pos++] = 0x80.toByte()
        if (pos > 56) {
            while (pos < 64) blockBuf[pos++] = 0
            packBlock(blockBuf, 0, w)
            sha256Compress(outState, w)
            pos = 0
        }
        while (pos < 56) blockBuf[pos++] = 0
        val totalBits = msg.size.toLong() * 8L
        for (i in 0..7) blockBuf[56 + i] = (totalBits ushr (56 - i * 8)).toByte()
        packBlock(blockBuf, 0, w)
        sha256Compress(outState, w)
    }

    private fun pbkdf2Sha256Block1Into(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        outLen: Int,
        outBuf: ByteArray,
        s: Pbkdf2Sha256Scratch,
    ) {
        val keyInts = s.keyInts
        keyInts.fill(0)
        if (password.size > 64) {
            sha256FullMessage(password, keyInts, s.w, s.blockBuf)
        } else {
            for (i in password.indices) {
                keyInts[i ushr 2] = keyInts[i ushr 2] or ((password[i].toInt() and 0xFF) shl (24 - (i and 3) * 8))
            }
        }
        val ipadState = s.ipadState
        val opadState = s.opadState
        val w = s.w
        System.arraycopy(SHA256_H0, 0, ipadState, 0, 8)
        for (i in 0..15) w[i] = keyInts[i] xor 0x36363636
        sha256Compress(ipadState, w)
        System.arraycopy(SHA256_H0, 0, opadState, 0, 8)
        for (i in 0..15) w[i] = keyInts[i] xor 0x5c5c5c5c
        sha256Compress(opadState, w)

        val u = s.u; val t = s.t; val tmp = s.tmp; val blockBuf = s.blockBuf
        System.arraycopy(ipadState, 0, u, 0, 8)
        var pos = 0
        for (b in salt) {
            blockBuf[pos++] = b
            if (pos == 64) { packBlock(blockBuf, 0, w); sha256Compress(u, w); pos = 0 }
        }
        for (b in 0..3) {
            blockBuf[pos++] = if (b == 3) 1 else 0
            if (pos == 64) { packBlock(blockBuf, 0, w); sha256Compress(u, w); pos = 0 }
        }
        blockBuf[pos++] = 0x80.toByte()
        if (pos > 56) {
            while (pos < 64) blockBuf[pos++] = 0
            packBlock(blockBuf, 0, w); sha256Compress(u, w); pos = 0
        }
        while (pos < 56) blockBuf[pos++] = 0
        val innerBits = (64L + salt.size.toLong() + 4L) * 8L
        for (i in 0..7) blockBuf[56 + i] = (innerBits ushr (56 - i * 8)).toByte()
        packBlock(blockBuf, 0, w); sha256Compress(u, w)

        System.arraycopy(opadState, 0, tmp, 0, 8)
        System.arraycopy(u, 0, w, 0, 8)
        w[8] = Int.MIN_VALUE; for (i in 9..14) w[i] = 0; w[15] = 768
        sha256Compress(tmp, w)
        System.arraycopy(tmp, 0, u, 0, 8)
        System.arraycopy(u, 0, t, 0, 8)

        for (iter in 2..iterations) {
            System.arraycopy(ipadState, 0, tmp, 0, 8); System.arraycopy(u, 0, w, 0, 8)
            w[8] = Int.MIN_VALUE; for (i in 9..14) w[i] = 0; w[15] = 768
            sha256Compress(tmp, w)
            System.arraycopy(opadState, 0, u, 0, 8); System.arraycopy(tmp, 0, w, 0, 8)
            w[8] = Int.MIN_VALUE; for (i in 9..14) w[i] = 0; w[15] = 768
            sha256Compress(u, w)
            for (k in 0..7) t[k] = t[k] xor u[k]
        }
        for (i in 0 until outLen) outBuf[i] = (t[i ushr 2] ushr (24 - (i and 3) * 8)).toByte()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd hex length: ${clean.length}" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex chars in $clean" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }

    fun decodeBase64Flexible(input: String): ByteArray {
        val normalized = input.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(pad)
        return runCatching { Base64.decode(padded, Base64.DEFAULT) }
            .getOrElse { java.util.Base64.getDecoder().decode(padded) }
    }

    fun encodeBase64(bytes: ByteArray): String =
        runCatching { Base64.encodeToString(bytes, Base64.NO_WRAP) }
            .getOrElse { java.util.Base64.getEncoder().encodeToString(bytes) }

    fun pbkdf2Hmac(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
        algorithm: String = "HmacSHA256",
    ): ByteArray {
        if (algorithm.equals("HmacSHA256", ignoreCase = true) && keyLengthBytes in 1..32 && iterations >= 1) {
            val out = ByteArray(keyLengthBytes)
            pbkdf2Sha256Block1Into(password, salt, iterations, keyLengthBytes, out, Pbkdf2Sha256Scratch())
            return out
        }
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(password, algorithm))
        val hashLen = mac.macLength
        val blockCount = (keyLengthBytes + hashLen - 1) / hashLen
        val out = ByteArray(blockCount * hashLen)
        val blockIndex = ByteArray(4)
        val u = ByteArray(hashLen)
        val t = ByteArray(hashLen)
        for (block in 1..blockCount) {
            blockIndex[0] = (block ushr 24).toByte()
            blockIndex[1] = (block ushr 16).toByte()
            blockIndex[2] = (block ushr 8).toByte()
            blockIndex[3] = block.toByte()
            mac.update(salt); mac.update(blockIndex); mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hashLen)
            for (iter in 2..iterations) {
                mac.update(u); mac.doFinal(u, 0)
                for (i in 0 until hashLen) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * hashLen, hashLen)
        }
        return out.copyOf(keyLengthBytes)
    }

    suspend fun solveAltchaChallengeSuspend(challengeJson: String, maxCounters: Int = 8000): String? {
        val root = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(challengeJson) }.getOrNull() ?: return null
        val params = root.get("parameters")
        if (params == null || !params.isObject) {
            return solveAltchaV1(root, maxCounters * 50)
        }
        val algo = params.get("algorithm")?.asText("PBKDF2/SHA-256") ?: "PBKDF2/SHA-256"
        val cost = params.get("cost")?.asInt(10000) ?: 10000
        val keyLen = params.get("keyLength")?.asInt(32) ?: 32
        if (!algo.equals("PBKDF2/SHA-256", ignoreCase = true) || keyLen !in 1..32 || cost < 500) {
            return solveAltchaV2(root, params, maxCounters)
        }
        val keyPrefix = params.get("keyPrefix")?.asText("") ?: return null
        val nonceHex = params.get("nonce")?.asText("") ?: return null
        val saltHex = params.get("salt")?.asText("") ?: return null
        val nonceBytes = runCatching { hexToBytes(nonceHex) }.getOrNull() ?: return null
        val saltBytes = runCatching { hexToBytes(saltHex) }.getOrNull() ?: return null
        val prefixBytes = if (keyPrefix.isNotEmpty() && keyPrefix.length % 2 == 0) {
            runCatching { hexToBytes(keyPrefix) }.getOrNull()
        } else null

        val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val found = AtomicBoolean(false)
        val winner = AtomicReference<String?>(null)
        val startedNs = System.nanoTime()
        coroutineScope {
            (0 until workerCount).map { workerId ->
                async(Dispatchers.Default) {
                    val scratch = Pbkdf2Sha256Scratch()
                    val passwordBuf = ByteArray(nonceBytes.size + 4)
                    System.arraycopy(nonceBytes, 0, passwordBuf, 0, nonceBytes.size)
                    val derivedBuf = ByteArray(keyLen)
                    var counter = workerId
                    while (counter <= maxCounters && !found.get()) {
                        passwordBuf[nonceBytes.size] = (counter ushr 24).toByte()
                        passwordBuf[nonceBytes.size + 1] = (counter ushr 16).toByte()
                        passwordBuf[nonceBytes.size + 2] = (counter ushr 8).toByte()
                        passwordBuf[nonceBytes.size + 3] = counter.toByte()
                        pbkdf2Sha256Block1Into(passwordBuf, saltBytes, cost, keyLen, derivedBuf, scratch)
                        val matched = if (prefixBytes != null) {
                            prefixBytes.indices.all { i -> derivedBuf[i] == prefixBytes[i] }
                        } else {
                            bytesToHex(derivedBuf).startsWith(keyPrefix)
                        }
                        if (matched && found.compareAndSet(false, true)) {
                            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                            val payload = mapOf(
                                "challenge" to root,
                                "solution" to mapOf("counter" to counter, "derivedKey" to bytesToHex(derivedBuf), "time" to elapsedMs),
                            )
                            winner.compareAndSet(null, encodeBase64(PelisJuanitaMarkup.jsonMapper.writeValueAsBytes(payload)))
                            break
                        }
                        counter += workerCount
                    }
                }
            }.awaitAll()
        }
        return winner.get()
    }

    private fun solveAltchaV2(root: JsonNode, params: JsonNode, maxCounters: Int): String? {
        val algo = params.get("algorithm")?.asText("PBKDF2/SHA-256") ?: "PBKDF2/SHA-256"
        val cost = params.get("cost")?.asInt(10000) ?: 10000
        val keyLen = params.get("keyLength")?.asInt(32) ?: 32
        val keyPrefix = params.get("keyPrefix")?.asText("") ?: return null
        val nonceHex = params.get("nonce")?.asText("") ?: return null
        val saltHex = params.get("salt")?.asText("") ?: return null
        val macAlgo = when (algo.uppercase()) {
            "PBKDF2/SHA-512" -> "HmacSHA512"
            "PBKDF2/SHA-384" -> "HmacSHA384"
            else -> "HmacSHA256"
        }
        val nonceBytes = runCatching { hexToBytes(nonceHex) }.getOrNull() ?: return null
        val saltBytes = runCatching { hexToBytes(saltHex) }.getOrNull() ?: return null
        val passwordBuf = ByteArray(nonceBytes.size + 4)
        System.arraycopy(nonceBytes, 0, passwordBuf, 0, nonceBytes.size)
        val startedNs = System.nanoTime()
        for (counter in 0..maxCounters) {
            passwordBuf[nonceBytes.size] = (counter ushr 24).toByte()
            passwordBuf[nonceBytes.size + 1] = (counter ushr 16).toByte()
            passwordBuf[nonceBytes.size + 2] = (counter ushr 8).toByte()
            passwordBuf[nonceBytes.size + 3] = counter.toByte()
            val derived = pbkdf2Hmac(passwordBuf, saltBytes, cost, keyLen, macAlgo)
            val hex = bytesToHex(derived)
            if (hex.startsWith(keyPrefix)) {
                val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                val payload = mapOf(
                    "challenge" to root,
                    "solution" to mapOf("counter" to counter, "derivedKey" to hex, "time" to elapsedMs),
                )
                return encodeBase64(PelisJuanitaMarkup.jsonMapper.writeValueAsBytes(payload))
            }
        }
        return null
    }

    private fun solveAltchaV1(root: JsonNode, maxNumber: Int): String? {
        val algorithm = root.get("algorithm")?.asText("SHA-256") ?: "SHA-256"
        val challenge = root.get("challenge")?.asText("") ?: return null
        val salt = root.get("salt")?.asText("") ?: return null
        val limit = root.get("maxnumber")?.asInt(maxNumber) ?: maxNumber
        val md = MessageDigest.getInstance(algorithm)
        val startedNs = System.nanoTime()
        for (number in 0..limit.coerceAtMost(2_000_000)) {
            md.reset()
            val digest = bytesToHex(md.digest((salt + number).toByteArray(StandardCharsets.UTF_8)))
            if (digest == challenge) {
                val took = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                val payload = mapOf(
                    "algorithm" to algorithm,
                    "challenge" to challenge,
                    "number" to number,
                    "salt" to salt,
                    "signature" to (root.get("signature")?.asText("") ?: ""),
                    "took" to took,
                )
                return encodeBase64(PelisJuanitaMarkup.jsonMapper.writeValueAsBytes(payload))
            }
        }
        return null
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PelisJuanitaVoeCaption(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PelisJuanitaVoePayload(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
    @JsonProperty("captions") val captions: List<PelisJuanitaVoeCaption>? = null,
)

internal object PelisJuanitaVoeCrypto {
    private val JUNK_PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    fun decryptF7(encoded: String): PelisJuanitaVoePayload? {
        val step1 = rot13(encoded.trim())
        var cleaned = step1
        for (marker in JUNK_PATTERNS) cleaned = cleaned.replace(marker, "")
        val step3 = String(PelisJuanitaCrypto.decodeBase64Flexible(cleaned), StandardCharsets.UTF_8)
        val step4 = step3.map { ch -> (ch.code - 3).toChar() }.joinToString("")
        val step5 = step4.reversed()
        val json = String(PelisJuanitaCrypto.decodeBase64Flexible(step5), StandardCharsets.UTF_8)
        return runCatching { PelisJuanitaMarkup.jsonMapper.readValue(json, PelisJuanitaVoePayload::class.java) }.getOrNull()
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    in 'a'..'z' -> ((ch - 'a' + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((ch - 'A' + 13) % 26 + 'A'.code).toChar()
                    else -> ch
                },
            )
        }
        return sb.toString()
    }
}

internal object PelisJuanitaVsembedCrypto {
    val DEFAULT_KEY_CHARS = listOf(
        "\uD835\uDD4A", "\uD835\uDD3C", "\uD835\uDD6E", "\uD835\uDE81",
        "\uD835\uDD3C", "\uD835\uDE83", "\uD835\uDCDA", "\uD835\uDCD4",
        "\uD835\uDCE8", "\uD835\uDFD9", "\uD835\uDFDA", "\uD835\uDFDB",
    )
    val DEFAULT_SALT_CHARS = listOf(
        "\uD83D\uDD10", "\uD83D\uDDDD\uFE0F", "\uD83D\uDD11",
        "\uD83D\uDEAA", "\uD83D\uDEE1\uFE0F", "\u26A1",
    )

    fun generateKeyHex(
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        epochDay: Long = System.currentTimeMillis() / 86_400_000L,
    ): String {
        val sb = StringBuilder()
        for (ch in keyChars) if (ch.isNotEmpty()) sb.append(Character.codePointAt(ch, 0).toString(16))
        sb.append(epochDay.toString(16))
        return sb.toString().take(32).padEnd(32, '0')
    }

    fun generateIvHex(host: String, saltChars: List<String> = DEFAULT_SALT_CHARS): String {
        val sb = StringBuilder()
        for (ch in saltChars) if (ch.isNotEmpty()) sb.append(Character.codePointAt(ch, 0).toString(16))
        if (host.isNotEmpty()) sb.append(PelisJuanitaCrypto.bytesToHex(host.toByteArray(StandardCharsets.UTF_8)))
        return sb.toString().take(32).padEnd(32, '0')
    }

    fun encryptHex(
        plain: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String {
        val keyBytes = PelisJuanitaCrypto.hexToBytes(generateKeyHex(keyChars))
        val ivBytes = PelisJuanitaCrypto.hexToBytes(generateIvHex(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return PelisJuanitaCrypto.bytesToHex(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decryptHex(
        hexCiphertext: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String? = runCatching {
        val keyBytes = PelisJuanitaCrypto.hexToBytes(generateKeyHex(keyChars))
        val ivBytes = PelisJuanitaCrypto.hexToBytes(generateIvHex(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        String(cipher.doFinal(PelisJuanitaCrypto.hexToBytes(hexCiphertext)), StandardCharsets.UTF_8)
    }.getOrNull()
}

class PelisJuanitaVoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
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
        private val REDIRECT_REGEX = Regex(
            """(?:(?:window\.)?location(?:\.href)?\s*=\s*|location\.replace\(\s*)['"]([^'"]+)['"]""",
        )

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "Voe",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val baseHeaders = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val cookies = linkedMapOf<String, String>()
            var currentUrl = url
            var currentReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
            var res = app.get(currentUrl, referer = currentReferer, headers = baseHeaders, cookies = cookies, timeout = 10L)
            cookies.putAll(res.cookies)

            repeat(2) {
                if (!res.text.contains("altcha-widget", ignoreCase = true) &&
                    !res.text.contains("application/json", ignoreCase = true)
                ) {
                    val redir = REDIRECT_REGEX.find(res.text)?.groupValues?.getOrNull(1)
                    if (redir != null && redir != res.url) {
                        val resolvedRedir = runCatching { URI(res.url).resolve(redir).toString() }.getOrDefault(redir)
                        currentReferer = res.url
                        currentUrl = resolvedRedir
                        res = app.get(currentUrl, referer = currentReferer, headers = baseHeaders, cookies = cookies, timeout = 10L)
                        cookies.putAll(res.cookies)
                    }
                }
            }

            if (res.text.contains("altcha-widget", ignoreCase = true)) {
                val doc = Jsoup.parse(res.text, res.url)
                val challengeAttr = doc.selectFirst("altcha-widget")?.attr("challenge")?.trim().orEmpty()
                val formAction = doc.selectFirst("form.access-form, form[method~=^(?i)post$], form[action]")?.attr("abs:action")
                    ?.takeIf { it.isNotBlank() } ?: res.url
                val token = doc.selectFirst("input[name=_token]")?.attr("value").orEmpty()
                val accessVal = doc.selectFirst("input[name=access]")?.attr("value") ?: "0"
                if (challengeAttr.isNotEmpty()) {
                    val challengeUrl = runCatching { URI(res.url).resolve(challengeAttr).toString() }.getOrDefault(challengeAttr)
                    val chalRes = app.get(
                        challengeUrl,
                        referer = res.url,
                        headers = baseHeaders + ("Accept" to "application/json, */*"),
                        cookies = cookies,
                        timeout = 10L,
                    )
                    cookies.putAll(chalRes.cookies)
                    val altchaPayload = PelisJuanitaCrypto.solveAltchaChallengeSuspend(chalRes.text) ?: return false
                    val origin = PelisJuanitaVidHideParser.originOf(res.url)
                    val postRes = app.post(
                        formAction,
                        referer = res.url,
                        headers = baseHeaders + mapOf("Origin" to origin, "Content-Type" to "application/x-www-form-urlencoded"),
                        cookies = cookies,
                        data = mapOf("_token" to token, "access" to accessVal, "altcha" to altchaPayload),
                        timeout = 12L,
                    )
                    cookies.putAll(postRes.cookies)
                    res = postRes
                    if (!res.text.contains("application/json", ignoreCase = true)) {
                        val postRedir = REDIRECT_REGEX.find(res.text)?.groupValues?.getOrNull(1)
                        if (postRedir != null && postRedir != res.url) {
                            val resolved = runCatching { URI(res.url).resolve(postRedir).toString() }.getOrDefault(postRedir)
                            res = app.get(resolved, referer = res.url, headers = baseHeaders, cookies = cookies, timeout = 10L)
                            cookies.putAll(res.cookies)
                        }
                    }
                }
            }

            val doc = Jsoup.parse(res.text, res.url)
            val encodedJson = doc.selectFirst("script[type=application/json]")?.data()?.trim()?.let { raw ->
                Regex("""\[\s*"([^"]+)"\s*]""").find(raw)?.groupValues?.getOrNull(1)
                    ?: raw.substringAfter("[\"", "").substringBeforeLast("\"]", "")
            }?.takeIf { it.isNotEmpty() }

            if (encodedJson == null) {
                var fallbackOk = false
                runCatching {
                    com.lagradost.cloudstream3.extractors.Voe().getUrl(res.url, referer, subtitleCallback) { link ->
                        fallbackOk = true
                        callback(link)
                    }
                }
                return fallbackOk
            }

            val decrypted = PelisJuanitaVoeCrypto.decryptF7(encodedJson) ?: return false
            val origin = PelisJuanitaVidHideParser.originOf(res.url)
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            )
            var emitted = false

            val m3u8Url = decrypted.source?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (m3u8Url != null) {
                val links = withTimeoutOrNull(4_500L) {
                    runCatching {
                        M3u8Helper.generateM3u8(sourceName, m3u8Url, "$origin/", headers = streamHeaders)
                    }.getOrElse { emptyList() }
                }.orEmpty()
                if (links.isNotEmpty()) {
                    links.forEach { callback(it); emitted = true }
                } else {
                    callback(
                        newExtractorLink(sourceName, sourceName, m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = "$origin/"; this.headers = streamHeaders; this.quality = Qualities.Unknown.value
                        },
                    )
                    emitted = true
                }
            }
            val mp4Url = decrypted.directAccessUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (!emitted && mp4Url != null) {
                callback(
                    newExtractorLink(sourceName, "$sourceName MP4", mp4Url, INFER_TYPE) {
                        this.referer = res.url; this.quality = Qualities.Unknown.value
                    },
                )
                emitted = true
            }
            for (cap in decrypted.captions.orEmpty()) {
                val capUrl = cap.file?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: continue
                val capLabel = cap.label?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                subtitleCallback(newSubtitleFile(capLabel, capUrl))
            }
            return emitted
        }
    }
}

class PelisJuanitaHglinkExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://hglink.to"
}

class PelisJuanitaMedixiruExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://medixiru.com"
}

class PelisJuanitaVsembedExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://vsembed.com"
}

class PelisJuanitaFlaswishExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://flaswish.com"
}

class PelisJuanitaHlswishExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://hlswish.com"
}

class PelisJuanitaStreamwishSiteExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.site"
}

class PelisJuanitaPlayerwishExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://playerwish.com"
}

class PelisJuanitaDhcplayExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://dhcplay.com"
}

class PelisJuanitaStrwishExtractor : PelisJuanitaStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://strwish.com"
}

open class PelisJuanitaStreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
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
        private val REDIRECT_HOSTS = listOf(
            "https://medixiru.com",
            "https://hglink.to",
            "https://flaswish.com",
            "https://hlswish.com",
            "https://streamwish.site",
            "https://playerwish.com",
        )
        private val JS_ASSET_REGEX = Regex("""src=["']((?:https?://[^"']+|/?assets/[^"']+)\.js)["']""")

        private fun isVsembedUrl(url: String): Boolean =
            runCatching { URI(url).host?.contains("vsembed", ignoreCase = true) == true }.getOrDefault(false)

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "StreamWish",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val baseHeaders = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
            var res = app.get(url, referer = effReferer, headers = baseHeaders, timeout = 8L)

            val unpacked = PelisJuanitaVidHideParser.parse(res.text, res.url)
            if (unpacked.streams.isNotEmpty() && emitParsedStreams(unpacked, sourceName, subtitleCallback, callback)) {
                return true
            }

            if (!isVsembedUrl(res.url) &&
                (res.text.contains("main.js", ignoreCase = true) ||
                    res.text.contains("Loading...", ignoreCase = true) ||
                    !res.text.contains("jwplayer", ignoreCase = true))
            ) {
                val fileCode = runCatching {
                    URI(url).rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
                }.getOrDefault("")
                if (fileCode.isNotEmpty()) {
                    for (mirror in REDIRECT_HOSTS) {
                        val hopped = runCatching {
                            app.get("$mirror/e/$fileCode", referer = res.url, headers = baseHeaders, timeout = 5L)
                        }.getOrNull() ?: continue
                        res = hopped
                        if (isVsembedUrl(res.url)) break
                        val mirrorParsed = PelisJuanitaVidHideParser.parse(res.text, res.url)
                        if (mirrorParsed.streams.isNotEmpty()) {
                            return emitParsedStreams(mirrorParsed, sourceName, subtitleCallback, callback)
                        }
                    }
                }
            }

            if (isVsembedUrl(res.url)) {
                return extractFromVsembed(res.url, res.text, sourceName, subtitleCallback, callback)
            }

            var fallbackEmitted = false
            runCatching {
                StreamWishExtractor().getUrl(url, referer, subtitleCallback) { link ->
                    fallbackEmitted = true
                    callback(link)
                }
            }
            if (fallbackEmitted) return true

            val doc = Jsoup.parse(res.text, res.url)
            val scriptData = doc.select("script")
                .firstOrNull { it.data().contains("sources:") || it.data().contains("jwplayer(") }
                ?.data()
            if (!scriptData.isNullOrBlank()) {
                val origin = PelisJuanitaVidHideParser.originOf(res.url)
                var jwFound = false
                JwPlayerHelper.extractStreamLinks(
                    scriptData,
                    sourceName,
                    origin,
                    { jwFound = true; callback(it) },
                    subtitleCallback,
                    mapOf("Origin" to origin, "Referer" to "$origin/", "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT),
                )
                if (jwFound) return true
            }
            return false
        }

        private suspend fun extractFromVsembed(
            pageUrl: String,
            pageHtml: String,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return false
            val origin = "${uri.scheme}://${uri.authority}"
            val host = uri.host ?: "vsembed.com"
            val shortCode = uri.rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
            if (shortCode.isEmpty()) return false

            val apiHeaders = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to pageUrl,
            )

            var keyChars = PelisJuanitaVsembedCrypto.DEFAULT_KEY_CHARS
            var saltChars = PelisJuanitaVsembedCrypto.DEFAULT_SALT_CHARS
            var encParam = PelisJuanitaVsembedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
            var trackRes = app.get("$origin/api/stream/tracking?c=$encParam&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 8L)
            var decryptedJson = trackRes.headers["x-tracking"]?.let {
                PelisJuanitaVsembedCrypto.decryptHex(it, host, keyChars, saltChars)
            }

            if (decryptedJson == null) {
                val assetPath = JS_ASSET_REGEX.find(pageHtml)?.groupValues?.getOrNull(1)
                if (assetPath != null) {
                    val assetUrl = runCatching { uri.resolve(assetPath).toString() }.getOrDefault("$origin$assetPath")
                    val bundleText = runCatching {
                        app.get(assetUrl, referer = pageUrl, headers = apiHeaders, timeout = 8L).text
                    }.getOrNull()
                    if (bundleText != null) {
                        val parsedKeys = parseCharArray(bundleText, "keyChars")
                        val parsedSalts = parseCharArray(bundleText, "saltChars")
                        if (!parsedKeys.isNullOrEmpty() && !parsedSalts.isNullOrEmpty()) {
                            keyChars = parsedKeys
                            saltChars = parsedSalts
                            encParam = PelisJuanitaVsembedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
                            trackRes = app.get("$origin/api/stream/tracking?c=$encParam&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 8L)
                            decryptedJson = trackRes.headers["x-tracking"]?.let {
                                PelisJuanitaVsembedCrypto.decryptHex(it, host, keyChars, saltChars)
                            }
                        }
                    }
                }
            }

            val jsonText = decryptedJson ?: return false
            val root = runCatching { PelisJuanitaMarkup.jsonMapper.readTree(jsonText) }.getOrNull() ?: return false
            val streamNode = root.get("stream") ?: root
            val hlsUrl = streamNode.get("hls")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            val tiktokUrl = streamNode.get("tiktok")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            )

            var emitted = false
            val candidateTimeouts = listOfNotNull(
                hlsUrl?.let { it to 3_000L },
                tiktokUrl?.let { it to 4_000L },
            ).distinctBy { it.first }
            for ((candidate, timeoutMs) in candidateTimeouts) {
                val generated = withTimeoutOrNull(timeoutMs) {
                    runCatching {
                        M3u8Helper.generateM3u8(sourceName, candidate, "$origin/", headers = streamHeaders)
                    }.getOrElse { emptyList() }
                }.orEmpty()
                if (generated.isNotEmpty()) {
                    generated.forEach { callback(it); emitted = true }
                    break
                }
            }
            if (!emitted) {
                val directFallback = tiktokUrl ?: hlsUrl
                if (directFallback != null) {
                    callback(
                        newExtractorLink(sourceName, sourceName, directFallback, ExtractorLinkType.M3U8) {
                            this.referer = "$origin/"; this.headers = streamHeaders; this.quality = Qualities.Unknown.value
                        },
                    )
                    emitted = true
                }
            }

            if (emitted) {
                runCatching {
                    val streamInfoRes = app.get("$origin/api/stream?s=$shortCode&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 6L)
                    if (streamInfoRes.isSuccessful) {
                        val subsNode = PelisJuanitaMarkup.jsonMapper.readTree(streamInfoRes.text).get("data")?.get("subtitles")
                        if (subsNode != null && subsNode.isArray) {
                            for (sub in subsNode) {
                                val subUrl = sub.get("file")?.asText() ?: sub.get("url")?.asText() ?: continue
                                val subLabel = sub.get("label")?.asText() ?: sub.get("language")?.asText() ?: "Subtítulos"
                                if (subUrl.startsWith("http", ignoreCase = true)) {
                                    subtitleCallback(newSubtitleFile(subLabel, subUrl))
                                }
                            }
                        }
                    }
                }
            }
            return emitted
        }

        private fun parseCharArray(bundleText: String, fieldName: String): List<String>? {
            val match = Regex("""(?:this\.|\blet\s+|\bconst\s+|\bvar\s+)?$fieldName\s*=\s*(\[[^\]]+\])""")
                .find(bundleText)?.groupValues?.getOrNull(1)
                ?: return null
            return runCatching {
                PelisJuanitaMarkup.jsonMapper.readValue(match, object : TypeReference<List<String>>() {})
            }.getOrNull()
        }

        private suspend fun emitParsedStreams(
            parsed: PelisJuanitaParsedEmbed,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val streamHeaders = mapOf(
                "Origin" to parsed.origin,
                "Referer" to "${parsed.origin}/",
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            )
            for (sub in parsed.subtitles) subtitleCallback(newSubtitleFile(sub.label, sub.url))
            var emitted = false
            for (stream in parsed.streams) {
                if (stream.key == "hls3" && emitted) continue
                val generated = withTimeoutOrNull(4_500L) {
                    runCatching {
                        M3u8Helper.generateM3u8(sourceName, stream.url, "${parsed.origin}/", headers = streamHeaders)
                    }.getOrElse { emptyList() }
                }.orEmpty()
                if (generated.isNotEmpty()) {
                    generated.forEach { callback(it); emitted = true }
                    break
                }
            }
            return emitted
        }
    }
}

class PelisJuanitaMorenciusExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://morencius.com"
}

class PelisJuanitaCallistaniseExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://callistanise.com"
}

class PelisJuanitaMinochinosExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://minochinos.com"
}

class PelisJuanitaVidHideProExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhidepro.com"
}

class PelisJuanitaMovearnpreExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://movearnpre.com"
}

class PelisJuanitaDingtezuniExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
}

class PelisJuanitaMivalyoExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://mivalyo.com"
}

class PelisJuanitaTaylorplayerExtractor : PelisJuanitaVidHideExtractor() {
    override val name = "Earnvids"
    override val mainUrl = "https://taylorplayer.com"
}

open class PelisJuanitaVidHideExtractor : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhidevip.com"
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
        private val MIRROR_ORIGINS = listOf(
            "https://callistanise.com",
            "https://vidhidevip.com",
            "https://peytonepre.com",
            "https://smoothpre.com",
            "https://ryderjet.com",
            "https://vidhidepre.com",
            "https://morencius.com",
            "https://minochinos.com",
        )
        private val UNRELIABLE_HOSTS = setOf("vidhidepro.com", "dhtpre.com")

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "VidHide",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val normalizedUrl = PelisJuanitaVidHideParser.normalizeEmbedUrl(url)
            val parsedUri = runCatching { URI(normalizedUrl) }.getOrNull()
            val pathWithQuery = buildString {
                append(parsedUri?.rawPath?.ifEmpty { "/" } ?: "/")
                val q = parsedUri?.rawQuery
                if (!q.isNullOrEmpty()) append("?$q")
            }
            val origOrigin = runCatching { PelisJuanitaVidHideParser.originOf(normalizedUrl) }.getOrNull()
            val origHost = parsedUri?.host?.lowercase().orEmpty()
            val candidateUrls = buildList {
                if (origHost !in UNRELIABLE_HOSTS) add(normalizedUrl)
                for (mirror in MIRROR_ORIGINS) {
                    if (!mirror.equals(origOrigin, ignoreCase = true)) add("$mirror$pathWithQuery")
                }
                if (origHost in UNRELIABLE_HOSTS) add(normalizedUrl)
            }

            val reqHeaders = mapOf(
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"

            var firstFallback: Pair<PelisJuanitaParsedEmbed, PelisJuanitaParsedStream>? = null
            var emittedSubs = false
            for (candidate in candidateUrls) {
                val res = try {
                    app.get(candidate, referer = effReferer, headers = reqHeaders, timeout = 6L)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (res.code == 404 || res.code == 410) break
                if (!res.isSuccessful) continue

                val attempt = PelisJuanitaVidHideParser.parse(res.text, res.url)
                if (attempt.streams.isEmpty()) {
                    if (res.text.contains("File was deleted", ignoreCase = true) ||
                        res.text.contains("File not found", ignoreCase = true) ||
                        res.text.contains("no longer available", ignoreCase = true)
                    ) {
                        break
                    }
                    continue
                }

                if (!emittedSubs) {
                    for (sub in attempt.subtitles) subtitleCallback(newSubtitleFile(sub.label, sub.url))
                    emittedSubs = true
                }
                val streamHeaders = mapOf(
                    "Origin" to attempt.origin,
                    "Referer" to "${attempt.origin}/",
                    "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                )
                val preferredFallback = attempt.streams.firstOrNull { it.key != "hls3" } ?: attempt.streams.first()
                if (firstFallback == null) firstFallback = attempt to preferredFallback

                for (stream in attempt.streams) {
                    val links = withTimeoutOrNull(4_000L) {
                        runCatching {
                            M3u8Helper.generateM3u8(sourceName, stream.url, "${attempt.origin}/", headers = streamHeaders)
                        }.getOrElse { emptyList() }
                    }.orEmpty()
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        return true
                    }
                }
            }

            val (fallbackResult, fallbackStream) = firstFallback ?: return false
            val streamHeaders = mapOf(
                "Origin" to fallbackResult.origin,
                "Referer" to "${fallbackResult.origin}/",
                "user-agent" to PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
            )
            callback(
                newExtractorLink(sourceName, sourceName, fallbackStream.url, ExtractorLinkType.M3U8) {
                    this.referer = "${fallbackResult.origin}/"; this.headers = streamHeaders; this.quality = Qualities.Unknown.value
                },
            )
            return true
        }
    }
}
