package id.phonelink.android

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
import id.phonelink.android.service.PhoneDataImpl
import id.phonelink.android.service.DyskAccessibilityService
import id.phonelink.android.service.PhoneLinkService
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
        PhoneLinkService.start(this)

        screenPeer = savedInstanceState?.getString(KEY_SCREEN_PEER)
        handleShare(intent)
        handleScreenRequest(intent)
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
                    PhoneLinkApp(
                        state = UiState(
                            localName, peers, prompt, manualTargets, lastError, restricted,
                            transfers, commands, commandResult, clipboardEnabled, clipboardAuto, phoneEnabled, phoneActive, sharingPeer,
                        ),
                        actions = UiActions(
                            requestPairing = connections::requestPairing,
                            acceptPairing = connections::acceptPairing,
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
            val peerId = connections.peers.value.firstOrNull { it.isPaired && it.isConnected }?.id
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
        screenConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun grantFeature(feature: PhoneFeature) {
        when {
            feature == PhoneFeature.Control -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            feature == PhoneFeature.Notifications ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            feature == PhoneFeature.Files && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            )
            else -> featurePermissions.launch(PhoneDataImpl.runtimePermissions(feature).toTypedArray())
        }
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
        private const val KEY_SCREEN_PEER = "screenPeer"
    }
}
