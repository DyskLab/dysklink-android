package id.phonelink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's colours for one theme. Same values as the desktop app's DarkColors and LightColors. */
@Immutable
data class DyskColors(
    val isDark: Boolean,
    /** The page background. */
    val ink: Color,
    val panel: Color,
    val panelRaised: Color,
    val stroke: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val mint: Color,
    val sky: Color,
    val violet: Color,
    val amber: Color,
    val coral: Color,
    /** Text in an accent: the bright accent on dark, a deeper one on light that reads at 4.5:1 or more on every panel. */
    val mintText: Color,
    val skyText: Color,
    val violetText: Color,
    val amberText: Color,
    val coralText: Color,
    /** Text and icons on a solid mint, amber, or coral fill, or on the accent gradient. */
    val onAccent: Color,
    /** A faint fill behind small icons. */
    val iconWell: Color,
    /** The colour faint tints are made from: white on dark, near-black on light. Use with a low alpha. */
    val contrast: Color,
) {
    /** The text-safe version of an accent, for views that take one colour for both a dot and a label. */
    fun text(color: Color): Color = when (color) {
        mint -> mintText
        sky -> skyText
        violet -> violetText
        amber -> amberText
        coral -> coralText
        else -> color
    }
}

val DarkColors = DyskColors(
    isDark = true,
    ink = Color(0xFF090D13),
    panel = Color(0xFF121822),
    panelRaised = Color(0xFF1A2230),
    stroke = Color(0x14FFFFFF),
    textPrimary = Color(0xFFF2F5F9),
    textSecondary = Color(0xFF98A3B3),
    textFaint = Color(0xFF667081),
    mint = Color(0xFF5EF2C2),
    sky = Color(0xFF4CC9F0),
    violet = Color(0xFF8B7BFF),
    amber = Color(0xFFFFC35C),
    coral = Color(0xFFFF6F7D),
    mintText = Color(0xFF5EF2C2),
    skyText = Color(0xFF4CC9F0),
    violetText = Color(0xFF8B7BFF),
    amberText = Color(0xFFFFC35C),
    coralText = Color(0xFFFF6F7D),
    onAccent = Color(0xFF090D13),
    iconWell = Color(0x0FFFFFFF),
    contrast = Color.White,
)

/**
 * The light theme. The bright accents are for fills, rings, bars, icons, and large titles (3:1 or more on the
 * panel); text uses the *Text accents, which read at 4.5:1 or more on the panel, raised panel, background, and
 * sidebar. onAccent is the dark ink, since white on the light accents is only about 3:1. Same values as the Mac.
 */
val LightColors = DyskColors(
    isDark = false,
    ink = Color(0xFFF3F5F8),
    panel = Color(0xFFFFFFFF),
    panelRaised = Color(0xFFE6EAF0),
    stroke = Color(0x1A0B1220),
    textPrimary = Color(0xFF111723),
    textSecondary = Color(0xFF4F5B6C),
    textFaint = Color(0xFF5F6A7B),
    mint = Color(0xFF0FA67A),
    sky = Color(0xFF1A8FC2),
    violet = Color(0xFF6A5AE0),
    amber = Color(0xFFC07F00),
    coral = Color(0xFFD9434F),
    mintText = Color(0xFF057553),
    skyText = Color(0xFF0B6A99),
    violetText = Color(0xFF5847D6),
    amberText = Color(0xFF8A5A00),
    coralText = Color(0xFFB42333),
    onAccent = Color(0xFF090D13),
    iconWell = Color(0x0D0B1220),
    contrast = Color(0xFF0B1220),
)

val LocalDyskColors = staticCompositionLocalOf { DarkColors }

/**
 * The current theme's colours. Each one reads [LocalDyskColors], so it can only be read in a composable;
 * in a Canvas, remember block, or click handler, read it into a val first.
 */
object Palette {
    val Logo = Color(0xFF14B8A6)

    val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalDyskColors.current.isDark
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.ink
    val Panel: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.panel
    val PanelRaised: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.panelRaised
    val Stroke: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.stroke
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.textSecondary
    val TextFaint: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.textFaint
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.mint
    val Sky: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.sky
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.violet
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.amber
    val Coral: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.coral
    /** Accents for text: the same as above on dark, deeper on light so they read at 4.5:1 or more. */
    val MintText: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.mintText
    val SkyText: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.skyText
    val VioletText: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.violetText
    val AmberText: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.amberText
    val CoralText: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.coralText
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.onAccent
    val IconWell: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.iconWell
    /** White on dark, near-black on light: for faint fills, tracks, and rings, always with a low alpha. */
    val Contrast: Color @Composable @ReadOnlyComposable get() = LocalDyskColors.current.contrast

    val Accent: Brush @Composable @ReadOnlyComposable get() = LocalDyskColors.current.let { Brush.linearGradient(listOf(it.mint, it.sky, it.violet)) }

    /** The text-safe version of an accent; any other colour comes back as it is. */
    @Composable
    @ReadOnlyComposable
    fun text(color: Color): Color = LocalDyskColors.current.text(color)

    @Composable
    @ReadOnlyComposable
    fun battery(percent: Int): Color = when {
        percent <= 15 -> Coral
        percent <= 35 -> Amber
        else -> Mint
    }
}

enum class Appearance(val label: String) { System("System"), Dark("Dark"), Light("Light") }

