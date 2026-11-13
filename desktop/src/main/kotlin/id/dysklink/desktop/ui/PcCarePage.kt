package id.dysklink.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.AppKind
import id.dysklink.desktop.CareFiles
import id.dysklink.desktop.CareItem
import id.dysklink.desktop.CleanGroup
import id.dysklink.desktop.DuplicateGroup
import id.dysklink.desktop.KeepRule
import id.dysklink.desktop.PcApp
import id.dysklink.desktop.PcCareModel
import id.dysklink.desktop.PcCareModel.Confirm
import id.dysklink.desktop.PcCareModel.Tab
import id.dysklink.desktop.QuitTracker
import id.dysklink.desktop.StartupEntry
import id.dysklink.desktop.StartupKind
import java.io.File

/**
 * PC care, as Mac care: free up space, find duplicate files, uninstall apps with their leftovers, and choose what
 * starts at login. Everything removed goes to the Recycle Bin or the Trash. [scrolls] is off for snapshots.
 */
@Composable
fun PcCarePage(model: DeskModel, care: PcCareModel, scrolls: Boolean = true) {
    val colors = Dysk.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().let { if (scrolls) it.verticalScroll(rememberScrollState()) else it }.padding(horizontal = 28.dp).padding(top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    "Free up space and tidy ${model.deviceName}. Everything removed goes to the ${care.bin}, so you can put it back.",
                    color = colors.textSecondary, fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val icons = mapOf(Tab.Cleaner to DyskIcons.Sparkles, Tab.Duplicates to DyskIcons.Copy, Tab.Uninstall to DyskIcons.Trash, Tab.Startup to DyskIcons.Power)
                    Tab.entries.forEach { tab -> TabPill(tab.title, care.tab == tab, icons[tab]) { care.showTab(tab) } }
                }
                care.message?.let { MessageStrip(it, care.messageIsProblem) { care.message = null } }
                when (care.tab) {
                    Tab.Cleaner -> CleanerTab(model, care)
                    Tab.Duplicates -> DuplicatesTab(care)
                    Tab.Uninstall -> UninstallTab(model, care)
                    Tab.Startup -> StartupTab(model, care)
                }
            }
            Footer(care)
        }
        care.confirm?.let { ConfirmOverlay(care, it) }
    }
}

@Composable
private fun MessageStrip(text: String, problem: Boolean, onClose: () -> Unit) {
    val colors = Dysk.colors
    val tint = if (problem) colors.amber else colors.mint
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().background(tint.copy(alpha = 0.1f), shape).border(1.dp, tint.copy(alpha = 0.22f), shape).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DyskIcon(if (problem) DyskIcons.Warning else DyskIcons.Check, tint, 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), color = colors.textPrimary, fontSize = 12.5.sp, lineHeight = 17.sp)
        SmallIconButton(DyskIcons.Close, colors.textSecondary, onClose)
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { DyskIcon(icon, tint, 13.dp) }
}

