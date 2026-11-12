package id.dysklink.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A panel with a hairline border, as Card on the Mac. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().background(colors.panel.copy(alpha = if (colors.isDark) 0.86f else 1f), shape)
            .border(1.dp, colors.stroke, shape).padding(padding),
        content = content,
    )
}

/** A small uppercase heading above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(start = 2.dp, top = 6.dp),
        color = Dysk.colors.textFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp,
    )
}

@Composable
fun DyskIcon(icon: ImageVector, tint: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** A dot and a word in a soft capsule of the same color. */
@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.13f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One tab in a row of pills: solid mint with dark text when selected, a raised pill otherwise. */
@Composable
fun TabPill(
    title: String,
    selected: Boolean,
    icon: ImageVector? = null,
    count: Int? = null,
    showTitle: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Dysk.colors
    val content = if (selected) colors.onAccent else colors.textPrimary
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(if (selected) colors.mint else colors.panelRaised)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { DyskIcon(it, content, 13.dp) }
        if (showTitle || selected || icon == null) Text(title, color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (count != null && count > 0) {
            Text(
                "$count",
                Modifier.background(if (selected) colors.onAccent.copy(alpha = 0.16f) else colors.mint, CircleShape)
                    .padding(horizontal = 6.dp),
                color = colors.onAccent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp,
            )
        }
    }
}

