package com.menorah.cinecalidadmybis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinecalidadMYBisMarkupTest {

    @Test
    fun parsesHomeAndPaginationWithWpPageNaviAndSrcsetFallback() {
        val html = """
            <html><body>
              <div id="content_inside">
                <div class="home_post_cont post_box">
                  <a href="https://www.cinecalidad.my/pelicula/el-corazon-de-la-bestia-4k-uhd/">
                    <img alt="El Corazón de la Bestia (2026)" srcset="https://www.cinecalidad.my/wp-content/uploads/2026/05/bestia-220x320.jpg 220w">
                  </a>
                  <div class="in_title">2026</div>
                </div>
                <div class="home_post_cont post_box">
                  <a href="https://www.cinecalidad.my/pelicula/no-salgas-2025/">
                    <img src="https://www.cinecalidad.my/wp-content/uploads/2026/04/nosalgas.jpg" alt="No Salgas (2025)">
                  </a>
                </div>
              </div>
              <div class="wp-pagenavi" role="navigation">
                <span class="pages">Pág. 1 de 227</span>
                <span class="current">1</span>
                <a class="page larger" href="https://www.cinecalidad.my/page/2/" title="Página 2">2</a>
              </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, CinecalidadMYBisMarkup.DEFAULT_MAIN_URL)
        val cards = CinecalidadMYBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("El Corazón de la Bestia", cards[0].title)
        assertEquals(2026, cards[0].year)
        assertTrue(cards[0].is4k)
        assertEquals("https://www.cinecalidad.my/wp-content/uploads/2026/05/bestia-220x320.jpg", cards[0].posterUrl)
        assertFalse(cards[1].is4k)
        assertTrue(CinecalidadMYBisMarkup.parseHasNext(doc, 1))
    }

    @Test
    fun parsesRealElCorazonDeLaBestiaOnlineOptionsKeepingRealHrefsAndPaneLanguage() {
        val html = """
            <html><body>
              <div class="single_left">
                <h1>El Corazón de la Bestia (2026)</h1>
                <table><tr>
                  <td><img src="https://www.cinecalidad.my/wp-content/uploads/2026/05/bestia.jpg"></td>
                  <td>
                    <p>Un exsoldado de las fuerzas especiales regresa a Alaska.</p>
                    <p id="imdb-box"><a href="https://www.imdb.com/title/tt123456/">6.8 / 10</a></p>
                    <span><strong>Género:</strong> <a href="https://www.cinecalidad.my/genero-peliculas/accion/">Acción</a></span>
                    <span><strong>Elenco:</strong> Brad Pitt, J.K. Simmons</span>
                  </td>
                </tr></table>
                <div id="panel_online">
                  <div class="pane_title">VER ONLINE</div>
                  <div class="pane_descripcion">Audio latino</div>
                  <ul class="linklist">
                    <a class="link onlinelink" data="jwhwrwzjh3gk" href="https://bysedikamoum.com/d/jwhwrwzjh3gk" service="OnlineFilemoon" target="_blank"><li>Filemoon</li></a>
                    <a class="link onlinelink" data="bsvlv6oulqfr" href="https://voe.sx/bsvlv6oulqfr" service="OnlineVoe" target="_blank"><li>Voe</li></a>
                    <a class="link onlinelink" data="sp7dxlerdkej" href="https://doodstream.com/d/sp7dxlerdkej" service="OnlineDoodstream" target="_blank"><li>Doodstream</li></a>
                    <a class="link onlinelink" data="UONA3BoC#DZBoOMu" href="https://mega.nz/file/UONA3BoC#DZBoOMu" service="OnlineMega" target="_blank"><li>Mega</li></a>
                    <a class="link onlinelink" data="xZe27iaD2_8" href="javascript:void(0);" service="Trailer" target="_blank"><li>Trailer</li></a>
                  </ul>
                </div>
              </div>
              <ul class="related-posts">
                <li><img src="https://www.cinecalidad.my/wp-content/uploads/rel-40x55.jpg"><a href="https://www.cinecalidad.my/pelicula/relacionada-2025/">Relacionada (2025) online</a></li>
              </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.cinecalidad.my/pelicula/el-corazon-de-la-bestia/")
        val detail = CinecalidadMYBisMarkup.parseMovieDetail(doc, "https://www.cinecalidad.my/pelicula/el-corazon-de-la-bestia/")
        assertNotNull(detail)
        detail!!
        assertEquals("El Corazón de la Bestia", detail.title)
        assertEquals(2026, detail.year)
        assertEquals(6.8, detail.rating10!!, 0.01)
        assertEquals("https://www.youtube.com/watch?v=xZe27iaD2_8", detail.trailerUrl)

        val opts = CinecalidadMYBisMarkup.parseOnlineOptions(doc)
        assertEquals(4, opts.size)
        assertEquals("Latino", opts[0].language)
        assertEquals("https://bysedikamoum.com/d/jwhwrwzjh3gk", opts[0].embedUrls[0])
        assertEquals("https://bysedikamoum.com/e/jwhwrwzjh3gk", opts[0].embedUrls[1])
        assertEquals("https://voe.sx/bsvlv6oulqfr", opts[1].embedUrls[0])
        assertEquals("https://doodstream.com/d/sp7dxlerdkej", opts[2].embedUrls[0])
        assertEquals("https://mega.nz/file/UONA3BoC#DZBoOMu", opts[3].embedUrls[0])
    }
}
