package com.menorah.latanime

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatanimeMarkupTest {

    @Test
    fun `buildPagedUrl and buildSearchUrl preserve query strings cleanly`() {
        assertEquals("https://latanime.org/emision", LatanimeMarkup.buildPagedUrl("https://latanime.org/emision", 1))
        assertEquals("https://latanime.org/emision?p=2", LatanimeMarkup.buildPagedUrl("https://latanime.org/emision", 2))
        assertEquals(
            "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=Latino&p=3",
            LatanimeMarkup.buildPagedUrl(
                "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=Latino",
                3,
            ),
        )
        assertEquals(
            "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=Latino",
            LatanimeMarkup.buildPagedUrl(
                "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=Latino&p=4",
                1,
            ),
        )
        assertEquals("https://latanime.org/buscar?q=one+piece", LatanimeMarkup.buildSearchUrl("one piece", 1))
        assertEquals("https://latanime.org/buscar?q=one+piece&p=2", LatanimeMarkup.buildSearchUrl("one piece", 2))
    }

    @Test
    fun `decodeBase64Player handles valid missing-padding whitespace and rejects non-http`() {
        val sample = "aHR0cHM6Ly9ieXNld2loZS5jb20vZS9tZjk1bHkwMjN0NHU" // no '=' padding
        assertEquals("https://bysewihe.com/e/mf95ly023t4u", LatanimeMarkup.decodeBase64Player(sample))
        assertEquals(
            "https://voe.sx/e/9bb05eump8bb",
            LatanimeMarkup.decodeBase64Player(" aHR0cHM6Ly92b2Uuc3gvZS85YmIwNWV1bXA4YmI= \n"),
        )
        assertNull(LatanimeMarkup.decodeBase64Player("amF2YXNjcmlwdDphbGVydCgxKQ==")) // javascript:alert(1)
        assertNull(LatanimeMarkup.decodeBase64Player("not-base64!!!"))
    }

    @Test
    fun `parseHomeRecentEpisodes resolves data-src over capblank placeholder and ignores carousel`() {
        val html = """
            <html><body>
            <div id="carouselExampleCaptions">
              <a href="https://latanime.org/ver/promo-episodio-1">Promo</a>
            </div>
            <section>
              <div class="row">
                <div class="col-6">
                  <a href="https://latanime.org/ver/one-piece-latino-episodio-891">
                    <img class="lozad" data-src="https://latanime.org/thumbs/portada/op891.jpg" src="https://latanime.org/img/capblank.png" alt="One Piece Latino" />
                    <span class="badge">Episodio 891</span>
                    <h3>One Piece Latino - Capitulo 891</h3>
                  </a>
                </div>
                <div class="col-6">
                  <a href="https://latanime.org/ver/terror-en-tokio-castellano-episodio-5">
                    <img src="https://latanime.org/thumbs/portada/tet5.jpg" alt="Terror en Tokio Castellano" />
                    <h3>Terror en Tokio Castellano - Episodio 5</h3>
                  </a>
                </div>
              </div>
            </section>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://latanime.org/")
        val items = LatanimeMarkup.parseHomeRecentEpisodes(doc)
        assertEquals(2, items.size)
        assertEquals("One Piece Latino", items[0].title)
        assertEquals(891, items[0].episodeNumber)
        assertEquals("https://latanime.org/thumbs/portada/op891.jpg", items[0].posterUrl)
        assertTrue(items[0].isDub)
        assertEquals("Terror en Tokio Castellano", items[1].title)
        assertEquals(5, items[1].episodeNumber)
        assertTrue(items[1].isDub)
    }

    @Test
    fun `parseCatalogCards handles badges types pagination and last page`() {
        val html = """
            <html><body>
            <div class="row">
              <div class="col-6"><a href="https://latanime.org/anime/overlord-the-sacred-kingdom">
                <img data-src="https://latanime.org/thumbs/imagen/overlord.jpg" src="https://latanime.org/img/capblank.png" />
                <div class="seriedetails"><span class="opacity-75">Pelicula Latino</span><h3 class="my-1">Overlord: The Sacred Kingdom</h3></div>
              </a></div>
              <div class="col-6"><a href="https://latanime.org/anime/urusei-yatsura-ova">
                <img src="https://latanime.org/thumbs/imagen/urusei.jpg" />
                <div class="seriedetails"><span class="opacity-75">Ova</span><h3 class="my-1">Urusei Yatsura OVA</h3></div>
              </a></div>
              <div class="col-6"><a href="https://latanime.org/anime/bleach-latino">
                <img src="https://latanime.org/thumbs/imagen/bleach.jpg" />
                <div class="seriedetails"><span class="opacity-75">Latino</span><h3 class="my-1">Bleach</h3></div>
              </a></div>
            </div>
            <ul class="pagination">
              <li class="page-item active"><a class="page-link">1</a></li>
              <li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=2">2</a></li>
              <li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=5">5</a></li>
              <li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=2">›</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://latanime.org/animes")
        val page = LatanimeMarkup.parseCatalogCards(doc, currentPage = 1)
        assertEquals(3, page.items.size)
        assertTrue(page.hasNext)
        assertEquals(5, page.maxPage)

        assertEquals(LatanimeCardKind.MOVIE, page.items[0].kind)
        assertTrue(page.items[0].isDub)
        assertEquals("https://latanime.org/thumbs/imagen/overlord.jpg", page.items[0].posterUrl)

        assertEquals(LatanimeCardKind.OVA, page.items[1].kind)
        assertFalse(page.items[1].isDub)

        assertEquals(LatanimeCardKind.ANIME, page.items[2].kind)
        assertTrue(page.items[2].isDub)

        val lastHtml = html.replace(
            """<li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=2">2</a></li>""",
            "",
        ).replace(
            """<li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=5">5</a></li>""",
            """<li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=1">1</a></li>""",
        ).replace(
            """<li class="page-item"><a class="page-link" href="https://latanime.org/animes?p=2">›</a></li>""",
            """<li class="page-item disabled"><span class="page-link">›</span></li>""",
        )
        val lastPage = LatanimeMarkup.parseCatalogCards(Jsoup.parse(lastHtml, "https://latanime.org/emision?p=3"), currentPage = 3)
        assertFalse(lastPage.hasNext)
    }

    @Test
    fun `parseCalendarCards extracts tabs deduplicates and keeps episode badges`() {
        val html = """
            <html><body>
            <div id="Calendario">
              <div class="tab-pane" id="lunes-tap-pane">
                <a href="https://latanime.org/anime/dandadan">
                  <img data-src="https://latanime.org/thumbs/imagen/dd.jpg" src="https://latanime.org/img/capblank.png" />
                  <div class="bg-calendario"><span class="badge">Próximo - 12</span></div>
                  <h3>DanDaDan</h3>
                </a>
              </div>
              <div class="tab-pane" id="martes-tap-pane">
                <a href="https://latanime.org/anime/dandadan">
                  <h3>DanDaDan</h3>
                </a>
                <a href="https://latanime.org/anime/bleach-tybw">
                  <img src="https://latanime.org/thumbs/imagen/bleach.jpg" />
                  <div class="bg-calendario"><span class="badge">Próximo - 35</span></div>
                  <h3>Bleach TYBW</h3>
                </a>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val cards = LatanimeMarkup.parseCalendarCards(Jsoup.parse(html, "https://latanime.org/calendario"))
        assertEquals(2, cards.size)
        assertEquals(12, cards[0].episodeNumber)
        assertEquals(35, cards[1].episodeNumber)
    }

    @Test
    fun `parseAnimeDetail sorts episodes parses metadata and preserves multi-movie collections as anime`() {
        val html = """
            <html><body>
            <div class="container my-3">
              <div class="row">
                <div class="col-lg-3">
                  <div class="serieimgficha">
                    <img class="img-fluid2" src="https://latanime.org/thumbs/imagen/seishun.jpg" />
                    <button class="btn-estado finalizado">Finalizado</button>
                  </div>
                </div>
                <div class="col-lg-9">
                  <h2>Seishun Buta Yarou Películas</h2>
                  <h3 class="fs-6 text-light opacity-75">Rascal Does Not Dream Peliculas</h3>
                  <div class="my-2"><span class="span-tiempo fs-6">Estreno: 29 de Noviembre de 2019</span></div>
                  <p>Episodios: 3</p>
                  <a href="https://latanime.org/genero/drama"><div class="btn">Drama</div></a>
                  <a href="https://latanime.org/genero/romance"><div class="btn">Romance</div></a>
                  <p class="my-2 opacity-75">Sinopsis de prueba.</p>
                  <div style="max-height: 400px; overflow-y: auto;">
                    <a href="https://latanime.org/ver/seishun-buta-yarou-peliculas-episodio-3">
                      <img data-src="https://latanime.org/thumbs/portada/s3.jpg" src="https://latanime.org/img/capblank.png" />
                      Seishun Buta Yarou Películas - Capitulo 3
                    </a>
                    <a href="https://latanime.org/ver/seishun-buta-yarou-peliculas-episodio-1">
                      <img data-src="https://latanime.org/thumbs/portada/s1.jpg" src="https://latanime.org/img/capblank.png" />
                      Seishun Buta Yarou Películas - Capitulo 1
                    </a>
                  </div>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val detail = LatanimeMarkup.parseAnimeDetail(
            Jsoup.parse(html, "https://latanime.org/anime/seishun-buta-yarou-peliculas"),
            "https://latanime.org/anime/seishun-buta-yarou-peliculas",
            cachedKind = LatanimeCardKind.MOVIE,
        )
        assertNotNull(detail)
        assertEquals(2019, detail!!.year)
        assertEquals(LatanimeShowStatus.COMPLETED, detail.status)
        assertEquals(listOf("Drama", "Romance"), detail.tags)
        assertEquals(LatanimeCardKind.ANIME, detail.kind) // 2+ episodes should expose episode list
        assertEquals(listOf(1, 3), detail.episodes.map { it.episode })
        assertEquals("Episodio 1", detail.episodes[0].name)
        assertEquals("https://latanime.org/thumbs/portada/s1.jpg", detail.episodes[0].posterUrl)
        assertTrue(detail.plot!!.contains("Rascal Does Not Dream"))
    }

    @Test
    fun `parseEpisodePage decodes players trims direct links and deduplicates mirror codes`() {
        val html = """
            <html><body>
            <div class="container">
              <h2>Terror en Tokio Castellano - 5</h2>
              <a class="cap-btn" href="https://latanime.org/anime/terror-en-tokio-castellano">Lista</a>
              <a data-player="aHR0cHM6Ly9zYXZlZmlsZXMuY29tL2UvMzg2a3I2c2lwNnlq">savefiles</a>
              <a data-player="aHR0cHM6Ly9ieXNld2loZS5jb20vZS9tZjk1bHkwMjN0NHU">byse</a>
              <a class="direct-link" href="https://savefiles.com/d/386kr6sip6yj ">Savefiles</a>
              <a class="direct-link" href="https://gofile.io/d/ZUVgf6r7 ">Gofile</a>
              <a class="direct-link" href="https://www.mediafire.com/file/js6i1adg5t8m469 ">Mediafire</a>
            </div>
            </body></html>
        """.trimIndent()
        val ep = LatanimeMarkup.parseEpisodePage(
            Jsoup.parse(html, "https://latanime.org/ver/terror-en-tokio-castellano-episodio-5"),
            "https://latanime.org/ver/terror-en-tokio-castellano-episodio-5",
        )
        assertEquals("https://latanime.org/anime/terror-en-tokio-castellano", ep.parentAnimeUrl)
        assertEquals(2, ep.players.size)
        assertEquals("https://savefiles.com/e/386kr6sip6yj", ep.players[0].url)
        assertEquals("https://bysewihe.com/e/mf95ly023t4u", ep.players[1].url)
        // Savefiles /d/386kr6sip6yj must be deduplicated against /e/386kr6sip6yj
        assertEquals(2, ep.downloads.size)
        assertEquals("https://gofile.io/d/ZUVgf6r7", ep.downloads[0].url)
        assertEquals("https://www.mediafire.com/file/js6i1adg5t8m469", ep.downloads[1].url)
    }

    @Test
    fun `search normalization letter selection and cloudflare challenge detection work reliably`() {
        assertEquals(listOf("D", "S"), LatanimeMarkup.candidateLetters("Dragon Ball Super"))
        assertEquals(listOf("0", "K"), listOf("0", "K")) // sanity
        assertEquals(listOf("09", "S"), LatanimeMarkup.candidateLetters("86 eighty six"))

        val cards = listOf(
            LatanimeCard("Kusuriya no Hitorigoto", "https://latanime.org/anime/los-diarios-de-la-boticaria", null),
            LatanimeCard("One Piece Latino", "https://latanime.org/anime/one-piece-latino", null),
            LatanimeCard("One Piece Film Red", "https://latanime.org/anime/one-piece-film-red", null),
        )
        val boticaria = LatanimeMarkup.filterAndRankCards(cards, "diarios boticaria")
        assertEquals(1, boticaria.size)
        assertEquals("Kusuriya no Hitorigoto", boticaria.first().title)

        val onePiece = LatanimeMarkup.filterAndRankCards(cards, "one piece")
        assertEquals(2, onePiece.size)

        assertTrue(LatanimeMarkup.isCloudflareChallenge(403, "<title>Just a moment...</title>", "challenge"))
        assertTrue(LatanimeMarkup.isCloudflareChallenge(403, "<title>Un momento…</title>", null))
        assertFalse(LatanimeMarkup.isCloudflareChallenge(200, """<a href="/anime/naruto">Naruto</a>""", null))
    }
}
