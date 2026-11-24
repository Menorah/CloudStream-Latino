package com.menorah.repelis24shop

import android.util.Base64
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder

internal data class Card(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class Season(val number: Int, val url: String)
internal data class Episode(val title: String, val season: Int, val number: Int, val url: String, val poster: String?)
internal data class Player(val url: String, val language: String)

internal object Repelis24ShopMarkup {
    private fun host(url: String, expected: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme.equals("https", true) && u.host.equals(expected, true) && u.userInfo == null && u.port in listOf(-1, 443)
    }.getOrDefault(false)
    fun site(url: String) = host(url, "repelis24.shop")
    fun nupload(url: String) = host(url, "nupload.my")
    /** Posters live on the site's CDN but any https image is accepted; lazy attrs are tried too. */
    private fun image(url: String): String? = runCatching {
        val u = URI(url.trim())
        val clean = url.trim().takeIf {
            u.scheme.equals("https", true) && !u.host.isNullOrBlank() && u.userInfo == null &&
                u.port in listOf(-1, 443) && it.length <= 2048 && !it.startsWith("data:")
        } ?: return@runCatching null
        // The site's image CDN is behind the same Cloudflare wall as the HTML: the app's
        // image loader would receive a challenge page instead of pixels. Route those
        // through a public image proxy; other hosts (TMDB…) work directly.
        val host = u.host.orEmpty()
        if (host.equals("repelis24.shop", true) || host.endsWith(".repelis24.shop", true)) {
            "https://images.weserv.nl/?url=" + java.net.URLEncoder.encode(clean.removePrefix("https://"), "UTF-8")
        } else clean
    }.getOrNull()
    fun img(element: org.jsoup.nodes.Element?): String? {
        if (element == null) return null
        // Lazy-load placeholders put a spinner in src; the real image lives in data-*.
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-bg", "data-background")) {
            val raw = element.attr(attr).trim()
            if (raw.isEmpty()) continue
            val value = element.absUrl(attr).ifBlank { raw }
            image(value)?.let { return it }
        }
        val style = element.attr("style")
        Regex("url\\([\"']?(https?://[^\"')]+)").find(style)?.groupValues?.get(1)?.let { image(it)?.let { v -> return v } }
        for (entry in element.attr("srcset").split(',')) {
            val raw = entry.trim().split(' ').firstOrNull()?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val value = element.absUrl(raw).ifBlank { raw }
            image(value)?.let { return it }
        }
        val src = element.attr("src").trim()
        return if (src.isEmpty()) null else image(element.absUrl("src").ifBlank { src })
    }

    /** Image of a card: `<img>` in any position, or a background style on the block. */
    fun cardImage(container: org.jsoup.nodes.Element?): String? {
        if (container == null) return null
        img(container.selectFirst("img"))?.let { return it }
        var node: org.jsoup.nodes.Element? = container
        var depth = 0
        while (node != null && depth < 4) {
            val style = node.attr("style")
            Regex("url\\([\"']?(https?://[^\"')]+)").find(style)?.groupValues?.get(1)?.let { image(it)?.let { v -> return v } }
            node = node.parent()
            depth++
        }
        return null
    }
    /** Nuupload's alternative player carries the actual VOE URL as one query parameter. */
    fun voeFromWrapper(url: String): String? = runCatching {
        if (!nupload(url)) return null
        val uri = URI(url)
        if (uri.path != "/iframe/" || uri.fragment != null) return null
        val values = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size != 2 || pieces[0] != "url") null else URLDecoder.decode(pieces[1], "UTF-8")
        }
        if (values.size != 1) return null
        values.single().takeIf { host(it, "voe.sx") && URI(it).path.orEmpty().startsWith("/e/") }
    }.getOrNull()
    fun cards(doc: Document): List<Card> {
        val items = doc.select(".movie-item").mapNotNull { e ->
            val a = e.selectFirst("a[href]") ?: return@mapNotNull null
            val url = a.absUrl("href"); if (!site(url)) return@mapNotNull null
            val path = URI(url).path.orEmpty(); val movie = path.startsWith("/pelicula/")
            if (!movie && !path.startsWith("/serie/")) return@mapNotNull null
            val title = a.selectFirst(".item-detail p")?.text()?.trim().orEmpty()
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim()?.removePrefix("Poster ")?.trim().orEmpty() }
                .ifBlank { e.selectFirst("h2, h3, .title")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            Card(title, url, cardImage(e) ?: cardImage(a), movie)
        }.distinctBy { it.url }
        if (items.isNotEmpty()) return items
        // Defensive fallback: any link block pointing at a title.
        return doc.select("a[href]").mapNotNull { a ->
            val url = a.absUrl("href"); if (!site(url)) return@mapNotNull null
            val path = URI(url).path.orEmpty(); val movie = path.startsWith("/pelicula/")
            if (!movie && !path.startsWith("/serie/")) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank { a.selectFirst("img")?.attr("alt")?.trim()?.removePrefix("Poster ")?.trim().orEmpty() }
                .ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            Card(title, url, cardImage(a.parent()), movie)
        }.distinctBy { it.url }
    }
    fun hasNext(doc: Document, page: Int) = doc.select(".pagination a[href]").any { e ->
        val url = e.absUrl("href")
        site(url) && URI(url).path.orEmpty().trimEnd('/').endsWith("/page/${page + 1}")
    }
    fun seasons(doc: Document): List<Season> = doc.select(".item-season a[href]").mapNotNull { a ->
        val url = a.absUrl("href"); if (!site(url) || !URI(url).path.orEmpty().startsWith("/temporada/")) return@mapNotNull null
        val number = Regex("(?i)temporada\\s+(\\d+)").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        Season(number, url)
    }.distinctBy { it.number }
    fun episodes(doc: Document, season: Int): List<Episode> = doc.select(".item-season-episodes a[href]").mapNotNull { a ->
        val url = a.absUrl("href"); if (!site(url)) return@mapNotNull null
        val match = Regex("-(\\d+)x(\\d+)/?$").find(URI(url).path.orEmpty()) ?: return@mapNotNull null
        val s = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val number = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        if (s != season || number < 1) return@mapNotNull null
        val title = a.selectFirst("[itemprop=name]")?.text()?.trim().orEmpty().ifBlank { "Episodio $number" }
        Episode(title, s, number, url, img(a.selectFirst("img")))
    }.distinctBy { it.season to it.number }
    fun players(doc: Document): List<Player> = doc.select("#player li[data-server]").mapNotNull { e ->
        val raw = runCatching { String(Base64.decode(e.attr("data-server"), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
        if (!nupload(raw)) return@mapNotNull null
        val text = e.text().lowercase()
        val language = when {
            "latino" in text -> "Latino"
            "castellano" in text -> "Castellano"
            "subtitulado" in text -> "Subtitulado"
            else -> "Idioma no indicado"
        }
        Player(raw, language)
    }.distinctBy { it.url to it.language }
    /** Decode only the documented numeric array and session; never execute remote JavaScript. */
    fun media(html: String): String? = runCatching {
        val pattern = Regex("""(\w+)\.forEach\s*\(function\s+\w*\s*\([^)]*\)\s*\{\s*(\w+)\s*\+=\s*String\.fromCharCode\(parseInt\(atob\([^)]*\)\.replace\(/\\D/g,\s*['\"]{2}\)\)\s*-\s*(\d+)\)""")
        val m = pattern.find(html) ?: return null
        val array = m.groupValues[1]; val offset = m.groupValues[3].toInt()
        val list = Regex("""\bvar\s+${Regex.escape(array)}\s*=\s*\[(.*?)]\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val parts = Regex("""[\"']([A-Za-z0-9+/=]+)[\"']""").findAll(list).map { it.groupValues[1] }.toList()
        if (parts.size !in 1..4096) return null
        val url = parts.joinToString("") { part ->
            val digits = String(Base64.decode(part, Base64.DEFAULT), Charsets.UTF_8).filter(Char::isDigit)
            val c = digits.toLong() - offset
            require(c in 32..126); c.toInt().toChar().toString()
        }
        val u = URI(url)
        if (!u.scheme.equals("https", true) || u.userInfo != null || u.port !in listOf(-1, 443) ||
            !(u.host.orEmpty().equals("ibra.lat", true) || u.host.orEmpty().lowercase().endsWith(".ibra.lat"))) return null
        val session = Regex("""\bsesz\s*=\s*['\"]([A-Za-z0-9]+)['\"]""").find(html)?.groupValues?.get(1) ?: return null
        url + (if (u.rawQuery == null) "?" else "&") + "s=" + URLEncoder.encode(session, "UTF-8")
    }.getOrNull()
}
