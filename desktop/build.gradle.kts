import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// DyskLink for Windows and Linux: one Kotlin app on the shared :core, the computer side of the protocol.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

val appVersion = "1.0.0"

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Bonjour/mDNS in plain Java, so neither Windows nor Linux needs Bonjour or Avahi installed.
    implementation("org.jmdns:jmdns:3.6.5")

    testImplementation(kotlin("test-junit"))
}

compose.desktop {
    application {
        mainClass = "id.dysklink.desktop.MainKt"
        jvmArgs += listOf("-Ddysklink.version=$appVersion")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "DyskLink"
            packageVersion = appVersion
            description = "Your Android phone and your PC, working together"
            vendor = "DyskLab"
            copyright = "DyskLab"
            modules("java.instrument", "java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "DyskLink"
                shortcut = true
                perUserInstall = true
                // Must never change, so new versions install over old ones.
                upgradeUuid = "6f0b2c1e-6a4d-4f3b-9d8e-2b7c9a1d5e40"
            }
            linux {
                iconFile.set(project.file("packaging/icon.png"))
                packageName = "dysklink"
                debMaintainer = "DyskLab"
                appCategory = "Utility"
                menuGroup = "Utility"
                shortcut = true
            }
        }
    }
}
