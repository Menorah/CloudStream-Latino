package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pages saved by the user from their own browser session. These are the shapes that broke in the
 * app: a movie whose page still contains `<section class="section episodes">` (which made it look
 * like a series), a series whose seasons are printed out of order, and the home page.
 */
class UserPagesTest {
    private fun resource(name: String, base: String): Document {
        val html = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
        return Jsoup.parse(html, base)
    }

    @Test
    fun `a movie page is not mistaken for a series`() {
        // The Odyssey carries an empty `<section class="section episodes">`, which used to be
        // enough to classify it as a series and end in "no episodes".
        val page = resource("movie-real-odyssey.html", "https://pelicinehd.com/movies/la-odisea/")
        assertTrue(page.select("section.section.episodes").isNotEmpty())
        assertFalse(PeliCineHDMarkup.isSeries(page))
        assertEquals("La Odisea", PeliCineHDMarkup.title(page))
        assertEquals(2, PeliCineHDMarkup.players(page).size)
        assertEquals(
            "https://pelicinehd.com/?trembed=0&trid=19821&trtype=1",
            PeliCineHDMarkup.players(page)[0].url
        )
    }

    @Test
    fun `the wrapper page yields the hoster url`() {
        val wrapper = resource("embed-wrapper-real.html", "https://pelicinehd.com/?trembed=0&trid=80650&trtype=2")
        assertEquals("https://morencius.com/v/ei06pqvqtfey", PeliCineHDMarkup.embeddedUrl(wrapper))
    }

    @Test
    fun `a series keeps all three seasons and reads them in order`() {
        // The site prints Season 2, Season 1, Season 3; position-based numbering lost the last.
        val page = resource("series-real-rings.html", "https://pelicinehd.com/series/el-senor-de-los-anillos-los-anillos-de-poder/")
        assertTrue(PeliCineHDMarkup.isSeries(page))
        val seasons = PeliCineHDMarkup.seasons(page)
        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertEquals(10866, PeliCineHDMarkup.seasonsPostId(page))
        assertTrue(seasons.all { it.post == 10866 })

        val episodes = PeliCineHDMarkup.episodes(page)
        assertTrue(episodes.isNotEmpty())
        assertTrue(episodes.all { it.season == 2 })
        assertEquals(episodes.map { it.number }.sorted(), episodes.map { it.number })
    }

    @Test
    fun `the home page lists its rows and the carousel cards`() {
        val page = resource("home-real.html", "https://pelicinehd.com/")
        val cards = PeliCineHDMarkup.cards(page)
        assertTrue("home cards ${cards.size}", cards.size > 30)
        assertTrue(cards.any { it.title == "Pacífico" })
        assertTrue(cards.all { it.url.startsWith("https://pelicinehd.com/") })
        // Nothing on this page is a real hero slider, so the fallback must not invent one.
        assertTrue(PeliCineHDMarkup.heroCards(page).isEmpty())
    }

    @Test
    fun `the user's catalogues are read as movies and as series`() {
        val movies = resource("catalog-user-real.html", "https://pelicinehd.com/peliculas/")
        val movieCards = PeliCineHDMarkup.cards(movies)
        assertTrue("movies ${movieCards.size}", movieCards.size > 30)
        assertTrue(movieCards.all { it.movie })

        val series = resource("series-catalog-user-real.html", "https://pelicinehd.com/series/")
        val seriesCards = PeliCineHDMarkup.cards(series)
        assertTrue("series ${seriesCards.size}", seriesCards.size > 20)
        assertTrue(seriesCards.all { !it.movie })
    }
}
