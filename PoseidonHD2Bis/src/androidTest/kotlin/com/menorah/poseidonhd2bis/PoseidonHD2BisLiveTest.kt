package com.menorah.poseidonhd2bis

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
class PoseidonHD2BisLiveTest {

    private fun getText(url: String, referer: String = "${PoseidonHD2BisData.MAIN_URL}/"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", PoseidonHD2BisData.DEFAULT_USER_AGENT)
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
        c.setRequestProperty("User-Agent", PoseidonHD2BisData.DEFAULT_USER_AGENT)
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
    fun offlineCryptoAndSlugSmoke() {
        assertEquals(
            "https://www.poseidonhd2.co/pelicula/603/the-matrix",
            PoseidonHD2BisData.slugToPublicUrl("movies/603/the-matrix"),
        )
        assertEquals(
            "https://www.poseidonhd2.co/serie/254630/12-12-12",
            PoseidonHD2BisData.extractSeriesBaseUrl("https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4"),
        )
        assertEquals("2b9ffe2e36e8a25c9c8affe5abaa4698", PoseidonVsembedCrypto.encryptHex("6168w", "vsembed.com"))
    }

    @Test
    fun liveCatalogSearchDetailsPlayersAndImages() {
        val live = InstrumentationRegistry.getArguments().getString("live") == "true"
        assumeTrue("Pass -e live true to run live network tests", live)

        val moviesHtml = getText("${PoseidonHD2BisData.MAIN_URL}/peliculas")
        val moviesPage = PoseidonHD2BisData.parseCatalogPage(moviesHtml)
        assertTrue("Expected >= 15 movies on /peliculas", moviesPage.items.size >= 15)
        assertTrue("Expected totalPages > 1 on /peliculas", moviesPage.totalPages > 1)

        val moviesPage2Html = getText("${PoseidonHD2BisData.MAIN_URL}/peliculas/page/2")
        val moviesPage2 = PoseidonHD2BisData.parseCatalogPage(moviesPage2Html)
        assertTrue("Expected movies on /peliculas/page/2", moviesPage2.items.isNotEmpty())
        assertTrue(
            "Expected page 2 items to differ from page 1",
            moviesPage2.items.first().url != moviesPage.items.first().url,
        )

        val episodesHtml = getText("${PoseidonHD2BisData.MAIN_URL}/episodios")
        val episodesPage = PoseidonHD2BisData.parseCatalogPage(episodesHtml)
        assertTrue("Expected >= 15 recent episodes on /episodios", episodesPage.items.size >= 15)

        val genreHtml = getText("${PoseidonHD2BisData.MAIN_URL}/genero/suspenso")
        val genrePage = PoseidonHD2BisData.parseCatalogPage(genreHtml)
        assertTrue("Expected items on /genero/suspenso", genrePage.items.isNotEmpty())

        val searchHtml = getText("${PoseidonHD2BisData.MAIN_URL}/search?q=matrix")
        val searchPage = PoseidonHD2BisData.parseCatalogPage(searchHtml)
        assertTrue("Expected Matrix search results", searchPage.items.isNotEmpty())

        val movieUrl = "${PoseidonHD2BisData.MAIN_URL}/pelicula/603/the-matrix"
        val movieHtml = getText(movieUrl)
        val movie = PoseidonHD2BisData.parseMovieDetail(movieHtml, movieUrl)
        assertNotNull("Parsed Matrix movie detail", movie)
        assertTrue("Expected Matrix title", movie!!.title.contains("Matrix", ignoreCase = true))
        assertTrue("Expected video sources on Matrix", movie.videos.isNotEmpty())
        checkImageReachable(movie.posterUrl!!)
        checkImageReachable(movie.backgroundPosterUrl!!)

        val firstVideo = movie.videos.firstOrNull { it.cyberlocker != "netu" } ?: movie.videos.first()
        val playerHtml = getText(firstVideo.playerUrl, movieUrl)
        val embedUrl = PoseidonHD2BisData.extractPlayerEmbedUrl(playerHtml)
        assertNotNull("Expected embed URL from ${firstVideo.playerUrl}", embedUrl)
        assertTrue("Expected https embed URL: $embedUrl", embedUrl!!.startsWith("http"))

        val seriesUrl = "${PoseidonHD2BisData.MAIN_URL}/serie/84773/la-casa-del-dragon"
        val seriesHtml = getText(seriesUrl)
        val series = PoseidonHD2BisData.parseSeriesDetail(seriesHtml, seriesUrl)
        assertNotNull("Parsed House of the Dragon series detail", series)
        assertTrue("Expected episodes across seasons", series!!.episodes.isNotEmpty())
        assertTrue("Expected non-zero season numbers", series.episodes.all { it.season > 0 })
        checkImageReachable(series.posterUrl!!)
    }
}
