package com.menorah.pelisjuanita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PelisJuanitaDataTest {

    @Test
    fun `session parsing, header construction and gate detection handle verified and expired states`() {
        val cookies = PelisJuanitaSessionHelper.parseCookieHeader("PHPSESSID=abc123; cf_clearance=cf999; path=/")
        assertEquals("abc123", cookies["PHPSESSID"])
        assertEquals("cf999", cookies["cf_clearance"])

        val merged = PelisJuanitaSessionHelper.mergeCookieHeaders("PHPSESSID=old; foo=1", "PHPSESSID=new; bar=2")
        val mergedMap = PelisJuanitaSessionHelper.parseCookieHeader(merged)
        assertEquals("new", mergedMap["PHPSESSID"])
        assertEquals("1", mergedMap["foo"])
        assertEquals("2", mergedMap["bar"])

        val session = PelisJuanitaSessionData(
            cookieHeader = "PHPSESSID=sess42; cf_clearance=clear1",
            userAgent = "TestAgent/1.0",
            verifiedAtMs = 1_700_000_000_000L,
        )
        assertTrue(session.hasPhpSession())
        val ajaxHeaders = PelisJuanitaSessionHelper.buildRequestHeaders(session, referer = "https://pelisjuanita.com/movies/", isAjax = true)
        assertEquals("TestAgent/1.0", ajaxHeaders["user-agent"])
        assertNull(ajaxHeaders["User-Agent"])
        assertEquals("PHPSESSID=sess42; cf_clearance=clear1", ajaxHeaders["Cookie"])
        assertEquals("XMLHttpRequest", ajaxHeaders["X-Requested-With"])
        assertEquals("https://pelisjuanita.com/movies/", ajaxHeaders["Referer"])

        val noCookieHeaders = PelisJuanitaSessionHelper.buildRequestHeaders(
            session,
            referer = "https://pelisjuanita.com/movies/",
            isAjax = false,
            includeCookieHeader = false,
        )
        assertNull(noCookieHeaders["Cookie"])

        val gateHtml = """<html><form method="POST"><input name="empresa_hp"><input name="captcha"></form></html>"""
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(302, "https://pelisjuanita.com/inicio.php", ""))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(200, "https://pelisjuanita.com/inicio.php", gateHtml))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(403, "https://pelisjuanita.com/movies/", "Verificación de Seguridad"))
        assertFalse(PelisJuanitaSessionHelper.looksLikeVerificationGate(200, "https://pelisjuanita.com/movies/", "<div id=\"dt_contenedor\"><div class=\"grid-container\"></div></div>"))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerifiedCatalogOrDetail("https://pelisjuanita.com/movies/", "<div id=\"dt_contenedor\"><div class=\"grid-item\"></div></div>"))
        assertTrue(PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(403, "<html><title>Just a moment...</title></html>"))
        assertTrue(PelisJuanitaSessionHelper.shouldTryWebTransport(403, "https://pelisjuanita.com/movies/pelicula/madden", "", null))
        assertTrue(PelisJuanitaSessionHelper.shouldTryWebTransport(302, "https://pelisjuanita.com/inicio.php", "", null))
        assertFalse(PelisJuanitaSessionHelper.shouldTryWebTransport(200, "https://pelisjuanita.com/movies/movieInfo.php", "<div>ok</div>", null))
    }

    @Test
    fun `url builders and target parser resolve movies, series, episodes and ajax endpoints`() {
        val mReq = PelisJuanitaMarkup.buildCatalogRequest("pelis:estrenos:", 2)
        assertTrue(mReq.isMovies)
        assertNull(mReq.postForm)
        assertEquals("https://pelisjuanita.com/movies/movies.php?estrenos=&page=2", mReq.url)

        val ep1 = PelisJuanitaMarkup.buildCatalogRequest("series:ultimos-episodios:", 1)
        assertFalse(ep1.isMovies)
        assertTrue(ep1.isRecentEpisodes)
        assertNull(ep1.postForm)
        assertEquals("https://pelisjuanita.com/series/apiSeries.php?ultimos-episodios=", ep1.url)

        val ep2 = PelisJuanitaMarkup.buildCatalogRequest("series:ultimos-episodios:", 2)
        assertEquals("https://pelisjuanita.com/series/apiSeries.php", ep2.url)
        assertEquals(mapOf("page" to "2"), ep2.postForm)

        assertEquals("https://pelisjuanita.com/tv/app/buscar.php?q=%25&pagina=3", PelisJuanitaMarkup.buildSearchApiUrl("%", 3))
        assertEquals("Foo Bar s 2026", PelisJuanitaMarkup.normalizeSearchQuery("  Foo & Bar's   2026 "))
        assertEquals("https://pelisjuanita.com/movies/search?s=casa+de+papel", PelisJuanitaMarkup.buildSearchPageUrl("casa & de'papel", 1))
        assertEquals("https://pelisjuanita.com/series/page/2/search?s=casa", PelisJuanitaMarkup.buildSearchPageUrl("casa", 2))
        assertEquals("https://pelisjuanita.com/movies/apiSeries.php?s=casa+libre&page=3", PelisJuanitaMarkup.buildUnifiedSearchAjaxUrl("casa & libre", 3))
        assertEquals("https://pelisjuanita.com/movies/pelicula/madden", PelisJuanitaMarkup.buildMovieShellUrl("madden"))
        assertEquals("https://pelisjuanita.com/movies/movieInfo.php?title=culpa-nuestra&p=", PelisJuanitaMarkup.buildMovieInfoUrl("culpa-nuestra"))
        assertEquals("https://pelisjuanita.com/movies/movieInfo.php?title=madden&p=55775", PelisJuanitaMarkup.buildMovieInfoUrl("madden", 55775L))
        assertEquals(55775L, PelisJuanitaMarkup.extractMovieIdFromShell("""<script>let u = `movieInfo.php?title=` + encodeURIComponent(title) + `&p=55775`;</script>"""))
        assertEquals("https://pelisjuanita.com/series/ver-serie/the-bads-of-bollywood", PelisJuanitaMarkup.buildSerieShellUrl("the-bads-of-bollywood"))
        assertEquals("https://pelisjuanita.com/series/serieInfo.php?nombreSerie=mobland&nroTemporada=1&nroEpisodio=3&p=7650", PelisJuanitaMarkup.buildSerieInfoEpisodeUrl("mobland", 1, 3, 7650L))
        assertEquals("https://pelisjuanita.com/series/serieInfo.php?nombreSerie=mobland&temporada=2&snum=1&enum=1&p=7650", PelisJuanitaMarkup.buildSerieInfoSeasonUrl("mobland", 2, 1, 1, 7650L))
        assertEquals("https://pelisjuanita.com/series/serieInfo.php?nombreSerie=mobland&nroTemporada=1&nroEpisodio=0&modoTemporada=1&p=7650", PelisJuanitaMarkup.buildSerieInfoSeasonModeUrl("mobland", 1, 7650L))

        val movieTarget = PelisJuanitaMarkup.parseTargetUrl("https://pelisjuanita.com/movies/pelicula/culpa-nuestra")
        assertNotNull(movieTarget)
        assertFalse(movieTarget!!.isSeries)
        assertEquals("culpa-nuestra", movieTarget.slug)
        assertEquals("https://pelisjuanita.com/movies/pelicula/culpa-nuestra", movieTarget.publicUrl)

        val epTarget = PelisJuanitaMarkup.parseTargetUrl("https://pelisjuanita.com/series/ver-serie/mobland/02x05")
        assertNotNull(epTarget)
        assertTrue(epTarget!!.isSeries)
        assertEquals("mobland", epTarget.slug)
        assertEquals(2, epTarget.season)
        assertEquals(5, epTarget.episode)
        assertEquals("https://pelisjuanita.com/series/ver-serie/mobland", epTarget.parentSeriesUrl)
    }

    @Test
    fun `image resolvers handle TMDB relative paths, local snapshots and placeholder filtering`() {
        assertEquals("https://image.tmdb.org/t/p/w500/oGdv5eh69qZBws5vjIFJvvSH1Uz.jpg", PelisJuanitaMarkup.resolvePosterUrl("/oGdv5eh69qZBws5vjIFJvvSH1Uz.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w1280/uIq62I9VTGeOlZeCHZuLviyMScA.jpg", PelisJuanitaMarkup.resolveBackdropUrl("/uIq62I9VTGeOlZeCHZuLviyMScA.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w500/bRa83oPFFPqaAF3wrpVWPr4z.jpg", PelisJuanitaMarkup.resolvePosterUrl("./Ver_files/bRa83oPFFPqaAF3wrpVWPr4z.jpg"))
        assertEquals("https://image.tmdb.org/t/p/original/hero.jpg", PelisJuanitaMarkup.resolveBackdropUrl("https://image.tmdb.org/t/p/original/hero.jpg"))
        assertNull(PelisJuanitaMarkup.resolvePosterUrl("https://pelisjuanita.com/logo-share.png"))
        assertNull(PelisJuanitaMarkup.resolvePosterUrl("/no-actor.jpg"))
        assertNull(PelisJuanitaMarkup.resolveBackdropUrl(""))
        assertNull(PelisJuanitaMarkup.resolveBackdropUrl("https://image.tmdb.org/t/p/original"))
        assertNull(PelisJuanitaMarkup.resolveBackdropUrl("https://image.tmdb.org/t/p/w1280/"))
        assertNull(PelisJuanitaMarkup.resolveBackdropUrl("https://image.tmdb.org/t/p/w500/null"))
        assertNull(PelisJuanitaMarkup.resolveBackdropUrl("https://pelisjuanita.com/movies/"))
    }

    @Test
    fun `parseCatalogHtml extracts movies, series episodes and pagination state`() {
        val moviesHtml = """
            <div class="grid-container">
              <div class="grid-item">
                <a href="/movies/pelicula/madden">
                  <div class="hover-info"><div class="left">★ 7.4</div><div class="right">2026</div></div>
                  <img class="img-cover" data-src="https://image.tmdb.org/t/p/w500/madden.jpg" alt="Ver Película Madden Online"/>
                  <h2>Madden</h2>
                </a>
              </div>
              <div class="grid-item">
                <a href="https://pelisjuanita.com/series/ver-serie/mobland">
                  <div class="hover-info"><div class="left">★ 8.4</div><div class="right">2025</div></div>
                  <img class="img-cover" src="/mobland123.jpg"/>
                  <h2>MobLand</h2>
                </a>
              </div>
            </div>
            <div class="pagination">
              <span>Página 1 de 85</span>
              <a href="https://pelisjuanita.com/movies/estrenos/page/2">2</a>
            </div>
        """.trimIndent()

        val cat = PelisJuanitaMarkup.parseCatalogHtml(moviesHtml, currentPage = 1)
        assertEquals(85, cat.totalPages)
        assertTrue(cat.hasNext)
        assertEquals(2, cat.items.size)
        assertEquals(PelisJuanitaItemKind.Movie, cat.items[0].kind)
        assertEquals("Madden", cat.items[0].title)
        assertEquals(2026, cat.items[0].year)
        assertEquals(7.4, cat.items[0].rating!!, 0.01)
        assertEquals("https://image.tmdb.org/t/p/w500/madden.jpg", cat.items[0].posterUrl)
        assertEquals("https://pelisjuanita.com/movies/pelicula/madden", cat.items[0].url)

        assertEquals(PelisJuanitaItemKind.Series, cat.items[1].kind)
        assertEquals("MobLand", cat.items[1].title)
        assertEquals(2025, cat.items[1].year)
        assertEquals("https://image.tmdb.org/t/p/w500/mobland123.jpg", cat.items[1].posterUrl)

        val epHtml = """
            <div class="grid-container-episodios">
              <div class="grid-item-episodios">
                <a href="/series/ver-serie/the-bads-of-bollywood/01x03">
                  <img class="img-cover-episodios" src="https://image.tmdb.org/t/p/w500/bolly.jpg"/>
                  <div class="texto-tira-episodios"><h3>The Ba***ds of Bollywood</h3><span>1x03</span></div>
                </a>
              </div>
            </div>
            <div class="pagination"><span>Página 20 de 20</span></div>
        """.trimIndent()
        val epCat = PelisJuanitaMarkup.parseCatalogHtml(epHtml, currentPage = 20, isRecentEpisodes = true)
        assertFalse(epCat.hasNext)
        assertEquals(1, epCat.items.size)
        assertEquals(PelisJuanitaItemKind.Episode, epCat.items[0].kind)
        assertEquals("The Ba***ds of Bollywood 1x03", epCat.items[0].title)
        assertEquals("https://pelisjuanita.com/series/ver-serie/the-bads-of-bollywood/01x03", epCat.items[0].url)

        // busqueda.zip mixed search results HTML (.seriesTag, .fecha, /series/page/N/search?s=..., messy img quote)
        val busquedaHtml = """
            <div class="grid-container">
              <div class="grid-item">
                <a href="https://pelisjuanita.com/series/ver-serie/la-casa-del-dragon">
                  <div class="seriesTag">serie</div>
                  <div class="fecha">2022</div>
                  <img class="img-cover" '="" src="https://image.tmdb.org/t/p/w500/hotd123.jpg" alt="La Casa del Dragón"/>
                  <h2>La Casa del Dragón</h2>
                </a>
              </div>
              <div class="grid-item">
                <a href="https://pelisjuanita.com/movies/pelicula/la-casa-gucci">
                  <div class="fecha">2021</div>
                  <img class="img-cover" '="" src="/gucci123.jpg" alt="La casa Gucci"/>
                  <h2>La casa Gucci</h2>
                </a>
              </div>
            </div>
            <div class="pagination">
              <span>Página 1 de 5</span>
              <a href="https://pelisjuanita.com/series/page/2/search?s=casa">2</a>
              <a href="https://pelisjuanita.com/series/page/5/search?s=casa">5</a>
            </div>
        """.trimIndent()
        val busquedaCat = PelisJuanitaMarkup.parseCatalogHtml(busquedaHtml, currentPage = 1)
        assertEquals(5, busquedaCat.totalPages)
        assertTrue(busquedaCat.hasNext)
        assertEquals(2, busquedaCat.items.size)
        assertEquals(PelisJuanitaItemKind.Series, busquedaCat.items[0].kind)
        assertEquals(2022, busquedaCat.items[0].year)
        assertEquals("https://image.tmdb.org/t/p/w500/hotd123.jpg", busquedaCat.items[0].posterUrl)
        assertEquals(PelisJuanitaItemKind.Movie, busquedaCat.items[1].kind)
        assertEquals(2021, busquedaCat.items[1].year)
        assertEquals("https://image.tmdb.org/t/p/w500/gucci123.jpg", busquedaCat.items[1].posterUrl)

        // movies.php / apiSeries.php double-slashed TMDB poster URLs + bare //filename.jpg + malformed rejections
        val doubleSlashHtml = """
            <div class="grid-container">
              <div class="grid-item">
                <a href="https://pelisjuanita.com/movies/pelicula/the-skin-crawler-murders">
                  <img class="img-cover" src="https://image.tmdb.org/t/p/w300//1UYm1w6shyYyqFGsry2JMaq56K2.jpg" alt="The Skin Crawler Murders"/>
                  <h2>The Skin Crawler Murders</h2>
                </a>
              </div>
              <div class="grid-item">
                <a href="https://pelisjuanita.com/series/ver-serie/la-casa-alegre">
                  <img class="img-cover" src="https://image.tmdb.org/t/p/w200//abc123456.jpg" alt="La casa alegre"/>
                  <h2>La casa alegre</h2>
                </a>
              </div>
            </div>
        """.trimIndent()
        val doubleSlashCat = PelisJuanitaMarkup.parseCatalogHtml(doubleSlashHtml, currentPage = 1)
        assertEquals(2, doubleSlashCat.items.size)
        assertEquals("https://image.tmdb.org/t/p/w300/1UYm1w6shyYyqFGsry2JMaq56K2.jpg", doubleSlashCat.items[0].posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w200/abc123456.jpg", doubleSlashCat.items[1].posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w500/1UYm1w6shyYyqFGsry2JMaq56K2.jpg", PelisJuanitaMarkup.resolvePosterUrl("//1UYm1w6shyYyqFGsry2JMaq56K2.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w500/jyR9lZNJtKzjhBasj1lnfC1lwut.jpg", PelisJuanitaMarkup.resolvePosterUrl("jyR9lZNJtKzjhBasj1lnfC1lwut.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w300/jyR9lZNJtKzjhBasj1lnfC1lwut.jpg", PelisJuanitaMarkup.resolvePosterUrl("/t/p/w300//jyR9lZNJtKzjhBasj1lnfC1lwut.jpg"))
        assertNull(PelisJuanitaMarkup.resolveBackdropUrl("https://image.tmdb.org/t/p/original//"))
        assertNull(PelisJuanitaMarkup.resolvePosterUrl("https://image.tmdb.org/t/p/w500//null"))
        assertEquals(
            "https://pelisjuanita.com/movies/movieInfo.php?title=slug%20con%20espacio&p=12",
            PelisJuanitaMarkup.buildMovieInfoUrl("slug con espacio", 12L),
        )
    }

    @Test
    fun `parseSearchJson parses real buscar php movie and series records`() {
        val rawJson = """
            {
              "status": "success",
              "pagina_actual": 1,
              "por_pagina": 30,
              "total_registros": 27890,
              "total_paginas": 930,
              "datos": [
                {
                  "tipo": "pelicula",
                  "id": 55868,
                  "TMDbId": 1599329,
                  "titulo": "All for Christmas",
                  "slug": "navidapp",
                  "img_frente": "/oGdv5eh69qZBws5vjIFJvvSH1Uz.jpg",
                  "img_fondo": "/uIq62I9VTGeOlZeCHZuLviyMScA.jpg",
                  "votos": "5.0",
                  "genero": "Romance, Comedia",
                  "fecha_estreno": "2026-11-24",
                  "sinopsis": "Una presentadora de podcast."
                },
                {
                  "tipo": "serie",
                  "id": 7650,
                  "TMDbId": 247718,
                  "titulo": "MobLand",
                  "slug": "mobland",
                  "img_frente": "/hIhpno9yQiGjpuguRLuA1Zd2d7O.jpg",
                  "img_fondo": "/bgMob9999.jpg",
                  "votos": "8.4",
                  "genero": "Drama, Crimen",
                  "fecha_estreno": "2025-03-30",
                  "sinopsis": "Dos familias rivales en Londres.",
                  "temporadas": "1"
                }
              ]
            }
        """.trimIndent()

        val page = PelisJuanitaMarkup.parseSearchJson(rawJson)
        assertNotNull(page)
        assertTrue(page!!.hasNext)
        assertEquals(930, page.totalPages)
        assertEquals(2, page.items.size)

        val m = page.items[0]
        assertEquals(PelisJuanitaItemKind.Movie, m.kind)
        assertEquals("https://pelisjuanita.com/movies/pelicula/navidapp", m.url)
        assertEquals("https://image.tmdb.org/t/p/w500/oGdv5eh69qZBws5vjIFJvvSH1Uz.jpg", m.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/uIq62I9VTGeOlZeCHZuLviyMScA.jpg", m.backdropUrl)
        assertEquals(2026, m.year)
        assertEquals(5.0, m.rating!!, 0.01)
        assertEquals(listOf("Romance", "Comedia"), m.genres)

        val s = page.items[1]
        assertEquals(PelisJuanitaItemKind.Series, s.kind)
        assertEquals(7650L, s.id)
        assertEquals("https://pelisjuanita.com/series/ver-serie/mobland", s.url)
        assertEquals(8.4, s.rating!!, 0.01)
    }

    @Test
    fun `parseMovieDetailHtml and parseSeriesDetailHtml parse metadata, seasons, episodes and clean stream rows`() {
        val movieHtml = """
            <div class="columna-principal">
              <div class="Image"><img id="cpt" src="https://image.tmdb.org/t/p/original/bg.jpg"/></div>
              <div class="itemServer tooltip" onclick="cargarTrailer('https://www.youtube.com/embed/7ljcNjDeF60')">Tráiler</div>
              <div class="titleInfo">
                <div class="columna imagen"><img src="https://image.tmdb.org/t/p/w500/poster.jpg"/></div>
                <h1>Madden</h1>
                <div class="info-linea">
                  <div class="info-item"><a href="https://pelisjuanita.com/movies/release/2026">2026</a></div>
                  <div class="info-item"><span>110 min.</span></div>
                </div>
                <div class="info-linea">
                  <a href="https://www.themoviedb.org/movie/1131943"><span><b>7.4</b></span></a>
                </div>
                <p class="sinopsis">Historia del mítico entrenador de la NFL.</p>
                <p id="sGenero"><a class="badge-etiqueta">Drama</a><a class="badge-etiqueta">Deportes</a></p>
              </div>
              <div class="carousel-actores">
                <a class="carousel-actores-item" href="/movies/reparto/Nicolas+Cage">
                  <img src="https://image.tmdb.org/t/p/w185/cage.jpg"/>
                  <p><strong>Nicolas Cage</strong></p>
                  <p>Al Davis</p>
                </a>
                <a class="carousel-actores-item" href="/movies/reparto/Christian+Bale">
                  <img src="/no-actor.jpg"/>
                  <p><strong>Christian Bale</strong></p>
                  <p>John Madden</p>
                </a>
              </div>
              <div class="box-download">
                <a class="row-download" data-idioma="latino" data-tipo="stream" data-url="https://vimeus.net/e/abc" onclick="cargarServior('https://vimeus.net/e/abc')">
                  <span>Vimeus</span><span>HD</span><span></span><span>Opción 1</span>
                </a>
                <a class="row-download" data-idioma="español" data-tipo="stream" onclick="cargarServior('https://pelisplayer.fortamomar.workers.dev/embed/123')">
                  <span>Fastserver</span><span>FULLHD</span><span></span><span>Opción 2</span>
                </a>
                <a class="row-download" data-idioma="subtitulada" data-tipo="stream" data-url="https://voe.sx/e/subbed1">
                  <span>Voe</span><span>HD</span><span></span><span>Opción 1</span>
                </a>
                <a class="row-download" data-idioma="latino" data-tipo="descarga" onclick="window.open('https://1fichier.com/?nope')">
                  <span>1fichier</span><span>HD</span><span>Latino</span><span>Descargar</span>
                </a>
              </div>
              <div class="container-carousel"><div class="carousel"><article class="card">
                <a href="/movies/pelicula/draft-day"><img class="img-cover" src="/draft123.jpg"/><h2>Draft Day</h2></a>
              </article></div></div>
            </div>
        """.trimIndent()

        val detail = PelisJuanitaMarkup.parseMovieDetailHtml(movieHtml, "https://pelisjuanita.com/movies/pelicula/madden")
        assertNotNull(detail)
        assertEquals("Madden", detail!!.title)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/original/bg.jpg", detail.backdropUrl)
        assertEquals(2026, detail.year)
        assertEquals(110, detail.durationMinutes)
        assertEquals(7.4, detail.rating!!, 0.01)
        assertEquals(listOf("Drama", "Deportes"), detail.genres)
        assertEquals("https://www.youtube.com/embed/7ljcNjDeF60", detail.trailerUrl)
        assertEquals(2, detail.actors.size)
        assertEquals("Nicolas Cage", detail.actors[0].name)
        assertEquals("Al Davis", detail.actors[0].role)
        assertEquals("https://image.tmdb.org/t/p/w185/cage.jpg", detail.actors[0].imageUrl)
        assertNull(detail.actors[1].imageUrl)
        assertEquals(1, detail.recommendations.size)
        assertEquals(3, detail.streams.size)
        assertEquals("Vimeus", detail.streams[0].serverName)
        assertEquals("Latino", detail.streams[0].languageLabel)
        assertEquals("Español", detail.streams[1].languageLabel)
        assertEquals("Subtitulado", detail.streams[2].languageLabel)

        val shellHtml = "<script>window.CURRENT_SERIE_ID=7650;</script>"
        assertEquals(7650L, PelisJuanitaMarkup.extractSeriesIdFromShell(shellHtml))

        val seriesHtml = """
            <html><head><meta name="snum" content="1"/><meta name="enum" content="1"/></head><body>
            <div class="columna-principal">
              <div class="Image"><img id="cpt" src="/sbg12345.jpg"/></div>
              <div class="titleInfo">
                <div class="columna imagen"><img src="/sposter123.jpg"/></div>
                <h1>MobLand</h1>
                <div class="info-linea">
                  <div class="info-item"><a href="https://pelisjuanita.com/series/release/2025">2025</a></div>
                  <div class="info-item"><span>60 min.</span></div>
                </div>
                <div class="info-linea">
                  <a href="https://www.themoviedb.org/tv/247718"><span><b>8.9</b></span></a>
                </div>
                <p class="sinopsis">Crimen organizado en Londres.</p>
                <p id="sGenero"><a class="badge-etiqueta">Crimen</a></p>
              </div>
              <div id="temporadasDropdown">
                <div class="server-item" onclick="cargarEpisodiosDeTemporada(1)">Temporada 1</div>
                <div class="server-item" onclick="cargarEpisodiosDeTemporada(2)">Temporada 2</div>
              </div>
              <ul id="ulTemporada">
                <a class="episodio-item" href="/series/ver-serie/mobland/01x01">
                  <div class="episodio-texto">
                    <h2 class="list-title">[01x01] Stick or Twist</h2>
                    <div class="episodio-meta"><span class="episodio-info">52 m • 30/03/2025</span></div>
                    <p class="list-sinopsis">Primer episodio.</p>
                  </div>
                </a>
              </ul>
            </div></body></html>
        """.trimIndent()
        val seriesDetail = PelisJuanitaMarkup.parseSeriesDetailHtml(seriesHtml, "https://pelisjuanita.com/series/ver-serie/mobland")
        assertNotNull(seriesDetail)
        assertEquals(listOf(1, 2), seriesDetail!!.seasons)
        assertEquals(8.9, seriesDetail.rating!!, 0.01)
        assertEquals(60, seriesDetail.durationMinutes)
        assertEquals(1, seriesDetail.episodes.size)
        val ep1 = seriesDetail.episodes[0]
        assertEquals(1, ep1.season)
        assertEquals(1, ep1.episode)
        assertEquals("Stick or Twist", ep1.title)
        assertEquals(52, ep1.runtimeMinutes)
        assertEquals("30/03/2025", ep1.releaseDate)
        assertEquals("Primer episodio.", ep1.description)

        // Real serieInfo.php?nroTemporada=1&nroEpisodio=1 leaves #ulTemporada empty while JS fetches ?temporada=1 bare fragments
        val emptySeasonShellHtml = """
            <html><head><meta name="snum" content="1"/><meta name="enum" content="1"/></head><body>
            <div class="titleInfo"><h1>MobLand</h1></div>
            <div id="temporadasDropdown">
              <div class="server-item" onclick="cargarEpisodiosDeTemporada('1');"><span>Temporada 1</span></div>
              <div class="server-item" onclick="cargarEpisodiosDeTemporada('2');"><span>Temporada 2</span></div>
            </div>
            <ul class="ul-temporada" id="ulTemporada"></ul>
            </body></html>
        """.trimIndent()
        val emptyShellDetail = PelisJuanitaMarkup.parseSeriesDetailHtml(emptySeasonShellHtml, "https://pelisjuanita.com/series/ver-serie/mobland")
        assertNotNull(emptyShellDetail)
        assertTrue(emptyShellDetail!!.episodes.isEmpty())
        assertEquals(listOf(1, 2), emptyShellDetail.seasons)

        val bareSeasonFragment = """
            <a class="episodio-item visible" href="https://pelisjuanita.com/series/ver-serie/mobland/02x01">
              <div class="episodio-texto">
                <h2 class="list-title">[02x01] Regreso</h2>
                <div class="episodio-meta"><span class="episodio-info">50 m • 10/01/2026</span></div>
              </div>
            </a>
            <a class="episodio-item" href="/series/ver-serie/mobland/02x02">
              <div class="episodio-texto">
                <h2 class="list-title">[02x02] Cuenta atrás</h2>
              </div>
            </a>
        """.trimIndent()
        val parsedFragment = PelisJuanitaMarkup.parseSeasonEpisodesHtml(bareSeasonFragment, "mobland", defaultSeason = 2)
        assertEquals(2, parsedFragment.size)
        assertEquals(2, parsedFragment[0].season)
        assertEquals(1, parsedFragment[0].episode)
        assertEquals("Regreso", parsedFragment[0].title)
        assertEquals(2, parsedFragment[1].episode)
    }

    @Test
    fun `extractors parse vimeus data json, dean edwards unquoted vimeos jws, fastserver and voe f7 crypto`() {
        val vimeusHtml = """
            <html><body>
            <script id="data" type="text/json">{"embeds":[{"server":"Online","lang":"Latino","quality":"Full HD","url":"https://vimeos.net/embed-abc123.html"}]}</script>
            </body></html>
        """.trimIndent()
        val embeds = PelisJuanitaVimeusParser.parseEmbeds(vimeusHtml)
        assertEquals(1, embeds.size)
        assertEquals("https://vimeos.net/embed-abc123.html", embeds[0].url)
        assertEquals("Full HD", embeds[0].quality)

        // Packed JS emulating vimeos.net unquoted keys + dummy empty.srt track
        val vimeosPacked = """
            <script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0("1").2({3:[{4:"5://6.7/8/9.a?t=99"}],b:[{4:"/c/d.e",f:"g",h:"i"},{4:"5://6.7/j/k.l",f:"m",h:"i"}]});',23,23,'jwplayer|vplayer|setup|sources|file|https|cdn|vimeos|hls|master|m3u8|tracks|srt|empty|srt|label|Upload captions|kind|captions|subs|es|vtt|Español'.split('|')))
            </script>
        """.trimIndent()
        val vimeosParsed = PelisJuanitaVidHideParser.parse(vimeosPacked, "https://vimeos.net/embed-abc123.html")
        assertEquals(1, vimeosParsed.streams.size)
        assertEquals("https://cdn.vimeos/hls/master.m3u8?t=99", vimeosParsed.streams[0].url)
        assertEquals(1, vimeosParsed.subtitles.size)
        assertEquals("Español", vimeosParsed.subtitles[0].label)
        assertEquals("https://cdn.vimeos/subs/es.vtt", vimeosParsed.subtitles[0].url)

        val fastHtml = """
            <script>
            jwplayer("player").setup({
                file: "https://data-limit-c.solo-latino.com/abc/master.m3u8?hdnts=exp=123",
                tracks: []
            });
            </script>
        """.trimIndent()
        assertEquals(
            "https://data-limit-c.solo-latino.com/abc/master.m3u8?hdnts=exp=123",
            PelisJuanitaFastserverParser.parseStreamUrl(fastHtml),
        )

        val actual = PelisJuanitaCrypto.pbkdf2Hmac(
            "secret".toByteArray(StandardCharsets.UTF_8),
            "msg".toByteArray(StandardCharsets.UTF_8),
            1,
            32,
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal("msg\u0000\u0000\u0000\u0001".toByteArray(StandardCharsets.UTF_8))
        assertEquals(
            expected.joinToString("") { "%02x".format(it) },
            actual.joinToString("") { "%02x".format(it) },
        )

        assertEquals(32, PelisJuanitaVsembedCrypto.encryptHex("12345", "vsembed.com").length)
        assertEquals("1f5101f5dd1f5111f6aa1f6e126a1767", PelisJuanitaVsembedCrypto.generateIvHex("vsembed.com"))
        assertTrue(PelisJuanitaVsembedCrypto.generateKeyHex().startsWith("1d54a1d53c1d56e1d6811d53c1d68"))
        val roundtripCipher = PelisJuanitaVsembedCrypto.encryptHex("cjvk", "vsembed.com")
        assertEquals("cjvk", PelisJuanitaVsembedCrypto.decryptHex(roundtripCipher, "vsembed.com"))

        // Stream row fallback outside .box-download + cargarServidor typo variant + protocol-relative URL
        val altStreams = PelisJuanitaMarkup.parseStreamSourcesHtml(
            """<a class="row-download" data-idioma="subtitulada" data-tipo="stream" onclick="cargarServidor('//bysesukior.com/e/abc123')"><span>Byse</span><span>HD</span></a>""",
        )
        assertEquals(1, altStreams.size)
        assertEquals("Subtitulado", altStreams[0].languageLabel)
        assertEquals("https://bysesukior.com/e/abc123", altStreams[0].url)

        val fallbackTipoStreams = PelisJuanitaMarkup.parseStreamSourcesHtml(
            """
            <a class="row-download" data-idioma="latino" data-tipo="ayuda" data-url="https://pelisjuanita.com/ayuda.mp4"><span>Ayuda</span><span>HD</span></a>
            <a class="row-download" data-idioma="latino" data-tipo="tutorial" data-url="https://pelisjuanita.com/tutorial.mp4"><span>Tutorial</span><span>HD</span></a>
            <a class="row-download" data-idioma="latino" data-src="https://vidara.to/e/HnuAfGnH5Z97l"><span>Vidara</span><span>FULLHD</span></a>
            """.trimIndent(),
        )
        assertEquals(1, fallbackTipoStreams.size)
        assertEquals("Vidara", fallbackTipoStreams[0].serverName)
        assertEquals("https://vidara.to/e/HnuAfGnH5Z97l", fallbackTipoStreams[0].url)

        // Byse deterministic PoW check against verified vector
        val vecHash = PelisJuanitaBysePow.hashNonce("5dd6af337978c416cc2cf9a6af73daad:2892".toByteArray(StandardCharsets.UTF_8))
        assertEquals(0x000a2171, vecHash[0])
        assertEquals(0xc54bc68f.toInt(), vecHash[1])
        assertEquals(12, PelisJuanitaBysePow.countLeadingZeroBits(vecHash))
        val powSolved = PelisJuanitaBysePow.solveBlocking("5dd6af337978c416cc2cf9a6af73daad", 12)
        assertNotNull(powSolved)

        // Byse ECDSA P-256 challenge signing + JWK verification
        val kp = PelisJuanitaByseCrypto.ecKeyPairP256()
        val jwk = PelisJuanitaByseCrypto.toJwkPublicKey(kp.public as java.security.interfaces.ECPublicKey)
        assertEquals("EC", jwk["kty"])
        assertEquals("P-256", jwk["crv"])
        assertEquals(32, PelisJuanitaCrypto.decodeBase64Flexible(jwk["x"]!!).size)
        assertEquals(32, PelisJuanitaCrypto.decodeBase64Flexible(jwk["y"]!!).size)
        val sigP1363 = PelisJuanitaCrypto.decodeBase64Flexible(PelisJuanitaByseCrypto.signChallengeNonce("nonce-123", kp))
        assertEquals(64, sigP1363.size)

        // Byse AES-256-GCM playback decryption check (both legacy 2-part and version-indexed 30-part keys)
        val byseKey1 = ByteArray(16) { it.toByte() }
        val byseKey2 = ByteArray(16) { (it + 16).toByte() }
        val byseIv = ByteArray(12) { (it + 1).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            SecretKeySpec(byseKey1 + byseKey2, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, byseIv),
        )
        val plain = """{"sources":[{"url":"https://edge.bysesukior.com/hls/stream.m3u8","height":1080,"bitrate_kbps":2400}],"tracks":[{"url":"https://edge.bysesukior.com/subs/es.vtt","label":"Español"}]}""".toByteArray(StandardCharsets.UTF_8)
        val enc = cipher.doFinal(plain)
        fun b64Url(b: ByteArray) = PelisJuanitaCrypto.encodeBase64(b).replace('+', '-').replace('/', '_').trimEnd('=')
        assertEquals(
            "https://edge.bysesukior.com/hls/stream.m3u8",
            PelisJuanitaByseCrypto.decryptPlayback(b64Url(byseIv), b64Url(enc), listOf(b64Url(byseKey1), b64Url(byseKey2))),
        )
        val thirtyParts = MutableList(30) { idx -> b64Url(ByteArray(16) { (idx + 70).toByte() }) }
        // version = "16" selects indices [16-1=15] and [30-16=14]
        thirtyParts[15] = b64Url(byseKey1)
        thirtyParts[14] = b64Url(byseKey2)
        val verDetails = PelisJuanitaByseCrypto.decryptPlaybackDetails(b64Url(byseIv), b64Url(enc), thirtyParts, version = "16")
        assertNotNull(verDetails)
        assertEquals("https://edge.bysesukior.com/hls/stream.m3u8", verDetails!!.sources[0].url)
        assertEquals(1, verDetails.tracks.size)
        assertEquals("Español", verDetails.tracks[0].label)
    }

    @Test
    fun `vsembed su routing, subtitle unwrapping, config parsing and live wasm chacha20 decryption`() {
        // 1. Distinguish VidSrc embeds (vsembed.su / vidsrc-embed.ru / etc.) from StreamWish (vsembed.com/e/...)
        val bigGirlsEmbedUrl =
            "https://vsembed.su/embed/movie?tmdb=1477963&ds_lang=es&sub_url=https%3A%2F%2Fpelisjuanita.com%2Fvidsrc%2Fcorsproxy.php%3Furl%3Dhttps%253A%252F%252Fwww.subtitlecat.com%252Fsubs%252F1812%252FBig.Girls.Dont.Cry.2026.1080p.WEBRip.x264.AAC-%255BYTS.GG%2520-%2520YTS.BZ%255D-es-419.srt&sub_label=Espa%C3%B1ol&sub_lang=es&utm_source=pelisjuanita.com"
        assertTrue(PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl(bigGirlsEmbedUrl))
        assertTrue(PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl("https://vsembed.su/embed/tv?tmdb=247718&season=1&episode=1&ds_lang=es"))
        assertTrue(PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl("https://vidsrc-embed.ru/embed/movie/1477963"))
        assertTrue(PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl("https://vsrc.su/embed/movie?tmdb=123"))
        assertFalse(PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl("https://vsembed.com/e/cjvk"))
        assertFalse(PelisJuanitaVidSrcSuParser.isVidsrcEmbedUrl("https://streamwish.to/e/abc123"))

        // Server name inference for iframe fallback keeps Vidsrc vs Streamwish distinct
        val iframeFallbacks = PelisJuanitaMarkup.parseStreamSourcesHtml(
            """<iframe id="if-video" src="$bigGirlsEmbedUrl"></iframe>""",
        )
        assertEquals(1, iframeFallbacks.size)
        assertEquals("Vidsrc", iframeFallbacks[0].serverName)
        val wishIframeFallbacks = PelisJuanitaMarkup.parseStreamSourcesHtml(
            """<iframe id="if-video" src="https://vsembed.com/e/cjvk"></iframe>""",
        )
        assertEquals("Streamwish", wishIframeFallbacks[0].serverName)

        // Real PelisJuanita .row-download markup unescapes &amp; cleanly
        val realRowHtml = """
            <div class="box-download">
              <div class="row-download" data-idioma="subtitulada" data-tipo="stream" data-url="https://vsembed.su/embed/movie?tmdb=1477963&amp;ds_lang=es&amp;sub_url=https%3A%2F%2Fpelisjuanita.com%2Fvidsrc%2Fcorsproxy.php%3Furl%3Dhttps%253A%252F%252Fwww.subtitlecat.com%252Fsubs%252F1812%252FBig.Girls.Dont.Cry.2026.1080p.WEBRip.x264.AAC-%255BYTS.GG%2520-%2520YTS.BZ%255D-es-419.srt&amp;sub_label=Espa%C3%B1ol&amp;sub_lang=es&amp;utm_source=pelisjuanita.com">
                <span>Vidsrc</span><span>HD</span><span></span><span>Opción 1</span>
              </div>
            </div>
        """.trimIndent()
        val parsedRows = PelisJuanitaMarkup.parseStreamSourcesHtml(realRowHtml)
        assertEquals(1, parsedRows.size)
        assertEquals("Vidsrc", parsedRows[0].serverName)
        assertEquals("Subtitulado", parsedRows[0].languageLabel)
        assertEquals(bigGirlsEmbedUrl, parsedRows[0].url)

        // 2. Subtitle extraction + corsproxy unwrapping + brackets/spaces percent normalization
        val subs = PelisJuanitaVidSrcSuParser.parseSubtitlesFromEmbedUrl(bigGirlsEmbedUrl)
        assertEquals(1, subs.size)
        assertEquals("Español", subs[0].label)
        assertEquals(
            "https://www.subtitlecat.com/subs/1812/Big.Girls.Dont.Cry.2026.1080p.WEBRip.x264.AAC-%5BYTS.GG%20-%20YTS.BZ%5D-es-419.srt",
            subs[0].directUrl,
        )
        assertTrue(subs[0].rawUrl.startsWith("https://pelisjuanita.com/vidsrc/corsproxy.php?url="))

        // 3. Step 1 (data-api / vs_src.php) + Landing CFG.playerUrl parsing
        val vsembedHtml = """
            <div data-api="/vs_src.php?type=movie&amp;id=1477963&amp;ds_lang=es"></div>
            <script>window.CFG = {"tvIndex":false};</script>
        """.trimIndent()
        val step1 = PelisJuanitaVidSrcSuParser.parseStep1(vsembedHtml, bigGirlsEmbedUrl)
        assertNotNull(step1)
        assertEquals("https://vsembed.su/vs_src.php?type=movie&id=1477963&ds_lang=es", step1!!.apiPath)
        assertNull(PelisJuanitaVidSrcSuParser.parsePlayerUrlFromLanding(vsembedHtml, bigGirlsEmbedUrl))

        val landingHtml = """
            <script>window.CFG = {"metaApi":"https://data.vidsrc.sh/api.php?type=movie\u0026tmdb=1477963","playerUrl":"/embed/player/movie/1477963?vs=abc123"};</script>
        """.trimIndent()
        assertEquals(
            "https://stellarconductornexus.com/embed/player/movie/1477963?vs=abc123",
            PelisJuanitaVidSrcSuParser.parsePlayerUrlFromLanding(landingHtml, "https://stellarconductornexus.com/embed/movie/1477963?vs=xyz"),
        )

        // 4. Open residential CONFIG (apiToken present, \u0026 escapes) & Gated TV CONFIG (\& escapes)
        val openMovieConfigHtml = """
            <script>
            window.CONFIG = {"mediaType":"movie","idType":"tmdb","mediaId":"1477963","tmdb":"1477963","dsLang":"es","subLabel":"Espa\u00f1ol","subLang":"es","subUrl":"https://pelisjuanita.com/vidsrc/corsproxy.php?url=https%3A%2F%2Fwww.subtitlecat.com%2Fsubs%2F1812%2Ftest.srt","api":"https://data.vidsrc.sh/api.php?type=movie\u0026tmdb=1477963\u0026stream_urls","metaApi":"https://data.vidsrc.sh/api.php?type=movie\u0026tmdb=1477963","season":null,"episode":null,"apiToken":"tok_open_123"};
            </script>
        """.trimIndent()
        val openCfg = PelisJuanitaVidSrcSuParser.parsePlayerConfig(
            openMovieConfigHtml,
            "https://stellarconductornexus.com/embed/player/movie/1477963?vs=abc",
        )
        assertNotNull(openCfg)
        assertFalse(openCfg!!.turnstile)
        assertEquals("tok_open_123", openCfg.apiToken)
        assertEquals(
            "https://birchandbay.space/api.php?type=movie&tmdb=1477963&stream_urls&api_token=tok_open_123",
            PelisJuanitaVidSrcSuParser.buildStreamApiUrl(openCfg, "birchandbay.space"),
        )

        val tvConfigHtml = """
            <script>
            window.CONFIG = {"mediaType":"tv","tv":true,"streamBase":"https://data.vidsrc.sh/api.php?type=tv\&tmdb=247718","metaApi":"https://data.vidsrc.sh/api.php?type=tv\&tmdb=247718\&season=1\&episode=1","season":1,"episode":1,"turnstile":true,"apiToken":""};
            </script>
        """.trimIndent()
        val tvCfg = PelisJuanitaVidSrcSuParser.parsePlayerConfig(
            tvConfigHtml,
            "https://stellarconductornexus.com/embed/player/tv/247718/1/1?vs=tv",
        )
        assertNotNull(tvCfg)
        assertTrue(tvCfg!!.turnstile)
        assertEquals("", tvCfg.apiToken)
        assertEquals("https://data.vidsrc.sh/api.php?type=tv&tmdb=247718", tvCfg.streamBase)
        assertEquals(
            "https://birchandbay.space/api.php?type=tv&tmdb=247718&season=1&episode=1&stream_urls",
            PelisJuanitaVidSrcSuParser.buildStreamApiUrl(tvCfg, "birchandbay.space"),
        )

        // 5. Real captured wasm.php (7,334 B) + real captured stream_urls ChaCha20 decryption
        val wasmZlibB64 =
            "eNrtWf1XFNcZvvOxM7PcYXc2UdAVw50KqAWCH4hKVBjiEU9JpMaviBhAoNEBxbhoNGAXUz9IFYzGRDhZAzXB6Kk1NEpCbDUQ" +
                "SSR+FEJOFMEPjKJmpWIQTawH7b2zU7PsyX/Q+WHvzPPuM8/c973PT8+ADMdSCgBA2Zh0ykk502mnE5CFcjI8R/4AtImhAMtJ" +
                "tJNSihDE60+9JZ9ykB/NcUuzl+atWEMD1pGfkQ8Au3zJspcBxTqys7Pwcxm5uXmZgOWzsjNXrFmeD0x+b43hZlO0U7nV3tkN" +
                "ZJqOZ+IRpTSDZH8K0Yr4KqIQUB0yjQGTq1R3vVfGqTKtVDKYFfeKistBq2MBwIhSZUoEECIaAQfURIvryx5Rj0XbdVG7j+iZ" +
                "Le42nog2/CIa8Kuis4ho+99ajrCPRZFHc4CP5ubWjvXaRut+0aR/VXPP05TkVDpv12/mMP1W47pyRmaUj9uKT/Iyq/SeWHeO" +
                "k00KGEEDpWgwXh0yp7AErfcgXhEIKvYgQREJ2uJBZkUi6E0P8lMGELTDg6AyiKAyDxKVIIJcHuSPgGxREL6XrUoIuUjKCHKx" +
                "4T2TtpAF0Q5FelW2ID9kUWU/xCE/Bz4umevHEHwZPGEwCI+bQVbEaBpWBJFVlSHiEdQ0+H4MwZfBEwaLBFVmkYRYTUNCIpJU" +
                "WUQCEjUNoR9D8GXwhGFCZlU2IRsyaRo25I9sKm7ejPw1DXM/huDL4AmDJjulMYPWNUTCEPFORb0Xb4bgy+A93QqkW4s+Dwt+" +
                "i4W8RdD3IfRjCL4M3tOtmXRr1edhxVO3kqmb9XMx92MIvgze0y1HupX0eUh46hKZOqefC9ePIfgyeN/TN/xh+MPwh+EPwx+G" +
                "Pwx/GP4w/GH4w/CH4Q/DH4Y/DH8Y/jD8Yfjj/80flAJIFqzlvWoMTfJgVkWMJ/jVC4KKWE8CrBdEFZk8UbBekFTEeWfCenmA" +
                "injvcFgvD1KR4J0S6+UgFZm942K9jFTk550b6+UQFUHvAFkvj1CR6J0k6+UIFfl7R8p6eZSKzwjoIFpFVi1k1vEEFUla2qzj" +
                "SSqyabEzwXAcRTmHAZkaBrBACKDjh4E43H4Rm/u8P1CoxyiHrMvUeGCHiIKHKIpzKgiRWWEVJQQBPG8djMBA0AG+FYkN8bBz" +
                "ZEYBMot/Ji3kNyGGhPysUgckBlvNlIPPkcMoiY3Hq8xBTOU1Ko847XsAMuGTUP93jQQA8zw3DvINgCffAHjtGwBLbll8vHUA" +
                "O0crMRBWcBAoAKKTc8LPb5r5bRXfeKsqePHx9JpHe5dsPHAjpz56aBOT17Mdt8zDRWNXXTHl72u5VDBobULf3Arz9emnB576" +
                "01tNfzxTM3PV2FJx+O6z1w4f67nbd97V5ny9+wt3bc3EpN7oae7jaTFN3a6pieEXt1Wh3dF/LbR+ZQuNNCUdfG9H8sYeauHk" +
                "Nx4erZqy4RsKvycQpv1meOG2LWzjgwUvWuvc7tPVfllXF3y2f1fEuSdTqi+vjL08t9KVv7Z54/6wGUNrQ2FxvFTW8bH/Lpdp" +
                "esypf1ctuX102YsHtg+Zap85MKA66qMNLeqbzdfS0uZXu2edzaPL7WZiZohW3klqaKn7/snLTA319w+fK2NTx463TJ7ffTaq" +
                "nn7qmPUlzLLAKbNKLpRsfYD+9foPEY1TXnI/87Ny8IWu+rxpP+3a9n1CwhlzX3XrPSGAurFzwp3X6kuSmgND0zqHx81LTb4b" +
                "OLWkG2sEw5jwnr7aK80PV38wv5ctjfzyozX2+iLTH+zXDzv/UTNn1Im3I28wobUPE9elrv5ahEOVAufIe5/MKsfPQjg1UCjN" +
                "ud1VDn4sYua6Rp2nWhe5Zicv2hPTGhw45s/DU4I2yVvGPZEG7JVHYuXF66PSVk0qrCzItC78y4W9C3t3CuVHF1aexkom+HLs" +
                "dxfnos6k/zzYPqniuCtpXcTno4NA07Pnwh5dPDDGvBvt65hWGJFI5aQ8qPtty82u2e19bdfKt8ZGR12va0BlNcfn/B61nHr6" +
                "amld87Q4sfPb/XZY0XJzeXBTxqjpqYOo2bmtzxYVpLx2Ab9sCEyxd9x/ovbGUPeRPRN/R3dEvR976RXbqbfv7/jknZG3gko6" +
                "3J/3TRiohkbG3BzinzLy56u7IgIaTmaXPJSmxF1hYeH49AX3v+iZAeecPnMwd+zk8KCNMzLHLR2cGNb+9RGsL0F50fRnwucd" +
                "K20c/O5oJW/Aoe/UrJVdMUL24fGuDFt6SG7eYkzzgwnRWZsbVsxLbivoCtmwN6w2cXxke/TWmfHtTyWs3Xmxe1LFvW1dEzft" +
                "DToUcqI4858BZ5Pf+DH50rCcO/vuxoatDE7lOsTi/wJR3IaL"
        val wasmBytes = java.util.zip.InflaterInputStream(
            java.io.ByteArrayInputStream(PelisJuanitaCrypto.decodeBase64Flexible(wasmZlibB64)),
        ).use { it.readBytes() }
        assertEquals(7334, wasmBytes.size)

        val liveApiJson = """{"status_code":"200","data":{"stream_urls_api_host":"birchandbay.space","stream_urls":"bO/o5G8DG4B1qgHH1Mti/OBJdLKbhfv1qfMKICKtQYO8buT/enzZug31E3LWYSgkO5PjvD5JnsvdA/+3hgocURu1WqFRh+S3kSxyzcHERAmY9SnARtq/7fY69o4C+FY9u+UIruLQ51//o92v++bulFPfh31qwp3Lya47X7hbiiUxCLmrdwXsXz3yVKIDpuIW3qFG7594pJCWlcSxaTtohY138XrxgKSEF0GDZqWRIhrVS0zKH0sQYwobvKwrV7aN0SHr7NCr+Y7qyvl/7Q6v4KleWPyJxbLNCh7AX80JQ+gfEN1cCV2E4rYPNLnAOrCHaM1SuIfzs9XCOtOZL2WYIhM=","cast_urls":["https://birchandbay.space/plc/3K-0Z15rcjYcD4Up3FdTYNcPIw77H8TVvwl2BRbT5-RO03NdXU0UGrAePpfEevWpaXjRd1Lqg_sFJ_xGHTVLwSZeuUrlew","https://birchandbay.space/plc/KlVwv0svngNc0Eyb06P8Xj94Zze_cqA7Vg-mpcFgtYiSG_8FWbJ_GElVnEuMIN4BPBMb08xHP6-mbes5ksWTinwbBTtA"]},"vs":{"wasm_url":"/wasm.php?w=5985796"}}"""
        val apiData = PelisJuanitaVidSrcSuParser.parseStreamApiResponse(liveApiJson, "https://data.vidsrc.sh/api.php")
        assertNotNull(apiData)
        assertEquals("https://data.vidsrc.sh/wasm.php?w=5985796", apiData!!.wasmUrl)
        val decrypted = PelisJuanitaWasmChaCha.decryptStreamUrls(apiData.encryptedStreamUrls, wasmBytes)
        assertEquals(
            listOf(
                "https://birchandbay.space/pl/3K-0Z15rcjYcD4Up3FdTYNcPIw77H8TVvwl2BRbT5-RO03NdXU0UGrAePpfEevWpaXjRd1Lqg_sFJ_xGHTVLwSZeuUrlew",
                "https://birchandbay.space/pl/KlVwv0svngNc0Eyb06P8Xj94Zze_cqA7Vg-mpcFgtYiSG_8FWbJ_GElVnEuMIN4BPBMb08xHP6-mbes5ksWTinwbBTtA",
            ),
            decrypted,
        )

        // 6. Media3 /pl/ -> /pli/ conversion & token parser (JSON and raw text)
        assertEquals("jwt-tok", PelisJuanitaVidSrcSuParser.parseTokenResponse("""{"token":"jwt-tok"}"""))
        assertEquals("raw-tok", PelisJuanitaVidSrcSuParser.parseTokenResponse(" raw-tok \n"))
        assertEquals(
            "https://birchandbay.space/pli/3K-0Z15rcjYcD4Up3FdTYNcPIw77H8TVvwl2BRbT5-RO03NdXU0UGrAePpfEevWpaXjRd1Lqg_sFJ_xGHTVLwSZeuUrlew?token=jwt-tok",
            PelisJuanitaVidSrcSuParser.toMedia3PlaylistUrl(decrypted[0], "jwt-tok"),
        )
        assertEquals(
            "https://birchandbay.space/pli/3K-0Z15rcjYcD4Up3FdTYNcPIw77H8TVvwl2BRbT5-RO03NdXU0UGrAePpfEevWpaXjRd1Lqg_sFJ_xGHTVLwSZeuUrlew?token=jwt-tok",
            PelisJuanitaVidSrcSuParser.toMedia3PlaylistUrl(apiData.castUrls[0], "jwt-tok"),
        )
        assertEquals(
            "https://birchandbay.space/pli/3K-0Z15rcjYcD4Up3FdTYNcPIw77H8TVvwl2BRbT5-RO03NdXU0UGrAePpfEevWpaXjRd1Lqg_sFJ_xGHTVLwSZeuUrlew?token=abc",
            PelisJuanitaVidSrcSuParser.rewriteInterceptedPlaylistUrl(
                "https://birchandbay.space/pl/3K-0Z15rcjYcD4Up3FdTYNcPIw77H8TVvwl2BRbT5-RO03NdXU0UGrAePpfEevWpaXjRd1Lqg_sFJ_xGHTVLwSZeuUrlew?token=abc",
            ),
        )
    }
}
