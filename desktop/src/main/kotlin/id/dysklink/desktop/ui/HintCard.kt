package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.HintTone
import id.dysklink.desktop.HostHint
import kotlinx.coroutines.delay

/**
 * Something this PC or the phone needs from outside DyskLink, where the feature is set up: what's wrong, what to
 * do, and a command to copy or a button. One look for every such hint, as the USB card's.
 */
@Composable
fun HintCard(hint: HostHint, model: DeskModel, color: Color = toneColor(hint.tone)) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), shape).border(1.dp, color.copy(alpha = 0.22f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            DyskIcon(DyskIcons.Info, color, 15.dp, Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(hint.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(hint.text, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
            hint.link?.let { link ->
                Spacer(Modifier.width(Space.sm))
                SecondaryButton(link.label, icon = DyskIcons.PopOut) { model.openLink(link.url) }
            }
            hint.action?.let { action ->
                Spacer(Modifier.width(Space.sm))
                SecondaryButton(action.label) { model.hintAction(action.id) }
            }
        }
        hint.command?.let { command -> CommandToCopy(command, model) }
        hint.moreCommands.forEach { command -> CommandToCopy(command, model) }
    }
}

/** Amber for something to fix, sky for something to know, mint for a state that's fine. */
@Composable
fun toneColor(tone: HintTone): Color = when (tone) {
    HintTone.Warn -> Dysk.colors.amber
    HintTone.Info -> Dysk.colors.sky
    HintTone.Ok -> Dysk.colors.mint
}

/** A command in a terminal's font, selectable, with Copy. */
@Composable
private fun CommandToCopy(command: String, model: DeskModel) {
    val colors = Dysk.colors
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_500); copied = false } }
    Spacer(Modifier.height(Space.xs))
    Row(
        Modifier.padding(start = 25.dp).fillMaxWidth().background(colors.panelRaised, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(command, color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        TextAction(if (copied) "Copied" else "Copy", colors.mint) { model.copyText(command); copied = true }
    }
}

/** Hints one under the other, with the space a setting row leaves above them. */
@Composable
fun HintList(hints: List<HostHint>, model: DeskModel) {
    hints.forEach { hint ->
        Spacer(Modifier.height(Space.sm))
        HintCard(hint, model)
    }
}
