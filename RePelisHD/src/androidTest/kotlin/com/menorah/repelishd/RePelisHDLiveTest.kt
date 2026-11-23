package com.menorah.repelishd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RePelisHDLiveTest {
    private fun doc(path: String): org.jsoup.nodes.Document {
        val c = URL("https://repelishd.surf$path").openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        try { assertEquals("HTTP $path", 200, c.responseCode); return Jsoup.parse(c.inputStream.bufferedReader().use { it.readText() }, c.url.toString()) } finally { c.disconnect() }
    }
    @Test fun catalogueAndSeries() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        val a = doc("/explorar?t=movie"); val b = doc("/explorar?t=movie&page=2")
        assertTrue(RePelisHDMarkup.cards(a).isNotEmpty()); assertTrue(RePelisHDMarkup.hasNext(a,1)); assertTrue(RePelisHDMarkup.cards(b).any { x -> RePelisHDMarkup.cards(a).none { it.url == x.url } })
        assertTrue(RePelisHDMarkup.cards(doc("/explorar?t=tv")).any { !it.movie })
        assertTrue(RePelisHDMarkup.cards(doc("/buscar?q=iron")).isNotEmpty())
        val movie = doc("/ver-pelicula/1726-iron-man-online-espanol")
        assertEquals("tt0371746", RePelisHDMarkup.imdb(movie)); assertNotNull(RePelisHDMarkup.poster(movie)); assertNotNull(RePelisHDMarkup.plot(movie)); assertEquals(2008,RePelisHDMarkup.year(movie))
        val base = "/ver-serie/1413-american-horror-story-online-espanol"
        val state = RePelisHDMarkup.initial(doc(base))!!
        assertEquals(13,RePelisHDMarkup.seasons(state).size); assertEquals(12,RePelisHDMarkup.episodes(state).size)
        val second = RePelisHDMarkup.initial(doc("$base/temporada-2"))!!
        assertEquals(13,RePelisHDMarkup.episodes(second).size); assertEquals("tt1844624",second.path("imdb").asText())
    }
}
