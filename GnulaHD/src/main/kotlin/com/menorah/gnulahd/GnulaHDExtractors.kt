package com.menorah.gnulahd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object GnulaHttpDefaults {
    const val UA = GnulaHDMarkup.DEFAULT_USER_AGENT
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    val JSON = jacksonObjectMapper()
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun hostBaseUrl(url: String, fallback: String): String = GnulaHDMarkup.hostBaseUrl(url, fallback)

    fun fileCodeFromPath(url: String): String {
        val clean = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val path = runCatching { URI(clean).path.orEmpty() }.getOrDefault(clean)
        Regex("""/(?:e|d)/([A-Za-z0-9_-]+)""").find(path)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""/embed-([A-Za-z0-9_-]+)""").find(path)?.groupValues?.getOrNull(1)?.let { return it }
        return clean.substringAfterLast('/').substringBefore('.')
    }
}

internal object GnulaHlsHelper {
    private val DEMUXED_AUDIO_REGEX = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO""", RegexOption.IGNORE_CASE)
    private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

    fun hasDemuxedAudio(playlistText: String): Boolean = DEMUXED_AUDIO_REGEX.containsMatchIn(playlistText)

    fun parseMaxResolutionHeight(playlistText: String): Int? =
        RESOLUTION_HEIGHT_REGEX.findAll(playlistText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()

    suspend fun emitHlsSafely(
        sourceName: String,
        displayName: String,
        masterUrl: String,
        referer: String,
        headers: Map<String, String> = emptyMap(),
        fallbackQuality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit,
    ) {
        val playlistText = withTimeoutOrNull(4_500L) {
            runCatching {
                app.get(masterUrl, referer = referer, headers = headers, verify = false, timeout = 4L)
                    .takeIf { it.isSuccessful }
                    ?.text
            }.getOrNull()
        }

        if (!playlistText.isNullOrBlank() && playlistText.contains("#EXTM3U")) {
            val maxHeight = parseMaxResolutionHeight(playlistText) ?: fallbackQuality
            if (hasDemuxedAudio(playlistText)) {
                callback(
                    newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = maxHeight
                    },
                )
                return
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
                return
            }

            callback(
                newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = maxHeight
                },
            )
            return
        }

        callback(
            newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = headers
                this.quality = fallbackQuality
            },
        )
    }
}

internal object GnulaJsUnpacker {
    private val PACKER_START_REGEX =
        Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[rd]\s*\)""")
    private val PARAMS_REGEX =
        Regex("""\}\s*\(\s*(['"])([\s\S]*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])([\s\S]*?)\5\s*\.\s*split\s*\(\s*(['"])\|?\7\s*\)""")
    private val WORD_REGEX = Regex("""\b\w+\b""")

    fun unpackAll(source: String): String {
        if (!source.contains("eval(function(p,a,c,k,e,")) return source
        val results = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < source.length) {
            val start = PACKER_START_REGEX.find(source, searchFrom) ?: break
            val tail = source.substring(start.range.first)
            val m = PARAMS_REGEX.find(tail)
            if (m != null) {
                val payload = m.groupValues[2]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val radix = m.groupValues[3].toIntOrNull() ?: 36
                val count = m.groupValues[4].toIntOrNull() ?: 0
                val symtab = m.groupValues[6].split("|")
                val unpacked = decodePacker(payload, radix, count, symtab)
                if (unpacked.isNotBlank()) results.add(unpacked)
                searchFrom = start.range.first + m.range.last + 1
            } else {
                searchFrom = start.range.last + 1
            }
        }
        return if (results.isEmpty()) source else results.joinToString("\n")
    }

    private fun decodePacker(payload: String, radix: Int, count: Int, symtab: List<String>): String {
        val lookup = HashMap<String, String>(count + 8)
        for (i in 0 until minOf(count, symtab.size)) {
            val token = encodeBaseN(i, radix)
            lookup[token] = symtab[i].ifEmpty { token }
        }
        return WORD_REGEX.replace(payload) { match -> lookup[match.value] ?: match.value }
    }

    private fun encodeBaseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(digits[n % radix])
            n /= radix
        }
        return sb.reverse().toString()
    }
}

