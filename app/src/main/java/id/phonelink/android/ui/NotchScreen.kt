package id.phonelink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.NotchAction
import id.phonelink.android.core.NotchSnapshot
import id.phonelink.android.core.NotchTask
import kotlinx.coroutines.delay

private const val MAX_TITLE = 80

/** The Mac notch's Pomodoro, water, and Todo list. Changes go to the Mac, which sends the new state back. */
@Composable
fun NotchScreen(
    macName: String,
    snapshot: NotchSnapshot?,
    focusDnd: Boolean,
    dndAccess: Boolean,
    onBack: () -> Unit,
    send: (NotchAction) -> Unit,
    setFocusDnd: (Boolean) -> Unit,
    openDndSettings: () -> Unit,
    /** "Mac", or "PC" for a Windows or Linux PC with the tools page (`pc-notch`). */
    kind: String = "Mac",
) {
    FeatureScaffold(if (kind == "Mac") Feature.Notch.title else "PC tools", onBack) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snapshot == null) {
                Text(
                    if (kind == "Mac") "Waiting for $macName. This needs DyskLink 0.2.0 or later on the Mac." else "Waiting for $macName.",
                    color = Palette.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                return@Column
            }
            FocusCard(snapshot, send)
            DndCard(focusDnd, dndAccess, setFocusDnd, openDndSettings)
            WaterCard(snapshot, send)
            NoteCard(snapshot.state.note, snapshot.receivedAt, send)
            TodoCard(snapshot.state.todos, send)
            Text(
                if (kind == "Mac") {
                    "These are the same as in the notch on $macName. The Mac keeps the data, so changes here show up there at once."
                } else {
                    "These are the same as on the Tools page of DyskLink on $macName. The PC keeps the data, so changes here show up there at once."
                },
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/** The quick note. What's typed goes to the Mac after a short pause, and the Mac's copy only replaces it when idle. */
@Composable
private fun NoteCard(note: String?, receivedAt: Long, send: (NotchAction) -> Unit) {
    if (note == null) return
    var draft by remember { mutableStateOf(note) }
    var editedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(note, receivedAt) {
        if (System.currentTimeMillis() - editedAt > NOTE_IDLE_MS) draft = note
    }
    LaunchedEffect(editedAt) {
        if (editedAt == 0L) return@LaunchedEffect
        delay(NOTE_SEND_DELAY_MS)
        send(NotchAction("note.set", text = draft))
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("QUICK NOTE", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f))
            if (draft.isNotEmpty()) {
                Text(
                    "Copy",
                    color = Palette.Mint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(draft)) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it.take(NOTE_MAX)
                editedAt = System.currentTimeMillis()
            },
            placeholder = { Text("An address, a number, a shopping list…") },
            textStyle = TextStyle(color = Palette.TextPrimary, fontSize = 15.sp, lineHeight = 21.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 3,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text("The same note is in the notch. It saves as you type.", color = Palette.TextFaint, fontSize = 12.sp)
    }
}

private const val NOTE_MAX = 4000
private const val NOTE_SEND_DELAY_MS = 600L
private const val NOTE_IDLE_MS = 2500L

@Composable
private fun FocusCard(snapshot: NotchSnapshot, send: (NotchAction) -> Unit) {
    val focus = snapshot.state.focus
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(focus.running, snapshot.receivedAt) {
        while (focus.running) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val left = if (focus.running) (focus.remainingMs - (now - snapshot.receivedAt)).coerceAtLeast(0) else focus.remainingMs
    val seconds = ((left + 999) / 1000).toInt()
    val phase = when (focus.phase) {
        "shortBreak" -> "Short break"
        "longBreak" -> "Long break"
        else -> "Focus"
    }
    val color = if (focus.phase == "focus") Palette.Coral else Palette.Mint
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("POMODORO", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    color = Palette.TextPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("$phase · session ${focus.session} of ${focus.sessions}", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if (focus.running) "Pause" else "Start", Modifier.width(110.dp)) {
                    send(NotchAction("focus.toggle"))
                }
                SecondaryButton("Reset", Modifier.width(110.dp)) { send(NotchAction("focus.reset")) }
            }
        }
        if (focus.lengthMs > 0) {
            Spacer(Modifier.height(12.dp))
            ProgressBar(1f - left.toFloat() / focus.lengthMs, color)
        }
    }
}

@Composable
private fun DndCard(on: Boolean, hasAccess: Boolean, setOn: (Boolean) -> Unit, openSettings: () -> Unit) {
    GlassCard(onClick = { if (!hasAccess && !on) openSettings() else setOn(!on) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Do Not Disturb while focusing", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (hasAccess) {
                        "Turns on priority-only Do Not Disturb during focus rounds and off when they end. A Do Not Disturb you set yourself is left alone."
                    } else {
                        "Needs Do Not Disturb access. Tap to open the setting, then turn DyskLink on."
                    },
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Switch(
                checked = on && hasAccess,
                onCheckedChange = { if (!hasAccess) openSettings() else setOn(it) },
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun WaterCard(snapshot: NotchSnapshot, send: (NotchAction) -> Unit) {
    val water = snapshot.state.water
    GlassCard {
        Text("WATER", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(litres(water.totalMl), color = Palette.TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(" / ${litres(water.goalMl)} L", color = Palette.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(water.totalMl.toFloat() / water.goalMl.coerceAtLeast(1), Palette.Sky)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Drink ${water.glassMl} ml", Modifier.weight(1f)) { send(NotchAction("water.drink")) }
            SecondaryButton("Undo", Modifier.width(100.dp)) { send(NotchAction("water.undo")) }
        }
    }
}

@Composable
private fun TodoCard(tasks: List<NotchTask>, send: (NotchAction) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<NotchTask?>(null) }
    var renameText by remember { mutableStateOf("") }
    val add = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            send(NotchAction("todo.add", text = text))
            draft = ""
        }
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODO", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text("${tasks.count { it.done }}/${tasks.size} done", color = Palette.TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        TaskInput(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Add a task",
            icon = Icons.Default.Add,
            iconLabel = "Add task",
            onSubmit = add,
        )
        if (tasks.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("No tasks yet.", color = Palette.TextFaint, fontSize = 13.sp)
        }
        tasks.forEach { task ->
            Spacer(Modifier.height(8.dp))
            if (renaming?.id == task.id) {
                val save = {
                    if (renameText.isNotBlank() && renameText.trim() != task.title) {
                        send(NotchAction("todo.rename", id = task.id, text = renameText.trim()))
                    }
                    renaming = null
                }
                TaskInput(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = "Task name",
                    icon = Icons.Default.Check,
                    iconLabel = "Save name",
                    onSubmit = save,
                    autoFocus = true,
                )
            } else {
                TaskRow(
                    task,
                    onToggle = { send(NotchAction("todo.toggle", id = task.id)) },
                    onRename = {
                        renaming = task
                        renameText = task.title
                    },
                    onDelete = { send(NotchAction("todo.delete", id = task.id)) },
                )
            }
        }
    }
}

/** A pill-shaped field with a round button inside it, so the two always line up. */
@Composable
private fun TaskInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    iconLabel: String,
    onSubmit: () -> Unit,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val ready = value.isNotBlank()
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(Palette.PanelRaised)
            .border(1.dp, if (focused) Palette.Mint.copy(alpha = 0.55f) else Palette.Stroke, CircleShape)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Palette.TextFaint, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.replace("\n", "").take(MAX_TITLE)) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.Mint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (ready) Palette.Mint else Palette.Stroke)
                .clickable(enabled = ready, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = iconLabel, tint = if (ready) Palette.Ink else Palette.TextFaint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TaskRow(task: NotchTask, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (task.done) Palette.Mint.copy(alpha = 0.08f) else Palette.PanelRaised)
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onToggle)
                .padding(10.dp),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (task.done) Palette.Mint else Color.Transparent)
                    .border(1.5.dp, if (task.done) Palette.Mint else Palette.TextFaint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (task.done) Icon(Icons.Default.Check, contentDescription = "Done", tint = Palette.Ink, modifier = Modifier.size(15.dp))
            }
        }
        Text(
            task.title,
            color = if (task.done) Palette.TextSecondary else Palette.TextPrimary,
            fontSize = 15.sp,
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onRename).padding(vertical = 12.dp),
        )
        Text(
            "Remove",
            color = Palette.Coral,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(Palette.Stroke),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

private fun litres(ml: Int): String {
    val value = ml / 1000.0
    return if (value == Math.floor(value)) "%.0f".format(value) else "%.2f".format(value).trimEnd('0')
}
