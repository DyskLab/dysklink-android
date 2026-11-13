package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.types.UInt32

/**
 * Desktop notifications on Linux over the session bus, without the tray. Popups went to the AWT tray's balloon
 * before, which doesn't exist where there's no tray (GNOME without AppIndicator), so they never showed there.
 * Also tells whether any notification service runs at all, for the hint in Settings.
 */
class LinuxNotifier private constructor(private val conn: DBusConnection) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dysklink-notify").apply { isDaemon = true } }

    /** Whether a notification service runs now, or can be started by the bus. Asked again on each window focus. */
    fun hasService(): Boolean = runCatching {
        worker.submit<Boolean> {
            val dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            dbus.NameHasOwner(LinuxTray.NOTIFICATIONS) || LinuxTray.NOTIFICATIONS in dbus.ListActivatableNames()
        }.get(2, TimeUnit.SECONDS)
    }.getOrDefault(true)

    fun notify(title: String, message: String) {
        worker.execute {
            runCatching {
                conn.getRemoteObject(LinuxTray.NOTIFICATIONS, "/org/freedesktop/Notifications", DesktopNotifications::class.java)
                    .Notify("DyskLink", UInt32(0), "", title, message, emptyList(), emptyMap(), -1)
            }.onFailure { CoreLog.w("DyskLink", "Can't show a notification", it) }
        }
    }

    fun close() {
        worker.execute { runCatching { conn.close() } }
        worker.shutdown()
    }

    companion object {
        fun start(address: String?): LinuxNotifier? {
            address ?: return null
            return runCatching { LinuxNotifier(DBusConnectionBuilder.forAddress(address).withShared(false).build()) }
                .onFailure { CoreLog.w("DyskLink", "No session bus for notifications", it) }.getOrNull()
        }
    }
}
