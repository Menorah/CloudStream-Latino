package com.menorah.cinecalidadambis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinecalidadAMBisMarkupTest {

    @Test
    fun parsesCardsAndSkipsNaadbPromos() {
        val html = """
            <html><body>
              <div id="archive-content">
                <article class="item movies naadb">
                  <div class="poster custom">
                    <img src="https://adsanalytics.org/images/promo.jpg" alt="promo">
                    <h3 class="hover_caption_caption"><a href="#aHR0cHM6Ly9hZHNhbmFseXRpY3Mub3Jn">
                      <div class="home_post_content"><div class="in_title">Netflix Premium</div></div>
                    </a></h3>
                  </div>
                </article>
                <article id="post-2881023" class="item movies">
                  <div class="poster custom">
                    <div class="selt ">Series</div>
                    <div class="last-num"><div class="last-s">Temporada 1</div><div class="last-ep">Episodio 14</div></div>
                    <img src="https://image.tmdb.org/t/p/w342//gcOV7JFbJpNqH8mzSuL8jG1WVy4.jpg" alt="Batman: La Serie Animada">
                    <h3 class="hover_caption_caption"><a href="https://www.cinecalidad.am/ver-serie/batman-la-serie-animada/">
                      <div class="home_post_content"><div class="in_title">Batman: La Serie Animada</div><p>1992</p></div>
                    </a></h3>
                  </div>
                </article>
                <article id="post-2881022" class="item movies">
                  <div class="poster custom">
                    <div class="selt ">Película</div>
                    <img src="data:image/png;base64,iVBORw0KGgo=" data-src="https://image.tmdb.org/t/p/w342//i5z5B7ctBc0xicA1WM4dl0aGSpB.jpg" alt="007: Otro día para morir">
                    <h3 class="hover_caption_caption"><a href="https://www.cinecalidad.am/ver-pelicula/007-otro-dia-para-morir/">
                      <div class="home_post_content"><div class="in_title">007: Otro día para morir</div><p>2002</p></div>
                    </a></h3>
                  </div>
                </article>
              </div>
              <div class="pagination"><a class="next page-numbers" href="https://www.cinecalidad.am/page/2/">Siguiente</a></div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, CinecalidadAMBisMarkup.DEFAULT_MAIN_URL)
        val cards = CinecalidadAMBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("Batman: La Serie Animada", cards[0].title)
        assertTrue(cards[0].isSeries)
        assertEquals("https://image.tmdb.org/t/p/w342/gcOV7JFbJpNqH8mzSuL8jG1WVy4.jpg", cards[0].posterUrl)
        assertEquals("007: Otro día para morir", cards[1].title)
        assertFalse(cards[1].isSeries)
        assertEquals("https://image.tmdb.org/t/p/w342/i5z5B7ctBc0xicA1WM4dl0aGSpB.jpg", cards[1].posterUrl)
        assertTrue(CinecalidadAMBisMarkup.parseHasNext(doc, 1))
    }

    @Test
    fun parsesMovieDetailSeriesEpisodesAndZopassOptions() {
        val html = """
            <html><body>
              <div class="single_left">
                <h1>The Batman</h1>
                <table><tr>
                  <td><img data-src="https://image.tmdb.org/t/p/w342//et6N6ratRz6Yftl87w4EfkH3IJE.jpg" src="data:image/png;base64,AAA="></td>
                  <td>
                    <p>Bruce Wayne patrulla Gotham City mientras enfrenta nuevos villanos cada noche en esta serie animada.</p>
                    <p><span><strong>Títulos:</strong> The Batman</span></p>
                    <span><strong>TMDB</strong><div id="star-rating-box"></div><b>7.5</b>/10</span>
                    <p><span><strong>Género:</strong> <a href="https://www.cinecalidad.am/genero-de-la-pelicula/animacion/">Animación</a>, <a href="https://www.cinecalidad.am/genero-de-la-pelicula/accion/">Acción</a></span></p>
                    <p><span><strong>Elenco:</strong> <a href="https://www.cinecalidad.am/reparto/rino-romano/">Rino Romano</a></span></p>
                  </td>
                </tr></table>
                <ul id="playeroptionsul">
                  <li class="dooplay_player_option" data-option="https://www.cinecalidad.am/player/?zopass=aHR0cHM6Ly92aW1lb3MubmV0L2VtYmVkLTJqbXptcHNzdnF4YS5odG1s">Vimeos</li>
                  <li class="dooplay_player_option" data-option="https://videoapp.zip/e/tv/2098/1/1">Videoapp</li>
                  <li id="player-option-trailer" class="dooplay_player_option dooplay_player_option_trailer" data-option="https://www.youtube.com/embed/LMlCN6_vUvs">Ver trailer</li>
                </ul>
                <div id="episodes"><div id="serie_contenido"><div id="seasons">
                  <div id="jstab" data-tab="1" class="se-c">
                    <h2><b>Temporada 1</b></h2>
                    <ul class="episodios">
                      <li>
                        <div class="imagen"><img data-src="https://image.tmdb.org/t/p/w185/ep1.jpg" src=""></div>
                        <div class="numerando">S1-E1</div>
                        <div class="episodiotitle"><a href="https://www.cinecalidad.am/ver-el-episodio/the-batman-1x1/">Un murciélago en el campanario</a></div>
                      </li>
                    </ul>
                  </div>
                  <div id="jstab" data-tab="2" class="se-c">
                    <h2><b>Temporada 2</b></h2>
                    <ul class="episodios">
                      <li>
                        <div class="imagen"><img src="https://image.tmdb.org/t/p/w185/ep21.jpg"></div>
                        <div class="numerando">S2-E3</div>
                        <div class="episodiotitle"><a href="https://www.cinecalidad.am/ver-el-episodio/the-batman-2x3/">Acertijo</a></div>
                      </li>
                    </ul>
                  </div>
                </div></div></div>
              </div>
            </body></html>
        """.trimIndent()

        val url = "https://www.cinecalidad.am/ver-serie/the-batman/"
        val doc = Jsoup.parse(html, url)
        val detail = CinecalidadAMBisMarkup.parseDetail(doc, url)
        assertNotNull(detail)
        detail!!
        assertTrue(detail.isSeries)
        assertEquals(7.5, detail.rating10!!, 0.01)
        assertEquals(listOf("Animación", "Acción"), detail.genres)
        assertEquals(listOf("Rino Romano"), detail.actors)
        assertEquals("https://www.youtube.com/embed/LMlCN6_vUvs", detail.trailerUrl)
        assertEquals(2, detail.episodes.size)
        assertEquals(1, detail.episodes[0].season)
        assertEquals(1, detail.episodes[0].episode)
        assertEquals(2, detail.episodes[1].season)
        assertEquals(3, detail.episodes[1].episode)

        val (opts, trailer) = CinecalidadAMBisMarkup.parsePlayerOptions(doc)
        assertEquals("https://www.youtube.com/embed/LMlCN6_vUvs", trailer)
        assertEquals(2, opts.size)
        assertEquals("https://vimeos.net/embed-2jmzmpssvqxa.html", opts[0].url)
        assertEquals("https://videoapp.zip/e/tv/2098/1/1", opts[1].url)
    }

    @Test
    fun unpacksVimeosPackerAndParsesMasterM3u8AndSubtitles() {
        val packedHtml = """
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('1 2={3:[{4:"5://6.7.8/9/a.b"}],c:[{4:"/d/e.f",g:"h",i:"j"},{4:"/k/l.m",g:"n",i:"j"}]};',24,24,'|var|jws|sources|file|https|p9|vimeos|zip|hls2|master|m3u8|tracks|srt|empty|srt|label|None|kind|captions|subs|es|vtt|Español'.split('|')))</script>
        """.trimIndent()
        val parsed = CinecalidadAMVimeosParser.parse(packedHtml, "https://vimeos.net/embed-abc.html")
        assertEquals(listOf("https://p9.vimeos.zip/hls2/master.m3u8"), parsed.m3u8Urls)
        assertEquals(listOf("Español" to "https://vimeos.net/subs/es.vtt"), parsed.subtitles)
    }
}
