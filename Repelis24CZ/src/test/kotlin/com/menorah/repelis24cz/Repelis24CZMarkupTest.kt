package com.menorah.repelis24cz

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Repelis24CZMarkupTest {

    @Test
    fun detectsChallengePagesAndParsesCardsAndPaginationWithQuery() {
        assertTrue(Repelis24CZMarkup.isChallengePage(200, "<html><title>One moment, please...</title></html>"))
        assertTrue(Repelis24CZMarkup.isChallengePage(403, "Forbidden"))
        assertFalse(Repelis24CZMarkup.isChallengePage(200, "<html><body><article class=\"dpu-archive-item\"></article></body></html>"))

        assertEquals(
            "https://repelis24.cz/tendencias/page/2/?get=movies",
            Repelis24CZMarkup.buildArchivePageUrl("https://repelis24.cz/tendencias/?get=movies", 2),
        )
        assertEquals(
            "https://repelis24.cz/ratings/page/3/?get=tv",
            Repelis24CZMarkup.buildArchivePageUrl("https://repelis24.cz/ratings/?get=tv", 3),
        )

        val html = """
            <html><body>
              <div class="dpu-archive-grid">
                <article id="post-455839" class="dpu-archive-item">
                  <a href="https://repelis24.cz/peliculas/spider-man-un-nuevo-dia/" class="dpu-archive-card" title="Spider-Man: Un nuevo día">
                    <div class="dpu-archive-thumb">
                      <img src="https://image.tmdb.org/t/p/w500/fBFjaDWfNslvrs6bJjknmG27wOS.jpg" alt="Spider-Man: Un nuevo día" class="dpu-archive-img">
                      <span class="dpu-card-languages">
                        <img src="https://repelis24.cz/wp-content/themes/RePelis24h/assets/flags/mx.png" alt="Español México" title="Español México">
                        <img src="https://repelis24.cz/wp-content/themes/RePelis24h/assets/flags/en.png" alt="Inglés" title="Inglés">
                      </span>
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
                <li><a class="next page-numbers" href="https://repelis24.cz/tendencias/page/2/?get=movies">»</a></li>
              </ul></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, Repelis24CZMarkup.DEFAULT_MAIN_URL)
        val cards = Repelis24CZMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("Spider-Man: Un nuevo día", cards[0].title)
        assertEquals(listOf("Latino", "Inglés"), cards[0].languages)
        assertFalse(cards[0].isSeries)
        assertEquals(2026, cards[0].year)
        assertEquals(8.0, cards[0].rating10!!, 0.01)
        assertTrue(cards[1].isSeries)
        assertTrue(Repelis24CZMarkup.parseHasNext(doc, 1))
    }

    @Test
    fun parsesRealMartySupremoHeroAndSuperMarioDropdownDataLang() {
        val movieHtml = """
            <html>
            <head>
              <meta property="og:description" content="Ambientada en el vibrante mundo del tenis de mesa de los años 50, Marty Supreme sigue a Marty Mauser en busca del respeto. […]">
            </head>
            <body>
              <div class="dpu-global-bg" style="background-image:url(https://image.tmdb.org/t/p/original/YEjuoz6.jpg);"></div>
              <div class="dpu-hero-ultra"><div class="dpu-hero-inner">
                <div class="dpu-hero-poster-side"><div class="dpu-poster-frame"><img alt="Marty Supremo" itemprop="image" src="https://image.tmdb.org/t/p/w185/guRmwb1r337m2tKBH9KcE3vOpGE.jpg"></div></div>
                <div class="dpu-hero-content">
                  <h1 class="dpu-hero-title" itemprop="name">Marty Supremo</h1>
                  <div class="dpu-hero-meta-grid">
                    <div class="meta-pill">2025</div>
                    <div class="meta-pill">150 Min.</div>
                    <div class="meta-pill rating-pill">TMDb 7,4/10 <span>(1.593 votos)</span></div>
                  </div>
                  <div class="dpu-hero-genres"><a href="https://repelis24.cz/genero/drama/" rel="tag">Drama</a><a href="https://repelis24.cz/genero/suspense/" rel="tag">Suspense</a></div>
                  <div class="dpu-hero-synopsis-short">Ambientada en el vibrante mundo del tenis de mesa de los años 50, Marty Supreme sigue a…</div>
                </div>
              </div></div>
              <div id="playeroptions">
                <div class="dpu-nav-item trailer-item"><div id="player-option-trailer" class="dooplay_player_option" data-post="419022" data-type="movie" data-nume="trailer">Trailer</div></div>
                <div class="dpu-nav-item dpu-dropdown" data-lang="mx">
                  <button class="dpu-dropdown-trigger"><img class="dpu-mini-flag" src="https://repelis24.cz/flags/mx.png"><span class="dpu-trigger-label">1. Vimeos</span></button>
                  <div class="dpu-dropdown-content"><ul class="dpu_ajax_mode">
                    <li class="dooplay_player_option" data-type="movie" data-post="419022" data-nume="1"><span class="dpu-server-index">1.</span><span>Vimeos</span></li>
                    <li class="dooplay_player_option" data-type="movie" data-post="419022" data-nume="4"><span class="dpu-server-index">4.</span><span>Streamwish</span></li>
                  </ul></div>
                </div>
                <div class="dpu-nav-item dpu-dropdown" data-lang="es">
                  <button class="dpu-dropdown-trigger"><img class="dpu-mini-flag" src="https://repelis24.cz/flags/es.png"><span class="dpu-trigger-label">Español</span></button>
                  <div class="dpu-dropdown-content"><ul class="dpu_ajax_mode">
                    <li class="dooplay_player_option" data-type="movie" data-post="419022" data-nume="6"><span class="dpu-server-index">6.</span><span>Vidhide</span></li>
                  </ul></div>
                </div>
                <div class="dpu-nav-item dpu-dropdown" data-lang="en">
                  <button class="dpu-dropdown-trigger"><img class="dpu-mini-flag" src="https://repelis24.cz/flags/en.png"><span class="dpu-trigger-label">Inglés</span></button>
                  <div class="dpu-dropdown-content"><ul class="dpu_ajax_mode">
                    <li class="dooplay_player_option" data-type="movie" data-post="419022" data-nume="8"><span class="dpu-server-index">8.</span><span>Filemoon</span></li>
                  </ul></div>
                </div>
              </div>
              <div id="source-player-trailer"><iframe src="https://www.youtube.com/embed/j10XcWybslg"></iframe></div>
              <div id="cast"><div class="person"><div class="name"><a>Timothée Chalamet</a></div></div></div>
              <div id="dpu_related_ultra_grid">
                <article class="dpu-related-card"><a href="https://repelis24.cz/peliculas/la-fiera/"><div class="dpu-rel-img"><img src="https://image.tmdb.org/t/p/w342/fiera.jpg" alt="La fiera"></div><div class="dpu-rel-details"><h3 class="dpu-rel-title">La fiera</h3><div class="dpu-rel-meta"><span class="dpu-rel-year">2026</span><span class="dpu-rel-rating">★ 6.1</span></div></div></a></article>
              </div>
            </body></html>
        """.trimIndent()
        val mDoc = Jsoup.parse(movieHtml, "https://repelis24.cz/peliculas/marty-supremo/")
        val detail = Repelis24CZMarkup.parseDetail(mDoc, "https://repelis24.cz/peliculas/marty-supremo/")!!
        assertEquals("Marty Supremo", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w500/guRmwb1r337m2tKBH9KcE3vOpGE.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/original/YEjuoz6.jpg", detail.backgroundUrl)
        assertEquals(2025, detail.year)
        assertEquals(150, detail.durationMinutes)
        assertEquals(7.4, detail.rating10!!, 0.01)
        assertTrue(detail.plot!!.endsWith("respeto."))
        assertEquals(listOf("Timothée Chalamet"), detail.actors)
        assertEquals(listOf("Drama", "Suspense"), detail.genres)
        assertEquals(1, detail.recommendations.size)
        assertEquals("La fiera", detail.recommendations[0].title)
        assertEquals(6.1, detail.recommendations[0].rating10!!, 0.01)

        val opts = Repelis24CZMarkup.parsePlayerOptions(mDoc)
        assertEquals(4, opts.size)
        assertEquals("Latino", opts[0].language)
        assertEquals("Vimeos", opts[0].serverName)
        assertEquals("Latino", opts[1].language)
        assertEquals("Streamwish", opts[1].serverName)
        assertEquals("Castellano", opts[2].language)
        assertEquals("Vidhide", opts[2].serverName)
        assertEquals("Inglés", opts[3].language)
        assertEquals("Filemoon", opts[3].serverName)

        val serieHtml = """
            <html><body>
              <h1>The Boys</h1>
              <div id="dpu-season-1" class="dpu-season-pane">
                <div class="dpu-episode-card" data-episode="35293" data-season-number="1" data-episode-number="1" aria-label="Reproducir temporada 1, episodio 1: El Nombre del Juego">
                  <div class="dpu-ep-card-image"><img src="https://image.tmdb.org/t/p/w300/ep1.jpg"></div>
                  <div class="dpu-ep-card-content"><h3 class="dpu-ep-title">El Nombre del Juego</h3><span class="dpu-ep-date">Jul. 25, 2019</span></div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val sDetail = Repelis24CZMarkup.parseDetail(
            Jsoup.parse(serieHtml, "https://repelis24.cz/series/the-boys/"),
            "https://repelis24.cz/series/the-boys/",
        )!!
        assertTrue(sDetail.isSeries)
        assertEquals(1, sDetail.episodes.size)
        assertEquals("35293", sDetail.episodes[0].episodeId)
        assertEquals("El Nombre del Juego", sDetail.episodes[0].title)
        assertEquals("Jul. 25, 2019", sDetail.episodes[0].airDate)
        val encodedEp = Repelis24CZMarkup.encodeEpisodeData("35293", sDetail.url)
        assertEquals("35293" to sDetail.url, Repelis24CZMarkup.decodeEpisodeData(encodedEp))

        assertEquals(
            "https://streamwish.to/e/55812cgpja3d",
            Repelis24CZMarkup.parseDooPlayerAjaxResponse("""{"embed_url":"https://streamwish.to/e/55812cgpja3d","type":"iframe"}"""),
        )
        assertNull(
            Repelis24CZMarkup.parseDooPlayerAjaxResponse("""{"embed_url":"https://directyp.org/4/abc"}"""),
        )
    }

    @Test
    fun unpacksVimeosEmbed() {
        val packed = """
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0={1:[{2:"3://4.5.6/7/8.9"}]};',10,10,'jws|sources|file|https|p9|vimeos|zip|hls2|master|m3u8'.split('|')))</script>
        """.trimIndent()
        val parsed = Repelis24CZVimeosParser.parse(packed, "https://vimeos.net/embed-xyz.html")
        assertEquals(listOf("https://p9.vimeos.zip/hls2/master.m3u8"), parsed.m3u8Urls)

        val srvCandidates = Repelis24CZVimeosParser.buildSrvFallbackCandidates(
            "https://p8.vimeos.zip/hls2/01/00015/xyz_,n,h,.urlset/master.m3u8?t=abc&srv=s1",
        )
        assertTrue(srvCandidates.contains("https://s1.vimeos.zip/hls2/01/00015/xyz_,n,h,.urlset/master.m3u8?t=abc&srv=s1"))
        assertTrue(srvCandidates.contains("https://s1.vimeos.net/hls2/01/00015/xyz_,n,h,.urlset/master.m3u8?t=abc&srv=s1"))
    }
}
