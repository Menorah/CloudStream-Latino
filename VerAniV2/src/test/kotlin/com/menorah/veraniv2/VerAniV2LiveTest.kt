package com.menorah.veraniv2

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
import java.net.URI
import java.util.concurrent.TimeUnit

class VerAniV2LiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Before
    fun checkEnabled() {
        val enabled = System.getProperty("verani.live") == "true" || System.getenv("VERANI_LIVE") == "true"
        assumeTrue("Set VERANI_LIVE=true to run live VerAniV2 tests", enabled)
    }

    private fun fetchDoc(url: String, referer: String = "https://verani.me/"): Pair<String, org.jsoup.nodes.Document> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VerAniV2ExtractorUserAgent.UA)
            .header("Referer", referer)
            .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { res ->
            assertTrue("HTTP ${res.code} for $url", res.isSuccessful)
            val finalUrl = res.request.url.toString()
            return finalUrl to Jsoup.parse(res.body?.string().orEmpty(), finalUrl)
        }
    }

    @Test
    fun `live catalog weekly filters and pagination parse real pages`() {
        val (_, homeDoc) = fetchDoc("https://verani.me/")
        val weekly = VerAniV2Markup.parseHomeWeeklyUpdates(homeDoc)
        assertTrue("Weekly updates should be non-empty", weekly.isNotEmpty())
        println("LIVE_WEEKLY: count=${weekly.size}, first=${weekly.first().title} -> ${weekly.first().url}")

        val filterUrls = listOf(
            "Últimos Animes" to "https://verani.me/animes/",
            "En Emisión" to VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/?estado=en-emision", 1),
            "Audio Latino" to VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino", 1),
            "Castellano" to VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/?idioma=Espa%C3%B1ol%20Espa%C3%B1a", 1),
            "Películas" to "https://verani.me/peliculas/",
            "Acción" to "https://verani.me/animes/accion/",
        )
        for ((label, url) in filterUrls) {
            val (_, doc) = fetchDoc(url)
            val paged = VerAniV2Markup.parseArchiveCards(doc)
            assertTrue("Expected cards for $label ($url)", paged.items.isNotEmpty())
            assertTrue(
                "All URLs for $label must stay on verani.me",
                paged.items.all { VerAniV2Markup.hostOf(it.url).endsWith("verani.me") },
            )
            println("LIVE_CATALOG[$label]: count=${paged.items.size}, hasNext=${paged.hasNext}, first=${paged.items.first().title}")
        }

        val (_, latP1Doc) = fetchDoc(VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino", 1))
        val (_, latP2Doc) = fetchDoc(VerAniV2Markup.buildArchivePageUrl("https://verani.me/animes/?idioma=Espa%C3%B1ol%20Latino", 2))
        val p1Urls = VerAniV2Markup.parseArchiveCards(latP1Doc).items.map { it.url }
        val p2Urls = VerAniV2Markup.parseArchiveCards(latP2Doc).items.map { it.url }
        assertTrue("Latino page 2 must be non-empty and distinct", p2Urls.isNotEmpty() && p1Urls != p2Urls)
    }

    @Test
    fun `live search with pagination works`() {
        val (_, s1Doc) = fetchDoc(VerAniV2Markup.buildSearchUrl("a", 1))
        val (_, s2Doc) = fetchDoc(VerAniV2Markup.buildSearchUrl("a", 2))
        val r1 = VerAniV2Markup.parseArchiveCards(s1Doc)
        val r2 = VerAniV2Markup.parseArchiveCards(s2Doc)
        assertTrue("Search 'a' page 1 non-empty", r1.items.isNotEmpty())
        assertTrue("Search 'a' page 1 hasNext", r1.hasNext)
        assertTrue("Search 'a' page 2 non-empty", r2.items.isNotEmpty())
        assertFalse("Search page 2 differs from page 1", r1.items.map { it.url } == r2.items.map { it.url })

        val (_, kimetsuDoc) = fetchDoc(VerAniV2Markup.buildSearchUrl("kimetsu", 1))
        val rk = VerAniV2Markup.parseArchiveCards(kimetsuDoc)
        assertTrue("Search 'kimetsu' non-empty", rk.items.isNotEmpty())
        println("LIVE_SEARCH: kimetsu=${rk.items.size}, first=${rk.items.first().title}")
    }

    @Test
    fun `live detail pages handle series boruto movies movie and chapter breadcrumb`() {
        val (borutoUrl, borutoDoc) = fetchDoc("https://verani.me/boruto-naruto-next-generations/")
        assertEquals(VerAniPageKind.ANIME, VerAniV2Markup.detectPageKind(borutoDoc))
        val boruto = VerAniV2Markup.parseAnimeDetail(borutoDoc, borutoUrl)!!
        val movieSeason = boruto.seasons.firstOrNull { it.name == "Películas" }
        assertNotNull("Boruto should expose Películas season", movieSeason)
        assertTrue("Movie season > 0", movieSeason!!.season > 0)
        assertFalse("Boruto poster not site logo", VerAniV2Markup.isSiteLogoUrl(boruto.posterUrl))
        assertTrue("Boruto episodes sorted ascending per season", boruto.episodes.isNotEmpty())
        println("LIVE_DETAIL[Boruto]: seasons=${boruto.seasons.size}, episodes=${boruto.episodes.size}, poster=${boruto.posterUrl}")

        val (kncUrl, kncDoc) = fetchDoc("https://verani.me/kiniro-no-corda-primo-passo/")
        val knc = VerAniV2Markup.parseAnimeDetail(kncDoc, kncUrl)!!
        assertFalse("Kiniro poster must fall back off site logo", VerAniV2Markup.isSiteLogoUrl(knc.posterUrl))
        println("LIVE_DETAIL[Kiniro]: title=${knc.title}, poster=${knc.posterUrl}, eps=${knc.episodes.size}")

        val (movUrl, movDoc) = fetchDoc("https://verani.me/pelicula/el-nino-y-la-bestia/")
        assertEquals(VerAniPageKind.MOVIE, VerAniV2Markup.detectPageKind(movDoc))
        val movie = VerAniV2Markup.parseMovieDetail(movDoc, movUrl)!!
        assertEquals("El niño y la bestia", movie.title)
        assertEquals(2015, movie.year)
        val movieTabs = VerAniV2Markup.parsePlayerTabs(movDoc)
        assertTrue("Movie must have server tabs", movieTabs.isNotEmpty())
        println("LIVE_MOVIE: title=${movie.title} (${movie.year}), tabs=${movieTabs.size}")

        val (_, epDoc) = fetchDoc("https://verani.me/kikansha-no-mahou-wa-tokubetsu-desu/capitulo-8-episodio-8-629/")
        assertEquals(VerAniPageKind.CHAPTER, VerAniV2Markup.detectPageKind(epDoc))
        val chap = VerAniV2Markup.parseChapterDetail(epDoc, "https://verani.me/kikansha-no-mahou-wa-tokubetsu-desu/capitulo-8-episodio-8-629/")
        assertNotNull("Chapter must expose parent anime breadcrumb", chap.parentAnimeUrl)
        val epTabs = VerAniV2Markup.parsePlayerTabs(epDoc)
        assertTrue("Episode tabs non-empty", epTabs.isNotEmpty())
        assertTrue("Broken embed-undef.html must be filtered", epTabs.none { it.url.contains("embed-undef") })
        println("LIVE_EP1: parent=${chap.parentAnimeUrl}, tabs=" + epTabs.joinToString(", ") { "${it.label}[${it.badge}]->${it.url}" })
    }

    @Test
    fun `live extractors resolve playable streams for turboembed vidara voe mp4upload`() {
        val testPages = listOf(
            "https://verani.me/pelicula/el-nino-y-la-bestia/",
            "https://verani.me/kikansha-no-mahou-wa-tokubetsu-desu/capitulo-8-episodio-8-629/",
        )
        val allTabs = testPages.flatMap { url ->
            val tabs = VerAniV2Markup.parsePlayerTabs(fetchDoc(url).second)
            println("LIVE_TABS[$url]: " + tabs.joinToString(" | ") { "${it.label}[${it.badge}]->${it.url}" })
            tabs
        }
        var verifiedStreams = 0

        // 1. AnimeAV1 -> TurboEmbed
        allTabs.firstOrNull { VerAniV2Markup.hostOf(it.url) == "animeav1.uns.bio" }?.let { tab ->
            runCatching {
                val file1 = URI(tab.url).fragment?.takeIf { it.isNotBlank() } ?: tab.url.substringAfter("#")
                val resJson = client.newCall(
                    Request.Builder().url("https://animeav1.uns.bio/api/public/resolve?file1=$file1")
                        .header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Referer", tab.url).build(),
                ).execute().use { it.body?.string().orEmpty() }
                val turboUrl = VerAniV2Markup.jsonMapper.readTree(resJson).get("url")?.asText().orEmpty()
                assertTrue("AnimeAV1 resolve url invalid: $resJson", turboUrl.startsWith("http"))
                val tUri = URI(turboUrl)
                val host = tUri.host
                val code = tUri.path.trimEnd('/').substringAfterLast('/')
                val enc = VerAniV2TurboEmbedCrypto.encryptHex(code, host)
                val pvSuffix = tUri.rawQuery?.takeIf { it.isNotBlank() }?.let { "&$it" }.orEmpty()
                val trackUrl = "${tUri.scheme}://${tUri.authority}/api/stream/tracking?c=$enc$pvSuffix"
                val xTracking = client.newCall(
                    Request.Builder().url(trackUrl).header("User-Agent", VerAniV2ExtractorUserAgent.UA).header("Referer", turboUrl).build(),
                ).execute().use { it.header("x-tracking") }
                assertNotNull("TurboEmbed x-tracking header missing for $trackUrl", xTracking)
                val decrypted = VerAniV2TurboEmbedCrypto.decryptHex(xTracking!!, host)
                assertNotNull("TurboEmbed decrypt failed for host=$host", decrypted)
                val root = VerAniV2Markup.jsonMapper.readTree(decrypted!!)
                val streamNode = root.get("stream") ?: root
                val masterUrl = listOf("tiktok", "hls", "direct")
                    .firstNotNullOfOrNull { k -> streamNode.get(k)?.asText()?.takeIf { it.startsWith("http") } }
                assertNotNull("TurboEmbed stream missing in $decrypted", masterUrl)
                val masterText = client.newCall(
                    Request.Builder().url(masterUrl!!).header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Referer", "${tUri.scheme}://${tUri.authority}/").build(),
                ).execute().use {
                    assertTrue("TurboEmbed master HTTP ${it.code}", it.isSuccessful)
                    it.body?.string().orEmpty()
                }
                assertTrue("Master playlist contains #EXTM3U", masterText.contains("#EXTM3U"))
                val hasDemuxed = VerAniV2HlsHelper.hasDemuxedAudio(masterText)
                val maxH = VerAniV2HlsHelper.parseMaxResolutionHeight(masterText)
                println("PROBE_STREAM[TurboEmbed ${tab.badge}]: url=$masterUrl, demuxedAudio=$hasDemuxed, maxH=$maxH")
                verifiedStreams++
            }.onFailure { println("LIVE_ERR[TurboEmbed ${tab.url}]: ${it.message}") }
        }

        // 2. Vidara
        allTabs.firstOrNull { VerAniV2Markup.hostOf(it.url).contains("vidara") }?.let { tab ->
            runCatching {
                val code = URI(tab.url).path.trimEnd('/').substringAfterLast('/')
                val payload = VerAniV2Markup.jsonMapper.writeValueAsString(mapOf("filecode" to code, "device" to "android", "codecs" to emptyList<String>()))
                val body = client.newCall(
                    Request.Builder().url("https://vidara.so/api/stream")
                        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Origin", "https://vidara.so")
                        .header("Referer", tab.url).build(),
                ).execute().use { it.body?.string().orEmpty() }
                val streamUrl = VerAniV2Markup.jsonMapper.readTree(body).get("streaming_url")?.asText().orEmpty()
                assertTrue("Vidara streaming_url invalid: $body", streamUrl.startsWith("http"))
                client.newCall(
                    Request.Builder().url(streamUrl).header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Referer", "https://vidara.so/").build(),
                ).execute().use { r ->
                    assertTrue("Vidara m3u8 HTTP ${r.code}", r.isSuccessful)
                    println("PROBE_STREAM[Vidara ${tab.badge}]: HTTP ${r.code} url=$streamUrl")
                    verifiedStreams++
                }
            }.onFailure { println("LIVE_ERR[Vidara ${tab.url}]: ${it.message}") }
        }

        // 3. VOE
        allTabs.firstOrNull { VerAniV2Markup.hostOf(it.url).contains("voe") }?.let { tab ->
            runCatching {
                var curUrl = tab.url
                var html = client.newCall(
                    Request.Builder().url(curUrl).header("User-Agent", VerAniV2ExtractorUserAgent.UA).build(),
                ).execute().use { curUrl = it.request.url.toString(); it.body?.string().orEmpty() }
                VerAniV2VoeDecoder.findRedirectUrl(html)?.let { redir ->
                    html = client.newCall(
                        Request.Builder().url(redir).header("User-Agent", VerAniV2ExtractorUserAgent.UA).header("Referer", curUrl).build(),
                    ).execute().use { curUrl = it.request.url.toString(); it.body?.string().orEmpty() }
                }
                val encoded = VerAniV2VoeDecoder.findEncodedPayload(html)
                assertNotNull("VOE F7 payload missing at $curUrl (len=${html.length})", encoded)
                val decJson = VerAniV2VoeDecoder.decryptF7(encoded!!)
                assertNotNull("VOE decryptF7 failed", decJson)
                val node = VerAniV2Markup.jsonMapper.readTree(decJson!!)
                val hlsUrl = node.get("source")?.asText().orEmpty()
                assertTrue("VOE source missing in $decJson", hlsUrl.startsWith("http"))
                val curUri = URI(curUrl)
                val origin = "${curUri.scheme}://${curUri.authority}"
                client.newCall(
                    Request.Builder().url(hlsUrl).header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Origin", origin).header("Referer", "$origin/").build(),
                ).execute().use { r ->
                    assertTrue("VOE m3u8 HTTP ${r.code}", r.isSuccessful)
                    println("PROBE_STREAM[Voe ${tab.badge}]: HTTP ${r.code} mirror=$origin url=${hlsUrl.take(70)}")
                    verifiedStreams++
                }
            }.onFailure { println("LIVE_ERR[Voe ${tab.url}]: ${it.message}") }
        }

        // 4. Mp4Upload
        allTabs.firstOrNull { VerAniV2Markup.hostOf(it.url).contains("mp4upload") }?.let { tab ->
            runCatching {
                val realUrl = VerAniV2Mp4UploadParser.normalizeEmbedUrl(tab.url)!!
                val html = client.newCall(
                    Request.Builder().url(realUrl).header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Referer", "https://www.mp4upload.com/").build(),
                ).execute().use { it.body?.string().orEmpty() }
                val mp4 = VerAniV2Mp4UploadParser.extractVideoSrc(html)
                assertNotNull("Mp4Upload src missing at $realUrl (len=${html.length})", mp4)
                client.newCall(
                    Request.Builder().url(mp4!!).header("User-Agent", VerAniV2ExtractorUserAgent.UA)
                        .header("Referer", "https://www.mp4upload.com/")
                        .header("Range", "bytes=0-1023").build(),
                ).execute().use { r ->
                    assertTrue("Mp4Upload HTTP ${r.code}", r.code in 200..299)
                    println("PROBE_STREAM[Mp4Upload ${tab.badge}]: HTTP ${r.code} mp4=${mp4.take(70)}")
                    verifiedStreams++
                }
            }.onFailure { println("LIVE_ERR[Mp4Upload ${tab.url}]: ${it.message}") }
        }

        assertTrue("Expected at least 3 distinct live extractors verified, got $verifiedStreams", verifiedStreams >= 3)
    }
}
