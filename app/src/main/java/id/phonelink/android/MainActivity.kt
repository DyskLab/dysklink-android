package id.phonelink.android

import id.phonelink.android.core.Transfer
import android.webkit.MimeTypeMap
import android.provider.DocumentsContract
import android.os.Environment
import android.content.ActivityNotFoundException
import android.app.DownloadManager
import android.app.AlertDialog
import id.phonelink.android.service.Updates
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.ConnectionManager
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.service.DeviceActionsImpl
import id.phonelink.android.service.LocalDevice
import id.phonelink.android.service.PhoneApps
import id.phonelink.android.service.PhoneDataImpl
import id.phonelink.android.service.DyskAccessibilityService
import id.phonelink.android.service.FocusDnd
import id.phonelink.android.service.ChargeReminder
import id.phonelink.android.service.PhoneLinkService
import id.phonelink.android.service.PrayerWeatherStore
import id.phonelink.android.service.ScreenCaptureService
import id.phonelink.android.ui.Palette
import id.phonelink.android.ui.PhoneLinkApp
import id.phonelink.android.ui.PhoneLinkTheme
import id.phonelink.android.ui.UiActions
import id.phonelink.android.ui.UiState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val batteryRestricted = mutableStateOf(false)
    private val dndAccess = mutableStateOf(false)
    private val prayerRequest = mutableStateOf(0)
    /** A feature name and when it was asked for, so the same one can be opened twice. */
    private val featureRequest = mutableStateOf<Pair<String, Long>?>(null)
    private val deviceActions by lazy { DeviceActionsImpl(this) }
    private var pickFilesFor: String? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val peerId = pickFilesFor ?: return@registerForActivityResult
            PhoneLinkService.connectionManager.value?.let { sendUris(it, peerId, uris) }
        }

    private val featurePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            PhoneLinkService.connectionManager.value?.phone?.refresh()
            if (results.values.any { !it }) toast("Permission denied. You can change it in the app settings.")
        }

    private var screenPeer: String? = null

    private val screenConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val peerId = screenPeer
            val data = result.data
            if (result.resultCode == RESULT_OK && peerId != null && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data, peerId)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The service keeps running even if the notification permission is denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Updates.prepare(this)
        PhoneLinkService.start(this)

        screenPeer = savedInstanceState?.getString(KEY_SCREEN_PEER)
        handleShare(intent)
        handleScreenRequest(intent)
        handleCameraRequest(intent)
        handleOpen(intent)
        val localName = LocalDevice(this).name
        setContent {
            PhoneLinkTheme {
                val manager by PhoneLinkService.connectionManager.collectAsStateWithLifecycle()
                val connections = manager
                if (connections == null) {
                    Box(Modifier.fillMaxSize().background(Palette.Ink))
                } else {
                    val peers by connections.peers.collectAsStateWithLifecycle()
                    val prompt by connections.prompt.collectAsStateWithLifecycle()
                    val manualTargets by connections.manualTargets.collectAsStateWithLifecycle()
                    val lastError by connections.lastError.collectAsStateWithLifecycle()
                    val restricted by batteryRestricted
                    val transfers by connections.fileTransfers.transfers.collectAsStateWithLifecycle()
                    val commands by connections.commands.collectAsStateWithLifecycle()
                    val commandResult by connections.commandResult.collectAsStateWithLifecycle()
                    val clipboardEnabled by connections.clipboardEnabled.collectAsStateWithLifecycle()
                    val clipboardAuto by connections.clipboardAuto.collectAsStateWithLifecycle()
                    val phoneEnabled by connections.phone.enabled.collectAsStateWithLifecycle()
                    val phoneActive by connections.phone.active.collectAsStateWithLifecycle()
                    val sharingPeer by ScreenCaptureService.activePeer.collectAsStateWithLifecycle()
                    val updates by Updates.state.collectAsStateWithLifecycle()
                    val notch by connections.notch.collectAsStateWithLifecycle()
                    val ringingMacs by connections.ringingMacs.collectAsStateWithLifecycle()
                    val focusDnd by FocusDnd.enabled.collectAsStateWithLifecycle()
                    val charge by ChargeReminder.settings.collectAsStateWithLifecycle()
                    val chargeSessions by ChargeReminder.sessions.collectAsStateWithLifecycle()
                    val phonesEnabled by connections.phonesEnabled.collectAsStateWithLifecycle()
                    PhoneLinkApp(
                        state = UiState(
                            localName, peers, prompt, manualTargets, lastError, restricted,
                            transfers, commands, commandResult, clipboardEnabled, clipboardAuto, phoneEnabled, phoneActive, sharingPeer,
                            updates, Updates.currentVersion,
                            notch = notch,
                            ringingMacs = ringingMacs,
                            focusDnd = focusDnd,
                            dndAccess = dndAccess.value,
                            charge = charge,
                            chargeSessions = chargeSessions,
                            prayerRequest = prayerRequest.value,
                            featureRequest = featureRequest.value,
                            phonesEnabled = phonesEnabled,
                        ),
                        actions = UiActions(
                            requestPairing = connections::requestPairing,
                            submitCode = connections::submitCode,
                            rejectPairing = connections::rejectPairing,
                            unpair = connections::unpair,
                            addManual = connections::addManual,
                            removeManual = connections::removeManual,
                            allowBackground = ::requestBatteryExemption,
                            openAppSettings = ::openAppSettings,
                            sendFiles = { peerId ->
                                pickFilesFor = peerId
                                filePicker.launch(arrayOf("*/*"))
                            },
                            sendInput = connections::sendInput,
                            sendLocalFile = { peerId, file, kind ->
                                connections.fileTransfers.sendFile(peerId, file.name, file.length(), kind) { file.inputStream() }
                            },
                            setPhonesEnabled = connections::setPhonesEnabled,
                            wakeMac = connections::wakeMac,
                            macAction = { peer, action -> connections.sendMacAction(action, peer) },
                            terminalLink = id.phonelink.android.ui.TerminalLink(connections.terminalEvents, connections::sendTerminal),
                            startCamera = ::startCamera,
                            startPhoneSound = ::startPhoneSound,
                            macScreenLink = id.phonelink.android.ui.MacScreenLink(
                                setSink = { connections.macScreenSink = it },
                                start = { peer, display -> connections.startMacScreen(peer, display) },
                                extend = connections::extendMacScreen,
                                stop = connections::stopMacScreen,
                                sync = connections::syncMacScreen,
                                pointer = connections::sendMacPointer,
                                input = connections::sendInput,
                            ),
                            sendClipboardText = { text ->
                                connections.shareClipboard(text, force = true) { ok ->
                                    runOnUiThread { toast(if (ok) "Text sent to your devices' clipboard" else "Couldn't send the text. It may be too long.") }
                                }
                            },
                            sendMedia = connections::sendMedia,
                            runCommand = connections::runCommand,
                            setClipboardEnabled = connections::setClipboardEnabled,
                            setClipboardAuto = { on ->
                                connections.setClipboardAuto(on)
                                if (on && !DyskAccessibilityService.isRunning) {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            },
                            setPhoneFeature = { feature, on ->
                                connections.phone.setEnabled(feature, on)
                                if (on && feature !in connections.phone.active.value) grantFeature(feature)
                            },
                            grantPhoneFeature = ::grantFeature,
                            startScreen = ::askForScreen,
                            stopScreen = { ScreenCaptureService.stop(this) },
                            downloadUpdate = {
                                updates.available?.let { release ->
                                    runCatching { startActivity(Updates.downloadIntent(release)) }
                                        .onFailure { toast("No browser found to download the update.") }
                                }
                            },
                            skipUpdate = { Updates.skip(this) },
                            checkUpdates = { lifecycleScope.launch { Updates.check(this@MainActivity, manual = true) } },
                            setAutoUpdates = { Updates.setAuto(this, it) },
                            openTransfer = ::openReceived,
                            notchAction = connections::sendNotchAction,
                            ringMac = connections::ringMac,
                            clearTransfers = connections.fileTransfers::clearHistory,
                            removeTransfer = { connections.fileTransfers.remove(it.id) },
                            shareTransfer = ::shareReceived,
                            openReceivedFolder = ::openReceivedFolder,
                            setCharge = { ChargeReminder.update(this, it) },
                            clearCharge = { ChargeReminder.clearHistory(this) },
                            setFocusDnd = { FocusDnd.setEnabled(this, it) },
                            openExactAlarms = {
                                runCatching { startActivity(PrayerWeatherStore.exactAlarmSettings(this)) }
                                    .onFailure { openAppSettings() }
                            },
                            openDndSettings = {
                                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                                    .onFailure { openAppSettings() }
                            },
                        ),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        handleScreenRequest(intent)
        handleCameraRequest(intent)
        handleOpen(intent)
    }

    private fun handleOpen(intent: Intent?) {
        val open = intent?.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN)
        when {
            open == OPEN_PRAYER -> prayerRequest.value += 1
            // From a widget: a feature of the connected Mac.
            open.startsWith(OPEN_FEATURE_PREFIX) -> featureRequest.value = open.removePrefix(OPEN_FEATURE_PREFIX) to System.nanoTime()
        }
    }

    /** Android only allows clipboard reads while the app has focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val text = runCatching { deviceActions.readClipboard() }.getOrNull() ?: return
        PhoneLinkService.connectionManager.value?.shareClipboard(text)
    }

    private fun handleShare(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        intent.action = null
        val uris = if (action == Intent.ACTION_SEND) {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        lifecycleScope.launch {
            val connections = PhoneLinkService.connectionManager.filterNotNull().first()
            val peerId = connections.peers.value.let { all -> all.firstOrNull { it.isMac && it.isPaired && it.isConnected } ?: all.firstOrNull { it.isPaired && it.isConnected } }?.id
            when {
                peerId == null -> toast("No Mac connected")
                uris.isNotEmpty() -> sendUris(connections, peerId, uris)
                text != null -> {
                    connections.shareClipboard(text)
                    toast("Text sent to the Mac clipboard")
                }
            }
        }
    }

    private fun sendUris(connections: ConnectionManager, peerId: String, uris: List<Uri>) {
        uris.forEach { uri ->
            val (name, size) = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                    cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
            } ?: return@forEach toast("Can't read the file")
            connections.fileTransfers.sendFile(peerId, name, size) {
                contentResolver.openInputStream(uri) ?: error("Can't open the file")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN_PEER, screenPeer)
    }

    private var cameraPeer: String? = null
    private var cameraFront = false

    private var soundPeer: String? = null

    private val soundConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val peerId = soundPeer
            val data = result.data
            if (result.resultCode == RESULT_OK && peerId != null && data != null) {
                id.phonelink.android.service.PhoneSoundService.start(this, result.resultCode, data, peerId)
            }
        }

    private val soundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) askForSound() else toast("DyskLink needs the microphone permission to record the phone's sound. Android uses it for app sound too; the microphone itself isn't recorded.")
        }

    /** Android needs the audio permission and its recording dialog. Only called after a tap on the phone. */
    fun startPhoneSound(peerId: String) {
        soundPeer = peerId
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) askForSound()
        else soundPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askForSound() {
        soundConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val peerId = cameraPeer ?: return@registerForActivityResult
            if (granted) id.phonelink.android.service.CameraStreamService.start(this, peerId, cameraFront)
            else toast("DyskLink needs the camera permission to share the camera")
        }

    /** Starts the camera for a Mac, asking for the permission first if needed. Only called after a tap on the phone. */
    fun startCamera(peerId: String, front: Boolean) {
        cameraPeer = peerId
        cameraFront = front
        if (checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            id.phonelink.android.service.CameraStreamService.start(this, peerId, front)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Comes from the Mac's camera request notification, which the user tapped. */
    private fun handleCameraRequest(intent: Intent?) {
        val peerId = intent?.getStringExtra(EXTRA_CAMERA_PEER) ?: return
        intent.removeExtra(EXTRA_CAMERA_PEER)
        lifecycleScope.launch {
            val connections = PhoneLinkService.connectionManager.filterNotNull().first()
            if (connections.peers.value.any { it.id == peerId && it.isPaired && it.isMac }) startCamera(peerId, false)
        }
    }

    /** Comes from the Mac request notification. Only opens the Android dialog and records nothing. */
    private fun handleScreenRequest(intent: Intent?) {
        val peerId = intent?.getStringExtra(EXTRA_SCREEN_PEER) ?: return
        intent.removeExtra(EXTRA_SCREEN_PEER)
        lifecycleScope.launch {
            val connections = PhoneLinkService.connectionManager.filterNotNull().first()
            if (connections.peers.value.any { it.id == peerId && it.isPaired }) askForScreen(peerId)
        }
    }

    private fun askForScreen(peerId: String) {
        screenPeer = peerId
        // The phone's sound goes with the screen, which needs the audio permission before Android's recording dialog.
        if (id.phonelink.android.service.AudioShare.wantsSoundWithScreen(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            screenSoundPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchScreenConsent()
        }
    }

    /** Without the permission the screen still goes, only silent. */
    private val screenSoundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchScreenConsent() }

    private fun launchScreenConsent() {
        screenConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun grantFeature(feature: PhoneFeature) {
        when {
            feature == PhoneFeature.Control -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            feature == PhoneFeature.ScreenTime -> startActivity(PhoneApps.usageAccessIntent(this))
            feature == PhoneFeature.Notifications ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            feature == PhoneFeature.Files && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            )
            else -> featurePermissions.launch(PhoneDataImpl.runtimePermissions(feature).toTypedArray())
        }
    }

    /** Opens a file from the Mac in an app that can show it. */
    private fun openReceived(transfer: Transfer) {
        val uri = transfer.uri?.let(Uri::parse) ?: return
        // The file may have been moved or deleted since it arrived.
        val exists = runCatching { contentResolver.openFileDescriptor(uri, "r")?.close() != null }.getOrDefault(false)
        if (!exists) return toast("${transfer.name} was moved or deleted.")
        val extension = transfer.name.substringAfterLast('.', "").lowercase()
        val type = contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            showNoAppFor(transfer)
        }
    }

    private fun shareReceived(transfer: Transfer) {
        val uri = transfer.uri?.let(Uri::parse) ?: return
        val exists = runCatching { contentResolver.openFileDescriptor(uri, "r")?.close() != null }.getOrDefault(false)
        if (!exists) return toast("${transfer.name} was moved or deleted.")
        val extension = transfer.name.substringAfterLast('.', "").lowercase()
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, transfer.name))
    }

    private fun showNoAppFor(transfer: Transfer) {
        // Files are saved in Download/DyskLink from Android 10. Older versions keep them in the app's own folder.
        val inDownloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val dialog = AlertDialog.Builder(this)
            .setTitle("No app can open this file")
            .setMessage(
                if (inDownloads) "${transfer.name} is saved in Download/DyskLink. Install an app that opens this kind of file, or open the folder to share it."
                else "${transfer.name} is saved in DyskLink's own folder. Install an app that opens this kind of file.",
            )
            .setNegativeButton("Close", null)
        if (inDownloads) dialog.setPositiveButton("Open folder") { _, _ -> openReceivedFolder() }
        dialog.show()
    }

    private fun openReceivedFolder() {
        val folder = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:${Environment.DIRECTORY_DOWNLOADS}/DyskLink",
        )
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
        )
        if (attempts.none { runCatching { startActivity(it) }.isSuccess }) toast("No file manager found.")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        PhoneLinkService.setUiVisible(true)
    }

    override fun onResume() {
        super.onResume()
        val power = getSystemService(PowerManager::class.java)
        batteryRestricted.value = !power.isIgnoringBatteryOptimizations(packageName)
        dndAccess.value = FocusDnd.hasAccess(this)
        // The user may have just returned from a permission page in Settings.
        PhoneLinkService.connectionManager.value?.phone?.refresh()
    }

    override fun onStop() {
        PhoneLinkService.setUiVisible(false)
        super.onStop()
    }

    @SuppressLint("BatteryLife") // This app has to keep its connection alive.
    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        )
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_SCREEN_PEER = "id.phonelink.android.SCREEN_PEER"
        const val EXTRA_CAMERA_PEER = "id.phonelink.android.CAMERA_PEER"
        const val EXTRA_OPEN = "id.phonelink.android.OPEN"
        const val OPEN_PRAYER = "prayer"
        const val OPEN_FEATURE_PREFIX = "feature:"
        private const val KEY_SCREEN_PEER = "screenPeer"
    }
}
