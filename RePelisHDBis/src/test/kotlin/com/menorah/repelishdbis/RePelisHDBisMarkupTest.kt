package com.menorah.repelishdbis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RePelisHDBisMarkupTest {

    @Test
    fun parsesCardsAndExplicitPaginationWithoutSizeHeuristic() {
        val gridItems = (1..40).joinToString("\n") { i ->
            """
            <a class="cv" href="https://repelishd.hair/ver-pelicula/peli-$i" title="Película $i">
              <div class="cv-art"><img src="https://repelishd.hair/timg/t/w342/p$i.jpg" alt="Película $i"></div>
              <div class="cv-t">Película $i</div>
              <div class="cv-m">2026</div>
            </a>
            """.trimIndent()
        }
        val docLastPage = Jsoup.parse("<html><body><div class=\"griglia\">$gridItems</div></body></html>", RePelisHDBisMarkup.DEFAULT_MAIN_URL)
        val cards = RePelisHDBisMarkup.parseCards(docLastPage)
        assertEquals(40, cards.size)
        assertFalse(RePelisHDBisMarkup.parseHasNext(docLastPage, 1, cards.size))

        val docWithNext = Jsoup.parse(
            """<html><body><div class="griglia">$gridItems</div><div class="carica"><a href="https://repelishd.hair/explorar?sort=popular&page=2">Siguiente</a></div></body></html>""",
            RePelisHDBisMarkup.DEFAULT_MAIN_URL,
        )
        assertTrue(RePelisHDBisMarkup.parseHasNext(docWithNext, 1, cards.size))
    }

    @Test
    fun parsesRealSpiderManChhLocAndSfondoAndDownloads() {
        val movieHtml = """
            <html><body>
              <div class="chh">
                <div class="chh-sfondo" style="background-image:url(https://repelishd.hair/timg/t/w1280/sA9f6hJ1v2.jpg)"></div>
                <div class="chh-loc"><img src="https://repelishd.hair/timg/t/w500/spidey.jpg" alt="Spider-Man"></div>
                <h1>Spider-Man: Brand New Day <span class="chh-h1sub">2026</span></h1>
                <ul class="chh-badge"><li>2026</li><li>126 min</li><li class="b-nudo"><b class="chh-voto">8.2</b></li></ul>
                <span class="chh-idi">LAT</span><span class="chh-idi">CAS</span><span class="chh-idi">SUB</span>
                <p class="sin-t">Peter Parker comienza un nuevo capítulo en Nueva York.</p>
                <a class="chh-gpill" href="/explorar?g=accion">Acción</a>
                <div class="chh-lista"><a href="/actores/tom-holland">Tom Holland</a></div>
              </div>
              <iframe src="https://playlatinsrc.co/movie/tt22084616?lang=latino"></iframe>
              <div class="dl-box">
                <a class="dl-btn" href="https://1fichier.com/?abcdef123">
                  <span class="dl-host">1fichier</span><span class="dl-lang">LAT</span><span class="dl-qual">1080p</span>
                </a>
              </div>
              <section class="chn">
                <div class="chn-fila">
                  <a class="cv" href="/ver-pelicula/spider-man-no-way-home">
                    <div class="cv-art"><img src="/timg/t/w342/nwh.jpg"></div>
                    <div class="cv-t">Spider-Man: No Way Home</div>
                  </a>
                </div>
              </section>
            </body></html>
        """.trimIndent()
        val detail = RePelisHDBisMarkup.parseDetail(
            Jsoup.parse(movieHtml, "https://repelishd.hair/ver-pelicula/spider-man-brand-new-day"),
            "https://repelishd.hair/ver-pelicula/spider-man-brand-new-day",
        )!!
        assertEquals("Spider-Man: Brand New Day", detail.title)
        assertEquals("https://repelishd.hair/timg/t/w500/spidey.jpg", detail.posterUrl)
        assertEquals("https://repelishd.hair/timg/t/w1280/sA9f6hJ1v2.jpg", detail.backgroundUrl)
        assertEquals(126, detail.durationMinutes)
        assertEquals(8.2, detail.rating10!!, 0.01)
        assertEquals("tt22084616", detail.imdbId)
        assertEquals(listOf("Latino", "Castellano", "Subtitulado"), detail.languages)
        assertEquals(listOf("Tom Holland"), detail.actors)
        assertEquals(1, detail.downloadLinks.size)
        assertEquals("1fichier", detail.downloadLinks[0].host)
        assertEquals("Latino", detail.downloadLinks[0].language)
        assertEquals(1, detail.recommendations.size)
    }

    @Test
    fun parsesNextFlightInizialeAndSerialEpisodes() {
        val escapedPayload = """
            {"iniziale":{"imdb":"tt1405406","base":"/ver-serie/cronicas-vampiricas-18165","titolo":"Crónicas vampíricas","stagione":8,"episodio":15,"stagioni":[{"season_number":1,"name":"Temporada 1","disponibili":22},{"season_number":8,"name":"Temporada 8","disponibili":16}],"episodi":[{"season_number":8,"episode_number":15,"name":"Estamos planeando una boda de junio","overview":"Una boda se organiza rápidamente.","sfondo":"https://repelishd.hair/timg/t/w500/ep15.jpg"}]}}
        """.trim().replace("\\", "\\\\").replace("\"", "\\\"")

        val html = """
            <html><body>
              <h1>Crónicas vampíricas <span class="chh-h1sub">T8·E15</span></h1>
              <script>self.__next_f.push([1,"$escapedPayload"])</script>
            </body></html>
        """.trimIndent()
        val detail = RePelisHDBisMarkup.parseDetail(
            Jsoup.parse(html, "https://repelishd.hair/ver-serie/cronicas-vampiricas-18165"),
            "https://repelishd.hair/ver-serie/cronicas-vampiricas-18165",
        )!!
        assertTrue(detail.isSeries)
        assertEquals("tt1405406", detail.imdbId)
        val ini = detail.iniziale!!
        assertEquals(8, ini.activeSeason)
        assertEquals(15, ini.activeEpisode)
        assertEquals(2, ini.seasons.size)
        assertEquals("https://playlatinsrc.co/serial/tt1405406?season=8&episode=15&lang=latino", ini.episodes[0].playDataUrl)
    }

    @Test
    fun parsesLivePlayLatinResolveAndFiltersFakeDroploadSearchVtt() {
        val movieNewHtml = """
            <div data-resolve-endpoint="/api/resolve" data-resolve-token="tok_movie_1" data-alt-url="https://unlimplay.com/f/embed/xyz"></div>
        """.trimIndent()
        val movieSpecs = PlayLatinSrcParser.extractResolveSpecs(movieNewHtml, "https://playlatinsrc.co/movie/tt22084616?lang=latino")
        assertEquals(1, movieSpecs.size)
        assertEquals("/api/resolve", movieSpecs[0].endpoint)
        assertTrue(movieSpecs[0].payloadJson.contains("tt22084616"))
        assertEquals("https://unlimplay.com/f/embed/xyz", PlayLatinSrcParser.extractAltUrl(movieNewHtml))

        // Live 2026 PlayLatinSRC movie/tv fetch('/api/embed-links') + escaped var ALT_URL
        val liveMovieHtml = """
            <script>
              var ALT_URL = "https:\/\/unlimplay.com\/f\/embed\/movie\/tt32558705";
              fetch('/api/embed-links', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ type: 'movie', id: "tt32558705", token: "tok_live_1" })
              });
            </script>
        """.trimIndent()
        val liveMovieSpecs = PlayLatinSrcParser.extractResolveSpecs(liveMovieHtml, "https://playlatinsrc.co/movie/1300968?lang=latino")
        assertEquals(1, liveMovieSpecs.size)
        assertEquals("/api/embed-links", liveMovieSpecs[0].endpoint)
        assertTrue(liveMovieSpecs[0].payloadJson.contains("tt32558705"))
        assertEquals("https://unlimplay.com/f/embed/movie/tt32558705", PlayLatinSrcParser.extractAltUrl(liveMovieHtml))

        val liveTvHtml = """
            <script>
              fetch('/api/embed-links', {
                  method: 'POST',
                  body: JSON.stringify({ type: 'tv', id: "tt0944947", token: "tok_tv_1" })
              });
            </script>
        """.trimIndent()
        val liveTvSpecs = PlayLatinSrcParser.extractResolveSpecs(liveTvHtml, "https://playlatinsrc.co/serial/tt0944947?season=1&episode=2&lang=latino")
        assertEquals(1, liveTvSpecs.size)
        assertEquals("/api/embed-links", liveTvSpecs[0].endpoint)
        assertTrue(liveTvSpecs[0].payloadJson.contains("\"type\":\"tv\""))

        val tvApiJson = """
            {"ok":true,"type":"tv","tv":{"title":"Juego de tronos","version":"latino","seasons":[{"season_number":1,"episodes":[{"episode_number":1,"sources":[{"name":"dropload.io","url":"https://dr0pstream.com/e/ep1"}]},{"episode_number":2,"sources":[{"name":"supervideo.tv","url":"https://superplayer.ws/e/ep2"}]}]}]}}
        """.trimIndent()
        val tvParsed = PlayLatinSrcParser.parseApiResponse(tvApiJson, targetSeason = 1, targetEpisode = 2)
        assertEquals(1, tvParsed.sources.size)
        assertEquals("https://superplayer.ws/e/ep2", tvParsed.sources[0].url)
        assertEquals("latino", tvParsed.sources[0].version)

        // Slug fallback for movies without inline tt... in SSR
        assertEquals("1300968", RePelisHDBisMarkup.extractSlugTmdbId("https://repelishd.hair/ver-pelicula/1300968-los-juegos-del-hambre-amanecer-en-la-cosecha-online-espanol"))
        val slugOnlyDetail = RePelisHDBisMarkup.parseDetail(
            Jsoup.parse("<html><body><h1>Amanecer en la cosecha</h1></body></html>", "https://repelishd.hair/ver-pelicula/1300968-amanecer"),
            "https://repelishd.hair/ver-pelicula/1300968-amanecer",
        )!!
        assertEquals("https://playlatinsrc.co/movie/1300968?lang=latino", slugOnlyDetail.playerUrl)

        // UnlimPlay parser
        val unlimHtml = """
            <script>if(typeof finalizePlayer==="function"){finalizePlayer({"latino":{"streamwish":"https://unlimplay.com/cps.php?t=abc&x=.m3u8","voe":"https://voe.sx/e/xyz123"},"subtitulado":{"vidhide":"https://unlimplay.com/cps.php?t=def&x=.m3u8"}});}</script>
        """.trimIndent()
        val unlimEntries = UnlimPlayParser.parseEntries(unlimHtml)
        assertEquals(3, unlimEntries.size)
        assertEquals("Latino", unlimEntries[0].language)
        assertEquals("https://unlimplay.com/cps.php?t=abc&x=.m3u8", unlimEntries[0].url)
        assertEquals("Subtitulado", unlimEntries[2].language)

        val serialHtml = """
            <div data-resolve-endpoint="/api/resolve" data-resolve-token="tok_serial_2"></div>
        """.trimIndent()
        val serialSpec = PlayLatinSrcParser.extractSerialRequest(serialHtml, "https://playlatinsrc.co/serial/tt1405406?season=8&episode=15&lang=latino")!!
        assertEquals("/api/resolve", serialSpec.endpoint)
        assertTrue(serialSpec.payloadJson.contains("\"season\":8"))

        val apiJson = """
            {"ok":true,"sources":[{"name":"Supervideo","url":"https://supervideo.tv/e/abc123","version":"Castellano","kind":"embed"},{"name":"HLS","url":"https://cdn.example.com/master.m3u8","version":"LAT","kind":"hls"}]}
        """.trimIndent()
        val parsed = PlayLatinSrcParser.parseApiResponse(apiJson)
        assertEquals(2, parsed.sources.size)
        assertEquals("Castellano", PlayLatinSrcParser.normalizeVersionLabel(parsed.sources[0].version))
        assertEquals("Latino", PlayLatinSrcParser.normalizeVersionLabel(parsed.sources[1].version))

        val packedDropload = """
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0={1:[{2:"3://4.5/6/7.8"}],9:[{2:"/a/b.c",d:"e",f:"g"},{2:"/a/h.i",d:"e",f:"j"}]};',20,20,'jw|sources|file|https|cdn|dropload|v|master|m3u8|tracks|srt|_search|vtt|kind|captions|label|Search subtitles...|real|vtt|Español'.split('|')))</script>
        """.trimIndent()
        val hls = PackedHlsParser.parse(packedDropload, "https://dropload.io/e/w4ptck2ikmh0")
        assertEquals(listOf("https://cdn.dropload/v/master.m3u8"), hls.m3u8Urls)
        assertEquals(listOf("Español" to "https://dropload.io/srt/real.vtt"), hls.subtitles)
    }
}
