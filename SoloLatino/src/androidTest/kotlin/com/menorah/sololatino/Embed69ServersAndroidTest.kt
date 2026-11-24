package com.menorah.sololatino

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fasterxml.jackson.core.Base64Variants
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class Embed69ServersAndroidTest {
    @Test fun decodesServersAndPreservesLanguage() {
        val key = MessageDigest.getInstance("SHA-256").digest("abc123pepper".toByteArray())
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val link = Base64Variants.getDefaultVariant().encode(iv + cipher.doFinal("https://example.com/e/one".toByteArray()))
        val html = """const LINK_CHALLENGE = 'abc123'; const LINK_SALT = 'pepper'; let dataLink = [{"video_language":"LAT","sortedEmbeds":[{"servername":"Streamwish","link":"$link"}]}];"""
        val result = Embed69Servers.parse(html).single()
        assertEquals("LAT", result.language)
        assertEquals("Streamwish", result.name)
        assertEquals("https://example.com/e/one", result.url)
    }
}
