package com.menorah.pelicinehd

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class PeliCineHDMarkupTest {
    private fun site(path: String = "/movies/star-wars-the-mandalorian-and-grogu/") =
        "https://pelicinehd.com$path"

    private fun doc(html: String, url: String = site()): Document =
        Jsoup.parse(html, url)

    // --------------------------------------------------------- catalog & search

    @Test
    fun catalogCardsParseToroFilmMarkupAndSkipRelatedOrGenericAlts() {
        val document = doc(
            """
            <main>
              <ul class="post-lst">
                <li>
                  <article class="post dfx fcl movies">
                    <div class="post-thumbnail">
                      <figure><img src="https://image.tmdb.org/t/p/w185/nB1Hq2Gz8Q5lUQAGDHNdXbyfoIF.jpg" alt="Pacífico"></figure>
                      <span class="year">2026</span>
                      <div class="post-ql"><span class="Qlty">FHD 1080P</span></div>
                    </div>
                    <header class="entry-header"><h2 class="entry-title">Pacífico</h2></header>
                    <a href="https://pelicinehd.com/movies/pacifico/" class="lnk-blk"></a>
                  </article>
                </li>
                <li>
                  <article class="post dfx fcl series">
                    <div class="post-thumbnail">
                      <figure><img data-src="https://image.tmdb.org/t/p/w185/gCZ18cl7Pc4tgiwMPWHTgjABrhy.jpg" src="data:image/gif;base64,R0lGODlhAQAB" alt="Image Site"></figure>
                      <span class="year">2023</span>
                    </div>
                    <header class="entry-header"><h2 class="entry-title">ONE PIECE</h2></header>
                    <a href="/series/one-piece/" class="lnk-blk"></a>
                  </article>
                </li>
                <li>
                  <article class="post dfx fcl movies">
                    <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w500/bad.jpg" alt="Image Site"></figure></div>
                    <a href="/movies/sin-titulo/" class="lnk-blk"></a>
                  </article>
                </li>
                <li>
                  <article class="post dfx fcl movies">
                    <header class="entry-header"><h2 class="entry-title">Ajeno</h2></header>
                    <a href="https://otro.example/movies/ajeno/" class="lnk-blk"></a>
                  </article>
                </li>
              </ul>
            </main>
            """.trimIndent(),
            site("/movies/")
        )

        val cards = PeliCineHDMarkup.cards(document)
        assertEquals(2, cards.size)
        assertTrue(cards[0].movie)
        assertEquals("Pacífico", cards[0].title)
        assertEquals("https://pelicinehd.com/movies/pacifico/", cards[0].url)
        assertEquals("https://image.tmdb.org/t/p/w500/nB1Hq2Gz8Q5lUQAGDHNdXbyfoIF.jpg", cards[0].poster)
        assertEquals(2026, cards[0].year)
        assertEquals("FHD 1080P", cards[0].quality)

        assertFalse(cards[1].movie)
        assertEquals("ONE PIECE", cards[1].title)
        assertEquals("https://pelicinehd.com/series/one-piece/", cards[1].url)
        assertEquals("https://image.tmdb.org/t/p/w500/gCZ18cl7Pc4tgiwMPWHTgjABrhy.jpg", cards[1].poster)
    }

    @Test
    fun paginationAndSearchUrlsHandleArchivesAndQueries() {
        assertEquals("/movies/", PeliCineHDMarkup.pageUrl("/movies/", 1))
        assertEquals("/movies/page/2/", PeliCineHDMarkup.pageUrl("/movies/", 2))
        assertEquals("/category/accion/page/3/", PeliCineHDMarkup.pageUrl("/category/accion", 3))
        assertEquals("/?s=el+planeta", PeliCineHDMarkup.searchUrl(" el planeta ", 1))
        assertEquals("/page/2/?s=avatar", PeliCineHDMarkup.searchUrl("avatar", 2))

        val archivePage1 = doc(
            """<nav class="navigation pagination"><div class="nav-links"><span class="page-numbers current">1</span><a class="page-numbers" href="https://pelicinehd.com/movies/page/2/">2</a><a class="next page-numbers" href="/movies/page/2/">Siguiente</a></div></nav>""",
            site("/movies/")
        )
        assertTrue(PeliCineHDMarkup.hasNextPage(archivePage1, 1, "/movies/"))
        assertFalse(PeliCineHDMarkup.hasNextPage(archivePage1, 2, "/movies/"))

        val searchPage1 = doc(
            """<div class="nav-links"><span class="current">1</span><a href="https://pelicinehd.com/page/2/?s=avatar">2</a></div>""",
            site("/?s=avatar")
        )
        assertTrue(PeliCineHDMarkup.hasNextPage(searchPage1, 1, "", "avatar"))

        val emptySearch = doc("""<main><p>No se encontraron resultados.</p></main>""", site("/?s=zzzznotfound99999"))
        assertTrue(PeliCineHDMarkup.cards(emptySearch).isEmpty())
        assertFalse(PeliCineHDMarkup.hasNextPage(emptySearch, 1, "", "zzzznotfound99999"))
    }

    // --------------------------------------------------------- movie & series detail

    @Test
    fun movieDetailExtractsMetadataAndRejectsSelfUrlBackdropBug() {
        // Real edge case observed on /movies/x/: ToroFilm emits the page's own URL in .bghd img when TMDB backdrop is absent.
        val selfBackdropPage = doc(
            """
            <div class="bghd"><img src="https://pelicinehd.com/movies/x/" alt="Background"></div>
            <article class="post single">
              <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w185/posterX.jpg" alt="X"></figure></div>
              <header class="entry-header"><h1 class="entry-title">X</h1></header>
              <div class="entry-meta">
                <span class="year">2022</span>
                <span class="duration">1h 45m</span>
              </div>
              <div class="description"><p>En 1979, un grupo de jóvenes cineastas...</p></div>
              <div class="vote-cn"><span class="vote"><span class="num">6.7</span></span></div>
              <div class="tagcloud"><a href="/category/terror/">Terror</a><a href="/category/suspense/">Suspense</a></div>
              <ul class="cast-lst"><li><a href="#">Mia Goth</a></li><li><a href="#">Jenna Ortega</a></li></ul>
              <div class="choose-season"><!-- some movie pages keep an empty season wrapper --></div>
              <div id="mdl-trailer"><iframe data-src="https://www.youtube.com/embed/Awg3cWuHfoc"></iframe></div>
            </article>
            <section class="related">
              <ul class="post-lst">
                <li><article class="post movies"><h2 class="entry-title">Pearl</h2><a href="/movies/pearl/" class="lnk-blk"></a></article></li>
              </ul>
            </section>
            <div class="modal"><h2>Únete a Telegram</h2><img src="https://pelicinehd.com/wp-content/uploads/2026/06/cropped-logo-pelicine-1.webp"></div>
            """.trimIndent(),
            site("/movies/x/")
        )

        val detail = PeliCineHDMarkup.detail(selfBackdropPage)
        assertNotNull(detail)
        detail!!
        assertTrue(detail.movie)
        assertEquals("X", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w500/posterX.jpg", detail.poster)
        // Must fall back to poster instead of returning https://pelicinehd.com/movies/x/!
        assertEquals("https://image.tmdb.org/t/p/w500/posterX.jpg", detail.backdrop)
        assertEquals(2022, detail.year)
        assertEquals(105, detail.durationMinutes)
        assertEquals(listOf("Terror", "Suspense"), detail.genres)
        assertEquals(listOf("Mia Goth", "Jenna Ortega"), detail.actors)
        assertEquals(6.7, detail.rating!!, 0.01)
        assertEquals("https://www.youtube.com/embed/Awg3cWuHfoc", detail.trailer)
        assertEquals(listOf("Pearl"), detail.recommendations.map { it.title })
    }

    @Test
    fun movieDetailUpgradesValidTmdbBackdropAndHandlesNumericTitles() {
        val page = doc(
            """
            <div class="bghd"><img data-src="https://image.tmdb.org/t/p/w780/back180.jpg" alt="Background"></div>
            <article class="post single">
              <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w185/p180.jpg"></figure></div>
              <header class="entry-header"><h1 class="entry-title">180</h1></header>
            </article>
            """.trimIndent(),
            site("/movies/180/")
        )
        val detail = PeliCineHDMarkup.detail(page)!!
        assertEquals("180", detail.title)
        assertEquals("https://image.tmdb.org/t/p/w1280/back180.jpg", detail.backdrop)
    }

    @Test
    fun seasonsAndEpisodesSortNumericallyEvenWhenSiteListsThemOutOfOrder() {
        val seriesDoc = doc(
            """
            <article class="post single">
              <header class="entry-header"><h1 class="entry-title">ONE PIECE</h1></header>
              <div class="choose-season">
                <ul class="sel-temp">
                  <li><a data-post="412" data-season="2" href="#">Temporada 2</a></li>
                  <li><a data-post="412" data-season="1" class="on" href="#">Temporada 1</a></li>
                </ul>
              </div>
              <ul id="episode_by_temp">
                <li>
                  <article class="post dfx fcl episodes">
                    <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w185/ep2.jpg"></figure><span class="num-epi">1x2</span></div>
                    <h2 class="entry-title">El hombre del sombrero de paja</h2>
                    <a href="/episode/one-piece-1x2/" class="lnk-blk"></a>
                  </article>
                </li>
                <li>
                  <article class="post dfx fcl episodes">
                    <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w185/ep1.jpg"></figure><span class="num-epi">1x1</span></div>
                    <h2 class="entry-title">Amanecer de una aventura</h2>
                    <a href="/episode/one-piece-1x1/" class="lnk-blk"></a>
                  </article>
                </li>
              </ul>
            </article>
            """.trimIndent(),
            site("/series/one-piece/")
        )

        val seasons = PeliCineHDMarkup.seasons(seriesDoc)
        assertEquals(listOf(1, 2), seasons.map { it.season })
        assertEquals("412", seasons[0].postId)

        val s1 = PeliCineHDMarkup.episodes(seriesDoc)
        assertEquals(listOf(1, 2), s1.map { it.number })
        assertEquals("Amanecer de una aventura", s1[0].title)
        assertEquals("https://image.tmdb.org/t/p/w500/ep1.jpg", s1[0].poster)

        // AJAX fragment returned by /wp-admin/admin-ajax.php (action=action_select_season)
        val ajaxFragment = Jsoup.parse(
            """
            <li>
              <article class="post dfx fcl episodes">
                <div class="post-thumbnail"><span class="num-epi">2x1</span></div>
                <h2 class="entry-title">Loguetown</h2>
                <a href="/episode/one-piece-2x1/" class="lnk-blk"></a>
              </article>
            </li>
            """.trimIndent(),
            site("/series/one-piece/")
        )
        val s2 = PeliCineHDMarkup.episodes(ajaxFragment, 2)
        assertEquals(1, s2.size)
        assertEquals(2, s2[0].season)
        assertEquals(1, s2[0].number)
        assertEquals("https://pelicinehd.com/episode/one-piece-2x1/", s2[0].url)
    }

    // --------------------------------------------------------- players & wrappers

    @Test
    fun playerOptionsDecodeLanguagesQualitiesAndTrembedWrappers() {
        val page = doc(
            """
            <div class="video-options">
              <ul class="aa-tbs aa-tbs-video">
                <li><a href="#options-0">OPCIÓN 1 Morencius - LAT HD</a></li>
                <li><a href="#options-1">OPCIÓN 2 Minochinos - CAS HD</a></li>
                <li><a href="#options-2">OPCIÓN 3 Minochinos - SUBTITULADO</a></li>
                <li><a href="#options-3">OPCIÓN 4 Morencius - LAT CAM</a></li>
                <li><a href="#options-4">OPCIÓN 5 Voe - LATÍNO HD-TS</a></li>
                <li><a href="#options-5">Trailer</a></li>
              </ul>
              <div class="aa-cn">
                <div id="options-0"><iframe data-src="https://pelicinehd.com/?trembed=0&#038;trid=101&#038;trtype=1"></iframe></div>
                <div id="options-1"><iframe data-src="https://pelicinehd.com/?trembed=1#038;trid=101#038;trtype=1"></iframe></div>
                <div id="options-2"><iframe src="https://minochinos.com/embed/td9hewhsozc5"></iframe></div>
                <div id="options-3"><iframe data-src="https://morencius.com/embed/15tgiexsgp7h"></iframe></div>
                <div id="options-4"><iframe data-src="https://voe.sx/e/abcd1234"></iframe></div>
                <div id="options-5"><iframe data-src="https://www.youtube.com/embed/xyz"></iframe></div>
              </div>
            </div>
            """.trimIndent(),
            site("/movies/la-odisea/")
        )

        val options = PeliCineHDMarkup.playerOptions(page)
        assertEquals(5, options.size)
        assertEquals("https://pelicinehd.com/?trembed=0&trid=101&trtype=1", options[0].url)
        assertEquals("Latino", options[0].language)
        assertNull(options[0].qualityTag)

        assertEquals("https://pelicinehd.com/?trembed=1&trid=101&trtype=1", options[1].url)
        assertEquals("Castellano", options[1].language)

        assertEquals("Subtitulado", options[2].language)
        assertEquals("Latino", options[3].language)
        assertEquals("CAM", options[3].qualityTag)
        assertEquals("Latino", options[4].language)
        assertEquals("HD-TS", options[4].qualityTag)

        val wrapperDoc = doc(
            """<div class="Video"><iframe src="https://morencius.com/embed/15tgiexsgp7h" frameborder="0"></iframe></div>""",
            site("/?trembed=0&trid=101&trtype=1")
        )
        assertEquals("https://morencius.com/embed/15tgiexsgp7h", PeliCineHDMarkup.unwrapWrapperUrl(wrapperDoc))
    }

    // --------------------------------------------------------- EarnVids / Morencius / Minochinos

    @Test
    fun earnVidsParserUnpacksMultiplePackersPrioritizesHls2AndResolvesRedirectOrigin() {
        // Simulates minochinos.com 302 -> callistanise.com with an unrelated ad packer before the player packer.
        val html = """
            <html><body>
            <script>eval(function(p,a,c,k,e,d){e=function(c){return c.toString(36)};if(!''.replace(/^/,String)){while(c--){d[c.toString(36)]=k[c]||c.toString(36)}k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c])}}return p}('0.1="2";',3,3,'window|adLoaded|ok'.split('|'),0,{}))</script>
            <script>eval(function(p,a,c,k,e,d){e=function(c){return c.toString(36)};if(!''.replace(/^/,String)){while(c--){d[c.toString(36)]=k[c]||c.toString(36)}k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c])}}return p}('0 1={"2":"3://4.5-6.7/2/a/b.8?tok=9","c":"3://d.e.f/c/b.g","h":"/i/j/b.8"};k("l").m({n:[{o:1.h||1.c||1.2,p:"q"}],r:[{o:"/s?op=get_slides",t:"u"},{o:"3://v.5-6.7/w/b.x",y:"Español",t:"z"}]});',36,36,'var|links|hls2|https|pfabi|dramiyos|cdn|com|m3u8|sig123|01|master|hls3|bad|willowgrovelearning|store|txt|hls4|stream|E4JHcht9|jwplayer|vplayer|setup|sources|file|type|hls|tracks|dl|kind|thumbnails|cdn1|vtt|vtt|label|captions'.split('|'),0,{}))</script>
            </body></html>
        """.trimIndent()

        val parsed = EarnVidsParser.parse(html, "https://callistanise.com/embed/td9hewhsozc5")
        assertEquals("https://callistanise.com", parsed.finalOrigin)
        // hls2 MUST come first (pure MPEG-TS); hls4 must resolve against redirected callistanise.com, never minochinos.com!
        assertEquals(listOf("hls2", "hls3", "hls4"), parsed.streams.map { it.key })
        assertEquals("https://pfabi.dramiyos-cdn.com/hls2/01/master.m3u8?tok=sig123", parsed.streams[0].url)
        assertEquals("https://callistanise.com/stream/E4JHcht9/master.m3u8", parsed.streams[2].url)
        // Thumbnails track excluded; real caption track preserved.
        assertEquals(1, parsed.subtitles.size)
        assertEquals("Español", parsed.subtitles[0].label)
        assertEquals("https://cdn1.dramiyos-cdn.com/vtt/master.vtt", parsed.subtitles[0].url)
    }

    // --------------------------------------------------------- SiteClient & UA

    @Test
    fun userAgentStripperRemovesAndroidWebViewWvMarkerWithoutBreakingChromeVersion() {
        val raw = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240405.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Mobile Safari/537.36"
        val cleaned = SiteClient.cleanBrowserUserAgent(raw)
        assertFalse(cleaned.contains("; wv"))
        assertFalse(cleaned.contains("Version/4.0"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240405.002) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36",
            cleaned
        )
    }

    @Test
    fun siteClientSolvesChallengeOnceAndReusesClearanceCookiesOverHttp() = runBlocking {
        var httpCalls = 0
        var browserCalls = 0
        var currentCookie = ""
        val cookies = object : CookieStore {
            override fun getCookie(url: String): String = currentCookie
            override fun flush() = Unit
        }
        val http = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                httpCalls++
                return if (headers["Cookie"]?.contains("cf_clearance=ok") == true) {
                    SiteResponse(200, url, "<html>ok</html>")
                } else {
                    SiteResponse(403, url, "<title>Un momento…</title>")
                }
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(200, url, "ajax-ok")
        }
        val browser = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                browserCalls++
                currentCookie = "cf_clearance=ok"
                return SiteResponse(200, url, "<html>solved</html>")
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(200, url, "ajax-browser")
        }

        val client = SiteClient(http, browser, cookies, "CleanChromeUA")
        assertEquals("<html>solved</html>", client.get(site("/movies/")).text)
        assertEquals("<html>ok</html>", client.get(site("/series/")).text)
        assertEquals("<html>ok</html>", client.get(site("/category/accion/")).text)
        assertEquals(3, httpCalls)
        assertEquals(1, browserCalls)
    }

    @Test
    fun siteClientFallsBackToBrowserPreferenceWhenHttpIsRejectedEvenWithClearance() = runBlocking {
        var httpCalls = 0
        var browserCalls = 0
        val cookies = object : CookieStore {
            override fun getCookie(url: String): String = "cf_clearance=present"
            override fun flush() = Unit
        }
        val http = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                httpCalls++
                return SiteResponse(403, url, "<html>Forbidden</html>")
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(403, url, "Forbidden")
        }
        val browser = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                browserCalls++
                return SiteResponse(200, url, "<html>browser-ok</html>")
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse {
                browserCalls++
                return SiteResponse(200, url, "<li>ep</li>")
            }
        }

        val client = SiteClient(http, browser, cookies)
        assertEquals("<html>browser-ok</html>", client.get(site("/movies/")).text)
        assertEquals("<li>ep</li>", client.post(site("/wp-admin/admin-ajax.php"), mapOf("action" to "action_select_season")).text)
        assertEquals(1, httpCalls)
        assertEquals(2, browserCalls)
    }

    @Test
    fun siteClientCoalescesConcurrentChallengeSolvesIntoSingleBrowserCall() = runBlocking {
        var browserCalls = 0
        var cookie = ""
        val cookies = object : CookieStore {
            override fun getCookie(url: String): String = cookie
            override fun flush() = Unit
        }
        val http = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse =
                if (headers["Cookie"]?.contains("cf_clearance=ok") == true) SiteResponse(200, url, "ok")
                else SiteResponse(503, url, "<title>Just a moment...</title>")
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(200, url, "ok")
        }
        val browser = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                browserCalls++
                delay(50)
                cookie = "cf_clearance=ok"
                return SiteResponse(200, url, "solved")
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(200, url, "ok")
        }

        val client = SiteClient(http, browser, cookies)
        val results = coroutineScope {
            listOf("/movies/", "/series/", "/category/accion/", "/category/drama/").map { p ->
                async { client.get(site(p)).text }
            }.awaitAll()
        }
        assertEquals(1, browserCalls)
        assertTrue(results.all { it == "solved" || it == "ok" })
    }

    @Test
    fun siteClientPropagatesCancellationAndGuardsSiteUrls() = runBlocking {
        var browserCalls = 0
        val client = SiteClient(
            object : SiteTransport {
                override suspend fun get(url: String, headers: Map<String, String>): SiteResponse = throw CancellationException()
                override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse = throw CancellationException()
            },
            object : SiteTransport {
                override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                    browserCalls++
                    return SiteResponse(200, url, "ok")
                }
                override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse =
                    SiteResponse(200, url, "ok")
            }
        )
        try {
            client.get(site("/movies/"))
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(0, browserCalls)

        assertTrue(PeliCineHDMarkup.siteUrl("https://pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("http://pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://pelicinehd.com.evil.example/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://user@pelicinehd.com/movies/x/"))
        assertFalse(PeliCineHDMarkup.siteUrl("https://pelicinehd.com:8443/movies/x/"))
    }

    @Test
    fun siteClientFallsBackToAlternateBrowserUaAndSurfacesBrowserErrors() = runBlocking {
        var cookie = ""
        val seenHttpUa = mutableListOf<String>()
        val cookies = object : CookieStore {
            override fun getCookie(url: String): String = cookie
            override fun flush() = Unit
        }
        val http = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                seenHttpUa += headers["User-Agent"].orEmpty()
                return if (headers["Cookie"]?.contains("cf_clearance=clean") == true && headers["User-Agent"] == "CleanUA") {
                    SiteResponse(200, url, "<html>ok</html>")
                } else {
                    SiteResponse(403, url, "<html><title>Attention Required! | Cloudflare</title></html>")
                }
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(403, url, "Forbidden")
        }
        val primaryBrowser = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>) =
                SiteResponse(403, url, "<div class=\"cf-turnstile\"></div>")
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(403, url, "Forbidden")
        }
        val backupBrowser = object : SiteTransport {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                cookie = "cf_clearance=clean"
                return SiteResponse(200, url, "<html>backup-ok</html>")
            }
            override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SiteResponse(200, url, "ok")
        }

        val client = SiteClient(http, primaryBrowser, cookies, "DefaultUA", backupBrowser, "CleanUA")
        assertEquals("<html>backup-ok</html>", client.get(site("/movies/")).text)
        assertEquals("<html>ok</html>", client.get(site("/series/")).text)
        assertEquals(listOf("DefaultUA", "CleanUA"), seenHttpUa)

        // When browser fails with a timeout after HTTP 403 refusal, throw the browser timeout rather than masking it as 403.
        val failingClient = SiteClient(
            object : SiteTransport {
                override suspend fun get(url: String, headers: Map<String, String>) = SiteResponse(403, url, "Forbidden")
                override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>) = SiteResponse(403, url, "Forbidden")
            },
            object : SiteTransport {
                override suspend fun get(url: String, headers: Map<String, String>): SiteResponse =
                    throw IllegalStateException("El navegador integrado agotó el tiempo.")
                override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse =
                    throw IllegalStateException("El navegador integrado agotó el tiempo.")
            }
        )
        try {
            failingClient.get(site("/movies/"))
            fail("expected browser error instead of stale HTTP 403")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("agotó el tiempo"))
        }

        assertTrue(SiteClient.looksLikeChallenge("""<script src="/cdn-cgi/challenge-platform/h/b/orchestrate"></script>"""))
        assertTrue(SiteClient.looksLikeChallenge("""<div class="cf-turnstile"></div>"""))
        assertTrue(SiteClient.looksLikeChallenge("""<title>Attention Required! | Cloudflare</title>"""))
        assertFalse(SiteClient.looksLikeChallenge("""<ul class="post-lst"><li><article class="post movies">Pacífico</article></li></ul>"""))
    }

    @Test
    fun userCapturedHtmlQuirksParseCleanly() {
        // 1. Homepage series card with CSS class `movies`, /peliculas/ & /release/2026/ pagination
        val archiveDoc = doc(
            """
            <main>
              <ul class="post-lst">
                <li class="series type-series">
                  <article class="post dfx fcl movies">
                    <header class="entry-header"><h2 class="entry-title">El Señor de los Anillos: Los Anillos de Poder</h2></header>
                    <div class="post-thumbnail or-1">
                      <figure><img src="https://image.tmdb.org/t/p/w500/xdsgZ60TBFiuAIvGPtxRsjDt4NV.jpg" alt="Image El Señor de los Anillos"></figure>
                      <span class="year">2022</span>
                    </div>
                    <a class="lnk-blk" href="https://pelicinehd.com/series/el-senor-de-los-anillos-los-anillos-de-poder/"></a>
                  </article>
                </li>
              </ul>
              <nav class="navigation pagination"><div class="nav-links">
                <a class="page-link current" href="https://pelicinehd.com/peliculas/">1</a>
                <a class="page-link" href="https://pelicinehd.com/peliculas/page/2/">2</a>
                <a class="page-link" href="https://pelicinehd.com/peliculas/page/2/">SIGUIENTE</a>
              </div></nav>
            </main>
            """.trimIndent(),
            site("/peliculas/")
        )
        val cards = PeliCineHDMarkup.cards(archiveDoc)
        assertEquals(1, cards.size)
        assertFalse(cards[0].movie)
        assertTrue(PeliCineHDMarkup.hasNextPage(archiveDoc, 1, "/peliculas/"))

        // 2. Movie detail with Directors + Actores in .cast-lst, Recomendadas owl-carousel (no .post-lst), and full-URL tab hrefs
        val odiseaDoc = doc(
            """
            <div class="bghd"><img class="TPostBg" src="https://image.tmdb.org/t/p/w780/twiVn9oFXOVR0uoYgawyEBlnFu8.jpg" alt="Background"></div>
            <article class="post single">
              <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w500/gr2v2nt0isUhTqnvNKeNQYpX0sO.jpg" alt="Image La Odisea"></figure></div>
              <header class="entry-header">
                <h1 class="entry-title">La Odisea</h1>
                <div class="entry-meta">
                  <span class="genres"><a href="https://pelicinehd.com/category/accion/">Acción</a>, <a href="https://pelicinehd.com/category/aventura/">Aventura</a></span>
                  <span class="duration">2h 52m</span>
                  <span class="year">2026</span>
                </div>
              </header>
              <ul class="cast-lst dfx fwp">
                <li><span>Director</span><p><a href="https://pelicinehd.com/director/christopher-nolan/">Christopher Nolan</a></p></li>
                <li><span>Actores</span><p><a href="https://pelicinehd.com/cast/adam-croasdell/">Adam Croasdell</a>, <a href="https://pelicinehd.com/cast/anne-hathaway/">Anne Hathaway</a></p></li>
              </ul>
              <ul class="aa-tbs aa-tbs-video">
                <li><a href="https://pelicinehd.com/movies/la-odisea/#options-0">OPCIÓN 1 Morencius -LAT HD</a></li>
                <li><a href="https://pelicinehd.com/movies/la-odisea/#options-1">OPCIÓN 2 Voe - LAT HD</a></li>
              </ul>
              <aside class="video-player" id="aa-options">
                <div id="options-0"><iframe data-src="https://pelicinehd.com/?trembed=0&amp;trid=19821&amp;trtype=1"></iframe></div>
                <div id="options-1"><iframe data-src="https://pelicinehd.com/?trembed=1&amp;trid=19821&amp;trtype=1"></iframe></div>
              </aside>
            </article>
            <section class="section episodes">
              <header class="section-header"><h3 class="section-title">Recomendadas</h3></header>
              <div class="owl-carousel owl-theme carousel">
                <div class="owl-item"><article class="post dfx fcl movies">
                  <header class="entry-header"><h2 class="entry-title">Tornados</h2></header>
                  <div class="post-thumbnail"><figure><img src="https://image.tmdb.org/t/p/w500/cpWWm1MVwFFjpM6TQ9QzP74ZXfH.jpg"></figure></div>
                  <a class="lnk-blk" href="https://pelicinehd.com/movies/tornados/"></a>
                </article></div>
              </div>
            </section>
            """.trimIndent(),
            site("/movies/la-odisea/")
        )
        val detail = PeliCineHDMarkup.detail(odiseaDoc)!!
        assertEquals("https://image.tmdb.org/t/p/w1280/twiVn9oFXOVR0uoYgawyEBlnFu8.jpg", detail.backdrop)
        assertEquals(172, detail.durationMinutes)
        assertEquals(listOf("Adam Croasdell", "Anne Hathaway"), detail.actors)
        assertEquals(listOf("Tornados"), detail.recommendations.map { it.title })

        val options = PeliCineHDMarkup.playerOptions(odiseaDoc)
        assertEquals(2, options.size)
        assertEquals("Latino", options[0].language)
        assertEquals("Latino", options[1].language)

        // 3. Episode embed where var links declares hls3 before hls2 and hls4 is absent (ei06pqvqtfey)
        val epEmbed = """
            <script>
            var links={"hls3":"https://dD2STLiWT0bC.radiantgroveproduction.cfd/mCJ01caRj2Dh8/hls3/01/01071/t8ktplkons7j_n/master.txt","hls2":"https://dD2STLiWT0bC.dramiyos-cdn.com/hls2/01/01071/t8ktplkons7j_n/master.m3u8?t=sig&s=1"};
            jwplayer("vplayer").setup({sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}],tracks:[{file:"/dl?op=get_slides",kind:"thumbnails"}]});
            </script>
        """.trimIndent()
        val parsedEmbed = EarnVidsParser.parse(epEmbed, "https://morencius.com/v/ei06pqvqtfey")
        assertEquals(listOf("hls2", "hls3"), parsedEmbed.streams.map { it.key })
        assertTrue(parsedEmbed.subtitles.isEmpty())
    }
}
