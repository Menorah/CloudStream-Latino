package com.menorah.animeonline

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SiteMarkupTest {
    @Test fun `landing page link is followed only on ver2 root`() {
        val html = """<a href="https://ww4.animeonline.ninja/inicio">¿Entrar en AnimeOnline? Clic aquí</a>"""
        val doc = Jsoup.parse(html, "https://ver2.animeonline.ninja/")
        assertEquals("https://ww4.animeonline.ninja/inicio", SiteMarkup.landingEntry(doc, "https://ver2.animeonline.ninja/"))
        assertNull(SiteMarkup.landingEntry(doc, "https://ww4.animeonline.ninja/online/"))
        val hostile = Jsoup.parse("""<a href="https://evil.example/inicio">Entrar</a>""", "https://ver2.animeonline.ninja/")
        assertNull(SiteMarkup.landingEntry(hostile, "https://ver2.animeonline.ninja/"))
    }

    @Test fun `catalog and search cards resolve lazy and relative links`() {
        val html = """
          <article class="item tvshows"><div class="poster"><img src="data:image/svg+xml,blank" data-src="/cover.jpg" alt="Anime"><a href="/online/anime/"></a></div><div class="data"><h3><a href="/online/anime/">Anime</a></h3></div></article>
          <div class="result-item"><div class="image"><a href="/pelicula/movie/"><img data-src="/movie.jpg" alt="Movie"></a></div><div class="details"><div class="title"><a href="/pelicula/movie/">Movie</a></div></div></div>
          <article class="item"><div class="data"><h3><a href="/episodio/1/">Episode</a></h3></div></article>
        """.trimIndent()
        val cards = SiteMarkup.cards(Jsoup.parse(html, "https://ww4.animeonline.ninja/search/"))
        assertEquals(2, cards.size)
        assertEquals("https://ww4.animeonline.ninja/online/anime/", cards[0].url)
        assertEquals("https://ww4.animeonline.ninja/cover.jpg", cards[0].poster)
        assertFalse(cards[0].movie)
        assertTrue(cards[1].movie)
        assertEquals("Movie", cards[1].title)
    }

    @Test fun `episodes retain season number and resolve relative images`() {
        val html = """<div id="seasons"><ul class="episodios"><li><span class="numerando">2 - 5</span><img data-src="/ep.jpg"><div class="episodiotitle"><a href="/episodio/test/">Título</a></div></li></ul></div>"""
        val episodes = SiteMarkup.episodes(Jsoup.parse(html, "https://ww4.animeonline.ninja/online/test/"))
        assertEquals(1, episodes.size)
        assertEquals("https://ww4.animeonline.ninja/episodio/test/", episodes[0].url)
        assertEquals("https://ww4.animeonline.ninja/ep.jpg", episodes[0].poster)
        assertEquals(2, episodes[0].season)
        assertEquals(5, episodes[0].number)
    }

    @Test fun `multiserver resolves grouped links and interleaves languages`() {
        val html = """
          <div class="OD OD_SUB"><li onclick="go_to_player('https://sub.example/e/1')"><span>SUB</span></li></div>
          <div class="OD OD_LAT"><li onclick="go_to_player('https://lat.example/e/1')"></li><li onclick="go_to_player('/e/2')"></li></div>
          <div class="OD OD_ES"><li onclick="go_to_player('https://es.example/e/1')"></li><li onclick="go_to_player('javascript:alert(1)')"></li></div>
        """.trimIndent()
        val servers = SiteMarkup.playerServers(Jsoup.parse(html, "https://player.example/embed/"))
        assertEquals(listOf("Latino", "Castellano", "Subtitulado", "Latino"), servers.map { it.language })
        assertEquals("https://player.example/e/2", servers.last().url)
        assertEquals("https://lat.example/e/1", servers.first().url)
    }
}
