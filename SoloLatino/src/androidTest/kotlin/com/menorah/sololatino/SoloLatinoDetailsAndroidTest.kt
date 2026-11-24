package com.menorah.sololatino

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoloLatinoDetailsAndroidTest {
    @Test fun searchAndStructuredDetails() {
        val result = SoloLatinoDetails.suggestions("""[{"type":"anime","title":"Naruto","year":2002,"url":"https://sololatino.net/serie/naruto"},{"type":"person","title":"Persona","url":"https://sololatino.net/persona/1"}]""")
        assertEquals("anime", result.single().type)
        val html = """<h1>Naruto</h1><script type="application/ld+json">{"@graph":[{"@type":"TVSeries","datePublished":"2002-10-03","genre":["Animación"]}]}</script>"""
        val detail = SoloLatinoDetails.detail(Jsoup.parse(html, "https://sololatino.net/serie/naruto"))!!
        assertEquals(2002, detail.year)
        assertEquals(listOf("Animación"), detail.genres)
    }
}
