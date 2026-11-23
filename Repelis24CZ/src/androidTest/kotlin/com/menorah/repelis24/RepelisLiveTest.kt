package com.menorah.repelis24

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class RepelisLiveTest {
    private fun request(url: String, form: Map<String, String>? = null): Pair<String, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        conn.setRequestProperty("Referer", "https://repelis24.cz/")
        if (form != null) {
            conn.requestMethod = "POST"; conn.doOutput = true
            val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            assertEquals("HTTP $url", 200, conn.responseCode)
            return conn.url.toString() to conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
    @Test fun catalogueMovieSeriesAndServerWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        fun doc(path: String): org.jsoup.nodes.Document {
            val (url, html) = request("https://repelis24.cz$path")
            return Jsoup.parse(html, url)
        }
        val first = doc("/peliculas/"); val second = doc("/peliculas/page/2/")
        assertTrue(RepelisMarkup.hasNext(first, 1))
        val pageOne = RepelisMarkup.cards(first); val pageTwo = RepelisMarkup.cards(second)
        assertTrue(pageOne.isNotEmpty()); assertTrue(pageTwo.any { p -> pageOne.none { it.url == p.url } })
        assertTrue(RepelisMarkup.cards(doc("/?s=iron")).isNotEmpty())
        for (filter in listOf("movies", "tv")) {
            val category = "/tendencias/?get=$filter"
            val a = doc(RepelisMarkup.pageUrl(category, 1)); val b = doc(RepelisMarkup.pageUrl(category, 2))
            assertTrue(RepelisMarkup.hasNext(a, 1))
            val one = RepelisMarkup.cards(a); val two = RepelisMarkup.cards(b)
            assertTrue(one.isNotEmpty() && two.any { x -> one.none { it.url == x.url } })
            assertTrue((one + two).all { it.movie == (filter == "movies") })
        }
        for (genre in listOf("accion", "animacion")) {
            val category = "/genero/$genre/"; val a = doc(RepelisMarkup.pageUrl(category, 1)); val b = doc(RepelisMarkup.pageUrl(category, 2))
            val one = RepelisMarkup.cards(a); val two = RepelisMarkup.cards(b)
            assertTrue(RepelisMarkup.hasNext(a, 1)); assertTrue(one.isNotEmpty() && two.any { x -> one.none { it.url == x.url } })
        }
        val series = doc("/series/game-of-thrones/")
        val episodes = RepelisMarkup.episodes(series)
        assertEquals(73, episodes.size); assertEquals((1..8).toList(), episodes.map { it.season }.distinct())
        assertEquals("74111", episodes.first().id)
        val movie = doc("/peliculas/codigo-venganza-2/")
        assertNotNull(RepelisMarkup.poster(movie)); assertNotNull(RepelisMarkup.backdrop(movie))
        assertNotNull(RepelisMarkup.poster(series))
        val options = RepelisMarkup.options(movie)
        assertTrue(options.any { it.language == "Latino" }); assertTrue(options.any { it.language == "Inglés" })
        val option = options.first { it.language == "Latino" }
        val (_, body) = request("https://repelis24.cz/wp-admin/admin-ajax.php", mapOf("action" to "doo_player_ajax", "post" to option.post, "nume" to option.number, "type" to option.type))
        assertTrue(JSONObject(body).optString("embed_url").startsWith("https://"))
        val (_, playerBody) = request("https://repelis24.cz/wp-admin/admin-ajax.php", mapOf("action" to "dpu_get_episode_player", "episode_id" to episodes.first().id))
        val episodeOptions = RepelisMarkup.episodePlayerOptions(playerBody, episodes.first().id)
        assertTrue(episodeOptions.any { it.language == "Latino" }); assertTrue(episodeOptions.any { it.language == "Inglés" })
        assertEquals("Latino", episodeOptions.first { it.number == "1" }.language)
        assertEquals("Inglés", episodeOptions.first { it.number == "4" }.language)
        val episodeOption = episodeOptions.first { it.language == "Latino" }
        val (_, episodeBody) = request("https://repelis24.cz/wp-admin/admin-ajax.php", mapOf("action" to "doo_player_ajax", "post" to episodeOption.post, "nume" to episodeOption.number, "type" to episodeOption.type))
        assertTrue(JSONObject(episodeBody).optString("embed_url").startsWith("https://"))
    }
}
