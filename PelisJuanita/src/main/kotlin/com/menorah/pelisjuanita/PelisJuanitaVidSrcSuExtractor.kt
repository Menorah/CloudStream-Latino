package com.menorah.pelisjuanita

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal object PelisJuanitaAppContextHolder {
    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    private val lifecycleRegistered = AtomicBoolean(false)

    private tailrec fun Context.unwrapActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.unwrapActivity()
        else -> null
    }

    fun register(context: Context?) {
        if (context == null) return
        context.unwrapActivity()?.let { act ->
            if (!act.isFinishing && !act.isDestroyed) {
                currentActivityRef = WeakReference(act)
            }
        }
        val appCtx = context.applicationContext ?: context
        appContextRef = WeakReference(appCtx)
        if (appCtx is Application && lifecycleRegistered.compareAndSet(false, true)) {
            appCtx.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            currentActivityRef = WeakReference(activity)
                        }
                    }

                    override fun onActivityStarted(activity: Activity) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            currentActivityRef = WeakReference(activity)
                        }
                    }

                    override fun onActivityResumed(activity: Activity) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            currentActivityRef = WeakReference(activity)
                        }
                    }

                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) {
                        if (currentActivityRef?.get() === activity) {
                            currentActivityRef = null
                        }
                    }
                },
            )
        }
    }

    fun getContext(): Context? {
        appContextRef?.get()?.let { return it }
        return runCatching { CloudStreamApp.context }.getOrNull()
    }

    fun getCurrentActivity(): Activity? {
        val tracked = currentActivityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (tracked != null) return tracked
        val fromApp = runCatching { CloudStreamApp.context?.unwrapActivity() }.getOrNull()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
        if (fromApp != null) return fromApp
        return appContextRef?.get()?.unwrapActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
    }
}

internal data class PelisJuanitaVidsrcSubtitleCandidate(
    val label: String,
    val rawUrl: String,
    val directUrl: String?,
)

internal data class PelisJuanitaVidsrcStep1(
    val apiPath: String? = null,
    val iframeUrl: String? = null,
)

internal data class PelisJuanitaVidsrcConfig(
    val mediaType: String = "",
    val apiToken: String = "",
    val turnstile: Boolean = false,
    val cstCaptcha: Boolean = false,
    val api: String = "",
    val metaApi: String = "",
    val streamBase: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val subUrl: String = "",
    val subLabel: String = "",
    val subLang: String = "",
    val playerOrigin: String = "",
)

internal data class PelisJuanitaVidsrcApiData(
    val encryptedStreamUrls: String = "",
    val plainStreamUrls: List<String> = emptyList(),
    val castUrls: List<String> = emptyList(),
    val wasmUrl: String = "",
    val wasmBase64: String = "",
    val streamUrlsApiHost: String = "",
    val defaultSubtitles: List<PelisJuanitaVidsrcSubtitleCandidate> = emptyList(),
)

/**
 * Pure-JVM WASM Data/Code parser + RFC 8439 ChaCha20 decryptor for `vs.wasm_url` (`wasm.php`)
 * stream_urls payloads served by VidSrc / vsembed.su pool hosts.
 */
internal object PelisJuanitaWasmChaCha {
    private const val CHACHA_C0 = 0x61707865 // "expa"
    private const val CHACHA_C1 = 0x3320646e // "nd 3"
    private const val CHACHA_C2 = 0x79622d32 // "2-by"
    private const val CHACHA_C3 = 0x6b206574 // "te k"

    private class Cursor(val buf: ByteArray, var pos: Int, val end: Int = buf.size) {
        fun hasRemaining(): Boolean = pos < end

        fun readU8(): Int = buf[pos++].toInt() and 0xFF

        fun readUleb32(): Int {
            var res = 0
            var shift = 0
            while (pos < end) {
                val b = readU8()
                res = res or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
                if (shift > 35) error("ULEB32 overflow")
            }
            error("Unexpected EOF reading ULEB32")
        }

        fun readSleb32(): Int {
            var res = 0
            var shift = 0
            var b: Int
            while (pos < end) {
                b = readU8()
                res = res or ((b and 0x7F) shl shift)
                shift += 7
                if ((b and 0x80) == 0) {
                    if (shift < 32 && (b and 0x40) != 0) {
                        res = res or (-(1 shl shift))
                    }
                    return res
                }
                if (shift > 35) error("SLEB32 overflow")
            }
            error("Unexpected EOF reading SLEB32")
        }

        fun readSleb64(): Long {
            var res = 0L
            var shift = 0
            var b: Int
            while (pos < end) {
                b = readU8()
                res = res or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if ((b and 0x80) == 0) {
                    if (shift < 64 && (b and 0x40) != 0) {
                        res = res or (-(1L shl shift))
                    }
                    return res
                }
                if (shift > 70) error("SLEB64 overflow")
            }
            error("Unexpected EOF reading SLEB64")
        }

        fun skipBlockType() {
            if (pos >= end) return
            val first = buf[pos].toInt()
            if (first == -64 || first == -1 || first == -2 || first == -3 || first == -4 ||
                first == -5 || first == -16 || first == -17
            ) {
                pos++
            } else {
                readSleb64()
            }
        }
    }

