version = 1

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
cloudstream {
    description = "Películas y series de RePelis24.cz en español."
    authors = listOf("Menorah")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "es"
}
