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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.BatteryMath
import id.dysklink.desktop.BatteryView
import id.dysklink.desktop.ChargeState
import id.dysklink.desktop.EnergyMath
import id.dysklink.desktop.EnergyView
import id.dysklink.desktop.ImpactLevel
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Battery on the Tools page, as the Mac's Battery tab. Hidden on a PC without a battery. */
@Composable
fun BatteryCard(model: DeskModel) {
    val flow = model.battery ?: return
    val view by flow.collectAsState()
    // The apps are only sampled while this card is on screen (and on battery for the tray's line).
    DisposableEffect(model) {
        model.watchEnergy(true)
        onDispose { model.watchEnergy(false) }
    }
    val energy = model.energy?.collectAsState()?.value
    BatteryContent(view ?: return, energy)
}

@Composable
fun BatteryContent(view: BatteryView, energy: EnergyView? = null) {
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
        if (energy != null) {
            Spacer(Modifier.height(Space.sm))
            EnergyApps(energy)
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

/** Apps using significant energy, as the Mac's Battery page: battery power against the load, then the top apps. */
@Composable
private fun EnergyApps(e: EnergyView) {
    val colors = Dysk.colors
    Column(Modifier.fillMaxWidth().background(colors.panelRaised.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Using significant energy", Modifier.weight(1f), color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(if (e.gpu) "Estimated from CPU and GPU use" else "Estimated from CPU use", color = colors.textFaint, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        // The split: what the battery gives, and how much of it the load takes.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box(Modifier.weight(1f)) {
                StatTile("Battery power", e.batteryWatts?.let { "%.1f W".format(Locale.ENGLISH, it) } ?: if (e.onBattery) "Not reported" else "On the charger",
                    if (e.onBattery) "From the battery now" else "Plug-in power: impact is relative")
            }
            Box(Modifier.weight(1f)) {
                StatTile("System load", "%.0f%% CPU".format(Locale.ENGLISH, e.loadPercent),
                    e.loadWatts?.let { "About %.1f W of the battery's power".format(Locale.ENGLISH, it) } ?: "All cores, last 15 seconds")
            }
        }
        if (e.batteryWatts != null && e.loadWatts != null) {
            Spacer(Modifier.height(6.dp))
            val share = (e.loadWatts / e.batteryWatts).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).background(colors.stroke, CircleShape)) {
                Box(Modifier.fillMaxWidth(share.coerceAtLeast(0.02f)).fillMaxHeight().background(colors.amber, CircleShape))
            }
            Row(Modifier.padding(top = 3.dp)) {
                Text("Load", Modifier.weight(1f), color = colors.textFaint, fontSize = 10.5.sp)
                Text("Screen, idle and the rest", color = colors.textFaint, fontSize = 10.5.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            !e.ready -> Text("Measuring…", Modifier.padding(vertical = 8.dp), color = colors.textFaint, fontSize = 12.5.sp)
            e.apps.isEmpty() -> Text("No apps using significant energy", Modifier.fillMaxWidth().padding(vertical = 10.dp),
                color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            else -> {
                val scale = maxOf(100.0, e.apps.maxOf { it.impact })
                e.apps.forEach { app ->
                    val tint = when (app.level) { ImpactLevel.High -> colors.coral; ImpactLevel.Medium -> colors.amber; ImpactLevel.Low -> colors.sky }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.name, Modifier.weight(1f), color = colors.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.width(90.dp).height(6.dp).background(colors.stroke, CircleShape)) {
                            Box(Modifier.fillMaxWidth((app.impact / scale).toFloat().coerceIn(0.04f, 1f)).fillMaxHeight().background(tint, CircleShape))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(app.watts?.let { "~%.1f W".format(Locale.ENGLISH, it) } ?: "", Modifier.width(52.dp), color = colors.textSecondary,
                            fontSize = 12.sp, textAlign = TextAlign.End)
                        Text(app.level.label, Modifier.width(60.dp), color = Dysk.colors.text(tint), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Impact is CPU time in percent of one core${if (e.gpu) " plus GPU use" else ""}, averaged over 15 seconds; apps above " +
                "${EnergyMath.SIGNIFICANT.toInt()} show.${if (e.loadWatts != null) " Watts are estimates." else ""} Only process names are read, and they stay on this PC.",
            color = colors.textFaint, fontSize = 11.sp, lineHeight = 15.sp,
        )
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
