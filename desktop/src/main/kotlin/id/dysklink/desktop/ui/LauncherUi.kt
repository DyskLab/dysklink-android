package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.HotkeyPhase
import id.dysklink.desktop.LauncherItem
import id.dysklink.desktop.LauncherKey
import id.dysklink.desktop.LauncherKeys
import id.dysklink.desktop.LauncherKind
import id.dysklink.desktop.LauncherModel
import id.dysklink.desktop.LauncherModifier
import id.dysklink.desktop.LauncherService
import id.dysklink.desktop.LauncherState
import id.dysklink.desktop.LauncherStep
import id.dysklink.desktop.Layouts
import id.dysklink.desktop.TidyAction
import id.dysklink.desktop.TidyMods
import id.dysklink.desktop.TrayMenuConfig
import id.dysklink.desktop.TraySection
import kotlinx.coroutines.delay

/** The AWT key behind a Compose key event, as a Launcher key; null for keys it doesn't use. */
fun launcherKey(event: KeyEvent, paste: () -> String? = ::clipboardText): LauncherKey? {
    val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return null
    val ctrl = awt.isControlDown || awt.isMetaDown
    if (awt.id == java.awt.event.KeyEvent.KEY_TYPED) {
        val c = awt.keyChar
        if (ctrl || awt.isAltDown || c == java.awt.event.KeyEvent.CHAR_UNDEFINED || c.code < 32 || c.code == 127) return null
        return LauncherKey.Type(c.toString())
    }
    if (awt.id != java.awt.event.KeyEvent.KEY_PRESSED) return null
    return when (awt.keyCode) {
        java.awt.event.KeyEvent.VK_UP -> LauncherKey.Up
        java.awt.event.KeyEvent.VK_DOWN -> LauncherKey.Down
        java.awt.event.KeyEvent.VK_PAGE_UP -> LauncherKey.PageUp
        java.awt.event.KeyEvent.VK_PAGE_DOWN -> LauncherKey.PageDown
        java.awt.event.KeyEvent.VK_ENTER -> LauncherKey.Enter(if (ctrl) LauncherModifier.Reveal else if (awt.isShiftDown) LauncherModifier.CopyPath else LauncherModifier.None)
        java.awt.event.KeyEvent.VK_ESCAPE -> LauncherKey.Escape
        java.awt.event.KeyEvent.VK_BACK_SPACE -> if (ctrl) LauncherKey.Clear else LauncherKey.Backspace
        java.awt.event.KeyEvent.VK_V -> if (ctrl) paste()?.let { LauncherKey.Type(it) } else null
        else -> null
    }
}

private fun clipboardText(): String? = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
}.getOrNull()

private fun kindIcon(kind: LauncherKind): ImageVector = when (kind) {
    LauncherKind.Answer -> DyskIcons.Sparkles
    LauncherKind.Action -> DyskIcons.Bolt
    LauncherKind.App -> DyskIcons.AppWindow
    LauncherKind.Clip -> DyskIcons.Clipboard
    LauncherKind.File -> DyskIcons.FileText
    LauncherKind.Folder -> DyskIcons.Folder
    LauncherKind.Web -> DyskIcons.Link
    LauncherKind.Emoji -> DyskIcons.Sparkles
    LauncherKind.Setting -> DyskIcons.Gear
}

/** What Enter and its modifiers do for the selected row, under the list. */
private fun footer(item: LauncherItem?): String = when (item?.kind) {
    LauncherKind.App -> "Enter open  ·  Ctrl+Enter show in folder  ·  Shift+Enter copy path  ·  Esc close"
    LauncherKind.File, LauncherKind.Folder -> "Enter open  ·  Ctrl+Enter show in folder  ·  Shift+Enter copy path  ·  Esc close"
    LauncherKind.Emoji -> "${item.subtitle}  ·  Esc close"
    LauncherKind.Web -> "Enter open in the browser  ·  Shift+Enter copy the link  ·  Esc close"
    LauncherKind.Answer -> "Enter copy  ·  Esc close"
    LauncherKind.Clip -> "Enter paste  ·  Ctrl+Enter copy  ·  Esc close"
    else -> "Enter run  ·  Esc close  ·  :emoji  ·  f files  ·  d folders  ·  yt, w, gh web"
}

