package com.menorah.sololatino

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.math.BigInteger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object SoloLatinoHostHttp {
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val MOBILE_UA = SoloLatinoSessionHelper.DEFAULT_USER_AGENT

    fun originOf(url: String, fallback: String = SoloLatinoMarkup.MAIN_URL): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val scheme = uri?.scheme
        val authority = uri?.authority
        return if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) "$scheme://$authority" else fallback
    }

    fun fileCodeFromPath(url: String): String {
        val clean = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val path = runCatching { URI(clean).rawPath.orEmpty() }.getOrDefault(clean)
        Regex("""/(?:e|d|v|embed)/([A-Za-z0-9_-]+)""").find(path)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""/embed-([A-Za-z0-9_-]+)""").find(path)?.groupValues?.getOrNull(1)?.let { return it }
        return clean.substringAfterLast('/').substringBefore('.')
    }

    fun decodeBase64Flexible(input: String): ByteArray {
        val clean = input.trim().replace("\r", "").replace("\n", "").replace('-', '+').replace('_', '/').trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd hex string length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}

internal object SoloLatinoHlsHelper {
    private val DEMUXED_AUDIO_REGEX = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO""", RegexOption.IGNORE_CASE)
    private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

    suspend fun emitHlsSafely(
        sourceName: String,
        displayName: String,
        masterUrl: String,
        referer: String,
        headers: Map<String, String> = emptyMap(),
        fallbackQuality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playlistText = withTimeoutOrNull(4_500L) {
            runCatching {
                app.get(masterUrl, referer = referer, headers = headers, verify = false, timeout = 4L)
                    .takeIf { it.isSuccessful }
                    ?.text
            }.getOrNull()
        }

        if (!playlistText.isNullOrBlank() && playlistText.contains("#EXTM3U")) {
            val maxHeight = RESOLUTION_HEIGHT_REGEX.findAll(playlistText)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull() ?: fallbackQuality
            if (DEMUXED_AUDIO_REGEX.containsMatchIn(playlistText)) {
                callback(
                    newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = maxHeight
                    },
                )
                return true
            }
            val generated = withTimeoutOrNull(4_500L) {
                runCatching {
                    M3u8Helper.generateM3u8(
                        source = sourceName,
                        streamUrl = masterUrl,
                        referer = referer,
                        quality = fallbackQuality,
                        headers = headers,
                        name = displayName,
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return true
            }
            callback(
                newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = maxHeight
                },
            )
            return true
        }

        callback(
            newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = headers
                this.quality = fallbackQuality
            },
        )
        return true
    }
}

internal data class SoloLatinoVidHideParsed(
    val origin: String,
    val streams: List<Pair<String, String>>,
    val subtitles: List<Pair<String, String>>,
)

internal object SoloLatinoVidHideParser {
    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PACKER_REGEX = Regex("""(?s)\}\s*\('([^'\\]*(?:\\.[^'\\]*)*)'\s*,\s*(\d+)\s*,\s*\d+\s*,\s*'([^'\\]*(?:\\.[^'\\]*)*)'\.split\('\|'\)""")
    private val LINKS_OBJ_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_ARR_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val TRACKS_ARR_REGEX = Regex("""(?s)["']?tracks["']?\s*:\s*(\[[^\]]*\])""")
    private val DIRECT_MEDIA_REGEX = Regex("""(?i)["']((?:https?:)?//[^\s"'<>]+|/[A-Za-z0-9_./~%-]+\.(?:m3u8|txt|mp4)(?:\?[^\s"'<>]*)?)["']""")
    private val WORD_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")

    fun normalizeEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        url.contains("/f/") -> url.replace("/f/", "/v/")
        else -> url
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
        val out = mutableListOf<String>()
        for (m in PACKER_REGEX.findAll(text)) {
            val payload = m.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val radix = m.groupValues[2].toIntOrNull() ?: continue
            val words = m.groupValues[3].split('|')
            val decoded = WORD_REGEX.replace(payload) { word ->
                val idx = decodeToken(word.value, radix)
                if (idx != null && idx in words.indices && words[idx].isNotEmpty()) words[idx] else word.value
            }
            out += decoded
        }
        return out
    }

    private fun resolveMedia(origin: String, raw: String?): String? {
        val clean = raw?.trim()?.replace("\\/", "/")?.ifEmpty { null } ?: return null
        return SoloLatinoMarkup.resolveUrl("$origin/", clean)
    }

    fun parse(html: String, finalUrl: String): SoloLatinoVidHideParsed {
        val origin = SoloLatinoHostHttp.originOf(finalUrl)
        val doc = Jsoup.parse(html, finalUrl)
        val scripts = doc.select("script").map { it.data() }.filter { it.isNotBlank() }
        val blocks = mutableListOf<String>()
        for (sc in scripts) {
            blocks += unpackAll(sc)
            blocks += sc
        }
        if (scripts.isEmpty()) {
            blocks += unpackAll(html)
            blocks += html
        }

        val linksMap = linkedMapOf<String, String>()
        val sourceUrls = mutableListOf<String>()
        val subtitles = mutableListOf<Pair<String, String>>()

        for (block in blocks) {
            for (m in LINKS_OBJ_REGEX.findAll(block)) {
                val rawObj = m.groupValues[1].replace("\\/", "/")
                val parsed = runCatching {
                    SoloLatinoMarkup.jsonMapper.readValue(rawObj, object : TypeReference<Map<String, String>>() {})
                }.getOrNull().orEmpty()
                for ((k, v) in parsed) {
                    val resolved = resolveMedia(origin, v) ?: continue
                    linksMap.putIfAbsent(k.lowercase(Locale.ROOT), resolved)
                }
            }
            for (m in SOURCES_ARR_REGEX.findAll(block)) {
                val arrText = m.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    SoloLatinoMarkup.jsonMapper.readValue(arrText, object : TypeReference<List<Map<String, Any?>>>() {})
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val rawFile = entry["file"]?.toString() ?: continue
                    val resolved = resolveMedia(origin, rawFile) ?: continue
                    sourceUrls += resolved
                }
            }
            for (m in TRACKS_ARR_REGEX.findAll(block)) {
                val arrText = m.groupValues[1].replace("\\/", "/")
                val entries = runCatching {
                    SoloLatinoMarkup.jsonMapper.readValue(arrText, object : TypeReference<List<Map<String, Any?>>>() {})
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val kind = entry["kind"]?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    if (!kind.contains("caption") && !kind.contains("subtitle")) continue
                    val file = resolveMedia(origin, entry["file"]?.toString()) ?: continue
                    val label = entry["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                    subtitles += label to file
                }
            }
        }

        val ordered = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        fun addStream(key: String, u: String?) {
            if (u != null && seen.add(u)) ordered += key to u
        }
        addStream("hls2", linksMap["hls2"])
        addStream("hls", linksMap["hls"])
        for (src in sourceUrls) addStream("source", src)
        addStream("hls3", linksMap["hls3"])
        addStream("hls4", linksMap["hls4"])
        for ((k, v) in linksMap) addStream(k, v)

        if (ordered.isEmpty()) {
            for (block in blocks) {
                for (m in DIRECT_MEDIA_REGEX.findAll(block)) {
                    val raw = m.groupValues[1]
                    if (raw.contains("get_slides", ignoreCase = true) || raw.contains("thumbnails", ignoreCase = true)) continue
                    addStream("regex", resolveMedia(origin, raw))
                }
            }
        }
        return SoloLatinoVidHideParsed(origin, ordered, subtitles.distinctBy { it.second })
    }
}

internal object SoloLatinoVoeCrypto {
    private val JUNK_PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    private val REDIRECT_PATTERNS = listOf(
        Regex("""(?:(?:window\.)?location(?:\.href)?\s*=\s*|location\.replace\(\s*)['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"';\s>]+)["']""", RegexOption.IGNORE_CASE),
    )

    private class Pbkdf2Sha256Scratch {
        val hmac = Mac.getInstance("HmacSHA256")
        val u = ByteArray(32)
        val t = ByteArray(32)
    }

