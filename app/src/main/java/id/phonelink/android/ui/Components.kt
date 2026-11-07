package id.phonelink.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.R

@Composable
fun LogoMark(size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Palette.Logo),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_glyph),
            contentDescription = "DyskLink logo",
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun LaptopGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val line = width * 0.09f
        drawRoundRect(
            color,
            topLeft = Offset(width * 0.17f, height * 0.2f),
            size = Size(width * 0.66f, height * 0.46f),
            cornerRadius = CornerRadius(width * 0.06f),
            style = Stroke(line),
        )
        drawLine(
            color,
            Offset(width * 0.06f, height * 0.8f),
            Offset(width * 0.94f, height * 0.8f),
            strokeWidth = line,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun BatteryRing(percent: Int?, charging: Boolean, size: Dp = 158.dp) {
    val color = percent?.let(Palette::battery) ?: Palette.TextFaint
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val width = this.size.width * 0.08f
            val inset = width / 2
            val arcSize = Size(this.size.width - width, this.size.height - width)
            drawArc(
                Color.White.copy(alpha = 0.07f), 0f, 360f, false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width),
            )
            drawArc(
                color, -90f, 360f * (percent ?: 0) / 100f, false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    percent?.toString() ?: "-",
                    color = Palette.TextPrimary,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (percent != null) Text("%", color = Palette.TextSecondary, fontSize = 18.sp)
            }
            Text(
                if (charging) "⚡ Charging" else "Battery",
                color = Palette.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun Radar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2700, easing = LinearEasing), RepeatMode.Restart),
        label = "radar-progress",
    )
    Box(modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            repeat(3) { index ->
                val phase = (progress + index / 3f) % 1f
                drawCircle(
                    Palette.Mint.copy(alpha = 0.55f * (1 - phase)),
                    radius = this.size.minDimension / 2 * (0.25f + 0.75f * phase),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
        Box(
            Modifier.size(74.dp).clip(CircleShape).background(Palette.Accent),
            contentAlignment = Alignment.Center,
        ) {
            LaptopGlyph(Palette.Ink, Modifier.size(40.dp))
        }
    }
}

@Composable
fun StorageBar(free: Long, total: Long) {
    val used = ((total - free).toFloat() / total.coerceAtLeast(1)).coerceIn(0.02f, 1f)
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
    ) {
        Box(
            Modifier.fillMaxWidth(used).fillMaxHeight().clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Palette.Mint, Palette.Sky, Palette.Violet))),
        )
    }
}

@Composable
fun CodeDigits(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { index, digit ->
            Box(
                Modifier
                    .size(width = 42.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.PanelRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(digit.toString(), color = Palette.TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
            if (index == 2) Box(Modifier.size(5.dp))
        }
    }
}

fun formatBytes(bytes: Long): String {
    val gigabytes = bytes / 1_000_000_000.0
    return if (gigabytes >= 1) "%.1f GB".format(gigabytes) else "%.0f MB".format(bytes / 1_000_000.0)
}
