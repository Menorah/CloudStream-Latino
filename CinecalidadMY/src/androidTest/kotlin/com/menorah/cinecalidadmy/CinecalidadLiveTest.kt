package com.menorah.cinecalidadmy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
@RunWith(AndroidJUnit4::class)
class CinecalidadLiveTest {
    private fun doc(path: String): org.jsoup.nodes.Document {
        val c = URL("https://www.cinecalidad.my$path").openConnection() as HttpURLConnection
        c.connectTimeout=15000; c.readTimeout=15000; c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        try { assertEquals("HTTP $path",200,c.responseCode); return Jsoup.parse(c.inputStream.bufferedReader().use { it.readText() }, c.url.toString()) } finally { c.disconnect() }
    }
    @Test fun liveMovieCatalogAndPlayers() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        val first=doc("/"); val second=doc("/page/2/"); val a=CinecalidadMarkup.cards(first); val b=CinecalidadMarkup.cards(second)
        assertTrue(a.isNotEmpty()); assertTrue(CinecalidadMarkup.hasNext(first,1)); assertTrue(b.any { x -> a.none { it.url == x.url } })
        assertTrue(CinecalidadMarkup.cards(doc("/?s=iron")).isNotEmpty())
        val movie=doc("/pelicula/hermanastras-2026-online-descarga/")
        assertNotNull(CinecalidadMarkup.title(movie)); assertNotNull(CinecalidadMarkup.poster(movie)); assertNotNull(CinecalidadMarkup.plot(movie))
        val players=CinecalidadMarkup.players(movie); assertTrue(players.any { it.url.startsWith("https://voe.sx/e/") }); assertTrue(players.any { it.url.startsWith("https://bysedikamoum.com/e/") }); assertTrue(players.all { it.language=="Latino" })
    }
}
