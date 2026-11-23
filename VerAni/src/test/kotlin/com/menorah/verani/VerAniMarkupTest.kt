package com.menorah.verani

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VerAniMarkupTest {
    private val base = "https://verani.me/animes/"
    @Test fun `catalog and search cards support both layouts and movie urls`() {
        val html = """
          <div class="content-card anime-card"><a class="card-link" href="/anime/"><img data-src="/wp-content/uploads/a.jpg"><h3 class="card-title">Anime</h3></a></div>
          <article class="content-card pelicula-card"><div><img src="https://image.tmdb.org/a.jpg"></div><div class="card-info"><a class="card-link" href="/anime/pelicula/movie/"><span class="card-title">Movie</span></a></div></article>
          <div class="content-card"><a class="card-link" href="https://evil.test/title/"><span class="card-title">Bad</span></a></div>
        """
        val cards = VerAniMarkup.cards(Jsoup.parse(html, base))
        assertEquals(listOf("Anime", "Movie"), cards.map { it.title })
        assertEquals("https://verani.me/wp-content/uploads/a.jpg", cards.first().poster)
        assertFalse(cards.first().movie); assertTrue(cards.last().movie)
    }
    @Test fun `episodes use season group and order without movie cards`() {
        val html = """
          <div class="temporada-group"><span class="temporada-name">Temporada 2</span>
          <a class="capitulo-card-link" href="/anime/capitulo-2-two/"><h3 class="capitulo-card-title">Capitulo 2</h3><p class="capitulo-card-subtitle">Dos</p></a>
          <a class="capitulo-card-link" href="/anime/capitulo-1-one/"><h3 class="capitulo-card-title">Capitulo 1</h3><p class="capitulo-card-subtitle">Uno</p></a></div>
          <div class="temporada-group"><span class="temporada-name">Temporada 1</span>
          <a class="capitulo-card-link" href="/anime/capitulo-3-three/"><h3 class="capitulo-card-title">Capitulo 3</h3></a>
          <a class="capitulo-card-link" href="/anime/pelicula/movie/"><h3 class="capitulo-card-title">Movie</h3></a></div>
        """
        val eps = VerAniMarkup.episodes(Jsoup.parse(html, base))
        assertEquals(listOf(1, 2, 2), eps.map { it.season })
        assertEquals(listOf(3, 1, 2), eps.map { it.number })
        assertEquals("Uno", eps[1].title)
    }
    @Test fun `servers preserve languages and combine duplicates`() {
        val html = """
         <button class="servidor-tab" data-url="https://player.zilla-networks.com/m3u8/id" data-idioma="Español Latino"></button>
         <button class="servidor-tab" data-url="https://player.zilla-networks.com/m3u8/id" data-idioma="Japonés" data-subtitulos="Español"></button>
         <button class="servidor-tab" data-url="javascript:alert(1)" data-idioma="Japonés"></button>
        """
        val servers = VerAniMarkup.servers(Jsoup.parse(html, base))
        assertEquals(1, servers.size)
        assertEquals("Español Latino / Japonés · Sub Español", servers.single().language)
    }
    @Test fun `pagination only accepts site links`() {
        assertTrue(VerAniMarkup.hasNext(Jsoup.parse("""<div class="pagination-wrapper"><a class="next" href="/animes/page/2/">Siguiente</a></div>""", base)))
        assertFalse(VerAniMarkup.hasNext(Jsoup.parse("""<div class="pagination-wrapper"><a class="next" href="https://evil.test/">Siguiente</a></div>""", base)))
    }
}
