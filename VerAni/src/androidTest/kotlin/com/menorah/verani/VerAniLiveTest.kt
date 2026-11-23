package com.menorah.verani

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerAniLiveTest {
    @Test fun catalogueDetailsAndPlayersWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        // The app's HTTP client is supplied by CloudStream at runtime, not by the
        // instrumentation APK. Exercise the same HTTP-first/browser-fallback policy.
        val browser = BrowserSiteTransport(InstrumentationRegistry.getInstrumentation().targetContext)
        val http = SiteTransport { url, headers ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
                try {
                    val code = conn.responseCode
                    val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    SiteResponse(code, conn.url.toString(), text)
                } finally { conn.disconnect() }
            }
        }
        val client = SiteClient(http, browser)
        suspend fun doc(path: String): org.jsoup.nodes.Document {
            android.util.Log.i("VerAniProbe", "Consultando $path")
            val response = client.get("https://verani.me$path")
            assertEquals("HTTP $path", 200, response.code)
            return Jsoup.parse(response.text, response.url)
        }
        val first = doc("/animes/"); val second = doc("/animes/page/2/")
        val page1 = VerAniMarkup.cards(first); val page2 = VerAniMarkup.cards(second)
        assertTrue(page1.isNotEmpty()); assertTrue(VerAniMarkup.hasNext(first))
        assertTrue(page2.any { candidate -> page1.none { it.url == candidate.url } })
        val movies = VerAniMarkup.cards(doc("/peliculas/")); assertTrue(movies.any { it.movie && it.poster != null })
        val search = doc("/?s=dragon+ball"); assertTrue(VerAniMarkup.cards(search).isNotEmpty()); assertTrue(VerAniMarkup.hasNext(search))
        val animeTops = doc("/tops/animes/")
        for (key in listOf("rated_anime", "votes_anime", "viewed", "commented", "reactions", "fav_anime")) {
            val ranking = VerAniMarkup.ranking(animeTops, key, false)
            assertTrue("Ranking de anime vacío: $key", ranking.isNotEmpty())
            assertTrue(ranking.all { !it.movie && isSiteUrl(it.url) })
        }
        val movieTops = doc("/tops/peliculas/")
        for (key in listOf("peli_viewed", "peli_commented", "peli_reactions", "fav_peli")) {
            val ranking = VerAniMarkup.ranking(movieTops, key, true)
            assertTrue("Ranking de películas vacío: $key", ranking.isNotEmpty())
            assertTrue(ranking.all { it.movie && isSiteUrl(it.url) })
        }
        val top = VerAniMarkup.ranking(animeTops, "rated_anime", false)
        assertTrue(top.size > 30)
        val top1 = VerAniMarkup.rankingPage(top, 1); val top2 = VerAniMarkup.rankingPage(top, 2)
        assertTrue(top1.hasNext); assertTrue(top2.cards.isNotEmpty())
        assertTrue(top1.cards.map { it.url }.intersect(top2.cards.map { it.url }.toSet()).isEmpty())
        val example = doc("/kiniro-no-corda-primo-passo/")
        assertNotNull(VerAniMarkup.backdrop(example))
        assertEquals("La ficha sin portada específica debe usar su fondo, no el logo", VerAniMarkup.backdrop(example), VerAniMarkup.poster(example))
        val details = doc("/dragon-ball/"); val episodes = VerAniMarkup.episodes(details)
        assertTrue("Se espera la lista completa de episodios", episodes.size > 100)
        assertTrue(episodes.first().number < episodes.last().number)
        val episode = doc("/dragon-ball/capitulo-1-bulma-y-son-goku/")
        val servers = VerAniMarkup.servers(episode)
        assertTrue(servers.any { it.language.contains("Latino") })
        assertTrue(servers.any { it.url.contains("player.zilla-networks.com/m3u8/") })
        // Report actual segment accessibility separately: a valid playlist alone
        // does not prove playback and the host may deny a particular network.
        val stream = servers.firstOrNull { it.url.contains("player.zilla-networks.com/m3u8/") }
        if (stream != null) {
            val checks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val record = org.json.JSONObject()
                try {
                    val manifest = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
                    manifest.connectTimeout = 8000; manifest.readTimeout = 8000
                    manifest.setRequestProperty("Referer", "https://verani.me/dragon-ball/capitulo-1-bulma-y-son-goku/")
                    try {
                        record.put("manifestStatus", manifest.responseCode)
                        if (manifest.responseCode == 200) {
                            val text = manifest.inputStream.bufferedReader().use { it.readText() }
                            record.put("isHls", text.trimStart().startsWith("#EXTM3U"))
                            val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                            if (first != null) {
                                val segment = java.net.URL(java.net.URL(stream.url), first).openConnection() as java.net.HttpURLConnection
                                segment.connectTimeout = 8000; segment.readTimeout = 8000
                                segment.setRequestProperty("Referer", "https://verani.me/dragon-ball/capitulo-1-bulma-y-son-goku/")
                                segment.setRequestProperty("Range", "bytes=0-1023")
                                try { record.put("segmentStatus", segment.responseCode) } finally { segment.disconnect() }
                            }
                        }
                    } finally { manifest.disconnect() }
                } catch (e: Exception) { record.put("error", e.javaClass.simpleName) }
                record
            }
            android.util.Log.i("VerAniProbe", checks.toString())
        }
        val movie = doc("/pelicula/el-nino-y-la-bestia/")
        assertTrue(VerAniMarkup.servers(movie).any { it.language.contains("Japonés") })
        assertEquals(VerAniMarkup.image(movie.selectFirst(".pelicula-poster-image")), VerAniMarkup.poster(movie))
        assertEquals(VerAniMarkup.image(movie.selectFirst(".pelicula-backdrop-image")), VerAniMarkup.backdrop(movie))
        assertNotNull(VerAniMarkup.poster(movie)); assertNotNull(VerAniMarkup.plot(movie))
        android.util.Log.i("VerAniProbe", org.json.JSONObject(mapOf("animePage1" to page1.size, "animePage2" to page2.size, "movies" to movies.size, "episodes" to episodes.size, "episodeServers" to servers.size, "movieServers" to VerAniMarkup.servers(movie).size)).toString())
    }
}
