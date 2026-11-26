package com.menorah.cuevana3v

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Cuevana3vMarkupTest {

    @Test
    fun `decodeTungUrl decodes Hyper, Nebula and base64 v embeds`() {
        val hyperUrl = "https://tungtungsahur.cuevana3v.pro/?token=1Vl0ZUFoGB3A3B3E3"
        val resolvedHyper = Cuevana3vMarkup.decodeTungUrl(hyperUrl)
        assertEquals("https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/7q1v682peq0u", resolvedHyper)

        val nebulaUrl = "https://tungtungsahur.cuevana3v.pro/?token=3GxkPBB0JUhUGAQN1"
        val resolvedNebula = Cuevana3vMarkup.decodeTungUrl(nebulaUrl)
        assertEquals("https://vibuxer.com/e/zopp6y5coyze", resolvedNebula)

        val vEmbed = "https://tungtungsahur.cuevana3v.pro/?v=aHR0cHM6Ly92aWRzcmMucG0vZW1iZWQvbW92aWUvMTIzNDU="
        val resolvedV = Cuevana3vMarkup.decodeTungUrl(vEmbed)
        assertEquals("https://vidsrc.me/embed/movie/12345", resolvedV)
    }

    @Test
    fun `parseTungConfig parses dynamic server table and xor key`() {
        val js = """
            <script>
            const urlParams = new URLSearchParams(window.location.search);
            const servers = {
                1: 'https://new-hyper.example/v/',
                2: 'https://filemoon.sx/e/',
                5: 'https://mirror5.example/e/'
            };
            const key = 'custom-key-2026';
            </script>
        """.trimIndent()
        val cfg = Cuevana3vMarkup.parseTungConfig(js)
        assertNotNull(cfg)
        assertEquals("https://new-hyper.example/v/", cfg!!.servers['1'])
        assertEquals("https://mirror5.example/e/", cfg.servers['5'])
        assertEquals("custom-key-2026", cfg.xorKey)
    }

    @Test
    fun `parseCatalogItems extracts movies, series, episodes, years and upgrades TMDB w200 posters`() {
        val html = """
            <html><body><main>
              <a data-instant="" href="https://cuevana3v.pro/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha">
                <div class="item-picture"><figure>
                  <img alt="película Los Juegos del Hambre: Amanecer en la cosecha" class="poster" src="https://image.tmdb.org/t/p/w200/8K4lZ5oepk3kArMWfQuqSegxSRe.jpg"/>
                  <span class="year text-center">2026</span>
                </figure></div>
                <div class="item-detail"><p>Los Juegos del Hambre: Amanecer en la cosecha</p></div>
              </a>
              <a href="https://cuevana3v.pro/serie/a-la-deriva-2">
                <div class="item-picture"><figure>
                  <img alt="serie A la deriva" class="poster" src="https://image.tmdb.org/t/p/w200/1wR8frkhJbbcIAVbGeP7uyig4R9.jpg"/>
                  <span class="year text-center">2026</span>
                </figure></div>
                <div class="item-detail"><p>A la deriva</p></div>
              </a>
              <a href="https://cuevana3v.pro/serie/a-la-deriva-2/episodio-1x10">
                <div class="item-picture"><figure>
                  <img alt="Poster del episodio" class="poster" src="https://image.tmdb.org/t/p/w1280/dRavic5pCfprwGF8hBIZu2axKNx.jpg"/>
                </figure></div>
                <div class="item-detail"><p>A la deriva 1x10</p></div>
              </a>
            </main></body></html>
        """.trimIndent()

        val cards = Cuevana3vMarkup.parseCatalogItems(html)
        assertEquals(3, cards.size)

        assertEquals("Los Juegos del Hambre: Amanecer en la cosecha", cards[0].title)
        assertEquals(CuevanaItemKind.Movie, cards[0].kind)
        assertEquals(2026, cards[0].year)
        assertEquals("https://image.tmdb.org/t/p/w500/8K4lZ5oepk3kArMWfQuqSegxSRe.jpg", cards[0].posterUrl)

        assertEquals("A la deriva", cards[1].title)
        assertEquals(CuevanaItemKind.Series, cards[1].kind)
        assertEquals("https://image.tmdb.org/t/p/w500/1wR8frkhJbbcIAVbGeP7uyig4R9.jpg", cards[1].posterUrl)

        assertEquals("A la deriva 1x10", cards[2].title)
        assertEquals(CuevanaItemKind.Episode, cards[2].kind)
        assertEquals("https://image.tmdb.org/t/p/w1280/dRavic5pCfprwGF8hBIZu2axKNx.jpg", cards[2].posterUrl)
    }

    @Test
    fun `hasNextPage handles Siguiente links and disables tendencies pagination`() {
        val withNext = """<div class="pag"><a href="https://cuevana3v.pro/peliculas?page=2">Siguiente</a></div>"""
        val onlyPrev = """<div class="pag"><a href="https://cuevana3v.pro/peliculas?page=1">Anterior</a></div>"""

        assertTrue(Cuevana3vMarkup.hasNextPage(withNext, "https://cuevana3v.pro/peliculas", 1, 18))
        assertFalse(Cuevana3vMarkup.hasNextPage(onlyPrev, "https://cuevana3v.pro/peliculas?page=2", 2, 18))
        assertFalse(Cuevana3vMarkup.hasNextPage(withNext, "https://cuevana3v.pro/peliculas?orden=tendencias", 1, 18))
        assertFalse(Cuevana3vMarkup.hasNextPage(withNext, "https://cuevana3v.pro/peliculas", 1, 0))
    }

    @Test
    fun `parseMovieDetail extracts title, score, plot, year, duration, actors, tags and recommendations`() {
        val html = """
            <html>
            <head><meta property="og:title" content="Película Los Juegos del Hambre: Amanecer en la cosecha Gratis Online (2026)"/></head>
            <body><main><div class="web-container">
              <div class="backdrop">
                <div class="backdrop-info">
                  <figure><img src="https://image.tmdb.org/t/p/w200/8K4lZ5oepk3kArMWfQuqSegxSRe.jpg"/></figure>
                  <h1 class="title page-title-hero"> Los Juegos del Hambre: Amanecer en la cosecha <span class="voteav">7.4</span></h1>
                  <p>Ambientada décadas antes de Katniss y Peeta.</p>
                </div>
                <div class="backdrop-image">
                  <figure><img src="https://image.tmdb.org/t/p/w1280/aqFZcr2dxSZ2UwWSo1WC6C0rwDf.jpg"/></figure>
                </div>
              </div>
              <div class="section"><h2 class="subtitle">Año de estreno</h2><p>2026</p></div>
              <div class="section"><h2 class="subtitle">Cuánto dura ver en Cuevana 3?</h2><p>Duración estima de 2h 39m</p></div>
              <div class="section"><h2 class="subtitle">Actores</h2><p>Joseph Zada, Mckenna Grace, Ralph Fiennes</p></div>
              <div class="section"><h2 class="subtitle">Categorías</h2>
                <p>
                  <a href="https://cuevana3v.pro/peliculas?genero=accion">Accion</a>
                  <a href="https://cuevana3v.pro/peliculas?genero=ciencia-ficcion">Ciencia Ficcion</a>
                </p>
              </div>
              <section class="section"><h2 class="subtitle">Qué más podría interesarte</h2>
                <div class="hero-carousel">
                  <a href="https://cuevana3v.pro/pelicula/eragon">
                    <div class="item-picture"><figure><img src="https://image.tmdb.org/t/p/w200/era.jpg"/><span class="year">2006</span></figure></div>
                    <div class="item-detail"><p>Éragon</p></div>
                  </a>
                </div>
              </section>
            </div></main></body></html>
        """.trimIndent()

        val detail = Cuevana3vMarkup.parseMovieDetail(html, "https://cuevana3v.pro/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha")
        assertEquals("Los Juegos del Hambre: Amanecer en la cosecha", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w500/8K4lZ5oepk3kArMWfQuqSegxSRe.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/aqFZcr2dxSZ2UwWSo1WC6C0rwDf.jpg", detail.backgroundPosterUrl)
        assertEquals("Ambientada décadas antes de Katniss y Peeta.", detail.plot)
        assertEquals(2026, detail.year)
        assertEquals(159, detail.durationMinutes)
        assertEquals(7.4, detail.rating!!, 0.01)
        assertEquals(listOf("Accion", "Ciencia Ficcion"), detail.genres)
        assertEquals(listOf("Joseph Zada", "Mckenna Grace", "Ralph Fiennes"), detail.actors)
        assertEquals(1, detail.recommendations.size)
        assertEquals("Éragon", detail.recommendations.first().title)
    }

    @Test
    fun `parseSeriesDetail and parseSeasonEpisodes handle seasons, dummy ratings and episode promotion`() {
        val seriesHtml = """
            <html><head>
              <script type="application/ld+json">{"@type":"TVSeries","name":"Dexter: Resurrección","aggregateRating":{"@type":"AggregateRating","ratingCount":1,"ratingValue":1}}</script>
            </head><body><main>
              <div class="title-detail-hero">
                <div class="title-detail-hero-details">
                  <figure class="poster"><img src="https://image.tmdb.org/t/p/w200/lZyvtYfG6RMJm3B6sjfJvcTVtFw.jpg"/></figure>
                  <div class="details"><h1 class="title"> Serie Dexter: Resurrección </h1></div>
                </div>
                <div class="title-detail-hero-background">
                  <figure class="backdrop"><img src="https://image.tmdb.org/t/p/w1280/8J0SjKToFBbg8dKpocVOCsrC77C.jpg"/></figure>
                </div>
              </div>
              <div class="title-detail">
                <div class="section"><h2 class="subtitle">2 Temporadas</h2>
                  <ul class="objects-lists">
                    <li><a href="https://cuevana3v.pro/serie/dexter-resurreccion/temporada-2"><p>Temporada <span>2</span></p></a></li>
                    <li><a href="https://cuevana3v.pro/serie/dexter-resurreccion/temporada-1"><p>Temporada <span>1</span></p></a></li>
                  </ul>
                </div>
                <div class="section"><h2 class="subtitle">Resumen general</h2><p>Dexter Morgan despierta de un coma.</p></div>
                <div class="section"><h2 class="subtitle">Año de estreno</h2><p>La primera temporada fue publicada en el año 2025</p></div>
                <div class="section"><h2 class="subtitle">Actores</h2><p>Michael C. Hall, Jack Alcott</p></div>
                <div class="section"><h2 class="subtitle">Categoría</h2><p><a href="https://cuevana3v.pro/series?genero=drama">Drama</a></p></div>
              </div>
            </main></body></html>
        """.trimIndent()

        val detail = Cuevana3vMarkup.parseSeriesDetail(seriesHtml, "https://cuevana3v.pro/serie/dexter-resurreccion")
        assertEquals("Dexter: Resurrección", detail.title)
        assertEquals(2025, detail.year)
        assertNull(detail.rating) // dummy ratingValue=1 with ratingCount=1 ignored
        assertEquals(listOf(1, 2), detail.seasons.map { it.season })
        assertEquals("https://image.tmdb.org/t/p/w500/lZyvtYfG6RMJm3B6sjfJvcTVtFw.jpg", detail.posterUrl)
        assertEquals("https://cuevana3v.pro/serie/dexter-resurreccion", Cuevana3vMarkup.extractSeriesUrlFromUrl("https://cuevana3v.pro/serie/dexter-resurreccion/episodio-2x4"))

        val seasonHtml = """
            <html><body><main>
              <a href="https://cuevana3v.pro/serie/dexter-resurreccion/episodio-2x4">
                <img src="https://image.tmdb.org/t/p/w1280/ep24.jpg"/>
                <div class="item-detail"><p>2x4 Call Me Red</p></div>
              </a>
              <a href="https://cuevana3v.pro/serie/dexter-resurreccion/episodio-2x1">
                <img src="https://image.tmdb.org/t/p/w1280/ep21.jpg"/>
                <div class="item-detail"><p>2x1</p></div>
              </a>
            </main></body></html>
        """.trimIndent()
        val eps = Cuevana3vMarkup.parseSeasonEpisodes(seasonHtml, fallbackSeason = 2)
        assertEquals(2, eps.size)
        assertEquals(1, eps[0].episode)
        assertEquals("Episodio 1", eps[0].title)
        assertEquals(4, eps[1].episode)
        assertEquals("Call Me Red", eps[1].title)
    }

    @Test
    fun `parsePlayerTabs normalizes languages, qualities and server names`() {
        val html = """
            <div id="player">
              <ul class="tabs-video">
                <li class="tab-video-item">
                  <span class="tab-item-name">Latino Calidad · HD</span>
                  <ul>
                    <li data-server="https://tungtungsahur.cuevana3v.pro/?token=1ABC">Servidor Hyper Reproducir</li>
                    <li data-server="https://tungtungsahur.cuevana3v.pro/?token=3XYZ">Servidor Nebula Reproducir</li>
                  </ul>
                </li>
                <li class="tab-video-item">
                  <span class="tab-item-name">Castellano Calidad · HD</span>
                  <ul>
                    <li data-server="https://tungtungsahur.cuevana3v.pro/?token=1DEF">Servidor Hyper Reproducir</li>
                  </ul>
                </li>
                <li class="tab-video-item">
                  <span class="tab-item-name">Multilenguaje [CC] Premiere</span>
                  <ul>
                    <li data-server="https://tungtungsahur.cuevana3v.pro/?v=aHR0cHM6Ly92aWRsaW5rLnByby9tb3ZpZS8x">Servidor Death Star Reproducir</li>
                  </ul>
                </li>
              </ul>
            </div>
        """.trimIndent()

        val sources = Cuevana3vMarkup.parsePlayerTabs(html)
        assertEquals(4, sources.size)
        assertEquals("Latino", sources[0].language)
        assertEquals("HD", sources[0].quality)
        assertEquals("Hyper", sources[0].serverName)
        assertEquals("Castellano", sources[2].language)
        assertEquals("Multilenguaje", sources[3].language)
        assertEquals("Premiere", sources[3].quality)
        assertEquals("Death Star", sources[3].serverName)
    }

    @Test
    fun `CuevanaVidHideParser unpacks packed script and prioritizes hls2 over hls4 and hls3`() {
        val packedScript = """
            <script type="text/javascript">
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('5 0={"4":"/1/2.3","7":"8://9.a/b/c.3","6":"8://d.e/f/g.h"};i("j").k({l:[{m:0.4||0.7||0.6,n:"o"}]});',25,25,'links|stream|abc|m3u8|hls4|var|hls2|hls3|https|bad|site|hls3|master|cdn|com|hls2|good|m3u8|jwplayer|vplayer|setup|sources|file|type|hls'.split('|'),0,{}))
            </script>
        """.trimIndent()

        val parsed = CuevanaVidHideParser.parse(packedScript, "https://vibuxer.com/e/zopp6y5coyze")
        assertEquals("https://vibuxer.com", parsed.finalOrigin)
        assertTrue(parsed.streams.isNotEmpty())
        assertEquals("hls2", parsed.streams[0].key)
        assertEquals("https://cdn.com/hls2/good.m3u8", parsed.streams[0].url)
        assertEquals("hls4", parsed.streams[1].key)
        assertEquals("https://vibuxer.com/stream/abc.m3u8", parsed.streams[1].url)
        assertEquals("hls3", parsed.streams[2].key)
    }
}
