package com.menorah.pelis182

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Pelis182Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pelis182Provider())
        registerExtractorAPI(PaulinitoExtractor())
    }
}
