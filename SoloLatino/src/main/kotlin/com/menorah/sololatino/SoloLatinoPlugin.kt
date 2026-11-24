package com.menorah.sololatino

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SoloLatinoPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(SoloLatinoProvider())
    }
}
