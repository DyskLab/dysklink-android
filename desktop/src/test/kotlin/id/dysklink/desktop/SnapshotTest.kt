package id.dysklink.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import id.dysklink.desktop.ui.DeskModel
import id.dysklink.desktop.ui.DyskTheme
import id.dysklink.desktop.ui.MainScreen
import id.dysklink.desktop.ui.Page
import id.dysklink.desktop.ui.ThemeMode
import id.dysklink.desktop.ui.UiState
import id.dysklink.desktop.ui.CameraWindowContent
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.ContactItem
import id.phonelink.android.core.FsEntry
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PhoneContacts
import id.phonelink.android.core.PhoneFolder
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSaved
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.core.TodoItem
import id.phonelink.android.core.TodoList
import id.phonelink.android.core.WaterEntry
import id.phonelink.android.core.WaterLog
import id.phonelink.android.core.WaterSettings
import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.GalleryItem
import id.phonelink.android.core.PhoneGallery
import id.phonelink.android.core.PhoneLinkProtocol
import id.phonelink.android.core.PhoneSms
import id.phonelink.android.core.PhoneVideo
import id.phonelink.android.core.SmsItem
import id.phonelink.android.core.SavedCommand
import id.phonelink.android.core.SmsOutgoing
import id.phonelink.android.core.VideoKind
import id.phonelink.android.core.HubPairing
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws the main window in its main states to PNGs, to look at the design without a phone.
 * Only runs when DYSKLINK_SNAPSHOTS names a folder:
 * `DYSKLINK_SNAPSHOTS=/tmp/snaps ./gradlew :desktop:test --tests '*SnapshotTest*'`
 */
class SnapshotTest {
    private val now = 1_762_900_000_000L

    private val phone = HubPhone(
        "p1", "Pixel 8", "Pixel 8", isPaired = true, isConnected = true,
        info = DeviceInfo(
            batteryPercent = 76, isCharging = true, network = "Wi-Fi", wifiLevel = 4, storageFreeBytes = 82_000_000_000,
            storageTotalBytes = 256_000_000_000, osVersion = "Android 15", model = "Pixel 8", carrier = "Telkomsel", cellType = "5G", signalLevel = 3,
        ),
        features = listOf("notifications", "screen", "control", "gallery", "sms"), platform = "android",
        caps = PhoneLinkProtocol.PHONE_CAPS,
    )
    /** A phone on an older DyskLink, which doesn't serve its screen to PCs. */
    private val oldPhone = phone.copy(caps = PhoneLinkProtocol.CAPS)
    private val tablet = HubPhone("p2", "Galaxy Tab S9", "SM-X710", isPaired = true, isConnected = false, platform = "android")
    private val mac = HubPhone("m1", "Fanny's MacBook Air", "MacBook Air", isPaired = true, isConnected = true, platform = "macos")
    private val newPc = HubPhone("w1", "DESKTOP-4F2K", "Windows 11", isPaired = false, isConnected = true, platform = "windows")

    private val notifications = listOf(
        NotificationItem("n1", "Messages", "Ayu Lestari", "Still meeting at three?", now - 120_000),
        NotificationItem("n2", "Calendar", "Team meeting", "Starts in 15 minutes", now - 900_000),
        NotificationItem("n3", "WhatsApp", "Family", "Dinner is ready, come down when you can.", now - 3_600_000, color = 0x25D366),
    )

    private val transfers = listOf(
        Transfer("t1", "p1", "final-presentation.pdf", 8_000_000, 5_000_000, outgoing = true, status = TransferStatus.Active, time = now),
        Transfer("t2", "p1", "vacation-photo.jpg", 3_400_000, 3_400_000, outgoing = false, status = TransferStatus.Done, time = now - 600_000),
        Transfer("t3", "m1", "notes.txt", 2_000, 2_000, outgoing = true, status = TransferStatus.Done, time = now - 7_200_000),
        Transfer("t4", "p1", "big-video.mp4", 900_000_000, 10_000, outgoing = false, status = TransferStatus.Failed, detail = "Cancelled on the phone", time = now - 86_400_000),
    )