/**
 * The Launcher in its own small window: type to search, arrows to move, Enter to open, Esc to clear or close.
 * [initial] and [initialFiles] are for snapshots.
 */
@Composable
fun LauncherContent(launcher: LauncherModel, onClose: () -> Unit, initial: LauncherState = LauncherState(), initialFiles: List<LauncherItem>? = null) {
    var state by remember { mutableStateOf(initial) }
    val results = remember(state.query) { launcher.results(state.query) }
    var files by remember { mutableStateOf(initialFiles.orEmpty()) }
    LaunchedEffect(state.query) {
        val scope = results.files
        if (initialFiles != null) return@LaunchedEffect
        if (scope == null) { files = emptyList(); return@LaunchedEffect }
        delay(140)
        files = launcher.files(scope, results.fileLimit)
    }
    val list = results.items + files + results.web
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state.selection) { if (state.selection in list.indices) listState.animateScrollToItem(maxOf(0, state.selection - 4)) }
    val handle = { key: LauncherKey ->
        val (next, step) = LauncherKeys.reduce(state, key, list.size)
        state = next
        when (step) {
            LauncherStep.None -> Unit
            LauncherStep.Close -> onClose()
            is LauncherStep.Run -> list.getOrNull(step.index)?.let { onClose(); launcher.run(it, step.modifier) }
        }
    }
    val colors = Dysk.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxSize().background(colors.panel, shape).border(1.dp, colors.stroke, shape)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { event -> launcherKey(event)?.let { handle(it); true } ?: false },
    ) {
        Row(Modifier.padding(horizontal = 18.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(DyskIcons.Search, colors.textSecondary, 18.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.query.isEmpty()) "Search apps, files, folders, or type a sum  ·  :emoji" else state.query + "▏",
                Modifier.weight(1f), color = if (state.query.isEmpty()) colors.textFaint else colors.textPrimary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.stroke))
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.query.isEmpty()) "" else "No results", color = colors.textFaint, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { i, item -> "$i:${item.id}" }) { index, item ->
                    LauncherRow(item, index == state.selection) { state = state.copy(selection = index); handle(LauncherKey.Enter()) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.stroke))
        Text(footer(list.getOrNull(state.selection)), Modifier.padding(10.dp).align(Alignment.CenterHorizontally), color = colors.textFaint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun LauncherRow(item: LauncherItem, selected: Boolean, onClick: () -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) colors.panelRaised else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.emoji != null) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(item.emoji, fontSize = 21.sp) }
        } else {
            IconWell(kindIcon(item.kind), if (selected) colors.mint else colors.textSecondary, 32.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = if (item.kind == LauncherKind.Answer) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotEmpty() && item.kind != LauncherKind.Emoji) Text(item.subtitle, color = colors.textFaint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(item.kind.label, color = colors.textFaint, fontSize = 11.sp)
    }
}

// The Tools page: the Launcher and Window tidy