    private fun pbkdf2Sha256Block1Into(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLen: Int,
        out: ByteArray,
        scratch: Pbkdf2Sha256Scratch,
    ) {
        val mac = scratch.hmac
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val saltPlus = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, saltPlus, 0, salt.size)
        saltPlus[salt.size + 3] = 1
        val u = mac.doFinal(saltPlus)
        System.arraycopy(u, 0, scratch.t, 0, 32)
        var cur = u
        for (i in 2..iterations) {
            cur = mac.doFinal(cur)
            for (k in 0 until 32) scratch.t[k] = (scratch.t[k].toInt() xor cur[k].toInt()).toByte()
        }
        System.arraycopy(scratch.t, 0, out, 0, keyLen.coerceAtMost(32))
    }

    suspend fun solveAltchaSuspend(challengeJson: String, maxCounters: Int = 8000): String? {
        val root = runCatching { SoloLatinoMarkup.jsonMapper.readTree(challengeJson) }.getOrNull() ?: return null
        val params = root.get("parameters")
        if (params == null || !params.isObject) {
            return solveAltchaV1(root)
        }
        val algo = params.get("algorithm")?.asText("PBKDF2/SHA-256") ?: "PBKDF2/SHA-256"
        val cost = params.get("cost")?.asInt(10000) ?: 10000
        val keyLen = params.get("keyLength")?.asInt(32) ?: 32
        if (!algo.equals("PBKDF2/SHA-256", ignoreCase = true) || keyLen !in 1..32 || cost < 500) {
            return null
        }
        val keyPrefix = params.get("keyPrefix")?.asText("") ?: return null
        val nonceHex = params.get("nonce")?.asText("") ?: return null
        val saltHex = params.get("salt")?.asText("") ?: return null
        val nonceBytes = runCatching { SoloLatinoHostHttp.hexToBytes(nonceHex) }.getOrNull() ?: return null
        val saltBytes = runCatching { SoloLatinoHostHttp.hexToBytes(saltHex) }.getOrNull() ?: return null
        val prefixBytes = if (keyPrefix.isNotEmpty() && keyPrefix.length % 2 == 0) {
            runCatching { SoloLatinoHostHttp.hexToBytes(keyPrefix) }.getOrNull()
        } else null

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val found = AtomicBoolean(false)
        val winner = AtomicReference<String?>(null)
        val started = System.nanoTime()
        coroutineScope {
            (0 until workers).map { workerId ->
                async(Dispatchers.Default) {
                    val scratch = Pbkdf2Sha256Scratch()
                    val passBuf = ByteArray(nonceBytes.size + 4)
                    System.arraycopy(nonceBytes, 0, passBuf, 0, nonceBytes.size)
                    val derivedBuf = ByteArray(keyLen)
                    var counter = workerId
                    while (counter <= maxCounters && !found.get()) {
                        passBuf[nonceBytes.size] = (counter ushr 24).toByte()
                        passBuf[nonceBytes.size + 1] = (counter ushr 16).toByte()
                        passBuf[nonceBytes.size + 2] = (counter ushr 8).toByte()
                        passBuf[nonceBytes.size + 3] = counter.toByte()
                        pbkdf2Sha256Block1Into(passBuf, saltBytes, cost, keyLen, derivedBuf, scratch)
                        val matched = if (prefixBytes != null) {
                            prefixBytes.indices.all { i -> derivedBuf[i] == prefixBytes[i] }
                        } else {
                            SoloLatinoHostHttp.bytesToHex(derivedBuf).startsWith(keyPrefix)
                        }
                        if (matched && found.compareAndSet(false, true)) {
                            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                            val payload = mapOf(
                                "challenge" to root,
                                "solution" to mapOf("counter" to counter, "derivedKey" to SoloLatinoHostHttp.bytesToHex(derivedBuf), "time" to elapsedMs),
                            )
                            winner.compareAndSet(null, Base64.getEncoder().encodeToString(SoloLatinoMarkup.jsonMapper.writeValueAsBytes(payload)))
                            break
                        }
                        counter += workers
                    }
                }
            }.awaitAll()
        }
        return winner.get()
    }

    private fun solveAltchaV1(root: JsonNode): String? {
        val algorithm = root.get("algorithm")?.asText("SHA-256") ?: "SHA-256"
        val challenge = root.get("challenge")?.asText("") ?: return null
        val salt = root.get("salt")?.asText("") ?: return null
        val limit = root.get("maxnumber")?.asInt(200_000) ?: 200_000
        val md = MessageDigest.getInstance(algorithm)
        val started = System.nanoTime()
        for (n in 0..limit.coerceAtMost(2_000_000)) {
            md.reset()
            val hex = SoloLatinoHostHttp.bytesToHex(md.digest((salt + n).toByteArray(StandardCharsets.UTF_8)))
            if (hex == challenge) {
                val took = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                val payload = mapOf(
                    "algorithm" to algorithm,
                    "challenge" to challenge,
                    "number" to n,
                    "salt" to salt,
                    "signature" to (root.get("signature")?.asText("") ?: ""),
                    "took" to took,
                )
                return Base64.getEncoder().encodeToString(SoloLatinoMarkup.jsonMapper.writeValueAsBytes(payload))
            }
        }
        return null
    }

    fun findRedirect(html: String): String? {
        for (rx in REDIRECT_PATTERNS) {
            val m = rx.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!m.isNullOrBlank()) return m
        }
        return null
    }

    fun decryptF7(encoded: String): JsonNode? = runCatching {
        val step1 = rot13(encoded.trim())
        var cleaned = step1
        for (marker in JUNK_PATTERNS) cleaned = cleaned.replace(marker, "")
        cleaned = cleaned.replace("_", "")
        val step3 = String(SoloLatinoHostHttp.decodeBase64Flexible(cleaned), StandardCharsets.UTF_8)
        val step4 = buildString(step3.length) {
            for (ch in step3) append((ch.code - 3).toChar())
        }
        val step5 = step4.reversed()
        val json = String(SoloLatinoHostHttp.decodeBase64Flexible(step5), StandardCharsets.UTF_8)
        SoloLatinoMarkup.jsonMapper.readTree(json)
    }.getOrNull()

    private fun rot13(input: String): String = buildString(input.length) {
        for (ch in input) {
            append(
                when (ch) {
                    in 'a'..'z' -> ((ch - 'a' + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((ch - 'A' + 13) % 26 + 'A'.code).toChar()
                    else -> ch
                },
            )
        }
    }
}

internal object SoloLatinoVsembedCrypto {
    val DEFAULT_KEY_CHARS = listOf(
        "\uD835\uDD4A", "\uD835\uDD3C", "\uD835\uDD6E", "\uD835\uDE81",
        "\uD835\uDD3C", "\uD835\uDE83", "\uD835\uDCDA", "\uD835\uDCD4",
        "\uD835\uDCE8", "\uD835\uDFD9", "\uD835\uDFDA", "\uD835\uDFDB",
    )
    val DEFAULT_SALT_CHARS = listOf(
        "\uD83D\uDD10", "\uD83D\uDDDD\uFE0F", "\uD83D\uDD11",
        "\uD83D\uDEAA", "\uD83D\uDEE1\uFE0F", "\u26A1",
    )

    private fun generateKeyHex(keyChars: List<String>, epochDay: Long = System.currentTimeMillis() / 86_400_000L): String {
        val sb = StringBuilder()
        for (ch in keyChars) if (ch.isNotEmpty()) sb.append(Character.codePointAt(ch, 0).toString(16))
        sb.append(epochDay.toString(16))
        return sb.toString().take(32).padEnd(32, '0')
    }

    private fun generateIvHex(host: String, saltChars: List<String>): String {
        val sb = StringBuilder()
        for (ch in saltChars) if (ch.isNotEmpty()) sb.append(Character.codePointAt(ch, 0).toString(16))
        if (host.isNotEmpty()) sb.append(SoloLatinoHostHttp.bytesToHex(host.toByteArray(StandardCharsets.UTF_8)))
        return sb.toString().take(32).padEnd(32, '0')
    }

    fun encryptHex(
        plain: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String {
        val keyBytes = SoloLatinoHostHttp.hexToBytes(generateKeyHex(keyChars))
        val ivBytes = SoloLatinoHostHttp.hexToBytes(generateIvHex(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return SoloLatinoHostHttp.bytesToHex(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decryptHex(
        hexCiphertext: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String? = runCatching {
        val keyBytes = SoloLatinoHostHttp.hexToBytes(generateKeyHex(keyChars))
        val ivBytes = SoloLatinoHostHttp.hexToBytes(generateIvHex(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        String(cipher.doFinal(SoloLatinoHostHttp.hexToBytes(hexCiphertext)), StandardCharsets.UTF_8)
    }.getOrNull()
}

internal object SoloLatinoByseCrypto {
    fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun ecKeyPairP256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun toFixedUnsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) System.arraycopy(raw, raw.size - 32, out, 0, 32)
        else System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        return out
    }

    fun toJwkPublicKey(pub: ECPublicKey): Map<String, String> = mapOf(
        "kty" to "EC",
        "crv" to "P-256",
        "x" to base64UrlNoPad(toFixedUnsigned32(pub.w.affineX)),
        "y" to base64UrlNoPad(toFixedUnsigned32(pub.w.affineY)),
    )

    private fun derToP1363(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0].toInt() == 0x30) { "Invalid DER" }
        var pos = 1
        val seqLen = der[pos++].toInt() and 0xFF
        if ((seqLen and 0x80) != 0) pos += (seqLen and 0x7F)
        require(der[pos++].toInt() == 0x02)
        val rLen = der[pos++].toInt() and 0xFF
        val r = der.copyOfRange(pos, pos + rLen)
        pos += rLen
        require(der[pos++].toInt() == 0x02)
        val sLen = der[pos++].toInt() and 0xFF
        val s = der.copyOfRange(pos, pos + sLen)

        val out = ByteArray(64)
        fun copy32(src: ByteArray, dstOff: Int) {
            var st = 0
            while (st < src.size - 1 && src[st].toInt() == 0) st++
            val len = minOf(src.size - st, 32)
            System.arraycopy(src, src.size - len, out, dstOff + (32 - len), len)
        }
        copy32(r, 0)
        copy32(s, 32)
        return out
    }

    fun signNonce(nonce: String, keyPair: KeyPair): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(nonce.toByteArray(StandardCharsets.UTF_8))
        return base64UrlNoPad(derToP1363(sig.sign()))
    }

    suspend fun solvePow(nonce: String, difficulty: Int, maxAttempts: Int = 500_000): String? = withContext(Dispatchers.Default) {
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
            for (i in 0 until len) buf[prefix.size + i] = digits[len - 1 - i]
            val words = hashPow(buf)
            var zeroBits = 0
            for (w in words) {
                if (w == 0) zeroBits += 32 else { zeroBits += Integer.numberOfLeadingZeros(w); break }
            }
            if (zeroBits >= target) return@withContext attempt.toString()
        }
        null
    }

    private fun hashPow(input: ByteArray): IntArray {
        val e = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt())
        fun mix() {
            e[0] += e[1]; e[3] = (e[3] xor e[0]).rotateLeft(16)
            e[2] += e[3]; e[1] = (e[1] xor e[2]).rotateLeft(12)
            e[0] += e[1]; e[3] = (e[3] xor e[0]).rotateLeft(8)
            e[2] += e[3]; e[1] = (e[1] xor e[2]).rotateLeft(7)
        }
        for (b in input) {
            e[0] = (e[0] + (b.toInt() and 0xFF)).rotateLeft(7)
            mix()
        }
        repeat(8) { mix() }
        val r = IntArray(512)
        for (i in 0 until 512) { mix(); r[i] = e[0] xor e[2] }
        val m1 = 0x9e3779b1.toInt()
        val m2 = 0x85ebca77.toInt()
        repeat(2) {
            for (s in 0 until 512) {
                val a = r[s] and 511
                var c = (r[s] + r[a]).rotateLeft(13)
                c = c xor (r[(s + 1) and 511] * m1)
                r[s] = c; e[0] = e[0] xor c; mix()
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
                s = s xor (d * m2)
            }
            out[i] = s xor e[2]
        }
        return out
    }

    fun decryptPlayback(ivB64: String, payloadB64: String, keyParts: List<String>, version: String?): JsonNode? {
        if (keyParts.size < 2) return null
        val verNum = version?.trim()?.toIntOrNull()
        val keyBytes = if (verNum != null && verNum in 1..20 && (verNum - 1) in keyParts.indices && (30 - verNum) in keyParts.indices) {
            SoloLatinoHostHttp.decodeBase64Flexible(keyParts[verNum - 1]) + SoloLatinoHostHttp.decodeBase64Flexible(keyParts[30 - verNum])
        } else {
            SoloLatinoHostHttp.decodeBase64Flexible(keyParts[0]) + SoloLatinoHostHttp.decodeBase64Flexible(keyParts[1])
        }
        val iv = SoloLatinoHostHttp.decodeBase64Flexible(ivB64)
        val ct = SoloLatinoHostHttp.decodeBase64Flexible(payloadB64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        var plain = String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        if (plain.startsWith("\uFEFF")) plain = plain.substring(1)
        return runCatching { SoloLatinoMarkup.jsonMapper.readTree(plain) }.getOrNull()
    }
}

open class SoloLatinoPelisSeriesHoyExtractor : ExtractorApi() {
    override var name = "PelisSeriesHoy"
    override var mainUrl = "https://player.pelisserieshoy.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extractWithClient(
            client = SoloLatinoClient(null),
            url = url,
            referer = referer,
            langTag = null,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    companion object {
        internal suspend fun extractWithClient(
            client: SoloLatinoClient,
            url: String,
            referer: String?,
            langTag: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val origin = SoloLatinoHostHttp.originOf(url, "https://player.pelisserieshoy.com")
            val resolvedServers = client.resolvePelisSeriesHoy(url, referer)
            if (resolvedServers.isEmpty()) return false

            val emitted = AtomicBoolean(false)
            val streamHeaders = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "user-agent" to client.currentUserAgent(),
            )
            coroutineScope {
                resolvedServers.map { srv ->
                    async(Dispatchers.IO) {
                        val effectiveLang = langTag ?: srv.langLabel
                        val sourceLabel = "SoloLatino · $effectiveLang · ${srv.label}"
                        when {
                            !srv.u.isNullOrBlank() && !srv.sig.isNullOrBlank() -> {
                                val proxyM3u8 = SoloLatinoMarkup.buildPelisSeriesHoyHlsProxyUrl(origin, srv.u, srv.sig)
                                val ok = SoloLatinoHlsHelper.emitHlsSafely(
                                    sourceName = sourceLabel,
                                    displayName = srv.label,
                                    masterUrl = proxyM3u8,
                                    referer = "$origin/",
                                    headers = streamHeaders,
                                ) { link ->
                                    emitted.set(true)
                                    callback(link)
                                }
                                ok
                            }
                            !srv.url.isNullOrBlank() && srv.type.equals("mp4", ignoreCase = true) -> {
                                callback(
                                    newExtractorLink(sourceLabel, "${srv.label} MP4", srv.url, INFER_TYPE) {
                                        this.referer = "$origin/"
                                        this.headers = streamHeaders
                                        this.quality = Qualities.Unknown.value
                                    },
                                )
                                emitted.set(true)
                                true
                            }
                            !srv.url.isNullOrBlank() && srv.url.contains(".m3u8", ignoreCase = true) -> {
                                SoloLatinoHlsHelper.emitHlsSafely(
                                    sourceName = sourceLabel,
                                    displayName = srv.label,
                                    masterUrl = srv.url,
                                    referer = "$origin/",
                                    headers = streamHeaders,
                                ) { link ->
                                    emitted.set(true)
                                    callback(link)
                                }
                            }
                            !srv.url.isNullOrBlank() -> {
                                val ok = SoloLatinoExtractorDispatcher.extract(
                                    url = srv.url,
                                    referer = "$origin/",
                                    sourceLabel = sourceLabel,
                                    subtitleCallback = subtitleCallback,
                                ) { link ->
                                    emitted.set(true)
                                    callback(link)
                                }
                                ok
                            }
                            else -> false
                        }
                    }
                }.awaitAll()
            }
            return emitted.get()
        }
    }
}

internal object SoloLatinoEmbed69Resolver {
    private val hostGate = Semaphore(4)

    private fun serverPriority(serverName: String, url: String): Int {
        val lower = "${serverName.lowercase(Locale.ROOT)} ${url.lowercase(Locale.ROOT)}"
        return when {
            lower.contains("voe") || lower.contains("casey") -> 0
            lower.contains("vidhide") || lower.contains("earnvids") || lower.contains("morencius") || lower.contains("callistanise") -> 1
            lower.contains("streamwish") || lower.contains("hglink") || lower.contains("medixiru") || lower.contains("vsembed") -> 2
            lower.contains("filemoon") -> 3
            lower.contains("byse") -> 4
            lower.contains("savefiles") -> 5
            lower.contains("luluvid") || lower.contains("lulu") -> 6
            lower.contains("dood") || lower.contains("dsvplay") || lower.contains("do7go") -> 7
            else -> 10
        }
    }

    suspend fun extract(
        embedUrl: String,
        referer: String?,
        preferredLangTag: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val effRef = referer?.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${SoloLatinoMarkup.MAIN_URL}/"
        val res = runCatching {
            app.get(
                embedUrl,
                referer = effRef,
                headers = mapOf(
                    "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
                ),
                timeout = 14L,
            )
        }.getOrNull() ?: return false
        if (!res.isSuccessful) return false

        val mirrors = SoloLatinoMarkup.decryptEmbed69Page(res.text)
            .sortedBy { serverPriority(it.serverName, it.url) }
        if (mirrors.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        coroutineScope {
            mirrors.map { mirror ->
                async(Dispatchers.IO) {
                    hostGate.withPermit {
                        val lang = mirror.langLabel.ifBlank { preferredLangTag ?: "Latino" }
                        val label = mirror.serverName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                        val sourceLabel = "SoloLatino · $lang · $label"
                        SoloLatinoExtractorDispatcher.extract(
                            url = mirror.url,
                            referer = res.url,
                            sourceLabel = sourceLabel,
                            subtitleCallback = subtitleCallback,
                        ) { link ->
                            emitted.set(true)
                            callback(link)
                        }
                    }
                }
            }.awaitAll()
        }
        return emitted.get()
    }
}

open class SoloLatinoVoeExtractor : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
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
            sourceLabel: String = "Voe",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val baseHeaders = mapOf(
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            )
            val cookies = linkedMapOf<String, String>()
            var currentUrl = url
            var currentRef = referer?.takeIf { it.isNotBlank() } ?: "${SoloLatinoMarkup.MAIN_URL}/"
            var res = runCatching {
                app.get(currentUrl, referer = currentRef, headers = baseHeaders, cookies = cookies, timeout = 12L)
            }.getOrNull() ?: return false
            cookies.putAll(res.cookies)

            repeat(2) {
                if (!res.text.contains("altcha-widget", ignoreCase = true) &&
                    !res.text.contains("application/json", ignoreCase = true)
                ) {
                    val redir = SoloLatinoVoeCrypto.findRedirect(res.text)
                    if (redir != null && redir != res.url) {
                        val nextUrl = SoloLatinoMarkup.resolveUrl(res.url, redir) ?: redir
                        currentRef = res.url
                        currentUrl = nextUrl
                        res = runCatching {
                            app.get(currentUrl, referer = currentRef, headers = baseHeaders, cookies = cookies, timeout = 12L)
                        }.getOrNull() ?: return@repeat
                        cookies.putAll(res.cookies)
                    }
                }
            }

            if (res.text.contains("altcha-widget", ignoreCase = true)) {
                val doc = Jsoup.parse(res.text, res.url)
                val widget: Element? = doc.selectFirst("altcha-widget")
                val challengeAttr = (widget?.attr("challenge")?.takeIf { it.isNotBlank() } ?: widget?.attr("challengeurl"))?.trim().orEmpty()
                val form: Element? = doc.selectFirst("form.access-form, form[method~=^(?i)post$], form[action]")
                val formAction = form?.attr("abs:action")?.takeIf { it.isNotBlank() } ?: res.url
                val tokenInput: Element? = doc.selectFirst("input[name=_token]")
                val token = tokenInput?.attr("value").orEmpty()
                val accessInput: Element? = doc.selectFirst("input[name=access]")
                val accessVal = accessInput?.attr("value") ?: "0"

                if (challengeAttr.isNotEmpty()) {
                    val challengeUrl = SoloLatinoMarkup.resolveUrl(res.url, challengeAttr) ?: challengeAttr
                    val chalRes = runCatching {
                        app.get(challengeUrl, referer = res.url, headers = baseHeaders + ("Accept" to "application/json, */*"), cookies = cookies, timeout = 10L)
                    }.getOrNull()
                    if (chalRes != null) {
                        cookies.putAll(chalRes.cookies)
                        val altchaPayload = SoloLatinoVoeCrypto.solveAltchaSuspend(chalRes.text)
                        if (altchaPayload != null) {
                            val origin = SoloLatinoHostHttp.originOf(res.url)
                            val postRes = runCatching {
                                app.post(
                                    formAction,
                                    referer = res.url,
                                    headers = baseHeaders + mapOf("Origin" to origin, "Content-Type" to "application/x-www-form-urlencoded"),
                                    cookies = cookies,
                                    data = mapOf("_token" to token, "access" to accessVal, "altcha" to altchaPayload),
                                    timeout = 12L,
                                )
                            }.getOrNull()
                            if (postRes != null) {
                                cookies.putAll(postRes.cookies)
                                res = postRes
                                if (!res.text.contains("application/json", ignoreCase = true)) {
                                    val postRedir = SoloLatinoVoeCrypto.findRedirect(res.text)
                                    if (postRedir != null && postRedir != res.url) {
                                        val nextUrl = SoloLatinoMarkup.resolveUrl(res.url, postRedir) ?: postRedir
                                        val r2 = runCatching { app.get(nextUrl, referer = res.url, headers = baseHeaders, cookies = cookies, timeout = 12L) }.getOrNull()
                                        if (r2 != null) {
                                            cookies.putAll(r2.cookies)
                                            res = r2
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val doc = Jsoup.parse(res.text, res.url)
            val jsonScript: Element? = doc.selectFirst("script[type=application/json]")
            val encodedJson = jsonScript?.data()?.trim()?.let { raw ->
                Regex("""\[\s*"([^"]+)"\s*]""").find(raw)?.groupValues?.getOrNull(1)
                    ?: raw.substringAfter("[\"", "").substringBeforeLast("\"]", "")
            }?.takeIf { it.isNotEmpty() }

            if (encodedJson == null) {
                var builtInOk = false
                runCatching {
                    Voe().getUrl(res.url, referer, subtitleCallback) { link ->
                        builtInOk = true
                        callback(link)
                    }
                }
                return builtInOk
            }

            val decrypted = SoloLatinoVoeCrypto.decryptF7(encodedJson) ?: return false
            val origin = SoloLatinoHostHttp.originOf(res.url)
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
            )

            val captionsNode = decrypted.get("captions")
            if (captionsNode != null && captionsNode.isArray) {
                for (cap in captionsNode) {
                    val capUrl = cap.get("file")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: continue
                    val capLabel = cap.get("label")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                    subtitleCallback(newSubtitleFile(capLabel, capUrl))
                }
            }

            val m3u8Url = decrypted.get("source")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (m3u8Url != null) {
                return SoloLatinoHlsHelper.emitHlsSafely(
                    sourceName = sourceLabel,
                    displayName = "Voe",
                    masterUrl = m3u8Url,
                    referer = "$origin/",
                    headers = streamHeaders,
                    callback = callback,
                )
            }
            val mp4Url = decrypted.get("direct_access_url")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (mp4Url != null) {
                callback(
                    newExtractorLink(sourceLabel, "Voe MP4", mp4Url, INFER_TYPE) {
                        this.referer = res.url
                        this.quality = Qualities.Unknown.value
                    },
                )
                return true
            }
            return false
        }
    }
}

class SoloLatinoCaseySeeExtractor : SoloLatinoVoeExtractor() {
    override var mainUrl = "https://caseyseemavoid.com"
}

open class SoloLatinoVidHideExtractor : ExtractorApi() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhidevip.com"
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
            "https://morencius.com",
            "https://callistanise.com",
            "https://vidhidevip.com",
            "https://peytonepre.com",
            "https://smoothpre.com",
            "https://ryderjet.com",
            "https://vidhidepre.com",
            "https://minochinos.com",
        )
        private val UNRELIABLE_HOSTS = setOf("vidhidepro.com", "dhtpre.com")

        suspend fun extract(
            url: String,
            referer: String?,
            sourceLabel: String = "VidHide",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val normalizedUrl = SoloLatinoVidHideParser.normalizeEmbedUrl(url)
            val parsedUri = runCatching { URI(normalizedUrl) }.getOrNull()
            val pathWithQuery = buildString {
                append(parsedUri?.rawPath?.ifEmpty { "/" } ?: "/")
                val q = parsedUri?.rawQuery
                if (!q.isNullOrEmpty()) append("?$q")
            }
            val origOrigin = SoloLatinoHostHttp.originOf(normalizedUrl)
            val origHost = parsedUri?.host?.lowercase(Locale.ROOT).orEmpty()
            val candidateUrls = buildList {
                if (origHost !in UNRELIABLE_HOSTS) add(normalizedUrl)
                for (mirror in MIRROR_ORIGINS) {
                    if (!mirror.equals(origOrigin, ignoreCase = true)) add("$mirror$pathWithQuery")
                }
                if (origHost in UNRELIABLE_HOSTS) add(normalizedUrl)
            }

            val reqHeaders = mapOf(
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            )
            val effRef = referer?.takeIf { it.isNotBlank() } ?: "${SoloLatinoMarkup.MAIN_URL}/"

            var firstFallback: Pair<SoloLatinoVidHideParsed, String>? = null
            var emittedSubs = false

            for (candidate in candidateUrls) {
                val res = try {
                    app.get(candidate, referer = effRef, headers = reqHeaders, timeout = 7L)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (res.code == 404 || res.code == 410) break
                if (!res.isSuccessful) continue

                val attempt = SoloLatinoVidHideParser.parse(res.text, res.url)
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
                    for ((lbl, subUrl) in attempt.subtitles) subtitleCallback(newSubtitleFile(lbl, subUrl))
                    emittedSubs = true
                }
                val streamHeaders = mapOf(
                    "Origin" to attempt.origin,
                    "Referer" to "${attempt.origin}/",
                    "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                )
                val preferredFallback = attempt.streams.firstOrNull { it.first != "hls3" }?.second ?: attempt.streams.first().second
                if (firstFallback == null) firstFallback = attempt to preferredFallback

                for ((_, streamUrl) in attempt.streams) {
                    val links = withTimeoutOrNull(4_500L) {
                        runCatching {
                            M3u8Helper.generateM3u8(sourceLabel, streamUrl, "${attempt.origin}/", headers = streamHeaders)
                        }.getOrElse { emptyList() }
                    }.orEmpty()
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        return true
                    }
                }
            }

            val (fallbackParsed, fallbackUrl) = firstFallback ?: return false
            val streamHeaders = mapOf(
                "Origin" to fallbackParsed.origin,
                "Referer" to "${fallbackParsed.origin}/",
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
            )
            callback(
                newExtractorLink(sourceLabel, "EarnVids", fallbackUrl, ExtractorLinkType.M3U8) {
                    this.referer = "${fallbackParsed.origin}/"
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                },
            )
            return true
        }
    }
}

class SoloLatinoMorenciusExtractor : SoloLatinoVidHideExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://morencius.com"
}

open class SoloLatinoStreamWishExtractor : ExtractorApi() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
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
            sourceLabel: String = "StreamWish",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val baseHeaders = mapOf(
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            )
            val effRef = referer?.takeIf { it.isNotBlank() } ?: "${SoloLatinoMarkup.MAIN_URL}/"
            var res = runCatching {
                app.get(url, referer = effRef, headers = baseHeaders, timeout = 8L)
            }.getOrNull() ?: return false

            val unpacked = SoloLatinoVidHideParser.parse(res.text, res.url)
            if (unpacked.streams.isNotEmpty() && emitParsed(unpacked, sourceLabel, subtitleCallback, callback)) {
                return true
            }

            if (!isVsembedUrl(res.url) &&
                (res.text.contains("main.js", ignoreCase = true) ||
                    res.text.contains("Loading...", ignoreCase = true) ||
                    !res.text.contains("jwplayer", ignoreCase = true))
            ) {
                val fileCode = SoloLatinoHostHttp.fileCodeFromPath(url)
                if (fileCode.isNotEmpty()) {
                    for (mirror in REDIRECT_HOSTS) {
                        val hopped = runCatching {
                            app.get("$mirror/e/$fileCode", referer = res.url, headers = baseHeaders, timeout = 6L)
                        }.getOrNull() ?: continue
                        res = hopped
                        if (isVsembedUrl(res.url)) break
                        val mirrorParsed = SoloLatinoVidHideParser.parse(res.text, res.url)
                        if (mirrorParsed.streams.isNotEmpty() && emitParsed(mirrorParsed, sourceLabel, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }

            if (isVsembedUrl(res.url)) {
                return extractFromVsembed(res.url, res.text, sourceLabel, subtitleCallback, callback)
            }

            var builtInOk = false
            runCatching {
                StreamWishExtractor().getUrl(url, referer, subtitleCallback) { link ->
                    builtInOk = true
                    callback(link)
                }
            }
            if (builtInOk) return true

            val doc = Jsoup.parse(res.text, res.url)
            val scriptData = doc.select("script")
                .firstOrNull { it.data().contains("sources:") || it.data().contains("jwplayer(") }
                ?.data()
            if (!scriptData.isNullOrBlank()) {
                val jwParsed = SoloLatinoVidHideParser.parse(scriptData, res.url)
                if (jwParsed.streams.isNotEmpty() && emitParsed(jwParsed, sourceLabel, subtitleCallback, callback)) {
                    return true
                }
            }
            return false
        }

        private suspend fun emitParsed(
            parsed: SoloLatinoVidHideParsed,
            sourceLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val streamHeaders = mapOf(
                "Origin" to parsed.origin,
                "Referer" to "${parsed.origin}/",
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
            )
            for ((lbl, subUrl) in parsed.subtitles) subtitleCallback(newSubtitleFile(lbl, subUrl))
            var emitted = false
            for ((key, streamUrl) in parsed.streams) {
                if (key == "hls3" && emitted) continue
                val ok = SoloLatinoHlsHelper.emitHlsSafely(
                    sourceName = sourceLabel,
                    displayName = "StreamWish",
                    masterUrl = streamUrl,
                    referer = "${parsed.origin}/",
                    headers = streamHeaders,
                ) { link ->
                    emitted = true
                    callback(link)
                }
                if (ok) return true
            }
            return false
        }

        private suspend fun extractFromVsembed(
            pageUrl: String,
            pageHtml: String,
            sourceLabel: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return false
            val origin = "${uri.scheme}://${uri.authority}"
            val host = uri.host ?: "vsembed.com"
            val shortCode = uri.rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
            if (shortCode.isEmpty()) return false

            val apiHeaders = mapOf(
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to pageUrl,
            )

            var keyChars = SoloLatinoVsembedCrypto.DEFAULT_KEY_CHARS
            var saltChars = SoloLatinoVsembedCrypto.DEFAULT_SALT_CHARS
            var encParam = SoloLatinoVsembedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
            var trackRes = runCatching {
                app.get("$origin/api/stream/tracking?c=$encParam&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 8L)
            }.getOrNull()
            var decryptedJson = trackRes?.headers?.get("x-tracking")?.let {
                SoloLatinoVsembedCrypto.decryptHex(it, host, keyChars, saltChars)
            }

            if (decryptedJson == null) {
                val assetPath = JS_ASSET_REGEX.find(pageHtml)?.groupValues?.getOrNull(1)
                if (assetPath != null) {
                    val assetUrl = SoloLatinoMarkup.resolveUrl(pageUrl, assetPath) ?: "$origin$assetPath"
                    val bundleText = runCatching {
                        app.get(assetUrl, referer = pageUrl, headers = apiHeaders, timeout = 8L).text
                    }.getOrNull()
                    if (bundleText != null) {
                        val parsedKeys = parseCharArray(bundleText, "keyChars")
                        val parsedSalts = parseCharArray(bundleText, "saltChars")
                        if (!parsedKeys.isNullOrEmpty() && !parsedSalts.isNullOrEmpty()) {
                            keyChars = parsedKeys
                            saltChars = parsedSalts
                            encParam = SoloLatinoVsembedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
                            trackRes = runCatching {
                                app.get("$origin/api/stream/tracking?c=$encParam&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 8L)
                            }.getOrNull()
                            decryptedJson = trackRes?.headers?.get("x-tracking")?.let {
                                SoloLatinoVsembedCrypto.decryptHex(it, host, keyChars, saltChars)
                            }
                        }
                    }
                }
            }

            val jsonText = decryptedJson ?: return false
            val root = runCatching { SoloLatinoMarkup.jsonMapper.readTree(jsonText) }.getOrNull() ?: return false
            val streamNode = root.get("stream") ?: root
            val hlsUrl = streamNode.get("hls")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            val tiktokUrl = streamNode.get("tiktok")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
            )

            var emitted = false
            for (candidate in listOfNotNull(hlsUrl, tiktokUrl).distinct()) {
                val ok = SoloLatinoHlsHelper.emitHlsSafely(
                    sourceName = sourceLabel,
                    displayName = "StreamWish",
                    masterUrl = candidate,
                    referer = "$origin/",
                    headers = streamHeaders,
                ) { link ->
                    emitted = true
                    callback(link)
                }
                if (ok) break
            }
            if (emitted) {
                runCatching {
                    val infoRes = app.get("$origin/api/stream?s=$shortCode&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 6L)
                    if (infoRes.isSuccessful) {
                        val subs = SoloLatinoMarkup.jsonMapper.readTree(infoRes.text).get("data")?.get("subtitles")
                        if (subs != null && subs.isArray) {
                            for (sub in subs) {
                                val u = sub.get("file")?.asText() ?: sub.get("url")?.asText() ?: continue
                                val lbl = sub.get("label")?.asText() ?: sub.get("language")?.asText() ?: "Subtítulos"
                                if (u.startsWith("http", ignoreCase = true)) {
                                    subtitleCallback(newSubtitleFile(lbl, u))
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
                .find(bundleText)?.groupValues?.getOrNull(1) ?: return null
            return runCatching {
                SoloLatinoMarkup.jsonMapper.readValue(match, object : TypeReference<List<String>>() {})
            }.getOrNull()
        }
    }
}

class SoloLatinoHglinkExtractor : SoloLatinoStreamWishExtractor() {
    override var mainUrl = "https://hglink.to"
}

open class SoloLatinoByseExtractor : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
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
        private const val ATTEST_TTL_MS = 10L * 60L * 1000L

        private data class AttestedState(
            val keyPair: KeyPair,
            val fingerprint: Map<String, Any?>,
            val cookies: MutableMap<String, String>,
            val attestedAtMs: Long,
        )

        private val originStates = ConcurrentHashMap<String, AttestedState>()
        private val originMutexes = ConcurrentHashMap<String, Mutex>()

        private fun jsonBody(payload: Any) =
            SoloLatinoMarkup.jsonMapper.writeValueAsString(payload).toRequestBody(SoloLatinoHostHttp.JSON_MEDIA)

        private suspend fun getAttestedState(origin: String, referer: String, forceRefresh: Boolean = false): AttestedState? {
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
                val keyPair = if (forceRefresh) SoloLatinoByseCrypto.ecKeyPairP256() else rechecked?.keyPair ?: SoloLatinoByseCrypto.ecKeyPairP256()
                val baseHeaders = mapOf(
                    "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                    "Accept" to "application/json, */*",
                    "Origin" to origin,
                    "Referer" to referer,
                )
                val chalRes = runCatching {
                    app.post("$origin/api/videos/access/challenge", headers = baseHeaders, cookies = cookies, requestBody = jsonBody(emptyMap<String, Any>()), timeout = 10L)
                }.getOrNull() ?: return@withLock null
                cookies.putAll(chalRes.cookies)
                if (!chalRes.isSuccessful) return@withLock null

                val chalNode = runCatching { SoloLatinoMarkup.jsonMapper.readTree(chalRes.text) }.getOrNull() ?: return@withLock null
                val challengeId = chalNode.get("challenge_id")?.asText()?.trim().orEmpty()
                val nonce = chalNode.get("nonce")?.asText()?.trim().orEmpty()
                if (challengeId.isEmpty() || nonce.isEmpty()) return@withLock null

                val signature = SoloLatinoByseCrypto.signNonce(nonce, keyPair)
                val pubJwk = SoloLatinoByseCrypto.toJwkPublicKey(keyPair.public as ECPublicKey)
                val attestPayload = linkedMapOf<String, Any?>(
                    "viewer_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("viewer_id"),
                    "device_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("device_id"),
                    "challenge_id" to challengeId,
                    "nonce" to nonce,
                    "signature" to signature,
                    "public_key" to pubJwk,
                    "client" to mapOf(
                        "user_agent" to SoloLatinoHostHttp.MOBILE_UA,
                        "platform" to "Linux armv8l",
                        "languages" to listOf("es-419", "es"),
                        "timezone" to "America/Mexico_City",
                    ),
                    "storage" to emptyMap<String, Any>(),
                    "attributes" to mapOf("entropy" to "low"),
                )
                val attestRes = runCatching {
                    app.post("$origin/api/videos/access/attest", headers = baseHeaders, cookies = cookies, requestBody = jsonBody(attestPayload), timeout = 10L)
                }.getOrNull() ?: return@withLock null
                cookies.putAll(attestRes.cookies)
                if (!attestRes.isSuccessful) return@withLock null

                val attNode = runCatching { SoloLatinoMarkup.jsonMapper.readTree(attestRes.text) }.getOrNull() ?: return@withLock null
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
                val st = AttestedState(keyPair, fp, Collections.synchronizedMap(cookies), System.currentTimeMillis())
                originStates[origin] = st
                st
            }
        }

        private fun parsePlaybackNode(rawJson: String): JsonNode? {
            val root = runCatching { SoloLatinoMarkup.jsonMapper.readTree(rawJson) }.getOrNull() ?: return null
            val pb = root.get("playback") ?: return null
            val iv = pb.get("iv")?.asText().orEmpty()
            val payload = pb.get("payload")?.asText().orEmpty()
            val version = pb.get("version")?.asText()
            val keyParts = pb.get("key_parts")?.mapNotNull { it.asText() }.orEmpty()
            if (iv.isBlank() || payload.isBlank() || keyParts.size < 2) return null
            return SoloLatinoByseCrypto.decryptPlayback(iv, payload, keyParts, version)
        }

        suspend fun extract(
            url: String,
            referer: String?,
            sourceLabel: String = "Byse",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val cleanUrl = url.substringBefore('#')
            val base = SoloLatinoHostHttp.originOf(cleanUrl, "https://byse.sx")
            val code = SoloLatinoHostHttp.fileCodeFromPath(cleanUrl)
            if (code.isBlank()) return false

            val baseHeaders = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA, "Accept" to "*/*")
            val detailsRes = runCatching {
                app.get("$base/api/videos/$code/embed/details", referer = cleanUrl, headers = baseHeaders, timeout = 8L)
            }.getOrNull()?.takeIf { it.isSuccessful } ?: runCatching {
                app.get("$base/api/videos/$code/details", referer = cleanUrl, headers = baseHeaders, timeout = 8L)
            }.getOrNull()?.takeIf { it.isSuccessful }

            val embedFrameUrl = detailsRes?.text?.let { raw ->
                runCatching { SoloLatinoMarkup.jsonMapper.readTree(raw).get("embed_frame_url")?.asText()?.trim() }.getOrNull()
            }?.takeIf { it.startsWith("http", ignoreCase = true) }

            val targets = buildList {
                if (!embedFrameUrl.isNullOrBlank()) {
                    val fOrigin = SoloLatinoHostHttp.originOf(embedFrameUrl, base)
                    val fCode = SoloLatinoHostHttp.fileCodeFromPath(embedFrameUrl).ifBlank { code }
                    add(Triple(fOrigin, fCode, embedFrameUrl))
                }
                if (embedFrameUrl == null || !embedFrameUrl.startsWith(base, ignoreCase = true)) {
                    add(Triple(base, code, cleanUrl))
                }
            }

            for ((targetOrigin, targetCode, targetRef) in targets) {
                val effRef = targetRef.ifBlank { "$targetOrigin/" }
                val quickGet = runCatching {
                    app.get(
                        "$targetOrigin/api/videos/$targetCode/embed/playback",
                        headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA, "Accept" to "*/*", "Referer" to effRef, "X-Embed-Parent" to cleanUrl),
                        timeout = 8L,
                    )
                }.getOrNull()
                var decrypted = quickGet?.takeIf { it.isSuccessful }?.let { parsePlaybackNode(it.text) }

                if (decrypted == null) {
                    val state = getAttestedState(targetOrigin, effRef)
                    if (state != null) {
                        val embedParent = referer?.takeIf { it.isNotBlank() } ?: "${SoloLatinoMarkup.MAIN_URL}/"
                        val embedHost = SoloLatinoMarkup.hostOf(embedParent).ifEmpty { "sololatino.net" }
                        suspend fun tryPb(st: AttestedState, isEmbed: Boolean): JsonNode? {
                            val prefix = if (isEmbed) "embed/" else ""
                            val pbHeaders = linkedMapOf(
                                "user-agent" to SoloLatinoHostHttp.MOBILE_UA,
                                "Accept" to "application/json, */*",
                                "Origin" to targetOrigin,
                                "Referer" to effRef,
                            ).apply {
                                if (isEmbed) {
                                    put("X-Embed-Origin", embedHost)
                                    put("X-Embed-Referer", embedParent)
                                    put("X-Embed-Parent", embedParent)
                                }
                            }
                            val bodyMap = mapOf("fingerprint" to st.fingerprint)
                            var pbRes = app.post("$targetOrigin/api/videos/$targetCode/${prefix}playback", headers = pbHeaders, cookies = st.cookies, requestBody = jsonBody(bodyMap), timeout = 10L)
                            st.cookies.putAll(pbRes.cookies)
                            if (pbRes.code == 428 || pbRes.text.contains("captcha_required", ignoreCase = true)) {
                                val capRes = app.post("$targetOrigin/api/videos/$targetCode/${prefix}captcha", headers = pbHeaders, cookies = st.cookies, requestBody = jsonBody(bodyMap), timeout = 10L)
                                st.cookies.putAll(capRes.cookies)
                                if (!capRes.isSuccessful) return null
                                val capNode = runCatching { SoloLatinoMarkup.jsonMapper.readTree(capRes.text) }.getOrNull() ?: return null
                                val powNonce = capNode.get("pow_nonce")?.asText().orEmpty()
                                val powDiff = capNode.get("pow_difficulty")?.asInt(12) ?: 12
                                val powToken = capNode.get("pow_token")?.asText().orEmpty()
                                if (powNonce.isEmpty() || powToken.isEmpty()) return null
                                val sol = SoloLatinoByseCrypto.solvePow(powNonce, powDiff) ?: return null
                                val verRes = app.post(
                                    "$targetOrigin/api/videos/$targetCode/${prefix}captcha/verify",
                                    headers = pbHeaders,
                                    cookies = st.cookies,
                                    requestBody = jsonBody(mapOf("pow_token" to powToken, "solution" to sol, "fingerprint" to st.fingerprint)),
                                    timeout = 10L,
                                )
                                st.cookies.putAll(verRes.cookies)
                                if (!verRes.isSuccessful) return null
                                val capToken = runCatching { SoloLatinoMarkup.jsonMapper.readTree(verRes.text).get("token")?.asText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
                                pbRes = app.post("$targetOrigin/api/videos/$targetCode/${prefix}playback", headers = pbHeaders + ("X-Captcha-Token" to capToken), cookies = st.cookies, requestBody = jsonBody(bodyMap), timeout = 10L)
                                st.cookies.putAll(pbRes.cookies)
                            }
                            return if (pbRes.isSuccessful) parsePlaybackNode(pbRes.text) else null
                        }
                        decrypted = runCatching { tryPb(state, false) }.getOrNull()
                            ?: runCatching { tryPb(state, true) }.getOrNull()
                    }
                }

                val sourcesNode = decrypted?.get("sources")
                if (sourcesNode != null && sourcesNode.isArray && sourcesNode.size() > 0) {
                    val tracks = decrypted.get("tracks") ?: decrypted.get("subtitles")
                    if (tracks != null && tracks.isArray) {
                        for (sub in tracks) {
                            val subUrl = (sub.get("url")?.asText() ?: sub.get("file")?.asText())?.trim().orEmpty()
                            if (subUrl.startsWith("http", ignoreCase = true)) {
                                val lbl = (sub.get("label")?.asText() ?: sub.get("language")?.asText())?.trim()?.ifBlank { null } ?: "Subtítulos"
                                subtitleCallback(newSubtitleFile(lbl, subUrl))
                            }
                        }
                    }

                    val sortedSources = sourcesNode.mapNotNull { n ->
                        val u = n.get("url")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@mapNotNull null
                        Triple(u, n.get("height")?.asInt(0) ?: 0, n.get("label")?.asText()?.trim())
                    }.sortedByDescending { it.second }

                    val best = sortedSources.firstOrNull() ?: continue
                    val streamHeaders = mapOf("Origin" to targetOrigin, "Referer" to "$targetOrigin/", "user-agent" to SoloLatinoHostHttp.MOBILE_UA)
                    val isM3u8 = best.first.contains(".m3u8", ignoreCase = true) || best.first.contains("/hls/", ignoreCase = true) || best.first.contains("master", ignoreCase = true)
                    if (isM3u8) {
                        return SoloLatinoHlsHelper.emitHlsSafely(
                            sourceName = sourceLabel,
                            displayName = best.third ?: nameOf(sourceLabel),
                            masterUrl = best.first,
                            referer = "$targetOrigin/",
                            headers = streamHeaders,
                            fallbackQuality = if (best.second > 0) best.second else Qualities.Unknown.value,
                            callback = callback,
                        )
                    } else {
                        callback(
                            newExtractorLink(sourceLabel, best.third ?: "Video", best.first, INFER_TYPE) {
                                this.referer = "$targetOrigin/"
                                this.headers = streamHeaders
                                this.quality = if (best.second > 0) best.second else Qualities.Unknown.value
                            },
                        )
                        return true
                    }
                }
            }
            return false
        }

        private fun nameOf(sourceLabel: String): String =
            sourceLabel.substringAfterLast("·").trim().ifBlank { "Video" }
    }
}

open class SoloLatinoFilemoonExtractor : SoloLatinoByseExtractor() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"

    companion object {
        suspend fun extractFilemoon(
            url: String,
            referer: String?,
            sourceLabel: String = "Filemoon",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (SoloLatinoByseExtractor.extract(url, referer, sourceLabel, subtitleCallback, callback)) {
                return true
            }
            val cleanUrl = url.substringBefore('#')
            val base = SoloLatinoHostHttp.originOf(cleanUrl, "https://filemoon.sx")
            val html = runCatching {
                app.get(cleanUrl, referer = referer ?: "$base/", headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA), timeout = 10L).text
            }.getOrNull() ?: return false
            val parsed = SoloLatinoVidHideParser.parse(html, cleanUrl)
            if (parsed.streams.isNotEmpty()) {
                val streamHeaders = mapOf("Referer" to "$base/", "Origin" to base, "user-agent" to SoloLatinoHostHttp.MOBILE_UA)
                for ((lbl, sub) in parsed.subtitles) subtitleCallback(newSubtitleFile(lbl, sub))
                return SoloLatinoHlsHelper.emitHlsSafely(
                    sourceName = sourceLabel,
                    displayName = "Filemoon",
                    masterUrl = parsed.streams.first().second,
                    referer = "$base/",
                    headers = streamHeaders,
                    callback = callback,
                )
            }
            return false
        }
    }
}

open class SoloLatinoSavefilesExtractor : ExtractorApi() {
    override var name = "Savefiles"
    override var mainUrl = "https://savefiles.com"
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
            sourceLabel: String = "Savefiles",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val base = SoloLatinoHostHttp.originOf(url, "https://savefiles.com")
            val code = SoloLatinoHostHttp.fileCodeFromPath(url)
            if (code.isBlank()) return false
            val dlResp = runCatching {
                app.post(
                    "$base/dl",
                    data = mapOf("op" to "embed", "file_code" to code, "auto" to "1", "referer" to (referer ?: "${SoloLatinoMarkup.MAIN_URL}/")),
                    headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA, "Referer" to "$base/e/$code", "Origin" to base),
                    timeout = 12L,
                )
            }.getOrNull() ?: return false
            if (!dlResp.isSuccessful) return false

            val parsed = SoloLatinoVidHideParser.parse(dlResp.text, dlResp.url)
            for ((lbl, subUrl) in parsed.subtitles) subtitleCallback(newSubtitleFile(lbl, subUrl))
            val streamUrl = parsed.streams.firstOrNull()?.second ?: return false
            val hdrs = mapOf("Referer" to "$base/", "Origin" to base)
            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                return SoloLatinoHlsHelper.emitHlsSafely(sourceLabel, "Savefiles", streamUrl, "$base/", hdrs, callback = callback)
            }
            callback(newExtractorLink(sourceLabel, "Savefiles", streamUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$base/"; this.headers = hdrs
            })
            return true
        }
    }
}

open class SoloLatinoLulustreamExtractor : ExtractorApi() {
    override var name = "Lulustream"
    override var mainUrl = "https://lulustream.com"
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
            sourceLabel: String = "Lulustream",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val base = SoloLatinoHostHttp.originOf(url, "https://lulustream.com")
            val resp = runCatching {
                app.get(url, referer = referer ?: "$base/", headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA), timeout = 10L)
            }.getOrNull() ?: return false
            if (!resp.isSuccessful) return false
            val parsed = SoloLatinoVidHideParser.parse(resp.text, resp.url)
            for ((lbl, sub) in parsed.subtitles) subtitleCallback(newSubtitleFile(lbl, sub))
            val streamUrl = parsed.streams.firstOrNull()?.second ?: return false
            val hdrs = mapOf("Referer" to "$base/", "Origin" to base, "user-agent" to SoloLatinoHostHttp.MOBILE_UA)
            return SoloLatinoHlsHelper.emitHlsSafely(sourceLabel, "Lulustream", streamUrl, "$base/", hdrs, callback = callback)
        }
    }
}

open class SoloLatinoDoodExtractor : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://dood.watch"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extract(url, name, callback)
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        suspend fun extract(url: String, sourceLabel: String = "DoodStream", callback: (ExtractorLink) -> Unit): Boolean {
            val embedUrl = url.replace("/d/", "/e/")
            val req = runCatching {
                app.get(embedUrl, headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA), timeout = 10L)
            }.getOrNull() ?: return false
            if (!req.isSuccessful) return false
            val host = SoloLatinoHostHttp.originOf(req.url, "https://dood.watch")
            val passMd5Path = Regex("""/pass_md5/[^'"]+""").find(req.text)?.value ?: return false
            val md5Resp = runCatching {
                app.get("$host$passMd5Path", referer = req.url, headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA), timeout = 10L)
            }.getOrNull() ?: return false
            val prefix = md5Resp.text.trim()
            if (!prefix.startsWith("http", ignoreCase = true)) return false
            val suffix = buildString(10) { repeat(10) { append(ALPHABET.random()) } }
            val finalVideo = "$prefix$suffix?token=${passMd5Path.substringAfterLast('/')}&expiry=${System.currentTimeMillis()}"
            val quality = Regex("""\d{3,4}[pP]""")
                .find(req.text.substringAfter("<title>", "").substringBefore("</title>", ""))
                ?.value
            callback(
                newExtractorLink(sourceLabel, "DoodStream", finalVideo, ExtractorLinkType.VIDEO) {
                    this.referer = "$host/"
                    this.quality = getQualityFromName(quality)
                },
            )
            return true
        }
    }
}

