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
        assertEquals(listOf("Latino", "Castellano", "Audio original · Sub español", "Latino"), servers.map { it.language })
        assertEquals("https://player.example/e/2", servers.last().url)
        assertEquals("https://lat.example/e/1", servers.first().url)
    }

    @Test fun `same server may be present in more than one language`() {
        val html = """<div class="OD_LAT"><li onclick="go_to_player('https://host.example/same')"></li></div><div class="OD_SUB"><li onclick="go_to_player('https://host.example/same')"></li></div>"""
        val servers = SiteMarkup.playerServers(Jsoup.parse(html, "https://player.example/"))
        assertEquals(listOf("Latino / Audio original · Sub español"), servers.map { it.language })
        assertEquals("[Latino / Audio original · Sub español] Host 720p", sourceLabel(servers[0].language, "Host 720p"))
        assertEquals("Host", sourceLabel(null, "Host"))
        assertNull(languageLabel("desconocido"))
        assertEquals("Castellano", languageLabel("ES"))
    }
    @Test fun `pagination follows the next page of the requested section only`() {
        val base = "https://ww4.animeonline.ninja/genero/audio-latino/"
        val first = Jsoup.parse("""<div class="pagination"><a class="inactive" href="/genero/audio-latino/page/2/">2</a></div>""", base)
        assertTrue(SiteMarkup.hasNextPage(first, 1, "/genero/audio-latino/"))
        assertFalse(SiteMarkup.hasNextPage(first, 2, "/genero/audio-latino/"))
        assertFalse(SiteMarkup.hasNextPage(first, 1, "/genero/anime-castellano/"))
        val other = Jsoup.parse("""<div class="pagination"><a href="https://evil.test/genero/audio-latino/page/2/">2</a></div>""", base)
        assertFalse(SiteMarkup.hasNextPage(other, 1, "/genero/audio-latino/"))
        val responsive = Jsoup.parse("""<div class="resppages"><a href="/genero/audio-latino/page/3/"></a></div>""", base)
        assertTrue(SiteMarkup.hasNextPage(responsive, 2, "/genero/audio-latino/"))
        assertFalse(SiteMarkup.hasNextPage(Jsoup.parse(""), 1, "/genero/audio-latino/"))
    }
    @Test fun `archive pages ignore the repeated featured carousel`() {
        val html = """
            <div id="featured-titles"><article class="item"><div class="data"><h3><a href="/online/featured/">Featured</a></h3></div></article></div>
            <div id="archive-content"><article class="item"><div class="data"><h3><a href="/online/actual/">Actual</a></h3></div></article></div>
        """
        val cards = SiteMarkup.cards(Jsoup.parse(html, "https://ww4.animeonline.ninja/online/"))
        assertEquals(listOf("Actual"), cards.map { it.title })
    }
}
