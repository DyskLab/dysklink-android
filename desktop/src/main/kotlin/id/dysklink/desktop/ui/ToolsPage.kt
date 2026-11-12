package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.PcNotch
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.core.TodoItem
import id.phonelink.android.core.WaterLog
import id.phonelink.android.core.WaterSettings
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The notch's tools on this PC, as on the Mac: a Pomodoro, water with reminders, and a Todo list. Phones paired with
 * this PC show the same and can change them from DyskLink. [clock] is for snapshots.
 */
@Composable
fun ToolsPage(model: DeskModel, clock: () -> Long = System::currentTimeMillis) {
    val tools by model.tools.collectAsState()
    var now by remember { mutableLongStateOf(clock()) }
    val running = tools.pomodoro.state.isRunning
    // The countdown moves every second while it runs; the rest every minute, for the day changing.
    LaunchedEffect(running) {
        while (true) {
            now = clock()
            delay(if (running) 1_000 else 30_000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Title("Tools", 26.sp)
        Text(
            "Focus, water, and tasks on ${model.deviceName}, like the notch on a Mac. Your phones see them and can change them from DyskLink.",
            color = Dysk.colors.textSecondary, fontSize = 13.sp,
        )
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(Modifier.weight(1f)) { PomodoroCard(model, tools, now) }
                    Box(Modifier.weight(1f)) { WaterCard(model, tools, now) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    PomodoroCard(model, tools, now)
                    WaterCard(model, tools, now)
                }
            }
        }
        TodoCard(model, tools, now)
    }
}

@Composable
private fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconWell(icon, tint, 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f), color = Dysk.colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        trailing()
    }
}

/** A round button with an icon, for Undo, Skip and the like. */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Dysk.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(colors.panelRaised).border(1.dp, colors.stroke, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DyskIcon(icon, if (enabled) colors.textPrimary else colors.textFaint, 15.dp) }
}

/** "Goal  –  2 L  +": one of a fixed list of values, as the Mac's steppers. */
@Composable
private fun <T> Stepper(title: String, value: T, choices: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    val colors = Dysk.colors
    val index = choices.indexOf(value).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().background(colors.panelRaised, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = colors.textSecondary, fontSize = 12.5.sp)
        StepButton("-", index > 0) { onChange(choices[index - 1]) }
        Text(label(value), Modifier.width(72.dp), color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+", index < choices.lastIndex) { onChange(choices[index + 1]) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Dysk.colors
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(colors.stroke).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) colors.textPrimary else colors.textFaint, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

// Pomodoro

@Composable
private fun PomodoroCard(model: DeskModel, tools: PcToolsState, now: Long) {
    val colors = Dysk.colors
    val settings = tools.pomodoro.settings
    val state = tools.pomodoro.state
    var editing by remember { mutableStateOf(false) }
    val tint = if (state.phase == PomodoroPhase.Focus) colors.coral else colors.mint
    GlassCard {
        CardTitle(DyskIcons.Timer, "Pomodoro", tint) {
            TextAction(if (editing) "Done" else "Lengths", colors.mint) { editing = !editing }
        }
        Spacer(Modifier.height(Space.sm))
        if (editing) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stepper("Focus", settings.focusMinutes, PomodoroSettings.FOCUS_CHOICES, { "$it min" }) { model.setPomodoro(settings.copy(focusMinutes = it)) }
                Stepper("Short break", settings.shortBreakMinutes, PomodoroSettings.SHORT_CHOICES, { "$it min" }) { model.setPomodoro(settings.copy(shortBreakMinutes = it)) }
                Stepper("Long break", settings.longBreakMinutes, PomodoroSettings.LONG_CHOICES, { "$it min" }) { model.setPomodoro(settings.copy(longBreakMinutes = it)) }
                Stepper("Rounds", settings.sessions, PomodoroSettings.SESSION_CHOICES, { "$it" }) { model.setPomodoro(settings.copy(sessions = it)) }
            }
            Spacer(Modifier.height(6.dp))
            Body("New lengths start with the next round.", colors.textFaint)
            return@GlassCard
        }
        Text(state.title(settings), color = tint, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        Text(
            PomodoroState.clock(state.remaining(now, settings)),
            color = colors.textPrimary, fontSize = 46.sp, fontWeight = FontWeight.Bold,
        )
        // How far this round has come, and the rounds of the cycle.
        Box(Modifier.fillMaxWidth().height(6.dp).background(colors.panelRaised, CircleShape)) {
            Box(Modifier.fillMaxWidth(state.progress(now, settings)).fillMaxHeight().background(tint, CircleShape))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            for (round in 1..settings.sessions) {
                val done = round < state.session || (round == state.session && state.phase != PomodoroPhase.Focus)
                Box(Modifier.size(9.dp).background(if (done) tint else colors.stroke, CircleShape))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (!state.isStarted) "Ready" else if (state.isRunning) "Running" else "Paused",
                color = colors.textFaint, fontSize = 11.5.sp,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                when {
                    state.isRunning -> "Pause"
                    state.isStarted -> "Resume"
                    else -> "Start focus"
                },
                icon = if (state.isRunning) DyskIcons.Pause else DyskIcons.Play,
                onClick = model::togglePomodoro,
            )
            RoundAction(DyskIcons.Skip, enabled = state.isStarted, onClick = model::skipPomodoro)
            RoundAction(DyskIcons.Rotate, enabled = state.isStarted, onClick = model::resetPomodoro)
        }
        Spacer(Modifier.height(6.dp))
        Body("A notification shows when a round ends. The countdown is in the tray.", colors.textFaint)
    }
}

