@file:Suppress("FunctionName")

package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import java.util.Properties as JProperties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.MethodNoReply
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// Wayland doesn't let apps move the pointer, type, or capture the screen by themselves. xdg-desktop-portal does it
// for them once the user says yes in a system dialog: RemoteDesktop for the pointer and keyboard, ScreenCast for the
// screen (as a PipeWire stream). GNOME, KDE Plasma and others have both; wlroots desktops (Sway, Hyprland) usually
// only ScreenCast. Every call answers with a Request object whose Response signal carries the result.

// ---- D-Bus interfaces, with the portal's own names and signatures. ----

@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    fun Close()

    /** 0 the user said yes, 1 they cancelled, 2 anything else. */
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

@DBusInterfaceName("org.freedesktop.portal.Session")
interface PortalSessionObject : DBusInterface {
    fun Close()

    class Closed(path: String, val details: Map<String, Variant<*>>) : DBusSignal(path, details)
}

@DBusInterfaceName("org.freedesktop.portal.RemoteDesktop")
interface RemoteDesktopPortal : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun SelectDevices(session: DBusPath, options: Map<String, Variant<*>>): DBusPath
    fun Start(session: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath

    // Sent without waiting for a reply, so a moving pointer never waits on the bus. Order is kept on one connection.
    @MethodNoReply fun NotifyPointerMotion(session: DBusPath, options: Map<String, Variant<*>>, dx: Double, dy: Double)
    @MethodNoReply fun NotifyPointerMotionAbsolute(session: DBusPath, options: Map<String, Variant<*>>, stream: UInt32, x: Double, y: Double)
    @MethodNoReply fun NotifyPointerButton(session: DBusPath, options: Map<String, Variant<*>>, button: Int, state: UInt32)
    @MethodNoReply fun NotifyPointerAxis(session: DBusPath, options: Map<String, Variant<*>>, dx: Double, dy: Double)
    @MethodNoReply fun NotifyPointerAxisDiscrete(session: DBusPath, options: Map<String, Variant<*>>, axis: UInt32, steps: Int)
    @MethodNoReply fun NotifyKeyboardKeycode(session: DBusPath, options: Map<String, Variant<*>>, keycode: Int, state: UInt32)
    @MethodNoReply fun NotifyKeyboardKeysym(session: DBusPath, options: Map<String, Variant<*>>, keysym: Int, state: UInt32)
}

@DBusInterfaceName("org.freedesktop.portal.ScreenCast")
interface ScreenCastPortal : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun SelectSources(session: DBusPath, options: Map<String, Variant<*>>): DBusPath
    fun Start(session: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath
}

/** One shortcut for BindShortcuts: an id and its properties (description, preferred_trigger). */
class PortalShortcut(
    @field:org.freedesktop.dbus.annotations.Position(0) val id: String,
    @field:org.freedesktop.dbus.annotations.Position(1) val properties: Map<String, Variant<*>>,
) : org.freedesktop.dbus.Struct()

@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
interface GlobalShortcutsPortal : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun BindShortcuts(session: DBusPath, shortcuts: List<PortalShortcut>, parentWindow: String, options: Map<String, Variant<*>>): DBusPath

    class Activated(
        path: String,
        val session: DBusPath,
        val shortcutId: String,
        val timestamp: org.freedesktop.dbus.types.UInt64,
        val options: Map<String, Variant<*>>,
    ) : DBusSignal(path, session, shortcutId, timestamp, options)
}

// ---- What the portal offers and says, apart from the bus so it can be tested. ----

object PortalBits {
    const val DESKTOP = "org.freedesktop.portal.Desktop"
    const val PATH = "/org/freedesktop/portal/desktop"
    const val REMOTE_DESKTOP = "org.freedesktop.portal.RemoteDesktop"
    const val SCREEN_CAST = "org.freedesktop.portal.ScreenCast"
    const val GLOBAL_SHORTCUTS = "org.freedesktop.portal.GlobalShortcuts"

    const val KEYBOARD = 1
    const val POINTER = 2
    const val MONITOR = 1
    /** A monitor the compositor adds for the stream, sized by what the stream's reader asks for (GNOME, KDE Plasma 6). */
    const val VIRTUAL = 4
    const val CURSOR_HIDDEN = 1
    const val CURSOR_EMBEDDED = 2
    /** Kept until the user takes it back, so the dialog shows once. */
    const val PERSIST_UNTIL_REVOKED = 2

