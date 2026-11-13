import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// DyskLink for Windows and Linux: one Kotlin app on the shared :core, the computer side of the protocol.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    // Only the compiler plugin, for the clipboard history's and battery history's JSON; the library comes with :core.
    id("org.jetbrains.kotlin.plugin.serialization")
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
    // Typing from the phone on any keyboard layout: SendInput with Unicode on Windows, Xlib and XTest on X11.
    // Only the core jar (about 2 MB, native parts for every OS inside), not jna-platform.
    implementation("net.java.dev.jna:jna:5.17.0")
    // The terminal from the phone: a real pseudo terminal (ConPTY on Windows, a pty on Linux), so colors, Ctrl-C,
    // and full-screen programs work. About 2.8 MB with natives for every OS; brings jna-platform (1.4 MB) and slf4j-api.
    implementation("org.jetbrains.pty4j:pty4j:0.13.13")
    // The tray icon on Linux desktops that only show StatusNotifierItem icons (KDE Plasma, GNOME with AppIndicator):
    // D-Bus in plain Java over the JDK's own unix sockets, so no libdbus or JNI. Used on Linux only.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")

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
            // jdk.security.auth: dbus-java reads the user id from it to log in to the session bus (the Linux tray).
            modules("java.instrument", "java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.security.auth")
            // Files beside the app, found through compose.application.resources.dir: CI puts softcam.dll (DyskLink
            // Camera, the webcam on Windows) in resources/windows-x64. Nothing there in a local build.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
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
