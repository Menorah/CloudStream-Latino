version = 4

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "VerAni.me — Catálogo completo de animes, estrenos semanales, audio latino, castellano, subtitulado y películas de verani.me con extractores nativos."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "es"
    iconUrl = "https://verani.me/wp-content/uploads/2026/01/Gemini_Generated_Image_y27f1qy27f1qy27f-1-e1767498152326.png"
}
