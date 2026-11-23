package com.menorah.cinecalidadam
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
class CinecalidadAMMarkupTest {
    private fun doc(s: String) = Jsoup.parse(s, "https://www.cinecalidad.am/")
    @Test fun cardsAndPaging() {
        val d=doc("""<article class="item naadb"><img alt="ad"><a href="https://ad.test">ad</a></article><article class="item"><div class="poster"><img data-src="https://image.tmdb.org/poster.jpg"><a href="/ver-serie/serie/"><div class="in_title">Una serie</div></a></div></article><article class="item"><div class="poster"><img src="https://image.tmdb.org/m.jpg"><a href="/ver-pelicula/peli/"><div class="in_title">Una peli</div></a></div></article><a href="/page/2/">2</a>""")
        val x=CinecalidadAMMarkup.cards(d); assertEquals(2,x.size); assertFalse(x[0].movie); assertTrue(x[1].movie); assertEquals("https://image.tmdb.org/poster.jpg",x[0].poster); assertTrue(CinecalidadAMMarkup.hasNext(d,1))
    }
    @Test fun episodesAndPlayers() {
        val d=doc("""<li><img data-src="https://image.tmdb.org/e.jpg"><div class="episodiotitle"><a href="/ver-el-episodio/tmdb-1-s2-e3/">Capítulo</a></div></li><li><a href="https://evil.test/ver-el-episodio/tmdb-1-s2-e4/">Mal</a></li><div id="panel_online"><div class="pane_descripcion">Audio latino</div><ul><li class="dooplay_player_option" data-option="https://vimeos.net/embed-a.html">Vimeos</li><li class="dooplay_player_option" data-option="https://videoapp.zip/e/movie/1">Videoapp</li><li class="dooplay_player_option dooplay_player_option_trailer" data-option="https://youtube.com/x">Trailer</li></ul></div>""")
        val episodes=CinecalidadAMMarkup.episodes(d); assertEquals(1, episodes.size); assertEquals(2, episodes[0].season); assertEquals(3, episodes[0].number); assertEquals("https://image.tmdb.org/e.jpg",episodes[0].poster)
        assertEquals(2,CinecalidadAMMarkup.players(d).size); assertTrue(CinecalidadAMMarkup.players(d).all { it.language=="Latino" })
        assertNull(CinecalidadAMMarkup.vimeosFromWrapper(doc("""<iframe src="https://vimeos.net.evil.test/a"></iframe>""")))
        assertEquals("https://vimeos.net/embed-x.html", CinecalidadAMMarkup.vimeosFromWrapper(doc("""<iframe src="https://vimeos.net/embed-x.html"></iframe>""")))
    }
}
