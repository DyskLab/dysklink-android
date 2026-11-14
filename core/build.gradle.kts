// Pure Kotlin shared by the Android app and the desktop apps: protocol, TLS pairing, connections, and file transfers.
// DyskLab/dysklink-desktop builds it too, from its android submodule, so it must stay free of Android code and take its
// plugin versions from the root build (both repositories declare the same ones).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    // WebSocket client for OBS (obs-websocket 5).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    // The Mac integration test reads its port and folders from these.
    listOf("DYSKLINK_IT_PORT", "DYSKLINK_IT_DOWNLOADS", "DYSKLINK_IT_PHOTOS").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
