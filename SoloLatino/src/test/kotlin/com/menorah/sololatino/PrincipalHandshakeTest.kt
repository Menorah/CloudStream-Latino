package com.menorah.sololatino

import org.junit.Assert.*
import org.junit.Test

class PrincipalHandshakeTest {
    @Test fun parsesSyntheticPlayerStateAndLeavesFormValuesUnencoded() {
        val html = """<script>const _t='session+token'; const _qc="item"; const _qk=['A','B','C','D'];</script>"""
        val handshake = PrincipalHandshake.parse(html)!!
        assertEquals("BDAC", handshake.signed)
        assertEquals("4ac5e99a", handshake.fields("1")["tk"])
        assertEquals("BDAC", handshake.fields("1")["rt"])
        assertEquals("session+token", handshake.fields("2", "id/one")["tok"])
        assertEquals("id/one", handshake.fields("2", "id/one")["v"])
        assertEquals("1", handshake.fields("click")["ts"])
    }
    @Test fun headerAndMalformedStates() {
        assertEquals("6290424a7e573f25", renderHeader("token", 180_000))
        assertNull(PrincipalHandshake.parse("const _t='x';const _qc='y';const _qk=['a'];"))
        assertNull(PrincipalHandshake.parse("<html>Just a moment</html>"))
    }
}
