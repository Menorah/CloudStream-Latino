package com.menorah.repelis24czbis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Repelis24CZBisMarkupTest {

    @Test
    fun detectsChallengePagesAndParsesCardsAndPagination() {
        assertTrue(Repelis24CZBisMarkup.isChallengePage(200, "<html><title>One moment, please...</title></html>"))
        assertTrue(Repelis24CZBisMarkup.isChallengePage(403, "Forbidden"))
        assertFalse(Repelis24CZBisMarkup.isChallengePage(200, "<html><body><article class=\"dpu-archive-item\"></article></body></html>"))

        val html = """
            <html><body>
              <div class="dpu-archive-grid">
                <article id="post-455839" class="dpu-archive-item">
                  <a href="https://repelis24.cz/peliculas/spider-man-un-nuevo-dia/" class="dpu-archive-card" title="Spider-Man: Un nuevo día">
                    <div class="dpu-archive-thumb">
                      <img src="https://image.tmdb.org/t/p/w500/fBFjaDWfNslvrs6bJjknmG27wOS.jpg" alt="Spider-Man: Un nuevo día" class="dpu-archive-img">
                    </div>
                    <div class="dpu-archive-meta-box">
                      <h3 class="dpu-archive-c-title">Spider-Man: Un nuevo día</h3>
                      <div class="dpu-archive-meta-info"><span>2026</span><span class="dpu-card-rating-premium">★ 8 <small>IMDb</small></span></div>
                    </div>
                  </a>
                </article>
                <article id="post-87269" class="dpu-archive-item">
                  <a href="https://repelis24.cz/series/tu-amigo-y-vecino-spider-man/" class="dpu-archive-card">
                    <div class="dpu-archive-thumb"><img src="https://image.tmdb.org/t/p/w500/spidey.jpg"></div>
                    <div class="dpu-archive-meta-box"><h3 class="dpu-archive-c-title">Tu amigo y vecino Spider-Man</h3><div class="dpu-archive-meta-info"><span>2025</span></div></div>
                  </a>
                </article>
              </div>
              <div class="dpu-pagination-ultra"><ul class="page-numbers">
                <li><a class="next page-numbers" href="https://repelis24.cz/peliculas/page/2/">»</a></li>
              </ul></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, Repelis24CZBisMarkup.DEFAULT_MAIN_URL)
        val cards = Repelis24CZBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("Spider-Man: Un nuevo día", cards[0].title)
        assertFalse(cards[0].isSeries)
        assertEquals(2026, cards[0].year)
        assertEquals(8.0, cards[0].rating10!!, 0.01)
        assertTrue(cards[1].isSeries)
        assertTrue(Repelis24CZBisMarkup.parseHasNext(doc, 1))
    }

    @Test
    fun parsesMoviePlayerOptionsSeriesEpisodesAndAjaxResponses() {
        val movieHtml = """
            <html><body>
              <h1>Código: Venganza</h1>
              <img src="https://image.tmdb.org/t/p/w185/6PwLfI2qpRZIIwN63XnNMYAqj87.jpg">
              <div>2026 95 Min. IMDb 5,7/10</div>
              <a href="https://repelis24.cz/genero/accion/" rel="tag">Acción</a>
              <div class="dpu-hero-synopsis-short">Cole Reed descubre una conspiración.</div>
              <div id="playeroptions">
                <div class="dpu-nav-item trailer-item"><div id="player-option-trailer" class="dooplay_player_option" data-post="452707" data-type="movie" data-nume="trailer">Trailer</div></div>
                <div class="dpu-nav-item dpu-dropdown" data-lang="mx">
                  <div class="dpu-dropdown-content"><ul class="dpu_ajax_mode">
                    <li class="dooplay_player_option" data-type="movie" data-post="452707" data-nume="4"><span class="dpu-status-dot"></span><span>4.</span><span>Streamwish</span></li>
                    <li class="dooplay_player_option" data-type="movie" data-post="452707" data-nume="5"><span class="dpu-status-dot"></span><span>5.</span><span>Voe</span></li>
                  </ul></div>
                </div>
              </div>
              <div id="fakeplayer"><img class="cover" src="https://image.tmdb.org/t/p/original/bg.jpg"></div>
              <div id="source-player-trailer"><iframe src="https://www.youtube.com/embed/j10XcWybslg"></iframe></div>
              <div id="cast"><div class="person"><div class="name"><a>Jason Statham</a></div></div></div>
            </body></html>
        """.trimIndent()
        val mDoc = Jsoup.parse(movieHtml, "https://repelis24.cz/peliculas/codigo-venganza-2/")
        val detail = Repelis24CZBisMarkup.parseDetail(mDoc, "https://repelis24.cz/peliculas/codigo-venganza-2/")!!
        assertEquals("Código: Venganza", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w500/6PwLfI2qpRZIIwN63XnNMYAqj87.jpg", detail.posterUrl)
        assertEquals(95, detail.durationMinutes)
        assertEquals(5.7, detail.rating10!!, 0.01)
        assertEquals(listOf("Jason Statham"), detail.actors)
        assertEquals("https://www.youtube.com/embed/j10XcWybslg", detail.trailerUrl)

        val opts = Repelis24CZBisMarkup.parsePlayerOptions(mDoc)
        assertEquals(2, opts.size)
        assertEquals("4", opts[0].nume)
        assertEquals("Streamwish", opts[0].serverName)
        assertEquals("mx", opts[0].language)

        val serieHtml = """
            <html><body>
              <h1>Tu amigo y vecino Spider-Man</h1>
              <div id="dpu-season-1" class="dpu-season-pane">
                <div class="dpu-episode-card" data-episode="87269" data-season-number="1" data-episode-number="1">
                  <div class="dpu-ep-card-image"><img src="https://image.tmdb.org/t/p/w300/ep1.jpg"></div>
                  <div class="dpu-ep-card-content"><h3 class="dpu-ep-title">Fantasía asombrosa</h3></div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val sDetail = Repelis24CZBisMarkup.parseDetail(
            Jsoup.parse(serieHtml, "https://repelis24.cz/series/tu-amigo-y-vecino-spider-man/"),
            "https://repelis24.cz/series/tu-amigo-y-vecino-spider-man/",
        )!!
        assertTrue(sDetail.isSeries)
        assertEquals(1, sDetail.episodes.size)
        assertEquals("87269", sDetail.episodes[0].episodeId)
        val encodedEp = Repelis24CZBisMarkup.encodeEpisodeData("87269", sDetail.url)
        assertEquals("87269" to sDetail.url, Repelis24CZBisMarkup.decodeEpisodeData(encodedEp))

        assertEquals(
            "https://streamwish.to/e/55812cgpja3d",
            Repelis24CZBisMarkup.parseDooPlayerAjaxResponse("""{"embed_url":"https://streamwish.to/e/55812cgpja3d","type":"iframe"}"""),
        )
        assertNull(
            Repelis24CZBisMarkup.parseDooPlayerAjaxResponse("""{"embed_url":"https://directyp.org/4/abc"}"""),
        )
    }

    @Test
    fun unpacksVimeosEmbed() {
        val packed = """
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0={1:[{2:"3://4.5.6/7/8.9"}]};',10,10,'jws|sources|file|https|p9|vimeos|zip|hls2|master|m3u8'.split('|')))</script>
        """.trimIndent()
        val parsed = Repelis24CZVimeosParser.parse(packed, "https://vimeos.net/embed-xyz.html")
        assertEquals(listOf("https://p9.vimeos.zip/hls2/master.m3u8"), parsed.m3u8Urls)
    }
}
