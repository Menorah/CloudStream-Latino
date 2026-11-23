version = 2

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Anime y películas de AnimeOnline en español."
    authors = listOf("Menorah")
    status = 3
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "es"
}
