plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "id.phonelink.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.dysklink.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0"
    }

    // The release key stays outside the repository. scripts/release-apk.sh passes its path and password,
    // taken from the macOS Keychain, so they're never written to a file here.
    val releaseStore = System.getenv("DYSKLINK_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("DYSKLINK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DYSKLINK_KEY_ALIAS") ?: "dysklink"
                keyPassword = System.getenv("DYSKLINK_KEYSTORE_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Without the key the release APK comes out unsigned, which Android refuses to install.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    // android.util.Log does nothing in JVM tests, so ConnectionManager can run there.
    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    // Google's code scanner UI in Play services: no camera permission, and the model isn't bundled in the APK.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Document edges, cropping, and filters, and the text inside scans. Both come through Play Services, so the app stays small.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // WebSocket client for OBS (obs-websocket 5).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
