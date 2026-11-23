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
        val browser = BrowserSiteTransport(InstrumentationRegistry.getInstrumentation().targetContext)
        suspend fun doc(path: String): org.jsoup.nodes.Document {
            val response = browser.get("https://verani.me$path", emptyMap())
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
