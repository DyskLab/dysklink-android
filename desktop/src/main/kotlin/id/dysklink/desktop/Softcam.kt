package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import id.phonelink.android.core.CoreLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DyskLink Camera on Windows: softcam (github.com/tshino/softcam, MIT), a DirectShow video input filter, built in CI
 * with its own name ("DyskLink Camera"), class id and shared memory names, so it lives beside any other softcam.
 * Video apps find it once it's registered with regsvr32, which needs administrator rights once.
 */
object Softcam {
    /** The class id CI gives the filter in softcam.cpp; it must match .github/workflows/desktop.yml. */
    const val CLSID = "{AB6332AA-0874-412F-800F-C0A472F4B554}"
    const val NAME = "DyskLink Camera"
    const val DLL = "softcam.dll"

    /** softcam.dll in the app's resources (jpackage's app folder), or null in a build without it. */
    fun dll(resourcesDir: String? = System.getProperty("compose.application.resources.dir")): File? =
        resourcesDir?.let { File(it, DLL) }?.takeIf { it.isFile }

    /** The DLL path in `reg query <key>\InprocServer32 /ve` output; null when it isn't registered. */
    fun registeredPath(regOutput: String, exitCode: Int): String? {
        if (exitCode != 0) return null
        // "    (Default)    REG_SZ    C:\...\softcam.dll"; "(Default)" is in Windows' own language.
        return regOutput.lines().firstNotNullOfOrNull { line ->
            Regex("""\sREG_(?:EXPAND_)?SZ\s+(.+)$""").find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /** Whether the registered DLL is this one, by path; Windows paths don't care about case. */
    fun samePath(registered: String?, dll: String?): Boolean =
        registered != null && dll != null && registered.trim('"').replace('/', '\\').equals(dll.replace('/', '\\'), ignoreCase = true)

    fun registered(): String? = runCatching {
        val process = ProcessBuilder("reg", "query", "HKCR\\CLSID\\$CLSID\\InprocServer32", "/ve").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) return@runCatching null
        registeredPath(out, process.exitValue())
    }.getOrNull()

    /** The PowerShell line that runs regsvr32 as administrator (Windows asks first), and waits for it. */
    fun elevatedRegsvr32(dll: String, remove: Boolean): String {
        val path = dll.replace("'", "''")
        val args = listOfNotNull("'/s'", "'/u'".takeIf { remove }, "'\"$path\"'").joinToString(",")
        return "\$p = Start-Process regsvr32 -ArgumentList $args -Verb RunAs -Wait -PassThru; exit \$p.ExitCode"
    }

    /** Registers (or with [remove], unregisters) [dll]. False when it failed or the user said no to Windows. */
    fun register(dll: File, remove: Boolean): Boolean = runCatching {
        val process = ProcessBuilder("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", elevatedRegsvr32(dll.absolutePath, remove))
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        val done = process.waitFor(120, TimeUnit.SECONDS)
        if (!done || process.exitValue() != 0) CoreLog.w("DyskLink", "regsvr32 ${if (remove) "/u " else ""}failed: $out")
        done && process.exitValue() == 0
    }.getOrDefault(false)
}

/** Sends frames to softcam: BGR24, bottom row first (a DIB with a positive height, as softcam describes it). */
class SoftcamSink(private val dll: File, override val width: Int, override val height: Int) : WebcamSink {
    @Suppress("FunctionName")
    private interface Api : Library {
        fun scCreateCamera(width: Int, height: Int, framerate: Float): Pointer?
        fun scDeleteCamera(camera: Pointer)
        fun scSendFrame(camera: Pointer, image: ByteArray)
        /** C++ bool: one byte. */
        fun scIsConnected(camera: Pointer): Byte
    }

    private var api: Api? = null
    private var camera: Pointer? = null
    private val out = ByteArray(width * height * 3)
    override val name: String get() = Softcam.NAME

    override fun open() {
        val api = runCatching { Native.load(dll.absolutePath, Api::class.java) }.getOrElse {
            throw WebcamOpenException("Couldn't load ${dll.name}: ${it.message}")
        }
        this.api = api
        // A frame rate of 0: every frame goes out as it's sent, since DyskLink paces them itself.
        camera = api.scCreateCamera(width, height, 0f)
            ?: throw WebcamOpenException("Another app is already sending to ${Softcam.NAME}, such as a second DyskLink.", busy = true)
    }

    override fun send(pixels: IntArray) {
        val camera = camera ?: return
        WebcamFrames.toBgrBottomUp(pixels, width, height, out)
        api?.scSendFrame(camera, out)
    }

    override fun close() {
        camera?.let { c -> runCatching { api?.scDeleteCamera(c) } }
        camera = null
    }
}
