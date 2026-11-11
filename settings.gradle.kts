pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneLink"
// CI for the desktop apps sets DYSKLINK_DESKTOP_ONLY, so it needs no Android SDK.
if (System.getenv("DYSKLINK_DESKTOP_ONLY") == null) include(":app")
include(":core", ":desktop")
