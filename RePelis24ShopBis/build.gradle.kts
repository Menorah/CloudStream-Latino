version = 1

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Películas y series en HD con audio latino, castellano y subtitulado de repelis24.shop."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "es"
    iconUrl = "https://repelis24.shop/images/repelis24-icon.png"
}
