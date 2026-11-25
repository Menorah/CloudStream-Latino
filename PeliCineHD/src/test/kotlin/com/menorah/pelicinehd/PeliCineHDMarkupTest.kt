package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas con las páginas reales del sitio guardadas en `src/test/resources`.
 * Ninguna prueba toca la red: solo comprueban el parseo del HTML del usuario.
 */
class PeliCineHDMarkupTest {
    private fun resource(name: String, base: String = "https://pelicinehd.com/"): Document {
        val html = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
        return Jsoup.parse(html, base)
    }

    // ---------------------------------------------------------------- portada

    @Test
    fun `la portada expone sus carruseles con tarjetas validas`() {
        val sections = PeliCineHDMarkup.sections(resource("inicio.html"))
        assertTrue("La portada debe tener secciones", sections.size >= 2)
        assertTrue(sections.any { it.first == "Películas Online" })
        assertTrue(sections.any { it.first == "Series Online" })
        val movies = sections.first { it.first == "Películas Online" }.second
        assertTrue(movies.isNotEmpty())
        assertTrue("Todas las tarjetas son películas", movies.all { it.movie })
        assertTrue("Todas tienen póster", movies.all { it.poster != null })
        assertTrue("Todas tienen año", movies.all { it.year != null })
        val series = sections.first { it.first == "Series Online" }.second
        assertTrue("Todas las tarjetas son series", series.all { !it.movie })
    }

    // ---------------------------------------------------------------- catálogos

    @Test
    fun `el catalogo de peliculas pagina de dos en adelante`() {
        val doc = resource("catalogo-peliculas.html", "https://pelicinehd.com/peliculas/")
        val cards = PeliCineHDMarkup.catalogue(doc)
        assertTrue("$cards", cards.size >= 30)
        assertTrue(cards.all { it.movie })
        assertTrue(cards.all { it.poster != null && it.year != null })
        assertTrue(PeliCineHDMarkup.hasNext(doc, 1))
        assertEquals(116, PeliCineHDMarkup.totalPages(doc))
    }

    @Test
    fun `el catalogo de series no mezcla peliculas`() {
        val doc = resource("catalogo-series.html", "https://pelicinehd.com/series/")
        val cards = PeliCineHDMarkup.catalogue(doc)
        assertTrue(cards.size >= 30)
        assertTrue(cards.all { !it.movie })
        assertTrue(cards.all { it.poster != null && it.year != null })
        assertTrue(PeliCineHDMarkup.hasNext(doc, 1))
        assertEquals(12, PeliCineHDMarkup.totalPages(doc))
    }

    // ---------------------------------------------------------------- fichas

    @Test
    fun `la ficha de pelicula trae metadatos y no es serie`() {
        val doc = resource("pelicula.html", "https://pelicinehd.com/movies/la-odisea/")
        assertFalse(PeliCineHDMarkup.isSeries(doc))
        val details = PeliCineHDMarkup.details(doc)!!
        assertEquals("La Odisea", details.title)
        assertNotNull(details.poster)
        assertEquals(2026, details.year)
        assertEquals(172, details.durationMinutes)
        assertEquals(listOf("Acción", "Aventura", "Fantasía"), details.genres)
        assertEquals(6.98, details.score!!, 0.001)
        assertTrue(details.plot!!.startsWith("Odiseo"))
        assertTrue(details.actors.contains("Christopher Nolan"))
        assertEquals("https://pelicinehd.com/movies/la-odisea/", PeliCineHDMarkup.canonical(doc))
    }

