package id.dysklink.desktop

import id.phonelink.android.core.HubPhone

/** The parts of the tray menu that can be shown, hidden and moved, as the Mac's menu bar in Advanced. Quit is always last. */
enum class TraySection(val label: String, val needs: String?, private val block: Int) {
    Open("Open DyskLink", null, 0),
    Battery("This PC's battery", "Shows on a PC with a battery", 1),
    EnergyApp("Top energy app", "Shows on battery when an app uses significant energy", 1),
    Pomodoro("Pomodoro", null, 1),
    Water("Drink a glass of water", null, 1),
    Tools("Tools", null, 1),
    Devices("Devices and battery", "Shows when a device is paired", 2),
    SendFile("Send a file", "Shows when a phone is connected", 3),
    SendClipboard("Send clipboard", "Shows when a phone is connected", 3),
    ClipboardHistory("Clipboard history", "Needs Clipboard History in Tools", 3),
    PcCare("PC care", null, 1),
    Launcher("Launcher", "Needs the Launcher in Tools", 1),
    Layouts("Window layouts", "Needs a saved window layout", 1);

    /** Sections of one kind sit together; a line goes between kinds. */
    val group: Int get() = block

    companion object {
        /** The menu as it was before it could be changed. */
        val DEFAULT_SHOWN = setOf(Open, Battery, Pomodoro, Water, Tools, Devices, SendFile, SendClipboard, ClipboardHistory)
    }
}

data class TrayMenuConfig(val order: List<TraySection> = TraySection.entries, val shown: Set<TraySection> = TraySection.DEFAULT_SHOWN) {
    val visible: List<TraySection> get() = order.filter { it in shown }

    fun setShown(section: TraySection, on: Boolean) = copy(shown = if (on) shown + section else shown - section)