open class SoloLatinoUqloadExtractor : ExtractorApi() {
    override var name = "Uqload"
    override var mainUrl = "https://uqload.cx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extract(url, referer, name, callback)
    }

    companion object {
        private val MP4_REGEX = Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+\.mp4[^"']*)["']""")

        suspend fun extract(
            url: String,
            referer: String?,
            sourceLabel: String = "Uqload",
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val req = runCatching {
                app.get(url, referer = referer, headers = mapOf("user-agent" to SoloLatinoHostHttp.MOBILE_UA), timeout = 12L)
            }.getOrNull() ?: return false
            if (!req.isSuccessful) return false
            val base = SoloLatinoHostHttp.originOf(req.url, "https://uqload.cx")
            val streamUrl = MP4_REGEX.find(req.text)?.groupValues?.getOrNull(1)
                ?: SoloLatinoVidHideParser.parse(req.text, req.url).streams.firstOrNull()?.second
                ?: return false
            val hdrs = mapOf("Referer" to "$base/", "Origin" to base)
            callback(newExtractorLink(sourceLabel, "Uqload", streamUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$base/"
                this.headers = hdrs
            })
            return true
        }
    }
}

open class SoloLatinoGDriveExtractor : ExtractorApi() {
    override var name = "Google Drive"
    override var mainUrl = "https://drive.google.com"
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
        private val FILE_ID_REGEX = Regex("""(?:/file/d/|/d/|[?&]id=)([A-Za-z0-9_-]{12,})""")

