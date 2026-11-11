package id.phonelink.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import id.phonelink.android.core.NetTools
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wi-Fi speed test, devices on this network, and ping. Nothing runs until the user starts it.
 * The speed test talks to speed.cloudflare.com, which sees this network's public address.
 */
object NetworkTools {
    data class NetworkInfo(
        val transport: String = "",
        val wifiName: String? = null,
        val address: String? = null,
        val gateway: String? = null,
        val linkMbps: Int? = null,
        val isMetered: Boolean = false,
    )

    enum class SpeedPhase { Idle, Latency, Download, Upload, Done, Failed }

    data class Speed(
        val phase: SpeedPhase = SpeedPhase.Idle,
        val latencyMs: Long? = null,
        val downMbps: Double? = null,
        val upMbps: Double? = null,
        val liveMbps: Double? = null,
        val error: String? = null,
        val usedMb: Long = 0,
    )

    data class Device(val ip: String, val name: String?, val open: Set<Int>, val kind: NetTools.Kind)

    data class Scan(
        val running: Boolean = false,
        val checked: Int = 0,
        val total: Int = 0,
        val devices: List<Device> = emptyList(),
        val note: String? = null,
    )

    data class Ping(
        val running: Boolean = false,
        val host: String = "",
        val lines: List<String> = emptyList(),
        val summary: NetTools.PingSummary? = null,
        val error: String? = null,
        val usedTcp: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _speed = MutableStateFlow(Speed())
    val speed: StateFlow<Speed> = _speed.asStateFlow()
    private val _scan = MutableStateFlow(Scan())
    val scan: StateFlow<Scan> = _scan.asStateFlow()
    private val _ping = MutableStateFlow(Ping())
    val ping: StateFlow<Ping> = _ping.asStateFlow()
    private var speedJob: Job? = null
    private var scanJob: Job? = null
    private var pingJob: Job? = null

    // Network info

    @Suppress("DEPRECATION")
    fun info(context: Context): NetworkInfo {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return NetworkInfo(transport = "Offline")
        val capabilities = connectivity.getNetworkCapabilities(network)
        val transport = when {
            capabilities == null -> "Unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        val link = connectivity.getLinkProperties(network)
        val address = link?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        // The network name needs location access on new Android versions, so it is only shown when Android gives it.
        val wifi = if (transport == "Wi-Fi") context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo else null
        val ssid = wifi?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return NetworkInfo(
            transport = transport,
            wifiName = ssid,
            address = address,
            gateway = gateway,
            linkMbps = wifi?.linkSpeed?.takeIf { it > 0 },
            isMetered = connectivity.isActiveNetworkMetered,
        )
    }

    // Speed test

    fun startSpeed() {
        speedJob?.cancel()
        speedJob = scope.launch {
            _speed.value = Speed(phase = SpeedPhase.Latency)
            try {
                val latency = measureLatency()
                _speed.update { it.copy(latencyMs = latency, phase = SpeedPhase.Download) }
                val down = transfer(upload = false)
                _speed.update { it.copy(downMbps = down, liveMbps = null, phase = SpeedPhase.Upload) }
                val up = transfer(upload = true)
                _speed.update { it.copy(upMbps = up, liveMbps = null, phase = SpeedPhase.Done) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _speed.update { it.copy(phase = SpeedPhase.Failed, liveMbps = null, error = "Couldn't reach the speed test server. Check the connection.") }
            }
        }
    }

    fun stopSpeed() {
        speedJob?.cancel()
        _speed.update { if (it.phase in setOf(SpeedPhase.Done, SpeedPhase.Failed, SpeedPhase.Idle)) it else it.copy(phase = SpeedPhase.Idle, liveMbps = null) }
    }

    private suspend fun measureLatency(): Long {
        val times = mutableListOf<Long>()
        repeat(6) {
            val start = System.nanoTime()
            val connection = URL(NetTools.SPEED_DOWN + "0").openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            try {
                if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
            // The first request also opens the connection and TLS, so it isn't counted.
            if (it > 0) times += (System.nanoTime() - start) / 1_000_000
        }
        return NetTools.median(times) ?: 0
    }

    /** Several streams for a few seconds, so one slow stream doesn't hide the real speed. */
    private suspend fun transfer(upload: Boolean): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val start = System.nanoTime()
        val duration = if (upload) 7_000L else 8_000L
        val deadline = start + duration * 1_000_000
        val workers = (1..STREAMS).map {
            async(Dispatchers.IO) {
                while (isActive && System.nanoTime() < deadline) {
                    if (upload) uploadOnce(bytes, deadline) else downloadOnce(bytes, deadline)
                }
            }
        }
        val ticker = launch {
            while (isActive) {
                delay(300)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                _speed.update { it.copy(liveMbps = NetTools.mbps(bytes.get(), elapsed), usedMb = it.usedMb) }
            }
        }
        workers.awaitAll()
        ticker.cancel()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        _speed.update { it.copy(usedMb = it.usedMb + bytes.get() / 1_000_000) }
        if (bytes.get() == 0L) throw IOException("Nothing transferred")
        NetTools.mbps(bytes.get(), elapsed)
    }

    private suspend fun downloadOnce(counter: AtomicLong, deadline: Long) {
        val connection = URL(NetTools.SPEED_DOWN + CHUNK_DOWN).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (System.nanoTime() < deadline) {
                    coroutineContextActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    counter.addAndGet(read.toLong())
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun uploadOnce(counter: AtomicLong, deadline: Long) {
        val connection = URL(NetTools.SPEED_UP).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(CHUNK_UP)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        val buffer = ByteArray(64 * 1024)
        var sent = 0L
        try {
            connection.outputStream.use { output ->
                while (sent < CHUNK_UP) {
                    coroutineContextActive()
                    val size = minOf(buffer.size.toLong(), CHUNK_UP - sent).toInt()
                    output.write(buffer, 0, size)
                    sent += size
                    // Past the deadline the rest only finishes the request, so it isn't counted.
                    if (System.nanoTime() < deadline) counter.addAndGet(size.toLong())
                }
            }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun coroutineContextActive() = kotlin.coroutines.coroutineContext.ensureActive()

    // Devices on this network

    fun startScan(context: Context) {
        scanJob?.cancel()
        val info = info(context)
        val local = info.address
        if (local == null || info.transport !in setOf("Wi-Fi", "Ethernet")) {
            _scan.value = Scan(note = "Connect to Wi-Fi to see devices on the same network.")
            return
        }
        val hosts = NetTools.subnet(local)
        _scan.value = Scan(running = true, total = hosts.size)
        scanJob = scope.launch {
            val limit = Semaphore(SCAN_PARALLEL)
            val found = mutableListOf<Device>()
            val lock = Any()
            coroutineScope {
                hosts.map { host ->
                    async {
                        limit.withPermit {
                            val alive = host == info.gateway || isAlive(host)
                            _scan.update { it.copy(checked = it.checked + 1) }
                            if (alive) {
                                val device = describe(host, info.gateway)
                                synchronized(lock) { found += device }
                                _scan.update { state -> state.copy(devices = sorted(found.toList())) }
                            }
                        }
                    }
                }.awaitAll()
            }
            val self = Device(local, android.os.Build.MODEL, emptySet(), NetTools.guess(emptySet(), isRouter = false, isSelf = true))
            _scan.update { it.copy(running = false, devices = sorted(found + self)) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scan.update { it.copy(running = false) }
    }

    private fun sorted(devices: List<Device>) = devices.distinctBy { it.ip }.sortedBy { NetTools.ipKey(it.ip) }

    /** A device that refuses a connection is still there. Only silence means nothing answered. */
    private fun isAlive(host: String): Boolean = NetTools.probePorts.any { port -> probe(host, port, PROBE_TIMEOUT_MS) != null }

    /** True for open, false for refused, null for no answer. */
    private fun probe(host: String, port: Int, timeout: Int): Boolean? = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        true
    } catch (_: ConnectException) {
        false
    } catch (_: SocketTimeoutException) {
        null
    } catch (_: IOException) {
        null
    }

    private suspend fun describe(host: String, gateway: String?): Device = coroutineScope {
        val open = NetTools.servicePorts.map { port -> async { port.takeIf { probe(host, port, SERVICE_TIMEOUT_MS) == true } } }
            .awaitAll().filterNotNull().toSet()
        // Reverse DNS from the router, when it has names for its devices.
        val name = withTimeoutOrNull(1_500) {
            withContext(Dispatchers.IO) {
                runCatching { InetAddress.getByName(host).canonicalHostName }.getOrNull()?.takeIf { it != host }
            }
        }
        Device(host, name?.removeSuffix(".lan")?.removeSuffix(".local")?.removeSuffix(".home"), open, NetTools.guess(open, host == gateway, isSelf = false))
    }

    // Ping

    fun startPing(host: String) {
        pingJob?.cancel()
        val target = host.trim()
        if (!NetTools.isValidHost(target)) {
            _ping.value = Ping(host = target, error = "Enter an address such as 192.168.1.1 or google.com.")
            return
        }
        _ping.value = Ping(running = true, host = target)
        pingJob = scope.launch {
            val times = mutableListOf<Double>()
            val worked = runCatching { systemPing(target, times) }.getOrDefault(false)
            if (!worked) {
                times.clear()
                _ping.update { it.copy(lines = emptyList(), usedTcp = true) }
                tcpPing(target, times)
            }
            _ping.update { it.copy(running = false, summary = NetTools.summarize(PING_COUNT, times)) }
        }
    }

    fun stopPing() {
        pingJob?.cancel()
        _ping.update { it.copy(running = false) }
    }

    /** Android's own ping. Returns false when it can't run, so the TCP fallback takes over. */
    private suspend fun systemPing(host: String, times: MutableList<Double>): Boolean = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("ping", "-c", PING_COUNT.toString(), "-W", "2", host).redirectErrorStream(true).start()
        var sawReply = false
        var sawOutput = false
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ensureActive()
                    if (line.isBlank()) continue
                    sawOutput = true
                    NetTools.parsePingLine(line)?.let {
                        sawReply = true
                        times += it.millis
                        _ping.update { state -> state.copy(lines = state.lines + "Reply in %.1f ms".format(it.millis)) }
                    }
                    if ("unknown host" in line.lowercase()) {
                        _ping.update { it.copy(error = "Couldn't find $host.") }
                        return@withContext true
                    }
                }
            }
            process.waitFor()
        } finally {
            process.destroy()
        }
        // Ping ran but nothing answered: many devices and networks block it, so TCP is tried.
        sawOutput && sawReply
    }

    /** Times a TCP connection instead, for when ICMP is blocked or ping isn't available. */
    private suspend fun tcpPing(host: String, times: MutableList<Double>) {
        val address = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(host) }.getOrNull() }
        if (address == null) {
            _ping.update { it.copy(error = "Couldn't find $host.") }
            return
        }
        repeat(PING_COUNT) {
            val millis = withContext(Dispatchers.IO) {
                listOf(443, 80).firstNotNullOfOrNull { port ->
                    val start = System.nanoTime()
                    when (probe(address.hostAddress ?: host, port, 2_000)) {
                        null -> null
                        else -> (System.nanoTime() - start) / 1_000_000.0
                    }
                }
            }
            if (millis != null) times += millis
            _ping.update { it.copy(lines = it.lines + (millis?.let { "Answered in %.1f ms".format(it) } ?: "No answer")) }
            delay(700)
        }
    }

    private const val STREAMS = 4
    private const val CHUNK_DOWN = 25_000_000L
    private const val CHUNK_UP = 4_000_000L
    private const val SCAN_PARALLEL = 64
    private const val PROBE_TIMEOUT_MS = 350
    private const val SERVICE_TIMEOUT_MS = 600
    private const val PING_COUNT = 5
}
