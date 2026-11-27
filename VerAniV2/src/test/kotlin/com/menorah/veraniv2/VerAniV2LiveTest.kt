package com.menorah.veraniv2

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

class VerAniV2LiveTest {

    private lateinit var provider: VerAniV2Provider
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Before
    fun checkEnabled() {
        val enabled = System.getProperty("verani.live") == "true" || System.getenv("VERANI_LIVE") == "true"
        assumeTrue("Set VERANI_LIVE=true or -Dverani.live=true to run live VerAniV2 integration tests", enabled)
        app.baseClient = probeClient
        provider = VerAniV2Provider()
    }

    @Test
    fun `live mainPage sections and pagination return valid cards`() = runBlocking {
        val sectionsToTest = listOf(
            VerAniV2Markup.SECTION_WEEKLY to "Estrenos Semanales",
            "https://verani.me/animes/" to "Últimos Animes",
            "https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino" to "Audio Latino",
            "https://verani.me/animes/?idioma=Espa%C3%B1ol%20Espa%C3%B1a" to "Castellano",
            "https://verani.me/peliculas/" to "Películas",
        )
        for ((data, label) in sectionsToTest) {
            val res = provider.getMainPage(1, MainPageRequest(label, data, false))
            val items = res.items.firstOrNull()?.list.orEmpty()
            assertTrue("Expected items in $label", items.isNotEmpty())
            assertTrue(
                "All links in $label must belong to verani.me",
                items.all { VerAniV2Markup.hostOf(it.url).endsWith("verani.me") },
            )
            println("LIVE_MAIN[$label]: count=${items.size}, hasNext=${res.hasNext}, first=${items.first().title} -> ${items.first().url}")
        }

        // Verify page 2 of Audio Latino differs from page 1
        val lat1 = provider.getMainPage(1, MainPageRequest("Audio Latino", "https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino", false))
            .items.first().list.map { it.url }.toSet()
        val lat2 = provider.getMainPage(2, MainPageRequest("Audio Latino", "https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino", false))
            .items.first().list.map { it.url }.toSet()
        assertTrue("Latino page 2 should differ from page 1", lat2.isNotEmpty() && lat1 != lat2)
    }

    @Test
    fun `live search with pagination works`() = runBlocking {
        val s1 = provider.search("a", 1)
        val s2 = provider.search("a", 2)
        assertTrue("Search 'a' page 1 non-empty", s1.items.isNotEmpty())
        assertTrue("Search 'a' page 1 hasNext", s1.hasNext == true)
        assertTrue("Search 'a' page 2 non-empty", s2.items.isNotEmpty())
        assertFalse("Search page 2 differs from page 1", s1.items.map { it.url } == s2.items.map { it.url })

        val kimetsu = provider.search("kimetsu", 1)
        assertTrue("Search 'kimetsu' non-empty", kimetsu.items.isNotEmpty())
        println("LIVE_SEARCH: kimetsu=${kimetsu.items.size}, first=${kimetsu.items.first().title}")
    }

    @Test
    fun `live load handles series boruto movies movie and chapter redirect`() = runBlocking {
        val boruto = provider.load("https://verani.me/boruto-naruto-next-generations/") as? AnimeLoadResponse
        assertNotNull("Boruto should load as AnimeLoadResponse", boruto)
        boruto!!
        val movieSeason = boruto.seasonNames?.firstOrNull { it.name == "Películas" }
        assertNotNull("Boruto should expose Películas season", movieSeason)
        assertTrue("Movie season must be > 0", movieSeason!!.season > 0)
        assertFalse("Poster must not be site logo", VerAniV2Markup.isSiteLogoUrl(boruto.posterUrl))

        val knc = provider.load("https://verani.me/kiniro-no-corda-primo-passo/") as? AnimeLoadResponse
        assertNotNull("Kiniro should load cleanly", knc)
        assertFalse("Kiniro poster must not be site logo", VerAniV2Markup.isSiteLogoUrl(knc!!.posterUrl))

        val movie = provider.load("https://verani.me/pelicula/el-nino-y-la-bestia/") as? MovieLoadResponse
        assertNotNull("Movie should load as MovieLoadResponse", movie)
        assertEqualsSafe("El niño y la bestia", movie!!.name)

        // Load a weekly chapter URL directly and ensure it resolves the parent series
        val weeklyFirst = provider.getMainPage(1, MainPageRequest("Estrenos", VerAniV2Markup.SECTION_WEEKLY, false))
            .items.first().list.first()
        val fromChapter = provider.load(weeklyFirst.url)
        assertTrue(
            "Chapter URL ${weeklyFirst.url} should resolve AnimeLoadResponse or fallback MovieLoadResponse",
            fromChapter is AnimeLoadResponse || fromChapter is MovieLoadResponse,
        )
        println("LIVE_LOAD: boruto_eps=${boruto.episodes.values.flatten().size}, knc_poster=${knc.posterUrl}, movie_year=${movie.year}, weekly=${fromChapter?.name}")
    }

