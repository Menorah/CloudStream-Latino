version = 3

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Películas y series en Full HD con audio latino e inglés de cinecalidad.am."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "es"
    iconUrl = "https://www.cinecalidad.am/wp-content/themes/Cinecalidad/assets/img/logo.svg"
}
