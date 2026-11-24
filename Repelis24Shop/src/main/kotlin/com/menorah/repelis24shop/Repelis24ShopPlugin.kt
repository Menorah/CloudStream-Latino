package com.menorah.repelis24shop

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Repelis24ShopPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(Repelis24ShopProvider()) }
}
