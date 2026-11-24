package com.menorah.sololatino

import org.junit.Assert.*
import org.junit.Test

class PrincipalPlayerDataTest {
    @Test fun optionsKeepLanguageAndRemoveDuplicateIdsOnlyWithinALanguage() {
        val options = PrincipalPlayerData.options("""{"langs_s":{"LAT":[["Player+","a"],["Opción 1","b"],["Repetido","a"]],"SUB":[["Opción 1","b"]]}}""")
        assertEquals(listOf("LAT", "LAT", "SUB"), options.map { it.language })
        assertEquals(listOf("Player+", "Opción 1", "Opción 1"), options.map { it.label })
    }
    @Test fun fallbackDoesNotInventALanguageAndRejectsInvalidRows() {
        val options = PrincipalPlayerData.options("""{"s":[["Player+","1"],["Mala",null],["Sin id",""]]}""")
        assertEquals(1, options.size)
        assertEquals("Idioma no indicado", options.single().language)
        assertTrue(PrincipalPlayerData.options("<html>403</html>").isEmpty())
    }
    @Test fun sourceSeparatesExternalIframesFromSignedProxyLinks() {
        val iframe = PrincipalPlayerData.source("""{"type":"iframe","url":"https://example.com/e/1"}""")!!
        assertEquals("iframe", iframe.type)
        assertEquals("https://example.com/e/1", iframe.url)
        val proxy = PrincipalPlayerData.source("""{"u":"https://cdn.example/hls/master.m3u8?x=1&y=dos","sig":"a+b=="}""")!!
        assertEquals("signed-proxy", proxy.type)
        assertEquals("https://player.pelisserieshoy.com/p.php?url=https%3A%2F%2Fcdn.example%2Fhls%2Fmaster.m3u8%3Fx%3D1%26y%3Ddos&sig=a%2Bb%3D%3D", proxy.url)
        assertNull(PrincipalPlayerData.source("""{"type":"iframe","url":"https://127.0.0.1/e/1"}"""))
        assertNull(PrincipalPlayerData.source("""{"u":"https://cdn.example/master.m3u8"}"""))
    }
}
