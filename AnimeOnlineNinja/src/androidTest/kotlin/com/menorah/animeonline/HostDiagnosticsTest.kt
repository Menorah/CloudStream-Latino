package com.menorah.animeonline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Opt-in diagnostic: logs host metadata only, never full URLs, cookies, tokens or video bytes. */
@RunWith(AndroidJUnit4::class)
class HostDiagnosticsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = "https://ww4.animeonline.ninja"
    private fun report(value: Map<String, Any?>) = Log.i("AOHostProbe", JSONObject(value).toString())

    @Test fun diagnoseWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("hostdiag") != "true") return@runBlocking
        val browser = BrowserSiteTransport(context)
        val pages = mutableListOf("$root/episodio/dragon-ball-super-cap-1/", "$root/episodio/dragon-ball-super-cap-2/", "$root/pelicula/kimi-no-na-wa-your-name/")
        try {
            val home = browser.get("$root/inicio/", emptyMap())
            pages += Jsoup.parse(home.text, home.url).select("a[href*='/episodio/']").map { it.absUrl("href") }.filter { it.startsWith("$root/episodio/") }.distinct().take(4)
        } catch (_: Exception) { report(mapOf("step" to "home", "error" to "unavailable")) }
        val samples = linkedMapOf<String, Pair<String, String>>()
        val inventory = linkedMapOf<String, MutableSet<String>>()
        for (page in pages.distinct().take(7)) {
            try {
                val response = browser.get(page, emptyMap())
                if (!response.isSuccessful) { report(mapOf("page" to URI(page).path, "status" to response.code)); continue }
                val doc = Jsoup.parse(response.text, response.url)
                val option = doc.selectFirst("#playeroptionsul li[data-post][data-nume]") ?: continue
                val post = option.attr("data-post"); val nume = option.attr("data-nume"); val type = option.attr("data-type").ifBlank { "tv" }
                if (!post.matches(Regex("[0-9]+")) || !nume.matches(Regex("[0-9]+")) || !type.matches(Regex("[a-z]+"))) continue
                val api = "$root/wp-json/dooplayer/v1/post/$post?type=$type&source=$nume"
                val result = browser.get(api, mapOf("Referer" to page))
                if (!result.isSuccessful) { report(mapOf("page" to URI(page).path,"apiStatus" to result.code)); continue }
                val embed = JSONObject(result.text).optString("embed_url")
                if (URI(embed).host != "saidochesto.top") continue
                val connection = URL(embed).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000; connection.readTimeout = 8_000
                connection.setRequestProperty("Referer", page)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val player = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
                val html = Jsoup.parse(player, embed)
                var count = 0
                for (server in SiteMarkup.playerServers(html)) {
                    val host = URI(server.url).host?.lowercase() ?: continue
                    val path = URI(server.url).path.orEmpty().lowercase()
                    val form = when { path.endsWith(".mp4") -> "mp4"; path.endsWith(".m3u8") -> "m3u8"; path.endsWith(".html") -> "html"; else -> "page" }
                    val pathKind = when { path.startsWith("/v/") -> "v"; path.startsWith("/e/") -> "e"; path.startsWith("/embed/") -> "embed"; path.startsWith("/get_video") -> "get_video"; else -> "other" }
                    inventory.getOrPut(host) { linkedSetOf() }.add("$pathKind:$form")
                    samples.putIfAbsent(host, server.url to embed)
                    count++
                }
                report(mapOf("page" to URI(page).path, "count" to count))
            } catch (e: Exception) { report(mapOf("page" to URI(page).path, "error" to e.javaClass.simpleName)) }
        }
        report(mapOf("inventory" to inventory.mapValues { it.value.toList() }))
        val results = mutableListOf<Map<String, Any?>>()
        for ((host, pair) in samples) {
            if (results.size >= 18 || !listOf("uqload", "savefiles", "vidara", "streamtape", "mxdrop", "mixdrop", "vixeo", "mp4upload", "zoplayer", "zoply", "zpl").any { host.contains(it) }) continue
            try {
                val connection = URL(pair.first).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 4_000; connection.readTimeout = 4_000
                connection.setRequestProperty("Range", "bytes=0-1023")
                connection.setRequestProperty("Referer", pair.second)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val status = connection.responseCode
                val mime = connection.contentType?.substringBefore(';').orEmpty()
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val data = stream?.use { input -> ByteArray(1024).let { b -> val n = input.read(b); if (n > 0) b.copyOf(n) else byteArrayOf() } } ?: byteArrayOf()
                val text = String(data, Charsets.ISO_8859_1)
                val signature = when { text.trimStart().startsWith("#EXTM3U") -> "hls"; text.take(32).contains("ftyp") -> "mp4"; text.contains("<html", true) || text.contains("<!doctype", true) -> "html"; else -> "other" }
                results.add(mapOf("host" to host, "status" to status, "mime" to mime, "signature" to signature, "redirectHost" to connection.getHeaderField("Location")?.let { runCatching { URI(it).host }.getOrNull() }))
                connection.disconnect()
            } catch (e: Exception) { results.add(mapOf("host" to host, "error" to e.javaClass.simpleName)) }
        }
        report(mapOf("checks" to results))
    }
}
