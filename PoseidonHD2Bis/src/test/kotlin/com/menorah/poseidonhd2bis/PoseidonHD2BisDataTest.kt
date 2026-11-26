package com.menorah.poseidonhd2bis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PoseidonHD2BisDataTest {

    @Test
    fun `buildPagedUrl and slugToPublicUrl handle catalog routes and Next slugs accurately`() {
        assertEquals("https://www.poseidonhd2.co/peliculas", PoseidonHD2BisData.buildPagedUrl("/peliculas", 1))
        assertEquals("https://www.poseidonhd2.co/peliculas/page/2", PoseidonHD2BisData.buildPagedUrl("/peliculas", 2))
        assertEquals("https://www.poseidonhd2.co/genero/ciencia-ficcion/page/3", PoseidonHD2BisData.buildPagedUrl("/genero/ciencia-ficcion", 3))

        assertEquals(
            "https://www.poseidonhd2.co/pelicula/603/the-matrix",
            PoseidonHD2BisData.slugToPublicUrl("movies/603/the-matrix"),
        )
        assertEquals(
            "https://www.poseidonhd2.co/serie/84773/la-casa-del-dragon",
            PoseidonHD2BisData.slugToPublicUrl("series/84773/la-casa-del-dragon"),
        )
        assertEquals(
            "https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4",
            PoseidonHD2BisData.slugToPublicUrl("series/254630/12-12-12/seasons/1/episodes/4"),
        )
        assertEquals(
            "https://www.poseidonhd2.co/serie/254630/12-12-12",
            PoseidonHD2BisData.extractSeriesBaseUrl("https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4"),
        )
    }

    @Test
    fun `parseCatalogPage handles movies array and episodes array with pages count`() {
        val moviesHtml = """
            <html><head></head><body>
            <script id="__NEXT_DATA__" type="application/json">{
              "props":{"pageProps":{
                "pages":804,
                "movies":[
                  {
                    "TMDbId":"603",
                    "titles":{"name":"Matrix"},
                    "images":{"poster":"https://image.tmdb.org/t/p/original/matrix.jpg"},
                    "url":{"slug":"movies/603/the-matrix"},
                    "releaseDate":"1999-03-31T00:00:00.000Z"
                  },
                  {
                    "TMDbId":"84773",
                    "titles":{"name":"La Casa del Dragón"},
                    "images":{"poster":"https://image.tmdb.org/t/p/original/hotd.jpg"},
                    "url":{"slug":"series/84773/la-casa-del-dragon"},
                    "releaseDate":"2022-08-21T00:00:00.000Z"
                  }
                ]
              }}
            }</script>
            </body></html>
        """.trimIndent()

        val catalog = PoseidonHD2BisData.parseCatalogPage(moviesHtml)
        assertEquals(804, catalog.totalPages)
        assertEquals(2, catalog.items.size)
        assertEquals(PoseidonItemKind.Movie, catalog.items[0].kind)
        assertEquals("Matrix", catalog.items[0].title)
        assertEquals("https://www.poseidonhd2.co/pelicula/603/the-matrix", catalog.items[0].url)
        assertEquals(1999, catalog.items[0].year)
        assertEquals(PoseidonItemKind.Series, catalog.items[1].kind)
        assertEquals("https://www.poseidonhd2.co/serie/84773/la-casa-del-dragon", catalog.items[1].url)
        assertEquals(2022, catalog.items[1].year)

        val episodesHtml = """
            <html><body>
            <script id="__NEXT_DATA__" type="application/json">{
              "props":{"pageProps":{
                "pages":3405,
                "episodes":[
                  {
                    "title":"12 12 12 1x4",
                    "number":4,
                    "releaseDate":"2026-07-02T00:00:00.000Z",
                    "image":"https://image.tmdb.org/t/p/original/ep4.jpg",
                    "url":{"slug":"series/254630/12-12-12/seasons/1/episodes/4"}
                  }
                ]
              }}
            }</script>
            </body></html>
        """.trimIndent()

        val epCatalog = PoseidonHD2BisData.parseCatalogPage(episodesHtml)
        assertEquals(3405, epCatalog.totalPages)
        assertEquals(1, epCatalog.items.size)
        assertEquals(PoseidonItemKind.Episode, epCatalog.items[0].kind)
        assertEquals("12 12 12 1x4", epCatalog.items[0].title)
        assertEquals("https://www.poseidonhd2.co/serie/254630/12-12-12/temporada/1/episodio/4", epCatalog.items[0].url)
        assertEquals(2026, epCatalog.items[0].year)
    }

    @Test
    fun `parseMovieDetail and parseSeriesDetail extract metadata, filter empty season 0 and map videos`() {
        val movieHtml = """
            <script id="__NEXT_DATA__" type="application/json">{
              "props":{"pageProps":{
                "thisMovie":{
                  "titles":{"name":"Matrix"},
                  "images":{"poster":"/poster.jpg","backdrop":"https://image.tmdb.org/t/p/original/bg.jpg"},
                  "overview":"Un programador descubre la verdad.",
                  "runtime":136,
                  "releaseDate":"1999-03-31T00:00:00.000Z",
                  "rate":{"average":8.2,"votes":25000},
                  "genres":[{"name":"Acción"},{"name":"Ciencia Ficción"}],
                  "cast":{"acting":[{"name":"Keanu Reeves"},{"name":"Carrie-Anne Moss"}]},
                  "url":{"slug":"movies/603/the-matrix"},
                  "videos":{
                    "latino":[{"cyberlocker":"vidhide","result":"https://player.poseidonhd2.co/player.php?h=abc","quality":"HD"}],
                    "english":[{"cyberlocker":"voesx","result":"https://player.poseidonhd2.co/player.php?h=xyz","quality":"1080p"}]
                  }
                },
                "relatedMovies":[
                  {"titles":{"name":"Matrix Recargado"},"images":{"poster":"https://image.tmdb.org/t/p/original/m2.jpg"},"url":{"slug":"movies/604/the-matrix-reloaded"},"releaseDate":"2003-05-15T00:00:00.000Z"}
                ]
              }}
            }</script>
        """.trimIndent()

        val movie = PoseidonHD2BisData.parseMovieDetail(movieHtml, "https://www.poseidonhd2.co/pelicula/603/the-matrix")
        assertNotNull(movie)
        assertEquals("Matrix", movie!!.title)
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg", movie.posterUrl)
        assertEquals(136, movie.durationMinutes)
        assertEquals(8.2, movie.rating!!, 0.01)
        assertEquals(listOf("Acción", "Ciencia Ficción"), movie.genres)
        assertEquals(listOf("Keanu Reeves", "Carrie-Anne Moss"), movie.actors)
        assertEquals(1, movie.recommendations.size)
        assertEquals(2, movie.videos.size)
        assertEquals("Latino", movie.videos[0].languageLabel)
        assertEquals("Subtitulado", movie.videos[1].languageLabel)

        val seriesHtml = """
            <script id="__NEXT_DATA__" type="application/json">{
              "props":{"pageProps":{
                "thisSerie":{
                  "titles":{"name":"La Casa del Dragón"},
                  "images":{"poster":"https://image.tmdb.org/t/p/original/s.jpg","backdrop":"https://image.tmdb.org/t/p/original/sb.jpg"},
                  "overview":"La historia de la Casa Targaryen.",
                  "releaseDate":"2022-08-21T00:00:00.000Z",
                  "rate":{"average":8.4,"votes":5000},
                  "genres":[{"name":"Drama"},{"name":"Fantasía"}],
                  "cast":{"acting":[{"name":"Matt Smith"},{"name":"Emma D'Arcy"}]},
                  "url":{"slug":"series/84773/la-casa-del-dragon"},
                  "seasons":[
                    {"number":0,"episodes":[]},
                    {"number":2,"episodes":[{"title":"Un hijo por un hijo","number":1,"releaseDate":"2024-06-16T00:00:00.000Z","image":"https://image.tmdb.org/t/p/original/2x1.jpg","url":{"slug":"series/84773/la-casa-del-dragon/seasons/2/episodes/1"}}]},
                    {"number":1,"episodes":[{"title":"Los herederos del dragón","number":1,"releaseDate":"2022-08-21T00:00:00.000Z","image":"https://image.tmdb.org/t/p/original/1x1.jpg","url":{"slug":"series/84773/la-casa-del-dragon/seasons/1/episodes/1"}}]}
                  ]
                }
              }}
            }</script>
        """.trimIndent()

        val series = PoseidonHD2BisData.parseSeriesDetail(seriesHtml, "https://www.poseidonhd2.co/serie/84773/la-casa-del-dragon")
        assertNotNull(series)
        assertEquals(2, series!!.episodes.size)
        assertEquals(1, series.episodes[0].season)
        assertEquals(2, series.episodes[1].season)
        assertEquals("2022-08-21T00:00:00.000Z", series.episodes[0].releaseDate)
    }

    @Test
    fun `extractPlayerEmbedUrl extracts target cyberlocker url and ignores ad hosts`() {
        val html = """
            <!DOCTYPE html><html><head></head><body>
            <script>var ad = 'https://doo.lat/pop.js'; var url = 'https://voe.sx/e/sm8v06z32ntn';</script>
            </body></html>
        """.trimIndent()
        assertEquals("https://voe.sx/e/sm8v06z32ntn", PoseidonHD2BisData.extractPlayerEmbedUrl(html))

        val adOnly = """<script>var url = 'https://doo.lat/pop.js';</script>"""
        assertNull(PoseidonHD2BisData.extractPlayerEmbedUrl(adOnly))
    }

    @Test
    fun `PoseidonCrypto pbkdf2Hmac and solveAltchaChallenge match SHA-256 reference vector`() {
        val nonce = PoseidonCrypto.hexToBytes("a1b2c3d4e5f600112233445566778899")
        val salt = PoseidonCrypto.hexToBytes("99887766554433221100ffeeddccbbaa")
        val pw = nonce + byteArrayOf(0, 0, 0, 0)
        val dk0 = PoseidonCrypto.pbkdf2Hmac(pw, salt, 50, 32, "HmacSHA256")
        assertEquals(
            "25a50a79e6407eb6e9cde3e0ecd174e3e02549471b4c253477c44843e81db0e0",
            PoseidonCrypto.bytesToHex(dk0),
        )

        val challengeJson = """
            {
              "parameters":{
                "algorithm":"PBKDF2/SHA-256",
                "nonce":"a1b2c3d4e5f600112233445566778899",
                "salt":"99887766554433221100ffeeddccbbaa",
                "cost":50,
                "keyLength":32,
                "keyPrefix":"00"
              },
              "signature":"sig-test"
            }
        """.trimIndent()

        val encodedPayload = PoseidonCrypto.solveAltchaChallenge(challengeJson, maxCounters = 500)
        assertNotNull(encodedPayload)
        val decodedJson = String(PoseidonCrypto.decodeBase64Flexible(encodedPayload!!), StandardCharsets.UTF_8)
        val tree = PoseidonHD2BisData.jsonMapper.readTree(decodedJson)
        assertEquals(295, tree.get("solution").get("counter").asInt())
        assertEquals(
            "002443d914be3ca3d667130ca2df65d991d3288b2b5d3d4422fc64c06c693ad3",
            tree.get("solution").get("derivedKey").asText(),
        )

        val asyncEncoded = runBlocking {
            PoseidonCrypto.solveAltchaChallengeSuspend(challengeJson, maxCounters = 500)
        }
        assertNotNull(asyncEncoded)
        val asyncTree = PoseidonHD2BisData.jsonMapper.readTree(
            String(PoseidonCrypto.decodeBase64Flexible(asyncEncoded!!), StandardCharsets.UTF_8),
        )
        assertEquals(295, asyncTree.get("solution").get("counter").asInt())
        assertEquals(
            "002443d914be3ca3d667130ca2df65d991d3288b2b5d3d4422fc64c06c693ad3",
            asyncTree.get("solution").get("derivedKey").asText(),
        )
    }

    @Test
    fun `PoseidonCrypto fast SHA-256 PBKDF2 matches reference Mac across salt and password boundaries`() {
        fun referencePbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val block = byteArrayOf(0, 0, 0, 1)
            mac.update(salt)
            mac.update(block)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t.copyOf(keyLen)
        }

        val passwords = listOf(
            ByteArray(20) { (it * 7 + 3).toByte() },
            ByteArray(64) { (it * 13 + 5).toByte() },
            ByteArray(80) { (it * 17 + 1).toByte() },
        )
        val salts = listOf(
            ByteArray(0),
            ByteArray(16) { (it + 11).toByte() },
            ByteArray(52) { (it + 23).toByte() },
            ByteArray(56) { (it + 31).toByte() },
            ByteArray(60) { (it + 41).toByte() },
            ByteArray(72) { (it + 53).toByte() },
        )
        for (pw in passwords) {
            for (salt in salts) {
                for (iters in listOf(1, 5, 50)) {
                    assertEquals(
                        PoseidonCrypto.bytesToHex(referencePbkdf2(pw, salt, iters, 32)),
                        PoseidonCrypto.bytesToHex(PoseidonCrypto.pbkdf2Hmac(pw, salt, iters, 32, "HmacSHA256")),
                    )
                }
            }
        }
        assertEquals(
            PoseidonCrypto.bytesToHex(referencePbkdf2(passwords[0], salts[1], 1000, 16)),
            PoseidonCrypto.bytesToHex(PoseidonCrypto.pbkdf2Hmac(passwords[0], salts[1], 1000, 16, "HmacSHA256")),
        )
    }

    @Test
    fun `PoseidonVoeCrypto decryptF7 decodes obfuscated VOE payload`() {
        val encoded =
            "CQEMnJkDpJ@\$I5o0carxx1^^KTkRoSWfIUOyp3qjGTc6oRk2CScDpRIUHQE7JIk6HIgIoREfHzk6Jy1fFHcyoRETG2kHF2p1BTkpASR2G297FzM3FHcbomufMJ5EAH95pa1zryIYM3WAExj5GSMqqz9jKJk2Aycdpz1ZsGt1MKAIF2MeHKOZnacfGQARJ2I4B1cqoz9jM3yLAJH1CSMqqxIoMJgeJ114A0cqoGk8GmyDF2LmIHgxoREfHzk6pTL0CQEzsIx1KT1WAIfmHIcqsT9XKJkREx9fnyqasGAjG3kMFzq9FIcyrIt1MGH8Iy12EIgyn2goKKt3Fy1gCUkCBIOYMwAIF2EfETkFoSt1KUkMAzI9GKkb"
        val payload = PoseidonVoeCrypto.decryptF7(encoded)
        assertNotNull(payload)
        assertEquals("https://cdn.example/voe/master.m3u8", payload!!.source)
        assertEquals("https://cdn.example/voe/video.mp4", payload.directAccessUrl)
        assertEquals(1, payload.captions?.size)
        assertEquals("Español", payload.captions!!.first().label)
    }

    @Test
    fun `PoseidonVsembedCrypto reproduces exact live vsembed AES-128-CBC key, iv and ciphertext`() {
        assertEquals("1d54a1d53c1d56e1d6811d53c1d6831d", PoseidonVsembedCrypto.generateKeyHex())
        assertEquals("1f5101f5dd1f5111f6aa1f6e126a1767", PoseidonVsembedCrypto.generateIvHex("vsembed.com"))
        assertEquals("2b9ffe2e36e8a25c9c8affe5abaa4698", PoseidonVsembedCrypto.encryptHex("6168w", "vsembed.com"))
        assertEquals("6168w", PoseidonVsembedCrypto.decryptHex("2b9ffe2e36e8a25c9c8affe5abaa4698", "vsembed.com"))
    }

    @Test
    fun `PoseidonVidHideParser prioritizes hls2 before hls4 and hls3`() {
        val packed = """
            <script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('5 0={"4":"/1/2.3","7":"8://9.a/b/c.3","6":"8://d.e/f/g.3"};',18,18,'links|stream|seg|m3u8|hls4|var|hls2|hls3|https|bad|cfd|hls3|master|good|com|hls2|index'.split('|'),0,{}))
            </script>
        """.trimIndent()

        val parsed = PoseidonVidHideParser.parse(packed, "https://callistanise.com/v/abc123")
        assertFalse(parsed.streams.isEmpty())
        assertEquals("hls2", parsed.streams[0].key)
        assertEquals("https://good.com/hls2/index.m3u8", parsed.streams[0].url)
        assertEquals("hls4", parsed.streams[1].key)
        assertEquals("https://callistanise.com/stream/seg.m3u8", parsed.streams[1].url)
        assertEquals("hls3", parsed.streams[2].key)
    }
}