    /** Moves a section up (-1) or down (+1). */
    fun move(section: TraySection, step: Int): TrayMenuConfig {
        val index = order.indexOf(section)
        val target = index + step
        if (index < 0 || target !in order.indices) return this
        return copy(order = order.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun save(settings: Settings) {
        settings.set("trayMenu.order", order.joinToString(",") { it.name })
        settings.set("trayMenu.shown", order.filter { it in shown }.joinToString(",") { it.name })
    }

    companion object {
        fun load(settings: Settings): TrayMenuConfig {
            val saved = settings.string("trayMenu.order").orEmpty().split(',').mapNotNull { n -> TraySection.entries.firstOrNull { it.name == n } }.distinct()
            // Sections added in later versions go at the end.
            val order = saved + TraySection.entries.filter { it !in saved }
            val shown = settings.string("trayMenu.shown")?.split(',')?.mapNotNull { n -> TraySection.entries.firstOrNull { it.name == n } }?.toSet()
                ?: TraySection.DEFAULT_SHOWN
            return TrayMenuConfig(order, shown)
        }
    }
}

/** What the tray menu shows now. */
data class TrayMenuState(
    val phones: List<HubPhone> = emptyList(),
    /** The Pomodoro's countdown, while it's started. */
    val trayStatus: String? = null,
    val pomodoroRunning: Boolean = false,
    val pomodoroStarted: Boolean = false,
    val battery: String? = null,
    /** The app using the most energy, such as "Google Chrome (high)", while one is above the threshold. */
    val topEnergyApp: String? = null,
    val clipboardHistory: Boolean = false,
    val pcCare: Boolean = false,
    val launcher: Boolean = false,
    val layouts: List<Pair<String, String>> = emptyList(),
)

/** What the tray menu's items do. */
class TrayMenuActions(
    val open: () -> Unit = {},
    val openTools: () -> Unit = {},
    val openDevice: (String) -> Unit = {},
    val togglePomodoro: () -> Unit = {},
    val drink: () -> Unit = {},
    val sendFiles: (HubPhone) -> Unit = {},
    val sendClipboard: () -> Unit = {},
    val clipboardHistory: () -> Unit = {},
    val pcCare: () -> Unit = {},
    val launcher: () -> Unit = {},
    val restoreLayout: (String) -> Unit = {},
    val quit: () -> Unit = {},
)

object TrayMenu {
    fun build(config: TrayMenuConfig, state: TrayMenuState, actions: TrayMenuActions): List<TrayEntry> {
        val paired = state.phones.filter { it.isPaired }
        val connected = paired.filter { it.isConnected }
        val parts = config.visible.map { section ->
            section to buildList<TrayEntry> {
                when (section) {
                    TraySection.Open -> add(TrayEntry.Item("Open DyskLink", onClick = actions.open))
                    TraySection.Battery -> state.battery?.let { add(TrayEntry.Item("Battery: $it", enabled = false)) }
                    TraySection.EnergyApp -> state.topEnergyApp?.let { add(TrayEntry.Item("Top energy app: $it", enabled = false)) }
                    TraySection.Pomodoro -> {
                        state.trayStatus?.let { add(TrayEntry.Item(it, enabled = false)) }
                        add(TrayEntry.Item(if (state.pomodoroRunning) "Pause focus" else if (state.pomodoroStarted) "Resume focus" else "Start focus", onClick = actions.togglePomodoro))
                    }
                    TraySection.Water -> add(TrayEntry.Item("Drink a glass of water", onClick = actions.drink))
                    TraySection.Tools -> add(TrayEntry.Item("Tools…", onClick = actions.openTools))
                    TraySection.Devices -> paired.forEach { device -> add(TrayEntry.Item(deviceLabel(device), onClick = { actions.openDevice(device.id) })) }
                    TraySection.SendFile -> when (connected.size) {
                        0 -> add(TrayEntry.Item("Send file…", enabled = false))
                        1 -> add(TrayEntry.Item("Send file to ${connected[0].name}…", onClick = { actions.sendFiles(connected[0]) }))
                        else -> add(TrayEntry.Submenu("Send file to", connected.map { d -> TrayEntry.Item("${d.name}…", onClick = { actions.sendFiles(d) }) }))
                    }
                    TraySection.SendClipboard -> add(TrayEntry.Item("Send clipboard", enabled = connected.isNotEmpty(), onClick = actions.sendClipboard))
                    TraySection.ClipboardHistory -> if (state.clipboardHistory) add(TrayEntry.Item("Clipboard history", onClick = actions.clipboardHistory))
                    TraySection.PcCare -> if (state.pcCare) add(TrayEntry.Item("PC care…", onClick = actions.pcCare))
                    TraySection.Launcher -> if (state.launcher) add(TrayEntry.Item("Launcher", onClick = actions.launcher))
                    TraySection.Layouts -> when (state.layouts.size) {
                        0 -> Unit
                        1 -> add(TrayEntry.Item("Restore ${state.layouts[0].second}", onClick = { actions.restoreLayout(state.layouts[0].first) }))
                        else -> add(TrayEntry.Submenu("Window layouts", state.layouts.map { (id, name) -> TrayEntry.Item(name, onClick = { actions.restoreLayout(id) }) }))
                    }
                }
            }
        }.filter { it.second.isNotEmpty() }
        return buildList {
            var lastGroup: Int? = null
            parts.forEach { (section, entries) ->
                if (lastGroup != null && section.group != lastGroup) add(TrayEntry.Separator)
                addAll(entries)
                lastGroup = section.group
            }
            if (isNotEmpty()) add(TrayEntry.Separator)
            add(TrayEntry.Item("Quit DyskLink", onClick = actions.quit))
        }
    }

    fun deviceLabel(device: HubPhone): String {
        val battery = device.info?.batteryPercent
        return when {
            !device.isConnected -> "${device.name}: offline"
            battery != null -> "${device.name}: $battery%${if (device.info?.isCharging == true) ", charging" else ""}"
            else -> "${device.name}: connected"
        }
    }
}
