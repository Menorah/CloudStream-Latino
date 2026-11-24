package com.menorah.sololatino

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SoloLatinoDetailsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    @Test fun suggestionsExcludePeopleAndKeepMoviesAndAnime() {
        val result = SoloLatinoDetails.suggestions(fixture("suggest.json"))
        assertEquals(6, result.size)
        assertEquals("Dark", result.first { it.title == "Dark" }.title)
        assertEquals("movie", result.first { it.title == "Batman: El caballero de la noche" }.type)
        assertEquals("anime", result.first { it.title == "Yami Shibai" }.type)
        assertTrue(result.none { it.url.contains("/persona/") })
    }
    @Test fun detailReadsActualMovieAndSeriesMarkup() {
        val movie = SoloLatinoDetails.detail(Jsoup.parse(fixture("detail-movie.html"), "https://sololatino.net/pelicula/la-desaparicion-de-josef-mengele"))!!
        assertEquals("La desaparición de Josef Mengele", movie.title)
        assertEquals(2025, movie.year)
        assertEquals(listOf("Drama", "Historia"), movie.genres)
        assertTrue(movie.poster!!.startsWith("https://image.tmdb.org/"))
        assertTrue(movie.background!!.startsWith("https://image.tmdb.org/"))
        val series = SoloLatinoDetails.detail(Jsoup.parse(fixture("detail-series.html"), "https://sololatino.net/serie/dark"))!!
        assertEquals("Dark", series.title)
        assertEquals(2017, series.year)
        assertTrue(series.plot!!.contains("desaparición de un niño"))
    }
    @Test fun disallowsCrossSitePagesAndSearchPoisoning() {
        assertNull(SoloLatinoDetails.detail(Jsoup.parse("<h1>Falso</h1>", "https://sololatino.net.evil.test/serie/dark")))
        assertTrue(SoloLatinoDetails.suggestions("""[{"title":"Falso","url":"https://sololatino.net.evil.test/serie/dark"}]""").isEmpty())
    }
}
