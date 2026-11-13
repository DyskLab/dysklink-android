package id.dysklink.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.BatteryMath
import id.dysklink.desktop.BatteryView
import id.dysklink.desktop.ChargeState
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Battery on the Tools page, as the Mac's Battery tab. Hidden on a PC without a battery. */
@Composable
fun BatteryCard(model: DeskModel) {
    val flow = model.battery ?: return
    val view by flow.collectAsState()
    BatteryContent(view ?: return)
}

@Composable
fun BatteryContent(view: BatteryView) {
    val colors = Dysk.colors
    val r = view.reading
    val tint = when {
        r.state == ChargeState.Charging || r.state == ChargeState.Full -> colors.mint
        else -> colors.battery(r.percent)
    }
    GlassCard {
        CardTitle(DyskIcons.Battery, "Battery", tint) {
            StatusPill(r.statusText, tint)
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.percent}%", color = colors.textPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            val estimate = when (r.state) {
                ChargeState.Charging -> r.timeToFull?.let { "Full in about ${BatteryMath.duration(it)}" }
                ChargeState.Discharging -> r.timeToEmpty?.let { "About ${BatteryMath.duration(it)} left" }
                else -> null
            }
            Text(estimate ?: "", Modifier.padding(bottom = 9.dp), color = colors.textSecondary, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(colors.panelRaised, CircleShape)) {
            Box(Modifier.fillMaxWidth(r.percent / 100f).fillMaxHeight().background(tint, CircleShape))
        }
        Spacer(Modifier.height(Space.sm))
        val power = r.watts?.let { "%.1f W".format(Locale.ENGLISH, it) }
        val tiles = listOf(
            Triple("Health", r.health?.let { "$it%" } ?: "Not reported", r.fullWh?.let { full -> "%.1f of %s Wh".format(Locale.ENGLISH, full, r.designWh?.let { "%.1f".format(Locale.ENGLISH, it) } ?: "?") }),
            Triple("Cycle count", r.cycles?.toString() ?: "Not reported", null),
            Triple(
                when (r.state) { ChargeState.Charging -> "Power in"; ChargeState.Discharging -> "Power out"; else -> "Power" },
                power ?: if (r.state == ChargeState.Full || r.state == ChargeState.NotCharging) "0 W" else "Not reported",
                r.voltage?.let { "%.2f V".format(Locale.ENGLISH, it) },
            ),
            Triple(
                if (r.state == ChargeState.Charging) "Time to full" else "Time remaining",
                (if (r.state == ChargeState.Charging) r.timeToFull else r.timeToEmpty)?.let(BatteryMath::duration) ?: "-",
                null,
            ),
            Triple("Temperature", r.temperatureC?.let { "%.1f °C".format(Locale.ENGLISH, it) } ?: "Not reported", null),
            Triple("Battery", listOfNotNull(r.vendor, r.model).joinToString(" ").ifEmpty { "-" }, r.chemistry),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if (maxWidth >= 640.dp) 3 else 2
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                tiles.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        row.forEach { (title, value, detail) -> Box(Modifier.weight(1f)) { StatTile(title, value, detail) } }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (view.days.size >= 2) {
            Spacer(Modifier.height(Space.sm))
            LineChart("Health, one point a day", view.days.map { it.health.toFloat() },
                // Health moves a point or two a month, so the scale starts a little below the lowest point.
                ((view.days.minOf { it.health } - 10).coerceAtLeast(0).toFloat())..100f, colors.mint,
                first = view.days.first().day, last = view.days.last().day, value = "${view.days.last().health}%")
        }
        if (view.levels.size >= 2) {
            Spacer(Modifier.height(Space.sm))
            LineChart("Level since DyskLink started", view.levels.map { it.toFloat() }, 0f..100f, colors.sky,
                first = "${view.levels.size} min ago", last = "Now", value = "${r.percent}%")
        }
        if (r.recent.isNotEmpty()) {
            Spacer(Modifier.height(Space.sm))
            Text("Recent usage", color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val format = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
            r.recent.takeLast(6).reversed().forEach { use ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(use.time.format(format), Modifier.width(110.dp), color = colors.textFaint, fontSize = 12.sp)
                    Text(if (use.onAc) "On power" else "On battery", Modifier.weight(1f), color = colors.textPrimary, fontSize = 12.sp)
                    Text(use.percent?.let { "$it%" } ?: "", color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
        if (view.notes.isNotEmpty()) {
            Spacer(Modifier.height(Space.sm))
            view.notes.forEach { Body(it, colors.textFaint) }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, detail: String?) {
    val colors = Dysk.colors
    Column(Modifier.fillMaxWidth().background(colors.panelRaised, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(title, color = colors.textSecondary, fontSize = 11.5.sp)
        Text(value, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // Every tile keeps the detail line, so a row of tiles is one height.
        Text(detail ?: " ", color = colors.textFaint, fontSize = 11.sp, maxLines = 1)
    }
}

/** One series as a 2 px line over a recessive grid; the title names it, so there's no legend. */
@Composable
private fun LineChart(title: String, points: List<Float>, range: ClosedFloatingPointRange<Float>, color: Color, first: String, last: String, value: String) {
    val colors = Dysk.colors
    Column(Modifier.fillMaxWidth().background(colors.panelRaised.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row {
            Text(title, Modifier.weight(1f), color = colors.textSecondary, fontSize = 12.sp)
            Text(value, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        val grid = colors.stroke
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val span = (range.endInclusive - range.start).coerceAtLeast(1f)
            listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f) }
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = if (points.size == 1) 0f else size.width * i / (points.size - 1)
                val y = size.height * (1 - ((p - range.start) / span).coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val lastY = size.height * (1 - ((points.last() - range.start) / span).coerceIn(0f, 1f))
            drawCircle(colors.panel, 5.dp.toPx(), Offset(size.width, lastY))
            drawCircle(color, 4.dp.toPx(), Offset(size.width, lastY))
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(first, Modifier.weight(1f), color = colors.textFaint, fontSize = 10.5.sp)
            Text(last, color = colors.textFaint, fontSize = 10.5.sp)
        }
    }
}
