package com.menorah.veraniv2

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object VerAniV2Crypto {
    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in B64_ALPHABET.indices) {
            this[B64_ALPHABET[i].code] = i
        }
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun encodeBase64(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val has1 = i < data.size
            val b1 = if (has1) data[i++].toInt() and 0xFF else 0
            val has2 = i < data.size
            val b2 = if (has2) data[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(B64_ALPHABET[(triple ushr 18) and 0x3F])
            sb.append(B64_ALPHABET[(triple ushr 12) and 0x3F])
            sb.append(if (has1) B64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (has2) B64_ALPHABET[triple and 0x3F] else '=')
        }
        return sb.toString()
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
                if (outIdx < out.size) {
                    out[outIdx++] = ((acc ushr bits) and 0xFF).toByte()
                }
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd hex length: ${clean.length}" }
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hexDigits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexDigits[v ushr 4])
            sb.append(hexDigits[v and 0x0F])
        }
        return sb.toString()
    }
}

internal object VerAniV2HlsHelper {
    private val DEMUXED_AUDIO_REGEX = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO""", RegexOption.IGNORE_CASE)
    private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

    fun hasDemuxedAudio(playlistText: String): Boolean = DEMUXED_AUDIO_REGEX.containsMatchIn(playlistText)

    fun parseMaxResolutionHeight(playlistText: String): Int? =
        RESOLUTION_HEIGHT_REGEX.findAll(playlistText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()

    /**
     * Emits an HLS stream safely.
     *
     * Crucial: when a master playlist declares separate `#EXT-X-MEDIA:TYPE=AUDIO` tracks (e.g. TurboEmbed `master.txt`),
     * `M3u8Helper.generateM3u8` splits out video-only variant URLs (`v1080p.txt`) tagged with higher quality than the
     * master playlist, causing ExoPlayer to play silent video. In that case we emit the master playlist directly so
     * ExoPlayer consumes both video and audio tracks cleanly.
     */
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

internal object VerAniV2TurboEmbedCrypto {
    val DEFAULT_KEY_CHARS = listOf("𝕊", "𝔼", "𝕮", "𝚁", "𝔼", "𝚃", "𝓚", "𝓔", "𝓨", "𝟙", "𝟚", "𝟛")
    val DEFAULT_SALT_CHARS = listOf("🔐", "🗝️", "🔑", "🚪", "🛡️", "⚡")

    fun buildKeyBytes(
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        val prefix = keyChars.joinToString("") { Character.codePointAt(it, 0).toString(16) }
        val dayHex = (nowMs / 86_400_000L).toString(16)
        val hex32 = (prefix + dayHex).take(32).padEnd(32, '0')
        return VerAniV2Crypto.hexToBytes(hex32)
    }

    fun buildIvBytes(
        host: String,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): ByteArray {
        val prefix = saltChars.joinToString("") { Character.codePointAt(it, 0).toString(16) }
        val hostHex = VerAniV2Crypto.bytesToHex(host.toByteArray(StandardCharsets.UTF_8))
        val hex32 = (prefix + hostHex).take(32).padEnd(32, '0')
        return VerAniV2Crypto.hexToBytes(hex32)
    }

    fun encryptHex(
        plainText: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val key = SecretKeySpec(buildKeyBytes(keyChars, nowMs), "AES")
        val iv = IvParameterSpec(buildIvBytes(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return VerAniV2Crypto.bytesToHex(encrypted)
    }

    fun decryptHex(
        cipherHex: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
        nowMs: Long = System.currentTimeMillis(),
    ): String? = runCatching {
        val key = SecretKeySpec(buildKeyBytes(keyChars, nowMs), "AES")
        val iv = IvParameterSpec(buildIvBytes(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val plain = cipher.doFinal(VerAniV2Crypto.hexToBytes(cipherHex))
        String(plain, StandardCharsets.UTF_8)
    }.getOrNull()

    fun parseCharArray(bundleJs: String, varName: String): List<String>? {
        val regex = Regex("""\b$varName\s*=\s*\[(.*?)]""")
        val raw = regex.find(bundleJs)?.groupValues?.getOrNull(1) ?: return null
        val itemRegex = Regex("""["']([^"']+)["']""")
        val items = itemRegex.findAll(raw).map { it.groupValues[1] }.toList()
        return items.takeIf { it.isNotEmpty() }
    }
}

internal object VerAniV2VoeDecoder {
    private val F7_JSON_SCRIPT_REGEX = Regex(
        """<script[^>]*type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*]\s*</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val JS_REDIRECT_REGEX = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
    private val SPECIAL_TOKEN_REGEX = Regex("""(@\$|\^\^|~@|%\?|\*~|!!|#&)""")

    fun findRedirectUrl(html: String): String? =
        JS_REDIRECT_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

    fun findEncodedPayload(html: String): String? =
        F7_JSON_SCRIPT_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()

    fun decryptF7(encoded: String): String? = runCatching {
        // Step 1: ROT13
        val step1 = buildString(encoded.length) {
            for (ch in encoded) {
                when (ch) {
                    in 'a'..'z' -> append(('a'.code + (ch.code - 'a'.code + 13) % 26).toChar())
                    in 'A'..'Z' -> append(('A'.code + (ch.code - 'A'.code + 13) % 26).toChar())
                    else -> append(ch)
                }
            }
        }
        // Step 2: Replace obfuscation markers with '_' then remove '_'
        val step2 = SPECIAL_TOKEN_REGEX.replace(step1, "_")
        val step3 = step2.replace("_", "")
        // Step 3: Base64 decode
        val step4 = String(VerAniV2Crypto.decodeBase64Flexible(step3), StandardCharsets.ISO_8859_1)
        // Step 4: Shift each character back by 3
        val step5 = buildString(step4.length) {
            for (ch in step4) {
                append((ch.code - 3).toChar())
            }
        }
        // Step 5: Reverse and Base64 decode UTF-8
        val reversed = step5.reversed()
        String(VerAniV2Crypto.decodeBase64Flexible(reversed), StandardCharsets.UTF_8)
    }.getOrNull()
}

