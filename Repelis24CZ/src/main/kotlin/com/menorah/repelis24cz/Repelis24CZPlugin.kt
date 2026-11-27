package com.menorah.repelis24cz

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Repelis24CZPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Repelis24CZProvider())
        registerExtractorAPI(Repelis24CZVimeosExtractor())
        registerExtractorAPI(Repelis24CZVoeExtractor())
    }
}
