package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.PhoneLinkProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A phone as `adb devices -l` lists it. */
data class AdbDevice(
    val serial: String,
    /** "device" when ready, "unauthorized" before the phone allows this PC, or "offline". */
    val state: String,
    val model: String,
) {
    val ready: Boolean get() = state == "device"
}

/** What the USB card shows. */
data class UsbState(
    val enabled: Boolean = true,
    /** Where adb was found, or null when it wasn't. */
    val adbPath: String? = null,
    val devices: List<AdbDevice> = emptyList(),
    val downloading: Boolean = false,
    /** From 0 to 1 while the size is known. */
    val progress: Float? = null,
    val downloadError: String? = null,
    /** Which help the card gives: Windows drivers or Linux udev rules. */
    val windows: Boolean = Platform.isWindows,
) {
    val ready: List<AdbDevice> get() = devices.filter { it.ready }
    val waiting: List<AdbDevice> get() = devices.filter { it.state == "unauthorized" }
}

/** Runs adb and returns what it printed, or null when it failed or took longer than [timeoutMs]. */
fun interface AdbRunner {
    fun run(adb: String, args: List<String>, timeoutMs: Long): String?
}

/**
 * Connects phones over a USB cable, as on the Mac. With USB debugging on, `adb reverse` gives the phone a port on
 * 127.0.0.1 that leads to this PC, and the phone uses it instead of Wi-Fi while the cable is in.
 * All adb calls run on the IO pool, never on the window's thread.
 */
