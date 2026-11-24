version = 1

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
cloudstream {
    description = "SoloLatino: módulo experimental, no publicar."
    authors = listOf("Menorah")
    status = 3
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "es"
}
android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }
