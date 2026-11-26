package com.menorah.poseidon

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PoseidonPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PoseidonProvider())
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