class UsbBridge(
    private val scope: CoroutineScope,
    /** The port this PC listens on, which may be DYSKLINK_PORT. */
    private val port: Int,
    private val dataDir: File,
    enabled: Boolean,
    private val onEnabledChange: (Boolean) -> Unit = {},
    /** After USB is turned off, phones on the cable are hung up on, so they come back over Wi-Fi. */
    private val onTurnedOff: () -> Unit = {},
    private val findAdb: () -> String? = { AdbLocator.find(dataDir = dataDir) },
    private val runner: AdbRunner = ProcessAdb,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windows: Boolean = Platform.isWindows,
) {
    private val _state = MutableStateFlow(UsbState(enabled = enabled, windows = windows))
    val state: StateFlow<UsbState> = _state.asStateFlow()

    private var job: Job? = null
    /** One adb round at a time: a poll, or taking the reverse ports away. */
    private val adbLock = Mutex()
    /** Serials that already have the reverse port. */
    @Volatile private var forwarded: Set<String> = emptySet()
    private var lastListCheck = 0L
    /** When adb last listed a phone. Polling is quick shortly after, and slow when no phone has been seen. */
    @Volatile private var lastDeviceSeen = Long.MIN_VALUE / 2
    /** The adb whose server was started, so `adb start-server` runs once. */
    private var serverStarted: String? = null

    fun start() {
        if (_state.value.enabled) startPolling()
    }

    fun setEnabled(on: Boolean) {
        if (_state.value.enabled == on) return
        _state.update { it.copy(enabled = on) }
        onEnabledChange(on)
        if (on) {
            startPolling()
        } else {
            job?.cancel()
            job = null
            val serials = forwarded
            scope.launch(Dispatchers.IO) {
                adbLock.withLock {
                    // The phones keep their reverse port only while this PC wants them on the cable.
                    val adb = _state.value.adbPath
                    if (adb != null && !_state.value.enabled) {
                        for (serial in serials) runner.run(adb, listOf("-s", serial, "reverse", "--remove", "tcp:${PhoneLinkProtocol.USB_PORT}"), TIMEOUT_MS)
                    }
                    if (!_state.value.enabled) {
                        forwarded = emptySet()
                        _state.update { it.copy(devices = emptyList()) }
                    }
                }
                if (!_state.value.enabled) onTurnedOff()
            }
        }
    }

    private fun startPolling() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value.enabled) {
                runCatching { poll() }.onFailure { CoreLog.w(TAG, "USB check failed", it) }
                delay(interval(clock() - lastDeviceSeen))
            }
        }
    }

    /** One check: lists the phones and gives each ready one the reverse port. Public for tests. */
    suspend fun poll() = adbLock.withLock {
        if (!_state.value.enabled) return@withLock
        val adb = findAdb()
        if (_state.value.adbPath != adb) _state.update { it.copy(adbPath = adb) }
        if (adb == null) return@withLock
        if (serverStarted != adb) {
            // Starting adb's server can take a while; it stays running after DyskLink quits, as on the Mac.
            runner.run(adb, listOf("start-server"), START_TIMEOUT_MS)
            serverStarted = adb
        }
        val now = clock()
        val recheck = now - lastListCheck > RECHECK_MS
        if (recheck) lastListCheck = now
        val found = parseDevices(runner.run(adb, listOf("devices", "-l"), TIMEOUT_MS).orEmpty())
        val known = forwarded
        val nowForwarded = HashSet<String>()
        for (device in found.filter { it.ready }) {
            // The reverse port is lost when the adb server restarts, so it's checked now and then.
            if (device.serial in known && !recheck) {
                nowForwarded += device.serial
                continue
            }
            val current = runner.run(adb, listOf("-s", device.serial, "reverse", "--list"), TIMEOUT_MS).orEmpty()
            if (hasReverse(current, port) ||
                runner.run(adb, listOf("-s", device.serial, "reverse", "tcp:${PhoneLinkProtocol.USB_PORT}", "tcp:$port"), TIMEOUT_MS) != null
            ) {
                nowForwarded += device.serial
            }
        }
        if (!_state.value.enabled) return@withLock
        (nowForwarded - known).forEach { CoreLog.i(TAG, "USB_FORWARD $it") }
        forwarded = nowForwarded
        if (found.isNotEmpty()) lastDeviceSeen = clock()
        if (_state.value.devices != found) _state.update { it.copy(devices = found) }
    }

    /** Serials that have the reverse port now. */
    val forwardedSerials: Set<String> get() = forwarded

    /** Fetches Google's platform tools into DyskLink's folder, for PCs without Android Studio or an adb package. */
    fun downloadAdb(url: String = AdbDownload.url(windows, Platform.isMac)) {
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = null, downloadError = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val adb = AdbDownload.fetch(url, dataDir, windows) { progress -> _state.update { it.copy(progress = progress) } }
                _state.update { it.copy(adbPath = adb.path) }
                if (_state.value.enabled) {
                    // Checks at once with the new adb instead of waiting up to 30 seconds.
                    job?.cancel()
                    job = null
                    startPolling()
                }
            } catch (error: Exception) {
                CoreLog.w(TAG, "adb download failed", error)
                _state.update { it.copy(downloadError = "Couldn't download the tools: ${error.message ?: error.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(downloading = false, progress = null) }
            }
        }
    }

    companion object {
        private const val TAG = "DyskLink"
        const val FAST_INTERVAL_MS = 4_000L
        const val SLOW_INTERVAL_MS = 30_000L
        /** How long after the last phone was seen polling stays quick. */
        const val RECENT_WINDOW_MS = 120_000L
        private const val RECHECK_MS = 20_000L
        const val TIMEOUT_MS = 10_000L
        const val START_TIMEOUT_MS = 30_000L

        /** Quick while a phone is plugged in or was a moment ago; otherwise every 30 seconds. */
        fun interval(sinceDeviceMs: Long): Long = if (sinceDeviceMs < RECENT_WINDOW_MS) FAST_INTERVAL_MS else SLOW_INTERVAL_MS

        /** Reads `adb devices -l`, with Windows line ends too. */
        fun parseDevices(output: String): List<AdbDevice> = output.lines().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size < 2 || line.startsWith("List of") || line.startsWith("*") || line.startsWith("adb ")) return@mapNotNull null
            val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ').orEmpty()
            AdbDevice(parts[0], parts[1], model)
        }

        /** True when `adb reverse --list` already has the USB port leading to this PC's [port]. */
        fun hasReverse(output: String, port: Int): Boolean =
            output.lines().any { line ->
                val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
                parts.windowed(2).any { (from, to) -> from == "tcp:${PhoneLinkProtocol.USB_PORT}" && to == "tcp:$port" }
            }
    }
}

/** Where adb may be on Windows and Linux (and a Mac, where this app only runs while it's developed). */
object AdbLocator {
    enum class Os { Windows, Linux, Mac }

    val currentOs: Os get() = when {
        Platform.isWindows -> Os.Windows
        Platform.isMac -> Os.Mac
        else -> Os.Linux
    }

    /** The adb a download puts in [dataDir]. */
    fun downloaded(dataDir: File, os: Os = currentOs): File = File(File(dataDir, "platform-tools"), if (os == Os.Windows) "adb.exe" else "adb")