        fun extractFileId(url: String): String? =
            FILE_ID_REGEX.find(url)?.groupValues?.getOrNull(1)

        suspend fun extract(
            url: String,
            referer: String?,
            sourceLabel: String = "Google Drive",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val fileId = extractFileId(url)
            if (!fileId.isNullOrBlank()) {
                val downloadUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                val hdrs = mapOf("user-agent" to SoloLatinoHostHttp.DESKTOP_UA)
                val res = runCatching {
                    app.get(downloadUrl, headers = hdrs + ("Range" to "bytes=0-1024"), timeout = 14L)
                }.getOrNull()
                if (res != null && (res.isSuccessful || res.code == 206)) {
                    val contentType = (res.headers["content-type"] ?: res.headers["Content-Type"]).orEmpty().lowercase(Locale.ROOT)
                    if (contentType.startsWith("video/") || contentType.contains("octet-stream") || res.code == 206) {
                        callback(
                            newExtractorLink(sourceLabel, "Google Drive", res.url, INFER_TYPE) {
                                this.headers = hdrs
                                this.quality = Qualities.Unknown.value
                            },
                        )
                        return true
                    }
                    val doc = Jsoup.parse(res.text, res.url)
                    val dlForm: Element? = doc.selectFirst("form#download-form")
                    val action = (dlForm?.attr("abs:action")?.takeIf { it.isNotBlank() } ?: dlForm?.attr("action"))?.trim()
                    if (!action.isNullOrBlank()) {
                        val params = dlForm?.select("input[name]").orEmpty().mapNotNull { inp ->
                            val n = inp.attr("name").trim()
                            val v = inp.attr("value").trim()
                            if (n.isNotEmpty()) "$n=${java.net.URLEncoder.encode(v, StandardCharsets.UTF_8.name())}" else null
                        }
                        val fullDl = if (params.isNotEmpty()) {
                            val sep = if ('?' in action) "&" else "?"
                            "$action$sep${params.joinToString("&")}"
                        } else action
                        callback(
                            newExtractorLink(sourceLabel, "Google Drive", fullDl, INFER_TYPE) {
                                this.headers = hdrs
                                this.quality = Qualities.Unknown.value
                            },
                        )
                        return true
                    }
                }
            }
            var builtInOk = false
            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    builtInOk = true
                    callback(link)
                }
            }
            return builtInOk
        }
    }
}

