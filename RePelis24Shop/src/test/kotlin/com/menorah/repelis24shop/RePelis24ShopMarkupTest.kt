package com.menorah.repelis24shop

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RePelis24ShopMarkupTest {
    private fun site(path: String = "/pelicula/la-odisea/") =
        "https://repelis24.shop" + path

    private fun doc(html: String, url: String = site()): Document = Jsoup.parse(html, url)

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------- catalog

    @Test
    fun catalogCardsUsePosterTitleAndProtocolRelativeImages() {
        val document = doc(
            """
            <div class="movie-item">
              <a href="https://repelis24.shop/pelicula/pacifico/">
                <div class="item-picture"><figure>
                  <img alt="Poster Pacífico" class="poster" loading="lazy" src="//repelis24.shop/cdn/p/v2/w1/3fNX6GcqmjFqzuc01YDmmJVRrxv2s.webp"/>
                  <span class="year text-center">2026</span>
                </figure></div>
                <div class="card-hover"><div class="card-hover-backdrop" data-backdrop="//repelis24.shop/cdn/b/v2/w1/wmg99dGbtSlZ9Lk.webp"></div></div>
                <div class="item-detail"><p>Pacífico</p></div>
              </a>
            </div>
            <div class="movie-item">
              <a href="https://repelis24.shop/serie/ncis-origins-zjcb/">
                <div class="item-picture"><figure><img alt="Poster NCIS: Origins" class="poster" src="/cdn/p/ncis.webp"/></figure></div>
                <div class="item-detail"><p>NCIS: Origins</p></div>
              </a>
            </div>
            <div class="movie-item"><a href="https://otro.example/pelicula/falsa/"><div class="item-detail"><p>Falsa</p></div></a></div>
            <div class="movie-item"><a href="/busqueda/iron"><div class="item-detail"><p>Sin tipo</p></div></a></div>
            """.trimIndent(),
            site("/peliculas/")
        )

        val cards = RePelis24ShopMarkup.cards(document)

        assertEquals(2, cards.size)
        assertTrue(cards[0].movie)
        assertEquals("Pacífico", cards[0].title)
        assertEquals("https://repelis24.shop/pelicula/pacifico/", cards[0].url)
        assertEquals("https://repelis24.shop/cdn/p/v2/w1/3fNX6GcqmjFqzuc01YDmmJVRrxv2s.webp", cards[0].poster)
        assertEquals("https://repelis24.shop/cdn/b/v2/w1/wmg99dGbtSlZ9Lk.webp", cards[0].background)
        assertFalse(cards[1].movie)
        assertEquals("https://repelis24.shop/cdn/p/ncis.webp", cards[1].poster)
    }

    @Test
    fun cardTitleFallsBackToImageAlt() {
        val document = doc(
            """<div class="movie-item"><a href="/serie/from-zeqm/"><img class="poster" alt="Poster From" src="//repelis24.shop/a.webp"/></a></div>""",
            site("/series/")
        )
        assertEquals(listOf("Poster From"), RePelis24ShopMarkup.cards(document).map { it.title })
    }

    @Test
    fun paginationUsesThemesPathAndNextLinkOnly() {
        assertEquals("/peliculas", RePelis24ShopMarkup.pageUrl("/peliculas/", 1))
        assertEquals("/peliculas/page/3", RePelis24ShopMarkup.pageUrl("/peliculas/", 3))
        assertEquals("/peliculas/estrenos/page/2", RePelis24ShopMarkup.pageUrl("/peliculas/estrenos", 2))
        val withNext = doc(
            """<a class="page-link" href="/peliculas/page/2">2</a><a class="next page-numbers" href="https://repelis24.shop/peliculas/page/2">Siguiente</a>""",
            site("/peliculas/")
        )
        assertTrue(RePelis24ShopMarkup.hasNext(withNext))
        val withoutNext = doc("""<a class="page-link" href="/peliculas/page/1">1</a>""", site("/peliculas/page/2/"))
        assertFalse(RePelis24ShopMarkup.hasNext(withoutNext))
        val foreign = doc("""<a class="next page-numbers" href="https://otro.example/peliculas/page/2">Siguiente</a>""")
        assertFalse(RePelis24ShopMarkup.hasNext(foreign))
    }

    @Test
    fun searchUrlsEncodeSegmentsWithoutFormEncoding() {
        assertEquals("/busqueda/la%20casa", RePelis24ShopMarkup.searchUrl("la casa"))
        assertEquals("/busqueda/la%20casa/page/2", RePelis24ShopMarkup.searchPageUrl("  la casa ", 2))
        assertEquals("/busqueda/rapidos%20y%20furiosos%209", RePelis24ShopMarkup.searchUrl("rapidos y furiosos 9"))
        assertEquals("/busqueda/100%25%20lobo", RePelis24ShopMarkup.searchUrl("100% lobo"))
        assertEquals("a+b", RePelis24ShopMarkup.decodeSegment("a%2Bb"))
    }

    // ------------------------------------------------------------- details

    @Test
    fun movieDetailsReadHeroBackdropBlock() {
        val document = doc(
            """
            <div class="backdrop">
              <article class="backdrop-info" itemtype="http://schema.org/Movie">
                <figure class="poster"><img alt="poster" itemprop="image" src="//repelis24.shop/cdn/p/v2/w1/ty7.webp"/></figure>
                <header class="info-header"><h1 class="title"><span itemprop="name"> Ver Película La Odisea </span></h1></header>
                <footer class="info-footer">
                  <p class="meta"><span itemprop="duration"> 2h 53min </span><span itemprop="datePublished"> 2026</span></p>
                  <ul class="info-list">
                    <li><strong>Géneros:</strong> <a href="/genero/accion/"><span itemprop="genre">Acción, </span></a><a href="/genero/aventura/"><span itemprop="genre">Aventura</span></a></li>
                    <li><strong>Actores:</strong> <a href="/buscar/Matt+Damon">Matt Damon, </a><a href="/buscar/Tom+Holland">Tom Holland</a></li>
                  </ul>
                </footer>
              </article>
            </div>
            <div class="description" itemprop="description"><h2>La Odisea es una pelicula gratis en HD de RePelis24 Online:</h2><p> Una epopeya mitológica que sigue la historia de Odiseo. </p></div>
            """.trimIndent()
        )

        assertTrue(RePelis24ShopMarkup.isMovie(document))
        assertEquals("La Odisea", RePelis24ShopMarkup.title(document))
        assertEquals("https://repelis24.shop/cdn/p/v2/w1/ty7.webp", RePelis24ShopMarkup.poster(document))
        assertEquals("Una epopeya mitológica que sigue la historia de Odiseo.", RePelis24ShopMarkup.plot(document))
        assertEquals(2026, RePelis24ShopMarkup.year(document))
        assertEquals(listOf("Acción", "Aventura"), RePelis24ShopMarkup.genres(document))
        assertEquals(listOf("Matt Damon", "Tom Holland"), RePelis24ShopMarkup.actors(document))
        assertEquals(173, RePelis24ShopMarkup.durationMinutes(document))
    }

    @Test
    fun seriesSlugSeasonNumbersAndEpisodeKeysAreStrict() {
        assertEquals("los-simpsons-zegw", RePelis24ShopMarkup.seriesSlug("https://repelis24.shop/serie/los-simpsons-zegw/"))
        assertEquals(36, RePelis24ShopMarkup.seasonNumber("https://repelis24.shop/temporada/los-simpsons-zegw-36/"))
        assertEquals(1, RePelis24ShopMarkup.seasonNumber("https://repelis24.shop/temporada/ncis-origins-zjcb-1/"))
        assertNull(RePelis24ShopMarkup.seasonNumber("https://repelis24.shop/temporada/los-simpsons-zegw/"))
        assertNull(RePelis24ShopMarkup.seasonNumber("https://otro.example/temporada/los-simpsons-zegw-1/"))
        assertTrue(RePelis24ShopMarkup.seasonIsSeries("https://repelis24.shop/temporada/from-zeqm-2/", "from-zeqm"))
        assertFalse(RePelis24ShopMarkup.seasonIsSeries("https://repelis24.shop/temporada/from-zeqm-other-2/", "from-zeqm"))
        assertEquals(2 to 10, RePelis24ShopMarkup.episodeKey("https://repelis24.shop/episodio/from-zeqm-2x10/"))
        assertNull(RePelis24ShopMarkup.episodeKey("https://repelis24.shop/episodio/from-zeqm-2x/"))
        assertEquals("https://repelis24.shop/temporada/ncis-origins-zjcb-3/", RePelis24ShopMarkup.seasonUrl("ncis-origins-zjcb", 3))
    }

    @Test
    fun seasonsAndEpisodesAreSortedFilteredAndDeduplicated() {
        val series = doc(
            """
            <ul>
              <li class="item-season"><p class="item-season-title"><a href="https://repelis24.shop/temporada/from-zeqm-2/"><span itemprop="name">Temporada 2</span></a></p></li>
              <li class="item-season"><p class="item-season-title"><a href="https://repelis24.shop/temporada/from-zeqm-1/"><span itemprop="name">Temporada 1</span></a></p></li>
              <li class="item-season"><p class="item-season-title"><a href="https://repelis24.shop/temporada/from-zeqm-1/"><span itemprop="name">Temporada 1</span></a></p></li>
              <li class="item-season"><p class="item-season-title"><a href="/temporada/otra-serie-1/">Otra</a></p></li>
            </ul>
            """.trimIndent(),
            site("/serie/from-zeqm/")
        )
        assertEquals(listOf(1, 2), RePelis24ShopMarkup.seasons(series).map { it.number })
        assertEquals("https://repelis24.shop/temporada/from-zeqm-1/", RePelis24ShopMarkup.seasons(series).first().url)

        val season = doc(
            """
            <div class="item-season-episodes"><a href="https://repelis24.shop/episodio/from-zeqm-2x2/" title="c2">
              <img src="//repelis24.shop/cdn/e/v2/w0/two.webp"/><span itemprop="name">Episodio 2</span><span>2x2</span></a></div>
            <div class="item-season-episodes"><a href="https://repelis24.shop/episodio/from-zeqm-2x1/" title="c1">
              <img src="//repelis24.shop/cdn/e/v2/w0/one.webp"/><span itemprop="name">Episodio 1</span><span>2x1</span></a></div>
            <div class="item-season-episodes"><a href="https://repelis24.shop/episodio/from-zeqm-3x1/"><span itemprop="name">Otra temporada</span></a></div>
            <div class="item-season-episodes"><a href="https://otro.example/episodio/from-zeqm-2x3/"><span itemprop="name">Externa</span></a></div>
            """.trimIndent(),
            site("/temporada/from-zeqm-2/")
        )
        val episodes = RePelis24ShopMarkup.episodes(season, 2)
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("Episodio 1", episodes.first().title)
        assertEquals("https://repelis24.shop/cdn/e/v2/w0/one.webp", episodes.first().poster)
    }

    @Test
    fun namedEpisodesKeepTheSiteTitle() {
        val season = doc(
            """<div class="item-season-episodes"><a href="/episodio/los-simpsons-zegw-36x1/"><span itemprop="name">¡Feliz cumpleaños, Bart!</span><span>36x1</span></a></div>""",
            site("/temporada/los-simpsons-zegw-36/")
        )
        assertEquals(listOf("¡Feliz cumpleaños, Bart!"), RePelis24ShopMarkup.episodes(season, 36).map { it.title })
    }

    // ------------------------------------------------------------- players

    @Test
    fun playersDecodeBase64ServersWithTheirAudioTab() {
        val watch = "https://nupload.my/watch/ghon0xAZXI7a3JdmVaxKeMt7kz7NLMauYU1elpYMUfJ9WA"
        val embed = "https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2Fijujdu3ylxpr"
        val document = doc(
            """
            <div class="player" id="player"><ul class="tabs-video">
              <li class="tab-video-item">
                <div><img alt="audio Español Castellano" class="tab-item-image" src="//repelis24.shop/cdn/plusdes/static/image/cas.png"/>
                <div class="tab-item-name"> Español Castellano <div style="margin-top: 1px">Calidad · HD</div></div></div>
                <ul><li data-server="${encode(watch)}" role="presentation"><span> Opción 1 · Español Castellano</span><span>Reproducir</span></li>
                    <li data-server="${encode(embed)}" role="presentation"><span> Opción 2 · Español Castellano</span><span>Reproducir</span></li></ul>
              </li>
              <li class="tab-video-item">
                <div><div class="tab-item-name"> Subtitulado <div>Calidad · HD</div></div></div>
                <ul><li data-server="${encode(watch + "sub")}" role="presentation"><span> Opción 1 · Subtitulado</span></li></ul>
              </li>
              <li class="tab-video-item">
                <div><div class="tab-item-name"> Latino <div>Calidad · HD</div></div></div>
                <ul><li data-server="${encode("https://otro.example/e/1")}" role="presentation">Opción 1 · Latino</li></ul>
              </li>
            </ul></div>
            """.trimIndent()
        )

        val options = RePelis24ShopMarkup.players(document)
        assertEquals(3, options.size)
        assertEquals(listOf("Español Castellano", "Español Castellano", "Subtitulado"), options.map { it.language })
        assertEquals(watch, options[0].url)
        assertEquals(embed, options[1].url)
        assertEquals(watch + "sub", options[2].url)
    }

    @Test
    fun playersFallBackToUngroupedServersAndRejectGarbage() {
        val document = doc(
            """
            <ul>
              <li data-server="${encode("https://nupload.my/watch/abc")}">Opción 1</li>
              <li data-server="%%%no-base64%%%">Rota</li>
              <li data-server="${encode("https://otro.example/e/1")}">Ajena</li>
              <li data-server="">Vacía</li>
            </ul>
            """
        )
        val options = RePelis24ShopMarkup.players(document)
        assertEquals(1, options.size)
        assertEquals("Idioma no indicado", options.first().language)
        assertEquals("https://nupload.my/watch/abc", options.first().url)
        assertNull(RePelis24ShopMarkup.decodeServer("%%%no-base64%%%"))
        assertNull(RePelis24ShopMarkup.decodeServer(""))
    }

    @Test
    fun iframeWrapperExposesTheEmbeddedHost() {
        assertEquals(
            "https://voe.sx/e/ijujdu3ylxpr",
            RePelis24ShopMarkup.nuploadTarget("https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2Fijujdu3ylxpr")
        )
        assertEquals(
            "https://nupload.my/watch/abc",
            RePelis24ShopMarkup.nuploadTarget("https://nupload.my/watch/abc")
        )
        assertNull(RePelis24ShopMarkup.nuploadTarget("https://nupload.my/iframe/?url=https%3A%2F%2Fnupload.my%2Fwatch%2Fabc"))
        assertNull(RePelis24ShopMarkup.nuploadTarget("https://repelis24.shop/watch/abc"))
        assertNull(RePelis24ShopMarkup.nuploadTarget("https://nupload.my/watch/"))
    }

    // ------------------------------------------------------- nupload decoder

    /** Mirrors the `nupload.my` page: Base64 of decimal digits minus a fixed offset. */
    private fun encodeStream(text: String, offset: Long): String = text.map { char ->
        "\"${encode((char.code.toLong() + offset).toString())}\""
    }.joinToString(",")

    @Test
    fun nuploadWatchPageYieldsManifestAndSession() {
        val offset = 5_041_587L
        val html = """
            <script>var sYSSyXM = [${encodeStream("https://sv5.ibra.lat/", offset)}];</script>
            <script>elJfkEsGCA.forEach(function Oym(value) { ShKopVlPx += String.fromCharCode(parseInt(atob(value).replace(/\D/g,'')) - $offset); } );var sesz="TUHIv34uJAIaiBmsp4GepBTkUIkEzxqts7bXagsUQb48MrAbwgqVSjgN2ArB8R5AZhyax05n",t="0";</script>
        """.trimIndent()
        val stream = RePelis24ShopMarkup.nuploadStream(html, "https://nupload.my/watch/ghon0xAZXI7a3JdmVaxKeMt7kz7NLMauYU1elpYMUfJ9WA")
        assertNotNull(stream)
        assertEquals("https://sv5.ibra.lat/", stream!!.url)
        assertEquals("TUHIv34uJAIaiBmsp4GepBTkUIkEzxqts7bXagsUQb48MrAbwgqVSjgN2ArB8R5AZhyax05n", stream.session)
        assertEquals(
            "https://sv5.ibra.lat/?s=TUHIv34uJAIaiBmsp4GepBTkUIkEzxqts7bXagsUQb48MrAbwgqVSjgN2ArB8R5AZhyax05n",
            stream.manifest()
        )
    }

    @Test
    fun nuploadDecoderRejectsForeignPagesAndMissingPieces() {
        val offset = 4_553_546L
        val html = """<script>var a = [${encodeStream("https://sv5.ibra.lat/", offset)}];
            var sesz="dAyTMKiPpITfWFZvnhX86H3rdOBKkJlyjUs6EKW3tPjqviVTAS1Gz47NTJydsTLMNdSsY";</script>"""
        assertNull(RePelis24ShopMarkup.nuploadStream(html, "https://repelis24.shop/watch/abc"))
        assertNull(RePelis24ShopMarkup.nuploadStream("<html></html>", "https://nupload.my/watch/abc"))
        // Session removed: decoding must fail instead of emitting a manifest without token.
        val withoutSession = html.replace(Regex("var sesz=\"[A-Za-z0-9]+\""), "")
        assertNull(RePelis24ShopMarkup.nuploadStream(withoutSession, "https://nupload.my/watch/abc"))
    }

    // --------------------------------------------------------- accessibility

    @Test
    fun challengePagesAreDetectedButNormalPagesAreNot() {
        val challenge = """
            <!DOCTYPE html><html><head><title>Just a moment...</title>
            <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1"></script></head><body></body></html>
        """.trimIndent()
        assertTrue(RePelis24ShopMarkup.isChallenge(challenge))
        val normal = doc(
            """<html><head><title>RePelis24 » Ver gratis películas y series online en HD</title></head>
               <body><div class="movie-item"><a href="/pelicula/x/"><div class="item-detail"><p>X</p></div></a></div></body></html>"""
        ).outerHtml()
        assertFalse(RePelis24ShopMarkup.isChallenge(normal))
    }

    // ------------------------------------------------------------ url guard

    @Test
    fun siteGuardRejectsForeignHostsSchemesAndCredentials() {
        assertTrue(RePelis24ShopMarkup.siteUrl("https://repelis24.shop/pelicula/la-odisea/"))
        assertFalse(RePelis24ShopMarkup.siteUrl("https://repelis24.shop.evil.test/pelicula/x/"))
        assertFalse(RePelis24ShopMarkup.siteUrl("http://repelis24.shop/pelicula/x/"))
        assertFalse(RePelis24ShopMarkup.siteUrl("https://evil@repelis24.shop/pelicula/x/"))
        assertFalse(RePelis24ShopMarkup.siteUrl("https://repelis24.shop:8443/pelicula/x/"))
        assertTrue(RePelis24ShopMarkup.nuploadUrl("https://nupload.my/watch/abc"))
        assertFalse(RePelis24ShopMarkup.nuploadUrl("https://nupload.my.evil.test/watch/abc"))
    }
}
