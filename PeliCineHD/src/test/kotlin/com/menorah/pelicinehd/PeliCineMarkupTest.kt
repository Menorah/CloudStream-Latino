package com.menorah.pelicinehd

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PeliCineMarkupTest {
    private fun doc(html: String, url: String = "https://pelicinehd.com/peliculas/") = Jsoup.parse(html, url)

    @Test fun cardsParseCatalogArticles() {
        val html = """<article class="movie-item"><a href="https://pelicinehd.com/movies/matrix/"><img src="https://image.tmdb.org/x.jpg" alt="Poster"></a><h2 class="entry-title">Matrix</h2><span class="year">1999</span></article>
        <article class="post-item"><a href="https://evil.test/movies/ad/"><img src="x.jpg"></a><h2 class="entry-title">Ad</h2></article>
        <article class="movie-item"><a href="https://pelicinehd.com/series/dig/"><img src="https://image.tmdb.org/y.jpg"></a><h2 class="entry-title">Dig</h2></article>"""
        val cards = PeliCineMarkup.cards(doc(html))
        assertEquals(listOf("Matrix", "Dig"), cards.map { it.title })
        assertEquals(1999, cards[0].year)
        assertEquals("https://image.tmdb.org/x.jpg", cards[0].poster)
    }

    @Test fun seasonsAndSeasonEpisodes() {
        val series = doc("""<ul class="aa-cnt"><li><a data-post="4321" data-season="2">T2</a></li><li><a data-post="4321" data-season="1">T1</a></li></ul>""",
            "https://pelicinehd.com/series/dig/")
        assertEquals(listOf(2, 1), PeliCineMarkup.seasons(series).map { it.season })
        val episodes = PeliCineMarkup.seasonEpisodes("""
            <ul><li><a class="lnk-blk" href="https://pelicinehd.com/episodios/dig-temporada-1-capitulo-1/"><h2 class="entry-title">1x1 Piloto</h2></a></li>
            <li><a class="lnk-blk" href="https://evil.test/episodios/ad/"><h2 class="entry-title">ad</h2></a></li></ul>""",
            "https://pelicinehd.com/series/dig/")
        assertEquals(1, episodes.size)
        assertEquals("1x1 Piloto", episodes[0].title)
    }

    @Test fun serversParseNamesAndLanguage() {
        val page = doc("""<body class="postid-4321 term-9">
            <span class="server">UqLoad - LATÍNO HD</span>
            <span class="server">Fastream - CASTELLANO</span>
            <span class="server">playerwish - SUBTITULADO</span></body>""",
            "https://pelicinehd.com/episodios/dig-temporada-1-capitulo-1/")
        assertEquals("4321", PeliCineMarkup.postId(page))
        val servers = PeliCineMarkup.servers(page)
        assertEquals(listOf("UqLoad", "Fastream", "playerwish"), servers.map { it.name })
        assertEquals(listOf("Latino", "Castellano", "Subtitulado"), servers.map { PeliCineMarkup.language(it.language) })
        assertEquals("https://pelicinehd.com/?trembed=1&trid=4321&trtype=2", PeliCineMarkup.trembedUrl(1, "4321", tv = true))
    }

    @Test fun trembedTargetPrefersIframe() {
        val html = """<html><iframe src="https://voe.sx/e/abc" width="100%"></iframe></html>"""
        assertEquals("https://voe.sx/e/abc", PeliCineMarkup.trembedTarget(html))
        val refresh = """<meta http-equiv="refresh" content="0;url=https://filemoon.sx/e/xyz">"""
        assertEquals("https://filemoon.sx/e/xyz", PeliCineMarkup.trembedTarget(refresh))
        assertNull(PeliCineMarkup.trembedTarget("<p>sin jugador</p>"))
    }

    @Test fun pagination() {
        assertTrue(PeliCineMarkup.hasNext(doc("""<div class="nav-links"><a>SIGUIENTE</a></div>""")))
        assertFalse(PeliCineMarkup.hasNext(doc("""<div class="nav-links"><a>1</a></div>""")))
    }
}
