package id.dysklink.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.dysklink.desktop.Platform

/** The Mac app's Theme, for Windows and Linux. Dark is the default; light follows the system or the setting. */
@Immutable
data class DyskColors(
    val isDark: Boolean,
    val ink: Color,
    val background: Color,
    val sidebar: Color,
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
    /** Text and icons on a solid mint pill or button. */
    val onAccent: Color,
    /** A faint fill behind small icons, as Color.white.opacity(0.06) on the Mac. */
    val iconWell: Color,
) {
    val logo = Color(0xFF14B8A6)
    val accentGradient: Brush get() = Brush.linearGradient(listOf(mint, sky, violet))

    /** The text-safe version of an accent, for views that take one colour for both a dot and a label. */
    fun text(color: Color): Color = when (color) {
        mint -> mintText
        sky -> skyText
        violet -> violetText
        amber -> amberText
        coral -> coralText
        else -> color
    }

    fun battery(percent: Int): Color = when {
        percent <= 15 -> coral
        percent <= 35 -> amber
        else -> mint
    }
}

val DarkColors = DyskColors(
    isDark = true,
    ink = Color(0xFF090D13),
    background = Color(0xFF090D13),
    sidebar = Color(0xFF070A0F),
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
)

/**
 * The light theme. The bright accents are for fills, rings, bars, icons, and large titles (3:1 or more on the
 * panel); text uses the *Text accents, which read at 4.5:1 or more on the panel, raised panel, background, and
 * sidebar. onAccent is the dark ink, since white on the light accents is only about 3:1. Same values as the Mac.
 */
val LightColors = DyskColors(
    isDark = false,
    ink = Color(0xFF090D13),
    background = Color(0xFFF3F5F8),
    sidebar = Color(0xFFE8ECF1),
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
)

/** Spacing steps, so every gap is one of four sizes. */
object Space {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
}

val LocalDysk = staticCompositionLocalOf { DarkColors }

/** The colors of the current theme, as `Dysk.colors.mint`. */
object Dysk {
    val colors: DyskColors @Composable get() = LocalDysk.current
}

enum class ThemeMode(val label: String) { System("System"), Dark("Dark"), Light("Light") }

@Composable
fun DyskTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = colors.mint, onPrimary = colors.onAccent, background = colors.background, surface = colors.panel)
    } else {
        lightColorScheme(primary = colors.mint, onPrimary = colors.onAccent, background = colors.background, surface = colors.panel)
    }
    CompositionLocalProvider(LocalDysk provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Whether the system uses a light theme: true or false when it can be told, null otherwise. */
object SystemTheme {
    fun prefersLight(): Boolean? = runCatching {
        when {
            Platform.isWindows -> {
                val out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                when {
                    out == null -> null
                    out.contains("0x1") -> true
                    out.contains("0x0") -> false
                    else -> null
                }
            }
            Platform.isMac -> run("defaults", "read", "-g", "AppleInterfaceStyle")?.let { !it.contains("Dark") } ?: true
            else -> {
                // GNOME 42 and later, and most desktops that follow it, say so here; older ones only in the theme name.
                val scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                val gtk = System.getenv("GTK_THEME") ?: run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
                when {
                    scheme?.contains("prefer-dark") == true -> false
                    scheme?.contains("prefer-light") == true -> true
                    gtk?.contains("dark", ignoreCase = true) == true -> false
                    gtk != null && gtk.isNotBlank() -> true
                    else -> null
                }
            }
        }
    }.getOrNull()

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0) text else null
    }
}
