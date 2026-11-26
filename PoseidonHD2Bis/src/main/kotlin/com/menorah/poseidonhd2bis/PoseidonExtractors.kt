package com.menorah.poseidonhd2bis

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object PoseidonCrypto {
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
        val normalized = input.trim()
            .replace('-', '+')
            .replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(pad)
        return runCatching {
            Base64.decode(padded, Base64.DEFAULT)
        }.getOrElse {
            java.util.Base64.getDecoder().decode(padded)
        }
    }

    fun encodeBase64(bytes: ByteArray): String {
        return runCatching {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrElse {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    fun pbkdf2Hmac(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
        algorithm: String = "HmacSHA256",
    ): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(password, algorithm))
        val hashLen = mac.macLength
        val blockCount = (keyLengthBytes + hashLen - 1) / hashLen
        val out = ByteArray(blockCount * hashLen)
        val blockIndex = ByteArray(4)

        for (block in 1..blockCount) {
            blockIndex[0] = (block ushr 24).toByte()
            blockIndex[1] = (block ushr 16).toByte()
            blockIndex[2] = (block ushr 8).toByte()
            blockIndex[3] = block.toByte()

            mac.update(salt)
            mac.update(blockIndex)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) {
                    t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (block - 1) * hashLen, hashLen)
        }
        return out.copyOf(keyLengthBytes)
    }

    fun solveAltchaChallenge(challengeJson: String, maxCounters: Int = 8000): String? {
        val root = runCatching {
            PoseidonHD2BisData.jsonMapper.readTree(challengeJson)
        }.getOrNull() ?: return null

        val params = root.get("parameters")
        if (params != null && params.isObject) {
            return solveAltchaV2(root, params, maxCounters)
        }
        return solveAltchaV1(root, maxCounters * 50)
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
        val prefixBytes = if (keyPrefix.isNotEmpty() && keyPrefix.length % 2 == 0) {
            runCatching { hexToBytes(keyPrefix) }.getOrNull()
        } else {
            null
        }

        val passwordBuf = ByteArray(nonceBytes.size + 4)
        System.arraycopy(nonceBytes, 0, passwordBuf, 0, nonceBytes.size)
        val startedNs = System.nanoTime()

        for (counter in 0..maxCounters) {
            passwordBuf[nonceBytes.size] = (counter ushr 24).toByte()
            passwordBuf[nonceBytes.size + 1] = (counter ushr 16).toByte()
            passwordBuf[nonceBytes.size + 2] = (counter ushr 8).toByte()
            passwordBuf[nonceBytes.size + 3] = counter.toByte()

            val derived = pbkdf2Hmac(passwordBuf, saltBytes, cost, keyLen, macAlgo)
            val matched = if (prefixBytes != null) {
                prefixBytes.indices.all { i -> derived[i] == prefixBytes[i] }
            } else {
                bytesToHex(derived).startsWith(keyPrefix)
            }
            if (matched) {
                val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                val payload = mapOf(
                    "challenge" to root,
                    "solution" to mapOf(
                        "counter" to counter,
                        "derivedKey" to bytesToHex(derived),
                        "time" to elapsedMs,
                    ),
                )
                val jsonBytes = PoseidonHD2BisData.jsonMapper.writeValueAsBytes(payload)
                return encodeBase64(jsonBytes)
            }
        }
        return null
    }

    private fun solveAltchaV1(root: JsonNode, maxCounters: Int): String? {
        val targetHex = root.get("challenge")?.asText()?.lowercase() ?: return null
        val salt = root.get("salt")?.asText() ?: return null
        val signature = root.get("signature")?.asText() ?: ""
        val algorithm = root.get("algorithm")?.asText("SHA-256") ?: "SHA-256"
        val digestAlgo = when (algorithm.uppercase()) {
            "SHA-512" -> "SHA-512"
            "SHA-384" -> "SHA-384"
            else -> "SHA-256"
        }
        val maxNum = root.get("maxnumber")?.asInt(maxCounters)?.coerceAtMost(maxCounters) ?: maxCounters
        val md = MessageDigest.getInstance(digestAlgo)
        val startedNs = System.nanoTime()

        for (n in 0..maxNum) {
            md.reset()
            val candidate = (salt + n.toString()).toByteArray(StandardCharsets.UTF_8)
            val hashHex = bytesToHex(md.digest(candidate))
            if (hashHex == targetHex) {
                val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                val payload = mapOf(
                    "algorithm" to algorithm,
                    "challenge" to targetHex,
                    "number" to n,
                    "salt" to salt,
                    "signature" to signature,
                    "took" to elapsedMs,
                )
                return encodeBase64(PoseidonHD2BisData.jsonMapper.writeValueAsBytes(payload))
            }
        }
        return null
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class VoeCaption(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class VoeDecryptedPayload(
    val source: String? = null,
    @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
    val captions: List<VoeCaption>? = null,
)

internal object PoseidonVoeCrypto {
    private val PATTERNS = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    fun decryptF7(encoded: String): VoeDecryptedPayload? {
        return runCatching {
            val step1 = rot13(encoded.trim())
            var step2 = step1
            for (pat in PATTERNS) {
                step2 = step2.replace(pat, "_")
            }
            val step3 = step2.replace("_", "")
            val step4Bytes = PoseidonCrypto.decodeBase64Flexible(step3)
            val step5 = step4Bytes.map { ((it.toInt() and 0xFF) - 3).toChar() }
                .joinToString("")
                .reversed()
            val finalJsonBytes = PoseidonCrypto.decodeBase64Flexible(step5)
            val jsonText = String(finalJsonBytes, StandardCharsets.UTF_8)
            PoseidonHD2BisData.jsonMapper.readValue(jsonText, VoeDecryptedPayload::class.java)
        }.getOrNull()
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when (c) {
                    in 'A'..'Z' -> (((c.code - 'A'.code + 13) % 26) + 'A'.code).toChar()
                    in 'a'..'z' -> (((c.code - 'a'.code + 13) % 26) + 'a'.code).toChar()
                    else -> c
                },
            )
        }
        return sb.toString()
    }
}