    const val RESPONSE_OK = 0
    const val RESPONSE_CANCELLED = 1

    /** The Request path the portal will use: the sender's unique name without ':' and with '.' as '_'. */
    fun requestPath(uniqueName: String, token: String): String =
        "/org/freedesktop/portal/desktop/request/${uniqueName.removePrefix(":").replace('.', '_')}/$token"

    fun sessionPath(uniqueName: String, token: String): String =
        "/org/freedesktop/portal/desktop/session/${uniqueName.removePrefix(":").replace('.', '_')}/$token"

    /** SelectDevices: keyboard and pointer, kept for good when the portal is new enough (version 2). */
    fun selectDevices(token: String, version: Int, restoreToken: String?): Map<String, Variant<*>> = buildMap {
        put("handle_token", Variant(token))
        put("types", Variant(UInt32((KEYBOARD or POINTER).toLong())))
        if (version >= 2) {
            put("persist_mode", Variant(UInt32(PERSIST_UNTIL_REVOKED.toLong())))
            restoreToken?.takeIf { it.isNotBlank() }?.let { put("restore_token", Variant(it)) }
        }
    }

    /**
     * SelectSources: one monitor, with the pointer drawn in when the portal can. In a remote desktop session the
     * permission is kept through SelectDevices, so [persist] is false there; a plain screen cast keeps it from version 4.
     */
    fun selectSources(token: String, version: Int, cursorModes: Int, restoreToken: String?, persist: Boolean, types: Int = MONITOR): Map<String, Variant<*>> = buildMap {
        put("handle_token", Variant(token))
        put("types", Variant(UInt32(types.toLong())))
        put("multiple", Variant(false))
        if (cursorModes and CURSOR_EMBEDDED != 0) put("cursor_mode", Variant(UInt32(CURSOR_EMBEDDED.toLong())))
        if (persist && version >= 4) {
            put("persist_mode", Variant(UInt32(PERSIST_UNTIL_REVOKED.toLong())))
            restoreToken?.takeIf { it.isNotBlank() }?.let { put("restore_token", Variant(it)) }
        }
    }

    /** The streams in a Start response: a(ua{sv}), each a PipeWire node and its place on the desktop. */
    fun streams(value: Any?): List<PortalStream> = items(unwrap(value)).mapNotNull { item ->
        val fields = items(item)
        val node = (fields.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val props = (fields.getOrNull(1) as? Map<String, Any?>).orEmpty()
        PortalStream(node, pair(props["position"]), pair(props["size"]))
    }

    private fun pair(value: Any?): Pair<Int, Int>? {
        val parts = items(unwrap(value)).mapNotNull { (it as? Number)?.toInt() }
        return if (parts.size >= 2) parts[0] to parts[1] else null
    }

    private fun unwrap(value: Any?): Any? = if (value is Variant<*>) unwrap(value.value) else value

    /** A struct or array as dbus-java hands it over: a Struct, an Object[], or a List. */
    private fun items(value: Any?): List<Any?> = when (val v = unwrap(value)) {
        null -> emptyList()
        is List<*> -> v.map(::unwrap)
        is Array<*> -> v.map(::unwrap)
        is org.freedesktop.dbus.Container -> v.parameters.map(::unwrap)
        else -> emptyList()
    }

    fun uint(value: Any?): Int? = when (val v = unwrap(value)) {
        is UInt32 -> v.toInt()
        is Number -> v.toInt()
        else -> null
    }

    fun string(value: Any?): String? = when (val v = unwrap(value)) {
        is String -> v
        is DBusPath -> v.path
        else -> null
    }
}

/** A PipeWire stream from the portal: [node] for pipewiresrc, [position] and [size] in desktop (logical) pixels. */
data class PortalStream(val node: Long, val position: Pair<Int, Int>?, val size: Pair<Int, Int>?)

/** Why a portal session didn't start. */
class PortalRefused(val cancelled: Boolean, message: String) : Exception(message)

/** What one portal interface offers. Null [version] means the interface isn't there. */
data class PortalOffer(val version: Int?, val types: Int = 0, val cursorModes: Int = 0)

/** Restore tokens, so the user is asked once and not every time. One per kind of session, in dataDir. */
class PortalTokens(private val file: File) {
    @Synchronized
    fun get(kind: String): String? = load().getProperty(kind)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun set(kind: String, token: String?) {
        val props = load()
        if (token.isNullOrBlank()) props.remove(kind) else props.setProperty(kind, token)
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "DyskLink: what the desktop portal allowed") }
        }.onFailure { CoreLog.w("DyskLink", "Can't save the portal permission", it) }
    }

    private fun load(): JProperties = JProperties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    companion object {
        const val INPUT = "remoteDesktop"
        const val SCREEN_WITH_INPUT = "remoteDesktopScreen"
        const val SCREEN = "screenCast"
        /** A virtual monitor for the phone as a second display, alone and with the pointer. */
        const val VIRTUAL = "virtualScreen"
        const val VIRTUAL_WITH_INPUT = "virtualScreenInput"
    }
}

