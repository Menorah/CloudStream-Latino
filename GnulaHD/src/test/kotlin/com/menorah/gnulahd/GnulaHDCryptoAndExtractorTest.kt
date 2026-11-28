package com.menorah.gnulahd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class GnulaHDCryptoAndExtractorTest {

    @Test
    fun `fileCodeFromPath strips gnraw fragment and query parameters cleanly`() {
        assertEquals("xkNOuHSrCNMj", GnulaHttpDefaults.fileCodeFromPath("https://vidara.to/e/xkNOuHSrCNMj#gnraw"))
        assertEquals("xkNOuHSrCNMj", GnulaHttpDefaults.fileCodeFromPath("https://vidaraa.cc/xkNOuHSrCNMj?24#gnraw"))
        assertEquals("abc123", GnulaHttpDefaults.fileCodeFromPath("https://savefiles.com/e/abc123?auto=1#gnraw"))
        assertEquals("xyz789", GnulaHttpDefaults.fileCodeFromPath("https://bysevepoin.com/e/xyz789#gnraw"))
    }

    @Test
    fun `decodeVidsonicUrl decodes hex reversed player URL`() {
        val target = "https://cdn.example.net/master.m3u8?s=1"
        val reversedHex = target.reversed().map { "%02x".format(it.code) }.chunked(5).joinToString("|")
        val html = "<script>const _0x1 = '$reversedHex';</script>"
        assertEquals(target, GnulaHostParsers.decodeVidsonicUrl(html))
    }

    @Test
    fun `decodeVixeoConfig decodes base64 JSON config`() {
        val json = """{"source":"https://cdn.vixeo.io/stream/master.m3u8"}"""
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        assertEquals("https://cdn.vixeo.io/stream/master.m3u8", GnulaHostParsers.decodeVixeoConfig(b64))
    }

    @Test
    fun `extractSavefilesStream and subtitles parse inline and packed snippets`() {
        val html = """
            <script>
            jwplayer("vplayer").setup({
              sources: [{file:"https://cdn.savefiles.com/hls/master.m3u8"}],
              tracks: [{file:"https://cdn.savefiles.com/subs/es.vtt", label:"Español"}, {file:"https://cdn.savefiles.com/thumbs.vtt", kind:"thumbnails"}]
            });
            </script>
        """.trimIndent()
        assertEquals("https://cdn.savefiles.com/hls/master.m3u8", GnulaHostParsers.extractSavefilesStream(html))
        val subs = GnulaHostParsers.extractSavefilesSubtitles(html)
        assertEquals(listOf("Español" to "https://cdn.savefiles.com/subs/es.vtt"), subs)
    }

    @Test
    fun `Byse AES-GCM playback decryption and P-256 signature round-trip`() {
        val keyPart1 = ByteArray(16) { (it + 1).toByte() }
        val keyPart2 = ByteArray(16) { (it + 17).toByte() }
        val key = keyPart1 + keyPart2
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val plain = """{"sources":[{"url":"https://stream.byse.sx/hls/master.m3u8","height":1080,"label":"HD"}],"tracks":[{"url":"https://stream.byse.sx/sub.vtt","label":"Latino"}]}"""

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))

        val decoded = GnulaByseCrypto.decryptPlaybackDetails(
            ivB64Url = GnulaByseCrypto.base64UrlNoPad(iv),
            payloadB64Url = GnulaByseCrypto.base64UrlNoPad(encrypted),
            keyParts = listOf(
                GnulaByseCrypto.base64UrlNoPad(keyPart1),
                GnulaByseCrypto.base64UrlNoPad(keyPart2),
            ),
        )
        assertNotNull(decoded)
        assertEquals("https://stream.byse.sx/hls/master.m3u8", decoded!!.sources[0].url)
        assertEquals(1080, decoded.sources[0].height)
        assertEquals("Latino", decoded.tracks[0].label)

        val kp = GnulaByseCrypto.ecKeyPairP256()
        val sig = GnulaByseCrypto.signChallengeNonce("test-nonce-123", kp)
        assertTrue(sig.isNotBlank())
    }
}