internal object PoseidonVsembedCrypto {
    val DEFAULT_KEY_CHARS = listOf("𝕊", "𝔼", "𝕮", "𝚁", "𝔼", "𝚃", "𝓚", "𝓔", "𝓨", "𝟙", "𝟚", "𝟛")
    val DEFAULT_SALT_CHARS = listOf("🔐", "🗝️", "🔑", "🚪", "🛡️", "⚡")

    fun generateKeyHex(
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        epochDay: Long = System.currentTimeMillis() / 86_400_000L,
    ): String {
        val sb = StringBuilder()
        for (ch in keyChars) {
            if (ch.isNotEmpty()) {
                sb.append(Character.codePointAt(ch, 0).toString(16))
            }
        }
        sb.append(epochDay.toString(16))
        val raw = sb.toString()
        return raw.take(32).padEnd(32, '0')
    }

    fun generateIvHex(
        host: String,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String {
        val sb = StringBuilder()
        for (ch in saltChars) {
            if (ch.isNotEmpty()) {
                sb.append(Character.codePointAt(ch, 0).toString(16))
            }
        }
        if (host.isNotEmpty()) {
            sb.append(PoseidonCrypto.bytesToHex(host.toByteArray(StandardCharsets.UTF_8)))
        }
        val raw = sb.toString()
        return raw.take(32).padEnd(32, '0')
    }

    fun encryptHex(
        plain: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String {
        val keyBytes = PoseidonCrypto.hexToBytes(generateKeyHex(keyChars))
        val ivBytes = PoseidonCrypto.hexToBytes(generateIvHex(host, saltChars))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return PoseidonCrypto.bytesToHex(encrypted)
    }

    fun decryptHex(
        hexCiphertext: String,
        host: String,
        keyChars: List<String> = DEFAULT_KEY_CHARS,
        saltChars: List<String> = DEFAULT_SALT_CHARS,
    ): String? {
        return runCatching {
            val keyBytes = PoseidonCrypto.hexToBytes(generateKeyHex(keyChars))
            val ivBytes = PoseidonCrypto.hexToBytes(generateIvHex(host, saltChars))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val plainBytes = cipher.doFinal(PoseidonCrypto.hexToBytes(hexCiphertext))
            String(plainBytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

internal data class PoseidonVidHideStream(
    val key: String,
    val url: String,
)

internal data class PoseidonVidHideSubtitle(
    val label: String,
    val url: String,
)

internal data class PoseidonVidHideParsed(
    val origin: String,
    val streams: List<PoseidonVidHideStream>,
    val subtitles: List<PoseidonVidHideSubtitle>,
)

internal object PoseidonVidHideParser {
    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val PACKER_REGEX = Regex(
        """(?s)eval\(\s*function\(p,a,c,k,e,[dr]\)\{.*?return p\}\(\s*'([^'\\]*(?:\\.[^'\\]*)*)'\s*,\s*(\d+)\s*,\s*\d+\s*,\s*'([^'\\]*(?:\\.[^'\\]*)*)'\.split\('\|'\)""",
    )
    private val LINKS_REGEX = Regex("""(?s)\b(?:(?:var|let|const)\s+)?links\s*=\s*(\{[^}]+\})""")
    private val SOURCES_REGEX = Regex("""(?s)["']?sources["']?\s*:\s*(\[[^\]]*\])""")
    private val TRACKS_REGEX = Regex("""(?s)["']?tracks["']?\s*:\s*(\[[^\]]*\])""")
    private val WORD_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")
    private val MEDIA_URL_REGEX = Regex(
        """(?i)["']((?:https?:)?//[^\s"'<>]+|/[A-Za-z0-9_./~%-]+\.(?:m3u8|txt|mp4)(?:\?[^\s"'<>]*)?)["']""",
    )

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

    fun parse(html: String, finalUrl: String): PoseidonVidHideParsed {
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
        val subtitles = mutableListOf<PoseidonVidHideSubtitle>()

        for (block in blocks) {
            for (match in LINKS_REGEX.findAll(block)) {
                val rawObj = match.groupValues[1].replace("\\/", "/")
                val parsed = runCatching {
                    PoseidonHD2BisData.jsonMapper.readValue(
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
                    PoseidonHD2BisData.jsonMapper.readValue(
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
                    PoseidonHD2BisData.jsonMapper.readValue(
                        arrText,
                        object : TypeReference<List<Map<String, Any?>>>() {},
                    )
                }.getOrNull().orEmpty()
                for (entry in entries) {
                    val kind = entry["kind"]?.toString()?.lowercase().orEmpty()
                    if (!kind.contains("caption") && !kind.contains("subtitle")) continue
                    val file = entry["file"]?.toString()?.let { resolveAgainstOrigin(origin, it) } ?: continue
                    val label = entry["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtítulos"
                    subtitles += PoseidonVidHideSubtitle(label, file)
                }
            }
        }

        val ordered = mutableListOf<PoseidonVidHideStream>()
        val seenUrls = mutableSetOf<String>()
        fun addStream(key: String, u: String?) {
            if (u != null && seenUrls.add(u)) ordered += PoseidonVidHideStream(key, u)
        }

        // Prefer hls2 (clean standard MPEG-TS CDN) before generic hls/sources; avoid broken hls3 when hls2 exists
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

        return PoseidonVidHideParsed(
            origin = origin,
            streams = ordered,
            subtitles = subtitles.distinctBy { it.url },
        )
    }
}

class PoseidonVoeExtractor : ExtractorApi() {
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
        private val REDIRECT_REGEX = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "Voe",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val baseHeaders = mapOf(
                "User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val cookies = linkedMapOf<String, String>()
            var currentUrl = url
            var currentReferer = referer?.takeIf { it.isNotBlank() } ?: "https://player.poseidonhd2.co/"
            var res = app.get(currentUrl, referer = currentReferer, headers = baseHeaders, cookies = cookies, timeout = 16L)
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
                        res = app.get(currentUrl, referer = currentReferer, headers = baseHeaders, cookies = cookies, timeout = 16L)
                        cookies.putAll(res.cookies)
                    }
                }
            }

            if (res.text.contains("altcha-widget", ignoreCase = true)) {
                val doc = Jsoup.parse(res.text, res.url)
                val challengeAttr = doc.selectFirst("altcha-widget")?.attr("challenge")?.trim().orEmpty()
                val formAction = doc.selectFirst("form.access-form, form[method=POST]")?.attr("abs:action")
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
                        timeout = 16L,
                    )
                    cookies.putAll(chalRes.cookies)
                    val altchaPayload = PoseidonCrypto.solveAltchaChallenge(chalRes.text) ?: return false
                    val origin = PoseidonVidHideParser.originOf(res.url)
                    val postRes = app.post(
                        formAction,
                        referer = res.url,
                        headers = baseHeaders + mapOf(
                            "Origin" to origin,
                            "Content-Type" to "application/x-www-form-urlencoded",
                        ),
                        cookies = cookies,
                        data = mapOf(
                            "_token" to token,
                            "access" to accessVal,
                            "altcha" to altchaPayload,
                        ),
                        timeout = 18L,
                    )
                    cookies.putAll(postRes.cookies)
                    res = postRes
                }
            }

            val doc = Jsoup.parse(res.text, res.url)
            val encodedJson = doc.selectFirst("script[type=application/json]")
                ?.data()
                ?.trim()
                ?.let { raw ->
                    Regex("""\[\s*"([^"]+)"\s*]""").find(raw)?.groupValues?.getOrNull(1)
                        ?: raw.substringAfter("[\"", "").substringBeforeLast("\"]", "")
                }
                ?.takeIf { it.isNotEmpty() }

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

            val decrypted = PoseidonVoeCrypto.decryptF7(encodedJson) ?: return false
            val origin = PoseidonVidHideParser.originOf(res.url)
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
            )

            var emitted = false
            val m3u8Url = decrypted.source?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (m3u8Url != null) {
                val links = runCatching {
                    M3u8Helper.generateM3u8(
                        sourceName,
                        m3u8Url,
                        "$origin/",
                        headers = streamHeaders,
                    )
                }.getOrElse { emptyList() }
                if (links.isNotEmpty()) {
                    links.forEach {
                        callback(it)
                        emitted = true
                    }
                } else {
                    callback(
                        newExtractorLink(sourceName, sourceName, m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = "$origin/"
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    emitted = true
                }
            }

            val mp4Url = decrypted.directAccessUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            if (!emitted && mp4Url != null) {
                callback(
                    newExtractorLink(sourceName, "$sourceName MP4", mp4Url, INFER_TYPE) {
                        this.referer = res.url
                        this.quality = Qualities.Unknown.value
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

class PoseidonMedixiruExtractor : PoseidonStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://medixiru.com"
}

class PoseidonHglinkExtractor : PoseidonStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://hglink.to"
}

class PoseidonVsembedExtractor : PoseidonStreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://vsembed.com"
}

open class PoseidonStreamWishExtractor : ExtractorApi() {
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
        private val REDIRECT_HOSTS = listOf("https://medixiru.com")
        private val JS_ASSET_REGEX = Regex("""src=["'](/assets/[^"']+\.js)["']""")

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "StreamWish",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val baseHeaders = mapOf(
                "User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "https://player.poseidonhd2.co/"
            var res = app.get(url, referer = effReferer, headers = baseHeaders, timeout = 16L)

            // Branch 1: Classic packed / JWPlayer embed directly returned
            val unpacked = PoseidonVidHideParser.parse(res.text, res.url)
            if (unpacked.streams.isNotEmpty()) {
                return emitParsedStreams(unpacked, sourceName, subtitleCallback, callback)
            }

            // Branch 2: streamwish.to / mirror returning Loading... + /main.js redirector -> hop via medixiru.com to vsembed.com
            if (!res.url.contains("vsembed.com", ignoreCase = true) &&
                (res.text.contains("main.js", ignoreCase = true) || res.text.contains("Loading...", ignoreCase = true))
            ) {
                val fileCode = runCatching {
                    URI(url).rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
                }.getOrDefault("")
                if (fileCode.isNotEmpty()) {
                    for (mirror in REDIRECT_HOSTS) {
                        val hopped = runCatching {
                            app.get("$mirror/e/$fileCode", referer = res.url, headers = baseHeaders, timeout = 16L)
                        }.getOrNull()
                        if (hopped != null) {
                            res = hopped
                            if (res.url.contains("vsembed.com", ignoreCase = true)) break
                            val mirrorParsed = PoseidonVidHideParser.parse(res.text, res.url)
                            if (mirrorParsed.streams.isNotEmpty()) {
                                return emitParsedStreams(mirrorParsed, sourceName, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }

            // Branch 3: vsembed.com SPA API + AES-128-CBC tracking header
            if (res.url.contains("vsembed.com", ignoreCase = true)) {
                if (extractFromVsembed(res.url, res.text, sourceName, subtitleCallback, callback)) {
                    return true
                }
            }

            // Branch 4: Native CloudStream StreamWish fallback
            var fallbackEmitted = false
            runCatching {
                StreamWishExtractor().getUrl(url, referer, subtitleCallback) { link ->
                    fallbackEmitted = true
                    callback(link)
                }
            }
            if (fallbackEmitted) return true

            // Branch 5: JwPlayerHelper on raw script text
            val doc = Jsoup.parse(res.text, res.url)
            val scriptData = doc.select("script")
                .firstOrNull { it.data().contains("sources:") || it.data().contains("jwplayer(") }
                ?.data()
            if (!scriptData.isNullOrBlank()) {
                val origin = PoseidonVidHideParser.originOf(res.url)
                var jwFound = false
                JwPlayerHelper.extractStreamLinks(
                    scriptData,
                    sourceName,
                    origin,
                    { jwFound = true; callback(it) },
                    subtitleCallback,
                    mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT),
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
                "User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to pageUrl,
            )

            // Subtitles + metadata (non-fatal)
            runCatching {
                val streamInfoRes = app.get("$origin/api/stream?s=$shortCode&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 12L)
                if (streamInfoRes.isSuccessful) {
                    val dataNode = PoseidonHD2BisData.jsonMapper.readTree(streamInfoRes.text).get("data")
                    val subsNode = dataNode?.get("subtitles")
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

            var keyChars = PoseidonVsembedCrypto.DEFAULT_KEY_CHARS
            var saltChars = PoseidonVsembedCrypto.DEFAULT_SALT_CHARS

            var encParam = PoseidonVsembedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
            var trackRes = app.get("$origin/api/stream/tracking?c=$encParam&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 14L)
            var xTracking = trackRes.headers["x-tracking"]

            var decryptedJson = xTracking?.let { PoseidonVsembedCrypto.decryptHex(it, host, keyChars, saltChars) }

            // Dynamic keyChars/saltChars fallback if vsembed rotates bundle keys
            if (decryptedJson == null) {
                val assetPath = JS_ASSET_REGEX.find(pageHtml)?.groupValues?.getOrNull(1)
                if (assetPath != null) {
                    val bundleText = runCatching {
                        app.get("$origin$assetPath", referer = pageUrl, headers = apiHeaders, timeout = 14L).text
                    }.getOrNull()
                    if (bundleText != null) {
                        val parsedKeys = parseCharArray(bundleText, "keyChars")
                        val parsedSalts = parseCharArray(bundleText, "saltChars")
                        if (!parsedKeys.isNullOrEmpty() && !parsedSalts.isNullOrEmpty()) {
                            keyChars = parsedKeys
                            saltChars = parsedSalts
                            encParam = PoseidonVsembedCrypto.encryptHex(shortCode, host, keyChars, saltChars)
                            trackRes = app.get("$origin/api/stream/tracking?c=$encParam&ld=1", referer = pageUrl, headers = apiHeaders, timeout = 14L)
                            xTracking = trackRes.headers["x-tracking"]
                            decryptedJson = xTracking?.let { PoseidonVsembedCrypto.decryptHex(it, host, keyChars, saltChars) }
                        }
                    }
                }
            }

            val jsonText = decryptedJson ?: return false
            val root = runCatching { PoseidonHD2BisData.jsonMapper.readTree(jsonText) }.getOrNull() ?: return false
            val streamNode = root.get("stream") ?: root
            val hlsUrl = streamNode.get("hls")?.asText()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            val streamHeaders = mapOf(
                "Origin" to origin,
                "Referer" to "$origin/",
            )

            var emitted = false
            if (hlsUrl != null) {
                val generated = runCatching {
                    M3u8Helper.generateM3u8(sourceName, hlsUrl, "$origin/", headers = streamHeaders)
                }.getOrElse { emptyList() }
                if (generated.isNotEmpty()) {
                    generated.forEach {
                        callback(it)
                        emitted = true
                    }
                } else {
                    callback(
                        newExtractorLink(sourceName, sourceName, hlsUrl, ExtractorLinkType.M3U8) {
                            this.referer = "$origin/"
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    emitted = true
                }
            }
            return emitted
        }

        private fun parseCharArray(bundleText: String, fieldName: String): List<String>? {
            val match = Regex("""this\.$fieldName=(\[[^\]]+\])""").find(bundleText)?.groupValues?.getOrNull(1)
                ?: return null
            return runCatching {
                PoseidonHD2BisData.jsonMapper.readValue(match, object : TypeReference<List<String>>() {})
            }.getOrNull()
        }

        private suspend fun emitParsedStreams(
            parsed: PoseidonVidHideParsed,
            sourceName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val streamHeaders = mapOf(
                "Origin" to parsed.origin,
                "Referer" to "${parsed.origin}/",
            )
            for (sub in parsed.subtitles) {
                subtitleCallback(newSubtitleFile(sub.label, sub.url))
            }
            var emitted = false
            for (stream in parsed.streams) {
                if (stream.key == "hls3" && emitted) continue
                val generated = runCatching {
                    M3u8Helper.generateM3u8(sourceName, stream.url, "${parsed.origin}/", headers = streamHeaders)
                }.getOrElse { emptyList() }
                if (generated.isNotEmpty()) {
                    generated.forEach {
                        callback(it)
                        emitted = true
                    }
                    break
                }
            }
            return emitted
        }
    }
}

class PoseidonVidHideProExtractor : PoseidonVidHideExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhidepro.com"
}

class PoseidonCallistaniseExtractor : PoseidonVidHideExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://callistanise.com"
}

open class PoseidonVidHideExtractor : ExtractorApi() {
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
        )

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "VidHide",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val normalizedUrl = PoseidonVidHideParser.normalizeEmbedUrl(url)
            val parsedUri = runCatching { URI(normalizedUrl) }.getOrNull()
            val pathWithQuery = buildString {
                append(parsedUri?.rawPath?.ifEmpty { "/" } ?: "/")
                val q = parsedUri?.rawQuery
                if (!q.isNullOrEmpty()) append("?$q")
            }

            val candidateUrls = buildList {
                add(normalizedUrl)
                val origOrigin = runCatching { PoseidonVidHideParser.originOf(normalizedUrl) }.getOrNull()
                for (mirror in MIRROR_ORIGINS) {
                    if (!mirror.equals(origOrigin, ignoreCase = true)) {
                        add("$mirror$pathWithQuery")
                    }
                }
            }

            val reqHeaders = mapOf(
                "User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            )
            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "https://player.poseidonhd2.co/"

            var parsed: PoseidonVidHideParsed? = null
            for (candidate in candidateUrls) {
                val res = try {
                    app.get(candidate, referer = effReferer, headers = reqHeaders, timeout = 8L)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (res.code == 404 || res.code == 410) break
                if (!res.isSuccessful) continue

                val attempt = PoseidonVidHideParser.parse(res.text, res.url)
                if (attempt.streams.isNotEmpty()) {
                    parsed = attempt
                    break
                }
                if (res.text.contains("File was deleted", ignoreCase = true) ||
                    res.text.contains("File not found", ignoreCase = true) ||
                    res.text.contains("no longer available", ignoreCase = true)
                ) {
                    break
                }
            }

            val result = parsed ?: return false
            for (sub in result.subtitles) {
                subtitleCallback(newSubtitleFile(sub.label, sub.url))
            }

            val streamHeaders = mapOf(
                "Origin" to result.origin,
                "Referer" to "${result.origin}/",
            )

            // Prefer hls2 (clean standard MPEG-TS CDN) and skip broken hls3/hls4 once a stream loads
            for (stream in result.streams) {
                if (stream.key == "hls3" && result.streams.any { it.key == "hls2" || it.key == "hls" }) {
                    continue
                }
                val links = runCatching {
                    M3u8Helper.generateM3u8(
                        sourceName,
                        stream.url,
                        "${result.origin}/",
                        headers = streamHeaders,
                    )
                }.getOrElse { emptyList() }
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                    return true
                }
            }

            val fallback = result.streams.firstOrNull { it.key != "hls3" } ?: result.streams.firstOrNull() ?: return false
            callback(
                newExtractorLink(sourceName, sourceName, fallback.url, ExtractorLinkType.M3U8) {
                    this.referer = "${result.origin}/"
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                },
            )
            return true
        }
    }
}

class PoseidonByseExtractor : ByseSX() {
    override val name = "Filemoon"
    override val mainUrl = "https://bysejikuar.com"
}
