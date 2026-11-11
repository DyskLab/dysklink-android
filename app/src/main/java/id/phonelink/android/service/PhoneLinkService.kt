package id.phonelink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.phonelink.android.ClipboardSendActivity
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.ConnectionManager
import id.phonelink.android.core.PairingPrompt
import id.phonelink.android.core.PhoneLinkProtocol
import id.phonelink.android.core.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the connection alive even when the app UI is closed. */
class PhoneLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var photoSyncer: PhotoSyncer? = null
    private val rescan = Channel<Unit>(Channel.CONFLATED)
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var discovery: NsdDiscovery? = null
    @Volatile private var watchers: PhoneWatchers? = null

    /** Whether NSD should be looking now; set from the main thread. */
    @Volatile private var discoveryWanted = true

    /** Whether the screen is on. Discovery pauses while it's off; turning it on looks again at once. */
    private val screenOn = MutableStateFlow(true)
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn.value = true
                    rescan.trySend(Unit)
                }
                Intent.ACTION_SCREEN_OFF -> screenOn.value = false
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A new network: scan again from the shortest wait, and look for services on it.
            rescan.trySend(Unit)
            android.os.Handler(mainLooper).post { if (discoveryWanted) discovery?.restart() }
        }
    }

    /** The pairing key lives in Android Keystore and is only used to sign TLS handshakes. */
    private fun pairingKeyProtection() =
        android.security.keystore.KeyProtection.Builder(android.security.keystore.KeyProperties.PURPOSE_SIGN)
            .setDigests(
                android.security.keystore.KeyProperties.DIGEST_NONE,
                android.security.keystore.KeyProperties.DIGEST_SHA256,
                android.security.keystore.KeyProperties.DIGEST_SHA384,
                android.security.keystore.KeyProperties.DIGEST_SHA512,
            )
            .build()

    override fun onCreate() {
        id.phonelink.android.core.CoreLog.sink = { level, tag, message, error ->
            if (level == id.phonelink.android.core.CoreLog.Level.WARN) android.util.Log.w(tag, message, error) else android.util.Log.i(tag, message)
        }
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        createWifiLock()
        ChargeReminder.start(this)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        // Screen on and off are only sent to receivers registered while running.
        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(
            this, screenReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Creating keys and reading files is slow, so it can't run on the main thread.
        scope.launch(Dispatchers.IO) {
            val device = LocalDevice(this@PhoneLinkService)
            val deviceActions = DeviceActionsImpl(this@PhoneLinkService)
            val connections = ConnectionManager(
                filesDir, scope, device::hello, device::info,
                isUiVisible = { uiVisible.value },
                actions = deviceActions,
                fileSinks = DownloadSinks(this@PhoneLinkService),
                phoneData = PhoneDataImpl(this@PhoneLinkService),
                keyStoreProtection = pairingKeyProtection(),
            )
            manager.value = connections
            launch(Dispatchers.Main) { connections.clipboardAuto.collect { DyskAccessibilityService.refreshCopyDetection() } }

            val phoneWatchers = PhoneWatchers(this@PhoneLinkService, connections.phone)
            watchers = phoneWatchers
            launch(Dispatchers.Main) { connections.phone.active.collect(phoneWatchers::sync) }

            discovery = NsdDiscovery(
                this@PhoneLinkService,
                connections::addDiscovered,
                connections::removeDiscovered,
            ).also { it.start() }
            // Multicast discovery costs battery: stop it while the screen is off, and while every paired device is
            // connected unless the app is open (where a new device may be paired).
            launch(Dispatchers.Main) {
                combine(connections.peers.map(::allPairedConnected), uiVisible, screenOn) { all, visible, on -> on && (!all || visible) }
                    .distinctUntilChanged()
                    .collect { wanted ->
                        discoveryWanted = wanted
                        if (wanted) discovery?.start() else discovery?.pause()
                    }
            }
            // A device dropping off: look for it again right away.
            launch {
                var previous = emptySet<String>()
                connections.peers
                    .map { peers -> peers.filter { it.isPaired && it.isConnected }.map { it.id }.toSet() }
                    .distinctUntilChanged()
                    .collect { now ->
                        if (!now.containsAll(previous)) rescan.trySend(Unit)
                        previous = now
                    }
            }
            launch { holdWifiLockWhileBusy(connections) }
            // Sound to or from a Mac or PC that dropped off ends here too, instead of playing or recording for nobody.
            launch(Dispatchers.Main) {
                connections.peers
                    .map { peers -> peers.filter { it.isConnected }.map { it.id }.toSet() }
                    .distinctUntilChanged()
                    .collect { connected -> AudioShare.keepOnly(this@PhoneLinkService, connected) }
            }

            // Other phones: listen, and say so on the network while phone connections are on.
            launch {
                connections.phonesEnabled.collect { on -> if (on) connections.startServer() }
            }
            launch(Dispatchers.Main) {
                connections.serverPort.collect { port -> discovery?.advertise(device.name, connections.localDeviceId, port) }
            }

            FocusDnd.load(this@PhoneLinkService)
            launch(Dispatchers.Main) {
                combine(connections.notch, FocusDnd.enabled) { notch, _ -> notch }
                    .collect { FocusDnd.sync(this@PhoneLinkService, it) }
            }
            launch { showStatus(connections) }
            launch(Dispatchers.Main) {
                // Peers change for every INFO; widgets only show names, battery and connection.
                connections.peers
                    .distinctUntilChangedBy { peers -> peers.map(::widgetFields) }
                    .collect {
                        MacWidget.render(this@PhoneLinkService, it)
                        Widgets.renderAll(this@PhoneLinkService)
                    }
            }
            launch(Dispatchers.Main) { connections.notch.collect { Widgets.renderAll(this@PhoneLinkService) } }
            connections.startUsb()
            connections.cameraSink = { peer, envelope -> CameraStreamService.handle(this@PhoneLinkService, peer, envelope) }
            connections.audioSink = { peer, envelope -> AudioShare.handle(this@PhoneLinkService, peer, envelope) }
            photoSyncer = PhotoSyncer(this@PhoneLinkService, connections, scope).also { syncer -> launch(Dispatchers.Main) { syncer.start() } }
            launch { showPairingAlerts(connections) }
            launch {
                uiVisible.collect {
                    if (it) {
                        connections.tryAutoPair()
                        rescan.trySend(Unit)
                    }
                }
            }
            launch { scanLoop(connections) }
            launch { Updates.runLoop(this@PhoneLinkService) }
            launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    connections.ping()
                }
            }
            while (true) {
                delay(INFO_INTERVAL_MS)
                connections.broadcastInfo()
                // Permissions can be revoked from Settings while the app runs.
                connections.phone.refresh()
            }
        }
    }

    private suspend fun showStatus(connections: ConnectionManager) {
        connections.peers
            .map { peers -> peers.count { it.isPaired && it.isConnected } }
            .distinctUntilChanged()
            .collect { count ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(count))
            }
    }

    /** The pairing code appears as a notification when the app isn't open. */
    private suspend fun showPairingAlerts(connections: ConnectionManager) {
        val notifications = getSystemService(NotificationManager::class.java)
        combine(connections.prompt, uiVisible) { prompt, visible -> prompt.takeUnless { visible } }
            .collect { prompt ->
                if (prompt == null) notifications.cancel(PAIRING_NOTIFICATION_ID)
                else notifications.notify(PAIRING_NOTIFICATION_ID, buildPairingNotification(prompt))
            }
    }

    /**
     * Fallback for networks without multicast. Sweeps back off (20 s, 1 min, 5 min, 30 min) while nothing turns up,
     * try known addresses first, skip the sweep with the screen off, and stop while every paired device is connected.
     */
    private suspend fun scanLoop(connections: ConnectionManager) {
        val scanner = LanScanner(this)
        val backoff = ScanBackoff()
        val power = getSystemService(PowerManager::class.java)
        val prefs = getSharedPreferences(SCAN_PREFS, MODE_PRIVATE)
        while (true) {
            val peers = connections.peers.value
            val action = ScanPolicy.decide(
                pairedCount = peers.count { it.isPaired },
                connectedPaired = peers.count { it.isPaired && it.isConnected },
                screenOn = power.isInteractive,
            )
            val known = prefs.getString(SCAN_HOSTS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            if (action != ScanAction.Idle) known.forEach { connections.probe(it, PhoneLinkProtocol.PORT) }
            if (action == ScanAction.Sweep) {
                val found = scanner.findHosts()
                found.forEach { connections.probe(it, PhoneLinkProtocol.PORT) }
                if (found.isNotEmpty()) {
                    prefs.edit().putString(SCAN_HOSTS, ScanPolicy.rememberHosts(known, found).joinToString(",")).apply()
                }
            }
            val wait = if (action == ScanAction.Idle) SCAN_IDLE_MS else backoff.next()
            // A new network, a lost device, or opening the app scans again now and from the shortest wait.
            if (withTimeoutOrNull(wait) { rescan.receive() } != null) backoff.reset()
        }
    }

    @Suppress("DEPRECATION")
    private fun createWifiLock() {
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phonelink:streaming")
            .apply { setReferenceCounted(false) }
    }

    /** The high-performance Wi-Fi lock costs battery, so it's held only while streaming or moving files. */
    private suspend fun holdWifiLockWhileBusy(connections: ConnectionManager) {
        val streaming = combine(
            ScreenCaptureService.activePeer, AudioShare.sending, AudioShare.listening, CameraStreamService.active, macScreenViewing,
        ) { screen, soundOut, soundIn, camera, macScreen -> screen != null || soundOut != null || soundIn != null || camera != null || macScreen }
        val moving = connections.fileTransfers.transfers.map { list -> list.any { it.status == TransferStatus.Active } }
        combine(streaming, moving) { a, b -> a || b }
            .distinctUntilChanged()
            .collect { busy ->
                val lock = wifiLock ?: return@collect
                runCatching { if (busy) lock.acquire() else if (lock.isHeld) lock.release() }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        runCatching { unregisterReceiver(screenReceiver) }
        wifiLock?.takeIf { it.isHeld }?.release()
        ChargeReminder.stop(this)
        discovery?.stop()
        watchers?.stop()
        photoSyncer?.stop()
        manager.value?.shutdown()
        manager.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DyskLink connection", NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(PAIRING_CHANNEL_ID, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications.createNotificationChannel(
            NotificationChannel(SCREEN_CHANNEL_ID, "Screen requests from Mac", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications.createNotificationChannel(
            NotificationChannel(SHARING_CHANNEL_ID, "Screen being shared", NotificationManager.IMPORTANCE_LOW),
        )
        Updates.prepare(this)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(connected: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("DyskLink is running")
            .setContentText(if (connected > 0) "Connected devices: $connected" else "Looking for devices")
            .setContentIntent(openAppIntent())
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Send clipboard",
                    PendingIntent.getActivity(
                        this, 2, Intent(this, ClipboardSendActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .setOngoing(true)
            .build()

    private fun buildPairingNotification(prompt: PairingPrompt): Notification =
        Notification.Builder(this, PAIRING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Pair with ${prompt.peerName}")
            .setContentText(
                if (prompt.showsCode) "Type code ${prompt.code} on ${prompt.peerName}."
                else "Tap to enter the code shown on ${prompt.peerName}.",
            )
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    companion object {
        const val SCREEN_CHANNEL_ID = "screen_request"
        const val SHARING_CHANNEL_ID = "screen_sharing"
        const val SCREEN_REQUEST_NOTIFICATION_ID = NotificationIds.SCREEN_REQUEST
        private const val CHANNEL_ID = "connection"
        private const val PAIRING_CHANNEL_ID = "pairing"
        private const val NOTIFICATION_ID = NotificationIds.CONNECTION
        private const val PAIRING_NOTIFICATION_ID = NotificationIds.PAIRING
        private const val INFO_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_MS = 25_000L
        /** With every paired device connected the loop only wakes to re-check; a lost device wakes it sooner. */
        private const val SCAN_IDLE_MS = 1_800_000L
        private const val SCAN_PREFS = "scan"
        private const val SCAN_HOSTS = "hosts"

        private val macScreenViewing = MutableStateFlow(false)

        /** The Mac screen viewer on this phone is showing (it keeps Wi-Fi in high-performance mode). */
        fun setMacScreenViewing(viewing: Boolean) {
            macScreenViewing.value = viewing
        }

        /** Only what the widgets show, so they redraw only when one of these changes. */
        private fun widgetFields(peer: id.phonelink.android.core.Peer) = listOf(
            peer.id, peer.name, peer.platform, peer.isPaired, peer.isConnected, peer.canWake,
            peer.info?.batteryPercent, peer.info?.isCharging, peer.info?.keepAwake,
        )

        private fun allPairedConnected(peers: List<id.phonelink.android.core.Peer>): Boolean =
            peers.any { it.isPaired } && peers.filter { it.isPaired }.all { it.isConnected }

        private val manager = MutableStateFlow<ConnectionManager?>(null)
        val connectionManager: StateFlow<ConnectionManager?> = manager.asStateFlow()

        private val uiVisible = MutableStateFlow(false)

        fun setUiVisible(visible: Boolean) {
            uiVisible.value = visible
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PhoneLinkService::class.java))
        }
    }
}
