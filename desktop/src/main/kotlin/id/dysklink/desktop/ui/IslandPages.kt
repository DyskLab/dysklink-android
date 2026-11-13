@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package id.dysklink.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.ClipEntry
import id.dysklink.desktop.IslandPage
import id.dysklink.desktop.IslandSection
import id.dysklink.desktop.IslandService
import id.dysklink.desktop.IslandText
import id.dysklink.desktop.LyricsService
import id.dysklink.desktop.LyricsState
import id.dysklink.desktop.MediaCommand
import id.dysklink.desktop.NowPlaying
import id.dysklink.desktop.PlacesView
import id.dysklink.desktop.Platform
import id.dysklink.desktop.PrayerRules
import id.dysklink.desktop.ShelfItem
import id.dysklink.desktop.ShelfMode
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PrayerTime
import id.phonelink.android.core.PrayerWeatherApi
import id.phonelink.android.core.TodoItem
import id.phonelink.android.core.TransferStatus
import id.phonelink.android.core.WaterLog
import id.phonelink.android.core.WaterSettings
import id.phonelink.android.core.WeatherDay
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val faint = Color.White.copy(alpha = 0.55f)

@Composable
internal fun IslandPageContent(service: IslandService, ui: IslandUiState, now: Long, reducedMotion: Boolean, scrolls: Boolean, onKeyboard: (Boolean) -> Unit) {
    when (ui.page) {
        IslandPage.Music -> MusicPage(service, now)
        IslandPage.Pomodoro -> PomodoroPage(service, now)
        IslandPage.Shelf -> if (ui.shelfMode == ShelfMode.Files && service.pages().isShown(IslandSection.Files)) FilesPage(service, ui, scrolls) else ClipboardPage(service, now, scrolls)
        IslandPage.Prayer -> PrayerPage(service, now)
        IslandPage.Weather -> WeatherPage(service)
        IslandPage.Todo -> TodoPage(service, ui, now, scrolls, onKeyboard)
        IslandPage.Water -> WaterPage(service, ui, now)
        IslandPage.Notifications -> NotificationsPage(service, now, scrolls)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        DyskIcon(icon, Color.White.copy(alpha = 0.75f), 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, fill: Color = Color.White.copy(alpha = 0.14f), enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(fill).clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer(alpha = if (enabled) 1f else 0.4f).padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { DyskIcon(it, Color.White, 12.dp); Spacer(Modifier.width(5.dp)) }
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Float = 26f, mirror: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer(alpha = if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { DyskIcon(icon, Color.White, (size * 0.45f).dp, Modifier.graphicsLayer(scaleX = if (mirror) -1f else 1f)) }
}

@Composable
private fun Bar(value: Float, color: Brush, height: Float = 5f, modifier: Modifier = Modifier.fillMaxWidth()) {
    Box(modifier.height(height.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(color))
    }
}

private fun solid(c: Color) = SolidColor(c) as Brush

@Composable
private fun SideDivider() = Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 4.dp).background(Color.White.copy(alpha = 0.14f)))

// Music

@Composable
private fun MusicPage(service: IslandService, now: Long) {
    val track by service.track.collectAsState()
    val art by service.art.collectAsState()
    val lyricsOn by service.lyrics.isOn.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(if (lyricsOn) Modifier.width(230.dp) else Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtImage(art?.takeIf { it.key == track?.key }, 58f)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: "Nothing playing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track?.subtitle?.ifEmpty { " " } ?: if (Platform.isWindows) "Play something in Spotify, a browser, or any media app" else "Play something in Spotify, a browser, or any MPRIS player",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val t = track
            val duration = t?.durationMs
            val elapsed = t?.elapsedMs(now)
            if (t != null && duration != null && elapsed != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(IslandText.clock(elapsed), color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).height(14.dp).then(if (t.canSeek) Modifier.pointerInput(t.key) {
                        detectTapGesturesCompat { x -> service.seek((duration * (x / size.width)).toLong()) }
                    } else Modifier), contentAlignment = Alignment.Center) {
                        Bar(if (duration > 0) elapsed.toFloat() / duration else 0f, solid(Color.White))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("-" + IslandText.clock(duration - elapsed), color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                MediaButton(DyskIcons.Skip, 15f, mirror = true, enabled = t != null) { service.media(MediaCommand.Previous) }
                MediaButton(if (t?.isPlaying == true) DyskIcons.Pause else DyskIcons.Play, 19f, enabled = t != null) { service.media(MediaCommand.PlayPause) }
                MediaButton(DyskIcons.Skip, 15f, enabled = t != null) { service.media(MediaCommand.Next) }
                Box(Modifier.size(32.dp, 28.dp).clip(RoundedCornerShape(8.dp)).clickable { service.lyrics.setOn(!lyricsOn) }, contentAlignment = Alignment.Center) {
                    DyskIcon(DyskIcons.Message, Color.White.copy(alpha = if (lyricsOn) 1f else 0.6f), 15.dp)
                }
            }
        }
        if (lyricsOn) {
            SideDivider()
            LyricsColumn(service.lyrics, track?.elapsedMs(now), Modifier.weight(1f))
        }
    }
}

/** A tap at a place along a bar, for seeking. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: (Float) -> Unit) {
    detectTapGestures(onTap = { offset: Offset -> onTap(offset.x) })
}

@Composable
private fun MediaButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Float, mirror: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(32.dp, 28.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).graphicsLayer(alpha = if (enabled) 1f else 0.45f), contentAlignment = Alignment.Center) {
        DyskIcon(icon, Color.White, size.dp, Modifier.graphicsLayer(scaleX = if (mirror) -1f else 1f))
    }
}

/** The lyrics beside the player: the current line in white with one before and three after, like a karaoke screen. */
@Composable
private fun LyricsColumn(lyrics: LyricsService, elapsed: Long?, modifier: Modifier) {
    val state by lyrics.state.collectAsState()
    val romanizes by lyrics.romanizes.collectAsState()
    val roman = remember(state) { LyricsService.romanized(state) }
    val shown = if (romanizes) roman ?: state else state
    Box(modifier.fillMaxHeight()) {
        when (shown) {
            LyricsState.Idle, LyricsState.Loading -> Note("Looking for lyrics...")
            LyricsState.NotFound -> Note("No lyrics found for this song")
            LyricsState.Failed -> Note("Couldn't reach lrclib.net. Lyrics come back with the connection.")
            is LyricsState.Plain -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.lines.forEach { Text(it.ifEmpty { " " }, color = Color.White.copy(alpha = 0.8f), fontSize = 12.5.sp) }
            }
            is LyricsState.Timed -> {
                val lines = shown.lines
                val current = lyrics.tracker.line(lines, elapsed)
                val center = current ?: 0
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (elapsed == null && current == null) {
                        Text("This player doesn't say where the song is, so the lines don't move.", color = Color.White.copy(alpha = 0.4f), fontSize = 10.5.sp)
                    }
                    for (i in (center - 1).coerceAtLeast(0)..(center + 3).coerceAtMost(lines.lastIndex)) {
                        val isCurrent = i == current
                        Text(
                            lines[i].text.ifEmpty { "..." }, Modifier.height(if (isCurrent) 38.dp else 17.dp),
                            color = Color.White.copy(alpha = if (isCurrent) 1f else 0.45f), fontSize = if (isCurrent) 14.5.sp else 12.5.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = if (isCurrent) 2 else 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (roman != null) {
            Box(
                Modifier.align(Alignment.TopEnd).height(18.dp).clip(RoundedCornerShape(50))
                    .background(if (romanizes) Color.White else Color.White.copy(alpha = 0.14f)).clickable { lyrics.setRomanizes(!romanizes) }.padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (romanizes) "Original" else "Romaji", color = if (romanizes) Color.Black else Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// Pomodoro

/** Every step of one cycle in order: focus and short break per session, the last with the long break. */
internal fun pomodoroSteps(s: PomodoroSettings): List<Pair<PomodoroPhase, Int>> = (1..s.sessions).flatMap { n ->
    if (n < s.sessions) listOf(PomodoroPhase.Focus to n, PomodoroPhase.ShortBreak to n) else listOf(PomodoroPhase.Focus to n, PomodoroPhase.LongBreak to n)
}

@Composable
private fun PomodoroPage(service: IslandService, now: Long) {
    val tools by service.tools.collectAsState()
    val settings = tools.pomodoro.settings
    val state = tools.pomodoro.state
    val steps = pomodoroSteps(settings)
    val index = steps.indexOfFirst { it.first == state.phase && it.second == state.session }.coerceAtLeast(0)
    val remaining = state.remaining(now, settings)
    val inCycle = remaining + steps.drop(index + 1).sumOf { settings.lengthMs(it.first) }
    val progress = state.progress(now, settings)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(180.dp)) {
            Text(
                when (state.phase) { PomodoroPhase.Focus -> "Focus · ${state.session} of ${settings.sessions}"; PomodoroPhase.ShortBreak -> "Short break"; PomodoroPhase.LongBreak -> "Long break" },
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            )
            Text(IslandText.clock(remaining), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Row {
                Text(IslandText.duration(inCycle), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text("until done", color = IslandColors.violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Bar(progress, solid(IslandColors.violet))
        }
        SideDivider()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This round", Modifier.weight(1f), color = Color.White.copy(alpha = 0.6f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Pill(null, if (state.isRunning) "Pause" else if (state.isStarted) "Resume" else "Start") { service.tools { togglePomodoro() } }
                Spacer(Modifier.width(6.dp))
                RoundButton(DyskIcons.Rotate, enabled = state.isStarted) { service.tools { resetPomodoro() } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, (phase, _) ->
                    val current = if (state.isStarted) index else -1
                    val fill = if (i < current) 1f else if (i == current) progress else 0f
                    if (phase == PomodoroPhase.ShortBreak) Box(Modifier.size(6.dp).clip(CircleShape).background(if (fill > 0) IslandColors.mint else Color.White.copy(alpha = 0.2f)))
                    else Bar(fill, solid(if (phase == PomodoroPhase.LongBreak) IslandColors.mint else IslandColors.violet), 6f, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                steps.forEach { (phase, session) ->
                    if (phase == PomodoroPhase.ShortBreak) Spacer(Modifier.width(6.dp))
                    else Text(if (phase == PomodoroPhase.LongBreak) "Long" else "$session", Modifier.weight(1f), color = faint, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
            Text("${IslandText.duration(steps.sumOf { settings.lengthMs(it.first) })} · ${settings.sessions} sessions", color = faint, fontSize = 12.sp)
        }
    }
}

// Shelf

private class FilesTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any = files
}

@Composable
private fun FilesPage(service: IslandService, ui: IslandUiState, scrolls: Boolean) {
    val shelf by service.shelf.collectAsState()
    val phone by service.phone.collectAsState()
    val transfers by service.transfers.collectAsState()
    val shelfDrop = remember(service) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { ui.shelfTargeted = true }
            override fun onExited(event: DragAndDropEvent) { ui.shelfTargeted = false }
            override fun onEnded(event: DragAndDropEvent) { ui.shelfTargeted = false }
            override fun onDrop(event: DragAndDropEvent): Boolean { service.addToShelf(event.files()); return true }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val stroke = if (ui.shelfTargeted) IslandColors.mint else Color.White.copy(alpha = 0.28f)
        Column(
            Modifier.weight(1f).fillMaxHeight().dashedBorder(stroke).dragAndDropTarget({ true }, shelfDrop).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            if (shelf.isEmpty()) {
                DyskIcon(DyskIcons.Tray, Color.White.copy(alpha = 0.85f), 22.dp)
                Spacer(Modifier.height(4.dp))
                Text("Drop files here", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Drag out anytime · click to open", color = faint, fontSize = 12.sp)
            } else {
                Row(
                    Modifier.fillMaxWidth().weight(1f).then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) { shelf.forEach { ShelfCard(service, it, phone) } }
                Text("${shelf.size} ${if (shelf.size == 1) "file" else "files"} · drag out anytime · click to open", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }
        phone?.let { p ->
            val latest = transfers.firstOrNull { it.peerId == p.id && it.outgoing }
            val phoneDrop = remember(service, p.id) {
                object : DragAndDropTarget {
                    override fun onEntered(event: DragAndDropEvent) { ui.phoneTargeted = true }
                    override fun onExited(event: DragAndDropEvent) { ui.phoneTargeted = false }
                    override fun onEnded(event: DragAndDropEvent) { ui.phoneTargeted = false }
                    override fun onDrop(event: DragAndDropEvent): Boolean = service.send(event.files(), p)
                }
            }
            val targeted = ui.phoneTargeted
            Column(
                Modifier.width(150.dp).fillMaxHeight().background(if (targeted) IslandColors.mint.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(16.dp))
                    .dashedBorder(if (targeted) IslandColors.mint else Color.White.copy(alpha = 0.28f)).dragAndDropTarget({ true }, phoneDrop)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                DyskIcon(DyskIcons.Phone, if (targeted) IslandColors.mint else Color.White.copy(alpha = 0.85f), 20.dp)
                Text("Send to phone", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(p.name, color = faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                latest?.let { tr ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(tr.name, color = Color.White.copy(alpha = 0.8f), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (tr.status) {
                            TransferStatus.Active -> Bar(if (tr.size > 0) tr.transferred.toFloat() / tr.size else 0f, solid(IslandColors.mint))
                            TransferStatus.Done -> Text("Sent", color = IslandColors.mint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            else -> Text(tr.detail ?: "Not sent", color = IslandColors.amber, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = this.drawBehind {
    val w = 1.2.dp.toPx()
    drawRoundRect(
        color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun ShelfCard(service: IslandService, item: ShelfItem, phone: id.phonelink.android.core.HubPhone?) {
    val file = File(item.path)
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(10.dp)).clickable { Platform.open(file) }
            .dragAndDropSource { _ -> DragAndDropTransferData(DragAndDropTransferable(FilesTransferable(listOf(file))), supportedActions = listOf(DragAndDropTransferAction.Copy)) }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (file.isDirectory) DyskIcon(DyskIcons.Folder, Color.White, 20.dp)
            else Text(file.extension.take(4).uppercase().ifEmpty { "FILE" }, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(item.name, color = Color.White, fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phone != null) Text("Send", Modifier.clickable { service.send(listOf(file), phone) }, color = IslandColors.mint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text("Remove", Modifier.clickable { service.removeFromShelf(item) }, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ClipboardPage(service: IslandService, now: Long, scrolls: Boolean) {
    val flow = service.clipHistory ?: return Note("Clipboard History isn't available on this PC.")
    val view by flow.collectAsState()
    if (!view.settings.enabled) return Note("Turn on Clipboard History on the Tools page to see it here.")
    val items = view.entries.take(20)
    if (items.isEmpty()) return Note("Nothing copied yet")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().weight(1f).then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) { items.forEach { ClipCard(service, it, it.id == view.entries.firstOrNull()?.id, now) } }
        Text("${view.entries.size} items · click to copy", color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp)
    }
}

@Composable
private fun ClipCard(service: IslandService, entry: ClipEntry, newest: Boolean, now: Long) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.size(104.dp, 96.dp).clip(shape).background(Color.White.copy(alpha = 0.08f))
            .border(1.5.dp, if (newest) IslandColors.violet else Color.Transparent, shape).clickable { service.copyClip(entry) }.padding(10.dp),
    ) {
        when {
            entry.text != null -> Text(entry.text.trim(), Modifier.weight(1f), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            entry.imageFile != null -> {
                val bitmap = remember(entry.id) { service.clipImageFile(entry)?.takeIf { it.isFile }?.let { decode(entry.id, it.readBytes()) } }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (bitmap != null) Image(bitmap, null, Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    else DyskIcon(DyskIcons.Image, Color.White.copy(alpha = 0.6f), 20.dp)
                }
            }
            else -> Text(entry.files?.joinToString { File(it).name }.orEmpty(), Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.pinned) { DyskIcon(DyskIcons.Pin, Color.White.copy(alpha = 0.45f), 9.dp); Spacer(Modifier.width(4.dp)) }
            Text(IslandText.ago(entry.date, now), color = Color.White.copy(alpha = 0.45f), fontSize = 10.5.sp)
        }
    }
}

// Prayer

@Composable
private fun PrayerPage(service: IslandService, now: Long) {
    val places by service.places.view.collectAsState()
    val schedule = places.schedule ?: return Note("Choose your city in Settings, The island.")
    val instant = Instant.ofEpochMilli(now)
    val next = schedule.upcoming(instant) ?: return Note(places.prayerProblem ?: "Loading prayer times...")
    val zone = schedule.location.zone
    val today = schedule.today(instant).orEmpty()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(190.dp)) {
            Text("Next", color = faint, fontSize = 13.sp)
            Text(next.prayer.label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(clock(next.time.toInstant(), zone), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("in " + IslandText.duration(next.time.toInstant().toEpochMilli() - now), color = IslandColors.skyBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Bar(PrayerRules.progress(next.previous?.toInstant(), next.time.toInstant(), instant), Brush.horizontalGradient(listOf(Color(0xFF4FC3FF), Color(0xFF6E5BFF))), 7f)
        }
        SideDivider()
        PrayerArc(today, next.prayer, instant, zone, Modifier.weight(1f).fillMaxHeight())
    }
}

private fun clock(at: Instant, zone: ZoneId): String = "%02d:%02d".format(at.atZone(zone).hour, at.atZone(zone).minute)

/**
 * The day as an arc with the five times evenly along it, as on the Mac; the coloured part reaches from Subuh to now,
 * between the time before and the next one.
 */
@Composable
private fun PrayerArc(times: List<PrayerTime>, next: id.phonelink.android.core.Prayer, now: Instant, zone: ZoneId, modifier: Modifier) {
    val count = times.size.coerceAtLeast(1)
    val nextIndex = times.indexOfFirst { it.prayer == next }
    val reached: Float = when {
        times.isEmpty() -> 0f
        nextIndex < 0 -> 1f
        nextIndex == 0 -> 0f
        else -> {
            val before = times[nextIndex - 1].time.toInstant()
            (nextIndex - 1 + PrayerRules.progress(before, times[nextIndex].time.toInstant(), now)) / (count - 1).coerceAtLeast(1)
        }
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) {
            // The dots sit over the middle of each label's column.
            val left = size.width / (2 * count)
            val span = size.width - 2 * left
            fun x(t: Float) = left + span * t
            fun y(t: Float) = size.height - sin(PI * t).toFloat() * (size.height - 6.dp.toPx())
            fun arc(upTo: Float): Path = Path().apply {
                for (step in 0..60) {
                    val t = upTo * step / 60f
                    if (step == 0) moveTo(x(t), y(t)) else lineTo(x(t), y(t))
                }
            }
            val w = 3.dp.toPx()
            drawPath(arc(1f), Color.White.copy(alpha = 0.18f), style = Stroke(w, cap = StrokeCap.Round))
            if (reached > 0f) drawPath(arc(reached), Brush.horizontalGradient(listOf(Color(0xFF4FC3FF), Color(0xFF6E5BFF)), x(0f), x(1f)), style = Stroke(w, cap = StrokeCap.Round))
            times.forEachIndexed { i, t ->
                val at = if (count > 1) i / (count - 1f) else 0.5f
                val isNext = t.prayer == next
                drawCircle(if (isNext) Color.White else Color.White.copy(alpha = 0.45f), (if (isNext) 5f else 3.5f).dp.toPx(), Offset(x(at), y(at)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            times.forEach { t ->
                val isNext = t.prayer == next
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.prayer.label, color = Color.White.copy(alpha = if (isNext) 1f else 0.55f), fontSize = 11.sp, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                    Text(clock(t.time.toInstant(), zone), color = Color.White.copy(alpha = if (isNext) 1f else 0.55f), fontSize = 11.sp)
                }
            }
        }
    }
}

// Weather

@Composable
private fun WeatherPage(service: IslandService) {
    val places by service.places.view.collectAsState()
    if (places.weatherPlace == null) return Note("Choose your city in Settings, The island.")
    val report = places.report ?: return Note(places.weatherProblem ?: "Loading the weather...")
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(190.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherGlyph(report.code, report.isDay, 38f)
                Spacer(Modifier.width(10.dp))
                Text(places.degrees(report.temperature), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Text(PrayerWeatherApi.describe(report.code), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            report.days.firstOrNull()?.let { Text("High ${places.degrees(it.high)} · Low ${places.degrees(it.low)}", color = faint, fontSize = 12.sp) }
        }
        SideDivider()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            report.days.take(3).forEachIndexed { i, day -> DayCard(places, day, i == 0, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayCard(places: PlacesView, day: WeatherDay, today: Boolean, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val name = if (today) "Today" else runCatching { LocalDate.parse(day.date).dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.US) }.getOrDefault(day.date)
        Text(name, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        WeatherGlyph(day.code, true, 22f)
        Text("${places.degrees(day.high)} / ${places.degrees(day.low)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(DyskIcons.Drop, IslandColors.skyBlue, 9.dp)
            Spacer(Modifier.width(3.dp))
            Text("${day.rainChance}%", color = IslandColors.skyBlue, fontSize = 11.sp)
        }
    }
}

/** The WMO weather codes as small drawings: sun or moon, cloud, rain, snow, fog, and lightning. */
@Composable
internal fun WeatherGlyph(code: Int, isDay: Boolean, size: Float) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width
        val sun = Color(0xFFFFC94D)
        val cloud = Color(0xFFE8ECF4)
        fun drawSun(cx: Float, cy: Float, r: Float) {
            if (isDay) {
                drawCircle(sun, r, Offset(cx, cy))
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    drawLine(sun, Offset(cx + cos(a).toFloat() * r * 1.35f, cy + sin(a).toFloat() * r * 1.35f), Offset(cx + cos(a).toFloat() * r * 1.75f, cy + sin(a).toFloat() * r * 1.75f), s * 0.05f, StrokeCap.Round)
                }
            } else {
                drawCircle(Color(0xFFDCE3FF), r, Offset(cx, cy))
                drawCircle(Color.Black, r * 0.85f, Offset(cx + r * 0.45f, cy - r * 0.3f))
            }
        }
        fun drawCloud(dy: Float = 0f, color: Color = cloud) {
            drawCircle(color, s * 0.17f, Offset(s * 0.36f, s * 0.55f + dy))
            drawCircle(color, s * 0.22f, Offset(s * 0.55f, s * 0.47f + dy))
            drawCircle(color, s * 0.15f, Offset(s * 0.72f, s * 0.58f + dy))
            drawRoundRect(color, Offset(s * 0.22f, s * 0.55f + dy), Size(s * 0.62f, s * 0.18f), androidx.compose.ui.geometry.CornerRadius(s * 0.09f))
        }
        fun drops(color: Color, snow: Boolean = false) {
            for (i in 0 until 3) {
                val x = s * (0.36f + i * 0.16f)
                if (snow) drawCircle(color, s * 0.045f, Offset(x, s * 0.88f))
                else drawLine(color, Offset(x, s * 0.80f), Offset(x - s * 0.04f, s * 0.94f), s * 0.05f, StrokeCap.Round)
            }
        }
        when (code) {
            0 -> drawSun(s / 2, s / 2, s * 0.22f)
            1, 2 -> { drawSun(s * 0.36f, s * 0.36f, s * 0.15f); drawCloud(s * 0.06f) }
            3 -> drawCloud()
            45, 48 -> { drawCloud(-s * 0.08f); for (i in 0 until 2) drawLine(cloud, Offset(s * 0.2f, s * (0.8f + i * 0.1f)), Offset(s * 0.8f, s * (0.8f + i * 0.1f)), s * 0.05f, StrokeCap.Round) }
            71, 73, 75, 77, 85, 86 -> { drawCloud(-s * 0.1f); drops(Color.White, snow = true) }
            95, 96, 99 -> {
                drawCloud(-s * 0.1f, Color(0xFFB8C2D6))
                val bolt = Path().apply { moveTo(s * 0.55f, s * 0.68f); lineTo(s * 0.44f, s * 0.84f); lineTo(s * 0.53f, s * 0.84f); lineTo(s * 0.46f, s * 0.98f); lineTo(s * 0.62f, s * 0.78f); lineTo(s * 0.53f, s * 0.78f); close() }
                drawPath(bolt, sun)
            }
            in 51..67, in 80..82 -> { drawCloud(-s * 0.1f); drops(Color(0xFF6EA8FF)) }
            else -> drawCloud()
        }
    }
}

// Todo

@Composable
private fun TodoPage(service: IslandService, ui: IslandUiState, now: Long, scrolls: Boolean, onKeyboard: (Boolean) -> Unit) {
    val tools by service.tools.collectAsState()
    val zone = ZoneId.systemDefault()
    val tasks = tools.todos.visible(now, zone)
    val done = tools.todos.doneToday(now, zone)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$done/${tasks.size}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("Tasks done today", color = faint, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(DyskIcons.Plus, "Add", enabled = !service.tools.value.let { it.todos.items.size >= 50 }) { ui.typing = ""; ui.draft = ""; onKeyboard(true) }
                Pill(if (ui.editingTodos) DyskIcons.Check else DyskIcons.Pencil, if (ui.editingTodos) "Done" else "Edit", enabled = tasks.isNotEmpty()) { ui.editingTodos = !ui.editingTodos }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ui.typing?.let { typing -> TaskField(service, ui, typing, onKeyboard) }
            if (tasks.isEmpty() && ui.typing == null) {
                Empty(DyskIcons.Checklist, "No tasks yet", "Press Add to write one")
            } else {
                Column(Modifier.fillMaxSize().then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tasks.forEach { TaskRow(service, ui, it, onKeyboard) }
                }
            }
        }
    }
}

/** Typing a task: the island takes the keyboard only for this, then gives it back. */
@Composable
private fun TaskField(service: IslandService, ui: IslandUiState, typing: String, onKeyboard: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(typing) { runCatching { focus.requestFocus() } }
    fun finish() {
        val text = ui.draft
        if (typing.isEmpty()) service.tools { addTodo(text) } else service.tools { renameTodo(typing, text) }
        ui.typing = null
        ui.draft = ""
        onKeyboard(false)
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            ui.draft, { ui.draft = it.take(80) },
            Modifier.weight(1f).focusRequester(focus).onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { finish(); true } else false
            },
            singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp), cursorBrush = SolidColor(Color.White),
            decorationBox = { inner -> Box { if (ui.draft.isEmpty()) Text(if (typing.isEmpty()) "New task" else "New title", color = Color.White.copy(alpha = 0.4f), fontSize = 13.5.sp); inner() } },
        )
        Text("Save", Modifier.clickable { finish() }.padding(start = 8.dp), color = IslandColors.todoGreen, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TaskRow(service: IslandService, ui: IslandUiState, task: TodoItem, onKeyboard: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (task.isDone) Color(0xFF1B2E1F) else Color.White.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(if (task.isDone) IslandColors.todoGreen else Color.Transparent)
                .border(1.5.dp, if (task.isDone) IslandColors.todoGreen else Color.White.copy(alpha = 0.6f), CircleShape).clickable { service.tools { toggleTodo(task.id) } },
            contentAlignment = Alignment.Center,
        ) { if (task.isDone) DyskIcon(DyskIcons.Check, Color.Black, 11.dp) }
        Spacer(Modifier.width(10.dp))
        Text(
            task.title, Modifier.weight(1f), color = if (task.isDone) Color.White.copy(alpha = 0.6f) else Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
        )
        if (ui.editingTodos) {
            Box(Modifier.size(22.dp).clip(CircleShape).clickable { ui.typing = task.id; ui.draft = task.title; onKeyboard(true) }, contentAlignment = Alignment.Center) {
                DyskIcon(DyskIcons.Pencil, Color.White.copy(alpha = 0.75f), 13.dp)
            }
            Box(Modifier.size(22.dp).clip(CircleShape).clickable { service.tools { deleteTodo(task.id) } }, contentAlignment = Alignment.Center) {
                DyskIcon(DyskIcons.Close, Color(0xFFFF6F7D), 12.dp)
            }
        }
    }
}

// Water

@Composable
private fun WaterPage(service: IslandService, ui: IslandUiState, now: Long) {
    val tools by service.tools.collectAsState()
    val water = tools.water
    val zone = ZoneId.systemDefault()
    val total = water.totalToday(now, zone)
    val goal = water.settings.goalMl
    val fraction = water.fraction(now, zone)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(262.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(litres(total), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("/ ${litres(goal)} L", Modifier.padding(bottom = 7.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.padding(bottom = 6.dp).size(30.dp, 26.dp).clip(RoundedCornerShape(50)).background(if (water.settings.reminds) IslandColors.blue else Color.White.copy(alpha = 0.14f))
                        .clickable { service.tools { setWater(water.settings.copy(reminds = !water.settings.reminds)) } },
                    contentAlignment = Alignment.Center,
                ) { DyskIcon(DyskIcons.Bell, Color.White.copy(alpha = if (water.settings.reminds) 1f else 0.6f), 12.dp) }
            }
            Row {
                Text("Today · ", color = faint, fontSize = 12.5.sp)
                Text("${Math.round(fraction * 100)}%", color = IslandColors.blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                if (total >= goal) Text(" · Goal reached", color = faint, fontSize = 12.5.sp)
            }
            Bar(fraction, Brush.horizontalGradient(listOf(Color(0xFF6FD0FF), Color(0xFF4A63F5))), 6f)
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(DyskIcons.Drop, "Drink", Color(0xFF214F8F)) { service.tools { drink() } }
                Pill(DyskIcons.Sliders, if (ui.waterSettings) "Chart" else "Settings") { ui.waterSettings = !ui.waterSettings }
                if (total > 0) RoundButton(DyskIcons.Undo) { service.tools { undoDrink() } }
            }
        }
        SideDivider()
        if (ui.waterSettings) WaterSettingsColumn(service, water.settings, Modifier.weight(1f)) else HourChart(water, now, zone, Modifier.weight(1f))
    }
}

private fun litres(ml: Int): String = if (ml % 1000 == 0) "${ml / 1000}" else "%.1f".format(Locale.US, ml / 1000.0).removeSuffix("0").removeSuffix(".")

@Composable
private fun HourChart(water: WaterLog, now: Long, zone: ZoneId, modifier: Modifier) {
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour
    val first = (hour - 7).coerceAtLeast(0)
    val hours = (first..first + 7).toList()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val drunk = water.entries.map { Instant.ofEpochMilli(it.at).atZone(zone) }.zip(water.entries).filter { it.first.toLocalDate() == today }
        .groupBy({ it.first.hour }, { it.second.ml }).mapValues { it.value.sum() }
    val top = maxOf(400, ((drunk.values.maxOrNull() ?: 0) + 99) / 100 * 100)
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            hours.forEach { slot ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(12.dp, 64.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.BottomCenter) {
                        drunk[slot]?.takeIf { it > 0 }?.let { ml ->
                            Box(Modifier.size(12.dp, (64f * ml / top).coerceAtLeast(6f).dp).clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(Color(0xFF6FB6FF), Color(0xFF2E5FC8)))))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (slot % 3 == 0) "%02d".format(slot) else " ", Modifier.height(14.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
                }
            }
        }
        // Level with the top and the bottom of the bars, above the hours.
        Column(Modifier.width(38.dp).height(64.dp + 4.dp + 14.dp), horizontalAlignment = Alignment.End) {
            Text("$top", Modifier.height(12.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, lineHeight = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("0 ml", Modifier.height(12.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, lineHeight = 12.sp)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun WaterSettingsColumn(service: IslandService, settings: WaterSettings, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WaterStepper("Goal", "${litres(settings.goalMl)} L", WaterSettings.GOAL_CHOICES, settings.goalMl) { service.tools { setWater(settings.copy(goalMl = it)) } }
        WaterStepper("Glass", "${settings.glassMl} ml", WaterSettings.GLASS_CHOICES, settings.glassMl) { service.tools { setWater(settings.copy(glassMl = it)) } }
        WaterStepper("Every", "${settings.remindEveryMinutes} min", WaterSettings.INTERVAL_CHOICES, settings.remindEveryMinutes) { service.tools { setWater(settings.copy(remindEveryMinutes = it)) } }
    }
}

@Composable
private fun WaterStepper(title: String, value: String, choices: List<Int>, current: Int, onChange: (Int) -> Unit) {
    val index = choices.indexOf(current)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        RoundButton(DyskIcons.ArrowDown, 24f, enabled = index > 0) { choices.getOrNull(index - 1)?.let(onChange) }
        Text(value, Modifier.width(62.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        RoundButton(DyskIcons.ArrowUp, 24f, enabled = index in 0 until choices.lastIndex) { choices.getOrNull(index + 1)?.let(onChange) }
    }
}

// Notifications

@Composable
private fun NotificationsPage(service: IslandService, now: Long, scrolls: Boolean) {
    val alerts by service.alerts.collectAsState()
    if (alerts.isEmpty()) return Empty(DyskIcons.Bell, "No phone notifications", "Turn on Notifications in DyskLink on the phone to see them here")
    Column(Modifier.fillMaxSize().then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        alerts.forEach { alert ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NotificationIconBox(alert, 34f)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(alert.item.app, color = faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(" · ", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
                        Text(IslandText.age(alert.item.time, now), color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                    }
                    Text(alert.item.title ?: alert.item.app, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    alert.item.text?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                picture(alert)?.let { Image(it, null, Modifier.padding(horizontal = 8.dp).size(42.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                CloseDot { service.dismiss(alert) }
            }
        }
    }
}