    fun decryptStreamUrls(encBase64: String, wasmBytes: ByteArray): List<String> {
        val raw = runCatching { PelisJuanitaCrypto.decodeBase64Flexible(encBase64) }.getOrNull() ?: return emptyList()
        if (raw.size <= 12) return emptyList()
        val key = extractKey(wasmBytes) ?: return emptyList()
        val nonce = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)
        val plain = decryptChaCha20(key, nonce, ciphertext, initialCounter = 0)
        val text = String(plain, StandardCharsets.UTF_8)
        return text.split('\r', '\n', ',', ' ')
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    fun extractKey(wasmBytes: ByteArray): ByteArray? = runCatching {
        if (wasmBytes.size < 16) return@runCatching null
        if (wasmBytes[0] != 0x00.toByte() || wasmBytes[1] != 0x61.toByte() ||
            wasmBytes[2] != 0x73.toByte() || wasmBytes[3] != 0x6D.toByte()
        ) {
            return@runCatching null
        }

        var mem = ByteArray(65536)
        val functions = mutableListOf<ByteArray>()

        val cur = Cursor(wasmBytes, 8)
        while (cur.hasRemaining()) {
            val secId = cur.readU8()
            val secLen = cur.readUleb32()
            val secEnd = (cur.pos + secLen).coerceAtMost(wasmBytes.size)
            val secCur = Cursor(wasmBytes, cur.pos, secEnd)
            when (secId) {
                10 -> {
                    val count = secCur.readUleb32()
                    repeat(count) {
                        val bodyLen = secCur.readUleb32()
                        val bodyEnd = (secCur.pos + bodyLen).coerceAtMost(secEnd)
                        functions.add(wasmBytes.copyOfRange(secCur.pos, bodyEnd))
                        secCur.pos = bodyEnd
                    }
                }
                11 -> {
                    val count = secCur.readUleb32()
                    repeat(count) {
                        val mode = secCur.readUleb32()
                        var offset = -1
                        if (mode == 0 || mode == 2) {
                            if (mode == 2) secCur.readUleb32() // memidx
                            while (secCur.hasRemaining()) {
                                val op = secCur.readU8()
                                if (op == 0x41) {
                                    offset = secCur.readSleb32()
                                } else if (op == 0x0B) {
                                    break
                                } else {
                                    offset = -1
                                    while (secCur.hasRemaining() && secCur.readU8() != 0x0B) {
                                        // skip expression
                                    }
                                    break
                                }
                            }
                        }
                        val dataLen = secCur.readUleb32()
                        val dataEnd = (secCur.pos + dataLen).coerceAtMost(secEnd)
                        if ((mode == 0 || mode == 2) && offset >= 0 && dataLen > 0) {
                            val needed = offset + dataLen
                            if (needed <= 2 * 1024 * 1024) {
                                if (needed > mem.size) {
                                    mem = mem.copyOf(maxOf(mem.size * 2, needed))
                                }
                                System.arraycopy(wasmBytes, secCur.pos, mem, offset, dataEnd - secCur.pos)
                            }
                        }
                        secCur.pos = dataEnd
                    }
                }
            }
            cur.pos = secEnd
        }

        for (fn in functions) {
            val words = extractChaChaKeyWords(fn, mem.copyOf())
            if (words != null && words.size == 8) {
                val key = ByteArray(32)
                for (i in 0 until 8) {
                    val w = words[i]
                    key[i * 4] = (w and 0xFF).toByte()
                    key[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
                    key[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
                    key[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
                }
                return@runCatching key
            }
        }
        null
    }.getOrNull()

    private fun extractChaChaKeyWords(code: ByteArray, mem: ByteArray): IntArray? {
        val c = Cursor(code, 0)
        val localDecls = c.readUleb32()
        repeat(localDecls) {
            c.readUleb32()
            c.readU8()
        }

        val stack = IntArray(256)
        var sp = 0
        val locals = IntArray(128)
        val globals = IntArray(32)

        fun push(v: Int) {
            if (sp < stack.size) stack[sp++] = v
        }

        fun pop(): Int = if (sp > 0) stack[--sp] else 0

        fun readI32LE(addr: Int): Int {
            if (addr < 0 || addr + 4 > mem.size) return 0
            return (mem[addr].toInt() and 0xFF) or
                ((mem[addr + 1].toInt() and 0xFF) shl 8) or
                ((mem[addr + 2].toInt() and 0xFF) shl 16) or
                ((mem[addr + 3].toInt() and 0xFF) shl 24)
        }

        fun readI16U(addr: Int): Int {
            if (addr < 0 || addr + 2 > mem.size) return 0
            return (mem[addr].toInt() and 0xFF) or ((mem[addr + 1].toInt() and 0xFF) shl 8)
        }

        fun readI8U(addr: Int): Int =
            if (addr in mem.indices) mem[addr].toInt() and 0xFF else 0

        fun writeI32LE(addr: Int, value: Int) {
            if (addr < 0 || addr + 4 > mem.size) return
            mem[addr] = (value and 0xFF).toByte()
            mem[addr + 1] = ((value ushr 8) and 0xFF).toByte()
            mem[addr + 2] = ((value ushr 16) and 0xFF).toByte()
            mem[addr + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        var seenConstsMask = 0
        var lastConstWord = 0
        val constLocalIndices = mutableSetOf<Int>()
        val capturedAfterConsts = mutableListOf<Int>()
        val capturedBeforeConsts = mutableListOf<Pair<Int, Int>>()

        while (c.hasRemaining()) {
            val op = c.readU8()
            when (op) {
                0x00, 0x01 -> Unit
                0x02, 0x03, 0x04 -> c.skipBlockType()
                0x05, 0x0B, 0x0F -> if (op == 0x0F || (op == 0x0B && !c.hasRemaining())) break
                0x0C, 0x0D -> c.readUleb32()
                0x0E -> {
                    val cnt = c.readUleb32()
                    repeat(cnt + 1) { c.readUleb32() }
                }
                0x10 -> {
                    c.readUleb32()
                    break
                }
                0x11 -> {
                    c.readUleb32()
                    c.readUleb32()
                    break
                }
                0x1A -> pop()
                0x1B -> {
                    val cond = pop()
                    val v2 = pop()
                    val v1 = pop()
                    push(if (cond != 0) v1 else v2)
                }
                0x20 -> {
                    val idx = c.readUleb32()
                    push(if (idx in locals.indices) locals[idx] else 0)
                }
                0x21, 0x22 -> {
                    val idx = c.readUleb32()
                    val v = pop()
                    if (op == 0x22) push(v)
                    if (idx in locals.indices) locals[idx] = v
                    if (v == CHACHA_C0 || v == CHACHA_C1 || v == CHACHA_C2 || v == CHACHA_C3) {
                        constLocalIndices.add(idx)
                    } else if (seenConstsMask == 0b1111) {
                        if (idx !in constLocalIndices) {
                            capturedAfterConsts.add(v)
                            if (capturedAfterConsts.size == 8) {
                                return capturedAfterConsts.toIntArray()
                            }
                        }
                    } else {
                        capturedBeforeConsts.add(idx to v)
                    }
                }
                0x23 -> {
                    val idx = c.readUleb32()
                    push(if (idx in globals.indices) globals[idx] else 0)
                }
                0x24 -> {
                    val idx = c.readUleb32()
                    val v = pop()
                    if (idx in globals.indices) globals[idx] = v
                }
                0x28 -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val base = pop()
                    push(readI32LE(base + off))
                }
                0x2C -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val base = pop()
                    push(readI8U(base + off).toByte().toInt())
                }
                0x2D -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val base = pop()
                    push(readI8U(base + off))
                }
                0x2E -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val base = pop()
                    push(readI16U(base + off).toShort().toInt())
                }
                0x2F -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val base = pop()
                    push(readI16U(base + off))
                }
                0x36 -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val v = pop()
                    val base = pop()
                    writeI32LE(base + off, v)
                }
                0x3A -> {
                    c.readUleb32()
                    val off = c.readUleb32()
                    val v = pop()
                    val base = pop()
                    val addr = base + off
                    if (addr in mem.indices) mem[addr] = (v and 0xFF).toByte()
                }
                0x3F -> {
                    c.readUleb32()
                    push(mem.size / 65536)
                }
                0x41 -> {
                    val v = c.readSleb32()
                    lastConstWord = v
                    when (v) {
                        CHACHA_C0 -> seenConstsMask = seenConstsMask or 0b0001
                        CHACHA_C1 -> seenConstsMask = seenConstsMask or 0b0010
                        CHACHA_C2 -> seenConstsMask = seenConstsMask or 0b0100
                        CHACHA_C3 -> seenConstsMask = seenConstsMask or 0b1000
                    }
                    push(v)
                }
                0x45 -> push(if (pop() == 0) 1 else 0)
                0x46 -> {
                    val b = pop(); val a = pop(); push(if (a == b) 1 else 0)
                }
                0x47 -> {
                    val b = pop(); val a = pop(); push(if (a != b) 1 else 0)
                }
                0x6A -> {
                    val b = pop(); val a = pop(); push(a + b)
                }
                0x6B -> {
                    val b = pop(); val a = pop(); push(a - b)
                }
                0x6C -> {
                    val b = pop(); val a = pop(); push(a * b)
                }
                0x71 -> {
                    val b = pop(); val a = pop(); push(a and b)
                }
                0x72 -> {
                    val b = pop(); val a = pop(); push(a or b)
                }
                0x73 -> {
                    val b = pop(); val a = pop(); push(a xor b)
                }
                0x74 -> {
                    val b = pop(); val a = pop(); push(a shl (b and 31))
                }
                0x75 -> {
                    val b = pop(); val a = pop(); push(a shr (b and 31))
                }
                0x76 -> {
                    val b = pop(); val a = pop(); push(a ushr (b and 31))
                }
                0x77 -> {
                    // Quarter-rounds start with i32.rotl; stop collecting key setup if not yet done.
                    val b = pop(); val a = pop()
                    if (seenConstsMask == 0b1111 && capturedAfterConsts.isEmpty() && capturedBeforeConsts.size >= 8) {
                        return capturedBeforeConsts.takeLast(8).map { it.second }.toIntArray()
                    }
                    push(Integer.rotateLeft(a, b and 31))
                    if (seenConstsMask == 0b1111) break
                }
                0x78 -> {
                    val b = pop(); val a = pop(); push(Integer.rotateRight(a, b and 31))
                }
                else -> {
                    // Unsupported opcode past setup
                    if (lastConstWord != 0 && seenConstsMask == 0b1111) break
                }
            }
        }
        return if (seenConstsMask == 0b1111 && capturedAfterConsts.size == 8) {
            capturedAfterConsts.toIntArray()
        } else if (seenConstsMask == 0b1111 && capturedAfterConsts.isEmpty() && capturedBeforeConsts.size >= 8) {
            capturedBeforeConsts.takeLast(8).map { it.second }.toIntArray()
        } else {
            null
        }
    }

    fun decryptChaCha20(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        initialCounter: Int = 0,
    ): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }

        val out = ByteArray(input.size)
        val state = IntArray(16)
        val working = IntArray(16)
        val keyWords = IntArray(8) { i -> readLe32(key, i * 4) }
        val nonceWords = IntArray(3) { i -> readLe32(nonce, i * 4) }

        var counter = initialCounter
        var offset = 0
        while (offset < input.size) {
            state[0] = CHACHA_C0
            state[1] = CHACHA_C1
            state[2] = CHACHA_C2
            state[3] = CHACHA_C3
            for (i in 0 until 8) state[4 + i] = keyWords[i]
            state[12] = counter
            state[13] = nonceWords[0]
            state[14] = nonceWords[1]
            state[15] = nonceWords[2]

            System.arraycopy(state, 0, working, 0, 16)
            repeat(10) {
                quarterRound(working, 0, 4, 8, 12)
                quarterRound(working, 1, 5, 9, 13)
                quarterRound(working, 2, 6, 10, 14)
                quarterRound(working, 3, 7, 11, 15)

                quarterRound(working, 0, 5, 10, 15)
                quarterRound(working, 1, 6, 11, 12)
                quarterRound(working, 2, 7, 8, 13)
                quarterRound(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) working[i] = working[i] + state[i]

            val blockLen = minOf(64, input.size - offset)
            for (i in 0 until blockLen) {
                val ksByte = (working[i ushr 2] ushr ((i and 3) * 8)) and 0xFF
                out[offset + i] = (input[offset + i].toInt() xor ksByte).toByte()
            }
            offset += blockLen
            counter++
        }
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] = x[a] + x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] = x[c] + x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] = x[a] + x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] = x[c] + x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}

