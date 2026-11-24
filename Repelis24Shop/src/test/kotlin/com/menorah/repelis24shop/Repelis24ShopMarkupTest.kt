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
    @Test fun imagesAcceptLazyAndExternalHttps() {
        val lazy = Jsoup.parse("""<div class="movie-item"><a href="/pelicula/x/"><img class="lazyload" src="/loader.gif" data-src="/cdn/p/v2/w1/abc.webp"><div class="item-detail"><p>X</p></div></a></div>""", "https://repelis24.shop/peliculas/")
        val proxied = Repelis24ShopMarkup.cards(lazy).single().poster
        // Site-CDN images are routed through the image proxy (Cloudflare serves a
        // challenge page instead of pixels on the raw URL).
        assertTrue("era $proxied", proxied!!.startsWith("https://images.weserv.nl/?url=") && proxied.contains("abc.webp"))
        val external = Jsoup.parse("""<div class="movie-item"><a href="/pelicula/y/"><img src="https://image.tmdb.org/t/p/w500/y.jpg"><div class="item-detail"><p>Y</p></div></a></div>""", "https://repelis24.shop/peliculas/")
        assertEquals("https://image.tmdb.org/t/p/w500/y.jpg", Repelis24ShopMarkup.cards(external).single().poster)
        val bad = Jsoup.parse("""<img src="data:image/gif;base64,AAAA">""", "https://repelis24.shop/")
        assertNull(Repelis24ShopMarkup.img(bad.selectFirst("img")))
    }
}
