@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.phonelink.android.ui

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import id.phonelink.android.core.ObsState
import id.phonelink.android.core.ObsProtocol
import id.phonelink.android.core.ObsClient
import id.phonelink.android.core.ObsAction
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.CommandItem
import id.phonelink.android.core.Deck
import id.phonelink.android.core.DeckButton
import id.phonelink.android.core.DeckKind
import id.phonelink.android.core.Dictation
import id.phonelink.android.core.InputEvent
import java.util.UUID

private val deckColors = listOf(Palette.Mint, Palette.Sky, Palette.Violet, Palette.Amber, Palette.Coral, Palette.TextSecondary)

private fun deckColor(index: Int): Color = deckColors.getOrElse(index) { Palette.Mint }

@Composable
fun DeckScreen(
    /** The Mac that shortcuts, media, text, and commands go to, or null when opened from the Tools tab without one. */
    macName: String?,
    connected: Boolean,
    /** From the Mac: false when Accessibility is off, which makes it ignore keys. */
    macTrusted: Boolean? = null,
    commands: List<CommandItem>,
    onBack: () -> Unit,
    sendInput: (InputEvent) -> Unit,
    sendMedia: (String) -> Unit,
    runCommand: (String) -> Unit,
    /** "Mac", or "PC" for a Windows or Linux PC that serves the deck (`pc-deck`): Cmd works as Ctrl there. */
    kind: String = "Mac",
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("deck", Context.MODE_PRIVATE) }
    var buttons by remember { mutableStateOf(Deck.decode(prefs.getString("buttons", null))) }
    var columns by remember { mutableStateOf(prefs.getInt("columns", 3).coerceIn(2, 4)) }
    var editing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<DeckButton?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<String?>(null) }
    val commandNames = commands.associate { it.id to it.name }
    val obsClient = ObsClient.shared
    val obs by obsClient.state.collectAsStateWithLifecycle()
    val obsPrefs = remember { context.getSharedPreferences("obs", Context.MODE_PRIVATE) }
    // Reconnects to the OBS used last time, so its buttons are ready when the screen opens.
    LaunchedEffect(Unit) {
        val host = obsPrefs.getString("host", null)
        if (!host.isNullOrBlank() && obs.status == ObsState.Status.Off && buttons.any { it.kind == DeckKind.Obs }) {
            obsClient.connect(host, obsPrefs.getInt("port", ObsProtocol.DEFAULT_PORT), obsPrefs.getString("password", "").orEmpty())
        }
    }

    fun canRun(button: DeckButton): Boolean {
        if (!Deck.isValid(button)) return false
        if (button.kind == DeckKind.Obs) {
            val action = ObsAction.decode(button.value) ?: return false
            return obs.status == ObsState.Status.Connected && ObsProtocol.isAvailable(action, obs)
        }
        return connected && (button.kind != DeckKind.Command || button.value in commandNames)
    }

    fun save(list: List<DeckButton>) {
        buttons = list
        prefs.edit().putString("buttons", Deck.encode(list)).apply()
    }

    fun press(button: DeckButton) {
        if (!canRun(button)) {
            val reason = when {
                !Deck.isValid(button) -> "This button isn't finished. Tap Edit to fix it."
                button.kind == DeckKind.Obs && obs.status != ObsState.Status.Connected -> "Not connected to OBS. Connect in the OBS card below."
                button.kind == DeckKind.Obs -> "OBS doesn't have this anymore. Tap Edit to pick it again."
                macName == null -> "No $kind is connected. Open DyskLink on the $kind."
                !connected -> "Not connected to $macName."
                button.kind == DeckKind.Command -> "This command isn't on $macName anymore."
                else -> "Can't run this button."
            }
            android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (macTrusted == false && button.kind in setOf(DeckKind.Shortcut, DeckKind.Text)) {
            android.widget.Toast.makeText(context, "Accessibility is off for DyskLink on the $kind, so the $kind ignores keys.", android.widget.Toast.LENGTH_LONG).show()
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        pressed = button.id
        when (button.kind) {
            DeckKind.Shortcut -> Deck.normalizeShortcut(button.value)?.let { sendInput(InputEvent("shortcut", key = it)) }
            DeckKind.Media -> sendMedia(button.value)
            DeckKind.Text -> Dictation.events(button.value).forEach(sendInput)
            DeckKind.Command -> runCommand(button.value)
            DeckKind.Obs -> ObsAction.decode(button.value)?.let(obsClient::run)
        }
    }

    FeatureScaffold("Stream Deck", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        editing -> "Tap a button to change it."
                        macName == null -> "No $kind connected. OBS buttons still work."
                        connected -> "Buttons run on $macName."
                        else -> "Not connected to $macName. OBS buttons still work."
                    },
                    color = if (connected || editing) Palette.TextSecondary else Palette.Amber,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (editing) "Done" else "Edit",
                    color = Palette.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (editing) Palette.Mint else Palette.TextSecondary)
                        .clickable { editing = !editing }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }

            val cells: List<DeckButton?> = buttons + if (editing && buttons.size < Deck.MAX_BUTTONS) listOf(null) else emptyList()
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { button ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if (button == null) {
                                AddTile { editor = DeckButton(UUID.randomUUID().toString(), "", "", 0, DeckKind.Shortcut, "") }
                            } else {
                                DeckTile(
                                    button = button,
                                    detail = Deck.detail(button, commandNames[button.value]),
                                    enabled = editing || canRun(button),
                                    active = button.kind == DeckKind.Obs && ObsAction.decode(button.value)?.let { ObsProtocol.isActive(it, obs) } == true,
                                    flash = pressed == button.id,
                                    editing = editing,
                                    compact = columns >= 4,
                                    onClick = { if (editing) editor = button else press(button) },
                                )
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            ObsCard(obs, obsPrefs, obsClient)

            if (editing) {
                GlassCard {
                    Text("BUTTONS PER ROW", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (2..4).forEach { count ->
                            Chip("$count", count == columns) {
                                columns = count
                                prefs.edit().putInt("columns", count).apply()
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Reset to the default buttons", Modifier.fillMaxWidth(), tint = Palette.Coral) { confirmReset = true }
                }
            }
            Text(
                if (kind == "Mac") {
                    "Shortcuts and typing need Accessibility permission for DyskLink on the Mac. Mac commands are the ones " +
                        "added in DyskLink on the Mac. Buttons are saved on this phone and work with any paired Mac."
                } else {
                    "On a PC, Cmd works as Ctrl and Option as Alt, and Mac shortcuts such as Spotlight or a screenshot run " +
                        "their Windows or Linux match. PC commands are the ones added in DyskLink on the PC. Buttons are saved " +
                        "on this phone and work with any paired computer."
                },
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // A short flash on the pressed button, so it's clear the press was sent.
    pressed?.let { id ->
        LaunchedEffect(id, buttons) {
            kotlinx.coroutines.delay(180)
            pressed = null
        }
    }

    editor?.let { current ->
        val index = buttons.indexOfFirst { it.id == current.id }
        ButtonEditor(
            initial = current,
            computer = kind,
            isNew = index < 0,
            commands = commands,
            obs = obs,
            canMoveLeft = index > 0,
            canMoveRight = index in 0 until buttons.lastIndex,
            onDismiss = { editor = null },
            onSave = { updated ->
                save(if (index < 0) buttons + updated else buttons.map { if (it.id == updated.id) updated else it })
                editor = null
            },
            onDelete = {
                save(buttons.filterNot { it.id == current.id })
                editor = null
            },
            onMove = { step ->
                val list = buttons.toMutableList()
                val target = (index + step).coerceIn(0, list.lastIndex)
                list.add(target, list.removeAt(index))
                save(list)
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the buttons?") },
            text = { Text("Your buttons are replaced with the 12 default ones.") },
            confirmButton = {
                TextButton(onClick = {
                    save(Deck.defaults)
                    confirmReset = false
                }) { Text("Reset", color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeckTile(
    button: DeckButton,
    detail: String,
    enabled: Boolean,
    flash: Boolean,
    editing: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val color = if (active && button.kind == DeckKind.Obs && button.value in setOf("stream", "record")) Palette.Coral else deckColor(button.color)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(
                when {
                    flash -> color.copy(alpha = 0.45f)
                    active -> color.copy(alpha = 0.32f)
                    enabled -> color.copy(alpha = 0.14f)
                    else -> color.copy(alpha = 0.06f)
                },
            )
            .border(if (active) 2.dp else 1.dp, color.copy(alpha = if (editing || active) 0.9f else 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val icon = FlatIcons.named(button.icon)
        if (icon != null) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = color.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(if (compact) 22.dp else 30.dp),
            )
            Spacer(Modifier.height(6.dp))
        } else {
            Box(Modifier.size(width = 18.dp, height = 3.dp).clip(CircleShape).background(color.copy(alpha = if (enabled) 0.9f else 0.35f)))
            Spacer(Modifier.height(8.dp))
        }
        Text(
            button.label,
            color = if (enabled) Palette.TextPrimary else Palette.TextFaint,
            fontSize = if (compact) 12.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (compact) 13.sp else 16.sp,
        )
        if (active) {
            Text(
                when (button.value) {
                    "stream" -> "● LIVE"
                    "record" -> "● REC"
                    else -> if (button.value.startsWith("mute:")) "MUTED" else "ON"
                },
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        } else if (!compact) {
            Text(
                detail,
                color = if (enabled) color else Palette.TextFaint,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .border(1.dp, Palette.Stroke, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = Palette.TextSecondary, fontSize = 32.sp)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, color: Color = Palette.Mint, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) Palette.Ink else Palette.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) color else Palette.PanelRaised)
            .border(1.dp, if (on) color else Palette.Stroke, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ButtonEditor(
    initial: DeckButton,
    isNew: Boolean,
    commands: List<CommandItem>,
    obs: ObsState,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onDismiss: () -> Unit,
    onSave: (DeckButton) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    computer: String = "Mac",
) {
    var label by remember { mutableStateOf(initial.label) }
    var icon by remember { mutableStateOf(initial.icon) }
    var color by remember { mutableStateOf(initial.color) }
    var kind by remember { mutableStateOf(initial.kind) }
    var value by remember { mutableStateOf(initial.value) }
    var showAllIcons by remember { mutableStateOf(false) }
    val autoLabel = if (kind == DeckKind.Text && label.isBlank()) value.trim().replace('\n', ' ').take(24) else label.trim()
    val draft = DeckButton(initial.id, autoLabel, "", color, kind, if (kind == DeckKind.Shortcut) Deck.normalizeShortcut(value) ?: value else value, icon)
    val valid = Deck.isValid(draft)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Panel,
        contentColor = Palette.TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) "New button" else "Edit button",
                    color = Palette.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // A live preview of the button as it will look in the grid.
                Box(Modifier.size(width = 92.dp, height = 84.dp)) {
                    DeckTile(draft, "", enabled = true, flash = false, editing = false, compact = true, onClick = {})
                }
            }
            // Only the middle scrolls, so Save stays in reach while picking an action.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(24) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icon", color = Palette.TextSecondary, fontSize = 12.sp)
                val allIcons = listOf("" to null) + FlatIcons.deck
                // The chosen icon always shows, even when the list is folded.
                val folded = allIcons.take(13).let { first -> if (first.any { it.first == icon }) first else first.take(12) + allIcons.filter { it.first == icon } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (if (showAllIcons) allIcons else folded).forEach { (name, vector) ->
                        run {
                            val on = name == icon
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) deckColor(color).copy(alpha = 0.25f) else Palette.PanelRaised)
                                    .border(1.dp, if (on) deckColor(color) else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable { icon = name },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (vector == null) {
                                    Text("None", color = Palette.TextFaint, fontSize = 9.sp)
                                } else {
                                    androidx.compose.material3.Icon(vector, contentDescription = name, tint = if (on) deckColor(color) else Palette.TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Text(
                    if (showAllIcons) "Fewer icons" else "All ${allIcons.size - 1} icons",
                    color = Palette.Mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { showAllIcons = !showAllIcons }.padding(vertical = 4.dp),
                )
                Text("Color", color = Palette.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until Deck.COLORS).forEach { index ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(deckColor(index))
                                .border(2.dp, if (index == color) Palette.TextPrimary else Color.Transparent, CircleShape)
                                .clickable { color = index },
                        )
                    }
                }
                Text("Action", color = Palette.TextSecondary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckKind.entries.forEach { option ->
                        Chip(option.label, option == kind) {
                            if (option != kind) {
                                kind = option
                                value = ""
                            }
                        }
                    }
                }
                when (kind) {
                    DeckKind.Shortcut -> ShortcutPicker(value, computer) { shortcut, name ->
                        value = shortcut
                        if (name != null && (label.isBlank() || Deck.commonShortcuts.any { it.first == label })) label = name
                        if (name != null && icon.isEmpty()) icon = shortcutIcon(name)
                    }
                    DeckKind.Media -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        run {
                            Deck.media.forEach { (id, name) ->
                                Chip(name, value == id) {
                                    value = id
                                    if (label.isBlank() || Deck.media.any { it.second == label }) label = name
                                    if (icon.isEmpty() || mediaIcons.containsValue(icon)) icon = mediaIcons[id].orEmpty()
                                }
                            }
                        }
                    }
                    DeckKind.Text -> OutlinedTextField(
                        // A name is made from the text when none is given.
                        value = value,
                        onValueChange = { value = it.take(Deck.MAX_TEXT) },
                        label = { Text("Text to type") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    )
                    DeckKind.Obs -> ObsActionPicker(obs, value) { action ->
                        value = action.encode()
                        if (label.isBlank()) label = defaultObsLabel(action)
                        if (icon.isEmpty()) icon = defaultObsIcon(action)
                    }
                    DeckKind.Command -> if (commands.isEmpty()) {
                        Text(
                            "This $computer has no commands yet. Add them in DyskLink on the $computer, under Commands.",
                            color = Palette.Amber,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        commands.forEach { command ->
                            Chip(command.name, value == command.id, Palette.Sky) {
                                value = command.id
                                if (label.isBlank() || commands.any { it.name == label }) label = command.name.take(24)
                                if (icon.isEmpty()) icon = "terminal"
                            }
                        }
                    }
                }
                if (!isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (canMoveLeft) Chip("‹ Move earlier", false) { onMove(-1) }
                        if (canMoveRight) Chip("Move later ›", false) { onMove(1) }
                    }
                    Text(
                        "Delete this button",
                        color = Palette.Coral,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(
                Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 20.dp, vertical = 14.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                missing(draft)?.let { Text(it, color = Palette.Amber, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                    PrimaryButton("Save", Modifier.weight(1f).alpha(if (valid) 1f else 0.4f)) { if (valid) onSave(draft) }
                }
            }
        }
    }
}

private fun defaultObsLabel(action: ObsAction): String = when (action) {
    is ObsAction.Scene -> action.name
    ObsAction.Stream -> "Go live"
    ObsAction.Record -> "Record"
    ObsAction.RecordPause -> "Pause rec"
    ObsAction.Replay -> "Save replay"
    ObsAction.VirtualCam -> "Virtual cam"
    ObsAction.Transition -> "Transition"
    is ObsAction.Mute -> "Mute ${action.input}"
    is ObsAction.Source -> action.source
}.take(24)

/** Everything OBS offers right now, read from OBS, so nothing has to be typed. */
@Composable
private fun ObsActionPicker(obs: ObsState, value: String, onPick: (ObsAction) -> Unit) {
    if (obs.status != ObsState.Status.Connected) {
        Text(
            "Connect to OBS first, in the OBS card under the buttons. Then its scenes, audio, and sources show up here.",
            color = Palette.Amber,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        ObsAction.decode(value)?.let { Text("Now: " + ObsProtocol.label(it), color = Palette.TextFaint, fontSize = 12.sp) }
        return
    }
    @Composable
    fun group(title: String, actions: List<ObsAction>, text: (ObsAction) -> String) {
        if (actions.isEmpty()) return
        Text(title, color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            actions.forEach { action -> Chip(text(action), action.encode() == value, Palette.Violet) { onPick(action) } }
        }
    }
    group("Outputs", listOf(ObsAction.Stream, ObsAction.Record, ObsAction.RecordPause, ObsAction.Replay, ObsAction.VirtualCam, ObsAction.Transition)) {
        ObsProtocol.label(it)
    }
    group("Switch to scene", obs.scenes.map { ObsAction.Scene(it) }) { (it as ObsAction.Scene).name }
    group("Mute / unmute", obs.audio.keys.sorted().map { ObsAction.Mute(it) }) { (it as ObsAction.Mute).input }
    obs.items.forEach { (scene, items) ->
        group("Show / hide in $scene", items.map { ObsAction.Source(scene, it.source) }.distinct()) { (it as ObsAction.Source).source }
    }
}

@Composable
private fun ObsCard(obs: ObsState, prefs: android.content.SharedPreferences, client: ObsClient) {
    var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty()) }
    var port by remember { mutableStateOf(prefs.getInt("port", ObsProtocol.DEFAULT_PORT).toString()) }
    var password by remember { mutableStateOf(prefs.getString("password", "").orEmpty()) }
    var open by remember { mutableStateOf(obs.status != ObsState.Status.Connected && host.isBlank()) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OBS", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(
                    when (obs.status) {
                        ObsState.Status.Off -> "Not connected"
                        ObsState.Status.Connecting -> "Connecting…"
                        ObsState.Status.Connected -> listOfNotNull(
                            obs.currentScene,
                            "LIVE".takeIf { obs.streaming },
                            (if (obs.recordPaused) "REC paused" else "REC").takeIf { obs.recording },
                        ).joinToString(" · ").ifEmpty { "Connected" }
                        ObsState.Status.Failed -> "Couldn't connect"
                    },
                    color = when (obs.status) {
                        ObsState.Status.Connected -> if (obs.streaming || obs.recording) Palette.Coral else Palette.Mint
                        ObsState.Status.Failed -> Palette.Coral
                        else -> Palette.TextPrimary
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                if (open) "Hide" else "Settings",
                color = Palette.Mint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        obs.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Text(
                "In OBS, open Tools > WebSocket Server Settings, turn on Enable WebSocket server, and press Show Connect Info " +
                    "for the address, port, and password. OBS can be on this computer or any other on the same Wi-Fi.",
                color = Palette.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim().take(100) },
                    label = { Text("Computer address") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(200) },
                label = { Text("Password (if set)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (obs.status == ObsState.Status.Connected || obs.status == ObsState.Status.Connecting) {
            SecondaryButton("Disconnect", Modifier.fillMaxWidth()) { client.disconnect() }
        } else {
            PrimaryButton("Connect to OBS", Modifier.fillMaxWidth()) {
                val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: ObsProtocol.DEFAULT_PORT
                if (host.isBlank()) {
                    open = true
                    return@PrimaryButton
                }
                prefs.edit().putString("host", host).putInt("port", portNumber).putString("password", password).apply()
                client.connect(host, portNumber, password)
                open = false
            }
        }
    }
}

/**
 * Builds a shortcut by tapping: a ready-made one, or modifiers plus one key. Nothing has to be typed
 * except a letter or number.
 */
@Composable
private fun ShortcutPicker(value: String, kind: String, onChange: (shortcut: String, name: String?) -> Unit) {
    val split = Deck.splitShortcut(value)
    var modifiers by remember { mutableStateOf(split?.first ?: emptySet()) }
    var key by remember { mutableStateOf(split?.second ?: "") }
    var showAll by remember { mutableStateOf(false) }

    fun update(newModifiers: Set<String>, newKey: String) {
        modifiers = newModifiers
        key = newKey
        onChange(Deck.joinShortcut(newModifiers, newKey) ?: "", null)
    }

    val preview = Deck.normalizeShortcut(value)
    Text(
        preview?.let { (if (kind == "Mac") "On the Mac: " else "On a Mac: ") + Deck.describeShortcut(it) } ?: "Pick a ready-made shortcut, or tap the keys below.",
        color = if (preview != null) Palette.Mint else Palette.TextSecondary,
        fontSize = if (preview != null) 16.sp else 12.sp,
        fontWeight = if (preview != null) FontWeight.Bold else FontWeight.Normal,
    )

    Text("Ready-made", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val shown = if (showAll) Deck.commonShortcuts else Deck.commonShortcuts.take(8)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        run {
            shown.forEach { (name, shortcut) ->
                Chip("$name  ${Deck.describeShortcut(shortcut)}", preview == shortcut, Palette.Sky) {
                    val parts = Deck.splitShortcut(shortcut)
                    modifiers = parts?.first ?: emptySet()
                    key = parts?.second ?: ""
                    onChange(shortcut, name)
                }
            }
        }
    }
    Text(
        if (showAll) "Show fewer" else "Show all ${Deck.commonShortcuts.size}",
        color = Palette.Mint,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).clickable { showAll = !showAll }.padding(vertical = 4.dp),
    )

    Text("Or build your own: hold", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        run {
            listOf("ctrl", "opt", "shift", "cmd", "fn").forEach { mod ->
                Chip(Deck.modifierLabels.getValue(mod), mod in modifiers) {
                    update(if (mod in modifiers) modifiers - mod else modifiers + mod, key)
                }
            }
        }
    }
    Text("and press", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = key.takeIf { it.length == 1 }?.uppercase() ?: "",
        onValueChange = { typed ->
            val char = typed.lastOrNull()?.lowercaseChar()?.toString()
            if (char != null) update(modifiers, char)
        },
        label = { Text("A letter, number, or symbol") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Deck.specialKeys.forEach { (id, name) -> Chip(name, key == id) { update(modifiers, id) } }
    }
}

private fun defaultObsIcon(action: ObsAction): String = when (action) {
    is ObsAction.Scene -> "scene"
    ObsAction.Stream -> "live"
    ObsAction.Record -> "record"
    ObsAction.RecordPause -> "pause"
    ObsAction.Replay -> "replay"
    ObsAction.VirtualCam -> "camera"
    ObsAction.Transition -> "transition"
    is ObsAction.Mute -> "mic_off"
    is ObsAction.Source -> "show"
}

/** A fitting icon for a ready-made shortcut, or none. */
private fun shortcutIcon(name: String): String = when (name) {
    "Copy" -> "copy"
    "Paste" -> "paste"
    "Cut" -> "cut"
    "Undo" -> "undo"
    "Redo" -> "redo"
    "Save" -> "save"
    "Find", "Spotlight", "DyskLink Launcher" -> "search"
    "Mission Control", "App windows", "Show desktop", "Desktop left", "Desktop right" -> "windows"
    "Screenshot area", "Screenshot screen", "Screenshot tools" -> "screenshot"
    "Lock screen" -> "lock"
    "New tab", "Reopen tab", "Close tab", "Next tab", "Previous tab", "Reload page" -> "web"
    "Emoji picker" -> "keyboard"
    "Force quit", "Quit app" -> "bolt"
    else -> ""
}

private val mediaIcons = mapOf(
    "play_pause" to "play", "next" to "next", "previous" to "previous",
    "volume_up" to "volume_up", "volume_down" to "volume_down", "mute" to "mute",
)

/** Why Save is off, so the editor never just greys it out without saying. */
private fun missing(button: DeckButton): String? = when {
    Deck.isValid(button) -> null
    button.kind == DeckKind.Shortcut && Deck.normalizeShortcut(button.value) == null -> "Pick a shortcut or a key."
    button.kind == DeckKind.Media && Deck.media.none { it.first == button.value } -> "Pick a media action."
    button.kind == DeckKind.Text && button.value.isEmpty() -> "Write the text to type."
    button.kind == DeckKind.Command && button.value.isBlank() -> "Pick a command."
    button.kind == DeckKind.Obs && ObsAction.decode(button.value) == null -> "Pick an OBS action."
    button.label.isBlank() -> "Give the button a name."
    else -> "Something is missing."
}
