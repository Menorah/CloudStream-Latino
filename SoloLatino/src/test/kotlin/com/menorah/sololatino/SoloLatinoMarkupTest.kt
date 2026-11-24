package com.menorah.sololatino

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SoloLatinoMarkupTest {
    private fun doc(html: String, url: String = "https://sololatino.net/peliculas") = Jsoup.parse(html, url)

    @Test fun parsesRealisticFixtureExcerpts() {
        fun fixture(name: String): String = javaClass.getResource("/fixtures/$name")!!.readText()
        val catalog = doc(fixture("catalog.html"), "https://sololatino.net/peliculas?page=2")
        val cards = SoloLatinoMarkup.cards(catalog)
        assertEquals(4, cards.size)
        assertEquals("La desaparición de Josef Mengele", cards[0].title)
        assertEquals(2025, cards[0].year)
        assertTrue(SoloLatinoMarkup.hasNext(catalog, 2))
        val naruto = doc(fixture("naruto.html"), "https://sololatino.net/serie/naruto")
        val episodes = SoloLatinoMarkup.episodes(naruto)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 53, 2 to 54), episodes.map { it.season to it.number })
    }

    @Test fun cardsUseOnlyCanonicalTitlePages() {
        val html = """<div class="card"><a href="/pelicula/ejemplo"><img class="card__poster" alt="Título" src="https://image.tmdb.org/t/p/w500/test.jpg"><span class="card__year">2024</span><p class="card__title">Película</p></a></div>
        <div class="card"><a href="/serie/ejemplo"><img class="card__poster" alt="Anime" src="https://image.tmdb.org/x.jpg"><span class="badge-anime">Anime</span></a></div>
        <div class="card"><a href="https://sololatino.net.evil.test/pelicula/ad"><img class="card__poster" alt="Ad"></a></div>
        <div class="card"><a href="/serie/ejemplo/temporada-1/episodio-1"><img class="card__poster" alt="Episodio"></a></div>"""
        val cards = SoloLatinoMarkup.cards(doc(html))
        assertEquals(listOf("Película", "Anime"), cards.map { it.title })
        assertEquals(listOf("movie", "anime"), cards.map { it.type })
        assertEquals(2024, cards.first().year)
    }

    @Test fun paginationChecksPageAndFilters() {
        val ok = doc("""<link rel="next" href="/peliculas?genero=terror&amp;page=3">""", "https://sololatino.net/peliculas?genero=terror&page=2")
        assertTrue(SoloLatinoMarkup.hasNext(ok, 2))
        assertFalse(SoloLatinoMarkup.hasNext(ok, 1))
        val wrong = doc("""<link rel="next" href="https://evil.test/peliculas?genero=terror&amp;page=3">""", "https://sololatino.net/peliculas?genero=terror&page=2")
        assertFalse(SoloLatinoMarkup.hasNext(wrong, 2))
    }

    @Test fun episodesPreserveTheSitesNumbersAndRejectOtherSeries() {
        val html = """<div data-season-panel="1"><a class="ep-item" href="/serie/naruto/temporada-1/episodio-1"><img class="ep-thumb" src="https://image.tmdb.org/1.jpg"><p class="text-sm font-semibold text-white">Inicio</p></a></div>
        <div data-season-panel="2"><a class="ep-item" href="/serie/naruto/temporada-2/episodio-53"><p class="text-sm font-semibold">Continuación</p></a><a class="ep-item" href="/serie/otra/temporada-2/episodio-54">Ajeno</a><a class="ep-item" href="/serie/naruto/temporada-3/episodio-55">Temporada incorrecta</a></div>"""
        val eps = SoloLatinoMarkup.episodes(doc(html, "https://sololatino.net/serie/naruto"))
        assertEquals(listOf(1 to 1, 2 to 53), eps.map { it.season to it.number })
        assertEquals("Continuación", eps[1].title)
    }

    @Test fun languageIsNotGuessedForUnknownGroups() {
        val html = """<div data-lang-group="mx"><button class="server-btn" data-player-token="abc123==">Principal</button></div>
        <div data-lang-group="other"><button class="server-btn" data-player-token="token">Otro</button></div>
        <div data-lang-group="es"><button class="server-btn" data-player-token="bad&amp;x">Inválido</button></div>"""
        assertEquals(listOf("Latino", "Idioma no indicado"), SoloLatinoMarkup.servers(doc(html)).map { it.language })
        assertFalse(SoloLatinoMarkup.siteUrl("https://sololatino.net.evil.test/"))
        assertFalse(SoloLatinoMarkup.siteUrl("https://other@sololatino.net/"))
    }
}
