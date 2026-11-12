package id.dysklink.desktop

import java.io.File
import java.net.InetAddress

/** Where things live on Windows and Linux, and what this computer is called. */
object Platform {
    private val osName = System.getProperty("os.name").orEmpty()
    val isWindows = osName.startsWith("Windows", ignoreCase = true)
    val isMac = osName.startsWith("Mac", ignoreCase = true)
    private val home = File(System.getProperty("user.home"))

    /**
     * What phones are told. A Mac only runs this app while it's being developed; it calls itself Linux then,
     * because "macos" would make the phone offer Mac-only features.
     */
    val protocolName: String = System.getenv("DYSKLINK_PLATFORM") ?: if (isWindows) "windows" else "linux"

    val version: String = System.getProperty("dysklink.version") ?: "dev"

    /** Pairing keys, paired phones, and settings. */
    val dataDir: File = (System.getenv("DYSKLINK_DATA_DIR")?.let(::File) ?: when {
        isWindows -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "DyskLink")
        isMac -> File(home, "Library/Application Support/DyskLink Desktop")
        else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "dysklink")
    }).apply { mkdirs() }

    /** Files from phones go to Downloads/DyskLink, as on the Mac. */
    val downloadsDir: File
        get() = (System.getenv("DYSKLINK_DOWNLOAD_DIR")?.let(::File) ?: File(xdgDownloads() ?: File(home, "Downloads"), "DyskLink"))

    /** Document scans from phones go to Documents/DyskLink/Scans. */
    val scansDir: File
        get() = System.getenv("DYSKLINK_SCANS_DIR")?.let(::File) ?: File(File(xdgDir("DOCUMENTS") ?: File(home, "Documents"), "DyskLink"), "Scans")

    private fun xdgDownloads(): File? = xdgDir("DOWNLOAD")

    private fun xdgDir(name: String): File? {
        if (isWindows || isMac) return null
        // Linux desktops in other languages rename Downloads and Documents, and say where in user-dirs.dirs.
        val dirs = File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "user-dirs.dirs")
        val line = runCatching { dirs.readLines() }.getOrNull()?.firstOrNull { it.startsWith("XDG_${name}_DIR=") } ?: return null
        val path = line.substringAfter('=').trim('"').replace("\$HOME", home.path)
        return File(path).takeIf { it.isDirectory }
    }

    val port: Int = System.getenv("DYSKLINK_PORT")?.toIntOrNull() ?: id.phonelink.android.core.PhoneLinkProtocol.PORT

    /** The computer's name as people know it, such as DESKTOP-4F2K or the Linux hostname. */
    val deviceName: String = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC").substringBefore('.')

    /** Such as "Windows 11" or "Ubuntu 24.04 LTS". */
    val model: String = when {
        isWindows -> System.getProperty("os.name")
        isMac -> "macOS ${System.getProperty("os.version")} (development)"
        else -> runCatching {
            File("/etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
        }.getOrNull() ?: "Linux"
    }

    /** Opens a folder or file with the system's own app. */
    fun open(file: File) {
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(file)
            } else if (!isWindows) {
                ProcessBuilder("xdg-open", file.path).start()
            }
        }
    }
}
