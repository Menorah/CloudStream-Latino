version = 5

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Películas y series en español latino, castellano y subtitulado de repelis24.cz."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "es-419"
    iconUrl = "https://repelis24.cz/wp-content/uploads/2026/04/cropped-repelis.top_.png"
}
