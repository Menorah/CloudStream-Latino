package com.menorah.repelis24czbis

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Repelis24CZBisPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Repelis24CZBisProvider())
        registerExtractorAPI(Repelis24CZVimeosExtractor())
        registerExtractorAPI(Repelis24CZVoeExtractor())
    }
}
