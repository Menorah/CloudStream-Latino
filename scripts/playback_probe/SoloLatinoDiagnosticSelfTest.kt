package com.menorah.sololatino

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.*

/** Injected only in CI; not included in the distributed diagnostic plugin. */
object SoloLatinoDiagnosticSelfTest {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(100_000) {
                    val provider = SoloLatinoAccessDiagnostic(context)
                    val result = provider.getMainPage(1, MainPageRequest("Diagnóstico", "local", false))
                    val items = result.items.single().list
                    check(items.size == 4) { "expected four diagnostic cards" }
                    val names = items.map { it.name }
                    check(names[0].startsWith("Portada:"))
                    check(names[1].startsWith("Episodio:"))
                    check(names[2].startsWith("Embed69"))
                    check(names[3].startsWith("WebView episodio:"))
                    val detail = provider.load("diagnostico-0")
                    check(detail.plot?.contains("no es el complemento final") == true) { "missing diagnostic explanation" }
                    names.forEach { Log.i("CSSoloDiagnostic", it) }
                }
                Log.i("CSSoloDiagnostic", "PASS provider and report")
            } catch (t: Throwable) {
                Log.e("CSSoloDiagnostic", "FAIL ${t.javaClass.simpleName}")
            }
        }
    }
}
