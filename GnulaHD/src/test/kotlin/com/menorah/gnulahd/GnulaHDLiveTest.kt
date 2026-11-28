package com.menorah.gnulahd

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GnulaHDLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Before
    fun checkEnabled() {
        val enabled = System.getProperty("gnulahd.live") == "true" || System.getenv("GNULAHD_LIVE") == "true"
        assumeTrue("Set GNULAHD_LIVE=true to run live GnulaHD tests", enabled)
    }

    private fun fetchText(url: String, referer: String = "${GnulaHDMarkup.MAIN_URL}/"): Pair<String, String> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", GnulaHDMarkup.DEFAULT_USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { res ->
            assertTrue("HTTP ${res.code} for $url", res.isSuccessful)
            return res.request.url.toString() to res.body.string()
        }
    }

    @Test
    fun `live home catalogs search and pagination parse cleanly`() {
        val (homeUrl, homeHtml) = fetchText("${GnulaHDMarkup.MAIN_URL}/")
        val homeDoc = Jsoup.parse(homeHtml, homeUrl)
        val week = GnulaHDMarkup.parseHomeTrendingWeek(homeDoc)
        val today = GnulaHDMarkup.parseHomeTrendingToday(homeDoc)
        assertTrue("Trending week non-empty", week.isNotEmpty())
        assertTrue("Trending today non-empty", today.isNotEmpty())

        for (path in listOf("/ver/peliculas/", "/ver/series/", "/ver/anime/", "/recien-agregado/", "/genres/accion/")) {
            val (u, html) = fetchText("${GnulaHDMarkup.MAIN_URL}$path")
            val page = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(html, u))
            assertTrue("Catalog $path non-empty", page.items.isNotEmpty())
            assertTrue("Catalog $path hasNext", page.hasNext)
        }

        val (upUrl, upHtml) = fetchText("${GnulaHDMarkup.MAIN_URL}/proximos-estrenos/")
        val upPage = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(upHtml, upUrl))
        assertTrue("Upcoming non-empty", upPage.items.isNotEmpty())
        assertFalse("Upcoming single page", upPage.hasNext)

        val (s1Url, s1Html) = fetchText(GnulaHDMarkup.buildSearchUrl("love", 1))
        val s1Page = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(s1Html, s1Url))
        assertTrue("Search page 1 non-empty", s1Page.items.isNotEmpty())
        assertTrue("Search page 1 hasNext", s1Page.hasNext)

        val (s2Url, s2Html) = fetchText(GnulaHDMarkup.buildSearchUrl("love", 2))
        val s2Page = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(s2Html, s2Url))
        assertTrue("Search page 2 non-empty", s2Page.items.isNotEmpty())
        assertFalse("Search page 2 differs from page 1", s1Page.items.first().url == s2Page.items.first().url)

        val (_, quickJson) = fetchText(GnulaHDMarkup.buildQuickSearchUrl("matrix"))
        val quickItems = GnulaHDMarkup.parseQuickSearchJson(quickJson)
        assertTrue("Quick search matrix non-empty", quickItems.isNotEmpty())
    }

    @Test
    fun `live details handle movie series anime quirks and player payload decode`() {
        val (pelUrl, pelHtml) = fetchText("${GnulaHDMarkup.MAIN_URL}/ver/peliculas/")
        val firstMovieUrl = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(pelHtml, pelUrl)).items.first().url
        val (mu, mHtml) = fetchText(firstMovieUrl)
        val movie = GnulaHDMarkup.parseDetail(Jsoup.parse(mHtml, mu), mu)!!
        assertEquals(GnulaMediaKind.MOVIE, movie.kind)
        assertFalse(movie.comingSoon)
        assertNotNull(movie.playerId)
        assertNotNull(movie.playerToken)

        val (_, apiRaw) = fetchText(GnulaHDMarkup.buildPlayerApiUrl(movie.playerId!!, movie.playerToken!!), referer = mu)
        val cipher = GnulaHDMarkup.extractPlayerApiCiphertext(apiRaw)!!
        val decoded = GnulaHDMarkup.decodeGnrdPayload(cipher)!!
        val payload = GnulaHDMarkup.parsePlayerPayload(decoded)!!
        assertTrue("Movie has at least one language group", payload.langs.isNotEmpty())

        val (crUrl, crHtml) = fetchText("${GnulaHDMarkup.MAIN_URL}/ver/the-crown/")
        val crown = GnulaHDMarkup.parseDetail(Jsoup.parse(crHtml, crUrl), crUrl)!!
        assertEquals(GnulaMediaKind.SERIES, crown.kind)
        assertEquals(60, crown.episodes.size)
        assertEquals(1, crown.episodes.first().season)
        assertEquals(1, crown.episodes.first().episode)
        assertEquals(6, crown.episodes.last().season)
        assertEquals(10, crown.episodes.last().episode)

        val (opUrl, opHtml) = fetchText("${GnulaHDMarkup.MAIN_URL}/ver/one-piece-1999/")
        val op = GnulaHDMarkup.parseDetail(Jsoup.parse(opHtml, opUrl), opUrl)!!
        assertEquals(GnulaMediaKind.ANIME, op.kind)
        assertTrue("One Piece has 1000+ episodes", op.episodes.size >= 1000)
        assertTrue("One Piece includes rescued ep 1176", op.episodes.any { it.episode == 1176 })

        val (epUrl, epHtml) = fetchText("${GnulaHDMarkup.MAIN_URL}/the-crown-6x10/")
        val epDetail = GnulaHDMarkup.parseDetail(Jsoup.parse(epHtml, epUrl), epUrl)!!
        assertEquals("${GnulaHDMarkup.MAIN_URL}/ver/the-crown/", epDetail.parentSeriesUrl)
        assertNotNull(epDetail.playerId)

        // Verify live Vidara API with #gnraw stripped on real server if present
        val vidaraSrv = payload.langs.flatMap { it.servers }.firstOrNull { it.src.contains("vidara", ignoreCase = true) }
        if (vidaraSrv != null) {
            val clean = GnulaHDMarkup.stripFragment(vidaraSrv.src)
            val base = GnulaHttpDefaults.hostBaseUrl(clean, "https://vidara.to")
            val code = clean.substringBefore('?').trimEnd('/').substringAfterLast('/')
            val body = """{"filecode":"$code","device":"web"}""".toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$base/api/stream")
                .header("User-Agent", GnulaHDMarkup.DEFAULT_USER_AGENT)
                .header("Origin", base)
                .header("Referer", "$base/")
                .post(body)
                .build()
            client.newCall(req).execute().use { res ->
                assertTrue("Vidara API HTTP ${res.code}", res.isSuccessful)
                assertTrue("Vidara streaming_url present", res.body.string().contains("streaming_url"))
            }
        }
    }
}
