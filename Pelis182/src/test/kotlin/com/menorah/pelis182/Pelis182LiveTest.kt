package com.menorah.pelis182

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class Pelis182LiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Before
    fun checkEnabled() {
        val enabled = System.getProperty("pelis182.live") == "true" || System.getenv("PELIS182_LIVE") == "true"
        assumeTrue("Set PELIS182_LIVE=true to run live Pelis182 tests", enabled)
    }

    private fun fetchText(url: String, referer: String = "${Pelis182Markup.MAIN_URL}/"): Pair<String, String> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Pelis182Markup.DEFAULT_USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { res ->
            assertTrue("HTTP ${res.code} for $url", res.isSuccessful)
            return res.request.url.toString() to res.body.string()
        }
    }

    @Test
    fun `live catalogs genres pagination and search parse cleanly`() {
        for (path in listOf("/catalogo/", "/catalogo/?orden=tendencias", "/category/series/", "/category/anime/", "/category/accion/?tipo=peliculas")) {
            val url = Pelis182Markup.buildPagedUrl(path, 1)
            val (finalUrl, html) = fetchText(url)
            val page = Pelis182Markup.parseCatalogPage(Jsoup.parse(html, finalUrl))
            assertTrue("Section $path should return cards", page.items.isNotEmpty())
        }

        val p2Url = Pelis182Markup.buildPagedUrl("/catalogo/?orden=tendencias", 2)
        val (p2Final, p2Html) = fetchText(p2Url)
        val p2Page = Pelis182Markup.parseCatalogPage(Jsoup.parse(p2Html, p2Final))
        assertTrue("Trending page 2 non-empty", p2Page.items.isNotEmpty())

        val (s1Url, s1Html) = fetchText(Pelis182Markup.buildSearchUrl("Black Mirror", 1))
        val s1Page = Pelis182Markup.parseCatalogPage(Jsoup.parse(s1Html, s1Url))
        assertTrue("Black Mirror search returns seasons", s1Page.items.size >= 5)

        val (emptyUrl, emptyHtml) = fetchText(Pelis182Markup.buildSearchUrl("zzzzzzzzzznoresult", 1))
        val emptyPage = Pelis182Markup.parseCatalogPage(Jsoup.parse(emptyHtml, emptyUrl))
        assertTrue("Nonexistent query returns empty list", emptyPage.items.isEmpty())
        assertFalse(emptyPage.hasNext)
    }

    @Test
    fun `live movie and multiseason series resolve playable 9bg hls streams and subtitles`() {
        val (mUrl, mHtml) = fetchText("${Pelis182Markup.MAIN_URL}/street-fighter/")
        val movie = Pelis182Markup.parseDetail(Jsoup.parse(mHtml, mUrl), mUrl)
        assertNotNull(movie)
        movie!!
        assertFalse(movie.isSeries)
        assertTrue("Movie has paulinito embed", movie.movieEmbeds.isNotEmpty())
        val embedUrl = movie.movieEmbeds.first().url
        assertTrue("Embed uses paulinito.com: $embedUrl", embedUrl.contains("paulinito.com"))

        // Player page requires pelis182 referer
        val (_, playerHtml) = fetchText(embedUrl, referer = mUrl)
        val cfg = Pelis182Markup.parsePaulinitoConfig(playerHtml)
        assertTrue("Paulinito config contains video.m3u8", cfg.sources.isNotEmpty())
        val masterM3u8 = cfg.sources.first().file
        assertTrue("Source is HLS m3u8", masterM3u8.contains(".m3u8"))

        // Master playlist and variant playlist require paulinito referer
        val playerOrigin = "${Pelis182Markup.hostBaseUrl(embedUrl)}/"
        val (_, masterText) = fetchText(masterM3u8, referer = playerOrigin)
        assertTrue("Master playlist starts with #EXTM3U", masterText.contains("#EXTM3U"))
        val firstVariant = masterText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        assertNotNull("Master playlist contains variant line", firstVariant)
        val variantUrl = URI(masterM3u8).resolve(firstVariant!!).toString()
        val (_, variantText) = fetchText(variantUrl, referer = playerOrigin)
        assertTrue("Variant playlist contains segments", variantText.contains("#EXTINF:"))

        // Subtitles (.srt) load cleanly with paulinito referer and exclude thumbnail.vtt
        assertTrue("Subtitle URLs exclude thumbnails", cfg.tracks.none { it.file.contains("thumbnail.vtt") })
        if (cfg.tracks.isNotEmpty()) {
            val (_, srtText) = fetchText(cfg.tracks.first().file, referer = playerOrigin)
            assertTrue("Subtitle file is valid SRT", srtText.contains("-->"))
        }

        // Anime movie resolves playable stream
        val (anUrl, anHtml) = fetchText("${Pelis182Markup.MAIN_URL}/solo-necesitas-matar/")
        val anime = Pelis182Markup.parseDetail(Jsoup.parse(anHtml, anUrl), anUrl)
        assertNotNull(anime)
        assertTrue("Anime movie has paulinito embed", anime!!.movieEmbeds.isNotEmpty())

        // Multi-season series: Black Mirror Temporada 7 + The Boys search sibling seasons
        val (bmUrl, bmHtml) = fetchText("${Pelis182Markup.MAIN_URL}/black-mirror-temporada-7/")
        val bm7 = Pelis182Markup.parseDetail(Jsoup.parse(bmHtml, bmUrl), bmUrl)
        assertNotNull(bm7)
        bm7!!
        assertTrue(bm7.isSeries)
        assertEquals("Black Mirror", bm7.title)
        assertEquals(7, bm7.seasonNumber)
        assertEquals(6, bm7.episodes.size)
        assertTrue("Episodes have season 7", bm7.episodes.all { it.season == 7 })

        val (tbSearchUrl, tbSearchHtml) = fetchText(Pelis182Markup.buildSearchUrl("The Boys", 1))
        val tbCards = Pelis182Markup.parseCatalogPage(Jsoup.parse(tbSearchHtml, tbSearchUrl)).items
        val matchedBoys = tbCards.filter { Pelis182Markup.isMatchingSeasonCard("The Boys", it.title, null, it.url) }
        assertTrue("The Boys search finds at least 5 seasons", matchedBoys.size >= 5)
    }
}