@Composable
private fun Tick(on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Dysk.colors
    Box(
        Modifier.size(18.dp).alpha(if (enabled) 1f else 0.35f).clip(RoundedCornerShape(5.dp)).background(if (on) colors.mint else Color.Transparent)
            .border(1.5.dp, if (on) colors.mint else colors.textFaint, RoundedCornerShape(5.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (on) DyskIcon(DyskIcons.Check, colors.onAccent, 12.dp) }
}

@Composable
private fun Chip(title: String, on: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.height(28.dp).clip(CircleShape).background(if (on) colors.mint else colors.panelRaised)
            .border(1.dp, if (on) Color.Transparent else colors.stroke, CircleShape).clickable(onClick = onClick).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { DyskIcon(it, if (on) colors.onAccent else colors.textSecondary, 11.dp); Spacer(Modifier.width(5.dp)) }
        Text(title, color = if (on) colors.onAccent else colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Tag(text: String, color: Color = Dysk.colors.textSecondary) {
    Text(
        text, Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
        color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

/** The big card at the top of a tab: an icon, what it does or found, and its button. */
@Composable
private fun LeadCard(icon: ImageVector, tint: Color, title: String, detail: String, busy: Boolean = false, action: @Composable () -> Unit) {
    val colors = Dysk.colors
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, tint, 42.dp, tint.copy(alpha = 0.12f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(detail, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(Space.sm))
            if (busy) Text("Working…", color = colors.textFaint, fontSize = 12.sp) else action()
        }
    }
}

/** A file or folder with its tick, size, and a button that shows it in the file manager. */
@Composable
private fun CareRow(item: CareItem, on: Boolean, care: PcCareModel, enabled: Boolean = true, badge: String? = null, badgeColor: Color = Dysk.colors.mint, toggle: () -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = toggle).padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(on, enabled, toggle)
        Spacer(Modifier.width(10.dp))
        val folder = item.members != null || item.file.isDirectory || item.file.extension.isEmpty()
        DyskIcon(if (folder) DyskIcons.Folder else DyskIcons.File, colors.textSecondary, 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, Modifier.weight(1f, fill = false), color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                badge?.let { Spacer(Modifier.width(6.dp)); Tag(it, badgeColor) }
            }
            if (item.detail.isNotEmpty()) Text(item.detail, color = colors.textFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(CareFiles.bytes(item.size), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        SmallIconButton(DyskIcons.Search, colors.textSecondary) { care.reveal(item.file) }
    }
}

// Cleaner

@Composable
private fun CleanerTab(model: DeskModel, care: PcCareModel) {
    val colors = Dysk.colors
    when {
        care.isCleaning -> LeadCard(DyskIcons.Sparkles, colors.mint, "Looking through ${care.cleanStep ?: ""}…", "This takes a moment on a full disk.", busy = true) {}
        care.cleanScanned -> LeadCard(DyskIcons.Sparkles, colors.mint, "${CareFiles.bytes(care.cleanTotal)} found", "Caches, logs, and developer files are ticked. Your own files never are.") {
            SecondaryButton("Scan again", onClick = care::scanCleaner)
        }
        else -> LeadCard(
            DyskIcons.Sparkles, colors.mint, "Find space to free",
            "Caches, logs, developer files, installers, old downloads, and big files. Nothing is removed until you choose.",
        ) { PrimaryButton("Scan", onClick = care::scanCleaner) }
    }
    CleanGroup.entries.forEach { group ->
        val items = care.cleanGroups[group].orEmpty()
        if (items.isNotEmpty()) CleanGroupCard(group, items, care)
    }
    if (care.cleanScanned && care.cleanTotal == 0L) Body("Nothing to clean right now.")
    if (care.cleanHints.isNotEmpty()) {
        SectionLabel("Done outside DyskLink")
        care.cleanHints.forEach { HintCard(it, model) }
    }
}

private fun groupIcon(group: CleanGroup) = when (group) {
    CleanGroup.Caches -> DyskIcons.Box
    CleanGroup.Logs -> DyskIcons.FileText
    CleanGroup.Developer -> DyskIcons.Tool
    CleanGroup.Installers -> DyskIcons.Download
    CleanGroup.OldDownloads -> DyskIcons.History
    CleanGroup.Large -> DyskIcons.Drive
}

@Composable
private fun CleanGroupCard(group: CleanGroup, items: List<CareItem>, care: PcCareModel) {
    val colors = Dysk.colors
    val showsAll = group in care.expandedGroups
    val allOn = items.all { it.id in care.cleanSelected }
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 1.dp)) { Tick(allOn) { care.toggleGroup(group) } }
            Spacer(Modifier.width(12.dp))
            DyskIcon(groupIcon(group), colors.mint, 15.dp, Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(group.detail(care.windows), color = colors.textSecondary, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(Space.sm))
            Column(horizontalAlignment = Alignment.End) {
                Text(CareFiles.bytes(items.sumOf { it.size }), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${items.size} item${if (items.size == 1) "" else "s"}", color = colors.textFaint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(start = 26.dp)) {
            (if (showsAll) items else items.take(5)).forEach { item -> CareRow(item, item.id in care.cleanSelected, care) { care.toggleClean(item.id) } }
        }
        if (items.size > 5) {
            Box(Modifier.padding(start = 24.dp)) {
                TextAction(if (showsAll) "Show fewer" else "Show all ${items.size}", colors.mint) {
                    care.expandedGroups = if (showsAll) care.expandedGroups - group else care.expandedGroups + group
                }
            }
        }
    }
}

// Duplicates

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuplicatesTab(care: PcCareModel) {
    val colors = Dysk.colors
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.Copy, colors.violet, 42.dp, colors.violet.copy(alpha = 0.12f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (care.isFindingDups) "Checked ${care.dupChecked} files…" else "Find files saved more than once", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Files count as copies only when every byte is the same, not just the name.", color = colors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(Space.sm))
            if (care.isFindingDups || care.dupScanned) {
                SecondaryButton(if (care.isFindingDups) "Stop" else "Search again", enabled = care.dupFolders.isNotEmpty(), onClick = care::findDuplicates)
            } else {
                PrimaryButton("Search", enabled = care.dupFolders.isNotEmpty(), onClick = care::findDuplicates)
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Look in")
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            care.folderChoices.forEach { folder -> Chip(folder.name, folder in care.dupFolders) { care.toggleFolder(folder) } }
            care.dupFolders.filter { it !in care.folderChoices }.forEach { folder -> Chip(folder.name, true) { care.toggleFolder(folder) } }
            Chip("Add folder…", false, DyskIcons.Plus) { care.addFolders(pickFolders()) }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Skip files under", Modifier.align(Alignment.CenterVertically), color = colors.textSecondary, fontSize = 12.sp)
            listOf(100_000L to "100 KB", 1_000_000L to "1 MB", 10_000_000L to "10 MB").forEach { (value, label) -> Chip(label, care.dupMinimum == value) { care.dupMinimum = value } }
            Spacer(Modifier.width(10.dp))
            KeepRule.entries.forEach { rule -> Chip(rule.label, care.keepRule == rule) { care.setKeep(rule) } }
        }
    }
    if (care.dupScanned && care.dupGroups.isEmpty()) Body("No copies found.")
    if (care.dupGroups.isNotEmpty()) {
        SectionLabel("${care.dupGroups.size} files with copies · ${CareFiles.bytes(care.dupGroups.sumOf { it.wasted })} in extra copies")
        care.dupGroups.take(150).forEach { DuplicateCard(it, care) }
    }
}

private fun pickFolders(): List<File> = runCatching {
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = true
        dialogTitle = "Add folders to search"
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
}.getOrDefault(emptyList())

@Composable
private fun DuplicateCard(group: DuplicateGroup, care: PcCareModel) {
    val colors = Dysk.colors
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.files.first().name, Modifier.weight(1f), color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${group.files.size} copies · ${CareFiles.bytes(group.size)} each", color = colors.textSecondary, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(4.dp))
        group.files.forEach { file ->
            val on = file.id in care.dupSelected
            val othersKept = group.files.any { it.id != file.id && it.id !in care.dupSelected }
            val shown = file.copy(detail = CareFiles.shortPath(file.file.parentFile, care.places.home) + (file.date?.let { " · created " + CareFiles.ago(it) } ?: ""))
            CareRow(shown, on, care, enabled = on || othersKept, badge = if (on) null else "Keep") { care.toggleDuplicate(file, group) }
        }
    }
}

// Uninstall

@Composable
private fun UninstallTab(model: DeskModel, care: PcCareModel) {
    val colors = Dysk.colors
    val app = care.chosen
    if (app != null) {
        AppDetail(app, care)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InputField(care.appQuery, { care.appQuery = it }, "Search apps", Modifier.weight(1f))
        Chip("Name", !care.sortBySize) { care.sortBySize = false }
        Chip("Size", care.sortBySize) { care.sortBySize = true }
    }
    care.uninstallHints.forEach { HintCard(it, model) }
    Text(
        if (care.windows) "Windows' own parts and DyskLink itself aren't listed." else "Apps from your menu with what installed them. Parts of the system and DyskLink itself aren't listed.",
        color = colors.textFaint, fontSize = 11.5.sp,
    )
    val shown = care.shownApps
    GlassCard(padding = 6.dp) {
        shown.forEachIndexed { index, item ->
            AppRow(item) { care.choose(item) }
            if (index != shown.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(colors.stroke))
        }
        if (shown.isEmpty()) Text(if (care.isLoadingApps) "Loading apps…" else "No apps match.", Modifier.padding(14.dp), color = colors.textSecondary, fontSize = 13.sp)
    }
}

private fun appLine(app: PcApp): String = listOfNotNull(
    app.publisher.takeIf { it.isNotBlank() }, app.version.takeIf { it.isNotBlank() }?.let { "Version $it" },
    app.installDate?.let { "Installed " + it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) },
).joinToString(" · ").ifEmpty { app.kind.label }

@Composable
private fun AppRow(app: PcApp, onClick: () -> Unit) {
    val colors = Dysk.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconWell(DyskIcons.AppWindow, colors.textSecondary, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, Modifier.weight(1f, fill = false), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.kind != AppKind.Windows) { Spacer(Modifier.width(6.dp)); Tag(app.kind.label) }
            }
            Text(appLine(app), color = colors.textFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(app.size?.let(CareFiles::bytes) ?: "", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        DyskIcon(DyskIcons.ChevronRight, colors.textFaint, 13.dp)
    }
}

@Composable
private fun AppDetail(app: PcApp, care: PcCareModel) {
    val colors = Dysk.colors
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { care.choose(null) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        DyskIcon(DyskIcons.Back, colors.textSecondary, 13.dp)
        Spacer(Modifier.width(4.dp))
        Text("All apps", color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.AppWindow, colors.mint, 52.dp, colors.mint.copy(alpha = 0.1f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(appLine(app), color = colors.textFaint, fontSize = 11.5.sp)
                Text(
                    listOfNotNull(app.kind.label, app.packageId.takeIf { it.isNotBlank() && app.kind != AppKind.Store }, if (app.forAllUsers) "for all users" else "for you").joinToString(" · "),
                    color = colors.textFaint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            app.size?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(CareFiles.bytes(it), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("installed", color = colors.textFaint, fontSize = 11.sp)
                }
            }
        }
        if (care.chosenGone) {
            Spacer(Modifier.height(Space.sm))
            Text("${app.name} is no longer installed.", color = colors.mint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        } else {
            if (care.isRunning) {
                Spacer(Modifier.height(Space.sm))
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().background(colors.amber.copy(alpha = 0.1f), shape).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DyskIcon(DyskIcons.Warning, colors.amber, 15.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (care.quitPhase == QuitTracker.Phase.Stuck) "${app.name} didn't quit. It may be asking something in its window, or it's stuck."
                        else "${app.name} is open. Quit it before uninstalling.",
                        Modifier.weight(1f), color = colors.textPrimary, fontSize = 12.5.sp,
                    )
                    when (care.quitPhase) {
                        QuitTracker.Phase.Quitting -> Text("Quitting…", color = colors.textSecondary, fontSize = 12.sp)
                        QuitTracker.Phase.Stuck -> SecondaryButton("Force quit", tint = colors.coral) { care.quit(force = true) }
                        QuitTracker.Phase.Idle -> SecondaryButton("Quit ${app.name.take(24)}") { care.quit() }
                    }
                }
            }
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        care.uninstalling && app.kind != AppKind.AppImage -> "Waiting for the uninstaller to finish…"
                        app.kind == AppKind.Windows -> "Opens ${app.name}'s own uninstaller."
                        app.kind == AppKind.AppImage -> "The AppImage goes to the ${care.bin}."
                        app.kind == AppKind.Store -> "Removes it for your account, as Settings does."
                        else -> "Removes the ${app.kind.label}" + if (app.forAllUsers) ". Your password is asked." else "."
                    },
                    Modifier.weight(1f), color = colors.textSecondary, fontSize = 12.sp,
                )
                PrimaryButton(if (care.preparing) "Checking…" else "Uninstall", enabled = !care.isRunning && !care.uninstalling && !care.preparing, icon = DyskIcons.Trash, onClick = care::requestUninstall)
            }
        }
    }
    SectionLabel("Files it left behind")
    Text(
        "Settings, caches, and saved data in your own folders. Ticked ones carry the app's ID or its install folder, so they surely belong to it. Ones that only share its name are left for you to check.",
        color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
    )
    GlassCard(padding = 10.dp) {
        care.leftovers.forEach { leftover ->
            CareRow(leftover.item, leftover.id in care.leftoverSelected, care, badge = if (leftover.byId) null else "Same name", badgeColor = colors.amber) { care.toggleLeftover(leftover.id) }
        }
        if (care.leftovers.isEmpty()) Text("None found.", Modifier.padding(4.dp), color = colors.textSecondary, fontSize = 12.5.sp)
    }
}

