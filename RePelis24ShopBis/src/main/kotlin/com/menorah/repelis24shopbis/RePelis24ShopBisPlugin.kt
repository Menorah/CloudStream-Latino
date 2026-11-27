package com.menorah.repelis24shopbis

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RePelis24ShopBisPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RePelis24ShopBisProvider())
        registerExtractorAPI(NuploadMyExtractor())
        registerExtractorAPI(RePelis24ShopVoeExtractor())
    }
}
