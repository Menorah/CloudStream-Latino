package com.menorah.cinecalidadambis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinecalidadAMBisMarkupTest {

    @Test
    fun parsesFeaturedWidgetAndMovieArchiveCardsAndPagination() {
        val html = """
            <html><body>
              <aside id="dtw_content_featured-3">
                <ul class="destacados-widget">
                  <li><div class="upw-image"><a href="https://www.cinecalidad.am/ver-pelicula/enola-holmes-2-online-gratis-en-cinecalidad/" title="Enola Holmes 2"><img data-src="https://image.tmdb.org/t/p/w185//eC7d7caUKi1dJuRdWPK8b8Hkpz5.jpg"></a></div></li>
                </ul>
              </aside>
              <div id="archive-content">
                <article class="item movies">
                  <div class="poster"><img data-src="https://image.tmdb.org/t/p/w300//madden.jpg" alt="Madden"></div>
                  <div class="rating">8.2</div>
                  <h3 class="hover_caption_caption"><a href="https://www.cinecalidad.am/ver-pelicula/madden/">Madden</a><p>2026</p></h3>
                  <div class="in_title">Madden</div>
                </article>
                <article class="item movies">
                  <div class="selt">Serie</div>
                  <img src="https://image.tmdb.org/t/p/w300/fbi.jpg" alt="F.B.I.">
                  <h3 class="hover_caption_caption"><a href="https://www.cinecalidad.am/ver-serie/f-b-i-online-gratis/">F.B.I.</a></h3>
                  <div class="in_title">F.B.I.</div>
                </article>
              </div>
              <div class="pagination"><a href="https://www.cinecalidad.am/page/2/">2</a></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, CinecalidadAMBisMarkup.DEFAULT_MAIN_URL)

        val featured = CinecalidadAMBisMarkup.parseFeaturedCards(doc)
        assertEquals(1, featured.size)
        assertEquals("Enola Holmes 2", featured[0].title)
        assertEquals("https://image.tmdb.org/t/p/w500/eC7d7caUKi1dJuRdWPK8b8Hkpz5.jpg", featured[0].posterUrl)

        val cards = CinecalidadAMBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("https://image.tmdb.org/t/p/w300/madden.jpg", cards[0].posterUrl)
        assertFalse(cards[0].isSeries)
        assertTrue(cards[1].isSeries)
        assertTrue(CinecalidadAMBisMarkup.parseHasNext(doc, 1))
    }

    @Test
    fun parsesRealMaddenZopassPaneLanguageAndDownloads() {
        val html = """
            <html><body>
              <div class="single_left">
                <h1>Madden</h1>
                <div class="single-calidad"><strong>Calidad:</strong><span>Dual 1080p</span></div>
                <table><tr><td><img data-src="https://image.tmdb.org/t/p/w300/madden.jpg"></td><td>
                  <p>Fecha: 2026</p>
                  <p>John Madden deja el campo de juego para construir el famoso videojuego de fútbol americano.</p>
                </td></tr></table>
                <a href="https://www.cinecalidad.am/reparto/nicolas-cage/">Nicolas Cage</a>
                <a href="https://www.cinecalidad.am/genero-de-la-pelicula/drama/">Drama</a>
              </div>
              <div id="panel_online">
                <div class="pane_descripcion">Audio latino</div>
                <ul id="playeroptionsul">
                  <li class="dooplay_player_option" data-option="/zopass/?zopass=aHR0cHM6Ly92aW1lb3MubmV0L2VtYmVkLWpndHR5dnJydzdzMi5odG1s&sn=VmltZW9z">Vimeos</li>
                  <li class="dooplay_player_option" data-option="/zopass/?zopass=aHR0cHM6Ly92aWRlb2FwcC56aXAvZS9tb3ZpZS8xMjM0NTY=&sn=VmlkZW9hcHA=">Videoapp</li>
                  <li class="dooplay_player_option dooplay_player_option_trailer" data-option="/zopass/?zopass=aHR0cHM6Ly93d3cueW91dHViZS5jb20vZW1iZWQvTE1sQ042X3ZVdnM=&sn=VHJhaWxlcg==">Ver trailer</li>
                </ul>
              </div>
              <div id="panel_descarga">
                <a href="https://www.cinecalidad.am/ver-pelicula/madden/?download=OU9wNno3"><li>Mediafire</li></a>
                <a href="magnet:?xt=urn:btih:aabd81add1929505e"><li>Torrent</li></a>
              </div>
              <div class="yarpp-related"><ul class="related-posts">
                <li><a href="https://www.cinecalidad.am/ver-pelicula/draft-day/"><img data-src="https://image.tmdb.org/t/p/w185/dd.jpg">Draft Day online</a></li>
              </ul></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://www.cinecalidad.am/ver-pelicula/madden/")
        val detail = CinecalidadAMBisMarkup.parseDetail(doc, "https://www.cinecalidad.am/ver-pelicula/madden/")!!
        assertEquals(2026, detail.year)
        assertEquals("https://www.youtube.com/embed/LMlCN6_vUvs", detail.trailerUrl)
        assertEquals(listOf("Nicolas Cage"), detail.actors)
        assertEquals(1, detail.recommendations.size)
        assertEquals("Draft Day", detail.recommendations[0].title)

        val (opts, trailer) = CinecalidadAMBisMarkup.parsePlayerOptions(doc)
        assertEquals("https://www.youtube.com/embed/LMlCN6_vUvs", trailer)
        assertEquals(2, opts.size)
        assertEquals("https://vimeos.net/embed-jgttyvrrw7s2.html", opts[0].url)
        assertEquals("Latino", opts[0].language)
        assertEquals("Dual 1080p", opts[0].quality)

        val dls = CinecalidadAMBisMarkup.parseDownloadOptions(doc)
        assertEquals(2, dls.size)
        assertFalse(dls[0].isMagnet)
        assertTrue(dls[1].isMagnet)
    }

    @Test
    fun parsesSeriesWithRepartoDeSerieTrailerScriptAndSeasons() {
        val serieHtml = """
            <html><body>
              <div class="single_left">
                <h1>F.B.I.</h1>
                <table><tr><td><img src="https://image.tmdb.org/t/p/w300/fbi.jpg"></td><td><p>Serie policial de acción en la oficina del FBI de Nueva York.</p></td></tr></table>
                <a href="https://www.cinecalidad.am/reparto-de-serie/missy-peregrym/">Missy Peregrym</a>
                <iframe id="trailerazo" src="saved_resource.html"></iframe>
                <script>document.querySelector("#show_trailer").addEventListener("click",e=>{e.preventDefault();document.querySelector("#trailerazo").src='https://www.youtube.com/embed/abc123XYZ';});</script>
              </div>
              <div id="seasons">
                <div id="jstab" data-tab="8">
                  <ul class="episodios">
                    <li>
                      <div class="imagen"><img data-src="https://image.tmdb.org/t/p/w300/ep22.jpg"></div>
                      <div class="numerando">S8-E22</div>
                      <div class="episodiotitle"><a href="https://www.cinecalidad.am/ver-serie/f-b-i-8x22/">Final</a></div>
                    </li>
                  </ul>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val detail = CinecalidadAMBisMarkup.parseDetail(
            Jsoup.parse(serieHtml, "https://www.cinecalidad.am/ver-serie/f-b-i/"),
            "https://www.cinecalidad.am/ver-serie/f-b-i/",
        )!!
        assertTrue(detail.isSeries)
        assertEquals(listOf("Missy Peregrym"), detail.actors)
        assertEquals("https://www.youtube.com/embed/abc123XYZ", detail.trailerUrl)
        assertEquals(1, detail.episodes.size)
        assertEquals(8, detail.episodes[0].season)
        assertEquals(22, detail.episodes[0].episode)
    }

    @Test
    fun unpacksVimeosMasterPlaylistAndIgnoresPlaceholderCaptions() {
        val packed = """
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0={1:[{2:"3://4.5.6/7/8.9"}],a:[{2:"/b/c.d",e:"f"},{2:"/g/h.i",e:"j",k:"l"}]};',22,22,'jw|sources|file|https|cdn|vimeos|zip|hls|master|m3u8|tracks|srt|empty|srt|kind|captions|subs|es|vtt|captions|label|Español'.split('|')))</script>
        """.trimIndent()
        val parsed = CinecalidadAMVimeosParser.parse(packed, "https://vimeos.net/embed-xyz.html")
        assertEquals(listOf("https://cdn.vimeos.zip/hls/master.m3u8"), parsed.m3u8Urls)
        assertEquals(listOf("Español" to "https://vimeos.net/subs/es.vtt"), parsed.subtitles)
    }
}
