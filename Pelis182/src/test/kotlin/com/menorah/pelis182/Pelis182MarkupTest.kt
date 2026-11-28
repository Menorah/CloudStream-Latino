package com.menorah.pelis182

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class Pelis182MarkupTest {

    @Test
    fun `paged and search url builders preserve query strings and pagination format`() {
        assertEquals("https://pelis182.net/catalogo/", Pelis182Markup.buildPagedUrl("/catalogo/", 1))
        assertEquals("https://pelis182.net/catalogo/page/3/", Pelis182Markup.buildPagedUrl("/catalogo/", 3))
        assertEquals(
            "https://pelis182.net/catalogo/?orden=tendencias",
            Pelis182Markup.buildPagedUrl("/catalogo/?orden=tendencias", 1),
        )
        assertEquals(
            "https://pelis182.net/catalogo/page/2/?orden=tendencias",
            Pelis182Markup.buildPagedUrl("/catalogo/?orden=tendencias", 2),
        )
        assertEquals(
            "https://pelis182.net/category/accion/page/4/?tipo=peliculas",
            Pelis182Markup.buildPagedUrl("/category/accion/?tipo=peliculas", 4),
        )
        assertEquals("https://pelis182.net/?s=blade+runner", Pelis182Markup.buildSearchUrl("blade runner", 1))
        assertEquals("https://pelis182.net/page/2/?s=blade+runner", Pelis182Markup.buildSearchUrl("blade runner", 2))
    }

    @Test
    fun `catalog parser reads movies series badges srcset posters and next page`() {
        val html = """
            <!doctype html>
            <html>
            <head><link rel="next" href="https://pelis182.net/category/accion/page/2/"/></head>
            <body>
            <section class="catalog-shell">
              <div class="card-grid">
                <article class="p182-card">
                  <a aria-label="Street Fighter" href="https://pelis182.net/street-fighter/">
                    <div class="p182-card__image">
                      <img alt="Póster de Street Fighter"
                           src="./Accion_files/j9Ukn1hRqSyv5qhHgDHd1s1Dhsg.jpg"
                           srcset="https://image.tmdb.org/t/p/w185/j9Ukn1hRqSyv5qhHgDHd1s1Dhsg.jpg 185w, https://image.tmdb.org/t/p/w500/j9Ukn1hRqSyv5qhHgDHd1s1Dhsg.jpg 500w" />
                    </div>
                    <h3>Street Fighter</h3>
                    <div class="p182-card__meta"><span>2026</span><span>6.3</span></div>
                  </a>
                </article>
                <article class="p182-card">
                  <a aria-label="VisionQuest – Temporada 1" href="/visionquest-temporada-1/">
                    <div class="p182-card__image">
                      <img src="https://image.tmdb.org/t/p/w185/vision.jpg" />
                      <span class="p182-card__badge">SERIE</span>
                    </div>
                    <h3>VisionQuest – Temporada 1</h3>
                    <div class="p182-card__meta"><span>2026</span><span>8.1</span></div>
                  </a>
                </article>
              </div>
              <nav class="navigation pagination">
                <div class="nav-links">
                  <span aria-current="page" class="page-numbers current">1</span>
                  <a class="next page-numbers" href="https://pelis182.net/category/accion/page/2/">→</a>
                </div>
              </nav>
            </section>
            </body>
            </html>
        """.trimIndent()

        val page = Pelis182Markup.parseCatalogPage(Jsoup.parse(html, Pelis182Markup.MAIN_URL))
        assertTrue(page.hasNext)
        assertEquals("https://pelis182.net/category/accion/page/2/", page.nextUrl)
        assertEquals(2, page.items.size)

        val movie = page.items[0]
        assertEquals("Street Fighter", movie.title)
        assertEquals("https://pelis182.net/street-fighter/", movie.url)
        assertEquals("https://image.tmdb.org/t/p/w500/j9Ukn1hRqSyv5qhHgDHd1s1Dhsg.jpg", movie.posterUrl)
        assertEquals(2026, movie.year)
        assertEquals(6.3, movie.rating!!, 0.001)
        assertEquals(Pelis182MediaKind.MOVIE, movie.kind)
        assertFalse(movie.isSeries)

        val series = page.items[1]
        assertEquals("VisionQuest – Temporada 1", series.title)
        assertEquals("https://pelis182.net/visionquest-temporada-1/", series.url)
        assertEquals("https://image.tmdb.org/t/p/w500/vision.jpg", series.posterUrl)
        assertEquals(Pelis182MediaKind.SERIES, series.kind)
        assertTrue(series.isSeries)
    }

    @Test
    fun `movie detail parser extracts sinopsis metadata backdrop cast jsonld and paulinito embed`() {
        val html = """
            <!doctype html>
            <html>
            <head>
              <link rel="canonical" href="https://pelis182.net/el-ultimo-gran-golpe/"/>
              <script type="application/ld+json">
              {"@context":"https://schema.org","@type":"Movie","name":"El último gran golpe","duration":"PT96M","dateCreated":"2026-06-18"}
              </script>
            </head>
            <body>
            <main id="main-content">
              <article class="watch-page">
                <nav class="breadcrumbs"><a href="https://pelis182.net/">Inicio</a><a href="https://pelis182.net/catalogo/">Películas</a></nav>
                <header class="watch-heading">
                  <div class="watch-heading__poster">
                    <img src="https://image.tmdb.org/t/p/w185/r0ZuhQn3v1INDUavdAPY7tPWR96.jpg" width="185"/>
                  </div>
                  <div class="watch-heading__body">
                    <p class="watch-kicker">PELÍCULA</p>
                    <h1 class="watch-title">El último gran golpe</h1>
                    <div class="watch-meta">
                      <span>2026</span>
                      <span>96 min</span>
                      <span class="watch-rating">★ 6.2<small>/10 · TMDb</small></span>
                    </div>
                    <nav class="watch-genres">
                      <a href="https://pelis182.net/category/accion/?tipo=peliculas">Acción</a>
                      <span>Suspense</span>
                    </nav>
                  </div>
                </header>
                <section id="p182-theater" class="theater">
                  <div class="theater__screen">
                    <div class="uvpi-wrapper">
                      <div class="uvpi-player-layer">
                        <iframe data-uvpi-src="https://paulinito.com/player/x4KVpoX5HJaAPax/" src="./saved_resource.html"></iframe>
                      </div>
                      <button class="uvpi-poster" style='background-image: linear-gradient(rgba(0,0,0,.18),rgba(0,0,0,.28)), url("https://image.tmdb.org/t/p/w1280/9Bv4Oki9u3aKZxDCNDcU4N2mttb.jpg");'></button>
                    </div>
                  </div>
                </section>
                <section id="sinopsis" class="movie-info">
                  <h2 class="p182ms-heading">El último gran golpe: Sinopsis</h2>
                  <p class="p182ms-text">Manco Kapak es el dueño de un club nocturno.</p>
                  <dl class="entry-facts">
                    <div><dt>Dirección</dt><dd>Derrick Borte</dd></div>
                    <div><dt>Estreno</dt><dd>18 de junio de 2026</dd></div>
                  </dl>
                </section>
                <section class="entry-cast">
                  <div class="entry-cast__person">
                    <img src="https://image.tmdb.org/t/p/w185/3IBZF0qCZMZ7zJYlgrH0sQzNZV.jpg" />
                    <span><b>Russell Crowe</b><small>Manco Kapak</small></span>
                  </div>
                </section>
                <section class="related">
                  <article class="p182-card">
                    <a href="https://pelis182.net/aniquilacion/">
                      <div class="p182-card__image"><img src="https://image.tmdb.org/t/p/w500/anq.jpg"/></div>
                      <h3>Aniquilación</h3>
                      <div class="p182-card__meta"><span>2026</span><span>5.7</span></div>
                    </a>
                  </article>
                </section>
              </article>
            </main>
            </body>
            </html>
        """.trimIndent()

        val detail = Pelis182Markup.parseDetail(Jsoup.parse(html, Pelis182Markup.MAIN_URL), "https://pelis182.net/el-ultimo-gran-golpe/")
        assertNotNull(detail)
        detail!!
        assertFalse(detail.isSeries)
        assertEquals(Pelis182MediaKind.MOVIE, detail.kind)
        assertEquals("El último gran golpe", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w500/r0ZuhQn3v1INDUavdAPY7tPWR96.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/9Bv4Oki9u3aKZxDCNDcU4N2mttb.jpg", detail.backgroundPosterUrl)
        assertEquals(2026, detail.year)
        assertEquals(96, detail.durationMinutes)
        assertEquals(6.2, detail.rating!!, 0.001)
        assertEquals(listOf("Acción", "Suspense"), detail.genres)
        assertEquals("2026-06-18", detail.isoDate)
        assertEquals(1, detail.actors.size)
        assertEquals("Russell Crowe", detail.actors[0].name)
        assertEquals("Manco Kapak", detail.actors[0].role)
        assertEquals(1, detail.movieEmbeds.size)
        assertEquals("https://paulinito.com/player/x4KVpoX5HJaAPax/", detail.movieEmbeds[0].url)
        assertEquals(1, detail.recommendations.size)
        assertFalse(detail.comingSoon)
    }

    @Test
    fun `series detail parser extracts season episodes and matches sibling seasons`() {
        val html = """
            <!doctype html>
            <html>
            <head><link rel="canonical" href="https://pelis182.net/the-boys-temporada-5/"/></head>
            <body>
            <main id="main-content">
              <article class="watch-page">
                <header class="watch-heading">
                  <div class="watch-heading__poster"><img src="https://image.tmdb.org/t/p/w185/9BrjQkOW8wMXzOUslk8RK2XMC1H.jpg"/></div>
                  <div class="watch-heading__body">
                    <p class="watch-kicker">SERIE</p>
                    <h1 class="watch-title">The Boys</h1>
                    <p class="watch-season">Temporada 5</p>
                    <div class="watch-meta"><span>2026</span><span>8 episodios</span><span class="watch-rating">★ 8.4/10 · TMDb</span></div>
                  </div>
                </header>
                <section id="p182-theater" class="theater">
                  <div class="theater__screen"><div class="uvpi-wrapper">
                    <div class="uvpi-player-layer"><iframe data-uvpi-src="https://paulinito.com/player/jnovOhHUhXOPfcS/" src="about:blank"></iframe></div>
                    <button class="uvpi-poster" style='background-image: url("https://image.tmdb.org/t/p/w1280/n6vVs6z8obNbExdD3QHTr4Utu1Z.jpg");'></button>
                  </div></div>
                  <div class="server-row episode-row">
                    <span>Selecciona un episodio:</span>
                    <div role="group">
                      <button class="server-button is-active" data-iframe='&lt;iframe src="https://paulinito.com/player/jnovOhHUhXOPfcS/" width="1280" height="720"&gt;&lt;/iframe&gt;' type="button">T5X1</button>
                      <button class="server-button" data-iframe='&lt;iframe src="https://paulinito.com/player/1xdg22jb8t90S40/" width="1280" height="720"&gt;&lt;/iframe&gt;' type="button">T5X2</button>
                    </div>
                  </div>
                </section>
                <section id="sinopsis" class="movie-info">
                  <p>Homelander gobierna Estados Unidos.</p>
                  <dl class="entry-facts"><div><dt>Primera emisión</dt><dd>8 de abril de 2026</dd></div></dl>
                </section>
                <section class="related">
                  <article class="p182-card">
                    <a href="https://pelis182.net/the-boys-temporada-4/"><span class="p182-card__badge">SERIE</span><h3>The Boys – Temporada 4</h3></a>
                  </article>
                  <article class="p182-card">
                    <a href="https://pelis182.net/agatha-en-todas-partes-temporada-1/"><span class="p182-card__badge">SERIE</span><h3>Agatha en todas partes – Temporada 1</h3></a>
                  </article>
                </section>
              </article>
            </main>
            </body>
            </html>
        """.trimIndent()

        val detail = Pelis182Markup.parseDetail(Jsoup.parse(html, Pelis182Markup.MAIN_URL), "https://pelis182.net/the-boys-temporada-5/")
        assertNotNull(detail)
        detail!!
        assertTrue(detail.isSeries)
        assertEquals(Pelis182MediaKind.SERIES, detail.kind)
        assertEquals("The Boys", detail.title)
        assertEquals(5, detail.seasonNumber)
        assertEquals("2026-04-08", detail.isoDate)
        assertEquals(2, detail.episodes.size)
        assertEquals(5, detail.episodes[0].season)
        assertEquals(1, detail.episodes[0].episode)
        assertEquals("Episodio 1", detail.episodes[0].title)
        assertEquals("https://paulinito.com/player/jnovOhHUhXOPfcS/", detail.episodes[0].embeds[0].url)
        assertEquals(2, detail.episodes[1].episode)
        assertEquals("https://paulinito.com/player/1xdg22jb8t90S40/", detail.episodes[1].embeds[0].url)

        assertTrue(
            Pelis182Markup.isMatchingSeasonCard(
                detail.title,
                "The Boys – Temporada 4",
                detail.url,
                "https://pelis182.net/the-boys-temporada-4/",
            ),
        )
        assertFalse(
            Pelis182Markup.isMatchingSeasonCard(
                detail.title,
                "Agatha en todas partes – Temporada 1",
                detail.url,
                "https://pelis182.net/agatha-en-todas-partes-temporada-1/",
            ),
        )
    }

    @Test
    fun `paulinito config parser extracts hls sources srt subtitles and excludes thumbnails including JuicyCodes`() {
        val rawConfigHtml = """
            <script>
            jwplayer("video_player").setup({
              image: "https://spark.9bg.net/abc/preview.jpg",
              sources: [{"file":"https://spark.9bg.net/F1OVoiq44j/WvFb4065jaj/video.m3u8","label":"HD","type":"application/x-mpegURL"}],
              tracks: [
                {"file":"https://spark.9bg.net/sub/OThjN19lY2Y.srt","label":"SUB-1","kind":"captions","default":false},
                {"file":"https://spark.9bg.net/thumb/thumbnail.vtt","kind":"thumbnails"}
              ]
            });
            </script>
        """.trimIndent()

        val cfg = Pelis182Markup.parsePaulinitoConfig(rawConfigHtml)
        assertEquals(1, cfg.sources.size)
        assertEquals("https://spark.9bg.net/F1OVoiq44j/WvFb4065jaj/video.m3u8", cfg.sources[0].file)
        assertEquals("HD", cfg.sources[0].label)
        assertEquals(1, cfg.tracks.size)
        assertEquals("https://spark.9bg.net/sub/OThjN19lY2Y.srt", cfg.tracks[0].file)
        assertEquals("Español (SUB-1)", cfg.tracks[0].label)

        // Encoded via JuicyCodes.Run("...")
        val encoded = Base64.getEncoder().encodeToString(rawConfigHtml.toByteArray(StandardCharsets.UTF_8))
        val juicyHtml = """<script>JuicyCodes.Run("$encoded");</script>"""
        val cfg2 = Pelis182Markup.parsePaulinitoConfig(juicyHtml)
        assertEquals(1, cfg2.sources.size)
        assertEquals("https://spark.9bg.net/F1OVoiq44j/WvFb4065jaj/video.m3u8", cfg2.sources[0].file)
        assertEquals(1, cfg2.tracks.size)
        assertEquals("Español (SUB-1)", cfg2.tracks[0].label)
        assertNull(cfg2.tracks.firstOrNull { it.file.contains("thumbnail") })
    }
}
