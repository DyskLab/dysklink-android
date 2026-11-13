package id.dysklink.desktop

/** One line of the tray menu. The same list feeds the AWT tray (Windows, XEmbed on Linux) and the D-Bus menu. */
sealed interface TrayEntry {
    class Item(val label: String, val enabled: Boolean = true, val onClick: () -> Unit = {}) : TrayEntry
    class Submenu(val label: String, val children: List<TrayEntry>) : TrayEntry
    data object Separator : TrayEntry
}

/** What the menu looks like, without the click handlers, to tell whether it has to be sent again. */
fun List<TrayEntry>.shape(): String = joinToString("|") { entry ->
    when (entry) {
        is TrayEntry.Item -> "i:${entry.enabled}:${entry.label}"
        is TrayEntry.Submenu -> "m:${entry.label}[${entry.children.shape()}]"
        TrayEntry.Separator -> "-"
    }
}

enum class TrayKind { StatusNotifier, Awt }

/** Where the tray icon lives. Main.kt picks one: StatusNotifierItem over D-Bus, then the AWT tray, then none. */
interface TrayHost {
    val kind: TrayKind

    /** The current menu and tooltip. Called on the UI thread whenever they may have changed. */
    fun update(menu: List<TrayEntry>, tooltip: String) {}

    /** Shows a desktop notification; false when the caller should use the AWT tray's balloon. */
    fun notify(title: String, message: String): Boolean = false

    fun close() {}
}

/** The AWT tray; Main.kt draws it with Compose's Tray from the same menu list, so nothing happens here. */
object AwtTrayHost : TrayHost {
    override val kind = TrayKind.Awt
}

object TrayHosts {
    /**
     * Linux first tries a StatusNotifierItem, which KDE, GNOME with AppIndicator, XFCE and others show natively;
     * the AWT tray only works where the desktop still has an XEmbed tray. Windows always uses the AWT tray.
     */
    fun choose(isLinux: Boolean, statusNotifier: () -> TrayHost?, awtSupported: () -> Boolean): TrayHost? =
        (if (isLinux) runCatching(statusNotifier).getOrNull() else null)
            ?: AwtTrayHost.takeIf { runCatching(awtSupported).getOrDefault(false) }
}

/** Settings shows this on Linux when there is no tray, because closing the window then quits. */
data class TrayHint(val text: String, val note: String?, val link: String?)

object TrayHints {
    const val EXTENSION_URL = "https://extensions.gnome.org/extension/615/appindicator-support/"

    fun isGnome(desktop: String?): Boolean = desktop.orEmpty().split(':').any { it.trim().contains("GNOME", ignoreCase = true) }

    fun pick(isLinux: Boolean, hasTray: Boolean, desktop: String?): TrayHint? = when {
        !isLinux || hasTray -> null
        isGnome(desktop) -> TrayHint(
            "No tray icon on this desktop. On GNOME, install the AppIndicator extension to keep DyskLink in the top bar.",
            "Until then, closing the window quits DyskLink.",
            EXTENSION_URL,
        )
        else -> TrayHint("Your desktop has no tray area; closing the window quits DyskLink.", null, null)
    }
}
