package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An episode page (`/episode/<slug>-<T>x<E>/`) is a full sheet of its own, like a movie page:
 * it has a title, a synopsis, the cast, one player panel and the list of the season. The app
 * opens it from the episode list, so `players` and `isSeries` must both behave there.
 */
class PeliCineHDEpisodePageTest {
    private fun page(): Document = Jsoup.parse(
        """
        <article class="post single">
          <div class="post-thumbnail"><figure><img src="https://pelicinehd.com/wp-content/uploads/rings.jpg"></figure></div>
          <aside>
            <header class="entry-header"><h1 class="entry-title">El Señor de los Anillos: Los Anillos de Poder 1x1</h1>
              <div class="entry-meta">
                <span class="genres"><a href="https://pelicinehd.com/category/drama/">Drama</a></span>
                <span class="duration fa-clock far">min</span>
                <span class="year fa-calendar far">2022</span>
              </div>
            </header>
            <div class="description">Galadriel está perturbada por las señales del regreso de un antiguo mal.</div>
            <ul class="cast-lst"><li><span>Director</span><p><a href="https://pelicinehd.com/director_tv/john-d-payne/">John D. Payne</a></p></li></ul>
          </aside>
          <footer><span class="vote fa-star"><span class="num">7.346</span><span>TMDB</span></span></footer>
        </article>
        <section class="section player episode">
          <aside class="video-player aa-cn" id="aa-options">
            <div id="options-0" class="video aa-tb hdd on">
              <iframe src="about:blank" data-src="https://pelicinehd.com/?trembed=0&amp;trid=80650&amp;trtype=2"></iframe>
            </div>
          </aside>
          <aside class="video-options"><ul class="aa-tbs aa-tbs-video">
            <li><a class="btn on" href="#options-0">OPCIÓN <span>1</span>
              <span class="server">Morencius                    -LAT HD</span></a></li>
          </ul></aside>
        </section>
        <div class="epsdsnv">
          <a href="https://pelicinehd.com/episode/el-senor-de-los-anillos-los-anillos-de-poder-1x2/" class="btn">siguiente</a>
        </div>
        <section class="section episodes">
          <div class="aa-drp choose-season">
            <ul class="aa-cnt sub-menu">
              <li class="sel-temp"><a data-post="10866" data-season="1" href="javascript:void(0)">Season 1</a></li>
            </ul>
          </div>
          <ul id="episode_by_temp">
            <li><article class="post episodes"><span class="num-epi">1x1</span>
              <h2 class="entry-title">Los Anillos de Poder 1x1</h2>
              <a href="https://pelicinehd.com/episode/el-senor-de-los-anillos-los-anillos-de-poder-1x1/" class="lnk-blk"></a>
            </article></li>
          </ul>
        </section>
        """.trimIndent(),
        "https://pelicinehd.com/episode/el-senor-de-los-anillos-los-anillos-de-poder-1x1/"
    )

    @Test
    fun `the episode sheet carries its own title, plot and player`() {
        val page = page()
        assertEquals(
            "El Señor de los Anillos: Los Anillos de Poder 1x1",
            PeliCineHDMarkup.title(page)
        )
        assertTrue(PeliCineHDMarkup.plot(page)!!.startsWith("Galadriel"))
        assertEquals(1, PeliCineHDMarkup.players(page).size)
        // The `trtype` of an episode is 2; the provider only needs the whole wrapper URL.
        assertEquals(
            "https://pelicinehd.com/?trembed=0&trid=80650&trtype=2",
            PeliCineHDMarkup.players(page)[0].url
        )
        // The panel label says the audio track, so the link is labelled even before extraction.
        assertEquals("Latino", PeliCineHDMarkup.players(page)[0].language)
        assertEquals(listOf("Drama"), PeliCineHDMarkup.genres(page))
        assertEquals(listOf("John D. Payne"), PeliCineHDMarkup.actors(page))
        assertEquals(7.346, PeliCineHDMarkup.score(page)!!, 0.0001)
    }

    @Test
    fun `an episode page is not offered as a season sheet`() {
        // It renders the season list, but it is an episode: the app already has that list.
        val page = page()
        assertTrue(PeliCineHDMarkup.isSeries(page))
        assertEquals(listOf(1), PeliCineHDMarkup.seasons(page).map { it.number })
        assertEquals(10866, PeliCineHDMarkup.seasonsPostId(page))
        assertFalse(PeliCineHDMarkup.seasons(page).isEmpty())
    }

    @Test
    fun `the language label of the panel is normalised`() {
        assertEquals("Latino", PeliCineHDMarkup.language("Morencius            -LAT HD"))
        assertEquals("Castellano", PeliCineHDMarkup.language("Voe   -CASTELLANO HD"))
        assertEquals("Subtitulado", PeliCineHDMarkup.language("Voe -SUB HD"))
        assertEquals("", PeliCineHDMarkup.language("Voe HD"))
        assertEquals("", PeliCineHDMarkup.language(null))
    }
}
