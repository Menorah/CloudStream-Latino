package com.menorah.sololatino

import com.fasterxml.jackson.core.Base64Variants
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Embed69ServersTest {
    private fun encrypt(text: String, key: ByteArray): String {
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64Variants.getDefaultVariant().encode(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    @Test fun realFixtureKeepsLanguageAndAllThreeStreamingOptions() {
        val fixture = javaClass.getResource("/fixtures/embed69-dark.js")!!.readText()
        val servers = Embed69Servers.parse(fixture)
        assertEquals(listOf("vidhide", "streamwish", "voe"), servers.map { it.name })
        assertTrue(servers.all { it.language == "LAT" && it.url.startsWith("https://") })
    }

    @Test fun currentContentKeyHandlesLanguagesAndExcludesDownloads() {
        val key = MessageDigest.getInstance("SHA-256").digest("abc123pepper".toByteArray())
        val lat = encrypt("`https://stream.example/e/1`", key)
        val sub = encrypt("https://sub.example/e/2", key)
        val bad = encrypt("http://unsafe.example/e/3", key)
        val local = encrypt("https://127.0.0.1/private", key)
        val html = """<script>const LINK_CHALLENGE = 'abc123'; const LINK_SALT = 'pepper';
            let dataLink = [{"video_language":"LAT","sortedEmbeds":[{"servername":"Streamwish","link":"$lat"},{"servername":"Download","link":"$lat"},{"servername":"Bad","link":"$bad"},{"servername":"Local","link":"$local"}]},{"video_language":"SUB","sortedEmbeds":[{"servername":"VOE","link":"$sub"}]}];</script>"""
        val result = Embed69Servers.parse(html)
        assertEquals(listOf("LAT", "SUB"), result.map { it.language })
        assertEquals(listOf("Streamwish", "VOE"), result.map { it.name })
        assertEquals(listOf("https://stream.example/e/1", "https://sub.example/e/2"), result.map { it.url })
    }

    @Test fun acceptsLiteralKeyAndRejectsInvalidOrMissingData() {
        val key = "0123456789abcdef".toByteArray()
        val encrypted = encrypt("https://example.com/e/one", key)
        val html = """let dataLink = [{"video_language":"LAT","sortedEmbeds":[{"servername":"One","link":"$encrypted"}]}]; decryptLink(server.link, '0123456789abcdef')"""
        assertEquals("One", Embed69Servers.parse(html).single().name)
        assertTrue(Embed69Servers.parse("Just a moment...").isEmpty())
        assertTrue(Embed69Servers.parse("const POW_DIFFICULTY = 99; let dataLink = []; ").isEmpty())
        assertTrue(Embed69Servers.parse("let dataLink = [bad json];").isEmpty())
    }
}