    /** Places to look, first to last. */
    fun candidates(os: Os, env: (String) -> String?, home: String, dataDir: File): List<String> {
        val sep = if (os == Os.Windows) "\\" else "/"
        val exe = if (os == Os.Windows) "adb.exe" else "adb"
        fun join(vararg parts: String) = parts.joinToString(sep) { it.trimEnd('\\', '/') }
        val list = ArrayList<String>()
        for (key in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            env(key)?.takeIf { it.isNotBlank() }?.let { list += join(it, "platform-tools", exe) }
        }
        when (os) {
            Os.Windows -> env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { list += join(it, "Android", "Sdk", "platform-tools", exe) }
            Os.Linux -> {
                list += join(home, "Android", "Sdk", "platform-tools", exe)
                // Debian and Ubuntu's android-tools-adb or adb, Fedora and Arch's android-tools.
                list += "/usr/bin/adb"
                list += "/usr/lib/android-sdk/platform-tools/adb"
            }
            Os.Mac -> {
                list += join(home, "Library", "Android", "sdk", "platform-tools", exe)
                list += "/opt/homebrew/bin/adb"
                list += "/usr/local/bin/adb"
            }
        }
        // As `where adb` or `which adb` would find it.
        val pathSep = if (os == Os.Windows) ';' else ':'
        env("PATH")?.split(pathSep)?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }?.forEach { list += join(it, exe) }
        list += join(dataDir.path, "platform-tools", exe)
        return list.distinct()
    }

    fun find(
        os: Os = currentOs,
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
        dataDir: File = Platform.dataDir,
        isExecutable: (String) -> Boolean = { path -> File(path).let { it.isFile && (os == Os.Windows || it.canExecute()) } },
    ): String? = candidates(os, env, home, dataDir).firstOrNull { runCatching { isExecutable(it) }.getOrDefault(false) }
}

/** Google's platform tools, unpacked into DyskLink's folder. */
object AdbDownload {
    fun url(windows: Boolean, mac: Boolean = false): String =
        "https://dl.google.com/android/repository/platform-tools-latest-${if (windows) "windows" else if (mac) "darwin" else "linux"}.zip"

    /** Downloads and unpacks into [dataDir]/platform-tools; returns adb. [progress] gets 0 to 1 when the size is known. */
    fun fetch(url: String, dataDir: File, windows: Boolean, progress: (Float?) -> Unit): File {
        dataDir.mkdirs()
        val zip = File(dataDir, "platform-tools-download.zip")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != 200) throw IOException("the server answered ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > MAX_ZIP_BYTES) throw IOException("the file is bigger than expected")
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) { shown = percent; progress(done.toFloat() / total) }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        try {
            return install(zip, dataDir, windows)
        } finally {
            zip.delete()
        }
    }

    /** Unpacks [zip] (with a platform-tools folder inside) over [dataDir]/platform-tools, and returns adb. */
    fun install(zip: File, dataDir: File, windows: Boolean): File {
        val staging = File(dataDir, "platform-tools-unpacking")
        staging.deleteRecursively()
        try {
            zip.inputStream().use { unzip(it, staging) }
            val unpacked = File(staging, "platform-tools")
            val adb = File(unpacked, if (windows) "adb.exe" else "adb")
            if (!adb.isFile) throw IOException("adb isn't in the download")
            if (!windows) unpacked.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
            val target = File(dataDir, "platform-tools")
            target.deleteRecursively()
            if (!unpacked.renameTo(target)) {
                unpacked.copyRecursively(target, overwrite = true)
                if (!windows) target.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
            }
            return File(target, adb.name)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unpacks a zip into [target], refusing any entry that would land outside it. */
    fun unzip(input: InputStream, target: File) {
        target.mkdirs()
        val root = target.canonicalFile
        var written = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(root, entry.name).canonicalFile
                if (file != root && !file.path.startsWith(root.path + File.separator)) {
                    throw IOException("the download has a file outside its folder (${entry.name})")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_UNPACKED_BYTES) throw IOException("the download unpacks bigger than expected")
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    /** Platform tools are about 15 MB zipped and 40 MB unpacked; well above, so a newer version still fits. */
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 600L * 1024 * 1024
}

/** Runs adb with ProcessBuilder: errors thrown away, output read on its own thread, killed after the timeout. */
object ProcessAdb : AdbRunner {
    override fun run(adb: String, args: List<String>, timeoutMs: Long): String? {
        val process = try {
            ProcessBuilder(listOf(adb) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            return null
        }
        runCatching { process.outputStream.close() }
        val output = ByteArrayOutputStream()
        // The adb server that `start-server` leaves running may hold the pipe open, so the reader isn't waited on for long.
        val reader = thread(isDaemon = true, name = "dysklink-adb") {
            runCatching {
                val buffer = ByteArray(8 * 1024)
                val stream = process.inputStream
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    synchronized(output) { output.write(buffer, 0, read) }
                }
            }
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        reader.join(2_000)
        return if (process.exitValue() == 0) synchronized(output) { output.toString(Charsets.UTF_8) } else null
    }
}