    private class Fake(
        devices: List<HubPhone> = emptyList(),
        pairing: HubPairing? = null,
        notifications: Map<String, List<NotificationItem>> = emptyMap(),
        transfers: List<Transfer> = emptyList(),
        soundFrom: String? = null,
        ringingHere: Boolean = false,
        computers: Boolean = false,
        screens: Map<String, PhoneVideo> = emptyMap(),
        cameras: Map<String, PhoneVideo> = emptyMap(),
        galleries: Map<String, PhoneGallery> = emptyMap(),
        sms: Map<String, PhoneSms> = emptyMap(),
        soundTo: String? = null,
        soundOut: Boolean = false,
        commandPhones: Set<String> = emptySet(),
        terminalPhones: Set<String> = emptySet(),
        terminals: Map<String, Int> = emptyMap(),
        remote: Boolean = false,
        usedBy: String? = null,
        commands: List<SavedCommand> = emptyList(),
        screenOut: Boolean = false,
        screenTo: String? = null,
        private val screenNotes: List<String> = emptyList(),
        contacts: Map<String, PhoneContacts> = emptyMap(),
        folders: Map<String, PhoneFolder> = emptyMap(),
        tools: PcToolsState = PcToolsState(),
        update: UpdateState = UpdateState(),
    ) : DeskModel {
        override val contacts = MutableStateFlow(contacts)
        override val folders = MutableStateFlow(folders)
        override fun loadContacts(deviceId: String) {}
        override fun openPhoneFolder(deviceId: String, path: String, hidden: Boolean?) {}
        override fun downloadPhoneFiles(deviceId: String, paths: List<String>) {}
        override fun copyText(text: String) {}
        override val tools = MutableStateFlow(tools)
        override fun addTodo(text: String) = true
        override fun toggleTodo(id: String) {}
        override fun deleteTodo(id: String) {}
        override fun renameTodo(id: String, text: String) {}
        override fun clearDoneTodos() {}
        override fun drink() {}
        override fun undoDrink() {}
        override fun setWater(settings: WaterSettings) {}
        override fun togglePomodoro() {}
        override fun resetPomodoro() {}
        override fun skipPomodoro() {}
        override fun setPomodoro(settings: PomodoroSettings) {}
        override val update = MutableStateFlow(update)
        override fun checkForUpdates() {}
        override fun downloadUpdate() {}
        override fun skipUpdate() {}
        override val screenOutOn = MutableStateFlow(screenOut)
        override val screenTo = MutableStateFlow(screenTo)
        override fun screenNotes() = screenNotes
        override fun setScreenOutOn(on: Boolean) {}
        override fun stopScreenOut() {}
        override val soundOutOn = MutableStateFlow(soundOut)
        override val soundTo = MutableStateFlow(soundTo)
        override val commands = MutableStateFlow(commands)
        override val commandPhones = MutableStateFlow(commandPhones)
        override val terminalPhones = MutableStateFlow(terminalPhones)
        override val terminals = MutableStateFlow(terminals)
        override val remoteOn = MutableStateFlow(remote)
        override val commandAsCtrl = MutableStateFlow(true)
        override val usedBy = MutableStateFlow(usedBy)
        override fun remoteNotes() = emptyList<String>()
        override fun setSoundOutOn(on: Boolean) {}
        override fun stopSoundOut() {}
        override fun addCommand(name: String, command: String) = true
        override fun removeCommand(id: String) {}
        override fun setCommandsAllowed(deviceId: String, on: Boolean) {}
        override fun setTerminalAllowed(deviceId: String, on: Boolean) {}
        override fun endTerminals(deviceId: String) {}
        override fun setRemoteOn(on: Boolean) {}
        override fun setCommandAsCtrl(on: Boolean) {}
        override fun stopRemote() {}
        override val screens = MutableStateFlow(screens)
        override val cameras = MutableStateFlow(cameras)
        override val galleries = MutableStateFlow(galleries)
        override val sms = MutableStateFlow(sms)
        override fun requestScreen(deviceId: String) {}
        override fun stopScreen(deviceId: String) {}
        override fun control(deviceId: String, event: ControlEvent) {}
        override fun requestCamera(deviceId: String) {}
        override fun stopCamera(deviceId: String) {}
        override fun cameraControl(deviceId: String, action: String) {}
        override fun loadGallery(deviceId: String, fresh: Boolean) {}
        override fun downloadGallery(deviceId: String, ids: List<Long>) {}
        override fun loadSms(deviceId: String) {}
        override fun sendSms(deviceId: String, address: String, body: String) = true
        override val phones = MutableStateFlow(devices)
        override val pairing = MutableStateFlow(pairing)
        override val notifications = MutableStateFlow(notifications)
        override val ringing = MutableStateFlow(emptySet<String>())
        override val transfers = MutableStateFlow(transfers)
        override val problem = MutableStateFlow<String?>(null)
        override val ringingHere = MutableStateFlow(ringingHere)
        override val soundFrom = MutableStateFlow(soundFrom)
        override val clipboardOn = MutableStateFlow(true)
        override val notifyOn = MutableStateFlow(true)
        override val controlOn = MutableStateFlow(true)
        override val computersOn = MutableStateFlow(computers)
        override val closeToTray = MutableStateFlow(true)
        override val themeMode = MutableStateFlow(ThemeMode.System)
        override val deviceName = "DESKTOP-FANNY"
        override val systemName = "Windows 11"
        override val version = "1.0.0"
        override val downloadsPath = "C:\\Users\\fanny\\Downloads\\DyskLink"
        override val addresses = listOf("192.168.1.20:47821")
        override val hasTray = true
        override val autostartAvailable = true
        override fun controlNotes() = emptyList<String>()
        override fun autostartOn() = true
        override fun setAutostart(on: Boolean) = true
        override fun setClipboardOn(on: Boolean) {}
        override fun setNotifyOn(on: Boolean) {}
        override fun setControlOn(on: Boolean) {}
        override fun setComputersOn(on: Boolean) {}
        override fun setCloseToTray(on: Boolean) {}
        override fun setThemeMode(mode: ThemeMode) {}
        override fun sendFiles(deviceId: String, files: List<File>) {}
        override fun sendClipboard(deviceId: String) = true
        override fun ring(deviceId: String, on: Boolean) {}
        override fun dismissNotification(deviceId: String, key: String) {}
        override fun unpair(deviceId: String) {}
        override fun requestPairing(deviceId: String) {}
        override fun submitCode(code: String) {}
        override fun rejectPairing() {}
        override fun stopRinging() {}
        override fun stopPhoneSound() {}
        override fun openDownloads() {}
        override fun open(file: File) {}
    }

