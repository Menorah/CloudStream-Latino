package com.menorah.animeonline2

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class AnimeOnline2LiveTest {

    private fun getText(url: String, referer: String = "${AnimeOnline2Parser.BASE}/inicio/"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 25_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", AnimeOnline2Parser.USER_AGENT)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        c.setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
        c.setRequestProperty("Referer", referer)
        try {
            assertEquals("HTTP $url", 200, c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun postForm(url: String, formBody: String, referer: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 25_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", AnimeOnline2Parser.USER_AGENT)
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Referer", referer)
        try {
            OutputStreamWriter(c.outputStream).use { it.write(formBody) }
            assertEquals("POST HTTP $url", 200, c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun checkImageReachable(url: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", AnimeOnline2Parser.USER_AGENT)
        c.setRequestProperty("Referer", "${AnimeOnline2Parser.BASE}/inicio/")
        c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
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
    fun liveCatalogsSearchPaginationAndImages() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return

        val homeHtml = getText("${AnimeOnline2Parser.BASE}/inicio/")
        val home = AnimeOnline2Parser.parseHomeModules(homeHtml)
        assertTrue("Home latestEpisodes empty", home.latestEpisodes.isNotEmpty())
        assertTrue("Home featuredOnAir empty", home.featuredOnAir.isNotEmpty())
        assertTrue("Home latestAnime empty", home.latestAnime.isNotEmpty())
        assertTrue("Home latestMovies empty", home.latestMovies.isNotEmpty())
        assertTrue("Home seasons empty", home.seasons.isNotEmpty())

        for (path in listOf("/online/", "/pelicula/", "/temporada/", "/genero/audio-latino/", "/genero/anime-castellano/")) {
            val p1 = AnimeOnline2Parser.parseArchivePage(getText(AnimeOnline2Parser.buildArchivePageUrl(path, 1)), 1)
            assertTrue("Archive $path page 1 empty", p1.items.isNotEmpty())
            assertTrue("Archive $path expected pagination", p1.hasNext)
            val p2 = AnimeOnline2Parser.parseArchivePage(getText(AnimeOnline2Parser.buildArchivePageUrl(path, 2)), 2)
            assertTrue("Archive $path page 2 empty", p2.items.isNotEmpty())
            assertTrue("Archive $path page 2 must advance", p2.items.any { x -> p1.items.none { it.url == x.url } })
        }

        val sr1 = AnimeOnline2Parser.parseSearchPage(getText(AnimeOnline2Parser.buildSearchUrl("a", 1)), 1)
        assertTrue("Search 'a' page 1 empty", sr1.items.isNotEmpty())
        assertTrue("Search 'a' expected pagination", sr1.hasNext)
        val sr2 = AnimeOnline2Parser.parseSearchPage(getText(AnimeOnline2Parser.buildSearchUrl("a", 2)), 2)
        assertTrue("Search 'a' page 2 empty", sr2.items.isNotEmpty())

        val samplePosters = listOfNotNull(
            home.latestEpisodes.firstNotNullOfOrNull { it.posterUrl },
            home.featuredOnAir.firstNotNullOfOrNull { it.posterUrl },
            home.latestAnime.firstNotNullOfOrNull { it.posterUrl },
            home.latestMovies.firstNotNullOfOrNull { it.posterUrl },
            home.seasons.firstNotNullOfOrNull { it.posterUrl },
            sr1.items.firstNotNullOfOrNull { it.posterUrl },
        )
        assertEquals("Expected sample posters across all sections", 6, samplePosters.size)
        for (imgUrl in samplePosters) {
            checkImageReachable(imgUrl)
        }
    }

    @Test
    fun liveSeriesMovieEpisodePlayersAndExtractors() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return

        // Episode -> parent series lookup
        val epUrl = "https://ww4.animeonline.ninja/episodio/radiant-cap-1/"
        val epHtml = getText(epUrl)
        val parentUrl = AnimeOnline2Parser.findParentSeriesUrlFromEpisode(epHtml)
        assertEquals("https://ww4.animeonline.ninja/online/radiant/", parentUrl)

        val seriesDetail = AnimeOnline2Parser.parseDetailPage(parentUrl!!, getText(parentUrl))
        assertEquals("Radiant", seriesDetail.title)
        assertTrue("Series episodes empty", seriesDetail.episodes.size >= 20)
        assertFalse("SEO spam leaked into tags: ${seriesDetail.tags}", seriesDetail.tags.any { it.lowercase().contains("flv") })

        // Movie detail + player option
        val movieUrl = "https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/"
        val movieHtml = getText(movieUrl)
        val movieDetail = AnimeOnline2Parser.parseDetailPage(movieUrl, movieHtml)
        assertTrue("Expected movie", movieDetail.isMovie)
        assertTrue("Expected movie player options", movieDetail.hasDirectPlayer)

        // Episode player wp-json -> saidochesto.top -> gupload.site live decode
        val opts = AnimeOnline2Parser.parsePlayerOptions(epHtml)
        assertTrue("Episode player options empty", opts.isNotEmpty())
        val o0 = opts.first()
        val jsonText = getText("${AnimeOnline2Parser.BASE}/wp-json/dooplayer/v1/post/${o0.postId}?type=${o0.type}&source=${o0.nume}", epUrl)
        val embedUrl = AnimeOnline2Parser.parseDooplayerEmbedUrl(jsonText)
        assertNotNull("Embed URL null", embedUrl)
        val saidoHtml = getText(embedUrl!!, epUrl)
        val servers = AnimeOnline2Parser.parseSaidochestoOptions(saidoHtml)
        assertTrue("Saidochesto servers empty", servers.isNotEmpty())

        val guploadUrl = servers.firstOrNull { it.url.contains("gupload.site") }?.url
        if (guploadUrl != null) {
            val videoUrl = AnimeOnline2Parser.extractGuploadVideoUrl(getText(guploadUrl, embedUrl))
            assertNotNull("GUpload videoUrl extraction failed", videoUrl)
            assertTrue("Unexpected GUpload URL $videoUrl", videoUrl!!.startsWith("https://"))
        }

        val savefilesUrl = servers.firstOrNull { it.url.contains("savefiles.top") }?.url
        if (savefilesUrl != null) {
            val code = savefilesUrl.substringAfterLast("/").substringBefore("?")
            val dlHtml = postForm("https://savefiles.top/dl", "op=embed&file_code=$code&auto=1&referer=$embedUrl", savefilesUrl)
            assertTrue("Savefiles /dl missing stream", dlHtml.contains(".m3u8") || dlHtml.contains("sources:"))
        }
    }
}