@Composable
fun LauncherCard(model: DeskModel) {
    val launcher = model.launcher ?: return
    val colors = Dysk.colors
    val view by launcher.view.collectAsState()
    var custom by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    GlassCard {
        CardTitle(DyskIcons.Search, "Launcher", colors.sky) { DyskSwitch(view.enabled) { launcher.setEnabled(it) } }
        Spacer(Modifier.height(Space.xs))
        Body("One shortcut opens a search box for apps, files and folders, sums and units, emoji, DyskLink's actions, and the web, like the Launcher on a Mac.")
        if (!view.enabled) return@GlassCard
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Shortcut", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        view.hotkey.backend == id.dysklink.desktop.HotkeyBackend.Command -> "This desktop needs a shortcut of its own; see below."
                        view.hotkey.phase == HotkeyPhase.Ready -> "Press ${view.combo} anywhere."
                        view.hotkey.phase == HotkeyPhase.Waiting -> "Waiting for the desktop to allow ${view.combo}."
                        else -> "${view.combo} isn't working yet; see below."
                    },
                    color = colors.textSecondary, fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LauncherService.CHOICES.forEach { c -> TabPill(c.label, !custom && view.combo == c.label) { custom = false; launcher.setCombo(c.label) } }
                TabPill(if (!custom && LauncherService.CHOICES.none { it.label == view.combo }) view.combo else "Custom", custom || LauncherService.CHOICES.none { it.label == view.combo }) {
                    custom = true; draft = view.combo
                }
            }
        }
        if (custom) {
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InputField(draft, { draft = it.take(40) }, "Such as Ctrl+Alt+K", Modifier.weight(1f), onSend = { launcher.setCombo(draft); custom = false })
                Spacer(Modifier.width(Space.xs))
                SecondaryButton("Use it") { launcher.setCombo(draft); custom = false }
            }
        }
        view.comboProblem?.let { Spacer(Modifier.height(6.dp)); Text(it, color = colors.coralText, fontSize = 12.sp) }
        Divider()
        Text("What to type", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        listOf(
            "App or file name" to "opens it; Ctrl+Enter shows it in its folder, Shift+Enter copies its path",
            "f report, d photos" to "files only, folders only",
            ":smile, e api" to "emoji, also by Indonesian words; add terang to gelap for a skin tone",
            "12*7+3, 10 km to mi" to "sums and units, Enter copies the answer",
            "yt, w, gh, maps, ddg" to "searches YouTube, Wikipedia, GitHub, Maps, DuckDuckGo; anything else searches Google",
        ).forEach { (what, does) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(what, Modifier.width(170.dp), color = colors.mintText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(does, color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${view.apps} apps found; ${if (view.files > 0) "${view.files} files and folders" else "files are being listed"} in your home folder, checked again every 15 minutes.",
                Modifier.weight(1f), color = colors.textFaint, fontSize = 11.5.sp,
            )
            SecondaryButton("Open the Launcher", icon = DyskIcons.Search) { launcher.open() }
        }
        HintList(view.hints, model)
    }
}

