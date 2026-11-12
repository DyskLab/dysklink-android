package id.dysklink.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A page title with a line under it, for Settings and File history. */
@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Title(title, 26.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Dysk.colors.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

@Composable
fun SettingsPage(model: DeskModel) {
    val colors = Dysk.colors
    val clipboardOn by model.clipboardOn.collectAsState()
    val notifyOn by model.notifyOn.collectAsState()
    val controlOn by model.controlOn.collectAsState()
    val computersOn by model.computersOn.collectAsState()
    val closeToTray by model.closeToTray.collectAsState()
    val themeMode by model.themeMode.collectAsState()
    val soundOutOn by model.soundOutOn.collectAsState()
    val screenOutOn by model.screenOutOn.collectAsState()
    Page {
        PageHeader("Settings", "How DyskLink works on ${model.deviceName}.")

        SectionLabel("General")
        GlassCard {
            var atLogin by remember { mutableStateOf(model.autostartOn()) }
            SettingRow(
                "Start at login",
                if (model.autostartAvailable) "DyskLink starts hidden in the tray, so phones can connect right away." else "Available in the installed app.",
                atLogin, enabled = model.autostartAvailable,
            ) { on -> if (model.setAutostart(on)) atLogin = on }
            if (model.hasTray) {
                Divider()
                SettingRow("Keep running in the tray", "Closing the window leaves DyskLink in the tray. Quit from the tray menu.", closeToTray, onChange = model::setCloseToTray)
            }
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Space.sm)) {
                    Text("Appearance", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("System follows the light or dark setting of this PC when it can be read.", color = colors.textSecondary, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode -> TabPill(mode.label, mode == themeMode) { model.setThemeMode(mode) } }
                }
            }
        }

        SectionLabel("Phones")
        GlassCard {
            SettingRow("Share the clipboard", "Text and pictures copied here can be pasted on your devices, and the other way round.", clipboardOn, onChange = model::setClipboardOn)
            Divider()
            SettingRow("Show phone notifications", "Notifications from the phone pop up on this PC.", notifyOn, onChange = model::setNotifyOn)
            Divider()
            SettingRow("Phones can control this PC", "The touchpad, keyboard, shortcuts and media remote on paired phones.", controlOn, onChange = model::setControlOn)
            if (controlOn) model.controlNotes().forEach { note ->
                Spacer(Modifier.height(Space.sm))
                Banner(note, colors.amber, DyskIcons.Info)
            }
            Divider()
            SettingRow("Let phones hear this PC", "A paired phone can ask for what plays on this PC, from Sound in DyskLink on the phone.", soundOutOn, onChange = model::setSoundOutOn)
            Divider()
            SettingRow(
                "Let phones see this screen",
                "A paired phone can view this PC's screen from PC screen in DyskLink, and tap and type on it while phones can control this PC. A notice shows while it's viewed. Off stops it at once.",
                screenOutOn, onChange = model::setScreenOutOn,
            )
            if (screenOutOn) model.screenNotes().forEach { note ->
                Spacer(Modifier.height(Space.sm))
                Banner(note, colors.amber, DyskIcons.Info)
            }
        }

        SectionLabel("Commands")
        CommandsSettings(model)

        SectionLabel("Computers")
        GlassCard {
            SettingRow(
                "Sync with other computers",
                "Share the clipboard and files with Macs and other PCs with DyskLink on this network. A Mac can only use this PC's mouse and keyboard while that's allowed below.",
                computersOn, onChange = model::setComputersOn,
            )
            RemoteSettings(model, computersOn)
        }

        SectionLabel("Files and connection")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Space.sm)) {
                    Text("Received files", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(model.downloadsPath, color = colors.textSecondary, fontSize = 12.sp)
                }
                SecondaryButton("Open", icon = DyskIcons.Folder, onClick = model::openDownloads)
            }
            Divider()
            Text("Manual connection", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("If a phone can't find this PC by itself, add it there by one of these addresses.", color = colors.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Column {
                    model.addresses.ifEmpty { listOf("No network found") }.forEach {
                        Text(it, color = colors.mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        SectionLabel("About")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("DyskLink ${model.version}", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("${model.systemName} · by DyskLab", color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            Divider()
            UpdateCard(model)
        }
    }
}

private enum class HistoryFilter(val title: String) { All("All"), Received("Received"), Sent("Sent") }

/** Every file sent or received, from every device. */
@Composable
fun HistoryPage(model: DeskModel) {
    val colors = Dysk.colors
    val transfers by model.transfers.collectAsState()
    val phones by model.phones.collectAsState()
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    Page {
        PageHeader("File history", "Files sent and received on this PC. Received files are in ${model.downloadsPath}.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryFilter.entries.forEach { item ->
                    val count = when (item) {
                        HistoryFilter.All -> transfers.size
                        HistoryFilter.Received -> transfers.count { !it.outgoing }
                        HistoryFilter.Sent -> transfers.count { it.outgoing }
                    }
                    TabPill("${item.title} $count", item == filter) { filter = item }
                }
            }
            SecondaryButton("Open folder", icon = DyskIcons.Folder, onClick = model::openDownloads)
        }
        val list = transfers.filter {
            when (filter) {
                HistoryFilter.All -> true
                HistoryFilter.Received -> !it.outgoing
                HistoryFilter.Sent -> it.outgoing
            }
        }
        if (list.isEmpty()) EmptyCard("No files yet. Send one from a device's page, or from the phone.", DyskIcons.History)
        list.take(200).forEach { transfer ->
            TransferRow(transfer, phones.firstOrNull { it.id == transfer.peerId }?.name ?: "A removed device", model)
        }
    }
}

/** Settings, About: what the last check found, Check now, and for a newer release Download and Skip. */
@Composable
private fun UpdateCard(model: DeskModel) {
    val colors = Dysk.colors
    val update by model.update.collectAsState()
    val release = update.available
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(if (release != null) "DyskLink ${release.version} is available" else "Updates", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    release != null -> "Download opens the installer for this PC. Install it over this version; pairings and settings stay. Nothing installs by itself."
                    update.checking -> "Checking GitHub..."
                    update.message != null -> update.message!!
                    update.lastChecked != null -> "Checked ${java.text.SimpleDateFormat("d MMM, HH:mm").format(java.util.Date(update.lastChecked!!))}. DyskLink looks once a day."
                    else -> "DyskLink looks for a newer version once a day."
                },
                color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        if (release != null) {
            TextAction("Skip this version", colors.textSecondary, model::skipUpdate)
            Spacer(Modifier.width(Space.xs))
            PrimaryButton("Download", icon = DyskIcons.Download, onClick = model::downloadUpdate)
        } else {
            SecondaryButton(if (update.checking) "Checking..." else "Check now", enabled = !update.checking, icon = DyskIcons.Refresh, onClick = model::checkForUpdates)
        }
    }
}
