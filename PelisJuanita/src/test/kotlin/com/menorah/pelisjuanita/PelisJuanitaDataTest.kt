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
        assertEquals("TestAgent/1.0", ajaxHeaders["User-Agent"])
        assertEquals("PHPSESSID=sess42; cf_clearance=clear1", ajaxHeaders["Cookie"])
        assertEquals("XMLHttpRequest", ajaxHeaders["X-Requested-With"])
        assertEquals("https://pelisjuanita.com/movies/", ajaxHeaders["Referer"])

        val gateHtml = """<html><form method="POST"><input name="empresa_hp"><input name="captcha"></form></html>"""
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(302, "https://pelisjuanita.com/inicio.php", ""))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(200, "https://pelisjuanita.com/inicio.php", gateHtml))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(403, "https://pelisjuanita.com/movies/", "Verificación de Seguridad"))
        assertFalse(PelisJuanitaSessionHelper.looksLikeVerificationGate(200, "https://pelisjuanita.com/movies/", "<div id=\"dt_contenedor\"><div class=\"grid-container\"></div></div>"))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerifiedCatalogOrDetail("https://pelisjuanita.com/movies/", "<div id=\"dt_contenedor\"><div class=\"grid-item\"></div></div>"))
        assertTrue(PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(403, "<html><title>Just a moment...</title></html>"))
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
        assertEquals("https://pelisjuanita.com/movies/movieInfo.php?title=culpa-nuestra&p=", PelisJuanitaMarkup.buildMovieInfoUrl("culpa-nuestra"))
        assertEquals("https://pelisjuanita.com/series/ver-serie/the-bads-of-bollywood", PelisJuanitaMarkup.buildSerieShellUrl("the-bads-of-bollywood"))
        assertEquals("https://pelisjuanita.com/series/serieInfo.php?nombreSerie=mobland&nroTemporada=1&nroEpisodio=3&p=7650", PelisJuanitaMarkup.buildSerieInfoEpisodeUrl("mobland", 1, 3, 7650L))
        assertEquals("https://pelisjuanita.com/series/serieInfo.php?nombreSerie=mobland&temporada=2&snum=1&enum=1&p=7650", PelisJuanitaMarkup.buildSerieInfoSeasonUrl("mobland", 2, 1, 1, 7650L))

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
    }
}
