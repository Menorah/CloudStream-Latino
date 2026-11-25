package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player a `?trembed=` wrapper returns for the site's own copies ("Morencius").
 *
 * The saved pages are the ones the user captured from their browser. They are the reason this
 * suite exists: the real embed of that hoster is not an `<iframe>` but a VidHide page whose
 * JW Player setup is hidden inside `eval(function(p,a,c,k,e,d)…)`. Reading only iframes found
 * nothing, and handing the wrapper to `loadExtractor` found no extractor for the hoster either,
 * so the app answered "no links found" on pages that clearly had players.
 */
class PeliCineHDPlayerTest {
    private fun resource(name: String, base: String): Document {
        val html = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
        return Jsoup.parse(html, base)
    }

    private fun moviePlayer() = resource(
        "vidhide-player-real.html",
        "https://morencius.com/v/a01xmf7qgzgc"
    )

    @Test
    fun `the wrapper of the site resolves to the hoster page`() {
        val wrapper = resource("embed-wrapper-real.html", "https://pelicinehd.com/?trembed=0&trid=80650&trtype=2")
        val embed = PeliCineHDMarkup.embed(wrapper)
        assertNotNull(embed)
        assertEquals("https://morencius.com/v/ei06pqvqtfey", embed!!.url)
        // An iframe already is the hoster page, so there is nothing to decode.
        assertNull(embed.hoster)
    }

    @Test
    fun `the packed player of a movie yields its signed manifest`() {
        val embed = PeliCineHDMarkup.embed(moviePlayer())
        assertNotNull(embed)
        val url = embed!!.url
        assertTrue(url, url.startsWith("https://wt4PjIIVE9AGjPL.dramiyos-cdn.com/hls2/"))
        assertTrue(url, url.endsWith("/a01xmf7qgzgc_n/master.m3u8?t=kJbJN6H3KXoA8YBpCbQItrPfz5T7qQnBECCZIJOc9zU&s=1795574634&e=129600&f=44069916&srv=Z8VeXJnplmoxw&i=0.4&sp=500&p1=Z8VeXJnplmoxw&p2=Z8VeXJnplmoxw&asn=272064"))
    }

    @Test
    fun `the hoster is read from the player instead of guessed`() {
        // The page names its player in `abouttext:"VidHide"`; the manifest lives on a rotating CDN.
        assertEquals("vidhide.com", PeliCineHDMarkup.embed(moviePlayer())!!.hoster)
    }

    @Test
    fun `the packed player of an episode yields a different manifest`() {
        val embed = PeliCineHDMarkup.embed(
            resource("vidhide-player-episode-real.html", "https://morencius.com/v/ei06pqvqtfey")
        )
        assertNotNull(embed)
        assertTrue(embed!!.url, embed.url.startsWith("https://dD2STLiWT0bC.dramiyos-cdn.com/hls2/"))
        assertTrue(embed.url, embed.url.contains("/ei06pqvqtfey_n/master.m3u8"))
    }

    @Test
    fun `ad slots and beacons never win over the player`() {
        // The wrapper prints `logs.*` and `imasdk` URLs before the player setup.
        val html = """
            <html><body>
              <script>
                $.post('https://logs.mosevura.com/dl', {op:'view4'});
                var s = 'https://imasdk.googleapis.com/js/sdkloader/ima3.js';
                var ad = 'https://afs.example.com/ad.js';
              </script>
              <div class="Video"><iframe src="https://morencius.com/v/ei06pqvqtfey"></iframe></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://morencius.com/v/ei06pqvqtfey",
            PeliCineHDMarkup.embed(Jsoup.parse(html, "https://pelicinehd.com/?trembed=0&trid=1")).url
        )
    }

    @Test
    fun `an unpacker decodes the radix-36 dictionary of the site`() {
        // The obfuscator replaces tokens in one pass and indents its numbers in base 36; decoding
        // with the classic ordered loop produced an empty result on the real pages.
        val packed = """
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('b n={"1d":"1r://6s.dg-df.1q","15":"1r://6n.cc/cy.6m"};',36,3,'file|m3u8|https://cdn.example.com/master.m3u8'.split('|')))
        """.trimIndent()
        val decoded = PeliCineHDMarkup.unpack(packed)
        assertNotNull(decoded)
        assertTrue(decoded!!, decoded.contains("var"))
    }

    @Test
    fun `a page without any player is reported as such`() {
        assertNull(PeliCineHDMarkup.embed(Jsoup.parse("<html><body>Sin reproductor</body></html>", "https://pelicinehd.com/?trembed=0&trid=1")))
        assertNull(PeliCineHDMarkup.unpackedPlayer("<html><body>nada</body></html>"))
    }

    @Test
    fun `the site wrapper is never taken as the media url`() {
        // `?trembed=` links are on the site itself; returning one would loop the request.
        val html = """<html><body><a href="https://pelicinehd.com/?trembed=1&trid=2&trtype=1">x</a></body></html>"""
        assertNull(PeliCineHDMarkup.embed(Jsoup.parse(html, "https://pelicinehd.com/?trembed=0&trid=1")))
        assertFalse(PeliCineHDMarkup.siteUrl("https://morencius.com/v/x"))
    }
}
