package com.menorah.poseidon

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PoseidonPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(PoseidonProvider()) }
}
