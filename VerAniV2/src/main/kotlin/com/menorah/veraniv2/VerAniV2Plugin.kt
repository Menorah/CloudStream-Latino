package com.menorah.veraniv2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VerAniV2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VerAniV2Provider())
        registerExtractorAPI(VerAniV2AnimeAv1Extractor())
        registerExtractorAPI(VerAniV2TurboEmbedExtractor())
        registerExtractorAPI(VerAniV2VidaraExtractor())
        registerExtractorAPI(VerAniV2ZillaExtractor())
        registerExtractorAPI(VerAniV2VoeExtractor())
        registerExtractorAPI(VerAniV2Mp4UploadExtractor())
        registerExtractorAPI(VerAniV2MxDropExtractor())
        registerExtractorAPI(VerAniV2ByseExtractor())
        registerExtractorAPI(VerAniV2ByseKozeExtractor())
        registerExtractorAPI(VerAniV2ByseBuhoExtractor())
        registerExtractorAPI(VerAniV2ByseSxExtractor())
    }
}
