package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.IslandMonitor
import id.dysklink.desktop.IslandPage
import id.dysklink.desktop.IslandSection
import id.dysklink.desktop.IslandService
import id.dysklink.desktop.IslandSettings
import id.dysklink.desktop.IslandWindow

/** The switch alone, for the Tools page: the rest is in Settings. */
@Composable
fun IslandSwitchCard(island: IslandService) {
    val settings by island.settings.collectAsState()
    GlassCard {
        SettingRow(
            "Show the island at the top of the screen",
            "Music with lyrics, files, the clipboard, codes from the phone, prayer times, the weather, and these tools, " +
                "in a dark island like the Mac notch. Choose its pages in Settings, The island.",
            settings.enabled, onChange = island::setEnabled,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IslandSettingsCard(model: DeskModel, island: IslandService, monitors: () -> List<IslandMonitor> = IslandWindow::monitors) {
    val colors = Dysk.colors
    val settings by island.settings.collectAsState()
    val hints by island.hints.collectAsState()
    val players by island.players.collectAsState()
    val lyricsOn by island.lyrics.isOn.collectAsState()
    val romaji by island.lyrics.romanizes.collectAsState()
    val places by island.places.view.collectAsState()
    GlassCard {
        SettingRow(
            "Show the island at the top of the screen",
            "A dark island hangs from the top edge, like the Mac notch. Point at it or click it to open it; Esc or moving away closes it. It hides while a game or video is full screen.",
            settings.enabled, onChange = island::setEnabled,
        )
        if (hints.isNotEmpty()) { Spacer(Modifier.height(8.dp)); HintList(hints, model) }
        Divider()
        SettingRow("Open with a click", "Off, it opens when the pointer rests on it.", settings.opensOnClick) { on -> island.update { it.copy(opensOnClick = on) } }
        Divider()
        SettingRow(
            "Show as a small window",
            "For desktops that don't let it sit at the top of the screen, such as some Wayland ones: the island opens in a small window you can move.",
            settings.asWindow,
        ) { on -> island.update { it.copy(asWindow = on) } }
        val screens = remember(settings.enabled) { monitors() }
        if (screens.size > 1) {
            Divider()
            Label("Screen")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = id.dysklink.desktop.IslandGeometry.pick(screens, settings.monitor)
                screens.forEach { m -> Chip(m.name, m.id == current?.id) { island.update { it.copy(monitor = if (m.primary) null else m.id) } } }
            }
        }

        Divider()
        Label("Pages")
        Text("Turn pages on or off and choose their order. Prayer times, the weather and notifications show once they're set up.", color = colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        val pages = island.pages(settings)
        settings.order.forEachIndexed { index, page ->
            val sections = IslandSection.entries.filter { it.page == page }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(page.title.ifEmpty { "Shelf" }, Modifier.width(120.dp), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sections.forEach { s ->
                        val shown = s !in settings.hidden
                        Chip(s.label, shown, enabled = !shown || pages.canHide(s) || !island.hasData(s)) { island.setShown(s, !shown) }
                    }
                }
                SmallArrow(up = true, enabled = index > 0) { island.move(page, -1) }
                Spacer(Modifier.width(4.dp))
                SmallArrow(up = false, enabled = index < settings.order.lastIndex) { island.move(page, 1) }
            }
        }

        Divider()
        Label("Music")
        Text(
            if (id.dysklink.desktop.Platform.isWindows) "Read from Windows' media controls, the same ones as the volume flyout: Spotify, browsers, Media Player, and most apps."
            else "Read over MPRIS, which Spotify, browsers, VLC, Rhythmbox and most Linux players offer.",
            color = colors.textSecondary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Whichever plays", settings.player == null) { island.update { it.copy(player = null) } }
            (players.map { it.id to it.name } + listOfNotNull(settings.player?.takeIf { p -> players.none { it.id == p } }?.let { it to it }))
                .forEach { (id, name) -> Chip(name, settings.player == id) { island.update { it.copy(player = id) } } }
        }
        Spacer(Modifier.height(8.dp))
        SettingRow("Lyrics", "Synced lyrics from lrclib.net beside the player. The last 50 songs found show again without the internet.", lyricsOn, onChange = island.lyrics::setOn)
        Spacer(Modifier.height(6.dp))
        SettingRow("Romaji for Japanese and Korean", "Kana and Hangul in Latin letters. Kanji stay as written.", romaji, enabled = lyricsOn, onChange = island.lyrics::setRomanizes)

        Divider()
        Label("From the phone")
        SettingRow("Codes from SMS and notifications", "A one-time code shows in the island with Copy. It's not kept in the clipboard history or sent to phones.", settings.codes) { on -> island.update { it.copy(codes = on) } }
        Spacer(Modifier.height(6.dp))
        SettingRow("Phone battery", "A note when the phone runs low, and when it's fully charged.", settings.phoneBattery) { on -> island.update { it.copy(phoneBattery = on) } }
        if (settings.phoneBattery) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Low at", color = colors.textSecondary, fontSize = 12.5.sp)
                IslandSettings.BATTERY_LIMITS.forEach { limit -> Chip("$limit%", settings.batteryLimit == limit) { island.update { it.copy(batteryLimit = limit) } } }
            }
        }

        Divider()
        PrayerSettings(island, places)
        Divider()
        WeatherSettings(island, places)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrayerSettings(island: IslandService, places: id.dysklink.desktop.PlacesView) {
    val colors = Dysk.colors
    var picking by remember { mutableStateOf(false) }
    Label("Prayer times")
    Text(
        (places.location?.let { "${it.city}, ${it.province}. " } ?: "") +
            "Times for cities in Indonesia from equran.id (Kemenag). A few months are kept here, so they work offline.",
        color = colors.textSecondary, fontSize = 12.sp,
    )
    places.prayerProblem?.let { Text(it, color = colors.amber, fontSize = 12.sp) }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(if (places.location == null) "Choose a city" else "Change city") { picking = !picking; if (picking) island.places.loadProvinces() }
        if (places.location != null) SecondaryButton("Remove") { island.places.setLocation(null) }
    }
    if (picking) {
        Spacer(Modifier.height(8.dp))
        if (places.provinces.isEmpty()) Text("Loading provinces...", color = colors.textSecondary, fontSize = 12.sp)
        val draft = places.draftProvince
        if (draft == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                places.provinces.forEach { p -> Chip(p, false) { island.places.chooseProvince(p) } }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("All provinces", colors.mint) { island.places.chooseProvince("") }
                Text(draft, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (places.cities.isEmpty()) Text("Loading cities...", color = colors.textSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                places.cities.forEach { c -> Chip(c, c == places.location?.city) { island.places.chooseCity(c); picking = false } }
            }
        }
    }
    if (places.location != null) {
        Spacer(Modifier.height(8.dp))
        SettingRow("Notify at prayer times", null, places.prayerNotify) { island.places.setPrayerNotify(it, places.prayerEarly) }
        Spacer(Modifier.height(6.dp))
        SettingRow("Also ten minutes before", null, places.prayerEarly, enabled = places.prayerNotify) { island.places.setPrayerNotify(places.prayerNotify, it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherSettings(island: IslandService, places: id.dysklink.desktop.PlacesView) {
    val colors = Dysk.colors
    var query by remember { mutableStateOf("") }
    Label("Weather")
    Text(
        (places.weatherPlace?.let { "${it.label}. " } ?: "") + "From Open-Meteo, no account needed. Only the city's position is sent.",
        color = colors.textSecondary, fontSize = 12.sp,
    )
    places.weatherProblem?.let { Text(it, color = colors.amber, fontSize = 12.sp) }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InputField(query, { query = it.take(60) }, "Search a city", Modifier.weight(1f), onSend = { island.places.search(query) })
        SecondaryButton("Search", enabled = query.isNotBlank()) { island.places.search(query) }
        if (places.weatherPlace != null) SecondaryButton("Remove") { island.places.chooseWeather(null) }
    }
    places.searchNote?.let { Text(it, color = colors.textSecondary, fontSize = 12.sp) }
    if (places.results.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            places.results.forEach { p -> Chip(p.label, p == places.weatherPlace) { island.places.chooseWeather(p); query = "" } }
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingRow("Fahrenheit", null, places.fahrenheit, onChange = island.places::setFahrenheit)
}

@Composable
private fun Label(text: String) {
    Text(text, color = Dysk.colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(50)
    Text(
        text,
        Modifier.alpha(if (enabled) 1f else 0.45f).clip(shape).background(if (selected) colors.mint.copy(alpha = 0.18f) else colors.panelRaised)
            .border(1.dp, if (selected) colors.mint.copy(alpha = 0.6f) else colors.stroke, shape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        color = if (selected) colors.text(colors.mint) else colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
    )
}

@Composable
private fun SmallArrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Dysk.colors
    androidx.compose.foundation.layout.Box(
        Modifier.size(26.dp).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).background(colors.panelRaised).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DyskIcon(if (up) DyskIcons.ArrowUp else DyskIcons.ArrowDown, colors.textPrimary, 12.dp) }
}
