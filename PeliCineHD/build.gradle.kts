version = 4

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
cloudstream {
    description = "Películas y series de PeliCineHD en español latino."
    authors = listOf("Menorah")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "es"
}
android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }
