package com.menorah.repelis24shop

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.CookieManager
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class Repelis24ShopLiveTest {
    private fun doc(path: String): org.jsoup.nodes.Document {
        val c = URL("https://repelis24.shop$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        c.setRequestProperty("Referer", "https://repelis24.shop/")
        try { assertEquals("HTTP $path", 200, c.responseCode); return Jsoup.parse(c.inputStream.bufferedReader().use { it.readText() }, c.url.toString()) }
        finally { c.disconnect() }
    }
    @Test fun nuuploadNumericArrayIsDecodedWithoutExecutingScript() {
        val source = "https://sv2.ibra.lat/video/"
        val encoded = source.map { c ->
            val value = "abc${c.code + 1234}xyz".toByteArray()
            "\"${android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)}\""
        }.joinToString(",")
        val html = "var arr=[$encoded]; var dest = \"\"; arr.forEach(function doIt(value) { dest += String.fromCharCode(parseInt(atob(value).replace(/\\D/g,'')) - 1234); });var sesz=\"abc123\";"
        assertEquals("$source?s=abc123", Repelis24ShopMarkup.media(html))
        assertNull(Repelis24ShopMarkup.media("no media"))
    }
    @Test fun catalogueAndSeries() {
        CookieHandler.setDefault(CookieManager())
        doc("/")
        val first = doc("/peliculas/"); val second = doc("/peliculas/page/2")
        assertTrue(Repelis24ShopMarkup.hasNext(first, 1)); assertTrue(Repelis24ShopMarkup.cards(first).isNotEmpty())
        assertTrue(Repelis24ShopMarkup.cards(second).any { a -> Repelis24ShopMarkup.cards(first).none { it.url == a.url } })
        assertTrue(Repelis24ShopMarkup.cards(doc("/buscar/iron+man")).any { it.title.contains("Iron Man", true) })
        val series = doc("/serie/the-terror/"); assertEquals(3, Repelis24ShopMarkup.seasons(series).size)
        assertEquals(10, Repelis24ShopMarkup.episodes(doc("/temporada/the-terror-1/"), 1).size)
        assertEquals(9, Repelis24ShopMarkup.episodes(doc("/temporada/the-terror-2/"), 2).size)
        val players = Repelis24ShopMarkup.players(doc("/pelicula/iron-man-zepn/"))
        assertTrue(players.any { it.language == "Latino" }); assertTrue(players.any { it.language == "Castellano" }); assertTrue(players.any { it.language == "Subtitulado" })
        assertTrue("Falta opción VOE", players.any { Repelis24ShopMarkup.voeFromWrapper(it.url) != null })
    }
}
