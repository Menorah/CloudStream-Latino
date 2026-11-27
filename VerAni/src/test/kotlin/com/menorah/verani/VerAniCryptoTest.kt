package com.menorah.verani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VerAniCryptoTest {

    @Test
    fun `turboembed key and iv handle emoji codepoints without variation selector and roundtrip`() {
        // UTC day 20000 = 0x4e20; nowMs = 20000L * 86_400_000L
        val fixedNow = 20_000L * 86_400_000L
        val keyBytes = VerAniTurboEmbedCrypto.buildKeyBytes(nowMs = fixedNow)
        val ivBytes = VerAniTurboEmbedCrypto.buildIvBytes(host = "turboembed.com")

        assertEquals(16, keyBytes.size)
        assertEquals(16, ivBytes.size)
        // IV prefix = 1f510 (🔐) + 1f5dd (🗝️) + 1f511 (🔑) + 1f6aa (🚪) + 1f6e1 (🛡️) + 26a1 (⚡) = 29 hex chars,
        // padded to 32 hex chars with start of UTF-8 hex of "turboembed.com" ("7475..." -> "747")
        assertEquals("1f5101f5dd1f5111f6aa1f6e126a1747", VerAniCrypto.bytesToHex(ivBytes))

        val cipherHex = VerAniTurboEmbedCrypto.encryptHex("9r03", "turboembed.com", nowMs = fixedNow)
        val decrypted = VerAniTurboEmbedCrypto.decryptHex(cipherHex, "turboembed.com", nowMs = fixedNow)
        assertEquals("9r03", decrypted)
    }

    @Test
    fun `turboembed parses char arrays from js bundle`() {
        val js = """const keyChars=["𝕊","𝔼","𝕮"];const saltChars=['🔐','🗝️'];"""
        assertEquals(listOf("𝕊", "𝔼", "𝕮"), VerAniTurboEmbedCrypto.parseCharArray(js, "keyChars"))
        assertEquals(listOf("🔐", "🗝️"), VerAniTurboEmbedCrypto.parseCharArray(js, "saltChars"))
    }

    @Test
    fun `hls helper detects demuxed audio tracks and max height`() {
        val demuxedMaster = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio0",NAME="default",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="2",URI="a1.txt"
            #EXT-X-STREAM-INF:BANDWIDTH=1352803,RESOLUTION=1920x1080,FRAME-RATE=24.000,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio0"
            v1080p.txt
            #EXT-X-STREAM-INF:BANDWIDTH=650000,RESOLUTION=1280x720,AUDIO="audio0"
            v720p.txt
        """.trimIndent()
        assertTrue(VerAniHlsHelper.hasDemuxedAudio(demuxedMaster))
        assertEquals(1080, VerAniHlsHelper.parseMaxResolutionHeight(demuxedMaster))

        val muxedMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720
            https://cdn.example.com/720.m3u8
        """.trimIndent()
        assertFalse(VerAniHlsHelper.hasDemuxedAudio(muxedMaster))
        assertEquals(720, VerAniHlsHelper.parseMaxResolutionHeight(muxedMaster))
    }

    @Test
    fun `voe decryptF7 reverses exact 5-step obfuscation including special tokens`() {
        val originalJson = """{"source":"https://cdn.example.com/master.m3u8","direct_access_url":"https://cdn.example.com/file.mp4"}"""
        // Encode forward: b64 -> reverse -> shift +3 -> b64 -> insert special tokens -> rot13
        val b1 = VerAniCrypto.encodeBase64(originalJson.toByteArray(StandardCharsets.UTF_8))
        val rev = b1.reversed()
        val shifted = rev.map { (it.code + 3).toChar() }.joinToString("")
        val b2 = VerAniCrypto.encodeBase64(shifted.toByteArray(StandardCharsets.ISO_8859_1))
        val withMarkers = b2.substring(0, 6) + "@$" + b2.substring(6, 12) + "^^" + b2.substring(12, 18) + "#&" + b2.substring(18)
        val rot13 = withMarkers.map { ch ->
            when (ch) {
                in 'a'..'z' -> ('a'.code + (ch.code - 'a'.code + 13) % 26).toChar()
                in 'A'..'Z' -> ('A'.code + (ch.code - 'A'.code + 13) % 26).toChar()
                else -> ch
            }
        }.joinToString("")

        val html = """<html><body><script>window.location.href = 'https://caseyseemavoid.com/e/xyz';</script><script type="application/json">["$rot13"]</script></body></html>"""
        assertEquals("https://caseyseemavoid.com/e/xyz", VerAniVoeDecoder.findRedirectUrl(html))
        val payload = VerAniVoeDecoder.findEncodedPayload(html)!!
        assertEquals(originalJson, VerAniVoeDecoder.decryptF7(payload))
    }

    @Test
    fun `mp4upload parser normalizes valid urls skips undef and extracts direct player src`() {
        assertNull(VerAniMp4UploadParser.normalizeEmbedUrl("https://www.mp4upload.com/embed-undef.html"))
        assertEquals(
            "https://www.mp4upload.com/embed-abc123XYZ.html",
            VerAniMp4UploadParser.normalizeEmbedUrl("https://mp4upload.com/abc123XYZ"),
        )
        val html = """
            <script>
            player.src({
                type: "video/mp4",
                src: "https://a4.mp4upload.com:183/d/token123/video.mp4"
            });
            var opts = { width: 1280, height: 720 };
            </script>
        """.trimIndent()
        assertEquals("https://a4.mp4upload.com:183/d/token123/video.mp4", VerAniMp4UploadParser.extractVideoSrc(html))
        assertEquals(720, VerAniMp4UploadParser.extractHeight(html))
    }

    @Test
    fun `mxdrop parser extracts wurl and normalizes protocol`() {
        val html = """<script>MDCore.ref="";MDCore.wurl="//s-node.mxdcontent.net/v/12345.mp4";</script>"""
        assertEquals("https://s-node.mxdcontent.net/v/12345.mp4", VerAniMxDropParser.extractWurl(html))
    }

    @Test
    fun `byse pow solver satisfies difficulty and ecdsa p256 signature roundtrips`() {
        val nonce = "test-nonce-2026"
        val sol = VerAniBysePow.solveBlocking(nonce, difficulty = 6, maxAttempts = 100_000)
        assertNotNull(sol)
        val hash = VerAniBysePow.hashNonce("$nonce:$sol".toByteArray(StandardCharsets.UTF_8))
        assertTrue(VerAniBysePow.countLeadingZeroBits(hash) >= 6)

        val kp = VerAniByseCrypto.ecKeyPairP256()
        val jwk = VerAniByseCrypto.toJwkPublicKey(kp.public as java.security.interfaces.ECPublicKey)
        assertEquals("EC", jwk["kty"])
        assertEquals("P-256", jwk["crv"])
        val p1363B64 = VerAniByseCrypto.signChallengeNonce(nonce, kp)
        val rawSig = VerAniCrypto.decodeBase64Flexible(p1363B64)
        assertEquals(64, rawSig.size)

        // Verify signature validity with JDK Signature using DER conversion
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(kp.public)
        verifier.update(nonce.toByteArray(StandardCharsets.UTF_8))
        // Convert P1363 64-byte back to minimal ASN.1 DER sequence
        fun derInt(b: ByteArray): ByteArray {
            var s = 0
            while (s < b.size - 1 && b[s].toInt() == 0) s++
            val slice = b.copyOfRange(s, b.size)
            return if ((slice[0].toInt() and 0x80) != 0) byteArrayOf(0x02, (slice.size + 1).toByte(), 0x00) + slice
            else byteArrayOf(0x02, slice.size.toByte()) + slice
        }
        val derR = derInt(rawSig.copyOfRange(0, 32))
        val derS = derInt(rawSig.copyOfRange(32, 64))
        val derSeq = byteArrayOf(0x30, (derR.size + derS.size).toByte()) + derR + derS
        assertTrue(verifier.verify(derSeq))
    }

    @Test
    fun `byse decryptPlaybackDetails supports versioned key_parts selection`() {
        val keyPartA = ByteArray(16) { (it + 1).toByte() }
        val keyPartB = ByteArray(16) { (it + 21).toByte() }
        val keyParts = MutableList(30) {
            VerAniByseCrypto.base64UrlNoPad(ByteArray(16) { 0x55 })
        }
        // Version 3 -> idx1 = 2, idx2 = 27
        keyParts[2] = VerAniByseCrypto.base64UrlNoPad(keyPartA)
        keyParts[27] = VerAniByseCrypto.base64UrlNoPad(keyPartB)

        val iv = ByteArray(12) { (it + 3).toByte() }
        val fullKey = keyPartA + keyPartB
        val json = """
            {
              "sources": [
                {"url": "https://cdn.byse.example/360.m3u8", "height": 360, "label": "360p"},
                {"url": "https://cdn.byse.example/1080.m3u8", "height": 1080, "label": "1080p"}
              ],
              "tracks": [
                {"url": "https://cdn.byse.example/es.vtt", "label": "Español"}
              ]
            }
        """.trimIndent()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fullKey, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))

        val decoded = VerAniByseCrypto.decryptPlaybackDetails(
            ivB64Url = VerAniByseCrypto.base64UrlNoPad(iv),
            payloadB64Url = VerAniByseCrypto.base64UrlNoPad(encrypted),
            keyParts = keyParts,
            version = "3",
        )
        assertNotNull(decoded)
        decoded!!
        assertEquals(2, decoded.sources.size)
        assertEquals("https://cdn.byse.example/1080.m3u8", decoded.sources.first().url)
        assertEquals(1, decoded.tracks.size)
        assertEquals("Español", decoded.tracks.first().label)
    }

    @Test
    fun `decorateName appends language badge once`() {
        assertEquals("TurboEmbed [Latino]", VerAniMarkup.decorateLinkName("TurboEmbed", "Latino"))
        assertEquals("TurboEmbed [Latino]", VerAniMarkup.decorateLinkName("TurboEmbed [Latino]", "Latino"))
        assertEquals("Voe", VerAniMarkup.decorateLinkName("Voe", ""))
    }

    @Test
    fun `mxdrop parser unpacks Dean Edwards packed script`() {
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('0.1=\"//2.3/4/5.6\";',10,7,'MDCore|wurl|s1|mxdcontent|v|9999|mp4'.split('|')))"
        assertEquals("https://s1.mxdcontent/v/9999.mp4", VerAniMxDropParser.extractWurl(packed))
    }
}
