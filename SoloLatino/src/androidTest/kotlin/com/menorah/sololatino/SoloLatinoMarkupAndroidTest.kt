package com.menorah.sololatino

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoloLatinoMarkupAndroidTest {
    @Test fun parsesSeasonAndNumberWithoutChangingTheSiteNumbering() {
        val doc = Jsoup.parse("""<div data-season-panel="2"><a class="ep-item" href="/serie/naruto/temporada-2/episodio-53"><p class="text-sm font-semibold">Episodio</p></a></div>""", "https://sololatino.net/serie/naruto")
        val episode = SoloLatinoMarkup.episodes(doc).single()
        assertEquals(2, episode.season)
        assertEquals(53, episode.number)
        assertEquals("Episodio", episode.title)
    }

    @Test fun nextPageAndPlayerLanguage() {
        val doc = Jsoup.parse("""<link rel="next" href="/peliculas?genero=terror&amp;page=2"><div data-lang-group="mx"><button class="server-btn" data-player-token="abc==">Principal</button></div>""", "https://sololatino.net/peliculas?genero=terror")
        assertTrue(SoloLatinoMarkup.hasNext(doc, 1))
        assertEquals("Latino", SoloLatinoMarkup.servers(doc).single().language)
    }
}
