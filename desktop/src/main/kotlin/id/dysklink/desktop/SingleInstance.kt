package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Lets a second start of DyskLink talk to the one running: `dysklink --clipboard` opens the running app's clipboard
 * picker and ends. The running app listens on a loopback port written, with a random token, to `instance` in the
 * data folder, which only this user can read.
 */
class SingleInstance(private val dataDir: File) : AutoCloseable {
    private var server: ServerSocket? = null
    private val file get() = File(dataDir, FILE)

    /** Starts listening; [onCommand] gets each command, such as [CLIPBOARD], on a background thread. */
    fun listen(onCommand: (String) -> Unit) {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val socket = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }
            .onFailure { CoreLog.w("DyskLink", "No single-instance port", it) }.getOrNull() ?: return
        server = socket
        runCatching {
            dataDir.mkdirs()
            file.writeText("${socket.localPort} $token\n")
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
        thread(isDaemon = true, name = "dysk-instance") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { c ->
                        c.soTimeout = 2_000
                        val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                        val command = parse(line, token)
                        c.getOutputStream().write((if (command != null) "ok\n" else "no\n").toByteArray())
                        if (command != null) onCommand(command)
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
    }

    companion object {
        const val FILE = "instance"
        const val CLIPBOARD = "clipboard"
        const val SHOW = "show"
        const val LAUNCHER = "launcher"
        /** "window:leftHalf" and the like: Window tidy, from a desktop shortcut where DyskLink can't take the keys. */
        const val WINDOW = "window:"
        private val COMMANDS = setOf(CLIPBOARD, SHOW, LAUNCHER)

        /** The command a start with [args] asks of the running DyskLink, if any: `--clipboard`, `--launcher`, `--window left-half`. */
        fun commandFor(args: Array<String>): String? {
            if ("--clipboard" in args) return CLIPBOARD
            if ("--launcher" in args) return LAUNCHER
            val at = args.indexOf("--window")
            if (at >= 0) return args.getOrNull(at + 1)?.let(WindowTidy::commandId)?.let { WINDOW + it }
            return null
        }

        /** "token command" from a client: the command when the token matches. */
        fun parse(line: String, token: String): String? {
            val parts = line.trim().split(' ')
            if (parts.size != 2 || parts[0] != token) return null
            val command = parts[1]
            if (command.startsWith(WINDOW)) return command.takeIf { WindowTidy.commandId(it.removePrefix(WINDOW)) == it.removePrefix(WINDOW) }
            return command.takeIf { it in COMMANDS }
        }

        /** Sends [command] to the running DyskLink; false when none answers. */
        fun send(dataDir: File, command: String): Boolean = runCatching {
            val (port, token) = File(dataDir, FILE).readText().trim().split(' ').let { it[0].toInt() to it[1] }
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                s.soTimeout = 2_000
                s.getOutputStream().write("$token $command\n".toByteArray())
                s.getInputStream().bufferedReader().readLine() == "ok"
            }
        }.getOrDefault(false)
    }
}