    @Test
    fun `la ficha de serie detecta temporadas y episodios`() {
        val doc = resource("serie.html", "https://pelicinehd.com/series/el-senor-de-los-anillos-los-anillos-de-poder/")
        assertTrue(PeliCineHDMarkup.isSeries(doc))
        val details = PeliCineHDMarkup.details(doc)!!
        assertEquals("El Señor de los Anillos: Los Anillos de Poder", details.title)
        assertEquals(2022, details.year)
        assertEquals(listOf(1, 2, 3), PeliCineHDMarkup.seasons(doc))
        assertEquals(2, PeliCineHDMarkup.currentSeason(doc))
        assertEquals("10866", PeliCineHDMarkup.postId(doc))
        val episodes = PeliCineHDMarkup.currentEpisodes(doc)
        assertEquals(8, episodes.size)
        assertTrue(episodes.all { it.season == 2 })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), episodes.map { it.episode })
        assertEquals("https://pelicinehd.com/episode/el-senor-de-los-anillos-los-anillos-de-poder-2x1/", episodes.first().url)
    }

    // ---------------------------------------------------------------- reproductores

    @Test
    fun `la ficha de pelicula enumera sus dos opciones con servidor`() {
        val doc = resource("pelicula.html", "https://pelicinehd.com/movies/la-odisea/")
        val players = PeliCineHDMarkup.players(doc)
        assertEquals(2, players.size)
        assertEquals("https://pelicinehd.com/?trembed=0&trid=19821&trtype=1", players[0].url)
        assertEquals("Morencius - LAT HD", players[0].server)
        assertEquals("Voe - LAT HD", players[1].server)
        assertEquals("Latino", PeliCineHDMarkup.language(players[0].server))
        assertEquals("Latino", PeliCineHDMarkup.language(players[1].server))
    }

    @Test
    fun `la ficha de episodio usa la opcion del capitulo`() {
        val doc = resource("episodio.html", "https://pelicinehd.com/episode/el-senor-de-los-anillos-los-anillos-de-poder-1x1/")
        val players = PeliCineHDMarkup.players(doc)
        assertEquals(1, players.size)
        assertEquals("https://pelicinehd.com/?trembed=0&trid=80650&trtype=2", players.single().url)
    }

    @Test
    fun `el wrapper descubre el host real y rechaza lo que no es https`() {
        val wrapper = javaClass.classLoader!!.getResourceAsStream("wrapper-pelicula.html")!!
            .bufferedReader().use { it.readText() }
        // Al guardar la página, el navegador reescribió el src del iframe como fichero local
        // (`./a01xmf7qgzgc.html`); el archivo del host documenta su URL original, que es la que
        // recibe el complemento en producción.
        val live = wrapper.replace("./a01xmf7qgzgc.html", "https://morencius.com/v/a01xmf7qgzgc")
        assertEquals(
            "https://morencius.com/v/a01xmf7qgzgc",
            PeliCineHDMarkup.wrappedUrl(live, "https://pelicinehd.com/?trembed=0&trid=19821&trtype=1"),
        )
        val episode = javaClass.classLoader!!.getResourceAsStream("wrapper-episodio.html")!!
            .bufferedReader().use { it.readText() }
            .replace("./ei06pqvqtfey.html", "https://morencius.com/v/ei06pqvqtfey")
        assertEquals(
            "https://morencius.com/v/ei06pqvqtfey",
            PeliCineHDMarkup.wrappedUrl(episode, "https://pelicinehd.com/?trembed=0&trid=80650&trtype=2"),
        )
        assertNull(PeliCineHDMarkup.wrappedUrl("<html><body>sin iframe</body></html>", "https://pelicinehd.com/"))
    }

    @Test
    fun `el wrapper relativo se resuelve contra su propia base`() {
        val html = "<div class=\"Video\"><iframe src=\"./a01xmf7qgzgc.html\"></iframe></div>"
        assertEquals(
            "https://pelicinehd.com/a01xmf7qgzgc.html",
            PeliCineHDMarkup.wrappedUrl(html, "https://pelicinehd.com/?trembed=0&trid=19821&trtype=1"),
        )
    }

    // ---------------------------------------------------------------- JS del host

    @Test
    fun `desempaqueta el jw player del host y obtiene el m3u8`() {
        val html = javaClass.classLoader!!.getResourceAsStream("hoster-pelicula.html")!!
            .bufferedReader().use { it.readText() }
        val script = PeliCineHDMarkup.unpack(html)!!
        assertTrue(script.startsWith("var links="))
        assertTrue(script.contains("abouttext:\"VidHide\""))
        val streams = PeliCineHDMarkup.streams(script)
        assertTrue("Debe encontrar el master.m3u8", streams.any { it.contains("master.m3u8") })
        assertTrue("El m3u8 va primero", streams.first().contains("master.m3u8"))
        assertTrue(streams.all { it.startsWith("https://") })
    }

    @Test
    fun `el host del episodio tambien se desempaqueta`() {
        val html = javaClass.classLoader!!.getResourceAsStream("hoster-episodio.html")!!
            .bufferedReader().use { it.readText() }
        val streams = PeliCineHDMarkup.streamsFromHost(html)
        assertTrue(streams.any { it.contains("/hls2/01/01071/") })
        assertTrue(streams.any { it.contains("master.m3u8") })
    }

    @Test
    fun `un html sin empaquetar no rompe el desempaquetado`() {
        assertNull(PeliCineHDMarkup.unpack("<html><body>nada</body></html>"))
        assertTrue(PeliCineHDMarkup.streamsFromHost("<html><body>nada</body></html>").isEmpty())
    }

    // ---------------------------------------------------------------- enlaces

    @Test
    fun `las rutas de catalogo usan page N solo desde la segunda pagina`() {
        // Misma regla que aplica getMainPage a las entradas con `%d`.
        fun path(template: String, page: Int): String = when {
            !template.contains("%d") -> template
            page <= 1 -> template.format(1).replace("page/1/", "")
            else -> template.format(page)
        }
        assertEquals("/peliculas/", path("/peliculas/page/%d/", 1))
        assertEquals("/peliculas/page/3/", path("/peliculas/page/%d/", 3))
        assertEquals("/series/page/2/", path("/series/page/%d/", 2))
        assertEquals("/category/terror/", path("/category/terror/page/%d/", 1))
    }

    @Test
    fun `solo se aceptan enlaces del dominio`() {
        assertTrue(PeliCineHDMarkup.siteUrl("https://pelicinehd.com/movies/la-odisea/"))
        assertTrue(PeliCineHDMarkup.siteUrl("https://www.pelicinehd.com/series/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("http://pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://pelicinehd.com.evil.test/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://evil.test/?u=pelicinehd.com"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://user:pass@pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://pelicinehd.com:8443/movies/x/"))
    }
}
