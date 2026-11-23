package com.menorah.repelis24

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RepelisPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(RepelisProvider()) }
}
