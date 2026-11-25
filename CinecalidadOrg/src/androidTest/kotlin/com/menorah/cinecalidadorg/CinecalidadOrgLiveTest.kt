package com.menorah.cinecalidadorg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CinecalidadOrgLiveTest {
    private fun getText(url: String, accept: String = "application/json, text/plain, */*"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
        )
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
        c.setRequestProperty("Referer", "${CinecalidadOrgApi.BASE}/")
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
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) Chrome/131.0")
        try {
            assertEquals("Image HTTP $url", 200, c.responseCode)
            val ct = c.contentType.orEmpty().lowercase()
            assertTrue("Unexpected content-type $ct for $url", ct.startsWith("image/"))
            val bytes = c.inputStream.use { it.readBytes() }
            assertTrue("Empty image $url", bytes.size > 500)
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun liveCatalogsFiltersSearchAndSiteConfig() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return

        val homeHtml = getText("${CinecalidadOrgApi.BASE}/", "text/html")
        val catalog = CinecalidadOrgApi.parseSiteConfigTaxonomies(homeHtml)
        assertTrue("Expected >= 20 genres", catalog.genresBySlug.size >= 20)
        assertTrue("Expected Latino id", catalog.langsBySlug.containsKey("latino"))
        assertTrue("Expected 2026 id", catalog.yearsBySlug.containsKey("2026"))

        val latest = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildLatestUrl()))
        assertTrue("Latest empty", latest.items.isNotEmpty())
        assertFalse("Latest should not paginate", latest.hasNext)

        for (type in listOf("movies", "tvshows", "animes", "novels", "wwe")) {
            val page1 = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildListingUrl(type, 1)))
            assertTrue("Listing $type empty", page1.items.isNotEmpty())
            assertTrue("Missing poster in $type", page1.items.first().poster?.startsWith("https://") == true)
        }

        val movies1 = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildListingUrl("movies", 1)))
        val movies2 = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildListingUrl("movies", 2)))
        assertTrue(movies1.hasNext)
        assertTrue(movies2.items.any { p2 -> movies1.items.none { it.url == p2.url } })

        val tops1 = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildTopsUrl("movies", 1)))
        val tops2 = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildTopsUrl("movies", 2)))
        assertTrue(tops1.items.isNotEmpty())
        assertTrue(tops1.hasNext)
        assertTrue(tops2.items.any { p2 -> tops1.items.none { it.url == p2.url } })

        val accionId = catalog.genresBySlug.getValue("accion").id
        val actionItems = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildFilterListingUrl("genres", accionId, 1)))
        assertTrue("Action filter empty", actionItems.items.isNotEmpty())

        val year1995Id = CinecalidadOrgApi.parseTaxTermId(getText(CinecalidadOrgApi.buildTaxMetadataUrl("years", "1995")))
        assertNotNull("Expected termId for 1995", year1995Id)
        val year1995Items = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildFilterListingUrl("years", year1995Id!!, 1)))
        assertTrue("1995 archive empty", year1995Items.items.isNotEmpty())

        val searchItems = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildSearchUrl("spider", 1)))
        assertTrue("Search 'spider' empty", searchItems.items.isNotEmpty())

        val samplePoster = movies1.items.firstNotNullOfOrNull { it.poster }
        assertNotNull("Expected poster URL", samplePoster)
        checkImageReachable(samplePoster!!)
    }

    @Test
    fun liveSinglesEpisodesCastAndPlayerEmbeds() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return

        val movie = CinecalidadOrgApi.parseSingle(
            getText(CinecalidadOrgApi.buildSingleUrl("movies", "perfectos-mentirosos-2026")),
            "movies",
        )
        assertNotNull(movie)
        val cast = CinecalidadOrgApi.parseCast(getText(CinecalidadOrgApi.buildCastUrl("movies", movie!!.id)))
        assertTrue("Cast empty", cast.isNotEmpty())
        val related = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildRelatedUrl(movie.id)))
        assertTrue("Related empty", related.items.isNotEmpty())

        val movieEmbeds = CinecalidadOrgApi.parsePlayerEmbeds(getText(CinecalidadOrgApi.buildPlayerUrl(movie.id)))
        assertTrue("Movie embeds empty", movieEmbeds.isNotEmpty())

        val seriesListing = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildListingUrl("tvshows", 1)))
        val firstShow = seriesListing.items.first()
        val showDetail = CinecalidadOrgApi.parseSingle(
            getText(CinecalidadOrgApi.buildSingleUrl("tvshows", firstShow.slug)),
            "tvshows",
        )!!
        val epPage1 = CinecalidadOrgApi.parseEpisodesPage(
            getText(CinecalidadOrgApi.buildEpisodesListUrl(showDetail.id, 1, 1)),
        )
        assertTrue("Show seasons empty", epPage1.seasons.isNotEmpty())
        val targetSeason = epPage1.seasons.first()
        val targetPage = if (epPage1.episodes.isNotEmpty()) epPage1 else {
            CinecalidadOrgApi.parseEpisodesPage(getText(CinecalidadOrgApi.buildEpisodesListUrl(showDetail.id, targetSeason, 1)))
        }
        assertTrue("Episode list empty for ${firstShow.slug}", targetPage.episodes.isNotEmpty())
        val epEmbeds = CinecalidadOrgApi.parsePlayerEmbeds(getText(targetPage.episodes.first().dataUrl))
        assertTrue("Episode embeds empty for ${firstShow.slug}", epEmbeds.isNotEmpty())

        val animeListing = CinecalidadOrgApi.parseListing(getText(CinecalidadOrgApi.buildListingUrl("animes", 1)))
        val firstAnime = animeListing.items.first()
        val animeDetail = CinecalidadOrgApi.parseSingle(
            getText(CinecalidadOrgApi.buildSingleUrl("animes", firstAnime.slug)),
            "animes",
        )!!
        val animeEpPage = CinecalidadOrgApi.parseEpisodesPage(
            getText(CinecalidadOrgApi.buildEpisodesListUrl(animeDetail.id, 1, 1)),
        )
        assertTrue("Anime episodes empty for ${firstAnime.slug}", animeEpPage.episodes.isNotEmpty())
    }
}
