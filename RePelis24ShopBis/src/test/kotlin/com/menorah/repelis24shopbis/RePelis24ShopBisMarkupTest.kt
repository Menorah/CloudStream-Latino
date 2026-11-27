package com.menorah.repelis24shopbis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RePelis24ShopBisMarkupTest {

    @Test
    fun parsesHomeMovieItemCardsAndPagination() {
        val html = """
            <html><body>
              <div class="movie-item">
                <a href="https://repelis24.shop/pelicula/the-accountant-2-el-contador-2/">
                  <div class="item-picture">
                    <figure>
                      <img src="//repelis24.shop/cdn/p/v2/w1/T2LWwKFfx72OjbjmWQ8B.webp" class="poster" alt="Poster The Accountant 2 (El contador 2)">
                      <span class="year text-center">2025</span>
                    </figure>
                    <div class="card-hover">
                      <div class="card-hover-info-overview"><p>Wolff resuelve el caso.</p></div>
                    </div>
                  </div>
                  <div class="item-detail"><p>The Accountant 2 (El contador 2)</p></div>
                </a>
              </div>
              <div class="movie-item">
                <a href="https://repelis24.shop/serie/cats-eyes/">
                  <figure><img src="//repelis24.shop/cdn/p/v2/w1/ce.webp" class="poster" alt="Poster Cat's Eyes"><span class="year">2024</span></figure>
                  <div class="item-detail"><p>Cat's Eyes</p></div>
                </a>
              </div>
              <ul class="pagination">
                <li><a class="disabled">1</a></li>
                <li><a class="page-link" href="https://repelis24.shop/peliculas/page/2">2</a></li>
              </ul>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, RePelis24ShopBisMarkup.DEFAULT_MAIN_URL)
        val cards = RePelis24ShopBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("The Accountant 2 (El contador 2)", cards[0].title)
        assertEquals("https://repelis24.shop/cdn/p/v2/w1/T2LWwKFfx72OjbjmWQ8B.webp", cards[0].posterUrl)
        assertEquals(2025, cards[0].year)
        assertFalse(cards[0].isSeries)
        assertEquals("Wolff resuelve el caso.", cards[0].plotPreview)
        assertTrue(cards[1].isSeries)
        assertTrue(RePelis24ShopBisMarkup.parseHasNext(doc, 1))
        assertEquals("https://repelis24.shop/busqueda/spider%20man/page/2", RePelis24ShopBisMarkup.buildSearchUrl("spider man", 2))
    }

    @Test
    fun parsesMovieServersSeriesSeasonsAndSeasonEpisodes() {
        val movieHtml = """
            <html><head><meta property="og:title" content="The Accountant 2 (El contador 2) - Ver Completa en Alta Calidad | RePelis24"></head><body>
              <img src="https://repelis24.shop/cdn/p/v2/w1/T2LWwKFfx72OjbjmWQ8B.webp" class="poster">
              <h2>The Accountant 2 (El contador 2) es una pelicula gratis en HD de RePelis24 Online:</h2>
              <p>Cuando un viejo conocido es asesinado, Wolff se ve obligado a resolver el caso.</p>
              <ul>
                <li role="presentation" data-server="aHR0cHM6Ly9udXBsb2FkLm15L3dhdGNoL0t2bzdNT0xtbjV4NVhGYzhnNmY3WnVTQzJWSU94a1pEeXZqcTc4WElRWTA="><span> Opción 1 · Latino</span><span>Reproducir</span></li>
                <li role="presentation" data-server="aHR0cHM6Ly9udXBsb2FkLm15L2lmcmFtZS8/dXJsPWh0dHBzJTNBJTJGJTJGdm9lLnN4JTJGZSUyRmtrcmpkcXV3Y3Blaw=="><span> Opción 2 · Latino</span><span>Reproducir</span></li>
              </ul>
            </body></html>
        """.trimIndent()
        val mDoc = Jsoup.parse(movieHtml, "https://repelis24.shop/pelicula/the-accountant-2-el-contador-2/")
        val detail = RePelis24ShopBisMarkup.parseDetail(mDoc, "https://repelis24.shop/pelicula/the-accountant-2-el-contador-2/")
        assertNotNull(detail)
        detail!!
        assertEquals("The Accountant 2 (El contador 2)", detail.title)
        assertFalse(detail.isSeries)
        assertTrue(detail.plot!!.contains("Wolff"))

        val servers = RePelis24ShopBisMarkup.parseServerOptions(mDoc)
        assertEquals(2, servers.size)
        assertEquals("https://nupload.my/watch/Kvo7MOLmn5x5XFc8g6f7ZuSC2VIOxkZDyvjq78XIQY0", servers[0].url)
        assertEquals("https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2Fkkrjdquwcpek", servers[1].url)
        assertEquals("https://voe.sx/e/kkrjdquwcpek", RePelis24ShopBisMarkup.extractQueryParam(servers[1].url, "url"))

        val serieHtml = """
            <html><body>
              <h2>Cat's Eyes es una serie gratis online de RePelis24:</h2>
              <p>Las hermanas se reencuentran en París.</p>
              <a href="https://repelis24.shop/temporada/cats-eyes-2/">Temporada 2</a>
              <a href="https://repelis24.shop/temporada/cats-eyes-1/">Temporada 1</a>
            </body></html>
        """.trimIndent()
        val sDoc = Jsoup.parse(serieHtml, "https://repelis24.shop/serie/cats-eyes/")
        val sDetail = RePelis24ShopBisMarkup.parseDetail(sDoc, "https://repelis24.shop/serie/cats-eyes/")!!
        assertTrue(sDetail.isSeries)
        assertEquals(listOf(1, 2), sDetail.seasonLinks.map { it.seasonNumber })

        val tempHtml = """
            <html><body>
              <a href="https://repelis24.shop/episodio/cats-eyes-1x2/"><img src="https://repelis24.shop/cdn/e/v2/w0/e2.webp">Episodio 2 1x2</a>
              <a href="https://repelis24.shop/episodio/cats-eyes-1x1/"><img src="https://repelis24.shop/cdn/e/v2/w0/e1.webp">Episodio 1 1x1</a>
            </body></html>
        """.trimIndent()
        val eps = RePelis24ShopBisMarkup.parseEpisodeLinks(Jsoup.parse(tempHtml, "https://repelis24.shop/temporada/cats-eyes-1/"))
        assertEquals(listOf(1, 2), eps.map { it.episode })
        assertEquals("https://repelis24.shop/cdn/e/v2/w0/e1.webp", eps[0].posterUrl)
    }

    @Test
    fun decodesRealNuploadObfuscatedBaseUrlAndSessionToken() {
        val html = """
            <script>
              var vaAoJOgRKU = ["dW1XeDc1NDM2MjFETVdBb2E=","RGJLRTc1NDM2MzNHaFFoSkY=","YnFTVTc1NDM2MzNnUkNPckc=","aWJ3TTc1NDM2MjlXQVR4a0o=","eWJ5VTc1NDM2MzJrY3hjcmI=","eGhVWDc1NDM1NzVFRVhKY1M=","RFBqaDc1NDM1NjRQQ25Da0U=","VVB4Vjc1NDM1NjRXdVJNaXg=","a3JuZzc1NDM2MzJSVVdCWWc=","bWNmbzc1NDM2MzVTQUFVSXk=","QmdacDc1NDM1NjhYeUN3WGk=","SmV4dzc1NDM1NjNvbldhREo=","bWN6Yjc1NDM2MjJYWkRxQms=","Q2FlSzc1NDM2MTVBWnNYbk8=","Q0tBUzc1NDM2MzFST2VGRG8=","dkdhVjc1NDM2MTRISE5HTUI=","RnlGZjc1NDM1NjNWdkxibkw=","RVBFbjc1NDM2MjVLdUl6Rmk=","RGxrSDc1NDM2MTRGbnV6d3E=","SmRwdDc1NDM2MzNTV21PeGQ=","cWhNbTc1NDM1NjRzeUpMSEw="];
            </script>
            <script>
              var vgpKOING = ""; vaAoJOgRKU.forEach(function Oym(value) { vgpKOING += String.fromCharCode(parseInt(atob(value).replace(/\D/g,'')) - 7543517); } );
              var sesz="WjoEzUbOdYUgSQFKPrQGsHlzla3yjrLg";
            </script>
        """.trimIndent()

        assertEquals(
            "https://sv3.ibra.lat/?s=WjoEzUbOdYUgSQFKPrQGsHlzla3yjrLg",
            NuploadMyDecoder.parseStreamUrl(html),
        )
    }
}