/** A small count with a bell, for a device row with notifications. */
@Composable
fun AlertBadge(count: Int) {
    val colors = Dysk.colors
    Row(
        Modifier.background(colors.mint, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DyskIcon(DyskIcons.Bell, colors.onAccent, 10.dp)
        Spacer(Modifier.width(3.dp))
        Text("$count", color = colors.onAccent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
    }
}

/** The battery as a ring with the percentage inside, as on the Mac. */
@Composable
fun BatteryRing(percent: Int?, charging: Boolean, size: Dp = 112.dp) {
    val colors = Dysk.colors
    val color = percent?.let(colors::battery) ?: colors.textFaint
    val track = if (colors.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.07f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.075f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            // A soft glow under the ring.
            drawArc(color.copy(alpha = 0.12f), -90f, 3.6f * (percent ?: 0), false, Offset(inset, inset), arcSize, style = Stroke(stroke * 1.7f, cap = StrokeCap.Round))
            drawArc(color, -90f, 3.6f * (percent ?: 0), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(percent?.toString() ?: "-", color = colors.textPrimary, fontSize = (size.value * 0.27f).sp, fontWeight = FontWeight.Bold)
                if (percent != null) {
                    Text(
                        "%", Modifier.padding(bottom = (size.value * 0.04f).dp, start = 1.dp),
                        color = colors.textSecondary, fontSize = (size.value * 0.11f).sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (charging) DyskIcon(DyskIcons.Bolt, color, 12.dp)
                // A small ring has no room for the word.
                if (size >= 100.dp) {
                    Spacer(Modifier.width(3.dp))
                    Text(if (charging) "Charging" else "Battery", color = colors.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** The logo on its teal rounded square. */
@Composable
fun LogoMark(size: Dp = 34.dp) {
    Box(Modifier.size(size).background(Dysk.colors.logo, RoundedCornerShape(size * 0.24f)), contentAlignment = Alignment.Center) {
        Icon(DyskIcons.Logo, null, tint = Color.White, modifier = Modifier.size(size))
    }
}

/** Rings that grow out of the phone while DyskLink waits for one. [progress] fixes the frame for snapshots. */
@Composable
fun RadarView(progress: Float? = null) {
    val colors = Dysk.colors
    val time = if (progress != null) progress else {
        val transition = rememberInfiniteTransition()
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2700, easing = LinearEasing), RepeatMode.Restart))
        value
    }
    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val radius = this.size.minDimension / 2
            // The glow behind the middle circle.
            drawCircle(Brush.radialGradient(listOf(colors.mint.copy(alpha = 0.28f), Color.Transparent), center, radius * 0.6f), radius * 0.6f)
            for (index in 0 until 3) {
                val phase = (time + index / 3f) % 1f
                drawCircle(colors.mint.copy(alpha = 0.55f * (1 - phase)), radius * (0.25f + 0.75f * phase) - 1f, style = Stroke(1.5.dp.toPx()))
            }
        }
        Box(Modifier.size(66.dp).background(colors.accentGradient, CircleShape), contentAlignment = Alignment.Center) {
            DyskIcon(DyskIcons.Phone, colors.ink, 26.dp)
        }
    }
}

/** The main action: a gradient capsule with dark text. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.alpha(if (enabled) 1f else 0.45f).height(40.dp).clip(CircleShape).background(colors.accentGradient)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { DyskIcon(it, colors.ink, 15.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = colors.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, tint: Color = Dysk.colors.textPrimary, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.alpha(if (enabled) 1f else 0.45f).height(40.dp).clip(CircleShape).background(colors.panelRaised)
            .border(1.dp, colors.stroke, CircleShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { DyskIcon(it, tint, 15.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = tint, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A small text button, for actions such as Unpair. */
@Composable
fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** A big button under the device's name for the most used actions. */
@Composable
fun RowScope.QuickTile(icon: ImageVector, title: String, tint: Color = Dysk.colors.mint, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(14.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f).clip(shape)
            .background(if (hovered && enabled) colors.panelRaised.copy(alpha = 1f).compositeOver(colors.mint, 0.06f) else colors.panelRaised)
            .border(1.dp, colors.stroke, shape).hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DyskIcon(icon, tint, 17.dp)
        Spacer(Modifier.height(7.dp))
        Text(title, color = colors.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Color.compositeOver(other: Color, amount: Float) = Color(
    red = red + (other.red - red) * amount,
    green = green + (other.green - green) * amount,
    blue = blue + (other.blue - blue) * amount,
    alpha = alpha,
)

/** An icon in a faint rounded square, as in the Mac's sidebar rows. */
@Composable
fun IconWell(icon: ImageVector, tint: Color, size: Dp = 34.dp, background: Color = Dysk.colors.iconWell) {
    Box(Modifier.size(size).background(background, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        DyskIcon(icon, tint, size * 0.47f)
    }
}

/** A setting: title and detail on the left, a mint switch on the right. */
@Composable
fun SettingRow(title: String, detail: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = Dysk.colors
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            detail?.let { Spacer(Modifier.height(2.dp)); Text(it, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        DyskSwitch(checked, enabled, onChange)
    }
}

@Composable
fun DyskSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = Dysk.colors
    // Material's switch is larger than the Mac's small one, so it's drawn at 80%.
    Box(Modifier.size(42.dp, 26.dp), contentAlignment = Alignment.Center) {
    Switch(
        checked, onChange, Modifier.requiredSize(52.dp, 32.dp).scale(0.8f), enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.mint, checkedThumbColor = colors.onAccent, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = colors.panelRaised, uncheckedThumbColor = colors.textFaint, uncheckedBorderColor = colors.stroke,
        ),
    )
    }
}

/** A thin line between rows inside a card. */
@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = Space.sm).height(1.dp).background(Dysk.colors.stroke))
}

/** A strip across the page for something happening now, such as this PC ringing. */
@Composable
fun Banner(text: String, color: Color, icon: ImageVector = DyskIcons.Info, action: Pair<String, () -> Unit>? = null) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), shape).border(1.dp, color.copy(alpha = 0.25f), shape)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).defaultMinSize(minHeight = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DyskIcon(icon, color, 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        action?.let { (label, onClick) ->
            Text(
                label, Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A card that only says something, such as "No notifications". */
@Composable
fun EmptyCard(text: String, icon: ImageVector? = null) {
    GlassCard(padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { DyskIcon(it, Dysk.colors.textFaint, 16.dp); Spacer(Modifier.width(10.dp)) }
            Text(text, color = Dysk.colors.textFaint, fontSize = 13.sp)
        }
    }
}

@Composable
fun Title(text: String, size: TextUnit = 22.sp) {
    Text(text, color = Dysk.colors.textPrimary, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Body(text: String, color: Color = Dysk.colors.textSecondary, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, fontSize = 13.sp, lineHeight = 18.sp)
}