// Startup

@Composable
private fun StartupTab(model: DeskModel, care: PcCareModel) {
    val colors = Dysk.colors
    care.startupError?.let { Text(it, color = colors.coral, fontSize = 12.sp) }
    val all = care.startup
    if (!care.startupLoaded) {
        LeadCard(DyskIcons.Power, colors.mint, "What starts with ${model.deviceName}", "Reading startup apps, tasks, and services…", busy = true) {}
        return
    }
    if (care.windows) {
        StartupSection("Your startup apps", "Apps set to open when you sign in. Turning one off keeps it from starting next time; turn it back on any time.",
            all.filter { it.kind in setOf(StartupKind.Run, StartupKind.RunOnce, StartupKind.Folder) && !it.adminOnly }, care)
        StartupSection("Scheduled tasks", "Tasks apps set to run when you sign in or the PC starts. You can turn off your own.",
            all.filter { it.kind == StartupKind.Task }, care)
        StartupSection("For all users", "Installed for every account. Changing these needs an administrator, so they're shown here to check.",
            all.filter { it.kind != StartupKind.Task && it.kind != StartupKind.Service && it.adminOnly }, care)
        StartupSection("Services", "Apps' services that start with Windows. Windows' own aren't listed.", all.filter { it.kind == StartupKind.Service }, care, collapsed = 5)
    } else {
        StartupSection("Autostart apps", "Apps that open when you log in. Turning one off writes Hidden=true in your own copy of its file, as GNOME Tweaks and KDE do.",
            all.filter { it.kind == StartupKind.Autostart && !it.desktopPart }, care)
        StartupSection("Background services", "systemd services for your account. Off stops it now and at the next login.",
            all.filter { it.kind == StartupKind.UserUnit && !it.desktopPart }, care)
        StartupSection("Part of the desktop", "The keyring, sound, input methods, and the like. Shown to check; turning them off can break the desktop.",
            all.filter { it.desktopPart && it.kind != StartupKind.SystemUnit }, care, collapsed = 5)
        StartupSection("System services", "Start with the PC for every user.", all.filter { it.kind == StartupKind.SystemUnit }, care, collapsed = 5)
    }
    care.startupHints.forEach { HintCard(it, model) }
}

