package com.menorah.animeonline2

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeOnline2ParserTest {

    @Test
    fun urlNormalizationAndBuilders() {
        assertEquals(
            "https://ww4.animeonline.ninja/online/dragon-ball-super/",
            AnimeOnline2Parser.normalizeSiteUrl("https://www1.animeonline.ninja/online/dragon-ball-super/"),
        )
        assertEquals(
            "https://ww4.animeonline.ninja/genero/audio-latino/",
            AnimeOnline2Parser.normalizeSiteUrl("https://www3.animeonline.ninja/genero/audio-latino/"),
        )
        assertEquals(
            "https://ww4.animeonline.ninja/pelicula/kimi-no-na-wa/",
            AnimeOnline2Parser.normalizeSiteUrl("/pelicula/kimi-no-na-wa/"),
        )
        assertEquals(
            "https://ww4.animeonline.ninja/online/page/2/",
            AnimeOnline2Parser.buildArchivePageUrl("/online/", 2),
        )
        assertEquals(
            "https://ww4.animeonline.ninja/?s=naruto+shippuden",
            AnimeOnline2Parser.buildSearchUrl("naruto shippuden", 1),
        )
        assertEquals(
            "https://ww4.animeonline.ninja/search/page/2/?s=naruto",
            AnimeOnline2Parser.buildSearchUrl("naruto", 2),
        )
    }

    @Test
    fun parseHomeAndArchiveCards() {
        val html = """
            <html><body>
            <div class="module">
              <div class="content">
                <article class="item se episodes" id="post-251056">
                  <div class="poster">
                    <img class="lazy" data-src="https://ww4.animeonline.ninja/wp-content/uploads/2026/11/ep19.jpg" src="data:image/svg+xml,%3Csvg%3E%3C/svg%3E" alt="Yozakura 19" />
                    <div class="season_m animation-1"><a href="https://ww4.animeonline.ninja/episodio/yozakura-cap-19/"><div class="see"></div></a></div>
                    <div class="epiposter"><h4><span class="icon-star1"></span>Episodio 19</h4></div>
                  </div>
                  <center><div class="data"><h3>Mission: Yozakura Family 2</h3></div></center>
                </article>
                <article class="item se seasons" id="post-249971">
                  <div class="poster">
                    <img class="lazy" data-src="https://ww4.animeonline.ninja/wp-content/uploads/2026/10/s2.jpg" src="data:image/svg+xml,foo" />
                    <div class="season_m animation-1"><a href="https://ww4.animeonline.ninja/temporada/kizu-temporada-2/"><span class="a">temporada</span><span class="b">2</span><span class="c">Kizu darake Seijo</span></a></div>
                  </div>
                  <div class="data"><h3><a href="https://ww4.animeonline.ninja/temporada/kizu-temporada-2/">Temporada 2</a></h3><span>Oct. 02, 2026</span></div>
                </article>
                <article class="item movies" id="post-34933">
                  <div class="poster">
                    <img data-src="https://ww4.animeonline.ninja/wp-content/uploads/2020/06/mov.jpg" src="data:image/svg+xml,foo" />
                    <div class="rating"><span class="icon-star2"></span> 8.7</div>
                    <a href="https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/"><div class="see"></div></a>
                  </div>
                  <div class="data"><h3><a href="https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/">All You Need Is Kill</a></h3><span>2026</span></div>
                </article>
                <div id="featured-titles">
                  <article class="item tvshows" id="post-featured-1">
                    <div class="poster">
                      <img data-src="https://ww4.animeonline.ninja/wp-content/uploads/2024/10/dbd.jpg" src="data:image/svg+xml,foo" />
                      <div class="rating">8.2</div>
                      <a href="https://www1.animeonline.ninja/online/dragon-ball-daima/"></a>
                    </div>
                    <div class="data"><h3><a href="https://www1.animeonline.ninja/online/dragon-ball-daima/">Dragon Ball Daima</a></h3><span>2024</span></div>
                  </article>
                </div>
                <div class="pagination"><span class="current">1</span><a href="https://ww4.animeonline.ninja/online/page/2/">2</a></div>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val home = AnimeOnline2Parser.parseHomeModules(html)
        assertEquals(1, home.latestEpisodes.size)
        assertEquals("Mission: Yozakura Family 2", home.latestEpisodes[0].title)
        assertEquals(19, home.latestEpisodes[0].episodeNumber)
        assertEquals("https://ww4.animeonline.ninja/wp-content/uploads/2026/11/ep19.jpg", home.latestEpisodes[0].posterUrl)

        assertEquals(1, home.featuredOnAir.size)
        assertEquals("https://ww4.animeonline.ninja/online/dragon-ball-daima/", home.featuredOnAir[0].url)
        assertEquals("8.2", home.featuredOnAir[0].rating)

        assertEquals(1, home.seasons.size)
        assertEquals("Kizu darake Seijo - Temporada 2", home.seasons[0].title)

        assertEquals(1, home.latestMovies.size)
        assertTrue(home.latestMovies[0].isMovie)
        assertEquals(2026, home.latestMovies[0].year)

        val archive = AnimeOnline2Parser.parseArchivePage(html, 1)
        assertTrue(archive.hasNext)
        assertEquals(4, archive.items.size)
    }

    @Test
    fun parseSearchAndEpisodeParentLink() {
        val searchHtml = """
            <div class="result-item">
              <article>
                <div class="image"><div class="thumbnail">
                  <a href="https://ww4.animeonline.ninja/online/naruto-shippuden/"><img data-src="https://ww4.animeonline.ninja/n.jpg" src="data:image/svg+xml,x" /><span class="tvshows">TV</span></a>
                </div></div>
                <div class="details">
                  <div class="title"><a href="https://ww4.animeonline.ninja/online/naruto-shippuden/">Naruto Shippuden</a></div>
                  <div class="meta"><span class="rating">IMDb 8.6</span><span class="year">2007</span></div>
                </div>
              </article>
            </div>
            <div class="pagination"><a href="https://ww4.animeonline.ninja/search/page/2/?s=naruto">2</a></div>
        """.trimIndent()

        val sr = AnimeOnline2Parser.parseSearchPage(searchHtml, 1)
        assertTrue(sr.hasNext)
        assertEquals(1, sr.items.size)
        assertEquals("Naruto Shippuden", sr.items[0].title)
        assertEquals("8.6", sr.items[0].rating)
        assertEquals(2007, sr.items[0].year)

        val epHtml = """
            <div class="pag_episodes">
              <div class="item"><a href="#">episodio anterior</a></div>
              <div class="item"><a href="https://ww4.animeonline.ninja/online/radiant/">lista de episodios</a></div>
              <div class="item"><a href="https://ww4.animeonline.ninja/episodio/radiant-cap-2/">siguiente episodio</a></div>
            </div>
        """.trimIndent()
        assertEquals(
            "https://ww4.animeonline.ninja/online/radiant/",
            AnimeOnline2Parser.findParentSeriesUrlFromEpisode(epHtml),
        )
    }

    @Test
    fun parseDetailFiltersSeoSpamAndExtractsSeasonsAndPlayers() {
        val detailHtml = """
            <html><body>
            <header><h1>Ver Anime</h1></header>
            <div id="single">
              <div class="sheader">
                <div class="poster"><img data-src="https://ww4.animeonline.ninja/wp-content/uploads/radiant.jpg" src="data:image/svg+xml,x" /></div>
                <div class="data">
                  <h1>Radiant</h1>
                  <div class="extra"><span class="date">Oct. 06, 2018</span><span class="runtime">24 Min.</span></div>
                  <div class="sgeneros">
                    <a href="/genero/accion/">Acción</a>
                    <a href="/genero/animeflv/">anime flv</a>
                    <a href="/genero/fantasia/">Fantasía</a>
                    <a href="/genero/jkanime/">jk anime</a>
                    <a href="/genero/audio-latino/">Audio Latino</a>
                  </div>
                </div>
              </div>
              <div class="dt_rating_vgs">8.4</div>
              <div id="info"><div class="wp-content"><p>Sinopsis de prueba.</p></div></div>
              <div id="dt_galery"><div class="g-item"><a href="https://image.tmdb.org/t/p/original/bg.jpg&#13;"></a></div></div>
              <div id="cast"><div class="person"><div class="img"><img data-src="https://image.tmdb.org/p.jpg" /></div><div class="data"><div class="name"><a>Actor Uno</a></div><div class="caracter">Seth</div></div></div></div>
              <div id="seasons">
                <div class="se-c"><div class="se-q"><span class="se-t">2</span></div><div class="se-a"><ul class="episodios">
                  <li><div class="imagen"><img data-src="https://ww4.animeonline.ninja/e2.jpg" /></div><div class="numerando">2 - 1</div><div class="episodiotitle"><a href="https://ww4.animeonline.ninja/episodio/radiant-2x1/">Episodio 2x1</a><span class="date">2019</span></div></li>
                </ul></div></div>
                <div class="se-c"><div class="se-q"><span class="se-t">1</span></div><div class="se-a"><ul class="episodios">
                  <li><div class="imagen"><img data-src="https://ww4.animeonline.ninja/e1.jpg" /></div><div class="numerando">1 - 1</div><div class="episodiotitle"><a href="https://ww4.animeonline.ninja/episodio/radiant-1x1/">Episodio 1x1</a><span class="date">2018</span></div></li>
                </ul></div></div>
              </div>
              <ul id="playeroptionsul">
                <li class="dooplay_player_option dooplay_player_option_trailer" data-post="99" data-nume="trailer" data-type="tv">Trailer</li>
                <li class="dooplay_player_option" data-post="51020" data-nume="1" data-type="tv"><span class="title">MULTISERVER saidochesto.top</span></li>
              </ul>
            </div>
            </body></html>
        """.trimIndent()

        val detail = AnimeOnline2Parser.parseDetailPage("https://ww4.animeonline.ninja/online/radiant/", detailHtml)
        assertEquals("Radiant", detail.title)
        assertFalse(detail.isMovie)
        assertEquals(2018, detail.year)
        assertEquals(24, detail.durationMinutes)
        assertEquals("8.4", detail.rating)
        assertEquals("https://image.tmdb.org/t/p/original/bg.jpg", detail.backdropUrl)
        assertEquals(listOf("Acción", "Fantasía", "Audio Latino"), detail.tags)
        assertEquals(1, detail.cast.size)
        assertEquals(2, detail.episodes.size)
        assertEquals(1 to 1, detail.episodes[0].season to detail.episodes[0].episode)
        assertEquals(2 to 1, detail.episodes[1].season to detail.episodes[1].episode)

        val opts = AnimeOnline2Parser.parsePlayerOptions(detailHtml)
        assertEquals(1, opts.size)
        assertEquals("51020", opts[0].postId)
        assertEquals("1", opts[0].nume)
        assertEquals("tv", opts[0].type)
    }

    @Test
    fun parseSaidochestoGuploadAndByseCrypto() {
        val embJson = """{"embed_url":"https:\/\/saidochesto.top\/embed.php?id=51020","type":"iframe"}"""
        assertEquals("https://saidochesto.top/embed.php?id=51020", AnimeOnline2Parser.parseDooplayerEmbedUrl(embJson))

        val saidoHtml = """
            <div class="SelectLangDisp">
              <li onclick="SelLang(this, 'SUB')"><span>SUBTITULADO</span></li>
              <li onclick="SelLang(this, 'LAT')"><span>ESPAÑOL LATINO</span></li>
            </div>
            <div class="OptionsLangDisp">
              <div class="OD OD_SUB">
                <li onclick="go_to_player('https://gupload.site/data/e/abc')"><span>gupload</span></li>
                <li onclick="go_to_player('https://vidara.to/e/xyz')"><span>vidara</span></li>
              </div>
              <div class="OD OD_LAT">
                <li onclick="go_to_player('https://savefiles.top/e/lat1')"><span>savefiles</span></li>
              </div>
            </div>
        """.trimIndent()
        val servers = AnimeOnline2Parser.parseSaidochestoOptions(saidoHtml)
        assertEquals(3, servers.size)
        assertEquals("Subtitulado", servers[0].languageLabel)
        assertEquals("https://gupload.site/data/e/abc", servers[0].url)
        assertEquals("Latino", servers[2].languageLabel)
        assertEquals("https://savefiles.top/e/lat1", servers[2].url)

        // Test gupload XOR decoding with real cipher
        val plainPayload = """{"videoUrl":"https:\/\/gupload.site\/data\/e\/hls\/test123\/720p.m3u8"}"""
        val key = "G7#kP!2qZxV9mRwL".toByteArray(StandardCharsets.UTF_8)
        val rawBytes = plainPayload.toByteArray(StandardCharsets.UTF_8)
        val xored = ByteArray(rawBytes.size) { i -> (rawBytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
        val b64 = Base64.getEncoder().encodeToString(xored)
        val guploadHtml = """<script>var _p=['G7#k','P!2q','ZxV9','mRwL']; var _data="prefix~$b64";</script>"""
        assertEquals(
            "https://gupload.site/data/e/hls/test123/720p.m3u8",
            AnimeOnline2Parser.extractGuploadVideoUrl(guploadHtml),
        )

        // Test ByseSX AES-GCM decryption
        val aesKey = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { (it + 5).toByte() }
        val byseJson = """{"sources":[{"url":"https://cdn.example/master.m3u8"}]}""".toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(byseJson)
        val b64u = Base64.getUrlEncoder().withoutPadding()
        val p1 = b64u.encodeToString(aesKey.copyOfRange(0, 16))
        val p2 = b64u.encodeToString(aesKey.copyOfRange(16, 32))
        assertEquals(
            "https://cdn.example/master.m3u8",
            AnimeOnline2Parser.decryptBysePlayback(b64u.encodeToString(iv), b64u.encodeToString(enc), listOf(p1, p2)),
        )
    }
}
