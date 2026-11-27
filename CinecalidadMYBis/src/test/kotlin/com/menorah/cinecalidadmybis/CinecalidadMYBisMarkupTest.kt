package com.menorah.cinecalidadmybis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinecalidadMYBisMarkupTest {

    @Test
    fun parsesUnquotedHomeCardsAndPagination() {
        val html = """
            <html><body>
              <div id=content><div id=content_inside>
                <div class="home_post_cont post_box">
                  <a href=https://www.cinecalidad.my/pelicula/heartland-2026-online-descarga/ >
                    <img width=220 height=320 src=https://www.cinecalidad.my/wp-content/uploads/2026/11/heartland-2026-4k-220x320.jpg alt="Heartland (2026)" title="Heartland (2026)">
                  </a>
                </div>
                <div class="home_post_cont post_box">
                  <a href=https://www.cinecalidad.my/pelicula/no-nos-rendimos-2026-online-descarga/ >
                    <img width=220 height=320 src=https://www.cinecalidad.my/wp-content/uploads/2026/11/serangan-balik-2026-220x320.jpg alt="No nos rendimos (2026)">
                  </a>
                </div>
                <a class="nextpostslink" rel="next" href="https://www.cinecalidad.my/page/2/">»</a>
              </div></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, CinecalidadMYBisMarkup.DEFAULT_MAIN_URL)
        val cards = CinecalidadMYBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("Heartland", cards[0].title)
        assertEquals(2026, cards[0].year)
        assertTrue(cards[0].is4k)
        assertEquals("https://www.cinecalidad.my/pelicula/heartland-2026-online-descarga/", cards[0].url)
        assertEquals("https://www.cinecalidad.my/wp-content/uploads/2026/11/heartland-2026-4k-220x320.jpg", cards[0].posterUrl)
        assertEquals("No nos rendimos", cards[1].title)
        assertFalse(cards[1].is4k)
        assertTrue(CinecalidadMYBisMarkup.parseHasNext(doc, 1))
    }

    @Test
    fun parsesMovieDetailAndOnlineOptionsFromRealMarkup() {
        val html = """
            <html><body>
            <div class=single_left>
              <h1>Ne Zha 2: El Renacer del Alma (2025)</h1>
              <table border=0><tr>
                <td><img src=https://www.cinecalidad.my/wp-content/uploads/2026/11/nezha-mo-tong-nao-hai-2025.jpg></td>
                <td>
                  <p>Tras una devastadora catástrofe, las almas de Nezha y Ao Bing sobreviven milagrosamente, aunque sus cuerpos se desintegran.</p>
                  <p><span><strong>Títulos:</strong>Ne Zha 2</span></p>
                  <div id=imdb-box><strong>IMDb:</strong> <a href=https://www.imdb.com/title/tt34956443/>7.9/10</a> de 27K votos</div>
                  <p>
                    <span><strong>Género:</strong><a href=https://www.cinecalidad.my/genero-peliculas/animacion/ >Animación</a> | <a href=https://www.cinecalidad.my/genero-peliculas/aventura/ >Aventura</a></span><br>
                    <span><strong>Director:</strong>Yu Yang</span><br>
                    <span><strong>Elenco:</strong>Yanting Lü, Joseph, Mo Han</span>
                  </p>
                </td>
              </tr></table>
              <div id=panel_online class=pane>
                <a href=javascript:void(0); class="link onlinelink" service=OnlineFilemoon data=c2ioduptr3bc><li>Filemoon</li></a>
                <a href=javascript:void(0); class="link onlinelink" service=OnlineVoe data=w96skpnfsutj><li>Voe</li></a>
                <a href=javascript:void(0); class="link onlinelink" service=OnlineDoodstream data=dm2phwhsf12r><li>Doodstream</li></a>
                <a href=javascript:void(0); class="link onlinelink" service=OnlineMega data=S2h0CDqS#Qccm3rzrfXcA7Ahx><li>Mega</li></a>
                <a href=javascript:void(0); class="link onlinelink" service=Trailer data=ETlOSBR92Fs><li>Trailer</li></a>
              </div>
              <ul class=related-posts>
                <li><img src=https://www.cinecalidad.my/wp-content/uploads/2025/08/ochi-40x55.jpg><a href=https://www.cinecalidad.my/pelicula/la-leyenda-de-ochi-2025-online-descarga/ >La leyenda de Ochi online</a></li>
              </ul>
            </div>
            </body></html>
        """.trimIndent()
        val movieUrl = "https://www.cinecalidad.my/pelicula/ne-zha-2-el-renacer-del-alma-2025-online-descarga/"
        val doc = Jsoup.parse(html, movieUrl)
        val detail = CinecalidadMYBisMarkup.parseMovieDetail(doc, movieUrl)
        assertNotNull(detail)
        detail!!
        assertEquals("Ne Zha 2: El Renacer del Alma", detail.title)
        assertEquals(2025, detail.year)
        assertEquals(7.9, detail.rating10!!, 0.01)
        assertEquals(listOf("Animación", "Aventura"), detail.genres)
        assertEquals(listOf("Yu Yang", "Yanting Lü", "Joseph", "Mo Han"), detail.actors)
        assertEquals("https://www.youtube.com/watch?v=ETlOSBR92Fs", detail.trailerUrl)
        assertEquals(1, detail.recommendations.size)
        assertEquals("https://www.cinecalidad.my/wp-content/uploads/2025/08/ochi-220x320.jpg", detail.recommendations[0].posterUrl)

        val opts = CinecalidadMYBisMarkup.parseOnlineOptions(doc)
        assertEquals(4, opts.size)
        assertEquals(
            listOf("https://bysedikamoum.com/e/c2ioduptr3bc", "https://filemoon.sx/e/c2ioduptr3bc"),
            opts[0].embedUrls,
        )
        assertEquals(listOf("https://voe.sx/e/w96skpnfsutj"), opts[1].embedUrls)
        assertEquals(listOf("https://doodstream.com/e/dm2phwhsf12r"), opts[2].embedUrls)
        assertEquals(listOf("https://mega.nz/embed/S2h0CDqS#Qccm3rzrfXcA7Ahx"), opts[3].embedUrls)
    }

    @Test
    fun buildsArchiveAndSearchUrlsCorrectly() {
        assertEquals(
            "https://www.cinecalidad.my/peliculas/4k-ultra-hd/page/3/",
            CinecalidadMYBisMarkup.buildArchivePageUrl("https://www.cinecalidad.my/peliculas/4k-ultra-hd/", 3),
        )
        assertEquals(
            "https://www.cinecalidad.my/?s=ne+zha",
            CinecalidadMYBisMarkup.buildSearchUrl("ne zha", 1),
        )
        assertEquals(
            "https://www.cinecalidad.my/page/2/?s=ne+zha",
            CinecalidadMYBisMarkup.buildSearchUrl("ne zha", 2),
        )
    }
}