internal object VerAniV2JsUnpacker {
    private val PACKED_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val WORD_REGEX = Regex("""\b\w+\b""")

    private fun decodeRadix(token: String, radix: Int): Int? {
        var value = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch.code - '0'.code
                in 'a'..'z' -> ch.code - 'a'.code + 10
                in 'A'..'Z' -> ch.code - 'A'.code + 36
                else -> return null
            }
            if (digit >= radix) return null
            value = value * radix + digit
        }
        return value
    }

    fun unpackAll(html: String): List<String> {
        val out = mutableListOf(html)
        for (match in PACKED_REGEX.findAll(html)) {
            val payload = match.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val radix = match.groupValues[2].toIntOrNull()?.coerceIn(2, 62) ?: continue
            val words = match.groupValues[4].split('|')
            val unpacked = WORD_REGEX.replace(payload) { mr ->
                val idx = decodeRadix(mr.value, radix)
                if (idx != null && idx in words.indices && words[idx].isNotEmpty()) words[idx] else mr.value
            }
            out += unpacked
        }
        return out
    }
}

internal object VerAniV2Mp4UploadParser {
    private val DIRECT_SRC_OBJ_REGEX = Regex("""player\.src\(\s*\{[^}]*\bsrc\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
    private val DIRECT_SRC_STR_REGEX = Regex("""player\.src\(\s*["']([^"']+)["']\s*\)""")
    private val SOURCE_TAG_REGEX = Regex("""<source[^>]+src\s*=\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val HEIGHT_REGEX = Regex("""\bheight\s*[:=]\s*(\d{3,4})""")

    fun normalizeEmbedUrl(url: String): String? {
        if (url.contains("embed-undef.html", ignoreCase = true)) return null
        val idMatch = Regex("""embed-([A-Za-z0-9]+)""").find(url)
            ?: Regex("""mp4upload\.com/([A-Za-z0-9]{8,})""").find(url)
        return if (idMatch != null) {
            "https://www.mp4upload.com/embed-${idMatch.groupValues[1]}.html"
        } else {
            url
        }
    }

    fun extractVideoSrc(html: String): String? {
        for (body in VerAniV2JsUnpacker.unpackAll(html)) {
            DIRECT_SRC_OBJ_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
            DIRECT_SRC_STR_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
            SOURCE_TAG_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    fun extractHeight(html: String): Int? =
        HEIGHT_REGEX.findAll(html).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull()
}

internal object VerAniV2MxDropParser {
    private val WURL_REGEX = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""")

    fun extractWurl(html: String): String? {
        for (body in VerAniV2JsUnpacker.unpackAll(html)) {
            val raw = WURL_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                return if (raw.startsWith("//")) "https:$raw" else raw
            }
        }
        return null
    }
}

internal data class VerAniV2ByseSource(
    val url: String,
    val height: Int,
    val bitrateKbps: Int,
    val label: String?,
)

internal data class VerAniV2ByseTrack(
    val label: String,
    val url: String,
)

internal data class VerAniV2BysePlaybackData(
    val sources: List<VerAniV2ByseSource>,
    val tracks: List<VerAniV2ByseTrack>,
)

internal object VerAniV2BysePow {
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

internal object VerAniV2ByseCrypto {
    fun base64UrlNoPad(bytes: ByteArray): String =
        VerAniV2Crypto.encodeBase64(bytes)
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

    fun resolveAesKeyBytes(keyParts: List<String>, version: String?): ByteArray? {
        if (keyParts.size < 2) return null
        val verNum = version?.trim()?.toIntOrNull()
        if (verNum != null && verNum in 1..20) {
            val idx1 = verNum - 1
            val idx2 = 30 - verNum
            if (idx1 in keyParts.indices && idx2 in keyParts.indices) {
                return VerAniV2Crypto.decodeBase64Flexible(keyParts[idx1]) +
                    VerAniV2Crypto.decodeBase64Flexible(keyParts[idx2])
            }
        }
        return VerAniV2Crypto.decodeBase64Flexible(keyParts[0]) +
            VerAniV2Crypto.decodeBase64Flexible(keyParts[1])
    }

    fun decryptPlaybackDetails(
        ivB64Url: String,
        payloadB64Url: String,
        keyParts: List<String>,
        version: String? = null,
    ): VerAniV2BysePlaybackData? {
        val keyBytes = resolveAesKeyBytes(keyParts, version) ?: return null
        val ivBytes = VerAniV2Crypto.decodeBase64Flexible(ivB64Url)
        val cipherBytes = VerAniV2Crypto.decodeBase64Flexible(payloadB64Url)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
        var plain = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (plain.startsWith("\uFEFF")) plain = plain.substring(1)

        val root = runCatching { VerAniV2Markup.jsonMapper.readTree(plain) }.getOrNull() ?: return null
        val sourcesNode = root.get("sources") ?: return null
        if (!sourcesNode.isArray) return null

        val parsedSources = mutableListOf<VerAniV2ByseSource>()
        for (node in sourcesNode) {
            val u = node.get("url")?.asText()?.trim().orEmpty()
            if (!u.startsWith("http", ignoreCase = true)) continue
            parsedSources += VerAniV2ByseSource(
                url = u,
                height = node.get("height")?.asInt(0) ?: 0,
                bitrateKbps = node.get("bitrate_kbps")?.asInt(0) ?: node.get("bitrate")?.asInt(0) ?: 0,
                label = node.get("label")?.asText()?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        if (parsedSources.isEmpty()) return null
        val ordered = parsedSources.sortedWith(
            compareByDescending<VerAniV2ByseSource> { it.height }.thenByDescending { it.bitrateKbps },
        )

        val tracks = mutableListOf<VerAniV2ByseTrack>()
        val tracksNode = root.get("tracks") ?: root.get("subtitles")
        if (tracksNode != null && tracksNode.isArray) {
            for (sub in tracksNode) {
                val subUrl = (sub.get("url")?.asText() ?: sub.get("file")?.asText())?.trim().orEmpty()
                if (!subUrl.startsWith("http", ignoreCase = true)) continue
                val subLabel = (sub.get("label")?.asText() ?: sub.get("language")?.asText() ?: sub.get("title")?.asText())
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                tracks += VerAniV2ByseTrack(label = subLabel, url = subUrl)
            }
        }
        return VerAniV2BysePlaybackData(sources = ordered, tracks = tracks)
    }
}

internal object VerAniV2ExtractorUserAgent {
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}

open class VerAniV2TurboEmbedExtractor : ExtractorApi() {
    override val name = "TurboEmbed"
    override val mainUrl = "https://turboembed.com"
    override val requiresReferer = false

    companion object {
        private val JS_ASSET_REGEX = Regex("""<script[^>]+src=["'](/assets/index-[^"']+\.js)["']""", RegexOption.IGNORE_CASE)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val origin = "${uri.scheme}://${uri.authority}"
        val host = uri.host ?: "turboembed.com"
        val shortCode = uri.rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
        if (shortCode.isEmpty()) return
        val pvSuffix = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "&$it" }.orEmpty()

        val apiHeaders = mapOf(
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to url,
        )

        var keyChars = VerAniV2TurboEmbedCrypto.DEFAULT_KEY_CHARS
        var saltChars = VerAniV2TurboEmbedCrypto.DEFAULT_SALT_CHARS
        var encParam = VerAniV2TurboEmbedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
        var trackRes = app.get(
            "$origin/api/stream/tracking?c=$encParam$pvSuffix",
            referer = url,
            headers = apiHeaders,
            timeout = 10L,
        )
        var decryptedJson = trackRes.headers["x-tracking"]?.let {
            VerAniV2TurboEmbedCrypto.decryptHex(it, host, keyChars, saltChars)
        }

        if (decryptedJson == null) {
            val pageHtml = runCatching {
                app.get(url, referer = referer, headers = apiHeaders, timeout = 8L).text
            }.getOrNull()
            val assetPath = pageHtml?.let { JS_ASSET_REGEX.find(it)?.groupValues?.getOrNull(1) }
            if (assetPath != null) {
                val assetUrl = runCatching { uri.resolve(assetPath).toString() }.getOrDefault("$origin$assetPath")
                val bundleText = runCatching {
                    app.get(assetUrl, referer = url, headers = apiHeaders, timeout = 8L).text
                }.getOrNull()
                if (bundleText != null) {
                    val parsedKeys = VerAniV2TurboEmbedCrypto.parseCharArray(bundleText, "keyChars")
                    val parsedSalts = VerAniV2TurboEmbedCrypto.parseCharArray(bundleText, "saltChars")
                    if (!parsedKeys.isNullOrEmpty() && !parsedSalts.isNullOrEmpty()) {
                        keyChars = parsedKeys
                        saltChars = parsedSalts
                        encParam = VerAniV2TurboEmbedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
                        trackRes = app.get(
                            "$origin/api/stream/tracking?c=$encParam$pvSuffix",
                            referer = url,
                            headers = apiHeaders,
                            timeout = 10L,
                        )
                        decryptedJson = trackRes.headers["x-tracking"]?.let {
                            VerAniV2TurboEmbedCrypto.decryptHex(it, host, keyChars, saltChars)
                        }
                    }
                }
            }
        }

        val jsonText = decryptedJson ?: return
        val root = runCatching { VerAniV2Markup.jsonMapper.readTree(jsonText) }.getOrNull() ?: return
        val streamNode = root.get("stream") ?: root
        val streamCandidates = listOf("tiktok", "hls", "direct")
            .mapNotNull { key ->
                streamNode.get(key)?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            }
            .distinct()

        if (streamCandidates.isEmpty()) return

        val streamHeaders = mapOf(
            "Origin" to origin,
            "Referer" to "$origin/",
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
        )

        var emitted = false
        for (candidate in streamCandidates) {
            val before = emitted
            VerAniV2HlsHelper.emitHlsSafely(
                sourceName = name,
                displayName = name,
                masterUrl = candidate,
                referer = "$origin/",
                headers = streamHeaders,
                callback = { link ->
                    emitted = true
                    callback(link)
                },
            )
            if (emitted && !before) break
        }

        if (emitted) {
            runCatching {
                val infoRes = app.get("$origin/api/stream?s=$shortCode$pvSuffix", referer = url, headers = apiHeaders, timeout = 5L)
                if (infoRes.isSuccessful) {
                    val subsNode = VerAniV2Markup.jsonMapper.readTree(infoRes.text).get("data")?.get("subtitles")
                    if (subsNode != null && subsNode.isArray) {
                        for (sub in subsNode) {
                            val subUrl = sub.get("url")?.asText()?.trim().orEmpty()
                            if (subUrl.startsWith("http", ignoreCase = true)) {
                                val subLang = sub.get("language")?.asText()?.trim()?.ifEmpty { null }
                                    ?: sub.get("label")?.asText()?.trim()?.ifEmpty { null }
                                    ?: "Español"
                                subtitleCallback(newSubtitleFile(subLang, subUrl))
                            }
                        }
                    }
                }
            }
        }
    }
}

class VerAniV2AnimeAv1Extractor : VerAniV2TurboEmbedExtractor() {
    override val name = "AnimeAV1"
    override val mainUrl = "https://animeav1.uns.bio"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val file1 = uri.fragment?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.rawQuery?.split("&")?.firstNotNullOfOrNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "file1") parts[1].trim() else null
            }
            ?: uri.rawPath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: return

        val origin = "${uri.scheme}://${uri.authority}"
        val res = runCatching {
            app.get(
                "$origin/api/public/resolve?file1=$file1",
                referer = url,
                headers = mapOf(
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
                    "Accept" to "application/json, */*",
                    "Referer" to url,
                ),
                timeout = 10L,
            )
        }.getOrNull() ?: return

        if (!res.isSuccessful) return
        val resolvedUrl = runCatching {
            VerAniV2Markup.jsonMapper.readTree(res.text).get("url")?.asText()?.trim()
        }.getOrNull()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return

        super.getUrl(resolvedUrl, url, subtitleCallback, callback)
    }
}

class VerAniV2VidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = true

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val origin = "${uri.scheme}://${uri.authority}"
        val filecode = uri.rawPath.orEmpty().trimEnd('/').substringAfterLast('/').trim()
        if (filecode.isEmpty()) return

        val payload = VerAniV2Markup.jsonMapper.writeValueAsString(
            mapOf(
                "filecode" to filecode,
                "device" to "android",
                "codecs" to emptyList<String>(),
            ),
        )
        val headers = mapOf(
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
            "Accept" to "application/json, */*",
            "Origin" to origin,
            "Referer" to url,
        )
        val res = runCatching {
            app.post(
                "$origin/api/stream",
                headers = headers,
                requestBody = payload.toRequestBody(JSON_MEDIA),
                timeout = 10L,
            )
        }.getOrNull() ?: return

        if (!res.isSuccessful) return
        val node = runCatching { VerAniV2Markup.jsonMapper.readTree(res.text) }.getOrNull() ?: return
        val streamUrl = node.get("streaming_url")?.asText()?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return

        val subsNode = node.get("subtitles")
        if (subsNode != null && subsNode.isArray) {
            for (sub in subsNode) {
                val subUrl = (sub.get("url")?.asText() ?: sub.get("file")?.asText())?.trim().orEmpty()
                if (subUrl.startsWith("http", ignoreCase = true)) {
                    val subLang = (sub.get("label")?.asText() ?: sub.get("lang")?.asText())?.trim().orEmpty().ifEmpty { "Español" }
                    subtitleCallback(newSubtitleFile(subLang, subUrl))
                }
            }
        }

        val streamHeaders = mapOf(
            "Origin" to origin,
            "Referer" to "$origin/",
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
        )
        VerAniV2HlsHelper.emitHlsSafely(
            sourceName = name,
            displayName = name,
            masterUrl = streamUrl,
            referer = "$origin/",
            headers = streamHeaders,
            callback = callback,
        )
    }
}

class VerAniV2ZillaExtractor : ExtractorApi() {
    override val name = "Zilla"
    override val mainUrl = "https://player.zilla-networks.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val m3u8Url = when {
            path.startsWith("/m3u8/") -> url
            else -> {
                val id = path.substringAfterLast('/').trim()
                if (id.isNotEmpty()) "$origin/m3u8/$id" else return
            }
        }

        val effRef = referer?.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${VerAniV2Markup.DEFAULT_MAIN_URL}/"
        val headers = mapOf(
            "Referer" to effRef,
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
        )
        VerAniV2HlsHelper.emitHlsSafely(
            sourceName = name,
            displayName = name,
            masterUrl = m3u8Url,
            referer = effRef,
            headers = headers,
            callback = callback,
        )
    }
}

open class VerAniV2VoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        var currentUrl = url
        var res = runCatching { app.get(currentUrl, referer = referer, headers = headers, timeout = 12L) }.getOrNull() ?: return
        if (!res.isSuccessful) return

        var html = res.text
        currentUrl = res.url
        VerAniV2VoeDecoder.findRedirectUrl(html)?.let { redir ->
            val r2 = runCatching { app.get(redir, referer = currentUrl, headers = headers, timeout = 12L) }.getOrNull()
            if (r2 != null && r2.isSuccessful) {
                html = r2.text
                currentUrl = r2.url
            }
        }

        val curUri = runCatching { URI(currentUrl) }.getOrNull()
        val curOrigin = if (curUri != null) "${curUri.scheme}://${curUri.authority}" else mainUrl
        val streamHeaders = mapOf(
            "Origin" to curOrigin,
            "Referer" to "$curOrigin/",
            "user-agent" to VerAniV2ExtractorUserAgent.UA,
        )

        val encoded = VerAniV2VoeDecoder.findEncodedPayload(html)
        val decryptedJson = encoded?.let { VerAniV2VoeDecoder.decryptF7(it) }
        if (!decryptedJson.isNullOrBlank()) {
            val node = runCatching { VerAniV2Markup.jsonMapper.readTree(decryptedJson) }.getOrNull()
            if (node != null) {
                val hlsUrl = node.get("source")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
                val mp4Url = node.get("direct_access_url")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

                val subsNode = node.get("captions") ?: node.get("subtitles")
                if (subsNode != null && subsNode.isArray) {
                    for (sub in subsNode) {
                        val subUrl = (sub.get("file")?.asText() ?: sub.get("url")?.asText())?.trim().orEmpty()
                        if (subUrl.startsWith("http", ignoreCase = true)) {
                            val subLang = (sub.get("label")?.asText() ?: sub.get("lang")?.asText())?.trim().orEmpty().ifEmpty { "Español" }
                            subtitleCallback(newSubtitleFile(subLang, subUrl))
                        }
                    }
                }

                var emitted = false
                if (hlsUrl != null) {
                    VerAniV2HlsHelper.emitHlsSafely(
                        sourceName = name,
                        displayName = name,
                        masterUrl = hlsUrl,
                        referer = "$curOrigin/",
                        headers = streamHeaders,
                        callback = { link ->
                            emitted = true
                            callback(link)
                        },
                    )
                }
                if (mp4Url != null && mp4Url != hlsUrl) {
                    callback(
                        newExtractorLink(name, "$name MP4", mp4Url, ExtractorLinkType.VIDEO) {
                            this.referer = "$curOrigin/"
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    emitted = true
                }
                if (emitted) return
            }
        }

        // Legacy fallback if F7 payload wasn't present
        val hlsLegacy = Regex("""["']hls["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            ?.let { raw -> if (raw.startsWith("http")) raw else runCatching { String(VerAniV2Crypto.decodeBase64Flexible(raw)) }.getOrNull() }
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (hlsLegacy != null) {
            VerAniV2HlsHelper.emitHlsSafely(name, name, hlsLegacy, "$curOrigin/", streamHeaders, callback = callback)
        }
    }
}

class VerAniV2Mp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val realUrl = VerAniV2Mp4UploadParser.normalizeEmbedUrl(url) ?: return
        val res = runCatching {
            app.get(
                realUrl,
                referer = referer ?: "${VerAniV2Markup.DEFAULT_MAIN_URL}/",
                headers = mapOf(
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
                    "Referer" to "$mainUrl/",
                ),
                timeout = 12L,
            )
        }.getOrNull() ?: return
        if (!res.isSuccessful) return

        val videoSrc = VerAniV2Mp4UploadParser.extractVideoSrc(res.text) ?: return
        val height = VerAniV2Mp4UploadParser.extractHeight(res.text) ?: Qualities.Unknown.value
        callback(
            newExtractorLink(name, name, videoSrc, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
                )
                this.quality = height
            },
        )
    }
}