internal object GnulaHostParsers {
    private val SOURCES_FILE_REGEX = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+)["']""")
    private val SOURCES_ARRAY_REGEX = Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""")
    private val FILE_URL_REGEX =
        Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TRACK_OBJ_REGEX =
        Regex("""\{[^{}]*?file\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["'][^{}]*\}""", RegexOption.IGNORE_CASE)
    private val VIDSONIC_VAR_REGEX = Regex("""const\s+_0x1\s*=\s*['"]([^'"]+)['"]\s*;""")

    fun extractSavefilesStream(html: String): String? {
        val unpacked = runCatching { GnulaJsUnpacker.unpackAll(html) }.getOrDefault(html)
        return SOURCES_FILE_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: SOURCES_FILE_REGEX.find(unpacked)?.groupValues?.getOrNull(1)
            ?: SOURCES_ARRAY_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: SOURCES_ARRAY_REGEX.find(unpacked)?.groupValues?.getOrNull(1)
            ?: FILE_URL_REGEX.find(unpacked)?.groupValues?.getOrNull(1)
            ?: FILE_URL_REGEX.find(html)?.groupValues?.getOrNull(1)
    }

    fun extractSavefilesSubtitles(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in TRACK_OBJ_REGEX.findAll(html)) {
            val block = m.value
            if (block.contains("thumbnails", ignoreCase = true)) continue
            val url = m.groupValues[1]
            val label = Regex("""label\s*:\s*["']([^"']+)["']""").find(block)?.groupValues?.getOrNull(1)
                ?: "Subtítulos"
            out.add(label to url)
        }
        return out
    }

    fun decodeVidsonicUrl(html: String): String? {
        val encoded = VIDSONIC_VAR_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: html.substringAfter("const _0x1 = ", "").substringBefore(";").replace("'", "").replace("\"", "").trim()
                .takeIf { it.isNotEmpty() }
            ?: return null
        val hex = encoded.replace("|", "").trim()
        if (hex.length < 16 || hex.length % 2 != 0) return null
        val decoded = runCatching {
            hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("").reversed()
        }.getOrNull() ?: return null
        return decoded.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    fun decodeVixeoConfig(dataConfigBase64: String): String? {
        if (dataConfigBase64.isBlank()) return null
        val raw = runCatching {
            String(Base64.getDecoder().decode(dataConfigBase64.trim()), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val node = runCatching { GnulaHttpDefaults.JSON.readTree(raw) }.getOrNull() ?: return null
        return node.get("source")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
    }
}

internal object GnulaVoeDecoder {
    private val REDIRECT_PATTERNS = listOf(
        Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""window\.location\.replace\s*\(\s*['"]([^'"]+)['"]\s*\)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"';\s>]+)["']""", RegexOption.IGNORE_CASE),
    )
    private val JSON_ARRAY_SCRIPT_REGEX = Regex(
        """<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""",
        RegexOption.IGNORE_CASE,
    )
    private val ALTCHA_WIDGET_REGEX = Regex(
        """<altcha-widget[^>]+challengeurl=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val REPL_TOKENS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    fun findRedirectUrl(html: String): String? {
        for (rx in REDIRECT_PATTERNS) {
            val u = rx.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!u.isNullOrBlank() && u.startsWith("http", ignoreCase = true)) return u
        }
        return null
    }

    fun findAltchaChallengeUrl(html: String): String? =
        ALTCHA_WIDGET_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    fun solveAltchaPayload(challengeJson: String): String? {
        val node = runCatching { GnulaHttpDefaults.JSON.readTree(challengeJson) }.getOrNull() ?: return null
        val algorithm = node.path("algorithm").asText("SHA-256")
        if (!algorithm.equals("SHA-256", ignoreCase = true)) return null
        val challenge = node.path("challenge").asText("")
        val salt = node.path("salt").asText("")
        val maxNumber = node.path("maxnumber").asLong(100_000L).coerceAtMost(2_000_000L)
        val signature = node.path("signature").asText("")
        if (challenge.isBlank() || salt.isBlank() || signature.isBlank()) return null

        val md = MessageDigest.getInstance("SHA-256")
        var found: Long? = null
        for (n in 0..maxNumber) {
            val digest = md.digest("$salt$n".toByteArray(StandardCharsets.UTF_8))
            if (digest.joinToString("") { "%02x".format(it) } == challenge) {
                found = n
                break
            }
        }
        val number = found ?: return null
        val payloadMap = mapOf(
            "algorithm" to algorithm,
            "challenge" to challenge,
            "number" to number,
            "salt" to salt,
            "signature" to signature,
        )
        return Base64.getEncoder().encodeToString(GnulaHttpDefaults.JSON.writeValueAsBytes(payloadMap))
    }

    fun decryptF7Payload(encoded: String): JsonNode? {
        return runCatching {
            val step1 = rot13(encoded)
            var step2 = step1
            for (tok in REPL_TOKENS) {
                step2 = step2.replace(tok, "_")
            }
            val step3 = step2.replace("_", "")
            val b1 = String(decodeBase64Flexible(step3), StandardCharsets.UTF_8)
            val shifted = buildString(b1.length) {
                for (ch in b1) append((ch.code - 3).toChar())
            }
            val reversed = shifted.reversed()
            val b2 = String(decodeBase64Flexible(reversed), StandardCharsets.UTF_8)
            GnulaHttpDefaults.JSON.readTree(b2)
        }.getOrNull()
    }

    fun extractPayloadFromHtml(html: String): JsonNode? {
        val raw = JSON_ARRAY_SCRIPT_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        return decryptF7Payload(raw)
    }

    private fun rot13(input: String): String = buildString(input.length) {
        for (c in input) {
            when (c) {
                in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                else -> append(c)
            }
        }
    }

    private fun decodeBase64Flexible(input: String): ByteArray {
        val clean = input.trim().replace("\n", "").replace("\r", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }
}

internal data class GnulaByseSource(
    val url: String,
    val height: Int,
    val bitrateKbps: Int,
    val label: String?,
)

internal data class GnulaByseTrack(
    val label: String,
    val url: String,
)

internal data class GnulaBysePlaybackData(
    val sources: List<GnulaByseSource>,
    val tracks: List<GnulaByseTrack>,
)

internal object GnulaBysePow {
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

        val mult1 = 0x9e3779b1.toInt()
        val mult2 = 0x85ebca77.toInt()
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
            if (w == 0) bits += 32 else return bits + Integer.numberOfLeadingZeros(w)
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
            for (i in 0 until len) buf[prefix.size + i] = digits[len - 1 - i]
            if (countLeadingZeroBits(hashNonce(buf)) >= target) return attempt.toString()
        }
        return null
    }

    suspend fun solve(nonce: String, difficulty: Int, maxAttempts: Int = 500_000): String? =
        withContext(Dispatchers.Default) { solveBlocking(nonce, difficulty, maxAttempts) }
}

internal object GnulaByseCrypto {
    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in B64_ALPHABET.indices) this[B64_ALPHABET[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun decodeBase64Flexible(input: String): ByteArray {
        val cleaned = input.trim().replace("\r", "").replace("\n", "").trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ByteArray((cleaned.length * 3) / 4)
        var outIdx = 0
        var acc = 0
        var bits = 0
        for (ch in cleaned) {
            val code = ch.code
            val val6 = if (code in B64_DECODE_TABLE.indices) B64_DECODE_TABLE[code] else -1
            if (val6 < 0) continue
            acc = (acc shl 6) or val6
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outIdx < out.size) out[outIdx++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun ecKeyPairP256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun toFixedUnsigned32(value: BigInteger): ByteArray {
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
        if ((seqLenByte and 0x80) != 0) pos += (seqLenByte and 0x7F)
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

    fun resolveAesKeyBytes(keyParts: List<String>, version: String?): ByteArray? {
        if (keyParts.size < 2) return null
        val verNum = version?.trim()?.toIntOrNull()
        if (verNum != null && verNum in 1..20) {
            val idx1 = verNum - 1
            val idx2 = 30 - verNum
            if (idx1 in keyParts.indices && idx2 in keyParts.indices) {
                return decodeBase64Flexible(keyParts[idx1]) + decodeBase64Flexible(keyParts[idx2])
            }
        }
        return decodeBase64Flexible(keyParts[0]) + decodeBase64Flexible(keyParts[1])
    }

    fun decryptPlaybackDetails(
        ivB64Url: String,
        payloadB64Url: String,
        keyParts: List<String>,
        version: String? = null,
    ): GnulaBysePlaybackData? {
        val keyBytes = resolveAesKeyBytes(keyParts, version) ?: return null
        val ivBytes = decodeBase64Flexible(ivB64Url)
        val cipherBytes = decodeBase64Flexible(payloadB64Url)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
        var plain = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (plain.startsWith("\uFEFF")) plain = plain.substring(1)

        val root = runCatching { GnulaHttpDefaults.JSON.readTree(plain) }.getOrNull() ?: return null
        val sourcesNode = root.get("sources") ?: return null
        if (!sourcesNode.isArray) return null

        val parsedSources = mutableListOf<GnulaByseSource>()
        for (node in sourcesNode) {
            val u = node.get("url")?.asText()?.trim().orEmpty()
            if (!u.startsWith("http", ignoreCase = true)) continue
            parsedSources += GnulaByseSource(
                url = u,
                height = node.get("height")?.asInt(0) ?: 0,
                bitrateKbps = node.get("bitrate_kbps")?.asInt(0) ?: node.get("bitrate")?.asInt(0) ?: 0,
                label = node.get("label")?.asText()?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        if (parsedSources.isEmpty()) return null
        val ordered = parsedSources.sortedWith(
            compareByDescending<GnulaByseSource> { it.height }.thenByDescending { it.bitrateKbps },
        )

        val tracks = mutableListOf<GnulaByseTrack>()
        val tracksNode = root.get("tracks") ?: root.get("subtitles")
        if (tracksNode != null && tracksNode.isArray) {
            for (sub in tracksNode) {
                val subUrl = (sub.get("url")?.asText() ?: sub.get("file")?.asText())?.trim().orEmpty()
                if (!subUrl.startsWith("http", ignoreCase = true)) continue
                val subLabel = (sub.get("label")?.asText() ?: sub.get("language")?.asText() ?: sub.get("title")?.asText())
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                tracks += GnulaByseTrack(label = subLabel, url = subUrl)
            }
        }
        return GnulaBysePlaybackData(sources = ordered, tracks = tracks)
    }
}

open class GnulaVidaraExtractor : ExtractorApi() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = GnulaHDMarkup.stripFragment(url)
        val base = GnulaHttpDefaults.hostBaseUrl(cleanUrl, mainUrl)
        val fileCode = cleanUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (fileCode.isBlank()) return

        val payload = GnulaHttpDefaults.JSON.writeValueAsString(
            mapOf("filecode" to fileCode, "device" to "web"),
        ).toRequestBody(GnulaHttpDefaults.JSON_MEDIA)

        val headers = mapOf(
            "User-Agent" to GnulaHttpDefaults.UA,
            "Accept" to "application/json, */*",
            "Origin" to base,
            "Referer" to "$base/",
        )

        val res = runCatching {
            app.post("$base/api/stream", headers = headers, requestBody = payload, timeout = 15L)
        }.getOrNull() ?: return
        if (!res.isSuccessful) return

        val root = runCatching { GnulaHttpDefaults.JSON.readTree(res.text) }.getOrNull() ?: return
        val streamingUrl = root.get("streaming_url")?.asText()?.trim().orEmpty()
        if (!streamingUrl.startsWith("http", ignoreCase = true)) return

        val subsNode = root.get("subtitles")
        if (subsNode != null && subsNode.isArray) {
            for (sub in subsNode) {
                val subUrl = (sub.get("file_path") ?: sub.get("url") ?: sub.get("file"))?.asText()?.trim().orEmpty()
                if (!subUrl.startsWith("http", ignoreCase = true)) continue
                val lang = (sub.get("language") ?: sub.get("label"))?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Subtítulos"
                subtitleCallback(newSubtitleFile(lang = lang, url = subUrl))
            }
        }

        val streamHeaders = mapOf("Referer" to "$base/", "Origin" to base)
        GnulaHlsHelper.emitHlsSafely(
            sourceName = name,
            displayName = name,
            masterUrl = streamingUrl,
            referer = "$base/",
            headers = streamHeaders,
            callback = callback,
        )
    }
}

class GnulaVidaraaCcExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidaraa.cc"
}

class GnulaVidaraSoExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidara.so"
}

class GnulaVidaraXCcExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidarax.cc"
}

class GnulaVidarawExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidaraw.com"
}

class GnulaVidaratemExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidaratem.com"
}

class GnulaVidaaraxComExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidaarax.com"
}

class GnulaVidaaraxNetExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidaarax.net"
}

class GnulaVidavacaExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidavaca.net"
}

class GnulaOdysseusaExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://odysseusa.cc"
}

class GnulaHandfacesnapExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://handfacesnap.cc"
}

class GnulaNamefacesnapExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://namefacesnap.cc"
}

class GnulaThebesthostervExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://thebesthosterv.com"
}

class GnulaVidmatrixaExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidmatrixa.com"
}

class GnulaVidChampionsExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://vidchampions.com"
}

class GnulaAntarcticadocsExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://antarcticadocs.com"
}

class GnulaNameitwebExtractor : GnulaVidaraExtractor() {
    override var mainUrl = "https://nameitweb.com"
}

open class GnulaSavefilesExtractor : ExtractorApi() {
    override var name = "Savefiles"
    override var mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = GnulaHDMarkup.stripFragment(url)
        val base = GnulaHttpDefaults.hostBaseUrl(cleanUrl, mainUrl)
        val code = GnulaHttpDefaults.fileCodeFromPath(cleanUrl)
        if (code.isBlank()) return

        val dlResp = runCatching {
            app.post(
                "$base/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to code,
                    "auto" to "1",
                    "referer" to (referer ?: "${GnulaHDMarkup.MAIN_URL}/"),
                ),
                headers = mapOf(
                    "User-Agent" to GnulaHttpDefaults.UA,
                    "Referer" to "$base/e/$code",
                    "Origin" to base,
                ),
                timeout = 15L,
            )
        }.getOrNull() ?: return
        if (!dlResp.isSuccessful) return

        GnulaHostParsers.extractSavefilesSubtitles(dlResp.text).forEach { (lang, subUrl) ->
            subtitleCallback(newSubtitleFile(lang = lang, url = subUrl))
        }

        val streamUrl = GnulaHostParsers.extractSavefilesStream(dlResp.text) ?: return
        val hdrs = mapOf("Referer" to "$base/", "Origin" to base)
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            GnulaHlsHelper.emitHlsSafely(name, name, streamUrl, "$base/", hdrs, callback = callback)
        } else {
            callback(
                newExtractorLink(name, name, streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    this.headers = hdrs
                },
            )
        }
    }
}

class GnulaSavefilesTopExtractor : GnulaSavefilesExtractor() {
    override var mainUrl = "https://savefiles.top"
}

open class GnulaByseExtractor : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://bysevepoin.com"
    override val requiresReferer = true

    companion object {
        private const val ATTEST_TTL_MS = 10L * 60L * 1000L

        private data class AttestedOriginState(
            val keyPair: KeyPair,
            val fingerprint: Map<String, Any?>,
            val cookies: MutableMap<String, String>,
            val attestedAtMs: Long,
        )

        private val originStates = ConcurrentHashMap<String, AttestedOriginState>()
        private val originMutexes = ConcurrentHashMap<String, Mutex>()

        private fun jsonBody(payload: Any) =
            GnulaHttpDefaults.JSON.writeValueAsString(payload).toRequestBody(GnulaHttpDefaults.JSON_MEDIA)

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
                val keyPair = if (forceRefresh) GnulaByseCrypto.ecKeyPairP256() else rechecked?.keyPair ?: GnulaByseCrypto.ecKeyPairP256()
                val baseHeaders = mapOf(
                    "user-agent" to GnulaHttpDefaults.MOBILE_UA,
                    "Accept" to "application/json, */*",
                    "Origin" to origin,
                    "Referer" to referer,
                )

                val chalRes = runCatching {
                    app.post(
                        "$origin/api/videos/access/challenge",
                        headers = baseHeaders,
                        cookies = cookies,
                        requestBody = jsonBody(emptyMap<String, Any>()),
                        timeout = 10L,
                    )
                }.getOrNull() ?: return@withLock null
                cookies.putAll(chalRes.cookies)
                if (!chalRes.isSuccessful) return@withLock null

                val chalNode = runCatching { GnulaHttpDefaults.JSON.readTree(chalRes.text) }.getOrNull() ?: return@withLock null
                val challengeId = chalNode.get("challenge_id")?.asText()?.trim().orEmpty()
                val nonce = chalNode.get("nonce")?.asText()?.trim().orEmpty()
                if (challengeId.isEmpty() || nonce.isEmpty()) return@withLock null

                val signature = GnulaByseCrypto.signChallengeNonce(nonce, keyPair)
                val pubJwk = GnulaByseCrypto.toJwkPublicKey(keyPair.public as ECPublicKey)
                val attestPayload = linkedMapOf<String, Any?>(
                    "viewer_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("viewer_id"),
                    "device_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("device_id"),
                    "challenge_id" to challengeId,
                    "nonce" to nonce,
                    "signature" to signature,
                    "public_key" to pubJwk,
                    "client" to mapOf(
                        "user_agent" to GnulaHttpDefaults.MOBILE_UA,
                        "platform" to "Linux armv8l",
                        "languages" to listOf("es-419", "es"),
                        "timezone" to "America/Mexico_City",
                    ),
                    "storage" to emptyMap<String, Any>(),
                    "attributes" to mapOf("entropy" to "low"),
                )
                val attestRes = runCatching {
                    app.post(
                        "$origin/api/videos/access/attest",
                        headers = baseHeaders,
                        cookies = cookies,
                        requestBody = jsonBody(attestPayload),
                        timeout = 10L,
                    )
                }.getOrNull() ?: return@withLock null
                cookies.putAll(attestRes.cookies)
                if (!attestRes.isSuccessful) return@withLock null

                val attNode = runCatching { GnulaHttpDefaults.JSON.readTree(attestRes.text) }.getOrNull() ?: return@withLock null
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

    private fun parsePlaybackNode(rawJson: String): GnulaBysePlaybackData? {
        val root = runCatching { GnulaHttpDefaults.JSON.readTree(rawJson) }.getOrNull() ?: return null
        val playback = root.get("playback") ?: return null
        val iv = playback.get("iv")?.asText().orEmpty()
        val payload = playback.get("payload")?.asText().orEmpty()
        val version = playback.get("version")?.asText()
        val keyParts = playback.get("key_parts")?.mapNotNull { it.asText() }.orEmpty()
        if (iv.isBlank() || payload.isBlank() || keyParts.size < 2) return null
        return runCatching {
            GnulaByseCrypto.decryptPlaybackDetails(iv, payload, keyParts, version)
        }.getOrNull()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = GnulaHDMarkup.stripFragment(url)
        val base = GnulaHttpDefaults.hostBaseUrl(cleanUrl, mainUrl)
        val code = GnulaHttpDefaults.fileCodeFromPath(cleanUrl)
        if (code.isBlank()) return

        val baseHeaders = mapOf("user-agent" to GnulaHttpDefaults.MOBILE_UA, "Accept" to "*/*")
        val detailsRes = runCatching {
            app.get("$base/api/videos/$code/embed/details", referer = cleanUrl, headers = baseHeaders, timeout = 8L)
        }.getOrNull()?.takeIf { it.isSuccessful } ?: runCatching {
            app.get("$base/api/videos/$code/details", referer = cleanUrl, headers = baseHeaders, timeout = 8L)
        }.getOrNull()?.takeIf { it.isSuccessful }

        val embedFrameUrl = detailsRes?.text?.let { raw ->
            runCatching { GnulaHttpDefaults.JSON.readTree(raw).get("embed_frame_url")?.asText()?.trim() }.getOrNull()
        }?.takeIf { it.startsWith("http", ignoreCase = true) }

        val targetPairs = buildList {
            if (!embedFrameUrl.isNullOrBlank()) {
                val fOrigin = GnulaHttpDefaults.hostBaseUrl(embedFrameUrl, base)
                val fCode = GnulaHttpDefaults.fileCodeFromPath(embedFrameUrl).ifBlank { code }
                add(Triple(fOrigin, fCode, embedFrameUrl))
            }
            if (embedFrameUrl == null || !embedFrameUrl.startsWith(base, ignoreCase = true)) {
                add(Triple(base, code, cleanUrl))
            }
        }

        for ((targetOrigin, targetCode, targetRef) in targetPairs) {
            val effRef = targetRef.ifBlank { "$targetOrigin/" }
            // Fast path: GET embed/playback for unchallenged nodes
            val quickGet = runCatching {
                app.get(
                    "$targetOrigin/api/videos/$targetCode/embed/playback",
                    headers = mapOf(
                        "user-agent" to GnulaHttpDefaults.MOBILE_UA,
                        "Accept" to "*/*",
                        "Referer" to effRef,
                        "X-Embed-Parent" to cleanUrl,
                    ),
                    timeout = 8L,
                )
            }.getOrNull()
            var decrypted = quickGet?.takeIf { it.isSuccessful }?.let { parsePlaybackNode(it.text) }

            if (decrypted == null) {
                val wasCached = originStates[targetOrigin]?.let {
                    (System.currentTimeMillis() - it.attestedAtMs) < ATTEST_TTL_MS
                } == true
                val state = getOrCreateAttestedState(targetOrigin, effRef)

                suspend fun requestPlayback(activeState: AttestedOriginState, isEmbedMode: Boolean): GnulaBysePlaybackData? {
                    val prefix = if (isEmbedMode) "embed/" else ""
                    val embedParent = referer?.takeIf { it.isNotBlank() } ?: "${GnulaHDMarkup.MAIN_URL}/"
                    val embedParentHost = runCatching { URI(embedParent).host.orEmpty() }.getOrDefault("ww3.gnulahd.nu")
                    val pbHeaders = linkedMapOf(
                        "user-agent" to GnulaHttpDefaults.MOBILE_UA,
                        "Accept" to "application/json, */*",
                        "Origin" to targetOrigin,
                        "Referer" to effRef,
                    ).apply {
                        if (isEmbedMode) {
                            put("X-Embed-Origin", embedParentHost)
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
                        timeout = 10L,
                    )
                    activeState.cookies.putAll(pbRes.cookies)

                    if (pbRes.code == 428 || pbRes.text.contains("captcha_required", ignoreCase = true)) {
                        val capRes = app.post(
                            "$targetOrigin/api/videos/$targetCode/${prefix}captcha",
                            headers = pbHeaders,
                            cookies = activeState.cookies,
                            requestBody = jsonBody(bodyMap),
                            timeout = 10L,
                        )
                        activeState.cookies.putAll(capRes.cookies)
                        if (!capRes.isSuccessful) return null

                        val capNode = runCatching { GnulaHttpDefaults.JSON.readTree(capRes.text) }.getOrNull() ?: return null
                        val powNonce = capNode.get("pow_nonce")?.asText().orEmpty()
                        val powDiff = capNode.get("pow_difficulty")?.asInt(12) ?: 12
                        val powToken = capNode.get("pow_token")?.asText().orEmpty()
                        if (powNonce.isEmpty() || powToken.isEmpty()) return null

                        val solution = GnulaBysePow.solve(powNonce, powDiff) ?: return null
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
                            timeout = 10L,
                        )
                        activeState.cookies.putAll(verRes.cookies)
                        if (!verRes.isSuccessful) return null

                        val captchaToken = runCatching {
                            GnulaHttpDefaults.JSON.readTree(verRes.text).get("token")?.asText()?.trim()
                        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

                        pbRes = app.post(
                            "$targetOrigin/api/videos/$targetCode/${prefix}playback",
                            headers = pbHeaders + ("X-Captcha-Token" to captchaToken),
                            cookies = activeState.cookies,
                            requestBody = jsonBody(bodyMap),
                            timeout = 10L,
                        )
                        activeState.cookies.putAll(pbRes.cookies)
                    }

                    if (!pbRes.isSuccessful) return null
                    return parsePlaybackNode(pbRes.text)
                }

                if (state != null) {
                    decrypted = runCatching { requestPlayback(state, isEmbedMode = false) }.getOrNull()
                        ?: runCatching { requestPlayback(state, isEmbedMode = true) }.getOrNull()
                    if (decrypted == null && wasCached) {
                        originStates.remove(targetOrigin, state)
                        val refreshed = getOrCreateAttestedState(targetOrigin, effRef, forceRefresh = true)
                        if (refreshed != null) {
                            decrypted = runCatching { requestPlayback(refreshed, isEmbedMode = false) }.getOrNull()
                                ?: runCatching { requestPlayback(refreshed, isEmbedMode = true) }.getOrNull()
                        }
                    }
                }
            }

            if (decrypted != null && decrypted.sources.isNotEmpty()) {
                for (sub in decrypted.tracks) {
                    subtitleCallback(newSubtitleFile(sub.label, sub.url))
                }
                val streamHeaders = mapOf(
                    "Origin" to targetOrigin,
                    "Referer" to "$targetOrigin/",
                    "user-agent" to GnulaHttpDefaults.MOBILE_UA,
                )
                val best = decrypted.sources.first()
                val isM3u8 = best.url.contains(".m3u8", ignoreCase = true) ||
                    best.url.contains("/hls/", ignoreCase = true) ||
                    best.url.contains("master", ignoreCase = true)
                if (isM3u8) {
                    GnulaHlsHelper.emitHlsSafely(
                        sourceName = name,
                        displayName = best.label?.let { "$name ($it)" } ?: name,
                        masterUrl = best.url,
                        referer = "$targetOrigin/",
                        headers = streamHeaders,
                        fallbackQuality = if (best.height > 0) best.height else Qualities.Unknown.value,
                        callback = callback,
                    )
                } else {
                    callback(
                        newExtractorLink(name, best.label?.let { "$name ($it)" } ?: name, best.url, INFER_TYPE) {
                            this.referer = "$targetOrigin/"
                            this.headers = streamHeaders
                            this.quality = if (best.height > 0) best.height else getQualityFromName(best.label)
                        },
                    )
                }
                return
            }
        }
    }
}

class GnulaByseSxExtractor : GnulaByseExtractor() {
    override var mainUrl = "https://byse.sx"
}

class GnulaByseBuhoExtractor : GnulaByseExtractor() {
    override var mainUrl = "https://bysebuho.com"
}

class GnulaByseZejataosExtractor : GnulaByseExtractor() {
    override var mainUrl = "https://bysezejataos.com"
}

class GnulaByseQekahoExtractor : GnulaByseExtractor() {
    override var mainUrl = "https://byseqekaho.com"
}

class GnulaByseKozeExtractor : GnulaByseExtractor() {
    override var mainUrl = "https://bysekoze.com"
}

class GnulaByseN1mwqExtractor : GnulaByseExtractor() {
    override var mainUrl = "https://n1mwq.org"
}

open class GnulaFilemoonExtractor : GnulaByseExtractor() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var emitted = false
        super.getUrl(url, referer, subtitleCallback) { link ->
            emitted = true
            callback(link)
        }
        if (emitted) return

        val cleanUrl = GnulaHDMarkup.stripFragment(url)
        val base = GnulaHttpDefaults.hostBaseUrl(cleanUrl, mainUrl)
        val html = runCatching {
            app.get(cleanUrl, referer = referer ?: "$base/", headers = mapOf("User-Agent" to GnulaHttpDefaults.UA), timeout = 12L).text
        }.getOrNull() ?: return
        val iframeSrc = Jsoup.parse(html, cleanUrl).selectFirst("iframe[src]")?.attr("src")?.trim()
        val pageHtml = if (!iframeSrc.isNullOrBlank()) {
            val resolvedIframe = GnulaHDMarkup.resolveUrl(base, iframeSrc) ?: iframeSrc
            runCatching {
                app.get(resolvedIframe, referer = cleanUrl, headers = mapOf("User-Agent" to GnulaHttpDefaults.UA), timeout = 12L).text
            }.getOrDefault(html)
        } else {
            html
        }
        val unpacked = getAndUnpack(pageHtml).ifBlank { GnulaJsUnpacker.unpackAll(pageHtml) }
        val streamUrl = GnulaHostParsers.extractSavefilesStream(unpacked)
            ?: GnulaHostParsers.extractSavefilesStream(pageHtml)
            ?: return
        val headers = mapOf("Referer" to "$base/", "Origin" to base)
        GnulaHlsHelper.emitHlsSafely(name, name, streamUrl, "$base/", headers, callback = callback)
    }
}

class GnulaFilemoonToExtractor : GnulaFilemoonExtractor() {
    override var mainUrl = "https://filemoon.to"
}

open class GnulaVoeExtractor : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val baseHeaders = mapOf("User-Agent" to GnulaHttpDefaults.UA)
        var currentUrl = GnulaHDMarkup.stripFragment(url)
        var html = ""
        var resolvedBase = GnulaHttpDefaults.hostBaseUrl(currentUrl, mainUrl)

        repeat(4) {
            val resp = runCatching {
                app.get(currentUrl, referer = referer ?: "$resolvedBase/", headers = baseHeaders, timeout = 15L)
            }.getOrNull() ?: return@repeat
            if (!resp.isSuccessful) return@repeat
            currentUrl = resp.url
            resolvedBase = GnulaHttpDefaults.hostBaseUrl(currentUrl, mainUrl)
            html = resp.text

            val altchaUrl = GnulaVoeDecoder.findAltchaChallengeUrl(html)
            if (altchaUrl != null) {
                val absAltcha = GnulaHDMarkup.resolveUrl(resolvedBase, altchaUrl) ?: altchaUrl
                val chJson = runCatching {
                    app.get(absAltcha, referer = currentUrl, headers = baseHeaders, timeout = 10L).text
                }.getOrNull()
                val solved = chJson?.let { GnulaVoeDecoder.solveAltchaPayload(it) }
                if (solved != null) {
                    val verifyResp = runCatching {
                        app.post(
                            currentUrl,
                            data = mapOf("altcha" to solved),
                            headers = baseHeaders + ("Referer" to currentUrl),
                            timeout = 12L,
                        )
                    }.getOrNull()
                    if (verifyResp != null && verifyResp.isSuccessful) {
                        html = verifyResp.text
                    }
                }
            }

            val nextRedirect = GnulaVoeDecoder.findRedirectUrl(html)
            if (!nextRedirect.isNullOrBlank() && nextRedirect != currentUrl) {
                currentUrl = GnulaHDMarkup.resolveUrl(resolvedBase, nextRedirect) ?: nextRedirect
            } else {
                return@repeat
            }
        }

        if (html.isBlank()) return
        val headers = mapOf("Referer" to "$resolvedBase/", "Origin" to resolvedBase, "user-agent" to GnulaHttpDefaults.UA)
        val node = GnulaVoeDecoder.extractPayloadFromHtml(html)
        if (node != null) {
            for (cap in node.path("captions")) {
                val capUrl = cap.path("file").asText("").trim()
                val label = cap.path("label").asText("Español").trim()
                if (capUrl.startsWith("http")) {
                    subtitleCallback(newSubtitleFile(lang = label, url = capUrl))
                }
            }
            val hls = node.path("source").asText("").trim()
            if (hls.startsWith("http")) {
                GnulaHlsHelper.emitHlsSafely(name, name, hls, "$resolvedBase/", headers, callback = callback)
            }
            val mp4 = node.path("direct_access_url").asText("").trim()
            if (mp4.startsWith("http") && mp4 != hls) {
                callback(
                    newExtractorLink(name, "$name MP4", mp4, ExtractorLinkType.VIDEO) {
                        this.referer = "$resolvedBase/"
                        this.headers = headers
                    },
                )
            }
            if (hls.startsWith("http") || mp4.startsWith("http")) return
        }

        val directHls = Regex("""['"]hls['"]\s*:\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
        if (!directHls.isNullOrBlank()) {
            GnulaHlsHelper.emitHlsSafely(name, name, directHls, "$resolvedBase/", headers, callback = callback)
            return
        }
        val directMp4 = Regex("""['"]mp4['"]\s*:\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
        if (!directMp4.isNullOrBlank()) {
            callback(newExtractorLink(name, name, directMp4, ExtractorLinkType.VIDEO) { this.referer = "$resolvedBase/" })
        }
    }
}

class GnulaCaseySeeExtractor : GnulaVoeExtractor() {
    override var mainUrl = "https://caseyseemavoid.com"
}

open class GnulaVidsonicExtractor : ExtractorApi() {
    override var name = "Vidsonic"
    override var mainUrl = "https://vidsonic.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = GnulaHDMarkup.stripFragment(url)
        val base = GnulaHttpDefaults.hostBaseUrl(cleanUrl, mainUrl)
        val res = runCatching {
            app.get(cleanUrl, referer = referer ?: "${GnulaHDMarkup.MAIN_URL}/", headers = mapOf("User-Agent" to GnulaHttpDefaults.UA), timeout = 12L)
        }.getOrNull() ?: return
        if (!res.isSuccessful) return
        val streamUrl = GnulaHostParsers.decodeVidsonicUrl(res.text) ?: return
        val hdrs = mapOf("Referer" to "$base/", "Origin" to base)
        GnulaHlsHelper.emitHlsSafely(name, name, streamUrl, "$base/", hdrs, callback = callback)
    }
}

class GnulaVixeoExtractor : ExtractorApi() {
    override var name = "Vixeo"
    override var mainUrl = "https://vixeo.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = GnulaHDMarkup.stripFragment(url)
        val base = GnulaHttpDefaults.hostBaseUrl(cleanUrl, mainUrl)
        val res = runCatching {
            app.get(cleanUrl, referer = referer ?: "${GnulaHDMarkup.MAIN_URL}/", headers = mapOf("User-Agent" to GnulaHttpDefaults.UA), timeout = 12L)
        }.getOrNull() ?: return
        if (!res.isSuccessful) return
        val dataConfig = Jsoup.parse(res.text, cleanUrl).selectFirst("#streamsonic-player-root")?.attr("data-config").orEmpty()
        val streamUrl = GnulaHostParsers.decodeVixeoConfig(dataConfig)
            ?: GnulaHostParsers.decodeVidsonicUrl(res.text)
            ?: return
        val hdrs = mapOf("Referer" to "$base/", "Origin" to base)
        GnulaHlsHelper.emitHlsSafely(name, name, streamUrl, "$base/", hdrs, callback = callback)
    }
}

internal object GnulaExtractorDispatcher {
    private val vidaraExtractor = GnulaVidaraExtractor()
    private val savefilesExtractor = GnulaSavefilesExtractor()
    private val byseExtractor = GnulaByseExtractor()
    private val filemoonExtractor = GnulaFilemoonExtractor()
    private val voeExtractor = GnulaVoeExtractor()
    private val vidsonicExtractor = GnulaVidsonicExtractor()
    private val vixeoExtractor = GnulaVixeoExtractor()

    private val skippedUnsupportedHosts = setOf("mega.nz", "1fichier.com", "1cloudfile.com")
    private val vidaraHostsPrefix = listOf(
        "vidara", "vidaarax", "vidavaca", "odysseusa.", "handfacesnap.",
        "namefacesnap.", "thebesthosterv.", "vidmatrixa.", "vidchampions.",
        "antarcticadocs.", "nameitweb.",
    )

    suspend fun extractServer(
        rawUrl: String,
        serverTitle: String,
        langLabel: String,
        refererPageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emitLabeled: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = GnulaHDMarkup.stripFragment(rawUrl)
        if (!cleanUrl.startsWith("http", ignoreCase = true)) return
        val host = runCatching { URI(cleanUrl).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
        if (host.isEmpty() || host in skippedUnsupportedHosts) return

        val titleLower = serverTitle.lowercase()
        val extractor: ExtractorApi? = when {
            host.startsWith("savefiles.") -> savefilesExtractor
            vidaraHostsPrefix.any { host.startsWith(it) } || titleLower.contains("vidara") -> vidaraExtractor
            host.startsWith("byse") || host == "n1mwq.org" || titleLower.contains("byse") -> byseExtractor
            host.startsWith("filemoon.") -> filemoonExtractor
            host.startsWith("voe.") || host.contains("avoid") || titleLower == "voe" -> voeExtractor
            host.startsWith("vidsonic.") -> vidsonicExtractor
            host.startsWith("vixeo.") -> vixeoExtractor
            else -> null
        }

        val collected = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        withTimeoutOrNull(18_000L) {
            try {
                if (extractor != null) {
                    extractor.getUrl(cleanUrl, refererPageUrl, subtitleCallback) { collected.add(it) }
                } else {
                    loadExtractor(cleanUrl, refererPageUrl, subtitleCallback) { collected.add(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Skip failing host without impacting sibling servers
            }
        }

        val fallbackHost = serverTitle.ifBlank { host.substringBefore('.').replaceFirstChar { it.uppercase() } }
        val snapshot = synchronized(collected) { collected.toList() }
        for (link in snapshot) {
            emitLabeled(labelLink(link, langLabel, fallbackHost))
        }
    }

    private suspend fun labelLink(link: ExtractorLink, language: String, hostFallback: String): ExtractorLink {
        val hostPart = link.name.trim().ifEmpty { hostFallback }
        val finalName = "[$language] $hostPart"
        if (link is DrmExtractorLink) {
            return newDrmExtractorLink(link.source, finalName, link.url, link.type, link.uuid) {
                referer = link.referer
                quality = link.quality
                headers = link.headers
                extractorData = link.extractorData
                audioTracks = link.audioTracks
                kid = link.kid
                key = link.key
                kty = link.kty
                keyRequestParameters = link.keyRequestParameters
                licenseUrl = link.licenseUrl
            }
        }
        return newExtractorLink(link.source, finalName, link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }
}
