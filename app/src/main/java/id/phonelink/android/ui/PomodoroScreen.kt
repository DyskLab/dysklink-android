package id.phonelink.android.ui

import android.app.AlarmManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.service.PomodoroStore
import kotlinx.coroutines.delay

/** Ticks every second while the timer runs, and moves the timer on when a step ends. */
@Composable
private fun rememberPomodoroNow(running: Boolean): Long {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        PomodoroStore.load(context)
        while (true) {
            PomodoroStore.tick(context)
            now = System.currentTimeMillis()
            if (!running) break
            delay(1_000 - now % 1_000)
        }
    }
    return now
}

private fun phaseColor(phase: PomodoroPhase): Color = when (phase) {
    PomodoroPhase.Focus -> Palette.Coral
    PomodoroPhase.ShortBreak -> Palette.Mint
    PomodoroPhase.LongBreak -> Palette.Sky
}

@Composable
fun PomodoroHomeCard(onOpen: () -> Unit) {
    val saved by PomodoroStore.saved.collectAsStateWithLifecycle()
    val now = rememberPomodoroNow(saved.state.isRunning)
    val state = saved.state
    val settings = saved.settings
    val summary = when {
        state.isRunning -> "${state.title(settings)} · ${PomodoroState.clock(state.remaining(now, settings))} left"
        state.isStarted -> "${state.title(settings)} · Paused, ${PomodoroState.clock(state.remaining(now, settings))} left"
        else -> "Ready · ${settings.focusMinutes} min focus, ${settings.sessions} rounds"
    }
    ToolHomeCard(Icons.Rounded.Timer, Palette.Coral, "Pomodoro", summary, onOpen)
}

@Composable
fun PomodoroScreen(onBack: () -> Unit, openExactAlarms: () -> Unit) {
    val context = LocalContext.current
    val saved by PomodoroStore.saved.collectAsStateWithLifecycle()
    val state = saved.state
    val settings = saved.settings
    val now = rememberPomodoroNow(state.isRunning)
    val notifications = rememberNotificationAccess()
    val color = phaseColor(state.phase)
    val exact = remember(now / 60_000) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    fun set(change: PomodoroSettings) = PomodoroStore.setSettings(context, change)

    FeatureScaffold("Pomodoro", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PomodoroRing(state.progress(now, settings), color, Modifier.size(210.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.title(settings), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(PomodoroState.clock(state.remaining(now, settings)), color = Palette.TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                state.isRunning -> state.nextStep(settings)?.let { "Then ${it.first.label.lowercase()}" } ?: "Last step"
                                state.isStarted -> "Paused"
                                else -> "Ready"
                            },
                            color = Palette.TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SessionDots(state, settings)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(
                        when {
                            state.isRunning -> "Pause"
                            state.isStarted -> "Resume"
                            else -> "Start"
                        },
                        Modifier.weight(1f),
                    ) {
                        if (state.isRunning) {
                            PomodoroStore.pause(context)
                        } else {
                            PomodoroStore.start(context)
                            if (!notifications.allowed) notifications.ask()
                        }
                    }
                    SecondaryButton("Skip", Modifier.weight(0.7f)) { PomodoroStore.skip(context) }
                    SecondaryButton("Reset", Modifier.weight(0.7f), tint = if (state.isStarted) Palette.TextPrimary else Palette.TextFaint) {
                        PomodoroStore.reset(context)
                    }
                }
                Spacer(Modifier.height(10.dp))
                NotificationHint(notifications, "Notifications are off, so the countdown and the sound at the end of each step can't show while you're in another app.")
                if (!exact) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "With the screen off, Android may ring the end of a step a few minutes late. Allow exact alarms to ring on time.",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    ToolLink("Allow exact alarms", onClick = openExactAlarms)
                }
            }

            GlassCard {
                Text("Lengths", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                ChoiceStepper("Focus", PomodoroSettings.FOCUS_CHOICES, settings.focusMinutes, { "$it min" }) { set(settings.copy(focusMinutes = it)) }
                ChoiceStepper("Short break", PomodoroSettings.SHORT_CHOICES, settings.shortBreakMinutes, { "$it min" }) {
                    set(settings.copy(shortBreakMinutes = it))
                }
                ChoiceStepper("Long break", PomodoroSettings.LONG_CHOICES, settings.longBreakMinutes, { "$it min" }) {
                    set(settings.copy(longBreakMinutes = it))
                }
                ChoiceStepper("Rounds before long break", PomodoroSettings.SESSION_CHOICES, settings.sessions, { "$it" }) {
                    set(settings.copy(sessions = it))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "A step that's already running keeps its length; changes apply from the next one.",
                    color = Palette.TextFaint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            Text(
                "Runs on this phone, also with the screen off. Separate from the Pomodoro in the Mac's notch.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun SessionDots(state: PomodoroState, settings: PomodoroSettings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        (1..settings.sessions).forEach { session ->
            val done = session < state.session || (session == state.session && state.phase != PomodoroPhase.Focus)
            val current = session == state.session && state.phase == PomodoroPhase.Focus && state.isStarted
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> Palette.Coral
                            current -> Palette.Coral.copy(alpha = 0.5f)
                            else -> Color.White.copy(alpha = 0.12f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PomodoroRing(progress: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        // The ring empties as the step runs down.
        val left = 1f - progress
        if (left > 0f) {
            drawArc(color, -90f, 360f * left, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
