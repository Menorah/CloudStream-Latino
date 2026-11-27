package com.menorah.repelishdbis

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RePelisHDBisMarkupTest {

    @Test
    fun parsesCatalogCardsAndSearchUrl() {
        val html = """
            <html><body>
              <a class="cv" href="/ver-pelicula/71688-la-dama-de-hierro-online-espanol" title="La dama de hierro" data-voto="6,4">
                <div class="cv-art"><img src="https://image.tmdb.org/t/p/w342/r4jO2yxJ6X00DFnlpjNxKuDvmOB.jpg" alt="La dama de hierro"/><span class="rate">★ 6,4</span></div>
                <div class="cv-t">La dama de hierro</div>
                <div class="cv-m">2011</div>
              </a>
              <a class="cv" href="/ver-serie/1877-phineas-y-ferb-online-espanol" title="Phineas y Ferb" data-voto="8,1">
                <div class="cv-art"><img src="https://image.tmdb.org/t/p/w342/pf.jpg" alt="Phineas y Ferb"/><span class="rate">★ 8,1</span></div>
                <div class="cv-t">Phineas y Ferb</div>
                <div class="cv-m">Serie</div>
              </a>
              <a href="/buscar?q=hierro&page=2">Siguiente</a>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, RePelisHDBisMarkup.DEFAULT_MAIN_URL)
        val cards = RePelisHDBisMarkup.parseCards(doc)
        assertEquals(2, cards.size)
        assertEquals("La dama de hierro", cards[0].title)
        assertFalse(cards[0].isSeries)
        assertEquals(2011, cards[0].year)
        assertEquals(6.4, cards[0].rating10!!, 0.01)
        assertTrue(cards[1].isSeries)
        assertEquals(8.1, cards[1].rating10!!, 0.01)
        assertTrue(RePelisHDBisMarkup.parseHasNext(doc, 1, cards.size))
        assertEquals("https://repelishd.surf/buscar?q=iron+man&page=2", RePelisHDBisMarkup.buildSearchUrl("iron man", 2))
    }

    @Test
    fun parsesMovieDetailAndSeriesInizialeFromNextFlight() {
        val movieHtml = """
            <html><body>
              <div class="chh-poster"><img src="https://image.tmdb.org/t/p/w500/bFj7XRg5avQDvuvWaag3IttjEAw.jpg"/></div>
              <b class="chh-voto">★ 7.9</b>
              <h1>Iron Man <span class="chh-h1sub">online HD</span></h1>
              <ul class="chh-badge"><li class="b-film">PELÍCULA</li><li class="b-nudo">2h 6m</li><li class="b-nudo">2008</li></ul>
              <div class="chh-gen"><a class="chh-gpill" href="/genero/accion">Acción</a><a class="chh-gpill" href="/genero/aventura">Aventura</a></div>
              <div class="sin"><p class="sin-t">Tony Stark construye una armadura.</p></div>
              <ul class="chh-lista"><li><b>Director</b><span><a href="/director/jon-favreau">Jon Favreau</a></span></li><li><b>Reparto</b><span><a href="/actores/robert-downey-jr">Robert Downey Jr.</a></span></li></ul>
              <iframe src="https://playlatinsrc.co/movie/tt0371746?lang=latino"></iframe>
              <section class="chn">
                <a class="cv" href="/ver-pelicula/1727-iron-man-2"><div class="cv-art"><img src="https://image.tmdb.org/t/p/w342/im2.jpg"/></div><div class="cv-t">Iron Man 2</div><div class="cv-m">2010</div></a>
              </section>
            </body></html>
        """.trimIndent()
        val mDoc = Jsoup.parse(movieHtml, "https://repelishd.surf/ver-pelicula/1726-iron-man-online-espanol")
        val mDetail = RePelisHDBisMarkup.parseDetail(mDoc, "https://repelishd.surf/ver-pelicula/1726-iron-man-online-espanol")
        assertNotNull(mDetail)
        mDetail!!
        assertEquals("Iron Man", mDetail.title)
        assertFalse(mDetail.isSeries)
        assertEquals(2008, mDetail.year)
        assertEquals(126, mDetail.durationMinutes)
        assertEquals("tt0371746", mDetail.imdbId)
        assertEquals("https://playlatinsrc.co/movie/tt0371746?lang=latino", mDetail.playerUrl)
        assertEquals(1, mDetail.recommendations.size)

        val pushedPayload = """18:["${'$'}L19",["${'$'}","main",null,{"iniziale":{"mediaId":75376,"imdb":"tt1844624","base":"/ver-serie/1413-american-horror-story-online-espanol","titolo":"American Horror Story","stagioni":[{"season_number":1,"name":"Murder House","disponibili":12},{"season_number":2,"name":"Asylum","disponibili":13}],"stagione":2,"episodio":1,"episodi":[{"season_number":2,"episode_number":1,"name":"Bienvenidos a Briarcliff","overview":"Manicomio.","sfondo":"https://image.tmdb.org/t/p/w780/ep21.jpg"}]}}]]"""
        val escaped = pushedPayload.replace("\\", "\\\\").replace("\"", "\\\"")
        val serieHtml = """
            <html><body>
              <h1>American Horror Story <span class="chh-h1sub">online HD</span></h1>
              <script>self.__next_f.push([1,"$escaped"])</script>
            </body></html>
        """.trimIndent()
        val sDoc = Jsoup.parse(serieHtml, "https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol")
        val ini = RePelisHDBisMarkup.parseIniziale(sDoc)
        assertNotNull(ini)
        ini!!
        assertEquals("tt1844624", ini.imdbId)
        assertEquals(2, ini.seasons.size)
        assertEquals(1, ini.episodes.size)
        assertEquals("https://playlatinsrc.co/serial/tt1844624/2/1?lang=latino", ini.episodes[0].playDataUrl)
    }

    @Test
    fun extractsPlayLatinSrcRequestsAndUnpacksDr0pstreamAndSuperplayer() {
        val moviePlayerHtml = """
            <script>
              fetch('/api/embed-links', {
                method: 'POST',
                body: JSON.stringify({ type: 'movie', id: "tt0371746", token: "tok123.sig" })
              });
            </script>
        """.trimIndent()
        val mReq = PlayLatinSrcParser.extractMovieRequest(moviePlayerHtml)
        assertNotNull(mReq)
        assertEquals("/api/embed-links", mReq!!.endpoint)
        assertTrue(mReq.payloadJson.contains("tok123.sig"))

        val serialPlayerHtml = """
            <div class="player-shell" data-resolve-token="serTok456" data-resolve-endpoint="/api/resolve"></div>
        """.trimIndent()
        val sReq = PlayLatinSrcParser.extractSerialRequest(serialPlayerHtml, "https://playlatinsrc.co/serial/tt1844624/1/2?lang=latino")
        assertNotNull(sReq)
        assertEquals("/api/resolve", sReq!!.endpoint)
        assertTrue(sReq.payloadJson.contains("\"season\":1"))
        assertTrue(sReq.payloadJson.contains("\"episode\":2"))

        val apiJson = """
            {"ok":true,"sources":[{"name":"dropload.io","url":"https://dr0pstream.com/e/74ba1v9zwn71","version":"latino","kind":"embed"},{"name":"supervideo.tv","url":"https://superplayer.ws/e/uhu5ojv9ts8s","kind":"embed"}]}
        """.trimIndent()
        val resolved = PlayLatinSrcParser.parseApiResponse(apiJson)
        assertEquals(2, resolved.sources.size)
        assertEquals("https://dr0pstream.com/e/74ba1v9zwn71", resolved.sources[0].url)

        val packedDr0p = """
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0({1:[{2:"3://4.5.6/7/8.9"}]});',10,10,'jwplayer|sources|file|https|ds2|dropcdn|io|hls|master|m3u8'.split('|')))</script>
        """.trimIndent()
        val hls = PackedHlsParser.parse(packedDr0p, "https://dr0pstream.com/e/abc")
        assertEquals(listOf("https://ds2.dropcdn.io/hls/master.m3u8"), hls.m3u8Urls)
    }
}
