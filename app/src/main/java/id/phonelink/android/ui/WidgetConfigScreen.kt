package id.phonelink.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.service.WidgetShortcut

/** Chooses up to four buttons for the shortcuts widget, in the order they're ticked. */
@Composable
fun ShortcutsConfigScreen(start: List<WidgetShortcut>, onSave: (List<WidgetShortcut>) -> Unit) {
    val chosen = remember { mutableStateListOf<WidgetShortcut>().apply { addAll(start) } }
    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            Text("Widget buttons", color = Palette.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Pick up to four. They show in the order you pick them.", color = Palette.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WidgetShortcut.entries.forEach { shortcut ->
                    val index = chosen.indexOf(shortcut)
                    val full = chosen.size >= 4 && index < 0
                    GlassCard(onClick = {
                        if (index >= 0) chosen.remove(shortcut) else if (!full) chosen.add(shortcut)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = index >= 0,
                                onCheckedChange = null,
                                enabled = !full,
                                colors = CheckboxDefaults.colors(checkedColor = Palette.Mint),
                            )
                            Text(
                                shortcut.label,
                                color = if (full) Palette.TextFaint else Palette.TextPrimary,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            if (index >= 0) Text("${index + 1}", color = Palette.Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (chosen.isEmpty()) "Pick at least one" else "Save", Modifier.fillMaxWidth()) {
                if (chosen.isNotEmpty()) onSave(chosen.toList())
            }
        }
    }
}