internal object PelisJuanitaVidSrcSuParser {
    private val VIDSRC_EMBED_HOSTS = setOf(
        "vsembed.su",
        "vidsrc-embed.su",
        "vidsrc-embed.ru",
        "vidsrcme.su",
        "vidsrcme.ru",
        "vsrc.su",
        "vidsrc.xyz",
        "vidsrc.in",
        "vidsrc.pm",
        "vidsrc.net",
    )

    private val DATA_API_REGEX = Regex("""data-api=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val PLAYER_IFRAME_REGEX = Regex(
        """<iframe[^>]+id=["']player_iframe["'][^>]+src=["']([^"']+)["']|<iframe[^>]+src=["']([^"']+)["'][^>]+id=["']player_iframe["']""",
        RegexOption.IGNORE_CASE,
    )
    private val ANY_EMBED_IFRAME_REGEX = Regex("""<iframe[^>]+src=["'](https?://[^"']+|//[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CFG_ASSIGN_REGEX = Regex("""(?:window\.)?CFG\s*=\s*\{""")
    private val CONFIG_ASSIGN_REGEX = Regex("""(?:window\.)?CONFIG\s*=\s*\{""")
    private val PLAYER_PATH_FALLBACK_REGEX = Regex("""(/embed/player/[^"'\s<>]+)""")
    private val PL_SEGMENT_REGEX = Regex("""/pl(?:c|i)?/""")
    private val STREAM_INF_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    private val INTERCEPTED_PL_REGEX = Regex(
        """^https?://[^/]+/pl(?:c|i)?/[A-Za-z0-9_-]{30,}(?:/[^?#]*)?\?[^#]*\btoken=([^&#\s]+)""",
        RegexOption.IGNORE_CASE,
    )

    @Suppress("DEPRECATION")
    private val tolerantMapper: ObjectMapper = ObjectMapper()
        .configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun isVidsrcEmbedUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        if (host in VIDSRC_EMBED_HOSTS || VIDSRC_EMBED_HOSTS.any { host.endsWith(".$it") }) {
            return true
        }
        val path = uri.rawPath.orEmpty().lowercase()
        return host.contains("vsembed") && (path.startsWith("/embed/movie") || path.startsWith("/embed/tv"))
    }

    fun originOf(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.authority}"
    }

    fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String? {
        val clean = relativeOrAbsolute.trim().replace("&amp;", "&")
        if (clean.isEmpty()) return null
        if (clean.startsWith("//")) return "https:$clean"
        baseUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()?.let { return it }
        return runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
    }

    fun normalizeSubtitleUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        // Keep URI-unsafe characters inside decoded filenames (e.g., spaces, `[`, `]`) percent-encoded for ExoPlayer.
        val sb = StringBuilder(trimmed.length + 16)
        for (ch in trimmed) {
            when (ch) {
                ' ' -> sb.append("%20")
                '[' -> sb.append("%5B")
                ']' -> sb.append("%5D")
                '"' -> sb.append("%22")
                '<' -> sb.append("%3C")
                '>' -> sb.append("%3E")
                '{' -> sb.append("%7B")
                '}' -> sb.append("%7D")
                '|' -> sb.append("%7C")
                '^' -> sb.append("%5E")
                '`' -> sb.append("%60")
                '\\' -> sb.append("%5C")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parseSubtitlesFromEmbedUrl(embedUrl: String): List<PelisJuanitaVidsrcSubtitleCandidate> {
        val query = runCatching { URI(embedUrl).rawQuery }.getOrNull()
            ?: embedUrl.substringAfter('?', "")
        if (query.isBlank()) return emptyList()
        val params = parseQueryParams(query)
        val rawSub = params["sub_url"]?.trim().orEmpty()
        if (!rawSub.startsWith("http://", ignoreCase = true) && !rawSub.startsWith("https://", ignoreCase = true)) {
            return emptyList()
        }
        val label = resolveSubtitleLabel(params["sub_label"], params["sub_lang"])
        return listOf(buildSubtitleCandidate(label, rawSub))
    }

    fun buildSubtitleCandidate(label: String, rawSubUrl: String): PelisJuanitaVidsrcSubtitleCandidate {
        val cleanRaw = normalizeSubtitleUrl(rawSubUrl)
        val unwrapped = unwrapCorsProxyUrl(rawSubUrl)?.let(::normalizeSubtitleUrl)
        return PelisJuanitaVidsrcSubtitleCandidate(
            label = label.ifBlank { "Español" },
            rawUrl = cleanRaw,
            directUrl = unwrapped?.takeIf { it != cleanRaw },
        )
    }

    fun unwrapCorsProxyUrl(url: String): String? {
        val lower = url.lowercase()
        if (!lower.contains("corsproxy.php")) return null
        val q = url.substringAfter('?', "")
        if (q.isBlank()) return null
        val inner = parseQueryParams(q)["url"]?.trim().orEmpty()
        return inner.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    fun resolveSubtitleLabel(subLabel: String?, subLang: String?): String {
        val cleanLabel = subLabel?.trim().orEmpty()
        if (cleanLabel.isNotEmpty()) return cleanLabel
        return when (subLang?.trim()?.lowercase()) {
            "es", "spa", "es-419", "es-mx", "es-es" -> "Español"
            "en", "eng" -> "Inglés"
            "pt", "por", "pt-br" -> "Portugués"
            else -> "Subtítulos"
        }
    }

    fun parseStep1(html: String, baseUrl: String): PelisJuanitaVidsrcStep1? {
        val apiRaw = DATA_API_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.trim()
        val iframeMatch = PLAYER_IFRAME_REGEX.find(html)
        val iframeRaw = (iframeMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: iframeMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: ANY_EMBED_IFRAME_REGEX.find(html)?.groupValues?.getOrNull(1))
            ?.replace("&amp;", "&")
            ?.trim()

        val resolvedApi = apiRaw?.let { resolveUrl(baseUrl, it) }
        val resolvedIframe = iframeRaw?.let { resolveUrl(baseUrl, it) }
        if (resolvedApi == null && resolvedIframe == null) return null
        return PelisJuanitaVidsrcStep1(apiPath = resolvedApi, iframeUrl = resolvedIframe)
    }

    fun parseSrcFromVsSrcJson(jsonText: String, baseUrl: String): String? {
        val root = runCatching { tolerantMapper.readTree(jsonText) }.getOrNull() ?: return null
        val src = root.get("src")?.asText()?.trim().orEmpty()
        if (src.isBlank()) return null
        return resolveUrl(baseUrl, src)
    }

    fun parsePlayerUrlFromLanding(html: String, baseUrl: String): String? {
        extractAssignedJsonObject(html, CFG_ASSIGN_REGEX)?.let { jsonStr ->
            val root = runCatching { tolerantMapper.readTree(jsonStr) }.getOrNull()
            val playerRel = root?.get("playerUrl")?.asText()?.trim().orEmpty()
            if (playerRel.isNotBlank()) {
                return resolveUrl(baseUrl, playerRel)
            }
        }
        val fallbackRel = PLAYER_PATH_FALLBACK_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        return resolveUrl(baseUrl, fallbackRel)
    }

    fun parsePlayerConfig(html: String, finalUrl: String): PelisJuanitaVidsrcConfig? {
        val jsonStr = extractAssignedJsonObject(html, CONFIG_ASSIGN_REGEX) ?: return null
        val root = runCatching { tolerantMapper.readTree(jsonStr) }.getOrNull() ?: return null
        val apiToken = root.get("apiToken")?.asText("").orEmpty().trim()
        val api = root.get("api")?.asText("").orEmpty().trim()
        val metaApi = root.get("metaApi")?.asText("").orEmpty().trim()
        val streamBase = root.get("streamBase")?.asText("").orEmpty().trim()
        if (api.isEmpty() && streamBase.isEmpty()) return null
        return PelisJuanitaVidsrcConfig(
            mediaType = root.get("mediaType")?.asText("").orEmpty().trim(),
            apiToken = apiToken,
            turnstile = root.get("turnstile")?.asBoolean(false) == true,
            cstCaptcha = root.get("cstCaptcha")?.asBoolean(false) == true,
            api = api,
            metaApi = metaApi,
            streamBase = streamBase,
            season = root.get("season")?.asInt(0) ?: 0,
            episode = root.get("episode")?.asInt(0) ?: 0,
            subUrl = root.get("subUrl")?.asText("").orEmpty().trim(),
            subLabel = root.get("subLabel")?.asText("").orEmpty().trim(),
            subLang = root.get("subLang")?.asText("").orEmpty().trim(),
            playerOrigin = runCatching { originOf(finalUrl) }.getOrDefault(""),
        )
    }

    fun parseMetaStreamApiHost(jsonText: String): String {
        val root = runCatching { tolerantMapper.readTree(jsonText) }.getOrNull() ?: return ""
        val data = root.get("data") ?: root
        return data.get("stream_urls_api_host")?.asText("").orEmpty().trim()
    }

    fun buildStreamApiUrl(config: PelisJuanitaVidsrcConfig, streamUrlsApiHost: String = ""): String? {
        var base = config.api.trim()
        if (base.isEmpty()) {
            val sb = config.streamBase.trim()
            if (sb.isEmpty()) return null
            base = buildString {
                append(sb)
                if (config.season > 0 && !sb.contains("season=", ignoreCase = true)) {
                    append(if (sb.contains('?')) '&' else '?')
                    append("season=").append(config.season)
                }
                if (config.episode > 0 && !sb.contains("episode=", ignoreCase = true)) {
                    append(if (contains('?')) '&' else '?')
                    append("episode=").append(config.episode)
                }
                if (!sb.contains("stream_urls", ignoreCase = true)) {
                    append(if (contains('?')) '&' else '?')
                    append("stream_urls")
                }
            }
        }
        val swapped = swapStreamApiHost(base, streamUrlsApiHost)
        if (config.apiToken.isBlank() || swapped.contains("api_token=", ignoreCase = true)) {
            return swapped
        }
        val sep = if (swapped.contains('?')) '&' else '?'
        return "$swapped${sep}api_token=${URLEncoder.encode(config.apiToken, "UTF-8")}"
    }

    fun swapStreamApiHost(apiUrl: String, streamUrlsApiHost: String): String {
        val hostOnly = streamUrlsApiHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trim()
        if (hostOnly.isEmpty()) return apiUrl
        val httpUrl = apiUrl.toHttpUrlOrNull() ?: return apiUrl
        return httpUrl.newBuilder().host(hostOnly).build().toString()
    }

    fun parseStreamApiResponse(jsonText: String, baseUrl: String = ""): PelisJuanitaVidsrcApiData? {
        val root = runCatching { tolerantMapper.readTree(jsonText) }.getOrNull() ?: return null
        val data = root.get("data") ?: return null

        val plainUrls = mutableListOf<String>()
        var encStr = ""
        val streamUrlsNode = data.get("stream_urls")
        when {
            streamUrlsNode == null || streamUrlsNode.isNull -> Unit
            streamUrlsNode.isArray -> {
                for (item in streamUrlsNode) {
                    val u = item.asText("").trim()
                    if (u.startsWith("http", ignoreCase = true)) plainUrls.add(u)
                }
            }
            streamUrlsNode.isTextual -> {
                val txt = streamUrlsNode.asText("").trim()
                if (txt.startsWith("http://", ignoreCase = true) || txt.startsWith("https://", ignoreCase = true)) {
                    txt.split('\r', '\n', ',').map { it.trim() }.filterTo(plainUrls) { it.startsWith("http") }
                } else {
                    encStr = txt
                }
            }
        }

        val castUrls = mutableListOf<String>()
        val castNode = data.get("cast_urls")
        if (castNode != null && castNode.isArray) {
            for (item in castNode) {
                val u = item.asText("").trim()
                if (u.startsWith("http", ignoreCase = true)) castUrls.add(u)
            }
        }

        val vsNode = root.get("vs") ?: data.get("vs")
        val rawWasmUrl = vsNode?.get("wasm_url")?.asText("").orEmpty().trim()
        val resolvedWasmUrl = if (rawWasmUrl.isNotEmpty() && baseUrl.isNotEmpty()) {
            resolveUrl(baseUrl, rawWasmUrl).orEmpty()
        } else {
            rawWasmUrl
        }
        val wasmBase64 = vsNode?.get("wasm")?.asText("").orEmpty().trim()

        val apiHost = data.get("stream_urls_api_host")?.asText("").orEmpty().trim()

        val subs = mutableListOf<PelisJuanitaVidsrcSubtitleCandidate>()
        val defaultSubs = data.get("default_subs") ?: root.get("default_subs")
        if (defaultSubs != null && defaultSubs.isArray) {
            for (s in defaultSubs) {
                val u = (s.get("file")?.asText("") ?: s.get("url")?.asText("") ?: "").trim()
                if (!u.startsWith("http", ignoreCase = true)) continue
                val lbl = (s.get("label")?.asText("") ?: s.get("lang")?.asText("") ?: "Subtítulos").trim()
                subs.add(buildSubtitleCandidate(lbl, u))
            }
        }

        if (plainUrls.isEmpty() && encStr.isEmpty() && castUrls.isEmpty()) return null
        return PelisJuanitaVidsrcApiData(
            encryptedStreamUrls = encStr,
            plainStreamUrls = plainUrls,
            castUrls = castUrls,
            wasmUrl = resolvedWasmUrl,
            wasmBase64 = wasmBase64,
            streamUrlsApiHost = apiHost,
            defaultSubtitles = subs,
        )
    }

    fun parseTokenResponse(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            val node = runCatching { tolerantMapper.readTree(trimmed) }.getOrNull()
            if (node != null) {
                if (node.isTextual) return node.asText("").trim()
                if (node.isObject) {
                    for (field in listOf("token", "data", "string", "result")) {
                        val v = node.get(field)?.asText("")?.trim().orEmpty()
                        if (v.isNotEmpty()) return v
                    }
                }
            }
        }
        return trimmed
    }

    fun toMedia3PlaylistUrl(rawStreamUrl: String, token: String = ""): String {
        val trimmed = rawStreamUrl.trim()
        val rewritten = if (PL_SEGMENT_REGEX.containsMatchIn(trimmed)) {
            PL_SEGMENT_REGEX.replaceFirst(trimmed, "/pli/")
        } else {
            trimmed
        }
        val cleanToken = token.trim()
        if (cleanToken.isEmpty() || rewritten.contains("token=", ignoreCase = true)) {
            return rewritten
        }
        val sep = if (rewritten.contains('?')) '&' else '?'
        return "$rewritten${sep}token=${URLEncoder.encode(cleanToken, "UTF-8")}"
    }

    fun rewriteInterceptedPlaylistUrl(requestUrl: String): String? {
        if (!INTERCEPTED_PL_REGEX.containsMatchIn(requestUrl)) return null
        return if (PL_SEGMENT_REGEX.containsMatchIn(requestUrl)) {
            PL_SEGMENT_REGEX.replaceFirst(requestUrl, "/pli/")
        } else {
            requestUrl
        }
    }

    fun extractHighestHeight(masterPlaylist: String): Int? =
        STREAM_INF_HEIGHT_REGEX.findAll(masterPlaylist)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()

    fun extractAssignedJsonObject(html: String, assignRegex: Regex): String? {
        val match = assignRegex.find(html) ?: return null
        val startBrace = match.range.last
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startBrace until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return html.substring(startBrace, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault(pair.substring(0, eq))
            val v = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))
            if (k.isNotEmpty() && v.isNotEmpty()) {
                out[k] = v
            }
        }
        return out
    }
}

class PelisJuanitaVidsrcEmbedSuExtractor : PelisJuanitaVidSrcSuExtractor() {
    override val name = "Vidsrc"
    override val mainUrl = "https://vidsrc-embed.su"
}

class PelisJuanitaVidsrcEmbedRuExtractor : PelisJuanitaVidSrcSuExtractor() {
    override val name = "Vidsrc"
    override val mainUrl = "https://vidsrc-embed.ru"
}

class PelisJuanitaVidsrcMeRuExtractor : PelisJuanitaVidSrcSuExtractor() {
    override val name = "Vidsrc"
    override val mainUrl = "https://vidsrcme.ru"
}

class PelisJuanitaVsrcSuExtractor : PelisJuanitaVidSrcSuExtractor() {
    override val name = "Vidsrc"
    override val mainUrl = "https://vsrc.su"
}

open class PelisJuanitaVidSrcSuExtractor : ExtractorApi() {
    override val name = "Vidsrc"
    override val mainUrl = "https://vsembed.su"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extract(url, referer, name, subtitleCallback, callback)
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val WEBVIEW_TIMEOUT_MS = 16_000L

        private val webViewSlots = Semaphore(1)

        private data class HttpTextResult(
            val code: Int,
            val finalUrl: String,
            val body: String,
        )

        private data class HttpBytesResult(
            val code: Int,
            val finalUrl: String,
            val bytes: ByteArray,
        )

        private data class HandshakeResolution(
            val landingUrl: String,
            val playerUrl: String,
            val config: PelisJuanitaVidsrcConfig,
        )

        private class ScopedCookieJar : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isEmpty()) return
                val hostKey = url.host.lowercase()
                val now = System.currentTimeMillis()
                val list = store.computeIfAbsent(hostKey) { Collections.synchronizedList(mutableListOf()) }
                synchronized(list) {
                    for (cookie in cookies) {
                        list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        if (cookie.expiresAt >= now) {
                            list.add(cookie)
                        }
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val now = System.currentTimeMillis()
                val out = mutableListOf<Cookie>()
                for ((_, list) in store) {
                    synchronized(list) {
                        list.removeAll { it.expiresAt < now }
                        for (c in list) {
                            if (c.matches(url)) out.add(c)
                        }
                    }
                }
                return out
            }
        }

        private fun newScopedClient(): OkHttpClient =
            app.baseClient.newBuilder()
                .cookieJar(ScopedCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()

        suspend fun extract(
            url: String,
            referer: String?,
            sourceName: String = "Vidsrc",
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean = withContext(Dispatchers.IO) {
            val client = newScopedClient()
            val emittedSubs = Collections.synchronizedSet(mutableSetOf<String>())

            suspend fun emitSubtitleCandidates(candidates: List<PelisJuanitaVidsrcSubtitleCandidate>) {
                for (cand in candidates) {
                    val chosen = pickReachableSubtitleUrl(client, cand) ?: continue
                    if (emittedSubs.add(chosen)) {
                        subtitleCallback(newSubtitleFile(cand.label, chosen))
                    }
                }
            }

            emitSubtitleCandidates(PelisJuanitaVidSrcSuParser.parseSubtitlesFromEmbedUrl(url))

            val effReferer = referer?.takeIf { it.isNotBlank() } ?: "${PelisJuanitaMarkup.MAIN_URL}/"
            val handshake = performHandshake(client, url, effReferer) ?: return@withContext false

            if (handshake.config.subUrl.isNotBlank()) {
                val lbl = PelisJuanitaVidSrcSuParser.resolveSubtitleLabel(
                    handshake.config.subLabel,
                    handshake.config.subLang,
                )
                emitSubtitleCandidates(listOf(PelisJuanitaVidSrcSuParser.buildSubtitleCandidate(lbl, handshake.config.subUrl)))
            }

            val config = handshake.config
            val playerOrigin = config.playerOrigin.ifBlank { PelisJuanitaVidSrcSuParser.originOf(handshake.playerUrl) }

            if (config.apiToken.isNotBlank()) {
                val okOpen = extractViaApiToken(
                    client = client,
                    config = config,
                    playerOrigin = playerOrigin,
                    sourceName = sourceName,
                    emitSubtitleCandidates = { cands -> emitSubtitleCandidates(cands) },
                    callback = callback,
                )
                if (okOpen) return@withContext true
            }

            // Gated path (or API fallback): run Turnstile-aware WebView if an Android Context is available.
            val androidCtx = PelisJuanitaAppContextHolder.getContext() ?: return@withContext false
            val freshHandshake = performHandshake(client, url, effReferer) ?: handshake
            val captured = webViewSlots.withPermit {
                withTimeoutOrNull(WEBVIEW_TIMEOUT_MS + 2_000L) {
                    resolveViaWebView(
                        context = androidCtx,
                        playerUrl = freshHandshake.playerUrl,
                        landingUrl = freshHandshake.landingUrl,
                    )
                }
            } ?: return@withContext false

            val wvOrigin = runCatching { PelisJuanitaVidSrcSuParser.originOf(freshHandshake.playerUrl) }
                .getOrDefault(playerOrigin)
            val wvHeaders = mapOf(
                "Origin" to wvOrigin,
                "Referer" to "$wvOrigin/",
                "User-Agent" to captured.second.ifBlank { DESKTOP_USER_AGENT },
            )
            emitPlayablePliUrl(
                client = client,
                pliUrl = captured.first,
                playerOrigin = wvOrigin,
                streamHeaders = wvHeaders,
                sourceName = sourceName,
                callback = callback,
            )
        }

        private suspend fun performHandshake(
            client: OkHttpClient,
            embedUrl: String,
            referer: String,
        ): HandshakeResolution? {
            val embedRes = httpGetText(
                client = client,
                url = embedUrl,
                referer = referer,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ) ?: return null
            if (embedRes.code !in 200..299 || embedRes.body.isBlank()) return null

            val step1 = PelisJuanitaVidSrcSuParser.parseStep1(embedRes.body, embedRes.finalUrl) ?: return null
            var landingUrl = step1.iframeUrl
            if (step1.apiPath != null) {
                val vsSrcRes = httpGetText(
                    client = client,
                    url = step1.apiPath,
                    referer = embedRes.finalUrl,
                    accept = "application/json, text/plain, */*",
                )
                val parsedSrc = vsSrcRes?.takeIf { it.code in 200..299 }
                    ?.let { PelisJuanitaVidSrcSuParser.parseSrcFromVsSrcJson(it.body, embedRes.finalUrl) }
                if (!parsedSrc.isNullOrBlank()) {
                    landingUrl = parsedSrc
                }
            }
            val finalLandingTarget = landingUrl ?: return null

            val landingReferer = runCatching { "${PelisJuanitaVidSrcSuParser.originOf(embedRes.finalUrl)}/" }
                .getOrDefault("https://vsembed.su/")
            val landingRes = httpGetText(
                client = client,
                url = finalLandingTarget,
                referer = landingReferer,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ) ?: return null
            if (landingRes.code !in 200..299 || landingRes.body.isBlank()) return null

            // Landing URL may already render player directly OR embed window.CFG.playerUrl.
            PelisJuanitaVidSrcSuParser.parsePlayerConfig(landingRes.body, landingRes.finalUrl)?.let { cfg ->
                return HandshakeResolution(
                    landingUrl = landingRes.finalUrl,
                    playerUrl = landingRes.finalUrl,
                    config = cfg,
                )
            }

            val playerUrl = PelisJuanitaVidSrcSuParser.parsePlayerUrlFromLanding(landingRes.body, landingRes.finalUrl)
                ?: return null
            val playerRes = httpGetText(
                client = client,
                url = playerUrl,
                referer = landingRes.finalUrl,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ) ?: return null
            if (playerRes.code !in 200..299 || playerRes.body.isBlank()) return null

            val config = PelisJuanitaVidSrcSuParser.parsePlayerConfig(playerRes.body, playerRes.finalUrl)
                ?: return null
            return HandshakeResolution(
                landingUrl = landingRes.finalUrl,
                playerUrl = playerRes.finalUrl,
                config = config,
            )
        }

        private suspend fun extractViaApiToken(
            client: OkHttpClient,
            config: PelisJuanitaVidsrcConfig,
            playerOrigin: String,
            sourceName: String,
            emitSubtitleCandidates: suspend (List<PelisJuanitaVidsrcSubtitleCandidate>) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val apiReferer = "$playerOrigin/"
            var streamHost = ""
            if (config.metaApi.isNotBlank()) {
                val metaRes = withTimeoutOrNull(4_500L) {
                    httpGetText(
                        client = client,
                        url = config.metaApi,
                        referer = apiReferer,
                        origin = playerOrigin,
                        accept = "application/json, text/plain, */*",
                    )
                }
                if (metaRes != null && metaRes.code in 200..299) {
                    streamHost = PelisJuanitaVidSrcSuParser.parseMetaStreamApiHost(metaRes.body)
                }
            }

            val streamApiUrl = PelisJuanitaVidSrcSuParser.buildStreamApiUrl(config, streamHost) ?: return false
            val streamRes = httpGetText(
                client = client,
                url = streamApiUrl,
                referer = apiReferer,
                origin = playerOrigin,
                accept = "application/json, text/plain, */*",
            ) ?: return false
            if (streamRes.code !in 200..299 || streamRes.body.isBlank()) return false

            val apiData = PelisJuanitaVidSrcSuParser.parseStreamApiResponse(streamRes.body, streamRes.finalUrl)
                ?: return false
            if (apiData.defaultSubtitles.isNotEmpty()) {
                emitSubtitleCandidates(apiData.defaultSubtitles)
            }

            val rawStreams = mutableListOf<String>()
            rawStreams.addAll(apiData.plainStreamUrls)

            if (rawStreams.isEmpty() && apiData.encryptedStreamUrls.isNotBlank()) {
                val wasmBytes = when {
                    apiData.wasmBase64.isNotBlank() ->
                        runCatching { PelisJuanitaCrypto.decodeBase64Flexible(apiData.wasmBase64) }.getOrNull()
                    apiData.wasmUrl.isNotBlank() -> {
                        val wRes = httpGetBytes(
                            client = client,
                            url = apiData.wasmUrl,
                            referer = apiReferer,
                            origin = playerOrigin,
                        )
                        wRes?.takeIf { it.code in 200..299 && it.bytes.isNotEmpty() }?.bytes
                    }
                    else -> null
                }
                if (wasmBytes != null) {
                    rawStreams.addAll(
                        PelisJuanitaWasmChaCha.decryptStreamUrls(apiData.encryptedStreamUrls, wasmBytes),
                    )
                }
            }

            if (rawStreams.isEmpty() && apiData.castUrls.isNotEmpty()) {
                // cast_urls share the exact `/plc/<P>` hash as `/pl/<P>`; rewriting to `/pli/<P>` works with /generate.php tokens.
                rawStreams.addAll(apiData.castUrls)
            }

            if (rawStreams.isEmpty()) return false

            val streamHeaders = mapOf(
                "Origin" to playerOrigin,
                "Referer" to apiReferer,
                "User-Agent" to DESKTOP_USER_AGENT,
            )
            val tokenCache = HashMap<String, String>()
            var emittedAny = false
            for ((index, rawUrl) in rawStreams.distinct().take(3).withIndex()) {
                val streamOrigin = runCatching { PelisJuanitaVidSrcSuParser.originOf(rawUrl) }.getOrNull() ?: continue
                val token = tokenCache.getOrPut(streamOrigin) {
                    fetchStreamToken(
                        client = client,
                        streamOrigin = streamOrigin,
                        apiToken = config.apiToken,
                        playerOrigin = playerOrigin,
                    )
                }
                if (token.isBlank()) continue
                val pliUrl = PelisJuanitaVidSrcSuParser.toMedia3PlaylistUrl(rawUrl, token)
                val label = if (index == 0) sourceName else "$sourceName (Mirror ${index + 1})"
                val ok = emitPlayablePliUrl(
                    client = client,
                    pliUrl = pliUrl,
                    playerOrigin = playerOrigin,
                    streamHeaders = streamHeaders,
                    sourceName = label,
                    callback = callback,
                )
                if (ok) {
                    emittedAny = true
                    break
                }
            }
            return emittedAny
        }

        private suspend fun fetchStreamToken(
            client: OkHttpClient,
            streamOrigin: String,
            apiToken: String,
            playerOrigin: String,
        ): String {
            val genUrl = buildString {
                append(streamOrigin.trimEnd('/'))
                append("/generate.php")
                if (apiToken.isNotBlank()) {
                    append("?api_token=").append(URLEncoder.encode(apiToken, "UTF-8"))
                }
            }
            val res = httpGetText(
                client = client,
                url = genUrl,
                referer = "$playerOrigin/",
                origin = playerOrigin,
                accept = "*/*",
            ) ?: return ""
            if (res.code !in 200..299) return ""
            return PelisJuanitaVidSrcSuParser.parseTokenResponse(res.body)
        }

        private suspend fun emitPlayablePliUrl(
            client: OkHttpClient,
            pliUrl: String,
            playerOrigin: String,
            streamHeaders: Map<String, String>,
            sourceName: String,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            val m3u8Res = withTimeoutOrNull(5_000L) {
                httpGetText(
                    client = client,
                    url = pliUrl,
                    referer = "$playerOrigin/",
                    origin = playerOrigin,
                    userAgent = streamHeaders["User-Agent"] ?: DESKTOP_USER_AGENT,
                    accept = "*/*",
                )
            } ?: return false
            if (m3u8Res.code !in 200..299 || !m3u8Res.body.contains("#EXTM3U", ignoreCase = true)) {
                return false
            }

            val body = m3u8Res.body
            val referer = "$playerOrigin/"
            val hasSeparateAudio = body.contains("#EXT-X-MEDIA:TYPE=AUDIO", ignoreCase = true)
            if (!hasSeparateAudio && body.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val variants = withTimeoutOrNull(4_500L) {
                    runCatching {
                        M3u8Helper.generateM3u8(sourceName, pliUrl, referer, headers = streamHeaders)
                    }.getOrElse { emptyList() }
                }.orEmpty()
                if (variants.isNotEmpty()) {
                    val sampleCheck = withTimeoutOrNull(3_000L) {
                        httpGetText(
                            client = client,
                            url = variants.first().url,
                            referer = referer,
                            origin = playerOrigin,
                            userAgent = streamHeaders["User-Agent"] ?: DESKTOP_USER_AGENT,
                            accept = "*/*",
                        )
                    }
                    if (sampleCheck != null && sampleCheck.code in 200..299 &&
                        sampleCheck.body.contains("#EXTM3U", ignoreCase = true)
                    ) {
                        variants.forEach(callback)
                        return true
                    }
                }
            }

            val topHeight = PelisJuanitaVidSrcSuParser.extractHighestHeight(body)
            val q = topHeight?.let { getQualityFromName(it.toString()) } ?: Qualities.P1080.value
            callback(
                newExtractorLink(sourceName, sourceName, pliUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = streamHeaders
                    this.quality = q
                },
            )
            return true
        }

        private suspend fun pickReachableSubtitleUrl(
            client: OkHttpClient,
            cand: PelisJuanitaVidsrcSubtitleCandidate,
        ): String? {
            val direct = cand.directUrl
            if (direct != null) {
                if (probeSubtitleReachable(client, direct)) return direct
                if (probeSubtitleReachable(client, cand.rawUrl)) return cand.rawUrl
                return direct
            }
            return cand.rawUrl
        }

        private suspend fun probeSubtitleReachable(client: OkHttpClient, url: String): Boolean {
            val res = withTimeoutOrNull(3_000L) {
                httpGetText(client = client, url = url, referer = "${PelisJuanitaMarkup.MAIN_URL}/", accept = "*/*")
            } ?: return false
            if (res.code !in 200..299 || res.body.isBlank()) return false
            return res.body.contains("-->") || res.body.contains("WEBVTT", ignoreCase = true)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun resolveViaWebView(
            context: Context,
            playerUrl: String,
            landingUrl: String,
        ): Pair<String, String>? = suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            var dialog: Dialog? = null
            var webView: WebView? = null
            var uaString = ""

            fun cleanup() {
                handler.post {
                    runCatching { CookieManager.getInstance().flush() }
                    runCatching { dialog?.dismiss() }
                    dialog = null
                    runCatching {
                        webView?.stopLoading()
                        (webView?.parent as? ViewGroup)?.removeView(webView)
                        webView?.destroy()
                    }
                    webView = null
                }
            }

            fun finish(result: Pair<String, String>?) {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                }
            }

            handler.post {
                if (finished.get()) return@post
                runCatching {
                    val activity = PelisJuanitaAppContextHolder.getCurrentActivity()
                    val hostContext: Context = activity ?: context.applicationContext ?: context
                    val wv = WebView(hostContext)
                    webView = wv

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    with(wv.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Keep default system WebView UA intact so Cloudflare Turnstile matches JS navigator properties.
                    }
                    uaString = wv.settings.userAgentString.orEmpty()

                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        val dlg = Dialog(activity)
                        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
                        dlg.setCancelable(false)
                        dlg.setCanceledOnTouchOutside(false)
                        dlg.window?.apply {
                            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                            addFlags(
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            )
                            setLayout(1, 1)
                            setGravity(Gravity.TOP or Gravity.START)
                        }
                        dlg.setContentView(
                            wv,
                            ViewGroup.LayoutParams(1, 1),
                        )
                        dialog = dlg
                        dlg.show()
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.confirm()
                            return true
                        }

                        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.cancel()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            defaultValue: String?,
                            result: JsPromptResult?,
                        ): Boolean {
                            result?.cancel()
                            return true
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            val rewritten = PelisJuanitaVidSrcSuParser.rewriteInterceptedPlaylistUrl(reqUrl)
                            if (rewritten != null) {
                                finish(rewritten to uaString)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            if (finished.get() || view == null) return
                            fun pollDom(attempt: Int) {
                                if (finished.get()) return
                                view.evaluateJavascript(
                                    "(function(){var e=document.querySelector('#err');var et=document.querySelector('#errText');var ts=document.querySelector('#ts-err');return JSON.stringify({err:!!(e&&!e.hidden),et:et?et.textContent:'',ts:ts?ts.textContent:''});})()",
                                ) { raw ->
                                    if (finished.get()) return@evaluateJavascript
                                    val txt = raw.orEmpty()
                                    val failed = txt.contains("Verification failed", ignoreCase = true) ||
                                        txt.contains("failed", ignoreCase = true) ||
                                        txt.contains("unavailable", ignoreCase = true)
                                    if (failed) {
                                        finish(null)
                                    } else if (attempt < 24) {
                                        handler.postDelayed({ pollDom(attempt + 1) }, 650L)
                                    }
                                }
                            }
                            handler.postDelayed({ pollDom(0) }, 1_200L)
                        }
                    }

                    handler.postDelayed({ finish(null) }, WEBVIEW_TIMEOUT_MS)
                    wv.loadUrl(playerUrl, mapOf("Referer" to landingUrl))
                }.onFailure {
                    finish(null)
                }
            }
        }

        private suspend fun httpGetText(
            client: OkHttpClient,
            url: String,
            referer: String,
            origin: String? = null,
            userAgent: String = DESKTOP_USER_AGENT,
            accept: String = "*/*",
        ): HttpTextResult? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", accept)
                    .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                    .header("Referer", referer)
                    .apply { if (!origin.isNullOrBlank()) header("Origin", origin) }
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    HttpTextResult(
                        code = res.code,
                        finalUrl = res.request.url.toString(),
                        body = res.body?.string().orEmpty(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        private suspend fun httpGetBytes(
            client: OkHttpClient,
            url: String,
            referer: String,
            origin: String? = null,
            userAgent: String = DESKTOP_USER_AGENT,
        ): HttpBytesResult? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*")
                    .header("Referer", referer)
                    .apply { if (!origin.isNullOrBlank()) header("Origin", origin) }
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    HttpBytesResult(
                        code = res.code,
                        finalUrl = res.request.url.toString(),
                        bytes = res.body?.bytes() ?: ByteArray(0),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}