    private fun assertEqualsSafe(expected: String, actual: String) {
        assertTrue("Expected '$expected' but got '$actual'", expected.equals(actual.trim(), ignoreCase = true))
    }

    @Test
    fun `live loadLinks extracts verifiable streams including turboembed full master`() = runBlocking {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = provider.loadLinks(
            data = "https://verani.me/kikansha-no-mahou-wa-tokubetsu-desu/capitulo-8-episodio-8-629/",
            isCasting = false,
            subtitleCallback = {},
            callback = { links += it },
        )
        assertTrue("loadLinks must emit links for test episode", ok && links.isNotEmpty())
        println("LIVE_LINKS_EP1 (${links.size}): " + links.joinToString(" | ") { "${it.name}[${it.type},q=${it.quality}]->${it.url.take(70)}" })

        // Test an episode with AnimeAV1/TurboEmbed & multiple hosts (movie1: El niño y la bestia)
        val mLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val mOk = provider.loadLinks(
            data = "https://verani.me/pelicula/el-nino-y-la-bestia/",
            isCasting = false,
            subtitleCallback = {},
            callback = { mLinks += it },
        )
        assertTrue("loadLinks must emit links for movie", mOk && mLinks.isNotEmpty())
        println("LIVE_LINKS_MOVIE (${mLinks.size}): " + mLinks.joinToString(" | ") { "${it.name}[${it.type},q=${it.quality}]->${it.url.take(70)}" })

        // Verify TurboEmbed emitted link (if present) points to master.txt (preserving audio), NOT video-only v1080p.txt
        val turboLinks = (links + mLinks).filter { it.url.contains("turboembed", ignoreCase = true) }
        for (tl in turboLinks) {
            assertFalse("TurboEmbed must keep master playlist with audio track, not video-only stream: ${tl.url}", tl.url.contains("v1080p"))
        }

        // Probe reachability of at least one emitted stream (VOE / Vidara / TurboEmbed / Mp4Upload)
        var verifiedCount = 0
        for (l in (links + mLinks)) {
            if (l.url.contains("zilla-networks.com")) continue // Zilla blocks Azure CI IPs via Cloudflare
            val reqBuilder = Request.Builder().url(l.url)
                .header("User-Agent", VerAniV2ExtractorUserAgent.UA)
            if (l.referer.isNotBlank()) reqBuilder.header("Referer", l.referer)
            l.headers.forEach { (k, v) ->
                if (!k.equals("user-agent", true)) reqBuilder.header(k, v)
            }
            if (l.type == ExtractorLinkType.VIDEO) reqBuilder.header("Range", "bytes=0-1023")
            val res = runCatching { probeClient.newCall(reqBuilder.build()).execute() }.getOrNull()
            res?.use { r ->
                println("PROBE_STREAM: ${l.name} -> HTTP ${r.code} ct=${r.header("Content-Type")}")
                if (r.code in 200..299) verifiedCount++
            }
        }
        assertTrue("At least one extracted live stream must return HTTP 2xx", verifiedCount > 0)
    }
}
