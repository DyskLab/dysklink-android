package id.dysklink.desktop

import java.io.File

/**
 * Starts DyskLink at login, hidden in the tray. Windows uses the per-user Run key, Linux the XDG autostart folder.
 * Only works from an installed app, where jpackage says where its launcher is.
 */
object Autostart {
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_KEY_FULL = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** The installed launcher, such as C:\\Users\\...\\DyskLink\\DyskLink.exe or /opt/dysklink/bin/DyskLink. */
    val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    val isAvailable: Boolean get() = launcher != null && !Platform.isMac

    private val desktopFile: File
        get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config", "autostart/dysklink.desktop")

    fun isOn(): Boolean = when {
        !isAvailable -> false
        Platform.isWindows -> run("reg", "query", RUN_KEY, "/v", "DyskLink") == 0
        else -> desktopFile.exists()
    }

    fun set(on: Boolean): Boolean {
        val path = launcher ?: return false
        return when {
            Platform.isWindows -> if (on) addRunKey(path) else run("reg", "delete", RUN_KEY, "/v", "DyskLink", "/f") == 0
            else -> runCatching {
                if (on) {
                    desktopFile.parentFile.mkdirs()
                    desktopFile.writeText(entry(path))
                } else {
                    desktopFile.delete()
                }
            }.isSuccess
        }
    }

    /** What Windows runs at login: the launcher in quotes, since it's usually under "Program Files", then --hidden. */
    fun command(path: String) = "\"$path\" --hidden"

    /**
     * The Run value as a .reg file. Passing the quoted command to `reg add` doesn't work: Java quotes the argument
     * again around the quotes inside it, and `reg` reads the result wrongly whenever the path has a space.
     * In a .reg file, backslashes and quotes inside a value are escaped with a backslash.
     */
    fun registryFile(path: String): String {
        val value = command(path).replace("\\", "\\\\").replace("\"", "\\\"")
        return "Windows Registry Editor Version 5.00\r\n\r\n[$RUN_KEY_FULL]\r\n\"DyskLink\"=\"$value\"\r\n"
    }

    private fun addRunKey(path: String): Boolean {
        val file = runCatching { File.createTempFile("dysklink-autostart", ".reg") }.getOrNull() ?: return false
        return try {
            // reg import reads UTF-16 with a byte order mark, which keeps any letters in the user's folder name.
            file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + registryFile(path).toByteArray(Charsets.UTF_16LE))
            run("reg", "import", file.absolutePath) == 0
        } finally {
            file.delete()
        }
    }

    /** The XDG autostart entry for [path]. */
    fun entry(path: String) = """
        [Desktop Entry]
        Type=Application
        Name=DyskLink
        Comment=Your Android phone and your PC, working together
        Exec="$path" --hidden
        Icon=dysklink
        X-GNOME-Autostart-enabled=true
        Terminal=false
    """.trimIndent() + "\n"

    private fun run(vararg command: String): Int =
        runCatching { ProcessBuilder(*command).redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() }.getOrDefault(-1)
}