internal object SoloLatinoExtractorDispatcher {
    suspend fun extract(
        url: String,
        referer: String?,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val lower = cleanUrl.lowercase(Locale.ROOT)

        // Direct media files
        if (lower.substringBefore('?').endsWith(".m3u8")) {
            val origin = SoloLatinoHostHttp.originOf(cleanUrl)
            return SoloLatinoHlsHelper.emitHlsSafely(
                sourceName = sourceLabel,
                displayName = "HLS",
                masterUrl = cleanUrl,
                referer = referer ?: "$origin/",
                headers = mapOf("Referer" to (referer ?: "$origin/")),
                callback = callback,
            )
        }
        if (lower.substringBefore('?').endsWith(".mp4")) {
            val origin = SoloLatinoHostHttp.originOf(cleanUrl)
            callback(
                newExtractorLink(sourceLabel, "MP4", cleanUrl, INFER_TYPE) {
                    this.referer = referer ?: "$origin/"
                    this.quality = Qualities.Unknown.value
                },
            )
            return true
        }

        val ok = when {
            lower.contains("voe.sx") || lower.contains("caseyseemavoid") || lower.contains("crystaltreatmente") ->
                SoloLatinoVoeExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("morencius") || lower.contains("vidhide") || lower.contains("earnvids") ||
                lower.contains("callistanise") || lower.contains("minochinos") || lower.contains("peytonepre") ||
                lower.contains("smoothpre") || lower.contains("ryderjet") || lower.contains("movearnpre") ||
                lower.contains("dingtezuni") || lower.contains("mivalyo") || lower.contains("taylorplayer") ->
                SoloLatinoVidHideExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("streamwish") || lower.contains("hglink") || lower.contains("medixiru") ||
                lower.contains("vsembed") || lower.contains("flaswish") || lower.contains("hlswish") ||
                lower.contains("playerwish") || lower.contains("dhcplay") || lower.contains("strwish") ->
                SoloLatinoStreamWishExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("filemoon") ->
                SoloLatinoFilemoonExtractor.extractFilemoon(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("byse") || lower.contains("n1mwq") ->
                SoloLatinoByseExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("savefiles") || lower.contains("streamhls.to") ->
                SoloLatinoSavefilesExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("lulustream") || lower.contains("luluvid") || lower.contains("luluvdo") || lower.contains("lulu.st") ->
                SoloLatinoLulustreamExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            lower.contains("dood") || lower.contains("d000d") || lower.contains("do7go") || lower.contains("dsvplay") || lower.contains("playmogo") ->
                SoloLatinoDoodExtractor.extract(cleanUrl, sourceLabel, callback)
            lower.contains("uqload") ->
                SoloLatinoUqloadExtractor.extract(cleanUrl, referer, sourceLabel, callback)
            lower.contains("drive.google.com") || lower.contains("drive.usercontent.google.com") ->
                SoloLatinoGDriveExtractor.extract(cleanUrl, referer, sourceLabel, subtitleCallback, callback)
            else -> false
        }
        if (ok) return true

        var fallbackOk = false
        runCatching {
            loadExtractor(cleanUrl, referer, subtitleCallback) { link ->
                fallbackOk = true
                callback(link)
            }
        }
        return fallbackOk
    }
}
