package id.dysklink.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.HubPhone

// What this PC serves phones (its sound, commands, a terminal) and a Mac's mouse and keyboard here.

/** Banners for what's going on with [device] now: this PC's sound going to it, its terminals, a Mac using this PC. */
@Composable
fun ServiceAlerts(model: DeskModel, device: HubPhone) {
    val colors = Dysk.colors
    val soundTo by model.soundTo.collectAsState()
    val terminals by model.terminals.collectAsState()
    val usedBy by model.usedBy.collectAsState()
    if (soundTo == device.id) {
        Banner("Sending sound to ${device.name}.", colors.mint, DyskIcons.Speaker, "Stop" to model::stopSoundOut)
        Spacer(Modifier.height(Space.sm))
    }
    val open = terminals[device.id] ?: 0
    if (open > 0) {
        Banner(
            "${device.name} has ${if (open == 1) "a terminal" else "$open terminals"} open on this PC.", colors.amber, DyskIcons.Terminal,
            "End" to { model.endTerminals(device.id) },
        )
        Spacer(Modifier.height(Space.sm))
    }
    if (usedBy == device.id) {
        Banner("${device.name} is using this PC's mouse and keyboard.", colors.sky, DyskIcons.Mouse, "Stop" to model::stopRemote)
        Spacer(Modifier.height(Space.sm))
    }
}

/** On the Sound tab: this PC's sound on the phone. */
@Composable
fun PcSoundCard(context: TabContext) {
    val colors = Dysk.colors
    val model = context.model
    val device = context.device
    val on by model.soundOutOn.collectAsState()
    val soundTo by model.soundTo.collectAsState()
    val sending = soundTo == device.id
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.Waves, if (sending) colors.mint else colors.textSecondary, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("This PC's sound on ${device.name}", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body(
                    when {
                        sending -> "What plays on this PC is heard on ${device.name}, about a quarter of a second behind."
                        !device.isConnected -> "${device.name} isn't connected."
                        on -> "Open Sound in DyskLink on the phone and tap Listen."
                        else -> "Turn on to let the phone hear what plays on this PC. Nothing is recorded until the phone asks."
                    },
                )
            }
            if (sending) SecondaryButton("Stop", colors.coral, onClick = model::stopSoundOut)
            else StatusPill(if (on) "Ready" else "Off", if (on) colors.mint else colors.textFaint)
        }
        Divider()
        SettingRow(
            "Let phones hear this PC",
            "A paired phone can ask for this PC's sound: music, videos, and calls, not the microphone. Off stops it at once.",
            on, onChange = model::setSoundOutOn,
        )
    }
}

