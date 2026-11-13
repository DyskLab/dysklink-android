package id.phonelink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Mosque
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.PrayerLocation
import id.phonelink.android.core.PrayerWeatherApi
import id.phonelink.android.core.WeatherReport
import id.phonelink.android.service.PrayerWeatherStore
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** A small card for the home screen. */
@Composable
fun PrayerHomeCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PrayerWeatherStore.refresh(context) }
    val state by PrayerWeatherStore.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val saved = state.saved
    val upcoming = state.schedule?.upcoming(Instant.ofEpochMilli(now))
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Same leading icon as the other tools in this tab.
            FlatIcon(androidx.compose.material.icons.Icons.Rounded.Mosque, Palette.Mint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (saved.location == null && saved.place == null) {
                    Text("Prayer times & weather", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose a city to see today's times, get a reminder, and add a widget.",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                } else {
                    Text(
                        upcoming?.let { "${it.prayer.label} ${it.time.format(clockFormat)}" } ?: "Prayer times",
                        color = Palette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(
                            upcoming?.let { "in " + PrayerWeatherApi.countdown(now, it.time.toInstant().toEpochMilli()) },
                            saved.location?.label,
                        ).joinToString(" · ").ifEmpty { "Choose a city for prayer times" },
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            saved.report?.let { report ->
                androidx.compose.material3.Icon(FlatIcons.weather(report.code, report.isDay), contentDescription = null, tint = Palette.Amber, modifier = Modifier.padding(end = 8.dp).size(26.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${report.temperature.toInt()}°",
                        color = Palette.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(PrayerWeatherApi.describe(report.code), color = Palette.TextFaint, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun PrayerScreen(onBack: () -> Unit, openExactAlarms: () -> Unit) {
    val context = LocalContext.current
    val state by PrayerWeatherStore.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var choosingCity by remember { mutableStateOf(false) }
    var province by remember { mutableStateOf<String?>(null) }
    var choosingPlace by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        PrayerWeatherStore.refresh(context)
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val saved = state.saved
    val schedule = state.schedule
    val upcoming = schedule?.upcoming(Instant.ofEpochMilli(now))
    val today = schedule?.today(Instant.ofEpochMilli(now))

    FeatureScaffold("Prayer times & weather", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Prayer times
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PRAYER TIMES", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(saved.location?.label ?: "No city yet", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    SmallLink(if (saved.location == null) "Choose city" else "Change") {
                        choosingCity = !choosingCity
                        province = null
                        if (choosingCity) PrayerWeatherStore.loadProvinces()
                    }
                }
                if (choosingCity) {
                    Spacer(Modifier.height(12.dp))
                    CityPicker(
                        provinces = state.provinces,
                        cities = state.cities,
                        province = province,
                        onProvince = {
                            province = it
                            PrayerWeatherStore.loadCities(it)
                        },
                        onCity = { city ->
                            province?.let { PrayerWeatherStore.setLocation(context, PrayerLocation(it, city)) }
                            choosingCity = false
                        },
                    )
                } else if (upcoming != null && today != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(upcoming.prayer.label, color = Palette.MintText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(upcoming.time.format(clockFormat), color = Palette.TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "  in " + PrayerWeatherApi.countdown(now, upcoming.time.toInstant().toEpochMilli()),
                            color = Palette.TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        today.forEach { time ->
                            val next = time.prayer == upcoming.prayer && time.time == upcoming.time
                            val past = time.time.toInstant().toEpochMilli() <= now
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (next) Palette.Mint.copy(alpha = 0.16f) else Palette.PanelRaised)
                                    .border(1.dp, if (next) Palette.Mint.copy(alpha = 0.5f) else Palette.Stroke, RoundedCornerShape(14.dp))
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    time.prayer.label,
                                    color = if (next) Palette.MintText else if (past) Palette.TextFaint else Palette.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    time.time.format(clockFormat),
                                    color = if (past && !next) Palette.TextFaint else Palette.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else if (saved.location != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.loading) "Loading this month's times…" else state.problem ?: "Times aren't loaded yet.",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                if (saved.location != null && !choosingCity) {
                    Spacer(Modifier.height(14.dp))
                    SwitchRow("Notify at prayer times", saved.notifies) {
                        PrayerWeatherStore.setNotifies(context, it, saved.early)
                    }
                    if (saved.notifies) {
                        SwitchRow("10 minutes before instead", saved.early) {
                            PrayerWeatherStore.setNotifies(context, true, it)
                        }
                        if (!PrayerWeatherStore.canAlarmExactly(context)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Android may send these a few minutes late. Allow alarms and reminders to get them on time.",
                                color = Palette.AmberText,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                            SmallLink("Allow alarms and reminders", onClick = openExactAlarms)
                        }
                    }
                }
            }

            // Weather
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("WEATHER", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(saved.place?.label ?: "No city yet", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    SmallLink(if (saved.place == null) "Choose city" else "Change") { choosingPlace = !choosingPlace }
                }
                if (choosingPlace) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(60) },
                        placeholder = { Text("City name, such as Bandung") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { PrayerWeatherStore.search(query) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LaunchedEffect(query) {
                        delay(400)
                        PrayerWeatherStore.search(query)
                    }
                    state.searchNote?.let { Text(it, color = Palette.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    state.places.forEach { place ->
                        Text(
                            place.label,
                            color = Palette.TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    PrayerWeatherStore.setPlace(context, place)
                                    choosingPlace = false
                                    query = ""
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                } else {
                    saved.report?.let { WeatherBody(it) }
                        ?: saved.place?.let {
                            Text(
                                if (state.loading) "Loading…" else state.problem ?: "Not loaded yet.",
                                color = Palette.TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                }
            }

            Text(
                "Prayer times come from equran.id for cities in Indonesia, and a month is kept on this phone so they work offline. " +
                    "Weather comes from Open-Meteo, which only receives the city's coordinates. Long-press the home screen and add " +
                    "the DyskLink prayer widget to see the next prayer at a glance.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WeatherBody(report: WeatherReport) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlatIcon(FlatIcons.weather(report.code, report.isDay), Palette.Amber, size = 56.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("${report.temperature.toInt()}°", color = Palette.TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(PrayerWeatherApi.describe(report.code), color = Palette.TextSecondary, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        report.days.forEachIndexed { index, day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.PanelRaised)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (index == 0) "Today" else date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: day.date,
                    color = Palette.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                androidx.compose.material3.Icon(FlatIcons.weather(day.code), contentDescription = PrayerWeatherApi.describe(day.code), tint = Palette.Amber, modifier = Modifier.padding(vertical = 4.dp).size(22.dp))
                Text("${day.high.toInt()}° / ${day.low.toInt()}°", color = Palette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (day.rainChance > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(FlatIcons.rain, contentDescription = "Rain", tint = Palette.Sky, modifier = Modifier.size(11.dp))
                        Text(" ${day.rainChance}%", color = Palette.SkyText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CityPicker(
    provinces: List<String>,
    cities: List<String>,
    province: String?,
    onProvince: (String) -> Unit,
    onCity: (String) -> Unit,
) {
    val items = if (province == null) provinces else cities
    Text(
        if (province == null) "Choose a province" else "Choose a city in $province",
        color = Palette.TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(6.dp))
    if (items.isEmpty()) {
        Text("Loading…", color = Palette.TextFaint, fontSize = 13.sp)
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.PanelRaised),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            items.forEach { name ->
                Text(
                    name,
                    color = Palette.TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (province == null) onProvince(name) else onCity(name) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Palette.MintText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
