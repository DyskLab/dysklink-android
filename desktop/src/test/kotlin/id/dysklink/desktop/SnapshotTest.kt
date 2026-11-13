package id.dysklink.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import id.dysklink.desktop.ui.DeskModel
import id.dysklink.desktop.ui.DyskTheme
import id.dysklink.desktop.ui.MainScreen
import id.dysklink.desktop.ui.Page
import id.dysklink.desktop.ui.ThemeMode
import id.dysklink.desktop.ui.UiState
import id.dysklink.desktop.ui.ToolsView
import id.dysklink.desktop.ui.CameraWindowContent
import id.dysklink.desktop.ui.ScreenWindowContent
import id.dysklink.desktop.ui.GamePanelWidth
import id.dysklink.desktop.ui.LauncherContent
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
        host: HostHints = HostHints(),
        contacts: Map<String, PhoneContacts> = emptyMap(),
        folders: Map<String, PhoneFolder> = emptyMap(),
        tools: PcToolsState = PcToolsState(),
        update: UpdateState = UpdateState(),
        usb: UsbState = UsbState(adbPath = "C:\\Users\\fanny\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe", windows = true),
        override val hasTray: Boolean = true,
        override val trayHint: TrayHint? = null,
        override val gamePointerLock: Boolean = true,
        webcam: WebcamState? = null,
        clip: ClipHistoryView? = null,
        battery: BatteryView? = null,
        private val images: File? = null,
        care: PcCareModel? = null,
        override val launcher: LauncherModel? = null,
        tidy: WindowTidyView? = null,
        tray: TrayMenuConfig? = null,
    ) : DeskModel {
        override val pcCare = care
        override val windowTidy = tidy?.let { MutableStateFlow(it) }
        override val trayMenu = tray?.let { MutableStateFlow(it) }
        override val clipHistory = clip?.let { MutableStateFlow(it) }
        override val battery = battery?.let { MutableStateFlow<BatteryView?>(it) }
        override fun clipImageFile(entry: ClipEntry): File? = entry.imageFile?.let { File(images, it) }
        override val webcam = webcam?.let { MutableStateFlow(it) }
        override val gameKeyStore = GameKeyStore(null)
        override val usb = MutableStateFlow(usb)
        override fun setUsbOn(on: Boolean) {}
        override fun downloadAdb() {}
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
        override val host = MutableStateFlow(host)
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
        override val autostartAvailable = true
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
            DyskTheme(dark) { MainScreen(model, ui, pickFiles = { emptyList() }, radarProgress = 0.45f, firewallAfterMs = 0) }
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

    @Test
    fun webcamSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val camera = PhoneVideo(VideoKind.Camera, waiting = false, live = true, width = 1280, height = 720, rotation = 0, lens = "back", hasFlash = true, frame = jpeg(1280, 720, "Camera", camera = true), frames = 90)
        val linuxObs = HostHints(obs = ObsCapture.Xcomposite)
        val ui = { UiState(tab = "camera") }
        shoot(dir, "40-webcam-off", Fake(all, host = linuxObs, webcam = WebcamState()), ui(), height = 820)
        val setup = HostChecks.linuxWebcam(LoopbackProbe(installed = false, secureBoot = true), Distro.Debian)
        shoot(dir, "41-webcam-linux-setup", Fake(all, host = linuxObs, webcam = WebcamState(on = true, status = WebcamStatus.NeedsSetup, hints = setup.hints)), ui(), height = 1150)
        val fedora = HostChecks.linuxWebcam(LoopbackProbe(installed = false), Distro.Fedora)
        shoot(dir, "41b-webcam-fedora-setup", Fake(all, host = linuxObs, webcam = WebcamState(on = true, status = WebcamStatus.NeedsSetup, hints = fedora.hints)), ui(), height = 1000)
        shoot(
            dir, "42-webcam-active-live",
            Fake(all, cameras = mapOf("p1" to camera), host = linuxObs, webcam = WebcamState(on = true, status = WebcamStatus.Active, device = "/dev/video10", live = true)),
            ui(), height = 1000,
        )
        shoot(dir, "42b-webcam-active-off", Fake(all, host = linuxObs, webcam = WebcamState(on = true, status = WebcamStatus.Active, device = "/dev/video10")), ui(), height = 820)
        val busy = WebcamState(
            on = true, status = WebcamStatus.Busy, device = "/dev/video10", problem = "Can't open /dev/video10: it's busy, another app is sending to it",
            hints = listOf(HostChecks.webcamBusy(false, "/dev/video10", "")),
        )
        shoot(dir, "43-webcam-busy", Fake(all, cameras = mapOf("p1" to camera), host = linuxObs, webcam = busy), ui(), height = 1100)
        val dll = "C:\\Users\\fanny\\AppData\\Local\\DyskLink\\app\\resources\\softcam.dll"
        val win = HostChecks.windowsWebcam(dll, null)
        shoot(dir, "44-webcam-windows-install", Fake(all, host = HostHints(obs = ObsCapture.Windows), webcam = WebcamState(on = true, status = WebcamStatus.NeedsSetup, label = "DyskLink Camera", hints = win.hints)), ui(), height = 820)
        val installed = HostChecks.windowsWebcam(dll, dll)
        shoot(dir, "45-settings-webcam-remove", Fake(all, webcam = WebcamState(settings = installed.settings)), UiState(page = Page.Settings), height = 1400)
        val window = Fake(all, cameras = mapOf("p1" to camera), webcam = WebcamState(on = true, status = WebcamStatus.Active, device = "/dev/video10", live = true))
        shootContent(dir, "46-camera-window-webcam", 960, 640) { CameraWindowContent(window, UiState(), "p1", "Pixel 8") }
        shoot(dir, "47-light-webcam-setup", Fake(all, host = linuxObs, webcam = WebcamState(on = true, status = WebcamStatus.NeedsSetup, hints = setup.hints)), ui(), dark = false, height = 1150)
        // The frame apps get while the phone camera is off.
        val canvas = WebcamFrames.Canvas()
        WebcamFrames.placeholder(canvas)
        javax.imageio.ImageIO.write(canvas.image, "png", File(dir, "48-webcam-off-frame.png"))
        assertTrue(File(dir, "41-webcam-linux-setup.png").length() > 0)
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

    /** Linux without a tray: GNOME is pointed at the AppIndicator extension, other desktops are told closing quits. */
    @Test
    fun trayHintSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        shoot(dir, "41-settings-no-tray-gnome", Fake(all, hasTray = false, trayHint = TrayHints.pick(true, false, "ubuntu:GNOME")), UiState(page = Page.Settings))
        shoot(dir, "41b-settings-no-tray-gnome-light", Fake(all, hasTray = false, trayHint = TrayHints.pick(true, false, "GNOME")), UiState(page = Page.Settings), dark = false)
        shoot(dir, "41c-settings-no-tray-other", Fake(all, hasTray = false, trayHint = TrayHints.pick(true, false, "sway")), UiState(page = Page.Settings))
        shoot(dir, "41d-settings-with-tray", Fake(all), UiState(page = Page.Settings))
        assertTrue(File(dir, "41-settings-no-tray-gnome.png").length() > 0)
    }

    /** This PC's screen on a phone: the switch in Settings, and the notice on every page while it's viewed. */
    @Test
    fun screenOutSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val wayland = HostChecks.hints(HostEnv(windows = false, linux = true, env = mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "ubuntu:GNOME")::get, onPath = { true }))
        shoot(dir, "25-settings-screen-off", Fake(all), UiState(page = Page.Settings), height = 1400)
        shoot(dir, "25b-settings-screen-on-wayland", Fake(all, screenOut = true, host = wayland), UiState(page = Page.Settings), height = 1400)
        shoot(dir, "26-screen-viewed-overview", Fake(all, screenOut = true, screenTo = "p1"))
        shoot(dir, "26b-screen-viewed-settings", Fake(all, screenOut = true, screenTo = "p1"), UiState(page = Page.Settings))
        shoot(dir, "26c-light-screen-viewed", Fake(all, screenOut = true, screenTo = "p2"), dark = false)
        assertTrue(File(dir, "26-screen-viewed-overview.png").length() > 0)
    }

    /** Game keys on the phone's screen: play and edit, in the tab and the pop-out window, and the Wayland fallback. */
    @Test
    fun gameKeysSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val screen = PhoneVideo(VideoKind.Screen, waiting = false, live = true, width = 576, height = 1280, frame = jpeg(576, 1280, "12:45"), frames = 40)
        val layout = GameProfile(
            "Mobile Legends",
            listOf(
                GameKey(kind = GameKey.Kind.Joystick, x = 0.22, y = 0.78, radius = 0.13, label = "WASD", walk = GameKey.Walk.HalfWithKey, walkKey = 56),
                GameKey(kind = GameKey.Kind.Tap, keyCode = 49, label = "Space", x = 0.84, y = 0.86),
                GameKey(kind = GameKey.Kind.Skill, keyCode = 12, label = "Q", x = 0.62, y = 0.88, radius = 0.09, quickCast = true),
                GameKey(kind = GameKey.Kind.Skill, keyCode = 14, label = "E", x = 0.7, y = 0.76, radius = 0.09),
                GameKey(kind = GameKey.Kind.Tap, keyCode = 15, label = "R", x = 0.86, y = 0.7, mode = GameKey.Mode.AutoFire),
                GameKey(kind = GameKey.Kind.Swipe, keyCode = 3, label = "F", x = 0.5, y = 0.6, toX = 0.5, toY = 0.52),
                GameKey(kind = GameKey.Kind.Tap, keyCode = 17, label = "T", x = 0.4, y = 0.4, mode = GameKey.Mode.Combo, steps = listOf(
                    GameKey.Step(kind = GameKey.Step.Kind.Tap, x = 0.3, y = 0.3), GameKey.Step(kind = GameKey.Step.Kind.Wait, ms = 80.0),
                    GameKey.Step(kind = GameKey.Step.Kind.Tap, x = 0.55, y = 0.32), GameKey.Step(kind = GameKey.Step.Kind.Wait, ms = 80.0),
                    GameKey.Step(kind = GameKey.Step.Kind.Swipe, x = 0.5, y = 0.45, toX = 0.65, toY = 0.45, ms = 120.0),
                )),
                GameKey(kind = GameKey.Kind.Look, label = "Look", x = 0.7, y = 0.45, radius = 0.12),
                GameKey(kind = GameKey.Kind.Fire, label = "Click", x = 0.9, y = 0.58),
            ),
        )
        /** A window state with game keys for p1, in [mode]: play, edit with a control selected, or edit waiting for a key. */
        fun setUp(fake: Fake, ui: UiState, mode: String, select: Int = 0): UiState {
            val game = ui.gameKeys("p1", fake)
            game.importProfiles(GameKeysJson.encodeProfile(layout))
            game.addProfile("Genshin Impact")
            game.selectProfile(0)
            game.enable(true)
            if (mode != "play") {
                game.edit(true)
                game.selected = game.keys[select].id
                if (mode == "pending") { game.selected = null; game.pending = PhonePoint(0.3, 0.55) }
            }
            return ui
        }
        val tab = { fake: Fake, mode: String, select: Int -> setUp(fake, UiState(tab = "screen"), mode, select) }
        Fake(all, screens = mapOf("p1" to screen)).let { shoot(dir, "50-gamekeys-tab-play", it, tab(it, "play", 0), height = 760) }
        Fake(all, screens = mapOf("p1" to screen)).let { shoot(dir, "50b-gamekeys-tab-edit-joystick", it, tab(it, "edit", 0), height = 760) }
        Fake(all, screens = mapOf("p1" to screen)).let { shoot(dir, "50c-gamekeys-tab-edit-combo", it, tab(it, "edit", 6), height = 760) }
        Fake(all, screens = mapOf("p1" to screen)).let { shoot(dir, "50d-gamekeys-tab-edit-pending", it, tab(it, "pending", 0), height = 760) }
        Fake(all, screens = mapOf("p1" to screen)).let { shoot(dir, "50e-light-gamekeys-tab-edit-skill", it, tab(it, "edit", 2), dark = false, height = 760) }
        Fake(all, screens = mapOf("p1" to screen), gamePointerLock = false).let { shoot(dir, "50f-gamekeys-tab-play-wayland", it, tab(it, "play", 0), height = 760) }
        Fake(listOf(phone.copy(caps = phone.caps - PhoneLinkProtocol.CAP_PC_GAMEKEYS), tablet, mac), screens = mapOf("p1" to screen)).let {
            val ui = UiState(tab = "screen").also { u -> u.gameKeys("p1", it).enable(true) }
            shoot(dir, "50g-gamekeys-old-phone", it, ui, height = 760)
        }
        val windowWidth = 460 + GamePanelWidth.value.toInt() + 9
        Fake(all, screens = mapOf("p1" to screen)).let { fake ->
            val ui = setUp(fake, UiState(), "play")
            shootContent(dir, "51-gamekeys-window-play", windowWidth, 900) { ScreenWindowContent(fake, ui, "p1") }
        }
        Fake(all, screens = mapOf("p1" to screen)).let { fake ->
            val ui = setUp(fake, UiState(), "edit", 5)
            shootContent(dir, "51b-gamekeys-window-edit-swipe", windowWidth, 900) { ScreenWindowContent(fake, ui, "p1") }
        }
        Fake(all, screens = mapOf("p1" to screen)).let { fake ->
            val ui = setUp(fake, UiState(), "edit", 4).also { it.screenRotation = 90 }
            shootContent(dir, "51c-gamekeys-window-edit-turned", 1280 + GamePanelWidth.value.toInt(), 700) { ScreenWindowContent(fake, ui, "p1") }
        }
        // The whole panel, unscrolled, for each kind of control's settings.
        listOf(0 to "joystick", 1 to "tap", 2 to "skill", 5 to "swipe", 6 to "combo", 7 to "look").forEach { (index, name) ->
            Fake(all, screens = mapOf("p1" to screen)).let { fake ->
                val game = setUp(fake, UiState(), "edit", index).gameKeys("p1", fake)
                if (name == "joystick") game.capture = GameKeysController.Capture.StickKeys(game.keys[0].id, listOf(13), listOf("W"))
                shootContent(dir, "52-gamekeys-panel-$name", GamePanelWidth.value.toInt() + 16, 1250) {
                    id.dysklink.desktop.ui.GameKeysPanel(game, true, scrolls = false)
                }
            }
        }
        assertTrue(File(dir, "50-gamekeys-tab-play.png").length() > 0)
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

    /** USB: the card in Settings and on a phone's Overview, in each state, and the USB badge in the sidebar. */
    @Test
    fun usbSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val onUsb = phone.copy(viaUsb = true)
        val all = listOf(phone, tablet, mac)
        val winAdb = "C:\\Users\\fanny\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
        val devices = listOf(
            id.dysklink.desktop.AdbDevice("R58N91ABCDE", "device", "Pixel 8"),
            id.dysklink.desktop.AdbDevice("ZY22", "unauthorized", ""),
            id.dysklink.desktop.AdbDevice("0A1B2C", "offline", "Galaxy A54"),
        )
        val settings = UiState(page = Page.Settings)
        shoot(dir, "40-usb-overview-connected", Fake(listOf(onUsb, tablet, mac), usb = UsbState(adbPath = winAdb, devices = devices.take(1), windows = true)), height = 1250)
        shoot(dir, "40b-light-usb-overview-connected", Fake(listOf(onUsb, tablet, mac), usb = UsbState(adbPath = winAdb, devices = devices.take(1), windows = true)), dark = false, height = 1250)
        shoot(dir, "41-usb-settings-devices-windows", Fake(all, usb = UsbState(adbPath = winAdb, devices = devices, windows = true)), settings, height = 1900)
        shoot(dir, "42-usb-settings-no-adb-linux", Fake(all, usb = UsbState(windows = false)), settings, height = 1800)
        shoot(dir, "42b-usb-settings-downloading", Fake(all, usb = UsbState(downloading = true, progress = 0.42f, windows = false)), settings, height = 1800)
        shoot(dir, "42c-usb-settings-download-failed", Fake(all, usb = UsbState(downloadError = "Couldn't download the tools: dl.google.com", windows = true)), settings, dark = false, height = 1800)
        shoot(dir, "43-usb-settings-off", Fake(all, usb = UsbState(enabled = false, adbPath = "/usr/bin/adb", windows = false)), settings, height = 1700)
        shoot(dir, "44-usb-overview-unauthorized-linux", Fake(all, usb = UsbState(adbPath = "/usr/bin/adb", devices = devices.drop(1).take(1), windows = false)), height = 1250)
        assertTrue(File(dir, "40-usb-overview-connected.png").length() > 0)
    }

    /** Hints for what this PC or the phone needs from outside DyskLink, in each place they show. */
    @Test
    fun hintSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        fun linux(env: Map<String, String>, tools: Set<String>, osRelease: String, notifications: Boolean? = true) =
            HostChecks.hints(HostEnv(windows = false, linux = true, env = env::get, onPath = { it in tools }, osRelease = osRelease, notificationService = notifications, x11Remap = false))
        val ubuntu = "ID=ubuntu\nID_LIKE=debian\n"
        val gnomeWayland = linux(mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "ubuntu:GNOME"), emptySet(), ubuntu, notifications = false)
        val kdeWayland = linux(mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to "KDE"), setOf("playerctl", "pactl", "parec"), "ID=arch\n")
        val fedoraX11 = linux(mapOf("XDG_SESSION_TYPE" to "x11", "XDG_CURRENT_DESKTOP" to "XFCE"), setOf("pactl", "firewall-cmd"), "ID=fedora\n")
        val archX11 = linux(mapOf("XDG_SESSION_TYPE" to "x11"), setOf("ufw", "xdotool"), "ID=arch\n")
        val windows = HostChecks.hints(HostEnv(windows = true, linux = false, publicNetwork = true))
        val settings = UiState(page = Page.Settings)
        val on = { host: HostHints -> Fake(all, soundOut = true, screenOut = true, computers = true, remote = true, host = host) }
        shoot(dir, "60-hints-settings-wayland-gnome", on(gnomeWayland), settings, height = 2900)
        shoot(dir, "60b-hints-settings-wayland-gnome-light", on(gnomeWayland), settings, dark = false, height = 2900)
        shoot(dir, "61-hints-overview-wayland-gnome", on(gnomeWayland), height = 1500)
        shoot(dir, "61b-hints-overview-wayland-kde", on(kdeWayland), height = 1500)
        shoot(dir, "62-hints-control-tab-fedora-x11", on(fedoraX11), UiState(tab = "control"))
        shoot(dir, "63-hints-sound-tab-arch", on(archX11), UiState(tab = "sound"), height = 900)
        shoot(dir, "64-hints-empty-windows-public", Fake(host = windows), height = 1000)
        shoot(dir, "64b-hints-empty-linux-ufw", Fake(host = archX11), height = 900)
        shoot(dir, "64c-hints-empty-fedora-firewalld", Fake(host = fedoraX11), dark = false, height = 900)
        val camera = PhoneVideo(VideoKind.Camera, waiting = false, live = true, width = 1280, height = 720, rotation = 0, lens = "back", hasFlash = true, frame = jpeg(1280, 720, "Camera", camera = true), frames = 90)
        shoot(dir, "65-hints-camera-start-wayland", Fake(all, host = gnomeWayland), UiState(tab = "camera"))
        shoot(dir, "65b-hints-camera-live-windows", Fake(all, cameras = mapOf("p1" to camera), host = windows), UiState(tab = "camera"), height = 1000)
        val failed = PhoneVideo(VideoKind.Camera, waiting = false, live = false, error = "The camera couldn't open (error 3)")
        shoot(dir, "65c-hints-camera-refused", Fake(all, cameras = mapOf("p1" to failed), host = archX11), UiState(tab = "camera"))
        val noControl = phone.copy(features = listOf("notifications", "screen", "sms"))
        val screen = PhoneVideo(VideoKind.Screen, waiting = false, live = true, width = 576, height = 1280, frame = jpeg(576, 1280, "12:45"), frames = 40)
        shoot(dir, "66-hints-gallery-off", Fake(listOf(noControl, tablet)), UiState(tab = "gallery"))
        shoot(dir, "66b-hints-screen-no-control", Fake(listOf(noControl, tablet), screens = mapOf("p1" to screen)), UiState(tab = "screen"), height = 900)
        shoot(dir, "66c-hints-contacts-off", Fake(listOf(noControl, tablet)), UiState(tab = "contacts"), dark = false)
        val refused = PhoneVideo(VideoKind.Screen, waiting = false, live = false, error = "Viewing this screen is turned off on the phone.")
        shoot(dir, "66d-hints-screen-refused", Fake(all, screens = mapOf("p1" to refused)), UiState(tab = "screen"))
        shootContent(dir, "67-hints-camera-window-xcomposite", 960, 640) {
            CameraWindowContent(Fake(all, cameras = mapOf("p1" to camera), host = archX11), UiState(), "p1", "Pixel 8")
        }
        assertTrue(File(dir, "60-hints-settings-wayland-gnome.png").length() > 0)
    }

    /** Clipboard History on the Tools page, the quick picker, and Battery in both themes. */
    @Test
    fun clipboardAndBatterySnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val store = ClipHistoryStore(java.nio.file.Files.createTempDirectory("clipsnap").toFile(), ClipSettings(enabled = true), clock = { now })
        val picture = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB).also { img ->
            val g = img.createGraphics()
            g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x2e86ab), 320f, 200f, java.awt.Color(0xf18f01))
            g.fillRect(0, 0, 320, 200); g.dispose()
        }
        store.add(ClipCapture.Text("https://github.com/dysklab/dysklink/releases"))
        store.add(ClipCapture.Image(ClipboardWatch.png(picture)!!), source = "Pixel 8")
        store.add(ClipCapture.Files(listOf("/home/fanny/Documents/final-presentation.pdf", "/home/fanny/Documents/notes.txt")))
        store.add(ClipCapture.Text("Still meeting at three? The room changed to 4B, next to the lift."), source = "Fanny's MacBook Air")
        store.add(ClipCapture.Text("sudo apt install wl-clipboard"))
        store.add(ClipCapture.Text("Jl. Sudirman No. 21, Jakarta"))
        store.togglePin(store.entries.value.last().id)
        val entries = store.entries.value.mapIndexed { i, e -> e.copy(date = now - i * 1_500_000L) }
        val clip = ClipHistoryView(
            entries = entries, settings = ClipSettings(enabled = true),
            hotkey = HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready), mode = ClipWatchMode.PollNoWlPaste,
            imports = listOf(ClipImportSource.CopyQ),
            hints = HostChecks.clipboardHints(ClipWatchMode.PollNoWlPaste, HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready),
                "Ctrl+Shift+V", "/opt/dysklink/bin/DyskLink --clipboard", true, Distro.Debian, "ubuntu:GNOME"),
        )
        val windowsClip = clip.copy(hotkey = HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Ready), mode = ClipWatchMode.Poll,
            imports = listOf(ClipImportSource.Ditto, ClipImportSource.CopyQ), hints = emptyList(), importMessage = "Added 214 items from Ditto, sorted by date.")
        val report = WindowsBattery.parseReport(javaClass.getResource("/batteryreport-sample.xml")!!.readText())
        val reading = WindowsBattery.parseWmi(
            "present=True\npercent=70\nremaining=31605\nchargeRate=24500\nvoltage=12850\ncharging=True\ndischarging=False\nonline=True", report,
        )!!
        val days = (0 until 40).map { BatteryDay(java.time.LocalDate.of(2026, 10, 4).plusDays(it.toLong()).toString(), 90 - it / 10) }
        val levels = (0 until 90).map { 40 + it / 3 }
        val battery = BatteryView(reading, days, levels, BatteryNotes.forReading(reading, windows = true))
        val linuxBattery = BatteryView(
            SysfsBattery.parse(mapOf("status" to "Discharging", "capacity" to "62", "energy_now" to "31000000", "energy_full" to "50000000",
                "energy_full_design" to "57000000", "power_now" to "9300000", "voltage_now" to "11800000", "cycle_count" to "412", "temp" to "334",
                "manufacturer" to "SMP", "model_name" to "5B10W51867", "technology" to "Li-poly")::get)!!,
            days, levels.reversed(),
        ).let { it.copy(notes = BatteryNotes.forReading(it.reading, windows = false)) }
        val tools = UiState(page = Page.Tools)
        shoot(dir, "80-clipboard-history-wayland", Fake(all, clip = clip, images = store.imagesFolder), tools, height = 2300)
        shoot(dir, "80b-clipboard-history-windows-light", Fake(all, clip = windowsClip, battery = battery, images = store.imagesFolder), tools, dark = false, height = 3000)
        shoot(dir, "80c-clipboard-off", Fake(all, clip = ClipHistoryView()), tools, height = 1300)
        val model = Fake(all, clip = clip, images = store.imagesFolder)
        shootContent(dir, "81-clipboard-picker", 540, 460) { id.dysklink.desktop.ui.ClipPickerContent(model, onClose = {}, clock = { now }) }
        shootContent(dir, "81b-clipboard-picker-search-light", 540, 460, dark = false) {
            id.dysklink.desktop.ui.ClipPickerContent(model, onClose = {}, clock = { now }, initial = PickerState("meet", 0))
        }
        shootContent(dir, "82-battery-windows-dark", 900, 900) { id.dysklink.desktop.ui.BatteryContent(battery) }
        shootContent(dir, "82b-battery-windows-light", 900, 900, dark = false) { id.dysklink.desktop.ui.BatteryContent(battery) }
        shootContent(dir, "82c-battery-linux-dark", 640, 1000) { id.dysklink.desktop.ui.BatteryContent(linuxBattery) }
        assertTrue(File(dir, "81-clipboard-picker.png").length() > 0)
    }

    /** Wayland: the portal's dialogs as they're answered, /dev/uinput, and what's missing, where each feature is set up. */
    @Test
    fun waylandPortalSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val tools = setOf("playerctl", "pactl", "parec")
        fun host(desktop: String, osRelease: String, w: WaylandHost) = HostChecks.hints(
            HostEnv(windows = false, linux = true, env = mapOf("XDG_SESSION_TYPE" to "wayland", "XDG_CURRENT_DESKTOP" to desktop)::get,
                onPath = { it in tools }, osRelease = osRelease, notificationService = true, wayland = w),
        )
        val ready = WaylandHost(route = InputRoute.PORTAL, screenCast = true, gst = GstCheck(true, true, true))
        val ubuntu = "ID=ubuntu\nID_LIKE=debian\n"
        val on = { h: HostHints -> Fake(all, soundOut = true, screenOut = true, computers = true, remote = true, host = h) }
        val settings = UiState(page = Page.Settings)
        shoot(dir, "70-wayland-gnome-first-time", on(host("ubuntu:GNOME", ubuntu, ready)), settings, height = 2600)
        shoot(dir, "70b-wayland-gnome-waiting", on(host("ubuntu:GNOME", ubuntu, ready.copy(input = PortalStatus(PortalPhase.Waiting), screen = PortalStatus(PortalPhase.Waiting)))), settings, height = 2600)
        shoot(dir, "70c-wayland-gnome-denied", on(host("ubuntu:GNOME", ubuntu, ready.copy(input = PortalStatus(PortalPhase.Denied), screen = PortalStatus(PortalPhase.Denied)))), settings, height = 2600)
        shoot(dir, "70d-wayland-gnome-allowed-light", on(host("ubuntu:GNOME", ubuntu, ready.copy(input = PortalStatus(PortalPhase.Allowed), inputRemembered = true, screenRemembered = true))), settings, dark = false, height = 2600)
        val sway = host("sway", "ID=arch\n", ready.copy(route = InputRoute.NONE, screenCast = false))
        shoot(dir, "71-wayland-sway-nothing-settings", on(sway), settings, height = 2600)
        shoot(dir, "71b-wayland-sway-overview", on(sway), height = 1500)
        shoot(dir, "71c-wayland-sway-control-tab", on(sway), UiState(tab = "control"), height = 1100)
        val kde = host("KDE", "ID=fedora\n", ready.copy(route = InputRoute.UINPUT, gst = GstCheck(launch = true)))
        shoot(dir, "72-wayland-kde-uinput-no-pipewiresrc", on(kde), settings, height = 2600)
        shoot(dir, "72b-wayland-kde-overview", on(kde), height = 1500)
        shoot(dir, "73-wayland-control-tab-waiting", on(host("ubuntu:GNOME", ubuntu, ready.copy(input = PortalStatus(PortalPhase.Waiting)))), UiState(tab = "control"), height = 1100)
        assertTrue(File(dir, "70-wayland-gnome-first-time.png").length() > 0)
    }

    /** PC care on the Tools page: each tab on Windows and on Linux, a removal's question, and what's left after one. */
    @Test
    fun pcCareSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val day = 86_400_000L
        val winHome = File("C:\\Users\\fanny")
        val local = File(winHome, "AppData\\Local")
        val roaming = File(winHome, "AppData\\Roaming")
        fun win() = PcCareModel(FakeCare(winHome, windows = true), scope, places = CarePlaces(winHome, true, temp = File(local, "Temp"), localAppData = local, appData = roaming),
            ui = kotlinx.coroutines.Dispatchers.Unconfined, io = kotlinx.coroutines.Dispatchers.Unconfined).apply { loadsOnShow = false }
        val linHome = File("/home/fanny")
        fun lin() = PcCareModel(FakeCare(linHome), scope, places = CarePlaces(linHome, false), ui = kotlinx.coroutines.Dispatchers.Unconfined, io = kotlinx.coroutines.Dispatchers.Unconfined).apply { loadsOnShow = false }
        val ui = { UiState(page = Page.Tools).also { it.toolsView = ToolsView.Care } }
        fun item(f: File, size: Long, detail: String = "", title: String? = null) = CareItem(f, size, detail, title = title)

        // Cleaner, Windows
        val w = win()
        w.cleanGroups = linkedMapOf(
            CleanGroup.Caches to listOf(
                item(File(local, "Temp"), 3_400_000_000, "1,204 items not changed today, in ~\\AppData\\Local\\Temp", "Temporary files"),
                item(File(local, "Google\\Chrome\\User Data\\Default\\Cache"), 1_250_000_000, "Profile: Default", "Chrome cache"),
                item(File(local, "Microsoft\\Edge\\User Data\\Default\\Cache"), 640_000_000, "Profile: Default", "Edge cache"),
                item(File(roaming, "discord\\Cache"), 420_000_000, "~\\AppData\\Roaming\\discord", "Discord cache"),
                item(File(local, "Spotify\\Storage"), 380_000_000, "Songs saved for streaming", "Spotify cache"),
                item(File(local, "D3DSCache"), 120_000_000, "", "DirectX shader cache"),
                item(File(local, "NVIDIA\\DXCache"), 90_000_000, "", "NVIDIA shader cache"),
            ),
            CleanGroup.Logs to listOf(item(File(local, "CrashDumps"), 260_000_000, "14 crash dumps", "Crash dumps")),
            CleanGroup.Developer to listOf(
                item(File(winHome, ".gradle\\caches"), 4_100_000_000, "~\\.gradle\\caches", "Gradle caches"),
                item(File(local, "npm-cache\\_cacache"), 900_000_000, "~\\AppData\\Local\\npm-cache\\_cacache", "npm cache"),
                CareItem(File(winHome, ".m2\\repository"), 1_700_000_000, "Unticked: may hold artifacts you installed yourself", title = "Maven repository", preselect = false),
            ),
            CleanGroup.Installers to listOf(item(File(winHome, "Downloads\\VSCodeUserSetup-x64-1.95.exe"), 98_000_000, "Added 3 days ago"), item(File(winHome, "Downloads\\node-v22.11.0-x64.msi"), 31_000_000, "Added 2 months ago")),
            CleanGroup.OldDownloads to listOf(item(File(winHome, "Downloads\\Invoice March.pdf"), 2_400_000, "Last used 7 months ago")),
            CleanGroup.Large to listOf(item(File(winHome, "Videos\\Wedding 4K.mp4"), 6_800_000_000, "~\\Videos · used 1 years ago")),
        )
        w.cleanSelected = w.cleanGroups.filterKeys { it.selectedByDefault }.values.flatten().filter { it.preselect }.map { it.id }.toSet()
        w.cleanScanned = true
        w.cleanHints = CareHints.cleaner(true, Distro.Other, { false })
        shoot(dir, "90-care-cleaner-windows", Fake(all, care = w), ui(), height = 1900)
        shoot(dir, "90b-care-cleaner-windows-light", Fake(all, care = w), ui(), dark = false, height = 1000)
        w.message = "Moved 9 items, 11.1 GB, to the Recycle Bin. Empty the Recycle Bin to free the space. 2 items couldn't be moved, maybe because they're in use. Close the app using them and try again."
        w.messageIsProblem = true
        w.confirm = PcCareModel.Confirm.Clean
        shoot(dir, "90c-care-cleaner-confirm", Fake(all, care = w), ui(), height = 800)
        shoot(dir, "90d-care-start", Fake(all, care = win()), ui(), height = 700)

        // Cleaner, Linux
        val l = lin()
        l.cleanGroups = linkedMapOf(
            CleanGroup.Caches to listOf(item(File(linHome, ".cache/mozilla"), 820_000_000, "~/.cache"), item(File(linHome, ".cache/thumbnails"), 140_000_000, "~/.cache"), item(File(linHome, ".var/app/com.spotify.Client/cache"), 300_000_000, "~/.var/app/com.spotify.Client", "com.spotify.Client cache")),
            CleanGroup.Developer to listOf(item(File(linHome, ".cache/pip"), 600_000_000, "~/.cache/pip", "pip cache"), item(File(linHome, ".cargo/registry/cache"), 1_100_000_000, "~/.cargo/registry/cache", "Cargo downloads")),
            CleanGroup.Installers to listOf(item(File(linHome, "Downloads/code_1.95_amd64.deb"), 101_000_000, "Added 4 days ago"), item(File(linHome, "Downloads/ubuntu-24.04-desktop-amd64.iso"), 5_900_000_000, "Added 5 months ago")),
        )
        l.cleanSelected = l.cleanGroups.filterKeys { it.selectedByDefault }.values.flatten().map { it.id }.toSet()
        l.cleanScanned = true
        l.cleanHints = CareHints.cleaner(false, Distro.Debian, { it in setOf("journalctl", "flatpak") }, "1.4GB")
        shoot(dir, "91-care-cleaner-linux", Fake(all, care = l), ui(), height = 1500)

        // Duplicates
        val d = win().apply { tab = PcCareModel.Tab.Duplicates }
        val photo = (1..3).map { CareItem(File(winHome, listOf("Downloads", "Pictures\\Holiday", "Desktop")[it - 1] + "\\IMG_2041.jpg"), 4_200_000, date = now - it * 40 * day) }
        val video = (1..2).map { CareItem(File(winHome, listOf("Downloads", "Videos")[it - 1] + "\\talk.mp4"), 820_000_000, date = now - it * 10 * day) }
        d.dupGroups = listOf(DuplicateGroup("v", 820_000_000, Duplicates.order(video, KeepRule.Oldest)), DuplicateGroup("p", 4_200_000, Duplicates.order(photo, KeepRule.Oldest)))
        d.dupSelected = d.dupGroups.flatMap { it.files.drop(1).map { f -> f.id } }.toSet()
        d.dupScanned = true
        shoot(dir, "92-care-duplicates", Fake(all, care = d), ui(), height = 1000)

        // Uninstall, Windows
        val u = win().apply { tab = PcCareModel.Tab.Uninstall }
        u.apps = listOf(
            PcApp("1", "7-Zip 23.01 (x64)", AppKind.Windows, "Igor Pavlov", "23.01", 5_600_000, java.time.LocalDate.of(2024, 2, 3)),
            PcApp("2", "Discord", AppKind.Windows, "Discord Inc.", "1.0.9171", 410_000_000, java.time.LocalDate.of(2025, 9, 12)),
            PcApp("3", "Spotify Music", AppKind.Store, "Spotify AB", "1.250.759.0"),
            PcApp("4", "Visual Studio Code", AppKind.Windows, "Microsoft Corporation", "1.95.3", 380_000_000, java.time.LocalDate.of(2025, 11, 1), forAllUsers = false),
            PcApp("5", "Zoom Workplace", AppKind.Windows, "Zoom Video Communications, Inc.", "6.1.0", 290_000_000, java.time.LocalDate.of(2024, 1, 15), forAllUsers = true),
        )
        u.uninstallHints = CareHints.uninstall(true, Distro.Other, { false })
        shoot(dir, "93-care-uninstall-windows", Fake(all, care = u), ui(), height = 1000)
        val chosen = u.apps[1]
        u.choose(chosen)
        u.isRunning = true
        u.leftovers = listOf(
            Leftover(CareItem(File(roaming, "discord"), 610_000_000, "~\\AppData\\Roaming"), byId = false),
            Leftover(CareItem(File(local, "Discord"), 85_000_000, "~\\AppData\\Local"), byId = true),
        )
        u.leftoverSelected = setOf(u.leftovers[1].id)
        shoot(dir, "93b-care-uninstall-running", Fake(all, care = u), ui(), height = 900)
        u.quitPhase = QuitTracker.Phase.Stuck
        shoot(dir, "93c-care-uninstall-stuck", Fake(all, care = u), ui(), height = 900)
        u.isRunning = false
        u.quitPhase = QuitTracker.Phase.Idle
        u.confirm = PcCareModel.Confirm.Uninstall(chosen, UninstallPlan(listOf("powershell")))
        shoot(dir, "93d-care-uninstall-confirm", Fake(all, care = u), ui(), height = 900)
        u.confirm = null
        u.chosenGone = true
        u.message = "Discord was uninstalled. Its leftovers are below: move the ticked ones to the Recycle Bin."
        shoot(dir, "93e-care-uninstall-leftovers", Fake(all, care = u), ui(), height = 900)

        // Uninstall, Linux
        val lu = lin().apply { tab = PcCareModel.Tab.Uninstall }
        lu.apps = listOf(
            PcApp("a", "Firefox", AppKind.Snap, "mozilla", "132.0", packageId = "firefox", forAllUsers = true),
            PcApp("b", "GNU Image Manipulation Program", AppKind.Flatpak, "", "2.10.38", 1_100_000_000, packageId = "org.gimp.GIMP", installation = "system", forAllUsers = true),
            PcApp("c", "Obsidian", AppKind.AppImage, "", "1.7.7", 120_000_000, location = File(linHome, "Applications/Obsidian-1.7.7.AppImage")),
            PcApp("d", "VLC media player", AppKind.Deb, "", "3.0.20-3build6", 9_800_000, packageId = "vlc", forAllUsers = true),
        )
        lu.uninstallHints = CareHints.uninstall(false, Distro.Debian, { false })
        shoot(dir, "94-care-uninstall-linux-no-pkexec", Fake(all, care = lu), ui(), height = 1000)
        lu.choose(lu.apps[3])
        lu.confirm = PcCareModel.Confirm.Uninstall(lu.apps[3], UninstallPlan(listOf("pkexec"), alsoRemoves = listOf("vlc-plugin-base", "vlc-plugin-video-output"), needsPassword = true))
        shoot(dir, "94b-care-uninstall-linux-confirm", Fake(all, care = lu), ui(), height = 800)

        // Startup, Windows
        val s = win().apply { tab = PcCareModel.Tab.Startup }
        fun entry(t: String, name: String, purpose: String, kind: StartupKind, off: Boolean = false, editable: Boolean = true, admin: Boolean = false, publisher: String = "", inWindows: Boolean = false, boot: Boolean = false) =
            StartupEntry("$kind:$t", t, name, publisher, purpose, "\"C:\\Program Files\\$name\\$t.exe\" --minimized", kind, off || inWindows, editable && !inWindows, admin, offInWindows = inWindows, atBoot = boot)
        s.startup = listOf(
            entry("Discord", "Discord", "Opens in the background at login", StartupKind.Run, publisher = "Discord Inc."),
            entry("OneDrive", "OneDrive", "Syncs your files", StartupKind.Run, publisher = "Microsoft Corporation", inWindows = true),
            entry("Spotify", "Spotify", "Opens at login", StartupKind.Run, off = true, publisher = "Spotify AB"),
            entry("Notion.lnk", "Notion", "Opens at login", StartupKind.Folder),
            entry("OneDrive Standalone Update Task-S-1-5-21", "OneDrive", "Checks for updates", StartupKind.Task),
            entry("GoogleUpdateTaskMachineCore", "Google Updater", "Checks for updates", StartupKind.Task, editable = false, admin = true, boot = true),
            entry("SecurityHealth", "Windows Security", "Shows an icon in the tray", StartupKind.Run, editable = false, admin = true),
            entry("RtkAudUService", "Realtek Audio", "Background helper", StartupKind.Run, editable = false, admin = true),
            entry("TeamViewer", "TeamViewer", "Runs as a service · running", StartupKind.Service, editable = false, admin = true, boot = true),
        )
        s.startupLoaded = true
        s.startupHints = CareHints.startup(true, s.startup)
        shoot(dir, "95-care-startup-windows", Fake(all, care = s), ui(), height = 1700)

        // Startup, Linux
        val ls = lin().apply { tab = PcCareModel.Tab.Startup }
        fun lentry(t: String, name: String, purpose: String, kind: StartupKind, off: Boolean = false, part: Boolean = false) =
            StartupEntry("$kind:$t", t, name, "", purpose, "/usr/bin/$t", kind, off, editable = (!part || off) && kind != StartupKind.SystemUnit, adminOnly = kind == StartupKind.SystemUnit, desktopPart = part, atBoot = kind == StartupKind.SystemUnit)
        ls.startup = listOf(
            lentry("nextcloud.desktop", "Nextcloud", "Syncs your files", StartupKind.Autostart),
            lentry("ulauncher.desktop", "Ulauncher", "Opens in the background at login", StartupKind.Autostart, off = true),
            lentry("syncthing.service", "Syncthing", "Syncs your files", StartupKind.UserUnit),
            lentry("org.gnome.SettingsDaemon.Power.desktop", "Power", "Background helper", StartupKind.Autostart, part = true),
            lentry("pipewire.service", "PipeWire Multimedia Service", "Runs in the background", StartupKind.UserUnit, part = true),
            lentry("docker.service", "Docker Application Container Engine", "Background helper", StartupKind.SystemUnit),
            lentry("cups.service", "CUPS Scheduler", "Runs as a service", StartupKind.SystemUnit),
        )
        ls.startupLoaded = true
        ls.startupHints = CareHints.startup(false, ls.startup)
        shoot(dir, "96-care-startup-linux", Fake(all, care = ls), ui(), height = 1500)
        shoot(dir, "96b-care-startup-linux-light", Fake(all, care = ls), ui(), dark = false, height = 1500)
        shoot(dir, "97-tools-with-switcher", Fake(all, care = win()), UiState(page = Page.Tools), height = 800)
        assertTrue(File(dir, "90-care-cleaner-windows.png").length() > 0)
    }

    /** The Launcher with made-up apps, files and actions, through the real ranking. */
    private class FakeLauncher(view: LauncherView) : LauncherModel {
        override val view = MutableStateFlow(view)
        private val now = 1_762_900_000_000L
        private fun app(name: String, detail: String = "") = LauncherItem("app:$name", LauncherKind.App, name, detail, LauncherTarget.Copy(name))
        private val candidates = listOf(
            app("Visual Studio Code", "Code editor"), app("Code::Blocks", "IDE"), app("Calculator"), app("Firefox Web Browser", "Web Browser"),
            app("Microsoft Teams", "Microsoft Store app"), app("Notepad", "Accessories"),
            LauncherItem("action:send:p1", LauncherKind.Action, "Send files to Pixel 8", "Phone", LauncherTarget.Action("send:p1"), "kirim file send"),
            LauncherItem("action:ring:p1", LauncherKind.Action, "Ring Pixel 8", "Find phone", LauncherTarget.Action("ring:p1")),
            LauncherItem("action:clipboard", LauncherKind.Action, "Clipboard history", "Everything you copied", LauncherTarget.Action("clipboard"), "copy code snippets"),
            LauncherItem("action:pccare", LauncherKind.Action, "PC care", "Clean up, duplicates, startup apps", LauncherTarget.Action("pccare")),
            LauncherItem("action:open", LauncherKind.Action, "Open DyskLink", "Main window", LauncherTarget.Action("open")),
            LauncherItem("setting:Display", LauncherKind.Setting, "Display", "Windows Settings", LauncherTarget.Command(listOf("x")), "layar monitor"),
        )
        override fun results(query: String) = LauncherQuery.results(
            query, candidates, Frecency().used("app:Visual Studio Code", now), now,
            clips = { listOf(LauncherItem("clip:1", LauncherKind.Clip, "git commit -m \"code review fixes\"", "From the clipboard history", LauncherTarget.Clip("1"))) },
            emoji = { LauncherEmoji.items(it, 0, listOf("🔥", "🙏"), canType = true).first },
        )
        override suspend fun files(scope: LauncherSearch.Scope, limit: Int) = emptyList<LauncherItem>()
        override fun run(item: LauncherItem, modifier: LauncherModifier) {}
    }

    @Test
    fun launcherAndWindowSnapshots() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        val all = listOf(phone, tablet, mac)
        val ready = HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Ready)
        val view = LauncherView(
            enabled = true, combo = "Alt+Space", hotkey = ready, apps = 142, files = 18_406,
            hints = HostChecks.launcherHints(ready, LauncherService.DEFAULT_COMBO, "dysklink --launcher", windows = true, currentDesktop = null, canType = true),
        )
        val launcher = FakeLauncher(view)
        val files = listOf(
            IndexedFile("C:\\Users\\fanny\\Documents\\code-review.docx", "code-review.docx", false),
            IndexedFile("C:\\Users\\fanny\\Projects\\dysklink-code", "dysklink-code", true),
        ).let { LauncherQuery.fileItems(it, "C:\\Users\\fanny") }
        shootContent(dir, "a0-launcher-results", 660, 470) { LauncherContent(launcher, onClose = {}, initial = LauncherState("code", 0), initialFiles = files) }
        shootContent(dir, "a1-launcher-emoji", 660, 470) { LauncherContent(launcher, onClose = {}, initial = LauncherState(":senyum", 1), initialFiles = emptyList()) }
        shootContent(dir, "a1b-launcher-emoji-tone", 660, 470) { LauncherContent(launcher, onClose = {}, initial = LauncherState(":jempol sawo", 0), initialFiles = emptyList()) }
        shootContent(dir, "a2-launcher-sum", 660, 470, dark = false) { LauncherContent(launcher, onClose = {}, initial = LauncherState("12*7+3", 0), initialFiles = emptyList()) }
        shootContent(dir, "a2b-launcher-empty", 660, 470) { LauncherContent(launcher, onClose = {}, initialFiles = emptyList()) }

        val monitors = listOf(Monitor(Rect(0, 0, 1920, 1080), Rect(0, 0, 1920, 1040)), Monitor(Rect(-1920, 0, 1920, 1080), Rect(-1920, 0, 1920, 1040)))
        val key = Layouts.monitorKey(monitors)
        val work = Layouts.capture("Work", listOf(
            OpenWindow(1, "chrome.exe", "Inbox", Rect(0, 0, 960, 1040)), OpenWindow(2, "code.exe", "dysklink", Rect(960, 0, 960, 1040)),
            OpenWindow(3, "windowsterminal.exe", "PowerShell", Rect(-1920, 0, 1920, 1040)),
        ), monitors, id = "w")
        val laptopOnly = Layouts.capture("Couch", listOf(OpenWindow(1, "chrome.exe", "Inbox", Rect(0, 0, 1366, 741))), listOf(Monitor(Rect(0, 0, 1366, 768), Rect(0, 27, 1366, 741))), id = "c")
        val tidy = WindowTidyView(
            enabled = true, supported = true, mods = TidyMods.CtrlAlt, hotkey = ready.copy(taken = listOf("Ctrl+Alt+Super+Left"), detail = "Another app already uses Ctrl+Alt+Super+Left."),
            layouts = listOf(laptopOnly, work), monitorKey = key, monitors = Layouts.describe(monitors), message = "Saved Work: 3 windows on 2 monitors.",
        ).let { it.copy(hints = HostChecks.windowTidyHints(true, false, null, null, it.hotkey, it.mods)) }
        val desk = UiState(page = Page.Tools).also { it.toolsView = ToolsView.Desk }
        shoot(dir, "a3-tools-launcher-windows", Fake(all, launcher = launcher, tidy = tidy), desk, height = 1900)
        shoot(dir, "a3b-light-tools-launcher-windows", Fake(all, launcher = launcher, tidy = tidy), desk, dark = false, height = 1900)
        val off = UiState(page = Page.Tools).also { it.toolsView = ToolsView.Desk }
        shoot(dir, "a4-tools-launcher-off", Fake(all, launcher = FakeLauncher(LauncherView()), tidy = WindowTidyView()), off, height = 800)
        val wayland = WindowTidyView(enabled = false, supported = false, hints = HostChecks.windowTidyHints(false, true, "GNOME", null, HotkeyStatus(HotkeyBackend.Command), TidyMods.CtrlAlt))
        val waylandLauncher = LauncherView(
            enabled = true, combo = "Alt+Space", hotkey = HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready), apps = 87, files = 9_210,
            hints = HostChecks.launcherHints(HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready), LauncherService.DEFAULT_COMBO, "dysklink --launcher", false, "GNOME", true),
        )
        shoot(dir, "a5-tools-wayland-note", Fake(all, launcher = FakeLauncher(waylandLauncher), tidy = wayland), off, height = 1300)
        val kde = wayland.copy(hints = HostChecks.windowTidyHints(false, true, "KDE", null, HotkeyStatus(HotkeyBackend.Command), TidyMods.CtrlAlt))
        shoot(dir, "a5b-tools-wayland-kde", Fake(all, launcher = FakeLauncher(LauncherView()), tidy = kde), off, height = 900)
        val tray = TrayMenuConfig().setShown(TraySection.Water, false).setShown(TraySection.Launcher, true).move(TraySection.Launcher, -1).move(TraySection.Launcher, -1)
        shoot(dir, "a6-settings-tray-menu", Fake(all, tray = tray), UiState(page = Page.Settings), height = 1500)
        shoot(dir, "a6b-light-settings-tray-menu", Fake(all, tray = tray), UiState(page = Page.Settings), dark = false, height = 1500)
        assertTrue(File(dir, "a0-launcher-results.png").length() > 0)
    }

}
