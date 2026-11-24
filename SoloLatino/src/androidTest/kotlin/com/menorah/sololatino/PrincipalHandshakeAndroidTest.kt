package com.menorah.sololatino

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrincipalHandshakeAndroidTest {
    @Test fun computesSyntheticHandshake() {
        val handshake = PrincipalHandshake.parse("""_t='session+token'; _qc="item"; _qk=['A','B','C','D'];""")!!
        assertEquals("BDAC", handshake.signed)
        assertEquals("4ac5e99a", handshake.fields("1")["tk"])
        assertEquals("6290424a7e573f25", renderHeader("token", 180_000))
    }
}
