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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Logo = Color(0xFF2B3A57)
    val Ink = Color(0xFF090D13)
    val Panel = Color(0xFF121822)
    val PanelRaised = Color(0xFF1A2230)
    val Stroke = Color(0x14FFFFFF)
    val TextPrimary = Color(0xFFF2F5F9)
    val TextSecondary = Color(0xFF98A3B3)
    val TextFaint = Color(0xFF667081)
    val Mint = Color(0xFF5EF2C2)
    val Sky = Color(0xFF4CC9F0)
    val Violet = Color(0xFF8B7BFF)
    val Amber = Color(0xFFFFC35C)
    val Coral = Color(0xFFFF6F7D)

    val Accent = Brush.linearGradient(listOf(Mint, Sky, Violet))

    fun battery(percent: Int): Color = when {
        percent <= 15 -> Coral
        percent <= 35 -> Amber
        else -> Mint
    }
}

@Composable
fun PhoneLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Mint,
            onPrimary = Palette.Ink,
            background = Palette.Ink,
            surface = Palette.Panel,
            onSurface = Palette.TextPrimary,
            onSurfaceVariant = Palette.TextSecondary,
            error = Palette.Coral,
        ),
        content = content,
    )
}

@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .background(
                Brush.radialGradient(
                    listOf(Palette.Violet.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(1050f, 80f),
                    radius = 1000f,
                ),
            )
            .background(
                Brush.radialGradient(
                    listOf(Palette.Mint.copy(alpha = 0.13f), Color.Transparent),
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
            color = color,
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
        Text(text, color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
        Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