@Composable
private fun StartupSection(title: String, detail: String, entries: List<StartupEntry>, care: PcCareModel, collapsed: Int = Int.MAX_VALUE) {
    val colors = Dysk.colors
    if (entries.isEmpty() && collapsed != Int.MAX_VALUE) return
    var all by remember(title) { mutableStateOf(false) }
    SectionLabel(title)
    Text(detail, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    GlassCard(padding = 6.dp) {
        val shown = if (all) entries else entries.take(collapsed)
        shown.forEachIndexed { index, entry ->
            StartupRow(entry, care)
            if (index != shown.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(colors.stroke))
        }
        if (entries.isEmpty()) Text("None.", Modifier.padding(14.dp), color = colors.textSecondary, fontSize = 13.sp)
        if (entries.size > collapsed) Box(Modifier.padding(start = 8.dp)) { TextAction(if (all) "Show fewer" else "Show all ${entries.size}", colors.mint) { all = !all } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StartupRow(entry: StartupEntry, care: PcCareModel) {
    val colors = Dysk.colors
    val busy = entry.id in care.startupBusy
    Row(Modifier.fillMaxWidth().alpha(if (busy) 0.55f else 1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconWell(if (entry.kind in setOf(StartupKind.Service, StartupKind.SystemUnit, StartupKind.UserUnit, StartupKind.Task)) DyskIcons.Gear else DyskIcons.AppWindow,
            if (entry.isOff) colors.textFaint else colors.textSecondary, 30.dp)
        Spacer(Modifier.width(12.dp))
        TooltipArea(
            tooltip = {
                Column(Modifier.widthIn(max = 520.dp).background(colors.panelRaised, RoundedCornerShape(8.dp)).border(1.dp, colors.stroke, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(entry.technical, color = colors.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(entry.command, color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (entry.location.isNotEmpty()) Text(entry.location, color = colors.textFaint, fontSize = 10.5.sp)
                }
            },
            modifier = Modifier.weight(1f), delayMillis = 400, tooltipPlacement = TooltipPlacement.CursorPoint(DpOffset(0.dp, 16.dp)),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.displayName, Modifier.weight(1f, fill = false), color = if (entry.isOff) colors.textSecondary else colors.textPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        entry.offInWindows -> Tag("Off in Windows")
                        entry.isOff -> Tag("Off")
                        entry.atBoot -> Tag("At startup", colors.sky)
                        else -> Tag("At login", colors.mint)
                    }
                    if (entry.adminOnly) Tag("Admin only", colors.amber)
                    if (entry.kind == StartupKind.RunOnce) Tag("Once")
                }
                Text(listOf(entry.purpose, entry.publisher).filter { it.isNotBlank() }.joinToString(" · "), color = colors.textSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.technical, color = colors.textFaint, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        entry.file?.let { file -> SmallIconButton(DyskIcons.Search, colors.textSecondary) { care.reveal(file) } }
        if (entry.editable) DyskSwitch(!entry.isOff, enabled = !busy) { care.setStartup(entry, it) }
    }
}

// The bar with the selection, and the question before anything moves

@Composable
private fun Footer(care: PcCareModel) {
    val (items, onClick) = when (care.tab) {
        Tab.Cleaner -> care.cleanSelection to { care.confirm = Confirm.Clean }
        Tab.Duplicates -> care.dupSelection to { care.confirm = Confirm.Duplicates }
        Tab.Uninstall -> (if (care.chosen != null && care.chosenGone) care.leftoverSelection else emptyList()) to { care.confirm = Confirm.Leftovers }
        Tab.Startup -> emptyList<CareItem>() to {}
    }
    if (items.isEmpty()) return
    val colors = Dysk.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.stroke))
    Row(Modifier.fillMaxWidth().background(colors.panel.copy(alpha = 0.95f)).padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(CareFiles.bytes(items.sumOf { it.size }), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${items.size} item${if (items.size == 1) "" else "s"} selected", color = colors.textSecondary, fontSize = 11.5.sp)
        }
        PrimaryButton("Move to ${care.bin}", icon = DyskIcons.Trash, onClick = onClick)
    }
}

@Composable
private fun ConfirmOverlay(care: PcCareModel, confirm: Confirm) {
    val colors = Dysk.colors
    val bin = care.bin
    val (title, detail, action) = when (confirm) {
        Confirm.Clean -> {
            val items = care.cleanSelection
            Triple("Move ${items.size} item${if (items.size == 1) "" else "s"} to the $bin?", "${CareFiles.bytes(items.sumOf { it.size })}. You can put them back from the $bin until you empty it.", "Move to $bin")
        }
        Confirm.Duplicates -> {
            val items = care.dupSelection
            Triple("Move ${items.size} cop${if (items.size == 1) "y" else "ies"} to the $bin?", "One copy of each file stays where it is. ${CareFiles.bytes(items.sumOf { it.size })} goes to the $bin.", "Move to $bin")
        }
        Confirm.Leftovers -> {
            val items = care.leftoverSelection
            Triple("Move ${items.size} leftover${if (items.size == 1) "" else "s"} to the $bin?", "${CareFiles.bytes(items.sumOf { it.size })} of settings and data ${care.chosen?.name ?: "the app"} left behind.", "Move to $bin")
        }
        is Confirm.Uninstall -> {
            val app = confirm.app
            val plan = confirm.plan
            val text = buildString {
                when {
                    plan.trash != null -> {
                        val extra = care.leftoverSelection
                        append("The AppImage" + (if (extra.isNotEmpty()) " and ${extra.size} of its files" else "") + " go to the $bin, ")
                        append(CareFiles.bytes((app.size ?: 0) + extra.sumOf { it.size }) + " in all.")
                    }
                    app.kind == AppKind.Windows -> append("${app.name}'s own uninstaller opens. Follow its steps; ${if (app.forAllUsers) "Windows may ask for permission first. " else ""}" +
                        "Afterwards, DyskLink offers the files it left behind.")
                    app.kind == AppKind.Store -> append("${app.name} is removed for your account" + (app.size?.let { ", freeing about ${CareFiles.bytes(it)}" } ?: "") + ".")
                    else -> append("${app.name} is removed" + (app.size?.let { ", about ${CareFiles.bytes(it)}" } ?: "") + "." + if (plan.needsPassword) " Your password is asked." else "")
                }
                if (plan.alsoRemoves.isNotEmpty()) {
                    append("\n\nThis also removes: " + plan.alsoRemoves.take(12).joinToString(", ") + if (plan.alsoRemoves.size > 12) ", and ${plan.alsoRemoves.size - 12} more." else ".")
                    if (id.dysklink.desktop.Uninstall.takesDesktop(plan.alsoRemoves)) append(" That includes part of your desktop: cancel unless you're sure.")
                }
            }
            Triple("Uninstall ${app.name}?", text, "Uninstall")
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (colors.isDark) 0.55f else 0.3f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(22.dp)
        Column(Modifier.width(440.dp).background(colors.panel, shape).border(1.dp, colors.stroke, shape).padding(26.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(detail, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SecondaryButton("Cancel") { care.confirm = null }
                PrimaryButton(action, icon = DyskIcons.Trash) {
                    care.confirm = null
                    when (confirm) {
                        Confirm.Clean -> care.cleanSelectedItems()
                        Confirm.Duplicates -> care.removeDuplicates()
                        Confirm.Leftovers -> care.trashLeftovers()
                        is Confirm.Uninstall -> care.uninstall(confirm.app, confirm.plan)
                    }
                }
            }
        }
    }
}
