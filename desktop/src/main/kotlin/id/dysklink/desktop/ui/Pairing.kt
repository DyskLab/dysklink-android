package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.HubPairing

/** Pairing over the whole window, as the Mac's PairingOverlay: show a code, type one, or say the other side is too old. */
@Composable
fun PairingOverlay(model: DeskModel, pairing: HubPairing) {
    val colors = Dysk.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (colors.isDark) 0.62f else 0.35f))
            // Clicks behind the card go nowhere.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.width(440.dp).background(colors.panel, shape).border(1.dp, colors.stroke, shape).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoMark(46.dp)
            Spacer(Modifier.height(18.dp))
            when {
                pairing.outdated -> {
                    Heading("Can't pair with ${pairing.phoneName} yet", pairing.error ?: "Update DyskLink on ${pairing.phoneName} to pair.")
                    Spacer(Modifier.height(Space.lg))
                    SecondaryButton("Close", onClick = model::rejectPairing)
                }
                pairing.typing -> TypeCode(model, pairing)
                else -> ShowCode(model, pairing)
            }
        }
    }
}

@Composable
private fun Heading(title: String, detail: String) {
    val colors = Dysk.colors
    Text(title, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(5.dp))
    Text(detail, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
}

@Composable
private fun ErrorLine(text: String) {
    Spacer(Modifier.height(Space.sm))
    Text(text, color = Dysk.colors.coral, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
}

@Composable
private fun Waiting(text: String) {
    val colors = Dysk.colors
    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = colors.mint, strokeWidth = 2.dp, trackColor = colors.panelRaised)
        Spacer(Modifier.width(Space.xs))
        Text(text, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** A phone is pairing: its user types the code shown here. */
@Composable
private fun ShowCode(model: DeskModel, pairing: HubPairing) {
    val colors = Dysk.colors
    Heading("Pair with ${pairing.phoneName}", "Type this code on ${pairing.phoneName}. It works only for this pairing.")
    Spacer(Modifier.height(Space.lg))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pairing.code.forEachIndexed { index, digit ->
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.padding(start = if (index == 3) 8.dp else 0.dp).size(48.dp, 62.dp)
                    .background(colors.panelRaised, shape).border(1.dp, colors.mint.copy(alpha = 0.3f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(digit.toString(), color = colors.textPrimary, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    pairing.error?.let { ErrorLine(it) }
    Spacer(Modifier.height(Space.sm))
    Waiting("Waiting for the code…")
    Spacer(Modifier.height(Space.xs))
    SecondaryButton("Cancel", onClick = model::rejectPairing)
}

/** Pairing with another computer: its code is typed here. */
@Composable
private fun TypeCode(model: DeskModel, pairing: HubPairing) {
    val colors = Dysk.colors
    var code by remember(pairing.phoneId) { mutableStateOf("") }
    // Each code goes once: typing a seventh digit or pressing Pair again mustn't use up another try.
    var sent by remember(pairing.phoneId) { mutableStateOf<String?>(null) }
    // A wrong code clears the field for the next try.
    LaunchedEffect(pairing.phoneId, pairing.attempts) {
        if (pairing.attempts > 0) { code = ""; sent = null }
    }
    val canSend = code.length == 6 && code != sent && !pairing.localAccepted
    val submit = { if (canSend) { sent = code; model.submitCode(code) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(pairing.phoneId) { runCatching { focus.requestFocus() } }

    Heading("Enter the code", "Type the 6-digit code that DyskLink shows on ${pairing.phoneName}.")
    Spacer(Modifier.height(Space.lg))
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        code,
        { typed ->
            val digits = typed.filter(Char::isDigit).take(6)
            if (digits != code) {
                code = digits
                if (digits.length == 6 && digits != sent && !pairing.localAccepted) { sent = digits; model.submitCode(digits) }
            }
        },
        Modifier.width(220.dp).focusRequester(focus),
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, letterSpacing = 6.sp),
        cursorBrush = SolidColor(colors.mint),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        decorationBox = { field ->
            Box(
                Modifier.background(colors.ink.copy(alpha = if (colors.isDark) 0.65f else 0.04f), shape)
                    .border(1.5.dp, colors.mint.copy(alpha = 0.5f), shape).padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (code.isEmpty()) {
                    Text("000000", color = colors.textFaint, fontSize = 28.sp, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                }
                field()
            }
        },
    )
    pairing.error?.let { ErrorLine(it) }
    Spacer(Modifier.height(Space.md))
    if (pairing.localAccepted) {
        Waiting("Checking the code…")
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", onClick = model::rejectPairing)
            PrimaryButton("Pair", enabled = canSend, onClick = submit)
        }
    }
}
