package com.menorah.veraniv2

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerAniV2MarkupTest {

    @Test
    fun `buildArchivePageUrl inserts page segment before query string`() {
        assertEquals(
            "https://verani.me/animes/",
            VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/", 1),
        )
        assertEquals(
            "https://verani.me/animes/page/2/",
            VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/", 2),
        )
        assertEquals(
            "https://verani.me/animes/page/3/?idioma=Espa%C3%B1ol%20Latino",
            VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino", 3),
        )
        assertEquals(
            "https://verani.me/animes/accion/page/2/",
            VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/accion/", 2),
        )
    }

    @Test
    fun `buildSearchUrl encodes query and appends paged only after page 1`() {
        assertEquals(
            "https://verani.me/?s=naruto+shippuden",
            VerAniV2Markup.buildSearchUrl("naruto shippuden", 1),
        )
        assertEquals(
            "https://verani.me/?s=naruto+shippuden&paged=2",
            VerAniV2Markup.buildSearchUrl("naruto shippuden", 2),
        )
    }

    @Test
    fun `isOwnedContentUrl rejects external promos and archive roots but allows series movies and chapters`() {
        assertFalse(VerAniV2Markup.isOwnedContentUrl("https://jujutsukaisenonline.com/"))
        assertFalse(VerAniV2Markup.isOwnedContentUrl("https://portalanime.com/noticia/"))
        assertFalse(VerAniV2Markup.isOwnedContentUrl("https://verani.me/animes/"))
        assertFalse(VerAniV2Markup.isOwnedContentUrl("https://verani.me/animes/accion/"))
        assertFalse(VerAniV2Markup.isOwnedContentUrl("https://verani.me/peliculas/"))
        assertFalse(VerAniV2Markup.isOwnedContentUrl("https://verani.me/genero/isekai/"))

        assertTrue(VerAniV2Markup.isOwnedContentUrl("https://verani.me/kiniro-no-corda-primo-passo/"))
        assertTrue(VerAniV2Markup.isOwnedContentUrl("https://verani.me/pelicula/el-nino-y-la-bestia/"))
        assertTrue(VerAniV2Markup.isOwnedContentUrl("https://verani.me/boruto-naruto-next-generations/pelicula/boruto-naruto-la-pelicula/"))
        assertTrue(VerAniV2Markup.isOwnedContentUrl("https://verani.me/kikansha-no-mahou-wa-tokubetsu-desu/capitulo-8-episodio-8-629/"))
    }

    @Test
    fun `parseHomeWeeklyUpdates parses weekly section and ignores external cards`() {
        val html = """
            <html><body>
            <section class="content-section capitulos-section">
              <div class="capitulos-grid">
                <div class="content-card capitulo-card">
                  <a href="https://verani.me/kikansha-no-mahou/capitulo-8/" class="card-link">
                    <div class="card-image">
                      <img data-src="https://image.tmdb.org/t/p/w500/ep8.jpg" alt="Kikansha" class="card-img" />
                      <span class="badge-season">T1</span>
                      <span class="badge-ep">Ep. 8</span>
                      <span class="cap-lang-badge">SUB</span>
                    </div>
                    <h3 class="card-title">Kikansha no Mahou</h3>
                    <p class="card-subtitle">Episodio 8</p>
                  </a>
                </div>
                <div class="content-card capitulo-card">
                  <a href="https://verani.me/dandadan/capitulo-3/" class="card-link">
                    <img src="https://image.tmdb.org/t/p/w500/dd3.jpg" class="card-img" />
                    <span class="badge-ep">Ep. 3</span>
                    <span class="cap-lang-badge">LAT</span>
                    <h3 class="card-title">DanDaDan</h3>
                  </a>
                </div>
                <div class="content-card capitulo-card">
                  <a href="https://jujutsukaisenonline.com/cap-1/" class="card-link">
                    <h3 class="card-title">External Promo</h3>
                  </a>
                </div>
              </div>
            </section>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://verani.me/")
        val items = VerAniV2Markup.parseHomeWeeklyUpdates(doc)
        assertEquals(2, items.size)
        assertEquals("Kikansha no Mahou", items[0].title)
        assertEquals(8, items[0].latestEpisode)
        assertFalse(items[0].isDub)
        assertEquals("https://image.tmdb.org/t/p/w500/ep8.jpg", items[0].posterUrl)
        assertEquals("DanDaDan", items[1].title)
        assertEquals(3, items[1].latestEpisode)
        assertTrue(items[1].isDub)
    }

    @Test
    fun `parseArchiveCards handles archive filters movies and search articles with pagination`() {
        val searchHtml = """
            <html><body>
            <main id="main">
              <div class="search-results-grid animes-grid">
                <article class="content-card pelicula-card">
                  <a href="https://verani.me/boruto/pelicula/el-dia/" class="card-link">
                    <div class="card-image"><img src="https://image.tmdb.org/t/p/w500/boruto.jpg" class="card-img" /></div>
                    <div class="overlay-meta"><span class="meta-item">2016</span></div>
                    <span class="card-title">El dia en que Naruto se Convirtio en Hokage</span>
                  </a>
                </article>
                <article class="content-card anime-card">
                  <a href="https://verani.me/naruto-shippuden/" class="card-link">
                    <div class="card-image"><img src="https://image.tmdb.org/t/p/w500/ns.jpg" class="card-img" /></div>
                    <div class="overlay-chapters"><span class="chapters-count">500 Capítulos</span></div>
                    <h3 class="card-title">Naruto Shippuden</h3>
                  </a>
                </article>
              </div>
              <nav class="pagination-nav"><ul class="page-numbers">
                <li><a class="next page-numbers" href="https://verani.me/?s=naruto&paged=2">Siguiente</a></li>
              </ul></nav>
            </main>
            </body></html>
        """.trimIndent()
        val paged = VerAniV2Markup.parseArchiveCards(Jsoup.parse(searchHtml, "https://verani.me/"))
        assertTrue(paged.hasNext)
        assertEquals(2, paged.items.size)
        assertTrue(paged.items[0].isMovie)
        assertEquals("El dia en que Naruto se Convirtio en Hokage", paged.items[0].title)
        assertEquals(2016, paged.items[0].year)
        assertFalse(paged.items[1].isMovie)
        assertEquals(500, paged.items[1].episodesCount)
    }

    @Test
    fun `parseAnimeDetail orders seasons and episodes chronologically appends movies and skips site logo og image`() {
        val html = """
            <html>
            <head>
              <meta property="og:image" content="https://verani.me/wp-content/uploads/2026/01/Gemini_Generated_Image_y27f1qy27f1qy27f-1-1024x401.png" />
            </head>
            <body>
            <main id="main" class="site-main anime-landing">
              <section class="anime-hero-section">
                <img src="https://image.tmdb.org/t/p/w1280/bg_real.jpg" class="anime-hero-bg-image" />
                <h1 class="anime-hero-title">Boruto Demo</h1>
                <span class="anime-hero-year">2017</span>
                <span class="anime-hero-status">Finalizado</span>
                <p class="anime-hero-description">Historia ninja.</p>
                <iframe class="anime-trailer-iframe" src="https://www.youtube.com/embed/abc123"></iframe>
              </section>
              <section class="anime-info-section">
                <div class="anime-info-card">
                  <span class="anime-info-label">Género</span>
                  <div class="anime-info-value"><a>Acción</a><a>Aventura</a></div>
                </div>
              </section>
              <section class="anime-capitulos-section">
                <div class="temporada-group">
                  <span class="temporada-name" data-season-num="2">Temporada 2</span>
                  <div class="capitulo-card"><a class="capitulo-card-link" href="https://verani.me/boruto/cap-2/">
                    <h3 class="capitulo-card-title">Capitulo 2</h3><p class="capitulo-card-subtitle">El combate</p>
                  </a></div>
                  <div class="capitulo-card"><a class="capitulo-card-link" href="https://verani.me/boruto/cap-1/">
                    <h3 class="capitulo-card-title">Capitulo 1</h3><p class="capitulo-card-subtitle">Episodio 1</p>
                  </a></div>
                </div>
                <div class="temporada-group">
                  <span class="temporada-name" data-season-num="1">Temporada 1</span>
                  <div class="capitulo-card"><a class="capitulo-card-link" href="https://verani.me/boruto/t1-cap-2/">
                    <h3 class="capitulo-card-title">Capitulo 2</h3><p class="capitulo-card-subtitle">Episodio 2</p>
                  </a></div>
                  <div class="capitulo-card"><a class="capitulo-card-link" href="https://verani.me/boruto/t1-cap-1/">
                    <h3 class="capitulo-card-title">Capitulo 1</h3><p class="capitulo-card-subtitle">Episodio 1</p>
                  </a></div>
                </div>
              </section>
              <section class="anime-capitulos-section">
                <h2 class="section-title"><span class="title-text">Películas</span></h2>
                <div class="capitulos-grid peliculas-grid">
                  <div class="capitulo-card pelicula-card">
                    <a href="https://verani.me/boruto/pelicula/boruto-movie/" class="capitulo-card-link">
                      <img src="https://image.tmdb.org/t/p/w500/m1.jpg" alt="Boruto: Naruto la película" />
                      <h3 class="capitulo-card-title">Boruto: Naruto la…</h3>
                    </a>
                  </div>
                </div>
              </section>
              <section class="anime-recommendations-section">
                <div class="content-card">
                  <a href="https://verani.me/naruto-shippuden/" class="card-link">
                    <img src="https://image.tmdb.org/t/p/w500/ns.jpg" class="card-img" />
                    <h3 class="card-title">Naruto Shippuden</h3>
                  </a>
                </div>
              </section>
            </main>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://verani.me/boruto/")
        assertEquals(VerAniPageKind.ANIME, VerAniV2Markup.detectPageKind(doc))
        val detail = VerAniV2Markup.parseAnimeDetail(doc, "https://verani.me/boruto/")
        assertNotNull(detail)
        detail!!

        assertEquals("Boruto Demo", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w1280/bg_real.jpg", detail.posterUrl)
        assertEquals(2017, detail.year)
        assertEquals(VerAniShowStatus.COMPLETED, detail.status)
        assertEquals(listOf("Acción", "Aventura"), detail.tags)
        assertEquals("https://www.youtube.com/embed/abc123", detail.trailerUrl)
        assertEquals(1, detail.recommendations.size)

        // Seasons sorted ascending + Películas as season 3 (never season 0)
        assertEquals(listOf(1, 2, 3), detail.seasons.map { it.season })
        assertEquals(listOf("Temporada 1", "Temporada 2", "Películas"), detail.seasons.map { it.name })

        // Chronological episode order
        assertEquals(5, detail.episodes.size)
        assertEquals("https://verani.me/boruto/t1-cap-1/", detail.episodes[0].url)
        assertNull(detail.episodes[0].name) // generic "Episodio 1" omitted so UI shows standard numbering cleanly
        assertEquals("https://verani.me/boruto/t1-cap-2/", detail.episodes[1].url)
        assertEquals("https://verani.me/boruto/cap-1/", detail.episodes[2].url)
        assertEquals("https://verani.me/boruto/cap-2/", detail.episodes[3].url)
        assertEquals("El combate", detail.episodes[3].name) // real descriptive title preserved
        assertEquals(3, detail.episodes[4].season)
        assertEquals("Boruto: Naruto la película", detail.episodes[4].name)
    }

    @Test
    fun `parseMovieDetail extracts clean title without year span`() {
        val html = """
            <html><body>
            <section class="pelicula-hero-section">
              <img src="https://image.tmdb.org/t/p/w1280/back.jpg" class="pelicula-backdrop-image" />
              <h1 class="pelicula-title">El niño y la bestia <span class="pelicula-year-line">(2015)</span></h1>
              <span class="pelicula-original-title">Bakemono no Ko</span>
              <img src="https://image.tmdb.org/t/p/w500/poster.jpg" class="pelicula-poster-image" />
              <div class="pelicula-meta-generos"><span class="pelicula-meta-item">Acción, Aventura, Fantasía</span></div>
              <p class="pelicula-overview-text">Un chico entra al mundo de las bestias.</p>
            </section>
            <section class="capitulo-reproductores-section pelicula-reproductores-section"></section>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://verani.me/pelicula/el-nino-y-la-bestia/")
        assertEquals(VerAniPageKind.MOVIE, VerAniV2Markup.detectPageKind(doc))
        val movie = VerAniV2Markup.parseMovieDetail(doc, "https://verani.me/pelicula/el-nino-y-la-bestia/")!!
        assertEquals("El niño y la bestia", movie.title)
        assertEquals("Bakemono no Ko", movie.originalTitle)
        assertEquals(2015, movie.year)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", movie.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/back.jpg", movie.backgroundUrl)
        assertEquals(listOf("Acción", "Aventura", "Fantasía"), movie.tags)
    }

    @Test
    fun `parseChapterDetail and parsePlayerTabs preserve parent url language badges and skip undef placeholders`() {
        val html = """
            <html><body>
            <section class="capitulo-hero-section">
              <div class="capitulo-hero-breadcrumb">
                <a href="https://verani.me/kikansha/" class="breadcrumb-text">Kikansha no Mahou</a>
              </div>
              <span class="capitulo-hero-line1">Capitulo 8</span>
            </section>
            <section class="capitulo-reproductores-section">
              <button type="button" class="servidor-tab"
                data-url="https://player.zilla-networks.com/m3u8/abc"
                data-label="Servidor 1" data-servidor="japones-1" data-idioma="Japonés" data-subtitulos="Español"></button>
              <button type="button" class="servidor-tab"
                data-url="https://www.mp4upload.com/embed-undef.html"
                data-label="Servidor roto" data-servidor="japones-2" data-idioma="Japonés" data-subtitulos="Español"></button>
              <button type="button" class="servidor-tab"
                data-url="https://animeav1.uns.bio/#k8f1xe"
                data-label="Servidor Latino" data-servidor="latino-1" data-idioma="Español Latino" data-subtitulos=""></button>
              <button type="button" class="servidor-tab"
                data-url="https://voe.sx/e/l3m41y8k42g6"
                data-label="Servidor ESP" data-servidor="esp-1" data-idioma="Español España" data-subtitulos=""></button>
            </section>
            <section class="capitulo-descargas-section">
              <a href="https://www.mp4upload.com/embed-realid123.html">Mp4Upload</a>
              <a href="https://ouo.io/ignore-me">Acortador</a>
            </section>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://verani.me/kikansha/capitulo-8/")
        assertEquals(VerAniPageKind.CHAPTER, VerAniV2Markup.detectPageKind(doc))
        val chap = VerAniV2Markup.parseChapterDetail(doc, "https://verani.me/kikansha/capitulo-8/")
        assertEquals("https://verani.me/kikansha/", chap.parentAnimeUrl)

        val tabs = VerAniV2Markup.parsePlayerTabs(doc)
        assertEquals(4, tabs.size)
        assertEquals("Sub Español", tabs[0].badge)
        assertEquals("https://animeav1.uns.bio/#k8f1xe", tabs[1].url)
        assertEquals("Latino", tabs[1].badge)
        assertEquals("Castellano", tabs[2].badge)
        assertEquals("https://www.mp4upload.com/embed-realid123.html", tabs[3].url)
    }
}