class VerAniV2MxDropExtractor : ExtractorApi() {
    override val name = "MxDrop"
    override val mainUrl = "https://mxdrop.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val res = runCatching {
            app.get(
                url,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                ),
                timeout = 12L,
            )
        }.getOrNull() ?: return
        if (!res.isSuccessful) return

        val finalOrigin = runCatching {
            val u = URI(res.url)
            "${u.scheme}://${u.authority}"
        }.getOrDefault(mainUrl)

        val wurl = VerAniV2MxDropParser.extractWurl(res.text) ?: return
        val quality = Jsoup.parse(res.text).select("meta[name=description], title").text()
            .let { Regex("""(\d{3,4}p)""").find(it)?.groupValues?.getOrNull(1) }
            ?.let(::getQualityFromName)
            ?: Qualities.Unknown.value

        callback(
            newExtractorLink(name, name, wurl, ExtractorLinkType.VIDEO) {
                this.referer = "$finalOrigin/"
                this.headers = mapOf(
                    "Referer" to "$finalOrigin/",
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
                )
                this.quality = quality
            },
        )
    }
}

open class VerAniV2ByseExtractor : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://byselapuix.com"
    override val requiresReferer = true

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
            VerAniV2Markup.jsonMapper.writeValueAsString(payload).toRequestBody(JSON_MEDIA)

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
                val keyPair = if (forceRefresh) VerAniV2ByseCrypto.ecKeyPairP256() else rechecked?.keyPair ?: VerAniV2ByseCrypto.ecKeyPairP256()
                val baseHeaders = mapOf(
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
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
                val chalNode = runCatching { VerAniV2Markup.jsonMapper.readTree(chalRes.text) }.getOrNull() ?: return@withLock null
                val challengeId = chalNode.get("challenge_id")?.asText()?.trim().orEmpty()
                val nonce = chalNode.get("nonce")?.asText()?.trim().orEmpty()
                if (challengeId.isEmpty() || nonce.isEmpty()) return@withLock null

                val signature = VerAniV2ByseCrypto.signChallengeNonce(nonce, keyPair)
                val pubJwk = VerAniV2ByseCrypto.toJwkPublicKey(keyPair.public as ECPublicKey)
                val attestPayload = linkedMapOf<String, Any?>(
                    "viewer_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("viewer_id"),
                    "device_id" to if (forceRefresh) null else rechecked?.fingerprint?.get("device_id"),
                    "challenge_id" to challengeId,
                    "nonce" to nonce,
                    "signature" to signature,
                    "public_key" to pubJwk,
                    "client" to mapOf(
                        "user_agent" to VerAniV2ExtractorUserAgent.UA,
                        "platform" to "Linux armv8l",
                        "languages" to listOf("es-MX", "es"),
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

                val attNode = runCatching { VerAniV2Markup.jsonMapper.readTree(attestRes.text) }.getOrNull() ?: return@withLock null
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
        val base = runCatching { val u = URI(url); "${u.scheme}://${u.authority}" }.getOrDefault(mainUrl)
        val code = runCatching { URI(url).rawPath.orEmpty().trimEnd('/').substringAfterLast('/') }.getOrDefault("")
        if (code.isBlank()) return

        val baseHeaders = mapOf("user-agent" to VerAniV2ExtractorUserAgent.UA, "Accept" to "*/*")
        val detailsRes = runCatching {
            app.get("$base/api/videos/$code/embed/details", referer = url, headers = baseHeaders, timeout = 8L)
        }.getOrNull()?.takeIf { it.isSuccessful } ?: runCatching {
            app.get("$base/api/videos/$code/details", referer = url, headers = baseHeaders, timeout = 8L)
        }.getOrNull()?.takeIf { it.isSuccessful }

        val embedFrameUrl = detailsRes?.text?.let { raw ->
            runCatching { VerAniV2Markup.jsonMapper.readTree(raw).get("embed_frame_url")?.asText()?.trim() }.getOrNull()
        }?.takeIf { it.startsWith("http", ignoreCase = true) }

        val targetPairs = buildList {
            if (!embedFrameUrl.isNullOrBlank()) {
                val fOrigin = runCatching { val u = URI(embedFrameUrl); "${u.scheme}://${u.authority}" }.getOrDefault(base)
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

            suspend fun requestPlayback(activeState: AttestedOriginState, isEmbedMode: Boolean): VerAniV2BysePlaybackData? {
                val prefix = if (isEmbedMode) "embed/" else ""
                val embedParent = referer?.takeIf { it.isNotBlank() } ?: "${VerAniV2Markup.DEFAULT_MAIN_URL}/"
                val embedParentHost = VerAniV2Markup.hostOf(embedParent).ifEmpty { "verani.me" }
                val pbHeaders = linkedMapOf(
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
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

                    val capNode = runCatching { VerAniV2Markup.jsonMapper.readTree(capRes.text) }.getOrNull() ?: return null
                    val powNonce = capNode.get("pow_nonce")?.asText().orEmpty()
                    val powDiff = capNode.get("pow_difficulty")?.asInt(12) ?: 12
                    val powToken = capNode.get("pow_token")?.asText().orEmpty()
                    if (powNonce.isEmpty() || powToken.isEmpty()) return null

                    val solution = VerAniV2BysePow.solve(powNonce, powDiff) ?: return null
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
                        VerAniV2Markup.jsonMapper.readTree(verRes.text).get("token")?.asText()?.trim()
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
                val root = runCatching { VerAniV2Markup.jsonMapper.readTree(pbRes.text) }.getOrNull() ?: return null
                val playback = root.get("playback") ?: return null
                val iv = playback.get("iv")?.asText().orEmpty()
                val payload = playback.get("payload")?.asText().orEmpty()
                val version = playback.get("version")?.asText()
                val keyParts = playback.get("key_parts")?.mapNotNull { it.asText() }.orEmpty()
                return runCatching {
                    VerAniV2ByseCrypto.decryptPlaybackDetails(iv, payload, keyParts, version)
                }.getOrNull()
            }

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
                    "user-agent" to VerAniV2ExtractorUserAgent.UA,
                )
                val best = decrypted.sources.first()
                val isM3u8 = best.url.contains(".m3u8", ignoreCase = true) ||
                    best.url.contains("/hls/", ignoreCase = true) ||
                    best.url.contains("master", ignoreCase = true)
                if (isM3u8) {
                    VerAniV2HlsHelper.emitHlsSafely(
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

class VerAniV2ByseKozeExtractor : VerAniV2ByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
}

class VerAniV2ByseBuhoExtractor : VerAniV2ByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysebuho.com"
}

class VerAniV2ByseSxExtractor : VerAniV2ByseExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://byse.sx"
}

internal object VerAniV2ExtractorDispatcher {
    private val animeAv1 = VerAniV2AnimeAv1Extractor()
    private val turboEmbed = VerAniV2TurboEmbedExtractor()
    private val vidara = VerAniV2VidaraExtractor()
    private val zilla = VerAniV2ZillaExtractor()
    private val voe = VerAniV2VoeExtractor()
    private val mp4Upload = VerAniV2Mp4UploadExtractor()
    private val mxDrop = VerAniV2MxDropExtractor()
    private val byse = VerAniV2ByseExtractor()

    fun decorateName(base: String, badge: String): String =
        VerAniV2Markup.decorateLinkName(base, badge)

    suspend fun extractTab(
        tab: VerAniPlayerTab,
        refererPageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val host = VerAniV2Markup.hostOf(tab.url).removePrefix("www.")
        val collected = mutableListOf<ExtractorLink>()
        val collect: (ExtractorLink) -> Unit = { collected += it }

        val customApi: ExtractorApi? = when {
            host == "animeav1.uns.bio" -> animeAv1
            host.contains("turboembed") || host.contains("turbovio") || host.contains("turbovid") || host.contains("vsembed") -> turboEmbed
            host == "vidara.so" || host.endsWith(".vidara.so") -> vidara
            host.contains("zilla-networks.com") -> zilla
            host == "voe.sx" || host.endsWith(".voe.sx") || host == "caseyseemavoid.com" -> voe
            host.contains("mp4upload.com") -> mp4Upload
            host.contains("mxdrop.") || host.contains("mixdrop.") -> mxDrop
            host.startsWith("byse") || host == "n1mwq.org" -> byse
            else -> null
        }

        try {
            if (customApi != null) {
                customApi.getUrl(tab.url, refererPageUrl, subtitleCallback, collect)
            }
            if (collected.isEmpty()) {
                loadExtractor(tab.url, refererPageUrl, subtitleCallback, collect)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        for (link in collected) {
            val taggedName = decorateName(link.name, tab.badge)
            val taggedSource = decorateName(link.source, tab.badge)
            if (taggedName == link.name && taggedSource == link.source) {
                callback(link)
            } else {
                callback(
                    newExtractorLink(
                        source = taggedSource,
                        name = taggedName,
                        url = link.url,
                        type = link.type,
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                        this.audioTracks = link.audioTracks
                    },
                )
            }
        }
    }
}
