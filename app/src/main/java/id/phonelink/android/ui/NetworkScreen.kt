package id.phonelink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.NetTools
import id.phonelink.android.service.NetworkTools
import id.phonelink.android.service.NetworkTools.SpeedPhase
import kotlinx.coroutines.delay

@Composable
fun NetworkHomeCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(NetworkTools.info(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            info = NetworkTools.info(context)
        }
    }
    val speed by NetworkTools.speed.collectAsStateWithLifecycle()
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatIcon(androidx.compose.material.icons.Icons.Rounded.Wifi, Palette.Sky)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Network tools", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(info.wifiName ?: info.transport, info.address).joinToString(" · "),
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            val down = speed.downMbps
            Text(
                if (down != null) "↓ ${NetTools.formatMbps(down)} Mbps" else "Speed test",
                color = Palette.SkyText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(NetworkTools.info(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            info = NetworkTools.info(context)
            delay(5_000)
        }
    }
    FeatureScaffold("Network tools", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoCard(info)
            SpeedCard(info)
            DevicesCard()
            PingCard(info)
            Text(
                "The speed test downloads and uploads test data from speed.cloudflare.com, which sees this network's " +
                    "public address. Finding devices only checks a few common ports on this Wi-Fi and never leaves it.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
}

@Composable
private fun InfoCard(info: NetworkTools.NetworkInfo) {
    GlassCard {
        CardTitle("THIS CONNECTION")
        Spacer(Modifier.height(4.dp))
        Text(info.wifiName ?: info.transport, color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        InfoRow("Type", info.transport + if (info.isMetered) " (metered)" else "")
        info.address?.let { InfoRow("Phone address", it) }
        info.gateway?.let { InfoRow("Router", it) }
        info.linkMbps?.let { InfoRow("Wi-Fi link", "$it Mbps") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Palette.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeedCard(info: NetworkTools.NetworkInfo) {
    val speed by NetworkTools.speed.collectAsStateWithLifecycle()
    val running = speed.phase in setOf(SpeedPhase.Latency, SpeedPhase.Download, SpeedPhase.Upload)
    GlassCard {
        CardTitle("SPEED TEST")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedTile("Download", speed.downMbps, speed.phase == SpeedPhase.Download, speed.liveMbps, Palette.Mint, Modifier.weight(1f))
            SpeedTile("Upload", speed.upMbps, speed.phase == SpeedPhase.Upload, speed.liveMbps, Palette.Violet, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (speed.phase) {
                SpeedPhase.Idle -> if (info.isMetered) "This connection is metered. A test uses about 50 to 300 MB." else "Takes about 20 seconds."
                SpeedPhase.Latency -> "Measuring response time…"
                SpeedPhase.Download -> "Testing download…"
                SpeedPhase.Upload -> "Testing upload…"
                SpeedPhase.Done -> "Response time ${speed.latencyMs} ms · used about ${speed.usedMb} MB"
                SpeedPhase.Failed -> speed.error ?: "The test failed."
            },
            color = if (speed.phase == SpeedPhase.Failed || (speed.phase == SpeedPhase.Idle && info.isMetered)) Palette.AmberText else Palette.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (running) {
            SecondaryButton("Stop", Modifier.fillMaxWidth()) { NetworkTools.stopSpeed() }
        } else {
            PrimaryButton(if (speed.phase == SpeedPhase.Idle) "Start test" else "Test again", Modifier.fillMaxWidth()) { NetworkTools.startSpeed() }
        }
    }
}

@Composable
private fun SpeedTile(
    label: String,
    result: Double?,
    active: Boolean,
    live: Double?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    val value = if (active) live else result
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) color.copy(alpha = 0.12f) else Palette.PanelRaised)
            .padding(12.dp),
    ) {
        Text(label, color = if (active) Palette.text(color) else Palette.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { NetTools.formatMbps(it) } ?: "–", color = Palette.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(" Mbps", color = Palette.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun DevicesCard() {
    val context = LocalContext.current
    val scan by NetworkTools.scan.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("DEVICES ON THIS WI-FI")
                if (scan.devices.isNotEmpty() || scan.running) {
                    Text("${scan.devices.size} found", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (scan.running && scan.total > 0) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Palette.Stroke)) {
                Box(Modifier.fillMaxWidth(scan.checked.toFloat() / scan.total).fillMaxHeight().background(Palette.Sky))
            }
        }
        scan.note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Palette.AmberText, fontSize = 12.sp)
        }
        scan.devices.forEach { device ->
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.PanelRaised)
                    .clickable { open = if (open == device.ip) null else device.ip }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatIcon(FlatIcons.device(device.kind.label), if (device.kind.label == "This phone") Palette.Mint else Palette.Sky)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name ?: device.kind.label, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            if (device.name != null) "${device.kind.label} · ${device.ip}" else device.ip,
                            color = Palette.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (open == device.ip && device.open.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open: " + device.open.sorted().joinToString(", ") { "${NetTools.portName(it)} ($it)" },
                        color = Palette.TextFaint,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
        if (!scan.running && scan.devices.isEmpty() && scan.note == null) {
            Spacer(Modifier.height(6.dp))
            Text("See phones, computers, printers, and TVs on the same Wi-Fi.", color = Palette.TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (scan.running) {
            SecondaryButton("Stop", Modifier.fillMaxWidth()) { NetworkTools.stopScan() }
        } else {
            PrimaryButton(if (scan.devices.isEmpty()) "Find devices" else "Search again", Modifier.fillMaxWidth()) { NetworkTools.startScan(context) }
        }
    }
}

@Composable
private fun PingCard(info: NetworkTools.NetworkInfo) {
    val ping by NetworkTools.ping.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    GlassCard {
        CardTitle("PING")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim().take(253) },
            placeholder = { Text("google.com or 192.168.1.1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { NetworkTools.startPing(host) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOfNotNull(info.gateway?.let { "Router" to it }, "Google" to "google.com", "Cloudflare" to "1.1.1.1").forEach { (label, target) ->
                Text(
                    label,
                    color = Palette.SkyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Palette.PanelRaised)
                        .clickable {
                            host = target
                            NetworkTools.startPing(target)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        if (ping.host.isNotEmpty() && (ping.lines.isNotEmpty() || ping.running || ping.error != null)) {
            Spacer(Modifier.height(10.dp))
            if (ping.usedTcp) {
                Text("Ping is blocked here, so this times a web connection instead.", color = Palette.TextFaint, fontSize = 12.sp)
            }
            ping.lines.forEach { Text(it, color = Palette.TextSecondary, fontSize = 13.sp) }
            ping.error?.let { Text(it, color = Palette.AmberText, fontSize = 13.sp) }
            ping.summary?.takeIf { ping.error == null }?.let { summary ->
                Spacer(Modifier.height(6.dp))
                Text(
                    if (summary.received == 0) {
                        "No answer from ${ping.host}."
                    } else {
                        "Average %.0f ms (%.0f to %.0f) · %d%% lost".format(summary.avg, summary.min, summary.max, summary.lossPercent)
                    },
                    color = if (summary.received == 0) Palette.CoralText else Palette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (ping.running) {
            SecondaryButton("Stop", Modifier.fillMaxWidth()) { NetworkTools.stopPing() }
        } else {
            PrimaryButton("Ping", Modifier.fillMaxWidth()) { NetworkTools.startPing(host) }
        }
    }
}
