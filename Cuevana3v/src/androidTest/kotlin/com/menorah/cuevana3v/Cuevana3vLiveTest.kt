package com.menorah.cuevana3v

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class Cuevana3vLiveTest {

    private fun getText(url: String, referer: String = "${Cuevana3vMarkup.MAIN_URL}/"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", Cuevana3vMarkup.DEFAULT_USER_AGENT)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        c.setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
        c.setRequestProperty("Referer", referer)
        try {
            assertEquals("HTTP $url", 200, c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun checkImageReachable(url: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", Cuevana3vMarkup.DEFAULT_USER_AGENT)
        try {
            assertEquals("Image HTTP $url", 200, c.responseCode)
            val ct = c.contentType.orEmpty().lowercase()
            assertTrue("Unexpected content-type $ct for $url", ct.startsWith("image/"))
            val bytes = c.inputStream.use { it.readBytes() }
            assertTrue("Empty image $url", bytes.size > 500)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Failed to decode bitmap for $url", bmp)
            assertTrue("Decoded image too small for $url: ${bmp!!.width}x${bmp.height}", bmp.width >= 50 && bmp.height >= 50)
            bmp.recycle()
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun offlineParserSmoke() {
        assertEquals(
            "https://lkhjerbhye3wikhoduh5xiczuvd.lol/v/7q1v682peq0u",
            Cuevana3vMarkup.decodeTungUrl("https://tungtungsahur.cuevana3v.pro/?token=1Vl0ZUFoGB3A3B3E3"),
        )
        assertEquals(
            "https://vibuxer.com/e/zopp6y5coyze",
            Cuevana3vMarkup.decodeTungUrl("https://tungtungsahur.cuevana3v.pro/?token=3GxkPBB0JUhUGAQN1"),
        )
        assertEquals(
            "https://cuevana3v.pro/serie/a-la-deriva-2",
            Cuevana3vMarkup.extractSeriesUrlFromUrl("https://cuevana3v.pro/serie/a-la-deriva-2/episodio-1x10"),
        )
    }

    @Test
    fun liveCatalogSearchDetailsAndImages() {
        val live = InstrumentationRegistry.getArguments().getString("live") == "true"
        assumeTrue("Pass -e live true to run live network tests", live)

        val moviesHtml = getText("${Cuevana3vMarkup.MAIN_URL}/peliculas")
        val movies = Cuevana3vMarkup.parseCatalogItems(moviesHtml)
        assertTrue("Expected >= 10 movies on /peliculas", movies.size >= 10)
        assertTrue("Expected next page on /peliculas", Cuevana3vMarkup.hasNextPage(moviesHtml, "${Cuevana3vMarkup.MAIN_URL}/peliculas", 1, movies.size))

        val recentEpsHtml = getText("${Cuevana3vMarkup.MAIN_URL}/episodios/recientes")
        val recentEps = Cuevana3vMarkup.parseCatalogItems(recentEpsHtml)
        assertTrue("Expected >= 15 recent episodes", recentEps.size >= 15)

        val searchHtml = getText("${Cuevana3vMarkup.MAIN_URL}/explorar?s=matrix")
        val searchResults = Cuevana3vMarkup.parseCatalogItems(searchHtml)
        assertTrue("Expected Matrix search results", searchResults.isNotEmpty())

        val movieUrl = "${Cuevana3vMarkup.MAIN_URL}/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha"
        val movieHtml = getText(movieUrl)
        val movieDetail = Cuevana3vMarkup.parseMovieDetail(movieHtml, movieUrl)
        assertTrue("Expected movie title", movieDetail.title.contains("Juegos del Hambre", ignoreCase = true))
        checkImageReachable(movieDetail.posterUrl!!)
        checkImageReachable(movieDetail.backgroundPosterUrl!!)

        val movieSources = Cuevana3vMarkup.parsePlayerTabs(movieHtml)
        assertTrue("Expected player sources on movie", movieSources.isNotEmpty())
        val firstHyper = movieSources.firstOrNull { it.serverName.equals("Hyper", ignoreCase = true) }
        assertNotNull("Expected Hyper server", firstHyper)
        val decodedHyper = Cuevana3vMarkup.decodeTungUrl(firstHyper!!.rawServerUrl)
        assertNotNull("Decoded Hyper URL", decodedHyper)
        val hyperHtml = getText(decodedHyper!!, "${Cuevana3vMarkup.TUNG_URL}/")
        val parsedHyper = CuevanaVidHideParser.parse(hyperHtml, decodedHyper)
        assertTrue("Expected streams from Hyper embed", parsedHyper.streams.isNotEmpty())
        assertEquals("hls2", parsedHyper.streams.first().key)

        val seriesUrl = "${Cuevana3vMarkup.MAIN_URL}/serie/dexter-resurreccion"
        val seriesHtml = getText(seriesUrl)
        val seriesDetail = Cuevana3vMarkup.parseSeriesDetail(seriesHtml, seriesUrl)
        assertTrue("Expected seasons on Dexter", seriesDetail.seasons.isNotEmpty())
        checkImageReachable(seriesDetail.posterUrl!!)

        val season1Html = getText(seriesDetail.seasons.first().url, seriesUrl)
        val s1Episodes = Cuevana3vMarkup.parseSeasonEpisodes(season1Html, seriesDetail.seasons.first().season)
        assertTrue("Expected episodes on Season 1", s1Episodes.isNotEmpty())
    }
}
