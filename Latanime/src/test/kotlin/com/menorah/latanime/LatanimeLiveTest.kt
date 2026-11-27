package com.menorah.latanime

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.interfaces.ECPublicKey
import java.util.concurrent.TimeUnit

class LatanimeLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Before
    fun checkEnabled() {
        val enabled = System.getProperty("latanime.live") == "true" || System.getenv("LATANIME_LIVE") == "true"
        assumeTrue("Set LATANIME_LIVE=true to run live Latanime tests", enabled)
    }

    private fun fetchDoc(url: String, referer: String = "https://latanime.org/"): Pair<String, org.jsoup.nodes.Document> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", LatanimeHttpDefaults.UA)
            .header("Referer", referer)
            .header("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { res ->
            assertTrue("HTTP ${res.code} for $url", res.isSuccessful)
            val finalUrl = res.request.url.toString()
            return finalUrl to Jsoup.parse(res.body.string(), finalUrl)
        }
    }

    @Test
    fun `live home catalogs calendar and pagination parse cleanly`() {
        val (_, homeDoc) = fetchDoc("https://latanime.org/")
        val homeEps = LatanimeMarkup.parseHomeRecentEpisodes(homeDoc)
        assertTrue("Home recent episodes non-empty", homeEps.isNotEmpty())
        println("LIVE_HOME_EPS: count=${homeEps.size}, first=${homeEps.first().title} ep=${homeEps.first().episodeNumber}")

        val (_, calDoc) = fetchDoc("https://latanime.org/calendario")
        val calCards = LatanimeMarkup.parseCalendarCards(calDoc)
        assertTrue("Calendar non-empty", calCards.isNotEmpty())
        println("LIVE_CALENDAR: count=${calCards.size}, first=${calCards.first().title}")

        val filters = listOf(
            "Audio Latino" to "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=latino",
            "Peliculas Latino" to "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=Pel%C3%ADcula%20Latino",
            "En Emision" to "https://latanime.org/emision",
            "Castellano" to "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=castellano",
            "Peliculas" to "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=Pel%C3%ADcula",
            "OVAs" to "https://latanime.org/animes?fecha=false&genero=false&letra=false&categoria=ova",
        )
        for ((label, url) in filters) {
            val (_, doc) = fetchDoc(url)
            val page = LatanimeMarkup.parseCatalogCards(doc, currentPage = 1)
            assertTrue("Catalog $label non-empty", page.items.isNotEmpty())
            assertTrue("No capblank placeholders in $label", page.items.none { it.posterUrl?.contains("capblank") == true })
            println("LIVE_CATALOG[$label]: count=${page.items.size}, hasNext=${page.hasNext}, maxPage=${page.maxPage}, firstBadge=${page.items.first().badge}, firstTitle=${page.items.first().title}")
        }
    }

    @Test
    fun `live details handle series movies collection and ovas`() {
        val (opUrl, opDoc) = fetchDoc("https://latanime.org/anime/one-piece-latino")
        val op = LatanimeMarkup.parseAnimeDetail(opDoc, opUrl)!!
        assertEquals(LatanimeShowStatus.ONGOING, op.status)
        assertTrue("One Piece has 800+ episodes, got ${op.episodes.size}", op.episodes.size >= 800)
        assertTrue(op.isDub)
        println("LIVE_DETAIL[OnePiece]: eps=${op.episodes.size}, status=${op.status}, poster=${op.posterUrl}")

        val (ovUrl, ovDoc) = fetchDoc("https://latanime.org/anime/overlord-the-sacred-kingdom")
        val ov = LatanimeMarkup.parseAnimeDetail(ovDoc, ovUrl, cachedKind = LatanimeCardKind.MOVIE)!!
        assertEquals(LatanimeCardKind.MOVIE, ov.kind)
        assertEquals(1, ov.episodes.size)
        assertEquals(2024, ov.year)
        println("LIVE_DETAIL[OverlordMovie]: kind=${ov.kind}, year=${ov.year}, ep=${ov.episodes.first().url}")

        val (sbUrl, sbDoc) = fetchDoc("https://latanime.org/anime/seishun-buta-yarou-peliculas")
        val sb = LatanimeMarkup.parseAnimeDetail(sbDoc, sbUrl, cachedKind = LatanimeCardKind.MOVIE)!!
        assertEquals(LatanimeCardKind.ANIME, sb.kind)
        assertEquals(3, sb.episodes.size)
        println("LIVE_DETAIL[SeishunMovies]: kind=${sb.kind}, eps=${sb.episodes.size}")

        val (urUrl, urDoc) = fetchDoc("https://latanime.org/anime/urusei-yatsura-ova")
        val ur = LatanimeMarkup.parseAnimeDetail(urDoc, urUrl, cachedKind = LatanimeCardKind.OVA)!!
        assertEquals(LatanimeCardKind.OVA, ur.kind)
        assertEquals(12, ur.episodes.size)
        println("LIVE_DETAIL[UruseiOVA]: kind=${ur.kind}, eps=${ur.episodes.size}")
    }

    @Test
    fun `live letter search finds titles without cloudflare`() {
        val (_, doc) = fetchDoc(LatanimeMarkup.buildLetterFilterUrl("D", 1))
        val cards = LatanimeMarkup.parseCatalogCards(doc, currentPage = 1).items
        val ranked = LatanimeMarkup.filterAndRankCards(cards, "dragon ball")
        assertTrue("Letter D search finds Dragon Ball entries", ranked.isNotEmpty())
        println("LIVE_LETTER_SEARCH[dragon ball]: count=${ranked.size}, first=${ranked.first().title}")
    }

    @Test
    fun `live episode hosts resolve playable urls end to end`() {
        val (epUrl, epDoc) = fetchDoc("https://latanime.org/ver/terror-en-tokio-castellano-episodio-5")
        val ep = LatanimeMarkup.parseEpisodePage(epDoc, epUrl)
        assertTrue("Episode has players", ep.players.isNotEmpty())
        println("LIVE_EP_PLAYERS: ${ep.players.map { "${it.label}=${it.url}" }}")

        // 1. Verify Byse live via /api/videos/access/{challenge,attest} + playback AES-GCM
        val byse = ep.players.firstOrNull { it.url.contains("byse", ignoreCase = true) }
        assertNotNull("Expected Byse player", byse)
        val byseCode = LatanimeHttpDefaults.fileCodeFromPath(byse!!.url)
        val byseBase = LatanimeHttpDefaults.hostBaseUrl(byse.url, "https://bysewihe.com")
        val detailsTxt = client.newCall(
            Request.Builder().url("$byseBase/api/videos/$byseCode/embed/details")
                .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                .header("Referer", byse.url)
                .build()
        ).execute().use { r ->
            assertTrue("Byse details ${r.code}", r.isSuccessful)
            r.body.string()
        }
        val frameUrl = LatanimeHttpDefaults.JSON.readTree(detailsTxt).path("embed_frame_url").asText()
        assertTrue("Byse frameUrl present: $frameUrl", frameUrl.startsWith("http"))
        val frameBase = LatanimeHttpDefaults.hostBaseUrl(frameUrl, byseBase)
        val frameCode = LatanimeHttpDefaults.fileCodeFromPath(frameUrl).ifBlank { byseCode }
        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val chJson = LatanimeHttpDefaults.JSON.readTree(
            client.newCall(
                Request.Builder().url("$frameBase/api/videos/access/challenge")
                    .post("{}".toRequestBody(jsonMedia))
                    .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                    .header("Origin", frameBase)
                    .header("Referer", frameUrl)
                    .build()
            ).execute().use { r ->
                assertTrue("Byse challenge ${r.code}", r.isSuccessful)
                r.body.string()
            }
        )
        val kp = LatanimeByseCrypto.ecKeyPairP256()
        val chalNonce = chJson.path("nonce").asText()
        val attestPayload = mapOf(
            "challenge_id" to chJson.path("challenge_id").asText(),
            "nonce" to chalNonce,
            "signature" to LatanimeByseCrypto.signChallengeNonce(chalNonce, kp),
            "public_key" to LatanimeByseCrypto.toJwkPublicKey(kp.public as ECPublicKey),
            "client" to mapOf(
                "user_agent" to LatanimeHttpDefaults.MOBILE_UA,
                "platform" to "Linux armv8l",
                "languages" to listOf("es-419", "es"),
                "timezone" to "America/Mexico_City",
            ),
            "storage" to emptyMap<String, Any>(),
            "attributes" to mapOf("entropy" to "low"),
        )
        val attestJson = LatanimeHttpDefaults.JSON.readTree(
            client.newCall(
                Request.Builder().url("$frameBase/api/videos/access/attest")
                    .post(LatanimeHttpDefaults.JSON.writeValueAsString(attestPayload).toRequestBody(jsonMedia))
                    .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                    .header("Origin", frameBase)
                    .header("Referer", frameUrl)
                    .build()
            ).execute().use { r ->
                assertTrue("Byse attest ${r.code}", r.isSuccessful)
                r.body.string()
            }
        )
        val fp = mapOf(
            "viewer_id" to attestJson.path("viewer_id").asText(),
            "device_id" to attestJson.path("device_id").asText(),
            "token" to attestJson.path("token").asText(),
            "confidence" to attestJson.path("confidence").asDouble(0.8),
        )
        val pbRes = client.newCall(
            Request.Builder().url("$frameBase/api/videos/$frameCode/playback")
                .post(LatanimeHttpDefaults.JSON.writeValueAsString(mapOf("fingerprint" to fp)).toRequestBody(jsonMedia))
                .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                .header("Origin", frameBase)
                .header("Referer", frameUrl)
                .build()
        ).execute()
        val pbBody = pbRes.body.string()
        val pbCode = pbRes.code
        pbRes.close()
        val finalPbJson = if (pbCode == 428 || pbBody.contains("captcha_required")) {
            val capNode = LatanimeHttpDefaults.JSON.readTree(
                client.newCall(
                    Request.Builder().url("$frameBase/api/videos/$frameCode/captcha")
                        .post(LatanimeHttpDefaults.JSON.writeValueAsString(mapOf("fingerprint" to fp)).toRequestBody(jsonMedia))
                        .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                        .header("Origin", frameBase)
                        .header("Referer", frameUrl)
                        .build()
                ).execute().use { it.body.string() }
            )
            val sol = LatanimeBysePow.solveBlocking(capNode.path("pow_nonce").asText(), capNode.path("pow_difficulty").asInt(12))!!
            val verNode = LatanimeHttpDefaults.JSON.readTree(
                client.newCall(
                    Request.Builder().url("$frameBase/api/videos/$frameCode/captcha/verify")
                        .post(
                            LatanimeHttpDefaults.JSON.writeValueAsString(
                                mapOf("pow_token" to capNode.path("pow_token").asText(), "solution" to sol, "fingerprint" to fp)
                            ).toRequestBody(jsonMedia)
                        )
                        .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                        .header("Origin", frameBase)
                        .header("Referer", frameUrl)
                        .build()
                ).execute().use { it.body.string() }
            )
            LatanimeHttpDefaults.JSON.readTree(
                client.newCall(
                    Request.Builder().url("$frameBase/api/videos/$frameCode/playback")
                        .post(LatanimeHttpDefaults.JSON.writeValueAsString(mapOf("fingerprint" to fp)).toRequestBody(jsonMedia))
                        .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                        .header("Origin", frameBase)
                        .header("Referer", frameUrl)
                        .header("X-Captcha-Token", verNode.path("token").asText())
                        .build()
                ).execute().use { r ->
                    assertTrue("Byse playback after captcha ${r.code}", r.isSuccessful)
                    r.body.string()
                }
            )
        } else {
            assertTrue("Byse playback $pbCode", pbCode in 200..299)
            LatanimeHttpDefaults.JSON.readTree(pbBody)
        }
        val pb = finalPbJson.path("playback")
        val decrypted = LatanimeByseCrypto.decryptPlaybackDetails(
            pb.path("iv").asText(),
            pb.path("payload").asText(),
            pb.path("key_parts").map { it.asText() },
            pb.path("version").asText(),
        )
        assertNotNull("Byse decrypted playback", decrypted)
        assertTrue("Byse sources non-empty", decrypted!!.sources.isNotEmpty())
        println("LIVE_BYSE_OK: ${decrypted.sources.first().url}")

        // 2. Verify Savefiles live
        val sf = ep.players.firstOrNull { it.url.contains("savefiles") }
        assertNotNull(sf)
        val sfCode = LatanimeHttpDefaults.fileCodeFromPath(sf!!.url)
        val sfHtml = client.newCall(
            Request.Builder().url("https://savefiles.com/dl")
                .post(
                    FormBody.Builder()
                        .add("op", "embed")
                        .add("file_code", sfCode)
                        .add("auto", "1")
                        .add("referer", "https://latanime.org/")
                        .build()
                )
                .header("User-Agent", LatanimeHttpDefaults.UA)
                .header("Referer", "https://savefiles.com/e/$sfCode")
                .build()
        ).execute().use { it.body.string() }
        val sfStream = LatanimeHostParsers.extractSavefilesStream(sfHtml)
        assertNotNull("Savefiles stream extracted", sfStream)
        println("LIVE_SAVEFILES_OK: $sfStream")

        // 3. Verify Hexload live
        val hex = ep.players.firstOrNull { it.url.contains("hexload") }
        assertNotNull(hex)
        val hexHtml = client.newCall(Request.Builder().url(hex!!.url).header("User-Agent", LatanimeHttpDefaults.UA).build())
            .execute().use { it.body.string() }
        val hexArgs = LatanimeHostParsers.extractHexloadArgs(hexHtml, LatanimeHttpDefaults.fileCodeFromPath(hex.url))
        val hexForm = FormBody.Builder().apply { hexArgs.forEach { (k, v) -> add(k, v) } }.build()
        val hexJson = client.newCall(
            Request.Builder().url("https://hexload.com/download").post(hexForm)
                .header("User-Agent", LatanimeHttpDefaults.UA)
                .header("Referer", hex.url)
                .header("Origin", "https://hexload.com")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
        ).execute().use { it.body.string() }
        val hexUrl = LatanimeHostParsers.extractHexloadResultUrl(hexJson)
        assertNotNull("Hexload URL extracted: $hexJson", hexUrl)
        println("LIVE_HEXLOAD_OK: $hexUrl")

        // 4. Verify Voe F7 live
        val voe = ep.players.firstOrNull { it.url.contains("voe.") }
        assertNotNull(voe)
        var curVoeUrl = voe!!.url
        var voeHtml = ""
        repeat(3) {
            val res = client.newCall(Request.Builder().url(curVoeUrl).header("User-Agent", LatanimeHttpDefaults.UA).build()).execute()
            curVoeUrl = res.request.url.toString()
            voeHtml = res.body.string()
            res.close()
            val redir = LatanimeVoeDecoder.findRedirectUrl(voeHtml)
            if (!redir.isNullOrBlank() && redir != curVoeUrl) curVoeUrl = redir else return@repeat
        }
        val voeNode = LatanimeVoeDecoder.extractPayloadFromHtml(voeHtml)
        assertNotNull("Voe F7 decrypted", voeNode)
        assertFalse(voeNode!!.path("source").asText("").isBlank())
        println("LIVE_VOE_OK: ${voeNode.path("source").asText()}")

        // 5. Verify MixDrop live
        val mx = ep.players.firstOrNull { it.url.contains("mixdrop") || it.url.contains("mxdrop") }
        assertNotNull(mx)
        val mxHtml = client.newCall(Request.Builder().url(mx!!.url).header("User-Agent", LatanimeHttpDefaults.UA).build())
            .execute().use { it.body.string() }
        val mxStream = LatanimeHostParsers.extractMixDropStream(mxHtml)
        assertNotNull("MixDrop stream extracted", mxStream)
        println("LIVE_MIXDROP_OK: $mxStream")

        // 6. Verify Mp4Upload live
        val mp4 = ep.players.firstOrNull { it.url.contains("mp4upload") }
        assertNotNull(mp4)
        val mp4Html = client.newCall(Request.Builder().url(mp4!!.url).header("User-Agent", LatanimeHttpDefaults.UA).build())
            .execute().use { it.body.string() }
        val mp4Stream = LatanimeHostParsers.extractMp4UploadStream(mp4Html)
        assertNotNull("Mp4Upload stream extracted", mp4Stream)
        println("LIVE_MP4UPLOAD_OK: $mp4Stream")

        // 7. Verify Mediafire direct download link
        val mf = ep.downloads.firstOrNull { it.url.contains("mediafire") }
        assertNotNull(mf)
        val mfDoc = Jsoup.parse(
            client.newCall(Request.Builder().url(mf!!.url).header("User-Agent", LatanimeHttpDefaults.UA).build())
                .execute().use { it.body.string() },
            mf.url,
        )
        val mfHref = mfDoc.selectFirst("a#downloadButton[href]")?.attr("href")?.trim()
        assertNotNull("Mediafire direct link extracted", mfHref)
        println("LIVE_MEDIAFIRE_OK: $mfHref")

        // 8. Verify Uqload (redirect uqload.cx -> uqload.vc + unpack) and Filemoon (Byse details) on One Piece ep 1
        val (opEpUrl, opEpDoc) = fetchDoc("https://latanime.org/ver/one-piece-latino-episodio-1")
        val opEp = LatanimeMarkup.parseEpisodePage(opEpDoc, opEpUrl)
        val uq = opEp.players.firstOrNull { it.url.contains("uqload") }
        if (uq != null) {
            val uqHtml = client.newCall(Request.Builder().url(uq.url).header("User-Agent", LatanimeHttpDefaults.UA).build())
                .execute().use { it.body.string() }
            val uqStream = LatanimeHostParsers.extractUqloadStream(uqHtml)
            assertNotNull("Uqload stream unpacked and extracted", uqStream)
            println("LIVE_UQLOAD_OK: $uqStream")
        }
        val fm = opEp.players.firstOrNull { it.url.contains("filemoon") }
        if (fm != null) {
            val fmCode = LatanimeHttpDefaults.fileCodeFromPath(fm.url)
            val fmBase = LatanimeHttpDefaults.hostBaseUrl(fm.url, "https://filemoon.sx")
            val fmRes = client.newCall(
                Request.Builder().url("$fmBase/api/videos/$fmCode/embed/details")
                    .header("User-Agent", LatanimeHttpDefaults.MOBILE_UA)
                    .header("Referer", fm.url)
                    .build()
            ).execute().use { it.body.string() }
            assertTrue("Filemoon uses Byse embed_frame_url", fmRes.contains("embed_frame_url"))
            println("LIVE_FILEMOON_BYSE_OK: $fmRes")
        }
    }
}
