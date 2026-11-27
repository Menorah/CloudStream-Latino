package com.menorah.latanime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LatanimeCryptoTest {

    @Test
    fun `unpacker decodes uqload and mixdrop packed blocks without webview`() {
        val packedUqload = """
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('2("1").0({3:[{4:"5://6.7.8/9/a.b"}]});',12,12,'setup|vplayer|jwplayer|sources|file|https|s1|uqload|vc|v|video|mp4'.split('|'),0,{}))
        """.trimIndent()
        val uqloadUrl = LatanimeHostParsers.extractUqloadStream(packedUqload)
        assertEquals("https://s1.uqload.vc/v/video.mp4", uqloadUrl)

        val packedMixDrop = """
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0.1="//2.3.4/5/6.7";',8,8,'MDCore|wurl|s|mxdrop|click|v|ep1|mp4'.split('|'),0,{}))
        """.trimIndent()
        val mixDropUrl = LatanimeHostParsers.extractMixDropStream(packedMixDrop)
        assertEquals("https://s.mxdrop.click/v/ep1.mp4", mixDropUrl)
    }

    @Test
    fun `host parsers extract savefiles hexload and mp4upload correctly`() {
        val savefilesHtml = """
            <script>
              jwplayer("vplayer").setup({
                sources: [{file:"https://s4.savefiles.com/hls2/01/00520/,jxmuvev53n4r_n,.urlset/master.m3u8?t=abc"}],
                tracks: [
                  {file: "https://s4.savefiles.com/vtt/01/thumb.vtt", kind: "thumbnails"},
                  {file: "https://s4.savefiles.com/sub/es.vtt", label: "Español", kind: "captions"}
                ]
              });
            </script>
        """.trimIndent()
        assertEquals(
            "https://s4.savefiles.com/hls2/01/00520/,jxmuvev53n4r_n,.urlset/master.m3u8?t=abc",
            LatanimeHostParsers.extractSavefilesStream(savefilesHtml),
        )
        val subs = LatanimeHostParsers.extractSavefilesSubtitles(savefilesHtml)
        assertEquals(listOf("Español" to "https://s4.savefiles.com/sub/es.vtt"), subs)

        val hexloadHtml = """
            $.ajax({
              type: 'POST',
              url: 'https://hexload.com/download',
              data: {
                op: 'download3',
                id: "hvpmcryhwgkd",
                ajax: '1',
                method_free: '1',
                dataType: "json",
              }
            });
        """.trimIndent()
        val args = LatanimeHostParsers.extractHexloadArgs(hexloadHtml, "fallback")
        assertEquals("download3", args["op"])
        assertEquals("hvpmcryhwgkd", args["id"])
        assertEquals("1", args["method_free"])

        val hexloadJson = """
            {"msg":"OK","result":{"folder":null,"url":"https://cdn.example.top/d/abc/Boti3-01.mp4","size":164528095},"status":200}
        """.trimIndent()
        assertEquals("https://cdn.example.top/d/abc/Boti3-01.mp4", LatanimeHostParsers.extractHexloadResultUrl(hexloadJson))

        val mp4Html = """player.src({ type: "video/mp4", src: "https://a1.mp4upload.com:183/d/xyz/video.mp4" });"""
        assertEquals("https://a1.mp4upload.com:183/d/xyz/video.mp4", LatanimeHostParsers.extractMp4UploadStream(mp4Html))
    }

    @Test
    fun `voe f7 decoder decrypts real reversible payload`() {
        val rawJson = """{"source":"https://cdn.voe/master.m3u8","direct_access_url":"https://cdn.voe/ep1.mp4"}"""
        val b2 = Base64.getEncoder().encodeToString(rawJson.toByteArray(StandardCharsets.UTF_8))
        val rev = b2.reversed()
        val shifted = buildString { for (c in rev) append((c.code + 3).toChar()) }
        val b1 = Base64.getEncoder().encodeToString(shifted.toByteArray(StandardCharsets.UTF_8))
        val withNoise = b1.replace("A", "@$")
        val rot = buildString {
            for (c in withNoise) {
                when (c) {
                    in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                    in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                    else -> append(c)
                }
            }
        }
        val html = """<script type="application/json">["$rot"]</script>"""
        val decoded = LatanimeVoeDecoder.extractPayloadFromHtml(html)
        assertNotNull(decoded)
        assertEquals("https://cdn.voe/master.m3u8", decoded!!.path("source").asText())
        assertEquals("https://cdn.voe/ep1.mp4", decoded.path("direct_access_url").asText())

        assertEquals(
            "https://caseyseemavoid.com/e/9bb05eump8bb",
            LatanimeVoeDecoder.findRedirectUrl("""<script>window.location.href = 'https://caseyseemavoid.com/e/9bb05eump8bb';</script>"""),
        )
    }

    @Test
    fun `byse crypto signs p256 solves pow and decrypts versioned aes gcm payload`() {
        val kp = LatanimeByseCrypto.ecKeyPairP256()
        val jwk = LatanimeByseCrypto.toJwkPublicKey(kp.public as ECPublicKey)
        assertEquals("EC", jwk["kty"])
        assertEquals("P-256", jwk["crv"])

        val sig = LatanimeByseCrypto.signChallengeNonce("test-nonce-123", kp)
        assertEquals(64, LatanimeByseCrypto.decodeBase64Flexible(sig).size)

        val powSol = LatanimeBysePow.solveBlocking("nonce-test", difficulty = 4)
        assertNotNull(powSol)

        // Versioned key_parts (version = "2" -> indices 1 and 28 of 30)
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 10).toByte() }
        val plain = """{"sources":[{"url":"https://cdn.byse/master.m3u8","height":1080,"label":"1080p"}]}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))

        val dummyPart = LatanimeByseCrypto.base64UrlNoPad(ByteArray(16))
        val keyParts = MutableList(30) { dummyPart }
        keyParts[1] = LatanimeByseCrypto.base64UrlNoPad(keyBytes.copyOfRange(0, 16))
        keyParts[28] = LatanimeByseCrypto.base64UrlNoPad(keyBytes.copyOfRange(16, 32))

        val decrypted = LatanimeByseCrypto.decryptPlaybackDetails(
            ivB64Url = LatanimeByseCrypto.base64UrlNoPad(iv),
            payloadB64Url = LatanimeByseCrypto.base64UrlNoPad(encrypted),
            keyParts = keyParts,
            version = "2",
        )
        assertNotNull(decrypted)
        assertEquals(1, decrypted!!.sources.size)
        assertEquals("https://cdn.byse/master.m3u8", decrypted.sources[0].url)
        assertEquals(1080, decrypted.sources[0].height)
    }

    @Test
    fun `hls helper detects demuxed audio tracks`() {
        val demuxed = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Spanish"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1920x1080,AUDIO="aud"
            video.m3u8
        """.trimIndent()
        assertTrue(LatanimeHlsHelper.hasDemuxedAudio(demuxed))
        assertEquals(1080, LatanimeHlsHelper.parseMaxResolutionHeight(demuxed))
    }
}