/** A started portal session: the pointer and keyboard when [devices] has them, the screen when [streams] isn't empty. */
class PortalSession(
    private val client: PortalClient,
    val handle: DBusPath,
    val devices: Int,
    val streams: List<PortalStream>,
) {
    @Volatile var closed = false
        internal set
    private val noOptions: Map<String, Variant<*>> = emptyMap()
    private val remote: RemoteDesktopPortal get() = client.remoteDesktop

    val hasPointer: Boolean get() = devices and PortalBits.POINTER != 0
    val hasKeyboard: Boolean get() = devices and PortalBits.KEYBOARD != 0

    fun motion(dx: Double, dy: Double) = remote.NotifyPointerMotion(handle, noOptions, dx, dy)
    fun motionAbsolute(stream: Long, x: Double, y: Double) = remote.NotifyPointerMotionAbsolute(handle, noOptions, UInt32(stream), x, y)
    fun button(evdev: Int, down: Boolean) = remote.NotifyPointerButton(handle, noOptions, evdev, UInt32(if (down) 1L else 0L))
    fun axisDiscrete(horizontal: Boolean, steps: Int) = remote.NotifyPointerAxisDiscrete(handle, noOptions, UInt32(if (horizontal) 1L else 0L), steps)
    fun keycode(evdev: Int, down: Boolean) = remote.NotifyKeyboardKeycode(handle, noOptions, evdev, UInt32(if (down) 1L else 0L))
    fun keysym(sym: Int, down: Boolean) = remote.NotifyKeyboardKeysym(handle, noOptions, sym, UInt32(if (down) 1L else 0L))

    fun close() {
        if (closed) return
        closed = true
        client.closeSession(this)
    }
}

/**
 * The portal over its own session bus connection. [start] calls block until the user answers the system dialog, so
 * they run off the UI and the connection's threads.
 */
