package com.menorah.animeonline

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CatalogPagerTest {
    private val root = "https://ww4.animeonline.ninja"
    private fun doc(section: String, number: Int, last: Int) = Jsoup.parse("""
        <article class="item"><div class="data"><h3><a href="/online/title-$number/">Title $number</a></h3></div></article>
        ${if (number < last) """<div class="pagination"><a href="$section/page/${number + 1}/">next</a></div>""" else ""}
    """, root + section + "/")

    @Test fun `expansion appends successive pairs without skipping the last page`() = runBlocking {
        val seen = mutableListOf<String>()
        val pager = CatalogPager { path ->
            seen += path
            val n = Regex("/page/(\\d+)/").find(path)?.groupValues?.get(1)?.toInt() ?: 1
            doc("/genero/audio-latino", n, 5)
        }
        val p1 = pager.get("/genero/audio-latino/", 1)
        val p2 = pager.get("/genero/audio-latino/", 2)
        val p3 = pager.get("/genero/audio-latino/", 3)
        assertEquals(listOf("Title 1", "Title 2"), p1.cards.map { it.title })
        assertEquals(listOf("Title 3", "Title 4"), p2.cards.map { it.title })
        assertEquals(listOf("Title 5"), p3.cards.map { it.title })
        assertTrue(p1.hasNext); assertTrue(p2.hasNext); assertFalse(p3.hasNext)
        assertEquals(listOf("/genero/audio-latino/", "/genero/audio-latino/page/2/", "/genero/audio-latino/page/3/", "/genero/audio-latino/page/4/", "/genero/audio-latino/page/5/"), seen)
    }

    @Test fun `failed prefetch is retried on expansion rather than skipped`() = runBlocking {
        var failed = false
        val pager = CatalogPager { path ->
            val n = Regex("/page/(\\d+)/").find(path)?.groupValues?.get(1)?.toInt() ?: 1
            if (n == 2 && !failed) { failed = true; throw IllegalStateException("temporary error") }
            doc("/genero/audio-latino", n, 3)
        }
        val first = pager.get("/genero/audio-latino/", 1)
        assertEquals(listOf("Title 1"), first.cards.map { it.title }); assertTrue(first.hasNext)
        val next = pager.get("/genero/audio-latino/", 2)
        assertEquals(listOf("Title 2", "Title 3"), next.cards.map { it.title }); assertFalse(next.hasNext)
    }

    @Test fun `inicio continues with online archive without repeating curated titles`() = runBlocking {
        val pager = CatalogPager { path ->
            if (path == "/inicio/") doc("/online", 1, 1)
            else doc("/online", if (path.contains("/page/2/")) 2 else 1, 3)
        }
        assertTrue(pager.get("/inicio/", 1).hasNext)
        val next = pager.get("/inicio/", 2)
        assertEquals(listOf("Title 2"), next.cards.map { it.title }); assertTrue(next.hasNext)
    }
}
