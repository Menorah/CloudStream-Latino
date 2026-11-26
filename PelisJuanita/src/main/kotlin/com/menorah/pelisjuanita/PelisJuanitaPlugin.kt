package com.menorah.pelisjuanita

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PelisJuanitaPlugin : Plugin() {
    override fun load(context: Context) {
        val sessionStore = AndroidPelisJuanitaSessionStore(context)
        val provider = PelisJuanitaProvider(context, sessionStore)
        openSettings = { ctx ->
            PelisJuanitaVerificationDialog.show(ctx, sessionStore) {
                provider.clearCaches()
            }
        }

        registerMainAPI(provider)
        registerExtractorAPI(PelisJuanitaVimeusExtractor())
        registerExtractorAPI(PelisJuanitaVimeosExtractor())
        registerExtractorAPI(PelisJuanitaVimeosWwwExtractor())
        registerExtractorAPI(PelisJuanitaFastserverMoviesExtractor())
        registerExtractorAPI(PelisJuanitaFastserverSeriesExtractor())
        registerExtractorAPI(PelisJuanitaSavefilesExtractor())
        registerExtractorAPI(PelisJuanitaLplaygoExtractor())
        registerExtractorAPI(PelisJuanitaByseExtractor())
        registerExtractorAPI(PelisJuanitaBysejikuarExtractor())
        registerExtractorAPI(PelisJuanitaByseSxExtractor())
        registerExtractorAPI(PelisJuanitaByseBuhoExtractor())
        registerExtractorAPI(PelisJuanitaByseZejataosExtractor())
        registerExtractorAPI(PelisJuanitaByseVepoinExtractor())
        registerExtractorAPI(PelisJuanitaByseQekahoExtractor())
        registerExtractorAPI(PelisJuanitaN1mwqExtractor())
        registerExtractorAPI(PelisJuanitaMixDropTopExtractor())
        registerExtractorAPI(PelisJuanitaMxDropClickExtractor())
        registerExtractorAPI(PelisJuanitaMixDropAgExtractor())
        registerExtractorAPI(PelisJuanitaMixDropCoExtractor())
        registerExtractorAPI(PelisJuanitaMixDropPsExtractor())
        registerExtractorAPI(PelisJuanitaOisauhExtractor())
        registerExtractorAPI(PelisJuanitaVoeExtractor())
        registerExtractorAPI(PelisJuanitaHglinkExtractor())
        registerExtractorAPI(PelisJuanitaMedixiruExtractor())
        registerExtractorAPI(PelisJuanitaVsembedExtractor())
        registerExtractorAPI(PelisJuanitaFlaswishExtractor())
        registerExtractorAPI(PelisJuanitaHlswishExtractor())
        registerExtractorAPI(PelisJuanitaStreamwishSiteExtractor())
        registerExtractorAPI(PelisJuanitaPlayerwishExtractor())
        registerExtractorAPI(PelisJuanitaDhcplayExtractor())
        registerExtractorAPI(PelisJuanitaStrwishExtractor())
        registerExtractorAPI(PelisJuanitaStreamWishExtractor())
        registerExtractorAPI(PelisJuanitaMorenciusExtractor())
        registerExtractorAPI(PelisJuanitaCallistaniseExtractor())
        registerExtractorAPI(PelisJuanitaMinochinosExtractor())
        registerExtractorAPI(PelisJuanitaMovearnpreExtractor())
        registerExtractorAPI(PelisJuanitaDingtezuniExtractor())
        registerExtractorAPI(PelisJuanitaMivalyoExtractor())
        registerExtractorAPI(PelisJuanitaTaylorplayerExtractor())
        registerExtractorAPI(PelisJuanitaVidHideProExtractor())
        registerExtractorAPI(PelisJuanitaVidHideExtractor())
    }
}
