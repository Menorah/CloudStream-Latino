package com.menorah.sololatino

import com.fasterxml.jackson.core.Base64Variants
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class EmbeddedServer(val language: String, val name: String, val url: String)

/**
 * Reads the server list of an already-accessible Embed69 player page. This does not
 * obtain a site session, solve an anti-bot challenge, or fetch/play any server.
 * Based on the documented data format and fixtures of xuper-plugin/maraton (Apache-2.0).
 */
internal object Embed69Servers {
    private val mapper = ObjectMapper()
    private const val MAX_PAGE = 2_000_000
    private const val MAX_NONCES = 1_000_000
    private const val MAX_WORK_NANOS = 2_000_000_000L
    private val assignment = Regex("\\b(?:let|var|const)\\s+dataLink\\s*=\\s*")
    private val directKey = Regex("decryptLink\\s*\\(\\s*server\\.link\\s*,\\s*['\"]([^'\"]{1,128})['\"]")

    private fun constant(html: String, name: String): String? =
        Regex("\\b${name}\\s*=\\s*['\"]([^'\"]{1,128})['\"]").find(html)?.groupValues?.get(1)

    private fun digest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun zeros(bytes: ByteArray, digits: Int): Boolean {
        for (index in 0 until digits / 2) if (bytes[index].toInt() != 0) return false
        return digits % 2 == 0 || (bytes[digits / 2].toInt() and 0xf0) == 0
    }

    private fun key(html: String): ByteArray? {
        val literal = directKey.find(html)?.groupValues?.get(1)?.toByteArray(Charsets.UTF_8)
        if (literal != null) return if (literal.size in listOf(16, 24, 32)) literal else literal.copyOf(32)
        val challenge = constant(html, "LINK_CHALLENGE")
        val salt = constant(html, "LINK_SALT")
        if (challenge != null && salt != null) return digest(challenge + salt)
        // Older players derive the content key from a small, page-local hash puzzle.
        val proof = constant(html, "POW_CHALLENGE") ?: return null
        val proofSalt = constant(html, "POW_SALT") ?: return null
        val difficulty = Regex("\\bPOW_DIFFICULTY\\s*=\\s*(\\d{1,2})\\b").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..5 } ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        for (nonce in 0 until MAX_NONCES) {
            if (nonce % 1024 == 0 && (Thread.currentThread().isInterrupted || System.nanoTime() - started > MAX_WORK_NANOS)) return null
            val attempt = proof + nonce
            if (zeros(hash.digest(attempt.toByteArray(Charsets.UTF_8)), difficulty)) return digest(attempt + proofSalt)
        }
        return null
    }

    private fun decrypt(encoded: String, key: ByteArray): String? = runCatching {
        if (encoded.length !in 1..8192) return null
        val data = Base64Variants.getDefaultVariant().decode(encoded)
        if (data.size < 32 || data.size % 16 != 0) return null
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data, 0, 16))
        String(cipher.doFinal(data, 16, data.size - 16), Charsets.UTF_8).trim().trim('`').trim()
    }.getOrNull()

    private fun publicHttps(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.matches(Regex("[0-9.]+")) && !host.contains(':')
    }

    fun parse(html: String): List<EmbeddedServer> {
        if (html.length > MAX_PAGE) return emptyList()
        val start = assignment.find(html)?.range?.last?.plus(1) ?: return emptyList()
        val sections = runCatching { mapper.factory.createParser(html.substring(start)).use { mapper.readTree<com.fasterxml.jackson.databind.JsonNode>(it) } }.getOrNull()
        if (sections == null || !sections.isArray || sections.size() > 50) return emptyList()
        val key = key(html) ?: return emptyList()
        val found = mutableListOf<EmbeddedServer>()
        for (section in sections) {
            val language = section.path("video_language").asText("").trim().take(32).ifBlank { "Idioma no indicado" }
            val servers = section.path("sortedEmbeds")
            if (!servers.isArray || servers.size() > 100) continue
            for (item in servers) {
                val name = item.path("servername").asText("").trim().take(80)
                val type = item.path("type").asText("")
                if (name.isBlank() || name.equals("download", true) || type.equals("download", true)) continue
                val url = decrypt(item.path("link").asText(""), key)?.takeIf(::publicHttps) ?: continue
                found.add(EmbeddedServer(language, name, url))
            }
        }
        return found.distinctBy { it.language to it.url }
    }
}