/** The Appearance setting. Dark by default; read at the root, so a change applies at once. */
object AppearanceStore {
    private const val PREFS = "appearance"
    private const val KEY = "mode"
    private var loaded = false
    private val state = mutableStateOf(Appearance.Dark)

    fun mode(context: Context): Appearance {
        if (!loaded) {
            loaded = true
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            state.value = Appearance.entries.firstOrNull { it.name == saved } ?: Appearance.Dark
        }
        return state.value
    }

    fun set(context: Context, mode: Appearance) {
        loaded = true
        state.value = mode
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        applyNightMode(context)
    }

    /**
     * Tells Android 12 and later which night mode the app uses, so the launch screen and window background
     * (res/values-v31 and values-night-v31) match the setting before the first frame. Android remembers it
     * across launches. Older versions get the right window colour from [windowBackground] in onCreate.
     */
    fun applyNightMode(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val night = when (mode(context)) {
            Appearance.Dark -> UiModeManager.MODE_NIGHT_YES
            Appearance.Light -> UiModeManager.MODE_NIGHT_NO
            Appearance.System -> UiModeManager.MODE_NIGHT_AUTO
        }
        runCatching { context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(night) }
    }

    /** The window colour for the saved setting, before Compose runs. */
    fun windowBackground(context: Context): Int {
        val dark = when (mode(context)) {
            Appearance.Dark -> true
            Appearance.Light -> false
            Appearance.System -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        return (if (dark) DarkColors else LightColors).ink.toArgb()
    }
}

/** Screens around live video (screen mirroring, cameras) count up while shown, so the system bars stay light-on-dark. */
private val darkBarRequests = mutableIntStateOf(0)

/** Keeps the status and navigation bar icons light while this is in the composition. */
@Composable
fun DarkSystemBars() {
    DisposableEffect(Unit) {
        darkBarRequests.intValue++
        onDispose { darkBarRequests.intValue-- }
    }
}

/** Shows [content] in the dark theme whatever the setting, for screens around live video. */
@Composable
fun ForceDark(content: @Composable () -> Unit) {
    DarkSystemBars()
    ProvideColors(DarkColors, content)
}

@Composable
private fun ProvideColors(colors: DyskColors, content: @Composable () -> Unit) {
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.mint,
            onPrimary = colors.onAccent,
            background = colors.ink,
            surface = colors.panel,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            error = colors.coral,
        )
    } else {
        lightColorScheme(
            primary = colors.mint,
            // Only Material's own controls read this (our buttons use OnAccent): a white switch knob and check
            // mark on mint, as on the Mac and desktop, instead of a near-black knob.
            onPrimary = Color.White,
            primaryContainer = colors.mint.copy(alpha = 0.16f),
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.sky,
            onSecondary = colors.onAccent,
            // Progress and slider tracks use these; Material's default is a lavender that matches nothing here.
            secondaryContainer = colors.panelRaised,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.violet,
            onTertiary = Color.White,
            background = colors.ink,
            onBackground = colors.textPrimary,
            surface = colors.panel,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.panelRaised,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerLowest = colors.panel,
            surfaceContainerLow = colors.panel,
            surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panel,
            surfaceContainerHighest = colors.panelRaised,
            outline = colors.textFaint,
            outlineVariant = Color(0xFFD5DBE3),
            error = colors.coral,
            onError = colors.onAccent,
        )
    }
    CompositionLocalProvider(LocalDyskColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Whether the current Appearance setting means dark, following the system for System. */
@Composable
fun isAppDark(): Boolean {
    val context = LocalContext.current
    return when (AppearanceStore.mode(context)) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.System -> isSystemInDarkTheme()
    }
}

@Composable
fun PhoneLinkTheme(content: @Composable () -> Unit) {
    val dark = isAppDark()
    val barsDark = dark || darkBarRequests.intValue > 0
    val activity = LocalContext.current.findComponentActivity()
    val windowBackground = (if (dark) DarkColors else LightColors).ink.toArgb()
    DisposableEffect(activity, barsDark, windowBackground) {
        // The window behind the content shows during resizes and the keyboard animation.
        activity?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(windowBackground))
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { barsDark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { barsDark },
        )
        onDispose {}
    }
    ProvideColors(if (dark) DarkColors else LightColors, content)
}

// The scrims enableEdgeToEdge() uses by default behind three-button navigation.
private val LIGHT_NAV_SCRIM = AndroidColor.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1B, 0x1B, 0x1B)

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    // A lighter glow on the light background, so it tints rather than muddies.
    val glow = if (Palette.IsDark) 1f else 0.5f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .background(
                Brush.radialGradient(
                    listOf(Palette.Violet.copy(alpha = 0.24f * glow), Color.Transparent),
                    center = Offset(1050f, 80f),
                    radius = 1000f,
                ),
            )
            .background(
                Brush.radialGradient(
                    listOf(Palette.Mint.copy(alpha = 0.13f * glow), Color.Transparent),
                    center = Offset(0f, 2200f),
                    radius = 1100f,
                ),
            ),
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Panel.copy(alpha = 0.86f))
            .border(1.dp, Palette.Stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun StatusPill(text: String, color: Color) {
    // On light, an accent label uses its deeper text colour so it stays readable on its own tint.
    val label = Palette.text(color)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text,
            color = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Palette.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.OnAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Palette.TextPrimary,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Palette.PanelRaised)
            .border(1.dp, Palette.Stroke, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.text(tint), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}
