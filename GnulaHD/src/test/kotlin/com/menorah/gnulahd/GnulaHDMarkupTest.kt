package com.menorah.gnulahd

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class GnulaHDMarkupTest {

    private fun encodeGnrdXor(plainJson: String): String {
        val key = byteArrayOf('g'.code.toByte(), 'N'.code.toByte(), '7'.code.toByte(), 'd'.code.toByte())
        val bytes = plainJson.toByteArray(StandardCharsets.UTF_8)
        val xored = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i and 3].toInt()).toByte() }
        return Base64.getEncoder().encodeToString(xored)
    }

    @Test
    fun `decodeGnrdPayload decodes XOR gN7d UTF-8 JSON and parses langs and downloads`() {
        val sampleJson = """
            {
              "langs": [
                {
                  "label": "Latino",
                  "flag": "🇲🇽",
                  "servers": [
                    {"title": "Vidara", "src": "https://vidara.to/e/abc123#gnraw"},
                    {"title": "Savefiles", "src": "https://savefiles.com/e/xyz999"}
                  ]
                },
                {
                  "label": "SUB",
                  "flag": "🇺🇸",
                  "servers": [
                    {"title": "Voe", "src": "https://voe.sx/e/xlpslkgo8zlg"}
                  ]
                }
              ],
              "dl": [
                {"name": "Byse", "lang": "ESPAÑA", "qual": "HD", "url": "https://bysevepoin.com/e/dl01"}
              ]
            }
        """.trimIndent()

        val encoded = encodeGnrdXor(sampleJson)
        val apiJson = """{"p":"$encoded"}"""
        val extracted = GnulaHDMarkup.extractPlayerApiCiphertext(apiJson)
        assertNotNull(extracted)

        val decoded = GnulaHDMarkup.decodeGnrdPayload(" $extracted \n".trimEnd('='))
        assertNotNull(decoded)

        val payload = GnulaHDMarkup.parsePlayerPayload(decoded!!)!!
        assertEquals(2, payload.langs.size)
        assertEquals("Latino", payload.langs[0].label)
        assertEquals("https://vidara.to/e/abc123#gnraw", payload.langs[0].servers[0].src)
        assertEquals("Subtitulado", payload.langs[1].label)
        assertEquals(1, payload.downloads.size)
        assertEquals("Castellano", payload.downloads[0].language)
    }

    @Test
    fun `URL builders and player target round-trip preserve query parameters`() {
        assertEquals("https://ww3.gnulahd.nu/ver/peliculas/", GnulaHDMarkup.buildPagedUrl("/ver/peliculas/", 1))
        assertEquals("https://ww3.gnulahd.nu/ver/peliculas/?page=2", GnulaHDMarkup.buildPagedUrl("/ver/peliculas/", 2))
        assertEquals(
            "https://ww3.gnulahd.nu/ver/peliculas/?orderby=views&page=3",
            GnulaHDMarkup.buildPagedUrl("/ver/peliculas/?orderby=views", 3),
        )
        assertEquals(
            "https://ww3.gnulahd.nu/genres/accion/?page=4",
            GnulaHDMarkup.buildPagedUrl("/genres/accion/", 4),
        )
        assertEquals("https://ww3.gnulahd.nu/?s=love+story", GnulaHDMarkup.buildSearchUrl("love story", 1))
        assertEquals("https://ww3.gnulahd.nu/page/2/?s=love+story", GnulaHDMarkup.buildSearchUrl("love story", 2))
        assertEquals("https://ww3.gnulahd.nu/wp-json/gnrd/v1/search?q=matrix", GnulaHDMarkup.buildQuickSearchUrl("matrix"))

        val encoded = GnulaHDMarkup.encodePlayerTarget(
            "526798",
            "19b2dbca009b009d015ddc2b45d96cb7",
            "https://ww3.gnulahd.nu/the-crown-6x10/",
        )
        val parsed = GnulaHDMarkup.parsePlayerTarget(encoded)
        assertEquals("526798", parsed.playerId)
        assertEquals("19b2dbca009b009d015ddc2b45d96cb7", parsed.token)
        assertEquals("https://ww3.gnulahd.nu/the-crown-6x10/", parsed.pageUrl)
    }

    @Test
    fun `parseHomeTrending and parseCatalogPage parse cards, ratings, years, upcoming, and pagination`() {
        val homeHtml = """
            <html><body>
            <div class="gnrd-trend-pane" data-pane="sem">
              <a class="gnrd-card gnrd-ta" href="https://ww3.gnulahd.nu/ver/the-jlo-show/" title="The JLO Show">
                <span class="gnrd-card-title">The JLO Show</span>
                <div class="gnrd-card-art">
                  <img loading="lazy" src="/wp-content/uploads/2026/11/jlo-200x300.webp" alt="The JLO Show" />
                  <span class="gnrd-rating">★ 7.0</span>
                  <div class="gnrd-langs"><span class="gnrd-lang">SUB</span></div>
                  <div class="gnrd-card-hover"><div class="gnrd-card-hinfo"><div class="gnrd-card-metaline"><span>★ 7.0</span><span>2026</span></div></div></div>
                </div>
              </a>
            </div>
            <div class="gnrd-trend-pane" data-pane="hoy">
              <a class="gnrd-card gnrd-ta" href="https://ww3.gnulahd.nu/ver/fool-night/" title="Fool Night">
                <span class="gnrd-card-title">Fool Night</span>
                <div class="gnrd-card-art">
                  <img data-src="https://ww3.gnulahd.nu/wp-content/uploads/2026/11/fn-200x300.webp" />
                  <span class="gnrd-rating">★ 9.0</span>
                  <div class="gnrd-langs"><span class="gnrd-lang">LAT</span><span class="gnrd-lang">SUB</span><span class="gnrd-lang">CAST</span></div>
                  <div class="gnrd-card-hover"><div class="gnrd-card-hinfo"><div class="gnrd-card-metaline"><span>2026</span></div></div></div>
                </div>
              </a>
            </div>
            </body></html>
        """.trimIndent()

        val homeDoc = Jsoup.parse(homeHtml, "https://ww3.gnulahd.nu/")
        val week = GnulaHDMarkup.parseHomeTrendingWeek(homeDoc)
        val today = GnulaHDMarkup.parseHomeTrendingToday(homeDoc)
        assertEquals(1, week.size)
        assertEquals("The JLO Show", week[0].title)
        assertEquals("https://ww3.gnulahd.nu/wp-content/uploads/2026/11/jlo-200x300.webp", week[0].posterUrl)
        assertEquals(7.0, week[0].rating!!, 0.01)
        assertEquals(2026, week[0].year)
        assertEquals(listOf("SUB"), week[0].languages)
        assertEquals(1, today.size)
        assertEquals("Fool Night", today[0].title)
        assertEquals(listOf("LAT", "SUB", "CAST"), today[0].languages)

        val upcomingHtml = """
            <html><body><main>
              <a class="gnrd-card gnest-ecard" data-tipo="pelicula" href="https://ww3.gnulahd.nu/proximos-estrenos/cliff-booth/">
                <div class="gnrd-card-art">
                  <img src="https://ww3.gnulahd.nu/wp-content/uploads/estrenos-posters/205624.webp" />
                  <span class="gnest-ecard-dias">Película</span>
                </div>
                <span class="gnrd-card-title">Cliff Booth</span>
                <span class="gnest-ecard-date">25 Nov 2026</span>
              </a>
            </main></body></html>
        """.trimIndent()
        val upPage = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(upcomingHtml, "https://ww3.gnulahd.nu/proximos-estrenos/"))
        assertFalse(upPage.hasNext)
        assertEquals(1, upPage.items.size)
        assertEquals(GnulaMediaKind.MOVIE, upPage.items[0].kind)
        assertEquals(2026, upPage.items[0].year)

        val searchHtml = """
            <html><body><main>
              <a class="gnrd-card" href="https://ww3.gnulahd.nu/ver/love-again/"><span class="gnrd-card-title">Love Again</span></a>
              <div class="gnrd-more-wrap"><a class="gnrd-loadmore" href="https://ww3.gnulahd.nu/?s=love&paged=2&rd=1" rel="next">Cargar más</a></div>
            </main></body></html>
        """.trimIndent()
        val searchPage = GnulaHDMarkup.parseCatalogPage(Jsoup.parse(searchHtml, "https://ww3.gnulahd.nu/?s=love"))
        assertTrue(searchPage.hasNext)
        assertEquals("Love Again", searchPage.items[0].title)
    }

    @Test
    fun `parseDetail extracts movie metadata, inline player creds, actors, trailer, and genres`() {
        val html = """
            <html><head>
              <meta itemprop="image" content="https://ww3.gnulahd.nu/wp-content/uploads/2026/11/jlo-683x1024.webp" />
              <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"Movie","name":"The JLO Show","genre":["Música","Documental"],"aggregateRating":{"@type":"AggregateRating","ratingValue":"7.0"}}
              </script>
            </head><body>
              <div class="gnrd-fi-bg" style="background-image:url('https://image.tmdb.org/t/p/w1280/bg.jpg')"></div>
              <div class="gnrd-eyebrow">Pelicula · 2026</div>
              <h1 class="gnrd-fi-title">The JLO Show</h1>
              <div id="gnrd-syn">Sinopsis completa de prueba. <button class="gnrd-fi-more">Ver más ↓</button></div>
              <div class="gnrd-qfacts">
                <div class="qf"><span class="qf-l">Año</span><span class="qf-v">2026</span></div>
                <div class="qf"><span class="qf-l">Duración</span><span class="qf-v">2 hr. 0 min.</span></div>
              </div>
              <div class="gnrd-info-row"><span class="gnrd-info-k">Género</span><span class="gnrd-info-v"><a href="/genres/musica/">Música</a></span></div>
              <div class="gnrd-trailer" data-yt="vgFVNNaoh5U"></div>
              <a class="gnrd-castc" href="/cast/jennifer-lopez/">
                <div class="gnrd-castc-ph"><img src="https://image.tmdb.org/t/p/w185/jlo.jpg"/></div>
                <div class="gnrd-castc-name">Jennifer Lopez</div>
                <div class="gnrd-castc-char">Self</div>
              </a>
              <script>var _gnrdPid=527555,_gnrdTok="632b14e8bdb4c9faec3d4b0f575918ac";</script>
            </body></html>
        """.trimIndent()

        val detail = GnulaHDMarkup.parseDetail(Jsoup.parse(html, "https://ww3.gnulahd.nu/ver/the-jlo-show/"), "https://ww3.gnulahd.nu/ver/the-jlo-show/")!!
        assertEquals(GnulaMediaKind.MOVIE, detail.kind)
        assertFalse(detail.comingSoon)
        assertEquals("The JLO Show", detail.title)
        assertEquals("https://ww3.gnulahd.nu/wp-content/uploads/2026/11/jlo-683x1024.webp", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/bg.jpg", detail.backgroundPosterUrl)
        assertEquals("Sinopsis completa de prueba.", detail.plot)
        assertEquals(2026, detail.year)
        assertEquals(120, detail.durationMinutes)
        assertEquals(7.0, detail.rating!!, 0.01)
        assertTrue(detail.genres.contains("Música"))
        assertTrue(detail.genres.contains("Documental"))
        assertEquals("https://www.youtube.com/watch?v=vgFVNNaoh5U", detail.trailerUrl)
        assertEquals(1, detail.actors.size)
        assertEquals("Jennifer Lopez", detail.actors[0].name)
        assertEquals("Self", detail.actors[0].role)
        assertEquals("527555", detail.playerId)
        assertEquals("632b14e8bdb4c9faec3d4b0f575918ac", detail.playerToken)
    }

    @Test
    fun `parseDetail sorts newest-first episodes ascending, handles data-e=0 quirk, and detects Anime vs Upcoming`() {
        val seriesHtml = """
            <html><body>
              <div class="gnrd-eyebrow">Anime · 1999</div>
              <h1 class="gnrd-fi-title">One Piece</h1>
              <a class="gnrd-epc" data-e="0" data-id="9002" data-s="1" data-t="tok1176" href="https://ww3.gnulahd.nu/one-piece-1999-1x1176/">
                <div class="gnrd-epc-thumb" style="background-image:url('https://image.tmdb.org/ep1176.jpg')"><span class="gnrd-epc-dur">24 min</span></div>
                <div class="gnrd-epc-body"><div class="gnrd-epc-head"><span class="gnrd-epc-n">1x1176</span><span class="gnrd-epc-title">Episodio final</span><span class="gnrd-epc-date">14 Dic 2023</span></div><p class="gnrd-epc-ov">Resumen 1176.</p></div>
              </a>
              <a class="gnrd-epc" data-e="1" data-id="9001" data-s="1" data-t="tok1" href="https://ww3.gnulahd.nu/one-piece-1999-1x01/">
                <div class="gnrd-epc-body"><div class="gnrd-epc-head"><span class="gnrd-epc-n">1x01</span><span class="gnrd-epc-title">Romance Dawn</span><span class="gnrd-epc-date">20 Oct 1999</span></div></div>
              </a>
            </body></html>
        """.trimIndent()

        val detail = GnulaHDMarkup.parseDetail(Jsoup.parse(seriesHtml, "https://ww3.gnulahd.nu/ver/one-piece-1999/"), "https://ww3.gnulahd.nu/ver/one-piece-1999/")!!
        assertEquals(GnulaMediaKind.ANIME, detail.kind)
        assertFalse(detail.comingSoon)
        assertEquals(2, detail.episodes.size)
        assertEquals(1, detail.episodes[0].episode)
        assertEquals("1999-10-20", detail.episodes[0].isoDate)
        assertEquals(1176, detail.episodes[1].episode)
        assertEquals(24, detail.episodes[1].durationMinutes)
        assertEquals("2023-12-14", detail.episodes[1].isoDate)
        assertEquals("Resumen 1176.", detail.episodes[1].overview)
        assertEquals("https://image.tmdb.org/ep1176.jpg", detail.episodes[1].posterUrl)

        val upcomingHtml = """
            <html><body>
              <div class="gnrd-eyebrow">Película</div>
              <h1 class="gnrd-fi-title">The Adventures of Cliff Booth</h1>
            </body></html>
        """.trimIndent()
        val upcoming = GnulaHDMarkup.parseDetail(Jsoup.parse(upcomingHtml, "https://ww3.gnulahd.nu/proximos-estrenos/cliff/"), "https://ww3.gnulahd.nu/proximos-estrenos/cliff/")!!
        assertTrue(upcoming.comingSoon)
        assertNull(upcoming.playerId)

        val epHtml = """
            <html><body>
              <div class="gnrd-eyebrow">Serie · 2016</div>
              <h1>The Crown 6x10</h1>
              <a class="gnrd-btn gnrd-btn-play" href="https://ww3.gnulahd.nu/ver/the-crown/">▶ Ver toda la serie</a>
              <a class="gnrd-epc" data-e="10" data-id="526798" data-s="6" data-t="19b2dbca009b009d015ddc2b45d96cb7" href="https://ww3.gnulahd.nu/the-crown-6x10/"></a>
              <script>var _gnrdPid=526798,_gnrdTok="19b2dbca009b009d015ddc2b45d96cb7";</script>
            </body></html>
        """.trimIndent()
        val epDetail = GnulaHDMarkup.parseDetail(Jsoup.parse(epHtml, "https://ww3.gnulahd.nu/the-crown-6x10/"), "https://ww3.gnulahd.nu/the-crown-6x10/")!!
        assertEquals("https://ww3.gnulahd.nu/ver/the-crown/", epDetail.parentSeriesUrl)
        assertEquals("526798", epDetail.playerId)
    }

    @Test
    fun `parseQuickSearchJson handles object and array payloads cleanly`() {
        val json = """{"results":[{"title":"The Matrix","url":"/ver/the-matrix/","poster":"/wp-content/uploads/m.webp","year":"1999","type":"Película","rating":"8.7"}]}"""
        val items = GnulaHDMarkup.parseQuickSearchJson(json)
        assertEquals(1, items.size)
        assertEquals("The Matrix", items[0].title)
        assertEquals("https://ww3.gnulahd.nu/ver/the-matrix/", items[0].url)
        assertEquals(1999, items[0].year)
        assertEquals(8.7, items[0].rating!!, 0.01)
        assertEquals(GnulaMediaKind.MOVIE, items[0].kind)
    }
}