    private fun shoot(dir: File, name: String, model: DeskModel, ui: UiState = UiState(), dark: Boolean = true, height: Int = 700) {
        val scene = ImageComposeScene(1040 * 3 / 2, height * 3 / 2, Density(1.5f)) {
            DyskTheme(dark) { MainScreen(model, ui, pickFiles = { emptyList() }, radarProgress = 0.45f) }
        }
        scene.render()
        val image = scene.render(1_000_000_000)
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** A made-up phone screen or camera picture, as the phone would send it. */
    private fun jpeg(width: Int, height: Int, label: String, camera: Boolean = false): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(if (camera) 0x4a6b8a else 0x1d2b4f), width.toFloat(), height.toFloat(), java.awt.Color(if (camera) 0xc9a26b else 0x6a3fa0))
        g.fillRect(0, 0, width, height)
        g.color = java.awt.Color(255, 255, 255, 60)
        if (camera) {
            g.fillOval(width / 3, height / 4, width / 3, height / 2)
        } else {
            for (row in 0 until 5) for (col in 0 until 4) g.fillRoundRect(width / 10 + col * width / 5, height / 4 + row * height / 9, width / 8, width / 8, 30, 30)
            g.fillRoundRect(width / 12, height - height / 10, width * 5 / 6, height / 16, 40, 40)
        }
        g.color = java.awt.Color.WHITE
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, width / 9)
        g.drawString(label, width / 10, height / 7)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun thumb(index: Int): String {
        val image = java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        val hues = listOf(0x2e86ab, 0xa23b72, 0xf18f01, 0xc73e1d, 0x3b8b5a, 0x6c5ce7, 0x00a8a8, 0xd4a017)
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(hues[index % hues.size]), 128f, 128f, java.awt.Color(hues[(index + 3) % hues.size]))
        g.fillRect(0, 0, 128, 128)
        g.color = java.awt.Color(255, 255, 255, 80)
        g.fillOval(20 + index % 5 * 8, 30, 60, 60)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun shootContent(dir: File, name: String, width: Int, height: Int, dark: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width * 3 / 2, height * 3 / 2, Density(1.5f)) { DyskTheme(dark) { content() } }
        scene.render()
        val image = scene.render(1_000_000_000)
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun phoneFeatureSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val screen = PhoneVideo(VideoKind.Screen, waiting = false, live = true, width = 576, height = 1280, frame = jpeg(576, 1280, "12:45"), frames = 40)
        val camera = PhoneVideo(VideoKind.Camera, waiting = false, live = true, width = 1280, height = 720, rotation = 0, lens = "back", hasFlash = true, frame = jpeg(1280, 720, "Camera", camera = true), frames = 90)
        val gallery = PhoneGallery(
            items = (0 until 23).map { GalleryItem(it.toLong(), "IMG_$it.jpg", now - it * 3_600_000L, 2_000_000, video = it % 7 == 3, durationMs = if (it % 7 == 3) 74_000 else null, thumb = thumb(it)) },
            end = false,
        )
        val sms = PhoneSms(
            messages = listOf(
                SmsItem(9, 1, "+62 812 3456 7890", "Still meeting at three?", now - 120_000, false),
                SmsItem(8, 1, "+62 812 3456 7890", "Yes, see you at the cafe.", now - 600_000, true),
                SmsItem(7, 1, "+62 812 3456 7890", "Are we still on for today?", now - 900_000, false),
                SmsItem(6, 2, "TELKOMSEL", "Your data package expires in 3 days. Reply YES to renew.", now - 86_400_000, false),
                SmsItem(5, 3, "+62 811 222 333", "Your package has been delivered to the front door.", now - 2 * 86_400_000, false),
            ),
            loaded = true,
            sent = listOf(SmsOutgoing("o1", "+62 812 3456 7890", "Leaving now, 10 minutes.", now - 30_000)),
        )
        val ui = { tab: String -> UiState(tab = tab) }
        shoot(dir, "14-screen-start", Fake(all), ui("screen"))
        shoot(dir, "15-screen-live", Fake(all, screens = mapOf("p1" to screen)), ui("screen"))
        shoot(dir, "15b-screen-waiting", Fake(all, screens = mapOf("p1" to PhoneVideo(VideoKind.Screen))), ui("screen"))
        shoot(dir, "15c-screen-old-phone", Fake(listOf(oldPhone, tablet, mac)), ui("screen"))
        shoot(dir, "16-camera-live", Fake(all, cameras = mapOf("p1" to camera)), ui("camera"))
        shoot(dir, "16b-camera-waiting", Fake(all, cameras = mapOf("p1" to PhoneVideo(VideoKind.Camera))), ui("camera"))
        val window = Fake(all, cameras = mapOf("p1" to camera))
        shootContent(dir, "17-camera-window", 960, 640) { CameraWindowContent(window, UiState(), "p1", "Pixel 8") }
        val clean = UiState().also { it.camera.clean = true; it.camera.mirror = true }
        shootContent(dir, "17b-camera-window-clean", 960, 640) { CameraWindowContent(window, clean, "p1", "Pixel 8") }
        shoot(dir, "18-gallery", Fake(all, galleries = mapOf("p1" to gallery)), ui("gallery"))
        shoot(dir, "18b-gallery-off", Fake(listOf(phone.copy(features = listOf("notifications")), tablet, mac)), ui("gallery"))
        shoot(dir, "19-messages", Fake(all, sms = mapOf("p1" to sms)), ui("messages"))
        shoot(dir, "19b-light-messages", Fake(all, sms = mapOf("p1" to sms)), ui("messages"), dark = false)
        shoot(dir, "20-light-screen-live", Fake(all, screens = mapOf("p1" to screen)), ui("screen"), dark = false)
        assertTrue(File(dir, "15-screen-live.png").length() > 0)
    }

    /** What this PC serves: its sound to the phone, commands and the terminal, a Mac's mouse and keyboard here. */
    @Test
    fun pcParitySnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val withData = phone.copy(features = phone.features + listOf("contacts", "files"))
        val all = listOf(withData, tablet, mac)
        val names = listOf("Adi Pratama", "Ani Wijaya", "Budi Santoso", "Bunda", "Citra Lestari", "Dewi Anggraini", "Eko Saputra", "Ánh Nguyễn", "Fajar Nugroho", "Kantor", "123 Taxi")
        val book = PhoneContacts(
            names.mapIndexed { i, name -> ContactItem("$i", name, if (i == 3) listOf("+62 812-3456-7890", "021 555 0101") else listOf("+62 81${i} 2233 44${i}5")) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            loaded = true,
        )
        val folder = PhoneFolder(
            "Documents",
            listOf(
                FsEntry("Invoices", true, 0, now - 86_400_000),
                FsEntry("Scans", true, 0, now - 3 * 86_400_000),
                FsEntry("Contract 2026.pdf", false, 1_843_200, now - 7_200_000),
                FsEntry("Notes.txt", false, 2_048, now - 600_000),
                FsEntry("Photo of whiteboard.jpg", false, 3_400_000, now - 2 * 86_400_000),
            ),
        )
        val clock = System.currentTimeMillis()
        val tools = PcToolsState(
            todos = TodoList(listOf(
                TodoItem("t1", "Send the invoice to Budi", clock - 7_200_000),
                TodoItem("t2", "Review the contract", clock - 5_000_000, doneAt = clock - 600_000),
                TodoItem("t3", "Buy printer paper", clock - 3_000_000),
            )),
            water = WaterLog(
                WaterSettings(reminds = true),
                (0..6).flatMap { day -> (0 until (if (day == 0) 4 else 5 + day % 4)).map { WaterEntry(clock - day * 86_400_000L - it * 1_800_000L, 250) } },
            ),
            pomodoro = PomodoroSaved(state = PomodoroState(PomodoroPhase.Focus, 2, endsAt = clock + 14 * 60_000 + 32_000)),
        )
        val update = UpdateState(DesktopRelease("1.2.0", "desktop-v1.2.0", DesktopUpdates.PAGE_URL, emptyList()), lastChecked = now)
        shoot(dir, "30-contacts", Fake(all, contacts = mapOf("p1" to book)), UiState(tab = "contacts"), height = 900)
        shoot(dir, "30c-contacts-off", Fake(listOf(phone, tablet, mac)), UiState(tab = "contacts"))
        shoot(dir, "30d-contacts-old-phone", Fake(listOf(oldPhone.copy(features = withData.features), tablet, mac)), UiState(tab = "contacts"))
        shoot(dir, "31-phone-files", Fake(all, folders = mapOf("p1" to folder)), UiState(tab = "phonefiles"), height = 900)
        shoot(dir, "31b-light-phone-files", Fake(all, folders = mapOf("p1" to folder)), UiState(tab = "phonefiles"), dark = false, height = 900)
        shoot(dir, "32-tools", Fake(all, tools = tools), UiState(page = Page.Tools), height = 1000)
        shoot(dir, "32b-light-tools", Fake(all, tools = tools), UiState(page = Page.Tools), dark = false, height = 1000)
        shoot(dir, "32c-tools-empty", Fake(all), UiState(page = Page.Tools), height = 1000)
        shoot(dir, "33-update-available", Fake(all, update = update), UiState(page = Page.Settings), height = 1700)
        shoot(dir, "33b-update-overview", Fake(all, update = update))
        shoot(dir, "33c-update-none", Fake(all, update = UpdateState(lastChecked = now, message = "DyskLink 1.0.0 is the newest version.")), UiState(page = Page.Settings), dark = false, height = 1700)
    }

    @Test
    fun pcServiceSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val commands = listOf(
            SavedCommand("c1", "Lock screen", "rundll32.exe user32.dll,LockWorkStation"),
            SavedCommand("c2", "Disk space", "wmic logicaldisk get size,freespace,caption"),
            SavedCommand("c3", "Restart Explorer", "taskkill /f /im explorer.exe && start explorer.exe"),
        )
        shoot(dir, "21-sound-out-off", Fake(all), UiState(tab = "sound"))
        shoot(dir, "21b-sound-out-sending", Fake(all, soundOut = true, soundTo = "p1"), UiState(tab = "sound"))
        shoot(dir, "21c-light-sound-out-sending", Fake(all, soundOut = true, soundTo = "p1"), UiState(tab = "sound"), dark = false)
        shoot(dir, "22-commands-off", Fake(all, commands = commands), UiState(tab = "commands"))
        shoot(dir, "22b-commands-on-terminal-open", Fake(all, commands = commands, commandPhones = setOf("p1"), terminalPhones = setOf("p1"), terminals = mapOf("p1" to 2)), UiState(tab = "commands"), height = 900)
        shoot(dir, "22c-light-commands-empty", Fake(all, commandPhones = setOf("p1")), UiState(tab = "commands"), dark = false)
        shoot(dir, "23-settings-commands", Fake(all, commands = commands, computers = true, remote = true, soundOut = true), UiState(page = Page.Settings), height = 1500)
        shoot(dir, "24-mac-using-this-pc", Fake(all, computers = true, remote = true, usedBy = "m1"), UiState(selectedId = "m1"))
        assertTrue(File(dir, "22b-commands-on-terminal-open.png").length() > 0)
    }

    /** This PC's screen on a phone: the switch in Settings, and the notice on every page while it's viewed. */
    @Test
    fun screenOutSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val wayland = listOf("Wayland doesn't let apps capture the screen yet. Log in with an X11 session to show this screen on a phone.")
        shoot(dir, "25-settings-screen-off", Fake(all), UiState(page = Page.Settings), height = 1400)
        shoot(dir, "25b-settings-screen-on-wayland", Fake(all, screenOut = true, screenNotes = wayland), UiState(page = Page.Settings), height = 1400)
        shoot(dir, "26-screen-viewed-overview", Fake(all, screenOut = true, screenTo = "p1"))
        shoot(dir, "26b-screen-viewed-settings", Fake(all, screenOut = true, screenTo = "p1"), UiState(page = Page.Settings))
        shoot(dir, "26c-light-screen-viewed", Fake(all, screenOut = true, screenTo = "p2"), dark = false)
        assertTrue(File(dir, "26-screen-viewed-overview.png").length() > 0)
    }

    @Test
    fun snapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val alerts = mapOf("p1" to notifications)
        shoot(dir, "1-empty", Fake())
        shoot(dir, "2-overview", Fake(all, notifications = alerts, transfers = transfers, computers = true))
        shoot(dir, "3-notifications", Fake(all, notifications = alerts), UiState(tab = "notifications"))
        shoot(dir, "4-pairing-code", Fake(listOf(phone), pairing = HubPairing("p3", "Galaxy S24", "482915")))
        shoot(dir, "4b-pairing-type", Fake(listOf(phone, newPc), pairing = HubPairing("w1", "DESKTOP-4F2K", "", typing = true, error = "That code didn't match. Try again."), computers = true))
        shoot(dir, "5-settings", Fake(all, computers = true), UiState(page = Page.Settings))
        shoot(dir, "6-light-overview", Fake(all, notifications = alerts, transfers = transfers, computers = true), dark = false)
        shoot(dir, "6b-light-empty", Fake(), dark = false)
        shoot(dir, "7-files", Fake(all, transfers = transfers, soundFrom = "p1"), UiState(tab = "files"))
        shoot(dir, "8-history", Fake(all, transfers = transfers), UiState(page = Page.History))
        shoot(dir, "9-sound", Fake(all, soundFrom = "p1", ringingHere = true), UiState(tab = "sound"))
        shoot(dir, "10-control", Fake(all), UiState(tab = "control"))
        shoot(dir, "11-computer", Fake(all, computers = true), UiState(selectedId = "m1"))
        shoot(dir, "12-new-computer", Fake(listOf(phone, newPc), computers = true), UiState(selectedId = "w1"))
        shoot(dir, "13-light-settings", Fake(all, computers = true), UiState(page = Page.Settings), dark = false)
        assertTrue(File(dir, "1-empty.png").length() > 0)
    }
}
