package id.phonelink.android.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import id.phonelink.android.ClipboardSendActivity
import id.phonelink.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Quick Settings tiles. Each one acts on every paired Mac that is connected right now. */
abstract class DyskTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null
    private var serviceRefused = false

    protected val connections get() = PhoneLinkService.connectionManager.value
    protected val connectedMacs get() = connections?.peers?.value.orEmpty().filter { it.isMac && it.isPaired && it.isConnected }

    override fun onStartListening() {
        super.onStartListening()
        // Android may refuse to start it from here; the subtitle then says to open DyskLink.
        serviceRefused = !PhoneLinkService.start(this)
        refresh()
        watching?.cancel()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        watching = scope.launch {
            PhoneLinkService.connectionManager.filterNotNull().flatMapLatest { it.peers }.collect { refresh() }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    protected fun refresh() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_logo_glyph)
        update(tile)
        if (serviceRefused && connections == null) setSubtitle(tile, "Open DyskLink to connect")
        tile.updateTile()
    }

    protected abstract fun update(tile: Tile)

    protected fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }
}

class ClipboardTile : DyskTile() {
    override fun update(tile: Tile) {
        tile.label = "Send clipboard"
        tile.state = if (connectedMacs.isEmpty()) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        setSubtitle(tile, if (connectedMacs.isEmpty()) "No Mac connected" else "To your Mac")
    }

    /** Android only lets an app read the clipboard while one of its screens has focus, so this opens one briefly. */
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

class LockMacTile : DyskTile() {
    override fun update(tile: Tile) {
        tile.label = "Lock Mac"
        tile.state = if (connectedMacs.isEmpty()) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        setSubtitle(tile, connectedMacs.singleOrNull()?.name ?: if (connectedMacs.isEmpty()) "No Mac connected" else "${connectedMacs.size} Macs")
    }

    override fun onClick() {
        connections?.sendMacAction("lock")
    }
}

class KeepAwakeTile : DyskTile() {
    override fun update(tile: Tile) {
        tile.label = "Keep Mac awake"
        val macs = connectedMacs
        val states = macs.mapNotNull { it.info?.keepAwake }
        tile.state = when {
            macs.isEmpty() || states.isEmpty() || states.all { it == "unavailable" } -> Tile.STATE_UNAVAILABLE
            "active" in states -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        setSubtitle(
            tile,
            when {
                macs.isEmpty() -> "No Mac connected"
                states.isEmpty() -> "Needs DyskLink 0.2 on the Mac"
                states.all { it == "unavailable" } -> "Turn on Keep Awake on the Mac"
                "active" in states -> "On"
                else -> "Off"
            },
        )
    }

    override fun onClick() {
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_UNAVAILABLE) return
        connections?.sendMacAction(if (tile.state == Tile.STATE_ACTIVE) "awake.stop" else "awake.start")
    }
}
