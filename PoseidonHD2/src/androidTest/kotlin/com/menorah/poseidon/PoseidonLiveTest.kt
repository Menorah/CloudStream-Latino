package com.menorah.poseidon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class PoseidonLiveTest {
    private fun get(url: String): Pair<String, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        c.setRequestProperty("Referer", "https://www.poseidonhd2.co/")
        try {
            assertEquals("HTTP $url", 200, c.responseCode)
            return c.url.toString() to c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    @Test fun catalogSeasonsAndPlayersWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        fun doc(path: String): org.jsoup.nodes.Document { val (url, html) = get("https://www.poseidonhd2.co$path"); return Jsoup.parse(html, url) }
        val page1 = doc("/peliculas"); val page2 = doc("/peliculas/page/2")
        val first = PoseidonMarkup.cards(page1); val second = PoseidonMarkup.cards(page2)
        assertTrue("Películas vacías", first.isNotEmpty()); assertTrue(PoseidonMarkup.hasNext(page1, 1))
        assertTrue(second.any { x -> first.none { it.url == x.url } })
        assertTrue(PoseidonMarkup.cards(doc("/series")).any { !it.movie })
        assertTrue(PoseidonMarkup.cards(doc("/search?q=iron")).isNotEmpty())
        val movie = PoseidonMarkup.state(doc("/pelicula/9909/mentes-peligrosas"))!!
        assertEquals("Mentes peligrosas", PoseidonMarkup.media(movie, true).path("titles").path("name").asText())
        val options = PoseidonMarkup.players(movie)
        assertTrue(options.any { it.language == "Latino" }); assertTrue(options.any { it.language == "Castellano" }); assertTrue(options.any { it.language == "Subtitulado" })
        val (_, html) = get(options.first().url)
        assertNotNull(PoseidonMarkup.destination(html))
        val series = PoseidonMarkup.state(doc("/serie/223397/ciudad-de-dios-la-lucha-no-para"))!!.path("thisSerie")
        assertTrue(PoseidonMarkup.episodes(series).any { it.season == 1 })
        val ep = PoseidonMarkup.state(doc("/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/1"))!!
        assertTrue("Temporada 2 ausente", PoseidonMarkup.episodes(ep.path("serie")).any { it.season == 2 && it.number == 3 })
        assertTrue(PoseidonMarkup.players(ep).isNotEmpty())
    }
}
