package id.phonelink.android.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import id.phonelink.android.core.WebFileServer
import java.net.BindException
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs the phone's file web server while the user wants it, and stops it when it isn't used. */
object WebFiles {
    data class State(
        val running: Boolean = false,
        val address: String? = null,
        val pin: String? = null,
        val lastDownload: String? = null,
        val downloads: Int = 0,
        val uploads: Int = 0,
        val lastUpload: String? = null,
        val problem: String? = null,
    )

    /** Remembered, and off until turned on. */
    fun allowsUploads(context: Context) = context.getSharedPreferences("web_files", Context.MODE_PRIVATE).getBoolean("uploads", false)

    fun setAllowsUploads(context: Context, on: Boolean) {
        context.getSharedPreferences("web_files", Context.MODE_PRIVATE).edit().putBoolean("uploads", on).apply()
        server?.allowsUploads = on
    }

    private const val IDLE_MS = 30 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var server: WebFileServer? = null
    private var watcher: Job? = null

    fun canReadFiles(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun start(context: Context) {
        stop()
        val info = NetworkTools.info(context)
        val local = info.address
        if (local == null || info.transport !in setOf("Wi-Fi", "Ethernet")) {
            _state.value = State(problem = "Connect the phone to Wi-Fi first. The laptop has to be on the same Wi-Fi.")
            return
        }
        val pin = WebFileServer.newPin()
        val created = WebFileServer(Environment.getExternalStorageDirectory(), pin)
        try {
            // Only on the Wi-Fi address, so the server isn't reachable over mobile data or a VPN.
            val bind = InetAddress.getByName(local)
            try {
                created.start(WebFileServer.DEFAULT_PORT, bind)
            } catch (_: BindException) {
                created.start(0, bind)
            }
        } catch (error: Exception) {
            _state.value = State(problem = "Couldn't start: ${error.message ?: "unknown error"}")
            return
        }
        created.onDownload = { from -> _state.update { it.copy(lastDownload = from, downloads = it.downloads + 1) } }
        created.allowsUploads = allowsUploads(context)
        created.onUpload = { _, name -> _state.update { it.copy(lastUpload = name, uploads = it.uploads + 1) } }
        server = created
        _state.value = State(running = true, address = "http://$local:${created.port}", pin = pin)
        watcher = scope.launch {
            while (true) {
                delay(60_000)
                if (System.currentTimeMillis() - created.lastUsed > IDLE_MS) {
                    stop()
                    _state.update { it.copy(problem = "Stopped after 30 minutes without use.") }
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        watcher?.cancel()
        watcher = null
        server?.stop()
        server = null
        _state.update { State(problem = null) }
    }
}
