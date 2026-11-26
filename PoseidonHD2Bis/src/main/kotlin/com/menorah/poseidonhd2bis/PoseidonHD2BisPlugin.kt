package com.menorah.poseidonhd2bis

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PoseidonHD2BisPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PoseidonHD2BisProvider())
        registerExtractorAPI(PoseidonVoeExtractor())
        registerExtractorAPI(PoseidonStreamWishExtractor())
        registerExtractorAPI(PoseidonMedixiruExtractor())
        registerExtractorAPI(PoseidonHglinkExtractor())
        registerExtractorAPI(PoseidonVsembedExtractor())
        registerExtractorAPI(PoseidonVidHideExtractor())
        registerExtractorAPI(PoseidonVidHideProExtractor())
        registerExtractorAPI(PoseidonCallistaniseExtractor())
        registerExtractorAPI(PoseidonByseExtractor())
    }
}
