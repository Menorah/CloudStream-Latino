package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the parser against fragments copied verbatim from the site (archived because Cloudflare
 * blocks the CI runners), so a theme change breaks a test instead of the app.
 */
class PeliCineHDRealMarkupTest {
    private fun resource(name: String, base: String): Document {
        val html = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
        return Jsoup.parse(html, base)
    }

    @Test
    fun realPagesAreNotMistakenForAChallenge() {
        listOf("catalog-real.html", "detail-real.html", "series-real.html").forEach { name ->
            val raw = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
            assertFalse(name, PeliCineHDMarkup.isChallenge(raw))
        }
        // Cloudflare injects this script into ordinary pages; it is not a challenge by itself.
        assertFalse(PeliCineHDMarkup.isChallenge("<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1\"></script>"))
        assertTrue(PeliCineHDMarkup.isChallenge("<html><head><title>Just a moment...</title></head></html>"))
        assertTrue(PeliCineHDMarkup.isChallenge("<div id=\"cf-challenge-running\">"))
        assertTrue(PeliCineHDMarkup.isChallenge("<script>window._cf_chl_opt = {}</script>"))
    }

    @Test
    fun realCatalogCardsAreParsed() {
        val page = resource("catalog-real.html", "https://pelicinehd.com/peliculas/")
        val cards = PeliCineHDMarkup.cards(page)
        assertEquals(3, cards.size)
        assertTrue(cards.all { it.movie })
        assertTrue(cards.all { it.title.isNotBlank() })
        assertTrue(cards.all { it.url.startsWith("https://pelicinehd.com/movies/") })
        assertTrue(cards.all { it.poster?.startsWith("https://") == true })
        assertTrue(cards.all { it.poster != null && !it.poster.startsWith("https://pelicinehd.com/wp-content/uploads/") })
        assertEquals("Umami", cards[0].title)
        assertEquals("https://pelicinehd.com/movies/umami/", cards[0].url)
        assertTrue(PeliCineHDMarkup.hasNext(page, 1))
    }

    @Test
    fun realMovieDetailIsParsed() {
        val page = resource(
            "detail-real.html",
            "https://pelicinehd.com/movies/28-anos-despues-el-templo-de-los-huesos/"
        )
        assertEquals("28 años después: El templo de los huesos", PeliCineHDMarkup.title(page))
        assertNotNull(PeliCineHDMarkup.poster(page))
        assertNotNull(PeliCineHDMarkup.plot(page))
        assertEquals(2026, PeliCineHDMarkup.year(page))
        assertTrue(PeliCineHDMarkup.genres(page).contains("Terror"))
        assertTrue(PeliCineHDMarkup.actors(page).contains("Cillian Murphy"))
        assertEquals(109, PeliCineHDMarkup.durationMinutes(page))
        assertEquals(6.7, PeliCineHDMarkup.score(page)!!, 0.001)
        assertFalse(PeliCineHDMarkup.isSeries(page))
    }

    @Test
    fun realPlayersKeepTheirOrderAndLanguage() {
        val page = resource(
            "detail-real.html",
            "https://pelicinehd.com/movies/28-anos-despues-el-templo-de-los-huesos/"
        )
        val players = PeliCineHDMarkup.players(page)
        assertEquals(2, players.size)
        assertEquals("https://pelicinehd.com/?trembed=0&trid=18569&trtype=1", players[0].url)
        assertEquals("Latino", players[0].language)
        assertEquals("https://pelicinehd.com/?trembed=1&trid=18569&trtype=1", players[1].url)
        assertEquals("Latino", players[1].language)
    }

    @Test
    fun realSeriesDetailListsEverySeasonAndEpisode() {
        val page = resource("series-real.html", "https://pelicinehd.com/series/adios-tierra/")
        assertTrue(PeliCineHDMarkup.isSeries(page))
        assertEquals("Adiós, Tierra", PeliCineHDMarkup.title(page))

        val seasons = PeliCineHDMarkup.seasons(page)
        assertEquals(1, seasons.size)
        assertEquals(1, seasons[0].number)
        assertEquals(9486, seasons[0].post)
        assertEquals(9486, PeliCineHDMarkup.seasonsPostId(page))

        val episodes = PeliCineHDMarkup.episodes(page, 1)
        assertEquals(episodes.joinToString { "${it.season}x${it.number}" }, 11, episodes.size)
        assertEquals(1, episodes.first().number)
        // The site skipped 1x6 for this title; the parser must not renumber the rest.
        assertEquals(12, episodes.last().number)
        assertFalse(episodes.any { it.number == 6 })
        assertTrue(episodes.all { it.season == 1 })
        assertTrue(episodes.all { it.url.startsWith("https://pelicinehd.com/episode/") })
        assertEquals("https://pelicinehd.com/episode/adios-tierra-1x1/", episodes.first().url)
        assertNotNull(episodes.first().poster)
        assertEquals(2024, PeliCineHDMarkup.year(page))
    }
}