/** A device tab: what [TabContext.device] may run here, and its terminals. */
@Composable
fun ColumnScope.CommandsTab(context: TabContext) {
    val colors = Dysk.colors
    val model = context.model
    val device = context.device
    val commandPhones by model.commandPhones.collectAsState()
    val terminalPhones by model.terminalPhones.collectAsState()
    val terminals by model.terminals.collectAsState()
    val commands by model.commands.collectAsState()
    val canRun = device.id in commandPhones
    val canOpen = device.id in terminalPhones
    val open = terminals[device.id] ?: 0
    GlassCard {
        SettingRow(
            "Let ${device.name} run commands",
            "Only the commands listed in Settings, by name. The phone never sends a command line of its own.",
            canRun, onChange = { model.setCommandsAllowed(device.id, it) },
        )
        Divider()
        SettingRow(
            "Let ${device.name} open a terminal",
            "A shell on this PC from the phone, after the phone's own fingerprint, face, or screen lock each time. Up to 4 at once.",
            canOpen, onChange = { model.setTerminalAllowed(device.id, it) },
        )
        if (canRun || canOpen) {
            Spacer(Modifier.height(Space.sm))
            Banner(
                "Whoever has ${device.name} unlocked can run ${if (canOpen) "anything" else "these commands"} on this PC as you. Turn this off when you don't need it.",
                colors.amber, DyskIcons.Warning,
            )
        }
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.Terminal, if (open > 0) colors.amber else colors.textSecondary, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Terminals", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body(
                    when {
                        open > 0 -> "${if (open == 1) "One terminal is" else "$open terminals are"} open from ${device.name}. A notice shows here for each one."
                        canOpen -> "Open Terminal in DyskLink on the phone. They all end when the phone disconnects."
                        else -> "Off. Nothing can be opened from the phone."
                    },
                )
            }
            if (open > 0) SecondaryButton("End", colors.coral) { model.endTerminals(device.id) }
            else StatusPill(if (canOpen) "Allowed" else "Off", if (canOpen) colors.amber else colors.textFaint)
        }
    }
    SectionLabel("Commands on this PC")
    if (commands.isEmpty()) {
        EmptyCard("No commands yet. Add them in Settings, under Commands.", DyskIcons.Terminal)
    } else {
        GlassCard {
            commands.forEachIndexed { index, command ->
                if (index > 0) Divider()
                CommandLine(command.name, command.command)
            }
        }
    }
    Body("On the phone: Commands and Terminal on this PC's page in DyskLink. An older DyskLink on the phone doesn't show them; update it.", colors.textFaint)
}

@Composable
private fun CommandLine(name: String, line: String, trailing: (@Composable () -> Unit)? = null) {
    val colors = Dysk.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(line, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** In Settings: the commands phones may run, by name. */
@Composable
fun CommandsSettings(model: DeskModel) {
    val colors = Dysk.colors
    val commands by model.commands.collectAsState()
    var name by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    GlassCard {
        Body("Phones you allow on their page can run these by name. Each runs ${if (model.systemName.startsWith("Windows")) "with cmd.exe /c" else "with /bin/sh -c"}, for at most 60 seconds, and the phone sees the end of what it printed.")
        commands.forEach { command ->
            Divider()
            CommandLine(command.name, command.command) { TextAction("Remove", colors.coral) { model.removeCommand(command.id) } }
        }
        Divider()
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            InputField(name, { name = it; problem = null }, "Name, such as Lock screen", Modifier.weight(0.4f))
            InputField(line, { line = it; problem = null }, "Command line", Modifier.weight(0.6f))
            SecondaryButton("Add", icon = DyskIcons.Plus) {
                if (model.addCommand(name, line)) { name = ""; line = "" } else problem = "Give it a name and a command line."
            }
        }
        problem?.let { Spacer(Modifier.height(6.dp)); Text(it, color = colors.coral, fontSize = 12.sp) }
    }
}

/** In Settings, under Computers: a Mac's mouse and keyboard here. */
@Composable
fun RemoteSettings(model: DeskModel, computersOn: Boolean) {
    val colors = Dysk.colors
    val on by model.remoteOn.collectAsState()
    val commandAsCtrl by model.commandAsCtrl.collectAsState()
    Divider()
    SettingRow(
        "Let paired computers use their mouse and keyboard here",
        "A paired Mac can push its pointer past the edge it chose for this PC and type here, as if its mouse and keyboard were plugged in. Push the pointer back past that edge, or press Control-Option-Command on the Mac, to go back. Off by default.",
        on && computersOn, enabled = computersOn, onChange = model::setRemoteOn,
    )
    if (on && computersOn) {
        Divider()
        SettingRow(
            "Use Command as Ctrl",
            "Command-C, Command-V and the like work as Ctrl here. Off: Command is the ${if (model.systemName.startsWith("Windows")) "Windows" else "Super"} key.",
            commandAsCtrl, onChange = model::setCommandAsCtrl,
        )
        model.remoteNotes().forEach { note ->
            Spacer(Modifier.height(Space.sm))
            Banner(note, colors.amber, DyskIcons.Info)
        }
    }
}
