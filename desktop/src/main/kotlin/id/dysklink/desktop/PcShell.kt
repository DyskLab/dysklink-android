package id.dysklink.desktop

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import id.phonelink.android.core.CommandResult
import id.phonelink.android.core.CommandRunner
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.PtyFactory
import id.phonelink.android.core.PtySession
import id.phonelink.android.core.SavedCommand
import id.phonelink.android.core.protocolJson
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

// Commands and a terminal for phones, as on the Mac. Both are off for every phone until it's allowed on that
// phone's page, and the phone asks for its own lock before each terminal.

/** The commands phones may run, kept in commands.json next to the pairings. Only names and IDs go to phones. */
class CommandStore(private val file: File) {
    private val serializer = ListSerializer(SavedCommand.serializer())
    private val _commands = MutableStateFlow(load())
    val commands: StateFlow<List<SavedCommand>> = _commands.asStateFlow()

    private fun load(): List<SavedCommand> =
        runCatching { protocolJson.decodeFromString(serializer, file.readText()) }.getOrNull().orEmpty()

    /** False when the name or command line is empty or too long. */
    fun add(name: String, command: String): Boolean {
        val n = name.trim()
        val c = command.trim()
        if (n.isEmpty() || c.isEmpty() || n.length > MAX_NAME || c.length > MAX_COMMAND || _commands.value.size >= MAX_COMMANDS) return false
        save(_commands.value + SavedCommand(UUID.randomUUID().toString(), n, c))
        return true
    }

    fun remove(id: String) = save(_commands.value.filter { it.id != id })

    private fun save(list: List<SavedCommand>) {
        _commands.value = list
        runCatching {
            file.writeText(protocolJson.encodeToString(serializer, list))
            // Only this user can read what the commands do.
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }.onFailure { CoreLog.w("DyskLink", "Couldn't save the commands", it) }
    }

    companion object {
        const val MAX_NAME = 60
        const val MAX_COMMAND = 2_000
        const val MAX_COMMANDS = 50
    }
}

object PcShell {
    private val home = File(System.getProperty("user.home"))

    /** The command line through cmd.exe on Windows and /bin/sh elsewhere. */
    fun commandArgs(line: String, windows: Boolean = Platform.isWindows): List<String> =
        if (windows) listOf(System.getenv("ComSpec")?.takeIf { it.isNotBlank() } ?: "cmd.exe", "/c", line)
        else listOf("/bin/sh", "-c", line)

    fun run(command: SavedCommand): CommandResult =
        CommandRunner.run(command.id, commandArgs(command.command), directory = home.takeIf { it.isDirectory })

    /** PowerShell on Windows, else cmd.exe; the user's own shell as a login shell elsewhere. */
    fun terminalShell(windows: Boolean = Platform.isWindows): List<String> {
        if (windows) {
            val root = System.getenv("SystemRoot") ?: "C:\\Windows"
            val powershell = File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            return if (powershell.isFile) listOf(powershell.path, "-NoLogo") else listOf(System.getenv("ComSpec") ?: "cmd.exe")
        }
        val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: listOf("/bin/bash", "/bin/zsh", "/bin/sh").first { File(it).canExecute() }
        return listOf(shell, "-l")
    }

    /** Shells on a pseudo terminal through pty4j: ConPTY on Windows, a Unix pty elsewhere. */
    val pty: PtyFactory = PtyFactory { cols, rows ->
        val environment = HashMap(System.getenv())
        environment["TERM"] = "xterm-256color"
        environment["COLORTERM"] = "truecolor"
        environment["TERM_PROGRAM"] = "DyskLink"
        environment.putIfAbsent("LANG", "en_US.UTF-8")
        val process = PtyProcessBuilder(terminalShell().toTypedArray())
            .setEnvironment(environment)
            .setDirectory(home.path)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
            .setUseWinConPty(true)
            .start()
        Pty4jSession(process)
    }

    private class Pty4jSession(private val process: PtyProcess) : PtySession {
        override val output: InputStream = process.inputStream
        private val input = process.outputStream

        override fun write(bytes: ByteArray) {
            input.write(bytes)
            input.flush()
        }

        override fun resize(cols: Int, rows: Int) { process.winSize = WinSize(cols, rows) }

        /** SIGHUP, as closing a terminal window, on Linux; Windows has no hang-up, so the shell is ended. */
        override fun hangUp() {
            if (Platform.isWindows) {
                process.destroy()
            } else {
                runCatching { ProcessBuilder("kill", "-HUP", process.pid().toString()).start().waitFor(1, TimeUnit.SECONDS) }
                    .onFailure { process.destroy() }
            }
        }

        override fun kill() {
            runCatching { process.descendants().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }

        override val isAlive: Boolean get() = process.isAlive
        override fun waitFor(): Int = process.waitFor()
    }
}
