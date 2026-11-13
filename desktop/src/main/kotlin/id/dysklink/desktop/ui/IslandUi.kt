@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package id.dysklink.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.CodeNotice
import id.dysklink.desktop.GlanceLeft
import id.dysklink.desktop.GlanceTone
import id.dysklink.desktop.IslandArt
import id.dysklink.desktop.IslandFace
import id.dysklink.desktop.IslandGlance
import id.dysklink.desktop.IslandPage
import id.dysklink.desktop.IslandSection
import id.dysklink.desktop.IslandService
import id.dysklink.desktop.IslandShapeSpec
import id.dysklink.desktop.OneTimeCode
import id.dysklink.desktop.PhoneAlert
import id.dysklink.desktop.ShelfMode
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/** What the island shows that isn't data: open or not, the page, and what's being typed. Kept by its window. */
class IslandUiState {
    var open by mutableStateOf(false)
    var page by mutableStateOf(IslandPage.Music)
    var shelfMode by mutableStateOf(ShelfMode.Files)
    /** Files are being dragged over the send-to-phone area, or over the shelf. */
    var phoneTargeted by mutableStateOf(false)
    var shelfTargeted by mutableStateOf(false)
    var editingTodos by mutableStateOf(false)
    /** "" while a new task is typed, a task's ID while it's renamed, null otherwise. */
    var typing by mutableStateOf<String?>(null)
    var draft by mutableStateOf("")
    var waterSettings by mutableStateOf(false)
    /** Where the pointer is from the island, -1..1 each way, for the eyes to glance at; zero when it's far. */
    var glance by mutableStateOf(Offset.Zero)
}

/** Colours of the island, which is always dark, like the Mac notch. */
internal object IslandColors {
    val body = Color.Black
    val resting = Color(0xFF05060A)
    val green = Color(0xFF34C759)
    val blue = Color(0xFF4FA3FF)
    val todoGreen = Color(0xFF62D17A)
    val waterTitle = Color(0xFF6EB6FF)
    val skyBlue = Color(0xFF6EA8FF)
    val violet = DarkColors.violet
    val mint = DarkColors.mint
    val amber = DarkColors.amber
    val coral = DarkColors.coral
    val glow = listOf(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF8B5CF6))

    fun tone(tone: GlanceTone) = when (tone) {
        GlanceTone.Green -> green
        GlanceTone.Amber -> amber
        GlanceTone.Blue -> blue
        GlanceTone.Violet -> violet
        GlanceTone.Mint -> mint
    }
}

/**
 * The notch silhouette: flat along the top edge, concave shoulders where it meets the edge, rounded bottom corners.
 * Drawn centred in whatever box it's given, with [width] the body between the shoulders, as the Mac's NotchShape.
 */
