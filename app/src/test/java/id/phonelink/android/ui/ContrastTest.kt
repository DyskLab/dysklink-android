package id.phonelink.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2 contrast of the light theme: text 4.5:1 on every surface, the bright accents 3:1 for icons and rings. */
class ContrastTest {
    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val light = LightColors
    private val surfaces = mapOf(
        "panel" to light.panel, "panelRaised" to light.panelRaised,
        "background" to light.ink,
        // The Mac and desktop sidebar, for parity.
        "sidebar" to Color(0xFFE8ECF1),
    )
    private val text = mapOf(
        "textPrimary" to light.textPrimary, "textSecondary" to light.textSecondary, "textFaint" to light.textFaint,
        "mintText" to light.mintText, "skyText" to light.skyText, "violetText" to light.violetText,
        "amberText" to light.amberText, "coralText" to light.coralText,
    )
    private val fills = mapOf("mint" to light.mint, "sky" to light.sky, "amber" to light.amber, "coral" to light.coral)

    @Test
    fun formula() = assertEquals(21.0, ratio(Color.Black, Color.White), 0.01)

    @Test
    fun textOnEverySurface() {
        val failures = text.flatMap { (name, color) ->
            surfaces.mapNotNull { (surface, background) ->
                val r = ratio(color, background)
                if (r < 4.5) "$name on $surface is ${"%.2f".format(r)}:1" else null
            }
        }
        assertTrue(failures.isEmpty(), "Below 4.5:1: $failures")
    }

    @Test
    fun accentTextOnItsOwnTint() {
        // Status pills and banners put the text accent on a 13% tint of the bright one.
        val pairs = listOf(
            light.mint to light.mintText, light.sky to light.skyText, light.violet to light.violetText,
            light.amber to light.amberText, light.coral to light.coralText,
        )
        for ((fill, label) in pairs) for (base in listOf(light.panel, light.ink)) {
            val r = ratio(label, fill.copy(alpha = 0.13f).compositeOver(base))
            assertTrue(r >= 4.5, "$label on its tint is ${"%.2f".format(r)}:1")
        }
    }

    @Test
    fun onAccentOnFills() {
        for ((name, fill) in fills) {
            val r = ratio(light.onAccent, fill)
            assertTrue(r >= 4.5, "onAccent on $name is ${"%.2f".format(r)}:1")
        }
    }

    @Test
    fun brightAccentsAsIcons() {
        for ((name, fill) in fills) {
            val r = ratio(fill, light.panel)
            assertTrue(r >= 3.0, "$name on panel is ${"%.2f".format(r)}:1")
        }
    }

    @Test
    fun textMapsAccents() {
        assertEquals(light.mintText, light.text(light.mint))
        assertEquals(light.amberText, light.text(light.amber))
        assertEquals(light.textPrimary, light.text(light.textPrimary))
        // Dark is unchanged: its text accents are the bright ones.
        assertEquals(DarkColors.mint, DarkColors.text(DarkColors.mint))
        assertEquals(DarkColors.coral, DarkColors.coralText)
    }

    @Test
    fun sameValuesAsTheMac() {
        // The Mac's and the desktop app's light values, so the three apps match.
        val mac = mapOf(
            "textSecondary" to 0xFF4F5B6C, "textFaint" to 0xFF5F6A7B, "mintText" to 0xFF057553, "skyText" to 0xFF0B6A99,
            "violetText" to 0xFF5847D6, "amberText" to 0xFF8A5A00, "coralText" to 0xFFB42333,
        )
        for ((name, hex) in mac) assertEquals(Color(hex), text.getValue(name), name)
        assertEquals(Color(0xFF090D13), light.onAccent)
    }
}
