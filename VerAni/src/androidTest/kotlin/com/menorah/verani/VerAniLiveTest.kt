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
        val details = doc("/dragon-ball/"); val episodes = VerAniMarkup.episodes(details)
        assertTrue("Se espera la lista completa de episodios", episodes.size > 100)
        assertTrue(episodes.first().number < episodes.last().number)
        val episode = doc("/dragon-ball/capitulo-1-bulma-y-son-goku/")
        val servers = VerAniMarkup.servers(episode)
        assertTrue(servers.any { it.language.contains("Latino") })
        assertTrue(servers.any { it.url.contains("player.zilla-networks.com/m3u8/") })
        val movie = doc("/pelicula/el-nino-y-la-bestia/")
        assertTrue(VerAniMarkup.servers(movie).any { it.language.contains("Japonés") })
        assertNotNull(VerAniMarkup.poster(movie)); assertNotNull(VerAniMarkup.plot(movie))
        android.util.Log.i("VerAniProbe", org.json.JSONObject(mapOf("animePage1" to page1.size, "animePage2" to page2.size, "movies" to movies.size, "episodes" to episodes.size, "episodeServers" to servers.size, "movieServers" to VerAniMarkup.servers(movie).size)).toString())
    }
}
