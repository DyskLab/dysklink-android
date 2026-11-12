package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoSyncTest {
    @Test
    fun freshStartOnlyTakesLaterSeconds() {
        val (where, args) = PhotoSync.selection(PhotoMark(1000, Long.MAX_VALUE))
        assertEquals("(date_added > ?)", where)
        assertEquals(listOf("1000"), args.toList())
    }

    @Test
    fun afterAPhotoTheSameSecondContinuesByID() {
        val (where, args) = PhotoSync.selection(PhotoMark(1000, 42))
        assertEquals("(date_added > ? OR (date_added = ? AND _id > ?))", where)
        assertEquals(listOf("1000", "1000", "42"), args.toList())
    }

    @Test
    fun videosGetVideoNames() {
        assertEquals("VID_7.mp4", PhotoSync.fileName(null, 7, "video/mp4"))
        assertEquals("VID_7.mov", PhotoSync.fileName("", 7, "video/quicktime"))
    }

    @Test
    fun namesFallBackToTheID() {
        assertEquals("PXL_1.jpg", PhotoSync.fileName("PXL_1.jpg", 7, "image/jpeg"))
        assertEquals("IMG_7.png", PhotoSync.fileName(null, 7, "image/png"))
        assertEquals("IMG_7.jpg", PhotoSync.fileName(" ", 7, null))
    }

    private fun peer(name: String, platform: String, caps: List<String>, connected: Boolean = true) =
        id.phonelink.android.core.Peer(name, name, platform, "m", isPaired = true, isConnected = connected, info = null, caps = caps)

    @Test
    fun phonesThatKeepABackupAreTargetsToo() {
        val caps = id.phonelink.android.core.PhoneLinkProtocol.PHONE_CAPS
        val peers = listOf(
            peer("mac", "macos", id.phonelink.android.core.PhoneLinkProtocol.CAPS),
            peer("tablet", "android", caps),
            peer("old", "android", caps - id.phonelink.android.core.PhoneLinkProtocol.CAP_P2P_BACKUP),
            peer("away", "android", caps, connected = false),
            peer("pc", "windows", caps),
        )
        val all = PhotoSync.targets(peers, isOn = { true }, wifiOnly = { false }, chargingOnly = { false }, onWifi = false, charging = false)
        assertEquals(listOf("mac", "tablet"), all.map { it.id })
        // Each target by its own switches.
        val some = PhotoSync.targets(peers, isOn = { it != "mac" }, wifiOnly = { false }, chargingOnly = { false }, onWifi = false, charging = false)
        assertEquals(listOf("tablet"), some.map { it.id })
        val charging = PhotoSync.targets(peers, isOn = { true }, wifiOnly = { false }, chargingOnly = { it == "tablet" }, onWifi = true, charging = false)
        assertEquals(listOf("mac"), charging.map { it.id })
        val plugged = PhotoSync.targets(peers, isOn = { true }, wifiOnly = { it == "mac" }, chargingOnly = { it == "tablet" }, onWifi = false, charging = true)
        assertEquals(listOf("tablet"), plugged.map { it.id })
    }
}