class IslandShape(private val width: Float, private val height: Float, private val bottomRadius: Float, private val shoulder: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val w = width * d
        val h = minOf(height * d, size.height)
        val s = shoulder * d
        val left = size.width / 2 - w / 2
        val right = size.width / 2 + w / 2
        val top = minOf(s, h / 2)
        val r = minOf(bottomRadius * d, h / 2, w / 2)
        val path = Path().apply {
            moveTo(left - s, 0f)
            lineTo(right + s, 0f)
            quadraticTo(right, 0f, right, top)
            lineTo(right, h - r)
            quadraticTo(right, h, right - r, h)
            lineTo(left + r, h)
            quadraticTo(left, h, left, h - r)
            lineTo(left, top)
            quadraticTo(left, 0f, left - s, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

private val Oval = GenericShape { size, _ -> addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }

/** The shape the island has now. */
fun islandShapeFor(open: Boolean, face: IslandFace): IslandShapeSpec = when {
    open -> IslandShapeSpec.Open
    face is IslandFace.Resting -> IslandShapeSpec.Resting
    face is IslandFace.Live -> IslandShapeSpec.Live
    else -> IslandShapeSpec.Banner
}

/** A curve without overshoot, so the shape never bounces past the screen edge (the Mac's). */
private val IslandEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

/**
 * Everything the island window draws. [clock] is fixed in snapshots; [reducedMotion] stops the blink, the glance, the
 * bars, and the shape's animation. The callbacks go to the window: hover, a click while closed, and when typing
 * needs the keyboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IslandContent(
    service: IslandService,
    ui: IslandUiState,
    clock: () -> Long = System::currentTimeMillis,
    reducedMotion: Boolean = false,
    scrolls: Boolean = true,
    onHover: (Boolean) -> Unit = {},
    onClosedClick: () -> Unit = {},
    onKeyboard: (Boolean) -> Unit = {},
    onFilesOver: () -> Unit = {},
    onShape: (IslandShapeSpec) -> Unit = {},
) {
    val tools by service.tools.collectAsState()
    val track by service.track.collectAsState()
    val art by service.art.collectAsState()
    val banners by service.banners.collectAsState()
    var now by remember { mutableLongStateOf(clock()) }
    val ticking = ui.open || tools.pomodoro.state.isRunning || track?.isPlaying == true || banners.peek != null
    LaunchedEffect(ticking) {
        while (true) {
            now = clock()
            delay(if (ticking) 1_000 else 15_000)
        }
    }
    val face = IslandGlance.face(
        now, ZoneId.systemDefault(), banners.code, banners.notice, banners.peek,
        tools.pomodoro.state, tools.pomodoro.settings, track, hasArt = art?.key == track?.key && art != null,
    )
    val target = islandShapeFor(ui.open, face)
    androidx.compose.runtime.SideEffect { onShape(target) }
    val spec = { open: Boolean -> if (reducedMotion) snap<Float>() else tween<Float>(if (open) 320 else 240, easing = IslandEasing) }
    val width by animateFloatAsState(target.width, spec(ui.open))
    val height by animateFloatAsState(target.height, spec(ui.open))
    val radius by animateFloatAsState(target.bottomRadius, spec(ui.open))
    val restingAlpha by animateFloatAsState(if (!ui.open && face is IslandFace.Resting) 1f else 0f, if (reducedMotion) snap() else tween(260))
    val openAlpha by animateFloatAsState(if (ui.open) 1f else 0f, if (reducedMotion) snap() else tween(if (ui.open) 260 else 120))
    val shoulder = target.shoulder
    val shape = IslandShape(width, height, radius, shoulder)
    val drop = remember(service) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { if (!ui.open) onFilesOver() }
            override fun onDrop(event: DragAndDropEvent): Boolean = false
        }
    }

    DyskTheme(dark = true) {
        Box(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        if (ui.typing != null) { ui.typing = null; onKeyboard(false) } else ui.open = false
                        true
                    } else false
                }
                .dragAndDropTarget(shouldStartDragAndDrop = { e -> e.hasFiles() }, target = drop),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier.size((width + 2 * shoulder).dp, height.dp)
                    .clip(shape)
                    .background(IslandColors.body)
                    .onPointerEvent(PointerEventType.Enter) { onHover(true) }
                    .onPointerEvent(PointerEventType.Exit) { onHover(false) }
                    .then(if (!ui.open) Modifier.clickable(remember { MutableInteractionSource() }, null) { onClosedClick() } else Modifier),
            ) {
                if (restingAlpha > 0f) RestingGlow(restingAlpha)
                Box(Modifier.fillMaxSize().padding(horizontal = shoulder.dp)) {
                    if (!ui.open) ClosedFace(face, art, service, reducedMotion, ui, Modifier.graphicsLayer(alpha = 1f - openAlpha))
                    if (openAlpha > 0f) {
                        Box(Modifier.size(IslandShapeSpec.Open.width.dp, IslandShapeSpec.Open.height.dp).align(Alignment.TopCenter).graphicsLayer(alpha = openAlpha)) {
                            OpenIsland(service, ui, now, reducedMotion, scrolls, onKeyboard)
                        }
                    }
                }
            }
        }
    }
}

private fun DragAndDropEvent.hasFiles(): Boolean =
    runCatching { awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)

/** The files in a drop, or none. */
internal fun DragAndDropEvent.files(): List<File> = runCatching {
    @Suppress("UNCHECKED_CAST")
    (awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
}.getOrDefault(emptyList())

/** The soft glow along the bottom of the resting island: cyan on the left into violet on the right. */
@Composable
private fun BoxScope.RestingGlow(alpha: Float) {
    Box(
        Modifier.matchParentSize().graphicsLayer(alpha = alpha).drawBehind {
            drawRect(IslandColors.resting)
            drawRect(Brush.horizontalGradient(IslandColors.glow), alpha = 0.95f)
            // The top stays near black; the colour rises from the bottom edge.
            drawRect(Brush.verticalGradient(0f to IslandColors.resting, 0.45f to IslandColors.resting.copy(alpha = 0.92f), 1f to Color.Transparent))
        },
    )
}

@Composable
private fun ClosedFace(face: IslandFace, art: IslandArt?, service: IslandService, reducedMotion: Boolean, ui: IslandUiState, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (face) {
            IslandFace.Resting -> Eyes(ui.glance, reducedMotion)
            is IslandFace.Live -> LiveRow(face, art, reducedMotion)
            is IslandFace.Code -> CodeBanner(face.notice, copy = service::copyCode, close = { service.hideCode() })
            is IslandFace.Notice -> NoticeBanner(face.alert)
        }
    }
}

/** Two white eyes that blink now and then and glance toward the pointer when it comes near. */
@Composable
fun Eyes(glance: Offset, reducedMotion: Boolean) {
    val blink = remember { Animatable(1f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) { blink.snapTo(1f); return@LaunchedEffect }
        while (true) {
            delay(Random.nextLong(3_200, 6_800))
            blink.animateTo(0.12f, tween(70))
            blink.animateTo(1f, tween(120))
        }
    }
    val gx by animateFloatAsState(if (reducedMotion) 0f else glance.x * 3.5f, tween(260))
    val gy by animateFloatAsState(if (reducedMotion) 0f else glance.y * 2.5f, tween(260))
    Row(Modifier.offset(gx.dp, gy.dp), horizontalArrangement = Arrangement.spacedBy(17.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(2) {
            Box(Modifier.size(10.dp, 16.dp).graphicsLayer(scaleY = blink.value).clip(Oval).background(Color.White))
        }
    }
}

/** Ring or album art, a big text, and the music bars, in one row. */
@Composable
private fun LiveRow(face: IslandFace.Live, art: IslandArt?, reducedMotion: Boolean) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            when (val left = face.left) {
                is GlanceLeft.Ring -> Ring(left.fraction, IslandColors.tone(left.tone), 24.dp.value)
                is GlanceLeft.Art -> ArtImage(art, 24f, round = true)
            }
        }
        Text(
            face.center, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White,
            fontSize = if (face.small) 13.sp else 22.sp, fontWeight = if (face.small) FontWeight.SemiBold else FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        AudioBars(face.playing, !reducedMotion, height = 18f)
    }
}

/** A small ring that fills clockwise from the top. */
@Composable
internal fun Ring(fraction: Float, color: Color, size: Float, stroke: Float = 3.5f) {
    Canvas(Modifier.size(size.dp)) {
        val w = stroke.dp.toPx()
        val inset = w / 2
        val arcSize = Size(this.size.width - w, this.size.height - w)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Bars that move while music plays and rest otherwise, as the Mac's AudioBars. */
@Composable
internal fun AudioBars(playing: Boolean, animate: Boolean, height: Float = 16f, color: Color = Color.White) {
    var time by remember { mutableStateOf(0.0) }
    LaunchedEffect(playing, animate) {
        if (!playing || !animate) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { frame -> time = (frame - start) / 1e9 }
            delay(50)
        }
    }
    val resting = listOf(5f, 9f, 13f, 9f, 5f)
    Row(Modifier.size(28.dp, height.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val h = if (playing && animate) 4f + 10f * abs(sin(time * 5 + i * 1.3)).toFloat() else if (playing) listOf(8f, 12f, 16f, 11f, 7f)[i] else resting[i]
            Box(Modifier.size(2.5.dp, (h * height / 16f).dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}

private val artCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 8
})

internal fun decode(key: String, bytes: ByteArray): ImageBitmap? = artCache.getOrPut(key) {
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

/** Album art or a video thumbnail when there is one, otherwise a stand-in. */
@Composable
internal fun ArtImage(art: IslandArt?, size: Float, round: Boolean = false) {
    val bitmap = remember(art?.key, art?.bytes?.size) { art?.let { decode(it.key + ":" + it.bytes.size, it.bytes) } }
    val shape = if (round) CircleShape else RoundedCornerShape((size * 0.2f).dp)
    Box(Modifier.size(size.dp).clip(shape).background(Brush.linearGradient(listOf(IslandColors.violet, IslandColors.mint.copy(alpha = 0.8f)))), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DyskIcon(DyskIcons.Waves, Color.White, (size * 0.42f).dp)
    }
}

/** A one-time code from the phone. Click Copy, then paste it where it's asked. Not kept in the clipboard history. */
@Composable
internal fun CodeBanner(notice: CodeNotice, copy: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF5CDBB0)), contentAlignment = Alignment.Center) {
            KeyGlyph(Color.Black, 20f)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Code from ${notice.source} · ${notice.phone}", color = Color.White.copy(alpha = 0.5f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(OneTimeCode.spaced(notice.code), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
        }
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (notice.copied) 0.75f else 0.95f))
                .clickable(onClick = copy).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DyskIcon(if (notice.copied) DyskIcons.Check else DyskIcons.Copy, Color.Black, 13.dp)
            Spacer(Modifier.width(5.dp))
            Text(if (notice.copied) "Copied" else "Copy", color = Color.Black, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        CloseDot(close)
    }
}

/** A key, for codes: a round bow and a shaft with two teeth. */
@Composable
internal fun KeyGlyph(color: Color, size: Float) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width
        val w = s * 0.13f
        drawCircle(color, s * 0.22f, Offset(s * 0.5f, s * 0.27f))
        drawCircle(Color(0xFF5CDBB0), s * 0.07f, Offset(s * 0.5f, s * 0.24f))
        drawLine(color, Offset(s * 0.5f, s * 0.4f), Offset(s * 0.5f, s * 0.92f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.5f, s * 0.66f), Offset(s * 0.66f, s * 0.66f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.5f, s * 0.82f), Offset(s * 0.62f, s * 0.82f), w, StrokeCap.Round)
    }
}

