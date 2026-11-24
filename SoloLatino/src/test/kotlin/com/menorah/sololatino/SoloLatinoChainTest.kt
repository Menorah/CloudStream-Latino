package com.menorah.sololatino

import org.junit.Assert.*
import org.junit.Test

class SoloLatinoChainTest {
    @Test fun csrfTokenPercentDecodesButKeepsPlus() {
        val token = SoloLatinoChain.csrfToken(mapOf("XSRF-TOKEN" to "abc%2Bdef%3D"))
        assertEquals("abc+def=", token)
        val literalPlus = SoloLatinoChain.csrfToken(mapOf("xsrf-token" to "ab+cd"))
        assertEquals("ab+cd", literalPlus)
        assertNull(SoloLatinoChain.csrfToken(mapOf("other" to "x")))
        assertNull(SoloLatinoChain.csrfToken(mapOf("XSRF-TOKEN" to "")))
    }

    @Test fun apiBodyEscapesTokenAndRenderHeaderIsStable() {
        assertEquals("{\"t\":\"a\\\"b\\\\c\"}", SoloLatinoChain.apiBody("a\"b\\c"))
        val headers = SoloLatinoChain.apiHeaders("xsrf-value", "tok123", "https://sololatino.net/serie/x", 1_700_000_000_000L)
        assertEquals("xsrf-value", headers["X-XSRF-TOKEN"])
        assertEquals("https://sololatino.net", headers["Origin"])
        assertEquals("https://sololatino.net/serie/x", headers["Referer"])
        assertEquals(renderHeader("tok123", 1_700_000_000_000L), headers["X-SL-Render"])
        assertEquals(16, headers.getValue("X-SL-Render").length)
    }

    @Test fun classifyCoversAllPlayerKinds() {
        assertEquals(SoloLatinoChain.PlayerKind.DIRECT_MP4, SoloLatinoChain.classify("https://cdn.example.com/file.mp4"))
        assertEquals(SoloLatinoChain.PlayerKind.PELIS_SERIES_HOY, SoloLatinoChain.classify("https://player.pelisserieshoy.com/e/abc"))
        assertEquals(SoloLatinoChain.PlayerKind.EMBED69, SoloLatinoChain.classify("https://embed69.org/e/xyz"))
        assertEquals(SoloLatinoChain.PlayerKind.EMBED69, SoloLatinoChain.classify("https://www.embed69.com/v/1"))
        assertEquals(SoloLatinoChain.PlayerKind.IFRAME, SoloLatinoChain.classify("https://other.host/embed/1"))
        // The API type wins over the host guess.
        assertEquals(SoloLatinoChain.PlayerKind.DIRECT_MP4, SoloLatinoChain.classify("https://cdn.example.com/tokenized", "mp4"))
        assertEquals(SoloLatinoChain.PlayerKind.EMBED69, SoloLatinoChain.classify("https://weird.host/x", "embed69"))
    }

    @Test fun extractPlayerUrlAcceptsKnownShapes() {
        val a = SoloLatinoChain.extractPlayerUrl("""{"url":"https:\/\/player.pelisserieshoy.com\/e\/1","type":"pelisserieshoy"}""")!!
        assertEquals("https://player.pelisserieshoy.com/e/1", a.first)
        assertEquals("pelisserieshoy", a.second)
        val b = SoloLatinoChain.extractPlayerUrl("""{"player_url":"https://embed69.org/e/2","type":"embed69"}""")!!
        assertEquals("https://embed69.org/e/2", b.first)
        val c = SoloLatinoChain.extractPlayerUrl("""{"src":"https://other.host/p/3"}""")!!
        assertEquals("https://other.host/p/3", c.first)
        assertNull(SoloLatinoChain.extractPlayerUrl("""html without links"""))
        assertNull(SoloLatinoChain.extractPlayerUrl("""{"url":"http://insecure.example/x"}"""))
    }

    @Test fun decryptApiBodyWrapsLinks() {
        assertEquals("{\"links\":[\"https://a/1\",\"https://b/2\"]}", SoloLatinoChain.decryptApiBody(listOf("https://a/1", "https://b/2")))
        assertEquals("{\"links\":[\"https://a/\\\"x\"]}", SoloLatinoChain.decryptApiBody(listOf("https://a/\"x")))
    }

    @Test fun fixHostMapsMirrorDomains() {
        assertEquals("https://streamwish.to/e/1", SoloLatinoChain.fixHost("https://hglink.to/e/1"))
        assertEquals("https://vidhidepro.com/e/2", SoloLatinoChain.fixHost("https://mivalyo.com/e/2"))
        assertEquals("https://filemoon.sx/e/3", SoloLatinoChain.fixHost("https://filemoon.link/e/3"))
        assertEquals("https://other.com/e/4", SoloLatinoChain.fixHost("https://other.com/e/4"))
    }

    @Test fun rawEmbedsReadsDataLinkWithoutKey() {
        val html = """
            <script>let dataLink = [{"file_id":"1","video_language":"LATINO","sortedEmbeds":
            [{"servername":"Voe","link":"AAAA","type":""},{"servername":"Download","link":"BBBB","type":"download"}]}];</script>
        """.trimIndent()
        val raw = Embed69Raw.rawEmbeds(html)
        assertEquals(1, raw.size)
        assertEquals("LATINO", raw[0].language)
        assertEquals("Voe", raw[0].name)
        assertEquals("AAAA", raw[0].encrypted)
    }

    @Test fun decryptedLinksRequiresSuccess() {
        assertEquals(listOf("https://host/e/1"), Embed69Raw.decryptedLinks("""{"success":true,"links":[{"index":0,"link":"https://host/e/1"}]}"""))
        assertNull(Embed69Raw.decryptedLinks("""{"success":false,"links":[]}"""))
        assertNull(Embed69Raw.decryptedLinks("not json"))
    }
}
