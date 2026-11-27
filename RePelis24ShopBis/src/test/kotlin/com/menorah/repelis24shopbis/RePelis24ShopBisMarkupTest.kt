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
              <div class="movie-item episode-item">
                <a href="https://repelis24.shop/serie/cats-eyes/">
                  <figure><img src="//repelis24.shop/cdn/p/v2/w1/ce.webp" class="poster" alt="Poster Cat's Eyes"></figure>
                  <div class="item-detail"><p>Cat's Eyes 2x7</p></div>
                </a>
              </div>
              <ul class="pagination">
                <li><a class="disabled">1</a></li>
                <li><a class="page-link" href="https://repelis24.shop/peliculas/estrenos/page/2">2</a></li>
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
    fun parsesRealLeaveNoTraceAndTulsaKingMarkupWithLanguagesAndEpisodeNames() {
        val movieHtml = """
            <html><head><meta property="og:title" content="Leave No Trace - Ver Completa en Alta Calidad | RePelis24"></head><body>
              <article class="backdrop-info" itemscope itemtype="http://schema.org/Movie">
                <figure class="poster"><img src="https://repelis24.shop/cdn/p/v2/w1/td4f7TuqscCHCLmKoU6O.webp" itemprop="image"></figure>
                <header class="info-header"><h1 class="title"><span itemprop="name"> Ver Película Leave No Trace </span></h1></header>
                <footer class="info-footer">
                  <p class="meta"><span itemprop="duration"> 1h 48min </span><span> · </span><span itemprop="datePublished"> 2018</span></p>
                  <div class="info"><ul class="info-list">
                    <li><strong>Géneros:</strong> <a href="https://repelis24.shop/genero/drama/"><span itemprop="genre">Drama</span></a></li>
                    <li><strong>Actores:</strong> <a href="https://repelis24.shop/buscar/Thomasin+McKenzie">Thomasin McKenzie, </a><a href="https://repelis24.shop/buscar/Ben+Foster">Ben Foster</a></li>
                  </ul></div>
                </footer>
                <div class="description" itemprop="description">
                  <h2>Leave No Trace es una pelicula gratis en HD de RePelis24 Online:</h2>
                  <p>Un padre y su hija de 13 años viven aislados en Portland.</p>
                </div>
              </article>
              <div class="backdrop-image" style="background-image: linear-gradient(rgba(0,0,0,0.95), rgba(0,0,0,0.4)), url('//repelis24.shop/cdn/b/v2/w2/eRPsddEqTcsdfnjc6Dto.webp');"></div>
              <ul class="tabs-video">
                <li class="tab-video-item">
                  <div>
                    <img alt="audio Latino" class="tab-item-image" src="https://repelis24.shop/lat.png">
                    <div class="tab-item-name"> Latino <div>Calidad · HD</div></div>
                  </div>
                  <ul>
                    <li role="presentation" data-server="aHR0cHM6Ly9udXBsb2FkLm15L3dhdGNoL0t2bzdNT0xtbjV4NVhGYzhnNmY3WnVTQzJWSU94a1pEeXZqcTc4WElRWTA="><span> Opción 1 · Latino</span><span>Reproducir</span></li>
                    <li role="presentation" data-server="aHR0cHM6Ly9udXBsb2FkLm15L2lmcmFtZS8/dXJsPWh0dHBzJTNBJTJGJTJGdm9lLnN4JTJGZSUyRmtrcmpkcXV3Y3Blaw=="><span> Opción 2 · Latino</span><span>Reproducir</span></li>
                  </ul>
                </li>
                <li class="tab-video-item">
                  <div>
                    <img alt="audio Español Castellano" class="tab-item-image" src="https://repelis24.shop/cas.png">
                    <div class="tab-item-name"> Español Castellano <div>Calidad · HD</div></div>
                  </div>
                  <ul>
                    <li role="presentation" data-server="aHR0cHM6Ly9udXBsb2FkLm15L3dhdGNoLzMwMXFveks3VndBNjhWbzlHTWE5UjMyUk9rSkYzanozaDU1Y3lxMElnWXlRZXc="><span> Opción 1 · Español Castellano</span><span>Reproducir</span></li>
                  </ul>
                </li>
                <li class="tab-video-item">
                  <div>
                    <img alt="audio Subtitulado" class="tab-item-image" src="https://repelis24.shop/sub.png">
                    <div class="tab-item-name"> Subtitulado <div>Calidad · HD</div></div>
                  </div>
                  <ul>
                    <li role="presentation" data-server="aHR0cHM6Ly9udXBsb2FkLm15L2lmcmFtZS8/dXJsPWh0dHBzJTNBJTJGJTJGdm9lLnN4JTJGZSUyRmVzcWV5eXhtMnV5Zw=="><span> Opción 2 · Subtitulado</span><span>Reproducir</span></li>
                  </ul>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val mDoc = Jsoup.parse(movieHtml, "https://repelis24.shop/pelicula/leave-no-trace-zmbp/")
        val detail = RePelis24ShopBisMarkup.parseDetail(mDoc, "https://repelis24.shop/pelicula/leave-no-trace-zmbp/")!!
        assertEquals("Leave No Trace", detail.title)
        assertFalse(detail.isSeries)
        assertEquals(108, detail.durationMinutes)
        assertEquals(2018, detail.year)
        assertEquals(listOf("Drama"), detail.genres)
        assertEquals(listOf("Thomasin McKenzie", "Ben Foster"), detail.actors)
        assertEquals("https://repelis24.shop/cdn/b/v2/w2/eRPsddEqTcsdfnjc6Dto.webp", detail.backgroundUrl)
        assertTrue(detail.plot!!.contains("Portland"))

        val servers = RePelis24ShopBisMarkup.parseServerOptions(mDoc)
        assertEquals(4, servers.size)
        assertEquals("Latino", servers[0].language)
        assertEquals("Calidad · HD", servers[0].qualityLabel)
        assertEquals("Latino", servers[1].language)
        assertEquals("Castellano", servers[2].language)
        assertEquals("Subtitulado", servers[3].language)
        assertEquals("https://voe.sx/e/kkrjdquwcpek", RePelis24ShopBisMarkup.extractQueryParam(servers[1].url, "url"))

        val serieHtml = """
            <html><body>
              <h1 class="title"><span itemprop="name"> Ver Serie Tulsa King </span></h1>
              <div class="description margin-bottom-third" itemprop="description">
                <h2>Ver Tulsa King Temporada 4 es una serie gratis online de PELISPOP:</h2>
                <p>Dwight Manfredi se instala en Tulsa.</p>
              </div>
              <a href="https://repelis24.shop/temporada/tulsa-king-zrly-4/">Temporada 4</a>
              <a href="https://repelis24.shop/temporada/tulsa-king-zrly-1/">Temporada 1</a>
            </body></html>
        """.trimIndent()
        val sDoc = Jsoup.parse(serieHtml, "https://repelis24.shop/serie/tulsa-king-zrly/")
        val sDetail = RePelis24ShopBisMarkup.parseDetail(sDoc, "https://repelis24.shop/serie/tulsa-king-zrly/")!!
        assertEquals("Tulsa King", sDetail.title)
        assertTrue(sDetail.isSeries)
        assertEquals(listOf(1, 4), sDetail.seasonLinks.map { it.seasonNumber })

        val tempHtml = """
            <html><body>
              <div class="item-season-episodes">
                <a href="https://repelis24.shop/episodio/tulsa-king-zrly-4x4/">
                  <img src="https://repelis24.shop/cdn/e/v2/w0/e4.webp">
                  <span itemprop="name">Punto de partida</span><span>4x4</span>
                </a>
                <a href="https://repelis24.shop/episodio/tulsa-king-zrly-4x5/">
                  <img src="https://repelis24.shop/cdn/e/v2/w0/e5.webp">
                  <span itemprop="name">Dinosaurios</span><span>4x5</span>
                </a>
              </div>
            </body></html>
        """.trimIndent()
        val eps = RePelis24ShopBisMarkup.parseEpisodeLinks(Jsoup.parse(tempHtml, "https://repelis24.shop/temporada/tulsa-king-zrly-4/"), fallbackSeason = 4)
        assertEquals(listOf(4, 5), eps.map { it.episode })
        assertEquals("Punto de partida", eps[0].title)
        assertEquals("Dinosaurios", eps[1].title)
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

        val dynamicTokenHtml = """
            <script>
              var arr1 = ["dW1XeDc1NDM2MjFETVdBb2E=","RGJLRTc1NDM2MzNHaFFoSkY=","YnFTVTc1NDM2MzNnUkNPckc=","aWJ3TTc1NDM2MjlXQVR4a0o=","eWJ5VTc1NDM2MzJrY3hjcmI=","eGhVWDc1NDM1NzVFRVhKY1M=","RFBqaDc1NDM1NjRQQ25Da0U=","VVB4Vjc1NDM1NjRXdVJNaXg=","a3JuZzc1NDM2MzJSVVdCWWc=","bWNmbzc1NDM2MzVTQUFVSXk=","QmdacDc1NDM1NjhYeUN3WGk=","SmV4dzc1NDM1NjNvbldhREo=","bWN6Yjc1NDM2MjJYWkRxQms=","Q2FlSzc1NDM2MTVBWnNYbk8=","Q0tBUzc1NDM2MzFST2VGRG8=","dkdhVjc1NDM2MTRISE5HTUI=","RnlGZjc1NDM1NjNWdkxibkw=","RVBFbjc1NDM2MjVLdUl6Rmk=","RGxrSDc1NDM2MTRGbnV6d3E=","SmRwdDc1NDM2MzNTV21PeGQ=","cWhNbTc1NDM1NjRzeUpMSEw="];
            </script>
            <script>
              var s = ""; arr1.forEach(function (value) { s += String.fromCharCode(parseInt(atob(value).replace(/\D/g,'')) - 7543517); });
              var tok99="ABC123XYZ";
              player.setup({ file: s + "?s=" + tok99 });
            </script>
        """.trimIndent()
        assertEquals(
            "https://sv3.ibra.lat/?s=ABC123XYZ",
            NuploadMyDecoder.parseStreamUrl(dynamicTokenHtml),
        )
    }

    @Test
    fun decodesVoeMkgMaJsonScriptIgnoringFakeBigBuckBunny() {
        val voeHtml = """
            <html><body>
              <script>var source='https://test-videos.co.uk/vids/bigbuckbunny/mp4/av1/1080/Big_Buck_Bunny_1080_10s_5MB.mp4';</script>
              <script type="application/json">["nJImnKWtC1A1ZROtG293o1S6pP94oT8lGRkwoK1aDGRiZGLuLJShL18cYz1fozSlCGHjqJ8cMGVjYQRkMGVjZQtlZQZ4nT4mYPO0MGLmZJHlZPHhYP81Y2N2ZzW6MGEwoaImLJ8cMzHjnG9joJIc"]</script>
            </body></html>
        """.trimIndent()
        // Build a valid MKGMa token dynamically using inverse steps for deterministic unit test verification
        val expectedJson = """{"source":"https://ugc-cdn.example.com/hls/master.m3u8","fallback":"https://ugc-cdn.example.com/v.mp4"}"""
        val b2 = java.util.Base64.getEncoder().encodeToString(expectedJson.toByteArray(Charsets.UTF_8))
        val rev = b2.reversed()
        val unshifted = ByteArray(rev.length) { i -> ((rev[i].code + 3) and 0xFF).toByte() }
        val b1 = java.util.Base64.getEncoder().encodeToString(unshifted)
        val withJunk = b1.take(4) + "@$" + b1.drop(4) + "^^"
        val rot = withJunk.map { c ->
            when (c) {
                in 'a'..'m', in 'A'..'M' -> (c.code + 13).toChar()
                in 'n'..'z', in 'N'..'Z' -> (c.code - 13).toChar()
                else -> c
            }
        }.joinToString("")
        val realHtml = """
            <html><body>
              <script>var source='https://test-videos.co.uk/vids/bigbuckbunny/mp4/av1/1080/Big_Buck_Bunny_1080_10s_5MB.mp4';</script>
              <script type="application/json">["$rot"]</script>
            </body></html>
        """.trimIndent()
        val parsed = ShopVoeDecoder.parseEmbedHtml(realHtml)
        assertNotNull(parsed)
        assertEquals("https://ugc-cdn.example.com/hls/master.m3u8", parsed!!.streamUrl)
        assertEquals("https://ugc-cdn.example.com/v.mp4", parsed.fallbackUrl)
        assertTrue(parsed.isHls)
        // Verify dummy invalid json doesn't crash
        ShopVoeDecoder.parseEmbedHtml(voeHtml)
    }
}
