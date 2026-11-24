package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeliCineHDMarkupTest {
    private fun doc(html: String, base: String = "https://pelicinehd.com/peliculas/") =
        Jsoup.parse(html, base)

    // ------------------------------------------------------------------ cards

    @Test
    fun catalogCardsUseTheRealThemeMarkup() {
        val page = doc(
            """
            <ul class="post-lst">
              <li><article class="post dfx fcl movies">
                <header class="entry-header"><h2 class="entry-title">Umami</h2></header>
                <div class="post-thumbnail or-1">
                  <figure><img loading="lazy" src="//image.tmdb.org/t/p/w500/h40GMBkxXmU0aar8IAv9MmM3ZDB.jpg" alt="Image Umami"></figure>
                  <span class="year">2025</span>
                </div>
                <a href="https://pelicinehd.com/movies/umami/" class="lnk-blk"></a>
              </article></li>
              <li><article class="post dfx fcl series">
                <h2 class="entry-title">Adiós, Tierra</h2>
                <div class="post-thumbnail"><img data-src="https://pelicinehd.com/wp-content/uploads/2024/05/adios.jpg"></div>
                <span class="year">2024</span>
                <a href="https://pelicinehd.com/series/adios-tierra/" class="lnk-blk"></a>
              </article></li>
              <li><article class="post dfx fcl movies">
                <h2 class="entry-title">Anuncio</h2>
                <a href="https://otro-sitio.example/movies/fake/"></a>
              </article></li>
            </ul>
            """.trimIndent()
        )

        val cards = PeliCineHDMarkup.cards(page)
        assertEquals(2, cards.size)
        assertEquals("Umami", cards[0].title)
        assertEquals("https://pelicinehd.com/movies/umami/", cards[0].url)
        assertEquals("https://image.tmdb.org/t/p/w500/h40GMBkxXmU0aar8IAv9MmM3ZDB.jpg", cards[0].poster)
        assertEquals(2025, cards[0].year)
        assertTrue(cards[0].movie)
        assertFalse(cards[1].movie)
        assertEquals("https://pelicinehd.com/wp-content/uploads/2024/05/adios.jpg", cards[1].poster)
    }

    @Test
    fun paginationReadsTheThemeLinks() {
        val first = doc(
            """
            <nav class="navigation pagination"><div class="nav-links">
            <a class="page-link current" href="https://pelicinehd.com/peliculas/">1</a>
            <a class="page-link" href="https://pelicinehd.com/peliculas/page/2/">2</a>
            <a href="https://pelicinehd.com/peliculas/page/2/">SIGUIENTE</a>
            </div></nav>
            """.trimIndent(),
            "https://pelicinehd.com/peliculas/"
        )
        assertTrue(PeliCineHDMarkup.hasNext(first, 1))

        val last = doc(
            """
            <nav class="navigation pagination"><div class="nav-links">
            <a class="page-link" href="https://pelicinehd.com/peliculas/page/72/">72</a>
            <a class="page-link current" href="https://pelicinehd.com/peliculas/page/73/">73</a>
            </div></nav>
            """.trimIndent(),
            "https://pelicinehd.com/peliculas/page/73/"
        )
        assertFalse(PeliCineHDMarkup.hasNext(last, 73))
        assertFalse(PeliCineHDMarkup.hasNext(doc("<html></html>"), 1))
    }

    // ---------------------------------------------------------------- details

    @Test
    fun movieDetailIsParsedFromTheArticle() {
        val page = doc(
            """
            <article class="post single">
              <div class="dfxb alg-cr">
                <div class="post-thumbnail"><figure><img src="//image.tmdb.org/t/p/w185/9qlq.jpg"></figure></div>
                <aside class="fg1">
                  <header class="entry-header">
                    <h1 class="entry-title">28 años después: El templo de los huesos</h1>
                    <div class="entry-meta">
                      <span class="genres"><a href="/category/ciencia-ficcion/">Ciencia ficción</a>, <a href="/category/terror/">Terror</a></span>
                      <span class="duration fa-clock far">1h 49m</span>
                      <span class="year fa-calendar far">2026</span>
                    </div>
                  </header>
                  <div class="description"><p>El Dr. Kelson se encuentra en una nueva relación.</p></div>
                  <ul class="cast-lst dfx fwp">
                    <li><span>Director</span><p><a href="/director/nia/">Nia DaCosta</a></p></li>
                    <li><span>Actores</span><p><a href="/cast/alfie/">Alfie Williams</a>, <a href="/cast/cillian/">Cillian Murphy</a></p></li>
                  </ul>
                </aside>
              </div>
              <footer><div class="vote-cn"><span class="vote fa-star"><span class="num">6.7</span><span>TMDB</span></span></div></footer>
            </article>
            """.trimIndent(),
            "https://pelicinehd.com/movies/28-anos-despues-el-templo-de-los-huesos/"
        )

        assertEquals("28 años después: El templo de los huesos", PeliCineHDMarkup.title(page))
        assertEquals("https://image.tmdb.org/t/p/w185/9qlq.jpg", PeliCineHDMarkup.poster(page))
        assertEquals("El Dr. Kelson se encuentra en una nueva relación.", PeliCineHDMarkup.plot(page))
        assertEquals(2026, PeliCineHDMarkup.year(page))
        assertEquals(listOf("Ciencia ficción", "Terror"), PeliCineHDMarkup.genres(page))
        assertEquals(listOf("Alfie Williams", "Cillian Murphy"), PeliCineHDMarkup.actors(page))
        assertEquals(6.7, PeliCineHDMarkup.score(page)!!, 0.001)
        assertEquals(109, PeliCineHDMarkup.durationMinutes(page))
        assertFalse(PeliCineHDMarkup.isSeries(page))
    }

    @Test
    fun durationHandlesHoursAndMinutesOnly() {
        fun minutes(text: String) = PeliCineHDMarkup.durationMinutes(
            doc("""<article class="post single"><span class="duration">$text</span></article>""")
        )
        assertEquals(109, minutes("1h 49m"))
        assertEquals(120, minutes("2h"))
        assertEquals(7, minutes("7 min."))
        assertNull(minutes("—"))
    }

    // ---------------------------------------------------------------- players

    @Test
    fun playerOptionsPairIframesWithTheirServerLabel() {
        val page = doc(
            """
            <section class="section player episode">
              <aside class="video-player aa-cn" id="aa-options">
                <div id="options-0" class="video aa-tb hdd on">
                  <iframe src="https://pelicinehd.com/?trembed=0&#038;trid=18569&trtype=1" data-src="https://pelicinehd.com/?trembed=0&#038;trid=18569&trtype=1"></iframe>
                </div>
                <div id="options-1" class="video aa-tb hdd">
                  <iframe data-src="https://pelicinehd.com/?trembed=1&#038;trid=18569&trtype=1"></iframe>
                </div>
              </aside>
              <aside class="video-options">
                <ul class="aa-tbs aa-tbs-video" data-tbs="aa-options">
                  <li><a class="btn on" href="#options-0">OPCIÓN <span>1</span> <span class="server">Minochinos -LATÍNO HD</span></a></li>
                  <li><a class="btn" href="#options-1">OPCIÓN <span>2</span> <span class="server">Voe -CASTELLANO HD</span></a></li>
                  <li><a href="#options-2">Anuncio</a></li>
                </ul>
              </aside>
            </section>
            """.trimIndent(),
            "https://pelicinehd.com/movies/28-anos-despues/"
        )

        val players = PeliCineHDMarkup.players(page)
        assertEquals(2, players.size)
        assertEquals("https://pelicinehd.com/?trembed=0&trid=18569&trtype=1", players[0].url)
        assertEquals("Latino", players[0].language)
        assertEquals("Castellano", players[1].language)
    }

    @Test
    fun languagesAreReadFromTheServerLabels() {
        assertEquals("Latino", PeliCineHDMarkup.language("Minochinos -LATÍNO HD"))
        assertEquals("Castellano", PeliCineHDMarkup.language("Voe -CASTELLANO HD"))
        assertEquals("Subtitulado", PeliCineHDMarkup.language("Filemoon -SUBTITULADO"))
        assertEquals("", PeliCineHDMarkup.language(null))
    }

    @Test
    fun playersFromForeignHostsAreIgnored() {
        val page = doc(
            """
            <div id="aa-options">
              <div id="options-0"><iframe src="https://evil.example/watch"></iframe></div>
              <div id="options-1"><iframe src="https://pelicinehd.com/?trembed=0&trid=1&trtype=1"></iframe></div>
            </div>
            """.trimIndent(),
            "https://pelicinehd.com/movies/x/"
        )
        assertEquals(1, PeliCineHDMarkup.players(page).size)
    }

    // -------------------------------------------------------- seasons/episodes

    private val seriesHtml = """
        <article class="post single series">
          <h1 class="entry-title">Adiós, Tierra</h1>
          <span class="year fa-calendar far">2024</span>
          <div class="post-thumbnail"><figure><img src="https://pelicinehd.com/wp-content/uploads/2024/05/poster.jpg"></figure></div>
          <div class="description"><p>Un asteroide se precipita hacia la Tierra.</p></div>
        </article>
        <section class="section episodes">
          <header class="section-header">
            <div class="aa-drp choose-season">
              <button class="btn lnk npd aa-lnk"><span>elegir temporada</span>Temporada<dt class="n_s" style="display: inline">1</dt></button>
              <ul class="aa-cnt sub-menu">
                <li class="sel-temp"><a data-post="9486" data-season="1" href="javascript:void(0)">Season 1</a></li>
                <li><a data-post="9486" data-season="2" href="javascript:void(0)">Season 2</a></li>
              </ul>
            </div>
          </header>
          <ul id="episode_by_temp" class="post-lst">
            <li><article class="post dfx fcl episodes fa-play-circle lg">
              <div class="post-thumbnail"><figure><img src="https://pelicinehd.com/uploads/ep1.jpg"></figure></div>
              <header class="entry-header">
                <span class="num-epi">1x1</span>
                <h2 class="entry-title">Adiós, Tierra 1x1</h2>
              </header>
              <a href="https://pelicinehd.com/episode/adios-tierra-1x1/" class="lnk-blk"></a>
            </article></li>
            <li><article class="post dfx fcl episodes">
              <span class="num-epi">1x2</span>
              <h2 class="entry-title">Adiós, Tierra 1x2</h2>
              <a href="https://pelicinehd.com/episode/adios-tierra-1x2/" class="lnk-blk"></a>
            </article></li>
          </ul>
        </section>
    """.trimIndent()

    @Test
    fun seriesDetailExposesSeasonsAndRenderedEpisodes() {
        val page = doc(seriesHtml, "https://pelicinehd.com/series/adios-tierra/")

        assertTrue(PeliCineHDMarkup.isSeries(page))
        assertEquals("Adiós, Tierra", PeliCineHDMarkup.title(page))
        assertEquals(9486, PeliCineHDMarkup.seasonsPostId(page))
        assertEquals(listOf(1, 2), PeliCineHDMarkup.seasons(page).map { it.number })
        assertEquals(9486, PeliCineHDMarkup.seasons(page).first().post)

        val episodes = PeliCineHDMarkup.episodes(page, 1)
        assertEquals(2, episodes.size)
        assertEquals("Adiós, Tierra 1x1", episodes[0].title)
        assertEquals(1, episodes[0].season)
        assertEquals(1, episodes[0].number)
        assertEquals("https://pelicinehd.com/episode/adios-tierra-1x1/", episodes[0].url)
        assertEquals("https://pelicinehd.com/uploads/ep1.jpg", episodes[0].poster)
        assertEquals(2, episodes[1].number)
    }

    @Test
    fun seasonAjaxHtmlIsParsedWithTheRequestedSeason() {
        val html = """
            <li><article class="post dfx fcl episodes">
              <span class="num-epi">2x3</span>
              <h2 class="entry-title">Adiós, Tierra 2x3</h2>
              <a href="https://pelicinehd.com/episode/adios-tierra-2x3/" class="lnk-blk"></a>
            </article></li>
        """.trimIndent()
        val episodes = PeliCineHDMarkup.parseSeasonHtml(
            html, "https://pelicinehd.com/series/adios-tierra/", 2
        )
        assertEquals(1, episodes.size)
        assertEquals(2, episodes[0].season)
        assertEquals(3, episodes[0].number)
        assertEquals("Adiós, Tierra 2x3", episodes[0].title)
        assertTrue(PeliCineHDMarkup.parseSeasonHtml("<li></li>", "https://pelicinehd.com/", 1).isEmpty())
    }

    @Test
    fun episodeNumbersAreStrict() {
        assertEquals(1 to 4, PeliCineHDMarkup.parseEpisodeNumber("1x4"))
        assertEquals(10 to 12, PeliCineHDMarkup.parseEpisodeNumber(" 10x12 "))
        assertNull(PeliCineHDMarkup.parseEpisodeNumber("Temporada 1"))
        assertNull(PeliCineHDMarkup.parseEpisodeNumber(null))
    }

    // -------------------------------------------------------------------- urls

    @Test
    fun catalogAndSearchUrlsMatchTheTheme() {
        assertEquals("https://pelicinehd.com/peliculas/", PeliCineHDMarkup.catalogUrl("peliculas", 1))
        assertEquals("https://pelicinehd.com/peliculas/page/2/", PeliCineHDMarkup.catalogUrl("peliculas", 2))
        assertEquals(
            "https://pelicinehd.com/category/ciencia-ficcion/page/3/",
            PeliCineHDMarkup.catalogUrl("category/ciencia-ficcion", 3)
        )
        assertEquals("https://pelicinehd.com/?s=la+casa", PeliCineHDMarkup.searchUrl("la casa"))
        assertEquals("https://pelicinehd.com/page/2/?s=la+casa", PeliCineHDMarkup.searchUrl("la casa", 2))
    }

    @Test
    fun siteGuardRejectsForeignHostsAndSchemes() {
        assertTrue(PeliCineHDMarkup.siteUrl("https://pelicinehd.com/movies/x/"))
        assertTrue(PeliCineHDMarkup.siteUrl("https://www.pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://pelicinehd.com.evil.test/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("http://pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://pelicinehd.com@evil.test/"))
        assertFalse(PeliCineHDMarkup.siteUrl(null))
        assertNull(PeliCineHDMarkup.absolute("javascript:void(0)"))
        assertEquals("https://pelicinehd.com/x/", PeliCineHDMarkup.absolute("/x/"))
    }

    @Test
    fun challengePagesAreDetected() {
        assertTrue(
            PeliCineHDMarkup.isChallenge(
                "<html><head><title>Just a moment...</title></head><body>cf-chl</body></html>"
            )
        )
        assertFalse(PeliCineHDMarkup.isChallenge("<html><body><h1>PeliCineHD</h1></body></html>"))
        assertFalse(PeliCineHDMarkup.isChallenge(null))
    }

    @Test
    fun ajaxNonceIsReadFromTheThemeSettings() {
        val html = """var torofilm_Public = {"url":"https://pelicinehd.com/wp-admin/admin-ajax.php","nonce":"91ecde02e3"};"""
        assertEquals("91ecde02e3", PeliCineHDMarkup.ajaxNonce(html))
        assertNull(PeliCineHDMarkup.ajaxNonce("<html></html>"))
    }
}
