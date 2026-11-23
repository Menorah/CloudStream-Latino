package com.menorah.verani

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VerAniPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VerAniProvider(context))
    }
}