class PortalClient(
    private val conn: DBusConnection,
    private val tokens: PortalTokens,
    private val answerTimeoutMs: Long = 180_000,
) : AutoCloseable {
    private val counter = AtomicInteger()
    private val prefix = "dysklink${ProcessHandle.current().pid()}_"
    private val sessions = java.util.concurrent.CopyOnWriteArrayList<Pair<PortalSession, (PortalSession) -> Unit>>()

    internal val remoteDesktop: RemoteDesktopPortal by lazy { conn.getRemoteObject(PortalBits.DESKTOP, PortalBits.PATH, RemoteDesktopPortal::class.java) }
    private val screenCast: ScreenCastPortal by lazy { conn.getRemoteObject(PortalBits.DESKTOP, PortalBits.PATH, ScreenCastPortal::class.java) }

    init {
        // The desktop can end a session (the user clicked Stop in the top bar): it's dropped here too.
        conn.addSigHandler(PortalSessionObject.Closed::class.java) { signal ->
            sessions.filter { it.first.handle.path == signal.path }.forEach { (session, onClosed) ->
                session.closed = true
                sessions.removeIf { it.first === session }
                runCatching { onClosed(session) }
            }
        }
    }

    private fun token() = prefix + counter.incrementAndGet()

    fun offer(iface: String): PortalOffer = runCatching {
        val props = conn.getRemoteObject(PortalBits.DESKTOP, PortalBits.PATH, Properties::class.java)
        val version = PortalBits.uint(props.Get<Any?>(iface, "version")) ?: return PortalOffer(null)
        if (iface == PortalBits.REMOTE_DESKTOP) {
            PortalOffer(version, types = PortalBits.uint(runCatching { props.Get<Any?>(iface, "AvailableDeviceTypes") }.getOrNull()) ?: 0)
        } else {
            PortalOffer(
                version,
                types = PortalBits.uint(runCatching { props.Get<Any?>(iface, "AvailableSourceTypes") }.getOrNull()) ?: 0,
                cursorModes = PortalBits.uint(runCatching { props.Get<Any?>(iface, "AvailableCursorModes") }.getOrNull()) ?: 0,
            )
        }
    }.getOrElse {
        CoreLog.i("DyskLink", "No $iface portal: ${it.message}")
        PortalOffer(null)
    }

    /**
     * Calls [call] with a fresh handle_token and waits for its Response. The signal is listened for before the call,
     * at the path the portal will use, so a quick answer isn't missed.
     */
    private fun request(timeoutMs: Long, call: (String) -> DBusPath): Map<String, Variant<*>> {
        val token = token()
        val expected = PortalBits.requestPath(conn.uniqueName, token)
        val answer = CompletableFuture<PortalRequest.Response>()
        val handler = conn.addSigHandler(PortalRequest.Response::class.java) { if (it.path == expected) answer.complete(it) }
        try {
            val path = call(token).path
            if (path != expected) {
                // Old portals made up their own path; the answer comes there.
                runCatching { handler.close() }
                val late = conn.addSigHandler(PortalRequest.Response::class.java) { if (it.path == path) answer.complete(it) }
                return wait(answer, timeoutMs, path).also { runCatching { late.close() } }
            }
            return wait(answer, timeoutMs, path)
        } finally {
            runCatching { handler.close() }
        }
    }

    private fun wait(answer: CompletableFuture<PortalRequest.Response>, timeoutMs: Long, path: String): Map<String, Variant<*>> {
        val response = try {
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            runCatching { conn.getRemoteObject(PortalBits.DESKTOP, path, PortalRequest::class.java).Close() }
            throw PortalRefused(false, "No answer from the system dialog.")
        }
        return when (response.response.toInt()) {
            PortalBits.RESPONSE_OK -> response.results
            PortalBits.RESPONSE_CANCELLED -> throw PortalRefused(true, "It wasn't allowed.")
            else -> throw PortalRefused(false, "The desktop refused it.")
        }
    }

    private fun createSession(create: (Map<String, Variant<*>>) -> DBusPath): DBusPath {
        val results = request(15_000) { token -> create(mapOf("handle_token" to Variant(token), "session_handle_token" to Variant(token()))) }
        val handle = PortalBits.string(results["session_handle"]) ?: throw PortalRefused(false, "The portal made no session.")
        return DBusPath(handle)
    }

    /**
     * The pointer and keyboard, and with [screen] one monitor too, in one session and one dialog. Kept by a restore
     * token, so the dialog only shows the first time.
     */
    fun startRemoteDesktop(screen: PortalOffer?, onClosed: (PortalSession) -> Unit = {}, sources: Int = PortalBits.MONITOR): PortalSession {
        val offer = offer(PortalBits.REMOTE_DESKTOP)
        val version = offer.version ?: throw PortalRefused(false, "This desktop has no remote desktop portal.")
        val kind = when {
            screen == null -> PortalTokens.INPUT
            sources == PortalBits.VIRTUAL -> PortalTokens.VIRTUAL_WITH_INPUT
            else -> PortalTokens.SCREEN_WITH_INPUT
        }
        val handle = createSession { remoteDesktop.CreateSession(it) }
        try {
            request(15_000) { remoteDesktop.SelectDevices(handle, PortalBits.selectDevices(it, version, tokens.get(kind))) }
            if (screen != null) {
                request(15_000) { screenCast.SelectSources(handle, PortalBits.selectSources(it, screen.version ?: 1, screen.cursorModes, null, persist = false, types = sources)) }
            }
            val results = request(answerTimeoutMs) { remoteDesktop.Start(handle, "", mapOf("handle_token" to Variant(it))) }
            tokens.set(kind, PortalBits.string(results["restore_token"]) ?: tokens.get(kind))
            val devices = PortalBits.uint(results["devices"]) ?: 0
            return track(PortalSession(this, handle, devices, PortalBits.streams(results["streams"])), onClosed)
        } catch (e: Exception) {
            closeHandle(handle)
            // A token the portal no longer takes is dropped, so the next try asks again instead of failing again.
            if (e is PortalRefused && !e.cancelled) tokens.set(kind, null)
            throw e
        }
    }

    /** One monitor through ScreenCast alone, for when the pointer isn't wanted or there's no RemoteDesktop. */
    fun startScreenCast(offer: PortalOffer, onClosed: (PortalSession) -> Unit = {}, sources: Int = PortalBits.MONITOR): PortalSession {
        val version = offer.version ?: throw PortalRefused(false, "This desktop has no screen sharing portal.")
        val kind = if (sources == PortalBits.VIRTUAL) PortalTokens.VIRTUAL else PortalTokens.SCREEN
        val handle = createSession { screenCast.CreateSession(it) }
        try {
            request(15_000) {
                screenCast.SelectSources(handle, PortalBits.selectSources(it, version, offer.cursorModes, tokens.get(kind), persist = true, types = sources))
            }
            val results = request(answerTimeoutMs) { screenCast.Start(handle, "", mapOf("handle_token" to Variant(it))) }
            tokens.set(kind, PortalBits.string(results["restore_token"]) ?: tokens.get(kind))
            val streams = PortalBits.streams(results["streams"])
            if (streams.isEmpty()) throw PortalRefused(false, "No screen was shared.")
            return track(PortalSession(this, handle, 0, streams), onClosed)
        } catch (e: Exception) {
            closeHandle(handle)
            if (e is PortalRefused && !e.cancelled) tokens.set(kind, null)
            throw e
        }
    }

    /**
     * Binds one global shortcut for this app and calls [onPress] when it's pressed. The desktop may ask in a dialog
     * first, and may let the user pick other keys than [trigger]. Closing the result ends the session.
     */
    fun bindShortcut(id: String, description: String, trigger: String, onPress: () -> Unit): AutoCloseable {
        if (offer(PortalBits.GLOBAL_SHORTCUTS).version == null) throw PortalRefused(false, "This desktop has no global shortcuts portal.")
        val shortcuts: GlobalShortcutsPortal = conn.getRemoteObject(PortalBits.DESKTOP, PortalBits.PATH, GlobalShortcutsPortal::class.java)
        val handle = createSession { shortcuts.CreateSession(it) }
        var last = 0L
        val listener = conn.addSigHandler(GlobalShortcutsPortal.Activated::class.java) { signal ->
            val now = System.currentTimeMillis()
            if (signal.session.path == handle.path && signal.shortcutId == id && now - last > 300) {
                last = now
                runCatching(onPress)
            }
        }
        try {
            val shortcut = PortalShortcut(id, mapOf("description" to Variant(description), "preferred_trigger" to Variant(trigger)))
            request(answerTimeoutMs) { shortcuts.BindShortcuts(handle, listOf(shortcut), "", mapOf("handle_token" to Variant(it))) }
        } catch (e: Exception) {
            runCatching { listener.close() }
            closeHandle(handle)
            throw e
        }
        return AutoCloseable {
            runCatching { listener.close() }
            closeHandle(handle)
        }
    }

    private fun track(session: PortalSession, onClosed: (PortalSession) -> Unit): PortalSession {
        sessions += session to onClosed
        return session
    }

    internal fun closeSession(session: PortalSession) {
        sessions.removeIf { it.first === session }
        closeHandle(session.handle)
    }

    private fun closeHandle(handle: DBusPath) {
        runCatching { conn.getRemoteObject(PortalBits.DESKTOP, handle.path, PortalSessionObject::class.java).Close() }
    }

    override fun close() {
        sessions.toList().forEach { it.first.close() }
        runCatching { conn.close() }
    }

    companion object {
        fun connect(address: String?, tokens: PortalTokens): PortalClient? {
            address ?: return null
            return runCatching { PortalClient(DBusConnectionBuilder.forAddress(address).withShared(false).build(), tokens) }
                .onFailure { CoreLog.w("DyskLink", "No session bus for the portal", it) }.getOrNull()
        }
    }
}