@Composable
fun WindowTidyCard(model: DeskModel) {
    val flow = model.windowTidy ?: return
    val colors = Dysk.colors
    val view by flow.collectAsState()
    LaunchedEffect(Unit) { model.tidyAction(TidyAction.Refresh) }
    GlassCard {
        CardTitle(DyskIcons.Grid, "Window tidy", colors.violet) { if (view.supported) DyskSwitch(view.enabled) { model.tidyAction(TidyAction.SetEnabled(it)) } }
        Spacer(Modifier.height(Space.xs))
        Body("Puts the window in front in a half, third or quarter of its screen with a shortcut, and saves and restores whole layouts for each set of monitors.")
        if (!view.supported) {
            HintList(view.hints, model)
            return@GlassCard
        }
        if (!view.enabled) return@GlassCard
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Keys", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("${view.mods.label} with the keys below, as on the Mac with Control+Option.", color = colors.textSecondary, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TidyMods.entries.forEach { m -> TabPill(m.label, m == view.mods) { model.tidyAction(TidyAction.SetMods(m)) } }
            }
        }
        Spacer(Modifier.height(Space.sm))
        val rows = view.shortcuts.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            rows.forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    pair.forEach { s ->
                        val taken = s.combo.label in view.hotkey.taken
                        Row(
                            Modifier.weight(1f).background(colors.panelRaised, RoundedCornerShape(9.dp)).clickable { model.tidyAction(TidyAction.Move(s.id)) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.label, Modifier.weight(1f), color = if (taken) colors.textFaint else colors.textPrimary, fontSize = 12.sp)
                            Text(s.combo.label.removePrefix(view.mods.label + "+").let { if (taken) "$it (taken)" else it }, color = if (taken) colors.amberText else colors.mintText, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Divider()
        Text("Saved layouts", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text("Now: ${view.monitors}. A layout goes back exactly on the monitors it was saved on; on others, each window takes the same share of its screen.", color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(Space.xs))
        var name by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputField(name, { name = it.take(40) }, "Name, such as Work", Modifier.weight(1f), onSend = { model.tidyAction(TidyAction.Save(name)); name = "" })
            Spacer(Modifier.width(Space.xs))
            PrimaryButton("Save current windows", icon = DyskIcons.Plus) { model.tidyAction(TidyAction.Save(name)); name = "" }
        }
        view.message?.let { Spacer(Modifier.height(6.dp)); Text(it, color = colors.textSecondary, fontSize = 12.sp) }
        Spacer(Modifier.height(Space.xs))
        Layouts.forMonitors(view.layouts, view.monitorKey).forEach { layout ->
            val here = layout.monitors == view.monitorKey
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).background(colors.panelRaised, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(layout.name, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${layout.windows.size} window${if (layout.windows.size == 1) "" else "s"}  ·  " + if (here) "these monitors" else "saved on ${if (layout.monitorCount == 1) "1 monitor" else "${layout.monitorCount} monitors"}, fitted to these",
                        color = if (here) colors.mintText else colors.textFaint, fontSize = 11.sp,
                    )
                }
                SecondaryButton("Restore") { model.tidyAction(TidyAction.Restore(layout.id)) }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(30.dp).clip(CircleShape).clickable { model.tidyAction(TidyAction.Delete(layout.id)) }, contentAlignment = Alignment.Center) {
                    DyskIcon(DyskIcons.Trash, colors.coral, 13.dp)
                }
            }
        }
        if (view.layouts.isEmpty()) Text("No layouts yet. Arrange your windows, then save them here.", color = colors.textFaint, fontSize = 12.sp)
        Spacer(Modifier.height(Space.xs))
        Text("From a desktop shortcut too: dysklink --window left-half (or right-third, maximize, next-screen, grid...).", color = colors.textFaint, fontSize = 11.5.sp)
        HintList(view.hints, model)
    }
}

// Settings: the tray menu

@Composable
fun TrayMenuCard(model: DeskModel) {
    val flow = model.trayMenu ?: return
    val colors = Dysk.colors
    val config by flow.collectAsState()
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("What the tray menu shows", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (model.hasTray) "Turn parts on or off and move them up or down. A part with nothing to show stays hidden by itself."
                    else "This desktop shows no tray icon now; the menu is used once it does.",
                    color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            TextAction("Reset", colors.textSecondary) { model.setTrayMenu(TrayMenuConfig()) }
        }
        Spacer(Modifier.height(Space.sm))
        config.order.forEachIndexed { index, section ->
            TrayRow(section, section in config.shown, index > 0, index < config.order.lastIndex,
                onToggle = { model.setTrayMenu(config.setShown(section, it)) },
                onMove = { model.setTrayMenu(config.move(section, it)) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quit DyskLink", Modifier.weight(1f), color = colors.textSecondary, fontSize = 12.5.sp)
            Text("Always at the bottom", color = colors.textFaint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TrayRow(section: TraySection, shown: Boolean, canUp: Boolean, canDown: Boolean, onToggle: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).background(colors.panelRaised, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DyskSwitch(shown, onChange = onToggle)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(section.label, color = if (shown) colors.textPrimary else colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            section.needs?.let { Text(it, color = colors.textFaint, fontSize = 10.5.sp) }
        }
        MoveButton(DyskIcons.ArrowUp, canUp) { onMove(-1) }
        MoveButton(DyskIcons.ArrowDown, canDown) { onMove(1) }
    }
}

@Composable
private fun MoveButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = Dysk.colors
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        DyskIcon(icon, if (enabled) colors.textSecondary else colors.textFaint.copy(alpha = 0.4f), 13.dp)
    }
}