@Composable
internal fun CloseDot(onClick: () -> Unit) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        DyskIcon(DyskIcons.Close, Color.White.copy(alpha = 0.6f), 10.dp)
    }
}

/** A new phone notification under the closed island: who it's from, what it says, and its picture. */
@Composable
internal fun NoticeBanner(alert: PhoneAlert) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        NotificationIconBox(alert, 40f)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("${alert.item.app} · ${alert.phone}", color = Color.White.copy(alpha = 0.5f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(alert.item.title ?: alert.item.app, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            alert.item.text?.takeIf { it.isNotEmpty() }?.let {
                Text(it.replace('\n', ' '), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        picture(alert)?.let { Image(it, null, Modifier.padding(start = 8.dp).size(46.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
    }
}

private val noticeImages = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap?>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 64
})

private fun base64Image(key: String, data: String?): ImageBitmap? {
    data ?: return null
    return noticeImages.getOrPut("$key:${data.length}") {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(java.util.Base64.getDecoder().decode(data)).toComposeImageBitmap() }.getOrNull()
    }
}

internal fun picture(alert: PhoneAlert): ImageBitmap? = base64Image(alert.id + ":p", alert.item.picture)

@Composable
internal fun NotificationIconBox(alert: PhoneAlert, size: Float) {
    val icon = base64Image(alert.id + ":a", alert.item.avatar) ?: base64Image(alert.id + ":i", alert.item.icon)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.26f).dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(alert.item.app.take(1).uppercase(), color = Color.White, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

// Open

private val dateFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", java.util.Locale.US)
private val timeFormat = DateTimeFormatter.ofPattern("H:mm")
private const val SIDE_ROOM = 44

@Composable
private fun OpenIsland(service: IslandService, ui: IslandUiState, now: Long, reducedMotion: Boolean, scrolls: Boolean, onKeyboard: (Boolean) -> Unit) {
    val settings by service.settings.collectAsState()
    val places by service.places.view.collectAsState()
    val phones by service.phones.collectAsState()
    val pages = service.pages(settings)
    val list = pages.pages
    // A page or shelf mode that was just hidden moves on to one that shows.
    LaunchedEffect(list, pages.hasShelfSwitch, places.location, places.weatherPlace, phones) {
        val (page, mode) = pages.settle(ui.page, ui.shelfMode)
        ui.page = page
        ui.shelfMode = mode
    }
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
        Header(service, ui, now, reducedMotion, pages.hasShelfSwitch, pages.isShown(IslandSection.Music))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().padding(horizontal = SIDE_ROOM.dp)) {
                IslandPageContent(service, ui, now, reducedMotion, scrolls, onKeyboard)
            }
            if (list.size > 1) {
                Arrow(left = true, Modifier.align(Alignment.CenterStart)) { turn(ui, list, -1) }
                Arrow(left = false, Modifier.align(Alignment.CenterEnd)) { turn(ui, list, 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (list.size > 1) Dots(list, ui.page) { ui.page = it }
    }
}

private fun turn(ui: IslandUiState, list: List<IslandPage>, step: Int) {
    val index = list.indexOf(ui.page).coerceAtLeast(0)
    ui.page = list[(index + step + list.size) % list.size]
    ui.typing = null
}

@Composable
private fun Header(service: IslandService, ui: IslandUiState, now: Long, reducedMotion: Boolean, shelfSwitch: Boolean, musicShown: Boolean) {
    val places by service.places.view.collectAsState()
    val phone by service.phone.collectAsState()
    val track by service.track.collectAsState()
    val pcBattery by (service.pcBattery ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = (SIDE_ROOM - 12).dp), verticalAlignment = Alignment.CenterVertically) {
        // As on the Mac: the page's name and place, then the date, time and batteries, all from the left.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = if (ui.page == IslandPage.Shelf && !service.pages().isShown(IslandSection.Files)) "Clipboard" else ui.page.title
            when (ui.page) {
                IslandPage.Todo -> { Text("Todo ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("list", color = IslandColors.todoGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                IslandPage.Water -> Text("Water", color = IslandColors.waterTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                else -> if (name.isNotEmpty()) Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            val place = when (ui.page) {
                IslandPage.Prayer -> places.location?.label
                IslandPage.Weather -> places.weatherPlace?.shortName
                else -> null
            }
            place?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, Modifier.widthIn(max = 80.dp), color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (ui.page != IslandPage.Music) Spacer(Modifier.width(14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pcBattery?.let { (percent, charging) -> Ring(percent / 100f, if (charging || percent > 20) IslandColors.green else IslandColors.amber, 15f, 3f) }
            Text(dateFormat.format(time), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(timeFormat.format(time), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            phone?.info?.batteryPercent?.let { percent ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DyskIcon(if (phone?.info?.isCharging == true) DyskIcons.Bolt else DyskIcons.Phone, Color.White.copy(alpha = 0.7f), 10.dp)
                    Spacer(Modifier.width(2.dp))
                    Text("$percent%", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (ui.page == IslandPage.Shelf) {
                if (shelfSwitch) ShelfSwitch(ui)
            } else if (musicShown) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { ui.page = IslandPage.Music }.padding(2.dp)) {
                    AudioBars(track?.isPlaying == true, !reducedMotion)
                }
            }
        }
    }
}

@Composable
private fun ShelfSwitch(ui: IslandUiState) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((mode, icon) in listOf(ShelfMode.Files to DyskIcons.Folder, ShelfMode.Clipboard to DyskIcons.Clipboard)) {
            Box(
                Modifier.size(28.dp, 20.dp).clip(RoundedCornerShape(50)).background(if (ui.shelfMode == mode) IslandColors.violet else Color.Transparent)
                    .clickable { ui.shelfMode = mode },
                contentAlignment = Alignment.Center,
            ) { DyskIcon(icon, Color.White, 12.dp) }
        }
    }
}

@Composable
private fun Arrow(left: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        DyskIcon(DyskIcons.ChevronRight, Color.White, 13.dp, Modifier.graphicsLayer(scaleX = if (left) -1f else 1f))
    }
}

@Composable
private fun Dots(list: List<IslandPage>, current: IslandPage, onPick: (IslandPage) -> Unit) {
    Row(Modifier.fillMaxWidth().height(13.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        list.forEach { page ->
            val isCurrent = page == current
            Box(
                Modifier.padding(horizontal = 2.5.dp).size(if (isCurrent) 16.dp else 5.dp, 5.dp).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = if (isCurrent) 1f else 0.35f)).clickable { onPick(page) },
            )
        }
    }
}
