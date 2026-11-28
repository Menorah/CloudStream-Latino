package com.menorah.sololatino

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SoloLatinoMarkupTest {

    @Test
    fun `url builders handle catalogs pagination search and series normalization`() {
        assertEquals("https://sololatino.net/", SoloLatinoMarkup.buildCatalogUrl(SoloLatinoMarkup.SECTION_HOME_RECENT_EPISODES, 1))
        assertEquals("https://sololatino.net/peliculas?sort=updated", SoloLatinoMarkup.buildCatalogUrl("peliculas?sort=updated", 1))
        assertEquals("https://sololatino.net/peliculas?sort=updated&page=2", SoloLatinoMarkup.buildCatalogUrl("peliculas?sort=updated", 2))
        assertEquals("https://sololatino.net/red/netflix", SoloLatinoMarkup.buildCatalogUrl("red/netflix", 1))
        assertEquals("https://sololatino.net/red/netflix?page=3", SoloLatinoMarkup.buildCatalogUrl("red/netflix", 3))
        assertEquals("https://sololatino.net/buscar?q=street+fighter", SoloLatinoMarkup.buildSearchUrl("  street fighter  ", 1))
        assertEquals("https://sololatino.net/buscar?q=dexter&page=2", SoloLatinoMarkup.buildSearchUrl("dexter", 2))
        assertEquals("https://sololatino.net/api/search/suggest?q=stranger+things", SoloLatinoMarkup.buildQuickSearchUrl("stranger things"))

        val (normEp, parentSeries) = SoloLatinoMarkup.normalizeSeriesOrEpisodeTarget(
            "/serie/dexter-resurreccion/temporada-1/episodio-3",
        )
        assertEquals("https://sololatino.net/serie/dexter-resurreccion/temporada-1/episodio-3", normEp)
        assertEquals("https://sololatino.net/serie/dexter-resurreccion", parentSeries)
        assertEquals(1 to 3, SoloLatinoMarkup.parseEpisodeCoordinatesFromUrl(normEp))

        val (normMovie, movieParent) = SoloLatinoMarkup.normalizeSeriesOrEpisodeTarget("https://sololatino.net/pelicula/street-fighter")
        assertEquals("https://sololatino.net/pelicula/street-fighter", normMovie)
        assertNull(movieParent)
    }

    @Test
    fun `home recent episodes and recently added parse accurately`() {
        val html = """
            <html><body>
            <section>
              <h2>Últimos Episodios</h2>
              <a class="ep-card" href="/serie/from/temporada-4/episodio-2">
                <img alt="From" data-src="https://image.tmdb.org/t/p/w300/from-ep.jpg" src="./home_files/placeholder.jpg" />
                <span>T4 · E2</span>
              </a>
              <a class="ep-card" href="/serie/solo-leveling/temporada-2/episodio-5">
                <img alt="Solo Leveling" src="https://image.tmdb.org/t/p/w300/sl-ep.jpg" />
              </a>
            </section>
            <section>
              <h2>Recién Añadidos</h2>
              <div class="card">
                <a href="https://sololatino.net/pelicula/street-fighter">
                  <div class="card__poster-wrap">
                    <img class="card__poster" alt="Street Fighter" src="https://image.tmdb.org/t/p/w500/sf.jpg" />
                    <span class="badge card__badge badge-movie">Película</span>
                    <span class="card__rating">★ 6.2</span>
                  </div>
                  <div class="card__body">
                    <h3 class="card__title">Street Fighter</h3>
                    <div class="card__meta"><span>2026</span></div>
                  </div>
                </a>
              </div>
              <div class="card">
                <a href="/serie/hotaru-no-yomeiri">
                  <div class="card__poster-wrap">
                    <img class="card__poster" alt="Boda de Luciérnagas" src="https://image.tmdb.org/t/p/w500/hotaru.jpg" />
                    <span class="badge card__badge badge-anime">Anime</span>
                    <span class="card__rating">★ 8.9</span>
                  </div>
                  <div class="card__body">
                    <h3 class="card__title">Boda de Luciérnagas</h3>
                    <span class="card__year">2025</span>
                  </div>
                </a>
              </div>
            </section>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, SoloLatinoMarkup.MAIN_URL)

        val eps = SoloLatinoMarkup.parseHomeRecentEpisodes(doc)
        assertEquals(2, eps.size)
        assertEquals("From (T4 · E2)", eps[0].title)
        assertEquals("https://sololatino.net/serie/from/temporada-4/episodio-2", eps[0].url)
        assertEquals("https://image.tmdb.org/t/p/w300/from-ep.jpg", eps[0].posterUrl)
        assertEquals("Solo Leveling (T2 · E5)", eps[1].title)

        val recent = SoloLatinoMarkup.parseHomeRecentAdded(doc)
        assertEquals(2, recent.size)
        assertEquals("Street Fighter", recent[0].title)
        assertEquals(2026, recent[0].year)
        assertEquals(6.2, recent[0].rating!!, 0.01)
        assertEquals(SoloLatinoMediaKind.MOVIE, recent[0].kind)

        assertEquals("Boda de Luciérnagas", recent[1].title)
        assertEquals(2025, recent[1].year)
        assertEquals(8.9, recent[1].rating!!, 0.01)
        assertEquals(SoloLatinoMediaKind.ANIME, recent[1].kind)
    }

    @Test
    fun `catalog pagination and quickSearch suggest json parse properly`() {
        val catHtml = """
            <html><body>
            <div class="card">
              <a href="/pelicula/dandadan-movie">
                <img class="card__poster" alt="Dandadan: La Película" data-src="https://image.tmdb.org/t/p/w500/dd.jpg" />
                <span class="badge card__badge badge-anime">Anime</span>
                <span class="card__rating">★ 8.4</span>
                <div class="card__body"><p class="card__title">Dandadan: La Película</p><span>2025</span></div>
              </a>
            </div>
            <div class="card">
              <a href="/serie/squid-game-dorama">
                <img class="card__poster" alt="El Juego del Calamar" src="https://image.tmdb.org/t/p/w500/sg.jpg" />
                <span class="badge card__badge badge-dorama">Dorama</span>
                <span class="card__rating">★ 8.1</span>
                <div class="card__body"><p class="card__title">El Juego del Calamar</p><span>2024</span></div>
              </a>
            </div>
            <nav class="pagination">
              <li class="page-item active"><a class="page-link" href="/animes?page=1">1</a></li>
              <li class="page-item"><a class="page-link" href="/animes?page=2">2</a></li>
              <li class="page-item"><a class="page-link" href="/animes?page=2">›</a></li>
            </nav>
            </body></html>
        """.trimIndent()
        val page = SoloLatinoMarkup.parseCatalogPage(Jsoup.parse(catHtml, SoloLatinoMarkup.MAIN_URL), currentPage = 1)
        assertTrue(page.hasNext)
        assertEquals(2, page.items.size)
        assertEquals(SoloLatinoMediaKind.ANIME_MOVIE, page.items[0].kind)
        assertEquals(SoloLatinoMediaKind.DORAMA, page.items[1].kind)

        val lastPage = SoloLatinoMarkup.parseCatalogPage(
            Jsoup.parse(catHtml.replace("""href="/animes?page=2">2""", """href="/animes?page=2">2"""), SoloLatinoMarkup.MAIN_URL),
            currentPage = 2,
        )
        // Page 2 has only links to page=2; "›" is without higher page only if disabled, wait: test disabled pagination
        val disabledPaginationHtml = """
            <nav class="pagination">
              <li class="page-item active"><a class="page-link" href="/animes?page=2">2</a></li>
              <li class="page-item disabled"><span>›</span></li>
            </nav>
        """.trimIndent()
        assertFalse(SoloLatinoMarkup.parseCatalogPage(Jsoup.parse(disabledPaginationHtml), currentPage = 2).hasNext)

        val suggestJson = """
            {
              "results": [
                {"type": "person", "name": "Michael C. Hall", "url": "/persona/123"},
                {"type": "movie", "title": "Street Fighter", "url": "/pelicula/street-fighter", "poster": "https://image.tmdb.org/t/p/w300/sf.jpg", "year": 2026, "rating": 6.2},
                {"type": "tv", "title": "Dexter: Resurrección", "slug": "dexter-resurreccion", "image": "/images/no-poster.jpg", "year": 2025}
              ]
            }
        """.trimIndent()
        val quick = SoloLatinoMarkup.parseQuickSearchJson(suggestJson)
        assertEquals(2, quick.size)
        assertEquals("Street Fighter", quick[0].title)
        assertEquals(SoloLatinoMediaKind.MOVIE, quick[0].kind)
        assertEquals("https://image.tmdb.org/t/p/w500/sf.jpg", quick[0].posterUrl)
        assertEquals("Dexter: Resurrección", quick[1].title)
        assertEquals("https://sololatino.net/serie/dexter-resurreccion", quick[1].url)
        assertEquals(SoloLatinoMediaKind.SERIES, quick[1].kind)
        assertNull(quick[1].posterUrl)
        assertNotNull(lastPage)
    }

    @Test
    fun `movie and multiseason series detail plus player buttons parse accurately`() {
        val movieHtml = """
            <html>
            <head>
              <link rel="canonical" href="https://sololatino.net/pelicula/street-fighter" />
              <meta name="csrf-token" content="csrf-abc-123" />
              <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@type": "Movie",
                  "name": "Street Fighter",
                  "description": "Ryu y Ken luchan por el torneo mundial.",
                  "image": "https://image.tmdb.org/t/p/original/sf-poster.jpg",
                  "datePublished": "2026-10-16",
                  "duration": "PT1H52M",
                  "genre": ["Acción", "Aventura"],
                  "aggregateRating": {"@type": "AggregateRating", "ratingValue": 6.4}
                }
              </script>
            </head>
            <body>
              <div class="detail-hero">
                <div class="detail-hero__bg" style="background-image:url('https://image.tmdb.org/t/p/w780/sf-bg.jpg')"></div>
                <img class="detail-poster" src="./street-fighter_files/local-cached.jpg" alt="Street Fighter" />
                <h1>Street Fighter</h1>
                <span>2026</span>
                <span>1h 52m</span>
                <div class="rating-badge">IMDb <span class="rating-badge__val">6.2</span></div>
                <a href="/genero/accion" class="genre-chip">Acción</a>
                <button data-trailer="dQw4w9WgXcQ">Ver Tráiler</button>
              </div>
              <div class="cast-card">
                <img src="https://image.tmdb.org/t/p/w185/actor1.jpg" alt="Andrew Koji" />
                <p class="font-semibold">Andrew Koji</p>
                <p class="text-xs">Ryu</p>
              </div>
              <div id="player-frame" data-lazy-embed="lazy-tok-999"></div>
              <div data-lang-tabs>
                <button data-lang-tab="mx">Latino</button>
                <button data-lang-tab="es">Español</button>
              </div>
              <div data-lang-group="mx">
                <button class="server-btn" data-player-token="tok-1">⭐ Principal</button>
                <button class="server-btn" data-player-token="tok-2">Embed69</button>
              </div>
              <div data-lang-group="es">
                <button class="server-btn" data-player-token="tok-3" data-resolved-url="https://voe.sx/e/pre123" data-resolved-type="iframe">Voe</button>
              </div>
            </body>
            </html>
        """.trimIndent()
        val mDoc = Jsoup.parse(movieHtml, SoloLatinoMarkup.MAIN_URL)
        val detail = SoloLatinoMarkup.parseDetail(mDoc, "https://sololatino.net/pelicula/street-fighter")!!
        assertFalse(detail.isSeriesLike)
        assertEquals("Street Fighter", detail.title)
        assertEquals(2026, detail.year)
        assertEquals(112, detail.durationMinutes)
        assertEquals(6.2, detail.rating!!, 0.01)
        assertEquals("https://image.tmdb.org/t/p/w500/sf-poster.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/sf-bg.jpg", detail.backgroundPosterUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", detail.trailerUrl)
        assertEquals(listOf("Acción", "Aventura"), detail.genres)
        assertEquals(1, detail.actors.size)
        assertEquals("Andrew Koji", detail.actors[0].name)
        assertEquals("Ryu", detail.actors[0].role)

        val player = SoloLatinoMarkup.parsePlayerSection(mDoc)
        assertEquals("csrf-abc-123", player.csrfToken)
        assertEquals("lazy-tok-999", player.lazyEmbedToken)
        assertEquals(3, player.buttons.size)
        assertEquals("Latino", player.buttons[0].langLabel)
        assertEquals("⭐ Principal", player.buttons[0].label)
        assertEquals("tok-1", player.buttons[0].token)
        assertEquals("Español", player.buttons[2].langLabel)
        assertEquals("https://voe.sx/e/pre123", player.buttons[2].resolvedUrl)

        val seriesHtml = """
            <html><body>
              <div class="detail-hero">
                <h1>Dexter: Resurrección</h1>
                <span class="badge">En emisión</span>
              </div>
              <div data-season-panel="1">
                <a class="ep-item" href="/serie/dexter-resurreccion/temporada-1/episodio-2">
                  <img class="ep-thumb" src="https://image.tmdb.org/t/p/w300/ep2.jpg" />
                  <span class="ep-num">E2</span>
                  <p class="text-sm font-semibold">E2 · Camera Shy</p>
                  <p class="line-clamp-2">Dexter sigue una pista en Nueva York.</p>
                  <span>18/07/2025</span>
                </a>
                <a class="ep-item" href="/serie/dexter-resurreccion/temporada-1/episodio-1">
                  <img class="ep-thumb" src="https://image.tmdb.org/t/p/w300/ep1.jpg" />
                  <span class="ep-num">E1</span>
                  <p class="text-sm font-semibold">Despertar</p>
                  <span>11/07/2025</span>
                </a>
              </div>
              <div data-season-panel="2">
                <a class="ep-item" href="/serie/dexter-resurreccion/temporada-2/episodio-1">
                  <span class="ep-num">1</span>
                  <p class="text-sm font-semibold">Nueva cacería</p>
                  <span>01/08/2026</span>
                </a>
              </div>
            </body></html>
        """.trimIndent()
        val sDetail = SoloLatinoMarkup.parseDetail(Jsoup.parse(seriesHtml, SoloLatinoMarkup.MAIN_URL), "https://sololatino.net/serie/dexter-resurreccion")!!
        assertTrue(sDetail.isSeriesLike)
        assertEquals(true, sDetail.statusOngoing)
        assertEquals(3, sDetail.episodes.size)
        assertEquals(1 to 1, sDetail.episodes[0].season to sDetail.episodes[0].episode)
        assertEquals("Despertar", sDetail.episodes[0].title)
        assertEquals("2025-07-11", sDetail.episodes[0].releaseDate)
        assertEquals("Camera Shy", sDetail.episodes[1].title)
        assertEquals("2025-07-18", sDetail.episodes[1].releaseDate)
        assertEquals("Dexter sigue una pista en Nueva York.", sDetail.episodes[1].description)
        assertEquals(2 to 1, sDetail.episodes[2].season to sDetail.episodes[2].episode)
    }

    @Test
    fun `render shield header and embed69 aes256cbc decryption match exact protocol`() {
        val nowMs = 1_760_000_040_000L
        val offsetMs = 120_000L
        val token = "sample_player_token"
        val expectedSlot = Math.floorDiv(nowMs + offsetMs, 60_000L)
        val raw = "$token:$expectedSlot:sololatino_render_shield_2026"
        val expectedHex = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        assertEquals(expectedHex, SoloLatinoMarkup.computeRenderShieldHeader(token, offsetMs, nowMs))

        val parsedDate = SoloLatinoMarkup.parseHttpDateMillis("Wed, 25 Nov 2026 12:30:00 GMT")
        assertNotNull(parsedDate)

        // Generate real AES-256-CBC Embed69 payload and verify decryption + sanitization
        val challenge = "ch_test_99"
        val salt = "salt_sl_2026"
        val key = MessageDigest.getInstance("SHA-256")
            .digest((challenge + salt).toByteArray(StandardCharsets.UTF_8))
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

        fun encryptLink(url: String): String {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val ct = c.doFinal(url.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(iv + ct)
        }

        val encVoe = encryptLink("`https://voe.sx/e/test123`\u0000")
        val encMorencius = encryptLink("https://morencius.com/embed/abc789")
        val embed69Html = """
            <script>
              let LINK_CHALLENGE = "$challenge";
              let LINK_SALT = "$salt";
              let dataLink = [
                {
                  "video_language": "LAT",
                  "sortedEmbeds": [
                    {"servername": "voe", "link": "$encVoe"},
                    {"servername": "vidhide", "link": "$encMorencius"}
                  ]
                }
              ];
            </script>
        """.trimIndent()
        val mirrors = SoloLatinoMarkup.decryptEmbed69Page(embed69Html)
        assertEquals(2, mirrors.size)
        assertEquals("Latino", mirrors[0].langLabel)
        assertEquals("https://voe.sx/e/test123", mirrors[0].url)
        assertEquals("https://morencius.com/embed/abc789", mirrors[1].url)
    }

    @Test
    fun `pelisserieshoy init fnv1a32 and s php responses parse cleanly`() {
        val html = """
            <script>
              var _t = 'tok_psh_01';
              var _qc = 'nonce_42';
              var _qk = ['cc', 'aa', 'dd', 'bb'];
              var ao = 'https://sololatino.net';
            </script>
        """.trimIndent()
        val init = SoloLatinoMarkup.parsePelisSeriesHoyInit(html, "https://player.pelisserieshoy.com/f/tt1190634-5x08?et=xyz")!!
        assertEquals("tok_psh_01", init.token)
        assertEquals("aabbccdd", init.qv)
        assertEquals(SoloLatinoMarkup.fnv1a32Hex("aabbccdd|clk|nonce_42"), init.qt)
        assertEquals("https://player.pelisserieshoy.com", init.originUrl)

        val step1Json = """
            {
              "langs_s": {
                "LAT": [["Principal", "srv_lat_1"]],
                "ENG": [["Sub HD", "srv_eng_1"]]
              },
              "s": [["Principal", "srv_lat_1"]]
            }
        """.trimIndent()
        val servers = SoloLatinoMarkup.parsePelisSeriesHoyStep1(step1Json)
        assertEquals(2, servers.size)
        assertEquals("Latino", servers[0].langLabel)
        assertEquals("Subtitulado", servers[1].langLabel)

        val step2Hls = SoloLatinoMarkup.parsePelisSeriesHoyStep2("""{"u":"https://cdn.example.com/master.m3u8","sig":"sig_abc=1","ctx":"c123"}""")!!
        assertEquals(
            "https://player.pelisserieshoy.com/p.php?url=https%3A%2F%2Fcdn.example.com%2Fmaster.m3u8&sig=sig_abc%3D1&ctx=c123",
            SoloLatinoMarkup.buildPelisSeriesHoyHlsProxyUrl("https://player.pelisserieshoy.com", step2Hls.u!!, step2Hls.sig!!, step2Hls.ctx),
        )
        // JS _qh() returns unpadded (h>>>0).toString(16) (no zero-padding)
        assertEquals("de8d5a3", SoloLatinoMarkup.fnv1a32Hex("68"))
        val pshEmptyAo = """
            <script>
              var _t = 'tok2'; var _qc = 'qc2'; var _qk = ['c','a','d','b'];
              var _ao = ''; var _qsrc = 'sl';
            </script>
        """.trimIndent()
        val initEmptyAo = SoloLatinoMarkup.parsePelisSeriesHoyInit(
            pshEmptyAo,
            "https://player.pelisserieshoy.com/f/tt123",
            refererUrl = "https://sololatino.co/pelicula/algo",
        )!!
        assertEquals("https://sololatino.co", initEmptyAo.ao)
        assertEquals("sl", initEmptyAo.qsrc)
        val mp4Step2 = SoloLatinoMarkup.parsePelisSeriesHoyStep2("""{"type":"mp4","u":"https://cdn.example.com/video.mp4"}""")!!
        assertEquals("mp4", mp4Step2.type)
        assertEquals("https://cdn.example.com/video.mp4", mp4Step2.u)
    }

    @Test
    fun `host extractors crypto and cloudflare detectors work offline`() = runBlocking {
        // EarnVids / VidHide links parsing prioritizes hls2 over hls3
        val vidHideHtml = """
            <script>
              var links = {"hls3":"https://cdn.morencius.com/bad/index.m3u8","hls2":"https://cdn.morencius.com/good/master.m3u8"};
            </script>
        """.trimIndent()
        val vhParsed = SoloLatinoVidHideParser.parse(vidHideHtml, "https://morencius.com/embed/xyz")
        assertEquals(listOf("hls2", "hls3"), vhParsed.streams.map { it.first })
        assertEquals("https://cdn.morencius.com/good/master.m3u8", vhParsed.streams[0].second)

        // Vsembed AES hex roundtrip
        val enc = SoloLatinoVsembedCrypto.encryptHex("s405k8cf50j9", "vsembed.com")
        assertEquals("s405k8cf50j9", SoloLatinoVsembedCrypto.decryptHex(enc, "vsembed.com"))

        // Byse ECDSA P-256 + AES-GCM roundtrip
        val kp = SoloLatinoByseCrypto.ecKeyPairP256()!!
        val sig = SoloLatinoHostHttp.decodeBase64Flexible(SoloLatinoByseCrypto.signNonce("nonce-1", kp)!!)
        assertEquals(64, sig.size)
        val k1 = ByteArray(16) { (it + 1).toByte() }
        val k2 = ByteArray(16) { (it + 17).toByte() }
        val iv = ByteArray(12) { (it + 5).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k1 + k2, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal("""{"sources":[{"url":"https://cdn.byse.sx/v.m3u8","height":1080}]}""".toByteArray(StandardCharsets.UTF_8))
        val dec = SoloLatinoByseCrypto.decryptPlayback(
            SoloLatinoByseCrypto.base64UrlNoPad(iv),
            SoloLatinoByseCrypto.base64UrlNoPad(ct),
            listOf(Base64.getEncoder().encodeToString(k1), Base64.getEncoder().encodeToString(k2)),
            null,
        )!!
        assertEquals("https://cdn.byse.sx/v.m3u8", dec.get("sources").get(0).get("url").asText())

        // Google Drive file id + Cloudflare challenge detection
        assertEquals("1AbCDefGhIjKlMnOpQrStUvWxYz", SoloLatinoMarkup.extractGDriveFileId("https://drive.google.com/file/d/1AbCDefGhIjKlMnOpQrStUvWxYz/view"))
        assertTrue(SoloLatinoSessionHelper.looksLikeCloudflareChallenge(403, "<title>Just a moment...</title><script>window._cf_chl_opt={};</script>"))
        assertFalse(SoloLatinoSessionHelper.looksLikeCloudflareChallenge(200, "<html><body><div class='card'></div></body></html>"))
    }

    @Test
    fun `tailwind posters srcset svg preservation and crash-hardened crypto helpers work`() = runBlocking {
        // Real SoloLatino detail page markup without .detail-poster, JSON-LD, or og:image:
        // must pick the Tailwind w-44 rounded-2xl poster, not the .max-h-16 SVG logo, and preserve w1280 backdrop
        val tailwindDetailHtml = """
            <html><body>
              <main class="flex-1 pt-16">
                <div class="detail-hero">
                  <div class="detail-hero__bg" style="background-image:url('https://image.tmdb.org/t/p/w1280/hero-bg.jpg')"></div>
                  <div class="relative z-10">
                    <div class="flex-shrink-0 hidden sm:block">
                      <img src="https://image.tmdb.org/t/p/w500/real-tailwind-poster.jpg" alt="Street Fighter" class="w-44 md:w-52 rounded-2xl shadow-2xl" />
                    </div>
                    <div class="flex-1">
                      <img src="https://image.tmdb.org/t/p/original/title-logo.svg" alt="Street Fighter" class="max-h-16 md:max-h-20 max-w-xs mb-3 object-contain" />
                      <h1>Street Fighter</h1>
                    </div>
                  </div>
                </div>
              </main>
            </body></html>
        """.trimIndent()
        val detail = SoloLatinoMarkup.parseDetail(Jsoup.parse(tailwindDetailHtml, SoloLatinoMarkup.MAIN_URL), "https://sololatino.net/pelicula/street-fighter")!!
        assertEquals("https://image.tmdb.org/t/p/w500/real-tailwind-poster.jpg", detail.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/hero-bg.jpg", detail.backgroundPosterUrl)

        // Backdrop fallback for posterUrl when no poster/og:image exists must NOT rewrite w1280 -> w500
        val backdropOnlyHtml = """
            <html><body>
              <div class="detail-hero">
                <div class="detail-hero__bg" style="background-image:url('https://image.tmdb.org/t/p/w1280/only-backdrop.jpg')"></div>
                <h1>Sin Póster</h1>
              </div>
            </body></html>
        """.trimIndent()
        val bdDetail = SoloLatinoMarkup.parseDetail(Jsoup.parse(backdropOnlyHtml, SoloLatinoMarkup.MAIN_URL), "https://sololatino.net/pelicula/sin-poster")!!
        assertEquals("https://image.tmdb.org/t/p/w1280/only-backdrop.jpg", bdDetail.posterUrl)

        // Episode stills (w400 / w300) must be preserved without rewriting to w500; SVG must keep original; srcset works
        assertEquals(
            "https://image.tmdb.org/t/p/original/logo.svg",
            SoloLatinoMarkup.cleanImageUrl("https://image.tmdb.org/t/p/original/logo.svg", SoloLatinoMarkup.MAIN_URL, "w500"),
        )
        val epHtml = """
            <html><body>
              <section>
                <h2>Últimos Episodios</h2>
                <a class="ep-card" href="/serie/yellowjackets/temporada-4/episodio-2">
                  <img alt="Yellowjackets" src="https://image.tmdb.org/t/p/w400/yj-still.jpg" />
                </a>
                <a class="ep-card" href="/serie/from/temporada-4/episodio-3">
                  <img alt="From" srcset="https://image.tmdb.org/t/p/w300/from-300.jpg 300w, https://image.tmdb.org/t/p/w400/from-400.jpg 400w" />
                </a>
              </section>
            </body></html>
        """.trimIndent()
        val epCards = SoloLatinoMarkup.parseHomeRecentEpisodes(Jsoup.parse(epHtml, SoloLatinoMarkup.MAIN_URL))
        assertEquals(2, epCards.size)
        assertEquals("https://image.tmdb.org/t/p/w400/yj-still.jpg", epCards[0].posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w400/from-400.jpg", epCards[1].posterUrl)

        // Embed69 tolerates malformed ciphertext entries without crashing and still returns valid mirrors
        val ch = "ch_resilience"
        val sl = "sl_resilience"
        val key = MessageDigest.getInstance("SHA-256").digest((ch + sl).toByteArray(StandardCharsets.UTF_8))
        val iv = ByteArray(16) { (it + 3).toByte() }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val validCt = Base64.getEncoder().encodeToString(iv + c.doFinal("https://voe.sx/e/ok123".toByteArray(StandardCharsets.UTF_8)))
        val badCt = Base64.getEncoder().encodeToString(ByteArray(32) { 0x7f.toByte() })
        val resilientE69 = """
            <script>
              let LINK_CHALLENGE = "$ch";
              let LINK_SALT = "$sl";
              let dataLink = [{"video_language":"LAT","sortedEmbeds":[{"servername":"broken","link":"$badCt"},{"servername":"voe","link":"$validCt"}]}];
            </script>
        """.trimIndent()
        val recovered = SoloLatinoMarkup.decryptEmbed69Page(resilientE69)
        assertEquals(1, recovered.size)
        assertEquals("https://voe.sx/e/ok123", recovered[0].url)

        // Zero-allocation Byse PoW solver finds a valid solution
        val powSol = SoloLatinoByseCrypto.solvePow("unit-test-nonce", 4, maxAttempts = 5_000)
        assertNotNull(powSol)

        // ALTCHA v1 (SHA-256) and ALTCHA v2 (PBKDF2/SHA-256) solvers return valid JSON tokens
        val targetNumber = 12L
        val v1Salt = "v1salt|"
        val v1Challenge = MessageDigest.getInstance("SHA-256")
            .digest("$v1Salt$targetNumber".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val v1Solved = SoloLatinoVoeCrypto.solveAltchaSuspend(
            """{"algorithm":"SHA-256","salt":"$v1Salt","challenge":"$v1Challenge","maxnumber":50}""",
        )
        assertNotNull(v1Solved)
        val v1Json = SoloLatinoMarkup.jsonMapper.readTree(String(Base64.getDecoder().decode(v1Solved), StandardCharsets.UTF_8))
        assertEquals(targetNumber, v1Json.get("number").asLong())

        val nonceHex = "0102030405060708"
        val saltHex = "a1a2a3a4a5a6a7a8"
        val nonceBytes = SoloLatinoHostHttp.hexToBytes(nonceHex)
        val saltBytes = SoloLatinoHostHttp.hexToBytes(saltHex)
        val pass5 = nonceBytes + byteArrayOf(0, 0, 0, 5)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pass5, "HmacSHA256")) }
        var u = mac.doFinal(saltBytes + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (iter in 2..600) {
            u = mac.doFinal(u)
            for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        val fullHex = SoloLatinoHostHttp.bytesToHex(t.copyOf(16))
        val prefix4 = fullHex.take(4)
        val v2Solved = SoloLatinoVoeCrypto.solveAltchaSuspend(
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":600,"keyLength":16,"keyPrefix":"$prefix4","nonce":"$nonceHex","salt":"$saltHex"}}""",
            maxCounters = 20,
        )
        assertNotNull(v2Solved)
        val v2Json = SoloLatinoMarkup.jsonMapper.readTree(String(Base64.getDecoder().decode(v2Solved), StandardCharsets.UTF_8))
        val solNode = v2Json.get("solution")
        assertTrue(solNode.get("derivedKey").asText().startsWith(prefix4))
        assertTrue(solNode.get("counter").asInt() in 0..5)

        // Session guard helpers: cdnjs script tags must NOT false-positive as CF challenges; CF 1020 and Chromium error pages must be caught
        val normalHtmlWithCdn = "<html><head><script src=\"https://cdnjs.cloudflare.com/ajax/libs/video.js/8.0.0/video.min.js\"></script></head><body>OK</body></html>"
        assertFalse(SoloLatinoSessionHelper.looksLikeCloudflareChallenge(200, normalHtmlWithCdn))
        assertFalse(SoloLatinoSessionHelper.looksLikeCloudflareAccessDenied(200, normalHtmlWithCdn))
        assertFalse(SoloLatinoSessionHelper.isChromiumErrorPage(normalHtmlWithCdn))

        val cf1020 = "<html><title>Access denied | embed69.org used Cloudflare to restrict access</title><body>Error code: 1020</body></html>"
        assertTrue(SoloLatinoSessionHelper.looksLikeCloudflareAccessDenied(200, cf1020))
        assertTrue(SoloLatinoSessionHelper.looksLikeCloudflareAccessDenied(403, cf1020))
        assertTrue(SoloLatinoSessionHelper.looksLikeCloudflareChallenge(403, cf1020))

        val chromiumErr = "<html><head><title>Webpage not available</title></head><!-- Upside down Android --></html>"
        assertTrue(SoloLatinoSessionHelper.isChromiumErrorPage(chromiumErr))
    }

    @Test
    fun `v4 sanctum exclusive csrf, production psh const _t, and high-cost altcha v2 pass`() = runBlocking {
        // 1. Laravel Sanctum CSRF rule: never emit X-CSRF-TOKEN when XSRF-TOKEN exists
        assertEquals(
            mapOf("X-XSRF-TOKEN" to "rotated-xsrf"),
            SoloLatinoSessionHelper.buildSanctumCsrfHeaders("rotated-xsrf", "stale-meta-csrf"),
        )
        assertEquals(
            mapOf("X-CSRF-TOKEN" to "meta-only"),
            SoloLatinoSessionHelper.buildSanctumCsrfHeaders("", "meta-only"),
        )
        assertTrue(SoloLatinoSessionHelper.buildSanctumCsrfHeaders(" ", null).isEmpty())

        // Standard User-Agent header casing
        val hdrs = SoloLatinoSessionHelper.buildBrowserHeaders("CustomUA/1.0")
        assertEquals("CustomUA/1.0", hdrs["User-Agent"])

        // 2. Real production PelisSeriesHoy markup (const _t + _qk + _qc + dynamic _ao)
        val prodPshHtml = """
            <script>
              var _qc="tt1190634-5x08";
              var _qk=["LjIzMi4xNjN8","dHQxMTkwNjM0","MTc5NTg0MTkyOA.96e9ee88bd1548337d074a43","LTV4MDh8MTgx"];
              var _qsrc="";
              var _ao='';
              const _t = '3dcf189b8329390d8fab86ebeae50198';
            </script>
        """.trimIndent()
        val pshInit = SoloLatinoMarkup.parsePelisSeriesHoyInit(
            prodPshHtml,
            "https://player.pelisserieshoy.com/f/tt1190634-5x08?et=abc",
            refererUrl = "https://sololatino.net/serie/the-boys/temporada-5/episodio-8",
        )!!
        assertEquals("3dcf189b8329390d8fab86ebeae50198", pshInit.token)
        assertEquals("dHQxMTkwNjM0LTV4MDh8MTgxLjIzMi4xNjN8MTc5NTg0MTkyOA.96e9ee88bd1548337d074a43", pshInit.qv)
        assertEquals("https://sololatino.net", pshInit.ao)
        assertEquals(SoloLatinoMarkup.fnv1a32Hex("${pshInit.qv}|clk|tt1190634-5x08"), pshInit.qt)

        // busy / no_click / retry_once payloads do not produce false URL resolutions
        assertNull(SoloLatinoMarkup.parsePelisSeriesHoyStep2("""{"error":"busy","retry":6}"""))
        assertNull(SoloLatinoMarkup.parsePelisSeriesHoyStep2("""{"type":"error","msg":"no_click"}"""))
        assertNull(SoloLatinoMarkup.parsePelisSeriesHoyStep2("""{"error":"retry_once"}"""))

        // 3. ALTCHA v2 PBKDF2/SHA-256 at production cost=10000 solves cleanly within timeout
        val nonceHex = "1020304050607080"
        val saltHex = "a0b0c0d0e0f00102"
        val nonceBytes = SoloLatinoHostHttp.hexToBytes(nonceHex)
        val saltBytes = SoloLatinoHostHttp.hexToBytes(saltHex)
        val targetCounter = 7
        val pass = nonceBytes + byteArrayOf(0, 0, 0, targetCounter.toByte())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pass, "HmacSHA256")) }
        var u = mac.doFinal(saltBytes + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (iter in 2..10_000) {
            u = mac.doFinal(u)
            for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        val prefix = SoloLatinoHostHttp.bytesToHex(t.copyOf(32)).take(4)
        val solved = SoloLatinoVoeCrypto.solveAltchaSuspend(
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":10000,"keyLength":32,"keyPrefix":"$prefix","nonce":"$nonceHex","salt":"$saltHex"}}""",
            maxCounters = 100,
        )
        assertNotNull(solved)
        val solvedJson = SoloLatinoMarkup.jsonMapper.readTree(String(Base64.getDecoder().decode(solved), StandardCharsets.UTF_8))
        assertTrue(solvedJson.get("solution").get("derivedKey").asText().startsWith(prefix))
    }
}
