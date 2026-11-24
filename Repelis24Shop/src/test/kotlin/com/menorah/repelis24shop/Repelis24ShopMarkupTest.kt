package com.menorah.repelis24shop
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class Repelis24ShopMarkupTest {
    @Test fun voeWrapperOnlyAcceptsOneSafeEmbedTarget() {
        assertEquals("https://voe.sx/e/abc", Repelis24ShopMarkup.voeFromWrapper("https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2Fabc"))
        assertNull(Repelis24ShopMarkup.voeFromWrapper("https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx.evil.test%2Fe%2Fabc"))
        assertNull(Repelis24ShopMarkup.voeFromWrapper("https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2Fabc&url=https%3A%2F%2Fvoe.sx%2Fe%2Fdef"))
        assertNull(Repelis24ShopMarkup.voeFromWrapper("https://nupload.my/iframe/?url=http%3A%2F%2Fvoe.sx%2Fe%2Fabc"))
        assertNull(Repelis24ShopMarkup.voeFromWrapper("https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fdownload"))
        assertNull(Repelis24ShopMarkup.voeFromWrapper("https://nupload.my.evil.test/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2Fabc"))
        assertNull(Repelis24ShopMarkup.voeFromWrapper("https://nupload.my/watch/abc"))
    }
    @Test fun cardsAndPagination() {
        val d = Jsoup.parse("""<div class="movie-item"><a href="/pelicula/test/"><img src="/p.webp"><div class="item-detail"><p>Test</p></div></a></div><ul class="pagination"><li><a href="/peliculas/page/2">2</a></li></ul>""", "https://repelis24.shop/peliculas/")
        assertEquals("Test", Repelis24ShopMarkup.cards(d).single().title)
        assertTrue(Repelis24ShopMarkup.hasNext(d, 1)); assertFalse(Repelis24ShopMarkup.hasNext(d, 2))
        assertFalse(Repelis24ShopMarkup.site("https://repelis24.shop.evil.test/pelicula/test/"))
    }
}