// Water

@Composable
private fun WaterCard(model: DeskModel, tools: PcToolsState, now: Long) {
    val colors = Dysk.colors
    val water = tools.water
    val zone = ZoneId.systemDefault()
    val total = water.totalToday(now, zone)
    val settings = water.settings
    var editing by remember { mutableStateOf(false) }
    GlassCard {
        CardTitle(DyskIcons.Drop, "Water", colors.sky) {
            TextAction(if (editing) "Done" else "Settings", colors.mint) { editing = !editing }
        }
        Spacer(Modifier.height(Space.sm))
        if (editing) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stepper("Daily goal", settings.goalMl, WaterSettings.GOAL_CHOICES, WaterLog::amount) { model.setWater(settings.copy(goalMl = it)) }
                Stepper("Glass", settings.glassMl, WaterSettings.GLASS_CHOICES, { "$it ml" }) { model.setWater(settings.copy(glassMl = it)) }
                Stepper("Remind every", settings.remindEveryMinutes, WaterSettings.INTERVAL_CHOICES, { "$it min" }) { model.setWater(settings.copy(remindEveryMinutes = it)) }
                Stepper("From", settings.fromHour, (0 until settings.toHour).toList(), { "%02d:00".format(it) }) { model.setWater(settings.copy(fromHour = it)) }
                Stepper("Until", settings.toHour, ((settings.fromHour + 1)..24).toList(), { "%02d:00".format(it) }) { model.setWater(settings.copy(toHour = it)) }
            }
            Spacer(Modifier.height(Space.sm))
            SettingRow("Reminders", "A notification when it's time to drink, between these hours, until the goal is reached.", settings.reminds) {
                model.setWater(settings.copy(reminds = it))
            }
            return@GlassCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(WaterLog.amount(total), color = colors.textPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("of ${WaterLog.amount(settings.goalMl)}", Modifier.padding(bottom = 8.dp), color = colors.textSecondary, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(colors.panelRaised, CircleShape)) {
            Box(Modifier.fillMaxWidth(water.fraction(now, zone)).fillMaxHeight().background(colors.sky, CircleShape))
        }
        Spacer(Modifier.height(10.dp))
        WeekBars(water, now, zone)
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Drink ${settings.glassMl} ml", icon = DyskIcons.Drop, onClick = model::drink)
            RoundAction(DyskIcons.Undo, enabled = total > 0, onClick = model::undoDrink)
            Spacer(Modifier.weight(1f))
            Text(
                if (settings.reminds) "Reminders every ${settings.remindEveryMinutes} min" else "Reminders off",
                color = if (settings.reminds) colors.sky else colors.textFaint, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Today and the six days before, as small bars against the goal. */
@Composable
private fun WeekBars(water: WaterLog, now: Long, zone: ZoneId) {
    val colors = Dysk.colors
    val week = water.week(now, zone)
    val top = maxOf(water.settings.goalMl, week.maxOf { it.second }).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        week.forEachIndexed { index, (day, ml) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.width(14.dp).weight(1f), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxSize().background(colors.panelRaised, RoundedCornerShape(5.dp)))
                    if (ml > 0) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight((ml.toFloat() / top).coerceIn(0.08f, 1f))
                                .background(if (ml >= water.settings.goalMl) colors.mint else colors.sky, RoundedCornerShape(5.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    if (index == week.lastIndex) "Today" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    color = if (index == week.lastIndex) colors.textPrimary else colors.textFaint, fontSize = 10.sp,
                )
            }
        }
    }
}

// Todo

@Composable
private fun TodoCard(model: DeskModel, tools: PcToolsState, now: Long) {
    val colors = Dysk.colors
    val zone = ZoneId.systemDefault()
    val tasks = tools.todos.visible(now, zone)
    val done = tools.todos.doneToday(now, zone)
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    GlassCard {
        CardTitle(DyskIcons.Checklist, "Todo", colors.mint) {
            Text("$done/${tasks.size} done today", color = colors.textSecondary, fontSize = 12.sp)
            if (tasks.any { it.isDone }) {
                Spacer(Modifier.width(Space.xs))
                TextAction("Clear done", colors.textSecondary, model::clearDoneTodos)
            }
        }
        Spacer(Modifier.height(Space.sm))
        val add = {
            when {
                draft.isBlank() -> Unit
                model.addTodo(draft) -> { draft = ""; problem = null }
                else -> problem = "The list is full (${PcNotch.MAX_TODOS} tasks). Finish or remove some first."
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputField(draft, { draft = it.take(PcNotch.MAX_TITLE) }, "New task", Modifier.weight(1f), onSend = add)
            Spacer(Modifier.width(Space.xs))
            PrimaryButton("Add", enabled = draft.isNotBlank(), icon = DyskIcons.Plus, onClick = add)
        }
        problem?.let { Spacer(Modifier.height(6.dp)); Text(it, color = colors.coral, fontSize = 12.sp) }
        Spacer(Modifier.height(Space.sm))
        if (tasks.isEmpty()) {
            Text("No tasks yet. Tasks done on an earlier day are cleared each morning.", color = colors.textFaint, fontSize = 12.5.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tasks.forEach { task ->
                if (renaming == task.id) {
                    val save = {
                        model.renameTodo(task.id, renameText)
                        renaming = null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InputField(renameText, { renameText = it.take(PcNotch.MAX_TITLE) }, "Task name", Modifier.weight(1f), onSend = save)
                        Spacer(Modifier.width(Space.xs))
                        SecondaryButton("Save", onClick = save)
                        Spacer(Modifier.width(4.dp))
                        TextAction("Cancel", colors.textSecondary) { renaming = null }
                    }
                } else {
                    TaskRow(task, onToggle = { model.toggleTodo(task.id) }, onRename = { renaming = task.id; renameText = task.title }, onDelete = { model.deleteTodo(task.id) })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TodoItem, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (task.isDone) colors.mint.copy(alpha = 0.08f) else colors.panelRaised)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (task.isDone) colors.mint else Color.Transparent)
                .border(1.5.dp, if (task.isDone) colors.mint else colors.textFaint, CircleShape).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { if (task.isDone) DyskIcon(DyskIcons.Check, colors.onAccent, 12.dp) }
        Spacer(Modifier.width(10.dp))
        Text(
            task.title, Modifier.weight(1f).clickable(onClick = onToggle),
            color = if (task.isDone) colors.textSecondary else colors.textPrimary, fontSize = 13.5.sp,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRename), contentAlignment = Alignment.Center) {
            DyskIcon(DyskIcons.Pencil, colors.textSecondary, 13.dp)
        }
        Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            DyskIcon(DyskIcons.Trash, colors.coral, 13.dp)
        }
    }
}
