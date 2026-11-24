package com.menorah.sololatino

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrincipalPlayerDataAndroidTest {
    @Test fun keepsLanguageAndEncodesSignedProxyQuery() {
        val options = PrincipalPlayerData.options("""{"langs_s":{"LAT":[["Player+","a"]],"SUB":[["Opción 1","b"]]}}""")
        assertEquals(listOf("LAT", "SUB"), options.map { it.language })
        val source = PrincipalPlayerData.source("""{"u":"https://cdn.example/master.m3u8?x=1&y=2","sig":"a+b=="}""")!!
        assertEquals("https://player.pelisserieshoy.com/p.php?url=https%3A%2F%2Fcdn.example%2Fmaster.m3u8%3Fx%3D1%26y%3D2&sig=a%2Bb%3D%3D", source.url)
    }
}
