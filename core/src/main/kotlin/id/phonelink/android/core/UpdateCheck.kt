package id.phonelink.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published release that is newer than the installed app. */
data class AppRelease(val version: String, val pageUrl: String, val apkUrl: String?)

/**
 * Reads the public GitHub release list. Pre-releases count, since DyskLink is still in them.
 * Kept free of Android classes so it can be tested on the JVM.
 */
object UpdateCheck {
    const val RELEASES_URL = "https://api.github.com/repos/DyskLab/dysklink-android/releases?per_page=10"
    const val PAGE_URL = "https://github.com/DyskLab/dysklink-android/releases"

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    @Serializable
    private data class Item(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val pageUrl: String,
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun newest(body: String, current: String): AppRelease? =
        json.decodeFromString<List<Item>>(body)
            .filter { !it.draft && version(it.tag) != null && isNewer(it.tag, current) }
            .map { item ->
                AppRelease(
                    version = clean(item.tag),
                    pageUrl = item.pageUrl,
                    apkUrl = item.assets.firstOrNull { it.name.endsWith(".apk") }?.url,
                )
            }
            .reduceOrNull { best, release -> if (isNewer(release.version, best.version)) release else best }

    fun clean(tag: String): String = tag.removePrefix("v").removePrefix("V")

    /** "v0.1.3" becomes [0, 1, 3]. Anything after a dash, like "-beta", is ignored. */
    fun version(text: String): List<Int>? {
        val parts = clean(text).substringBefore('-').split('.').map { it.toIntOrNull() }
        return if (parts.isEmpty() || parts.any { it == null }) null else parts.filterNotNull()
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = version(candidate) ?: return false
        val b = version(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
