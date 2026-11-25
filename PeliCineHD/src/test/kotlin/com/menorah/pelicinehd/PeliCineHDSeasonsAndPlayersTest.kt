package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seasons and players against the shapes the theme actually emits. */
class PeliCineHDSeasonsAndPlayersTest {
    private fun page(html: String) = Jsoup.parse(html, "https://pelicinehd.com/series/ejemplo/")

    @Test
    fun `every season of the selector is listed`() {
        // The Rings of Power shows three seasons on the site; the third only had a label.
        val html = """
            <div class="aa-drp choose-season">
              <button class="btn lnk npd aa-lnk"><span>elegir temporada</span>Temporada<dt class="n_s">2</dt></button>
              <ul class="aa-cnt sub-menu">
                <li><a data-post="1234" data-season="1" href="javascript:void(0)">Season 1</a></li>
                <li class="sel-temp"><a data-post="1234" data-season="2" href="javascript:void(0)">Season 2</a></li>
                <li><a data-post="1234">Temporada 3</a></li>
              </ul>
            </div>
        """.trimIndent()
        val seasons = PeliCineHDMarkup.seasons(page(html))
        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertTrue(seasons.all { it.post == 1234 })
    }

    @Test
    fun `a season without any attribute is still counted by its position`() {
        val html = """
            <div class="choose-season"><ul>
              <li><a href="javascript:void(0)">Season 1</a></li>
              <li><a href="javascript:void(0)">Season 2</a></li>
            </ul></div>
        """.trimIndent()
        assertEquals(listOf(1, 2), PeliCineHDMarkup.seasons(page(html)).map { it.number })
    }

    @Test
    fun `season numbers are read from the label`() {
        assertEquals(3, PeliCineHDMarkup.seasonInText("Temporada 3"))
        assertEquals(2, PeliCineHDMarkup.seasonInText("Season 2"))
        assertEquals(4, PeliCineHDMarkup.seasonInText("T4"))
        assertNull(PeliCineHDMarkup.seasonInText("Episodios"))
        assertNull(PeliCineHDMarkup.seasonInText(null))
    }

    @Test
    fun `a wrapper page yields the hoster embed instead of the site url`() {
        val html = """
            <html><body>
              <div class="video-player"><iframe src="https://voe.sx/e/abc123"></iframe></div>
            </body></html>
        """.trimIndent()
        assertEquals("https://voe.sx/e/abc123", PeliCineHDMarkup.embeddedUrl(page(html)))
    }

    @Test
    fun `a lazy iframe and a script-built player are both resolved`() {
        val lazy = PeliCineHDMarkup.embeddedUrl(page("""<iframe data-src="https://minochinos.com/embed/9"></iframe>"""))
        assertEquals("https://minochinos.com/embed/9", lazy)

        val script = PeliCineHDMarkup.embeddedUrl(
            page("""<script>var player = new Playerjs({file:"https://cdn.example.com/movie/index.m3u8",});</script>""")
        )
        assertEquals("https://cdn.example.com/movie/index.m3u8", script)
    }

    @Test
    fun `the site's own urls are never taken as the embed`() {
        val html = """
            <html><body>
              <iframe src="https://pelicinehd.com/?trembed=0&trid=1&trtype=1"></iframe>
              <a href="https://pelicinehd.com/peliculas/">Películas</a>
            </body></html>
        """.trimIndent()
        assertNull(PeliCineHDMarkup.embeddedUrl(page(html)))
    }
}
