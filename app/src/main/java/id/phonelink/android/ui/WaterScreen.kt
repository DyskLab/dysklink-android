package id.phonelink.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.WaterLog
import id.phonelink.android.core.WaterSettings
import id.phonelink.android.service.WaterStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
private fun rememberWaterNow(): Long {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        WaterStore.load(context)
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    return now
}

private fun waterSummary(log: WaterLog, now: Long, zone: ZoneId): String {
    val total = log.totalToday(now, zone)
    val percent = (log.fraction(now, zone) * 100).toInt()
    return if (total >= log.settings.goalMl) "Goal reached · ${WaterLog.amount(total)} today"
    else "${WaterLog.amount(total)} of ${WaterLog.amount(log.settings.goalMl)} today · $percent%"
}

@Composable
fun WaterHomeCard(onOpen: () -> Unit) {
    val now = rememberWaterNow()
    val log by WaterStore.log.collectAsStateWithLifecycle()
    ToolHomeCard(Icons.Rounded.WaterDrop, Palette.Sky, "Water", waterSummary(log, now, ZoneId.systemDefault()), onOpen)
}

@Composable
fun WaterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val now = rememberWaterNow()
    val log by WaterStore.log.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val settings = log.settings
    val total = log.totalToday(now, zone)
    val notifications = rememberNotificationAccess()
    var custom by remember { mutableStateOf(false) }
    fun set(change: WaterSettings) = WaterStore.setSettings(context, change)

    FeatureScaffold("Water", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WaterRing(log.fraction(now, zone), Modifier.size(190.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(WaterLog.amount(total), color = Palette.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("of ${WaterLog.amount(settings.goalMl)}", color = Palette.TextSecondary, fontSize = 13.sp)
                        Text(
                            if (total >= settings.goalMl) "Goal reached" else "${(log.fraction(now, zone) * 100).toInt()}%",
                            color = Palette.SkyText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton("+ Glass ${settings.glassMl} ml", Modifier.weight(1f)) { WaterStore.drink(context) }
                    SecondaryButton("Custom", Modifier.weight(0.7f)) { custom = true }
                }
                if (total > 0) {
                    Spacer(Modifier.height(6.dp))
                    ToolLink("Undo last", Palette.TextSecondary) { WaterStore.undo(context) }
                }
            }

            GlassCard {
                Text("Last 7 days", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                WeekChart(log.week(now, zone), settings.goalMl, LocalDate.now(zone))
            }

            GlassCard {
                Text("Settings", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                ChoiceStepper("Daily goal", WaterSettings.GOAL_CHOICES, settings.goalMl, WaterLog::amount) { set(settings.copy(goalMl = it)) }
                ChoiceStepper("Glass size", WaterSettings.GLASS_CHOICES, settings.glassMl, { "$it ml" }) { set(settings.copy(glassMl = it)) }
                Spacer(Modifier.height(6.dp))
                ToolSwitchRow("Reminders", settings.reminds) { on ->
                    set(settings.copy(reminds = on))
                    if (on && !notifications.allowed) notifications.ask()
                }
                if (settings.reminds) {
                    ChoiceStepper("Every", WaterSettings.INTERVAL_CHOICES, settings.remindEveryMinutes, { "$it min" }) {
                        set(settings.copy(remindEveryMinutes = it))
                    }
                    ChoiceStepper("From", (0..23).filter { it < settings.toHour }, settings.fromHour, ::hourText) {
                        set(settings.copy(fromHour = it))
                    }
                    ChoiceStepper("Until", (1..24).filter { it > settings.fromHour }, settings.toHour, ::hourText) {
                        set(settings.copy(toHour = it))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(nextReminderText(log, now, zone), color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    NotificationHint(notifications, "Notifications are off for DyskLink, so reminders can't show.")
                }
            }
            Text(
                "Kept on this phone only, for 7 days. Separate from the water page in the Mac's notch.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }

    if (custom) CustomAmountDialog(onDismiss = { custom = false }, onAdd = { WaterStore.drink(context, it); custom = false })
}

private fun hourText(hour: Int): String = "%02d:00".format(Locale.US, hour % 24).let { if (hour == 24) "24:00" else it }

private fun nextReminderText(log: WaterLog, now: Long, zone: ZoneId): String {
    val next = log.nextReminder(now, zone) ?: return "The interval doesn't fit between these hours, so no reminder comes."
    val time = Instant.ofEpochMilli(next).atZone(zone)
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val goalReached = log.totalToday(now, zone) >= log.settings.goalMl
    return when {
        time.toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate() ->
            (if (goalReached) "Goal reached, so no more today. " else "") + "Next reminder tomorrow at $clock."
        next <= now + 60_000 -> "Next reminder any moment now."
        else -> "Next reminder at $clock. Drinking moves it later."
    }
}

@Composable
private fun WaterRing(fraction: Float, modifier: Modifier) {
    val track = Palette.Contrast.copy(alpha = 0.08f)
    val sky = Palette.Sky
    val light = if (Palette.IsDark) Color(0xFF6FD0FF) else Color(0xFF3FB6E8)
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(
                Brush.sweepGradient(listOf(light, sky, Color(0xFF4A63F5), light)),
                -90f,
                360f * fraction,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun WeekChart(days: List<Pair<LocalDate, Int>>, goal: Int, today: LocalDate) {
    val top = maxOf(goal, days.maxOf { it.second }).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { (day, ml) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (ml == 0) "" else if (ml >= 1000) WaterLog.amount(ml).removeSuffix(" L") + "L" else "$ml",
                    color = Palette.TextFaint,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.weight(1f).width(18.dp).clip(RoundedCornerShape(9.dp)).background(Palette.Contrast.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (ml > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((ml.toFloat() / top).coerceIn(0.04f, 1f))
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (ml >= goal) Palette.Sky else Palette.Sky.copy(alpha = 0.55f)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (day == today) "Today" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    color = if (day == today) Palette.TextPrimary else Palette.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Full bars reached the goal of ${WaterLog.amount(goal)}.", color = Palette.TextFaint, fontSize = 12.sp)
}

@Composable
private fun CustomAmountDialog(onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ml = text.toIntOrNull()
    val valid = ml != null && ml in 1..WaterLog.MAX_DRINK_ML
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an amount") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter { it.isDigit() }.take(4) },
                label = { Text("Millilitres") },
                suffix = { Text("ml") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onAdd(ml!!) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(ml!!) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
