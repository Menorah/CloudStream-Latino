package com.menorah.repelis24shop
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class Repelis24ShopMarkupTest {
    @Test fun cardsAndPagination() {
        val d = Jsoup.parse("""<div class="movie-item"><a href="/pelicula/test/"><img src="/p.webp"><div class="item-detail"><p>Test</p></div></a></div><ul class="pagination"><li><a href="/peliculas/page/2">2</a></li></ul>""", "https://repelis24.shop/peliculas/")
        assertEquals("Test", Repelis24ShopMarkup.cards(d).single().title)
        assertTrue(Repelis24ShopMarkup.hasNext(d, 1)); assertFalse(Repelis24ShopMarkup.hasNext(d, 2))
        assertFalse(Repelis24ShopMarkup.site("https://repelis24.shop.evil.test/pelicula/test/"))
    }
}
