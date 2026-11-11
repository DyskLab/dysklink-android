package id.phonelink.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import id.phonelink.android.core.Dictation
import id.phonelink.android.core.DictationLanguage
import id.phonelink.android.core.InputEvent

@Composable
fun DictationScreen(macName: String, connected: Boolean, onBack: () -> Unit, send: (InputEvent) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dictation", Context.MODE_PRIVATE) }
    var language by remember {
        mutableStateOf(runCatching { DictationLanguage.valueOf(prefs.getString("language", null) ?: "") }.getOrDefault(DictationLanguage.Indonesian))
    }
    var autoSend by remember { mutableStateOf(prefs.getBoolean("autoSend", true)) }
    var listening by remember { mutableStateOf(false) }
    var wanted by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var problem by remember { mutableStateOf<String?>(null) }
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }

    fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Long pauses are normal while writing, so the recognizer waits a little longer before finishing.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)

    val deliver by rememberUpdatedState { spoken: String ->
        if (autoSend && connected) {
            val text = Dictation.format(spoken, language, typed)
            Dictation.events(text).forEach(send)
            typed = (typed + text).takeLast(200)
        } else {
            draft += Dictation.format(spoken, language, draft)
        }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                problem = null
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                level = 0f
            }
            override fun onError(error: Int) {
                listening = false
                level = 0f
                partial = ""
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (quiet && wanted) {
                    // Silence just ends one round. Listening goes on until the user stops it.
                    recognizer.startListening(intent())
                    return
                }
                wanted = false
                problem = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognition needs the internet for this language. Check the connection, or download the language offline in Google's voice settings."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Try again in a moment."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "${language.label} isn't available on this phone. Add it in Google's voice settings."
                    else -> "Listening stopped (error $error). Tap the microphone to start again."
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                level = 0f
                partial = ""
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { deliver(it) }
                if (wanted) recognizer.startListening(intent())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            recognizer?.destroy()
        }
    }

    fun start() {
        wanted = true
        problem = null
        recognizer?.startListening(intent())
    }

    fun stop() {
        wanted = false
        recognizer?.stopListening()
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMic = granted
        if (granted) start() else problem = "DyskLink needs the microphone to hear you. Allow it in the app's settings."
    }

    FeatureScaffold("Voice typing to Mac", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DictationLanguage.entries.forEach { option ->
                        val on = option == language
                        Text(
                            option.label,
                            color = if (on) Palette.Ink else Palette.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) Palette.Mint else Palette.PanelRaised)
                                .border(1.dp, if (on) Palette.Mint else Palette.Stroke, CircleShape)
                                .clickable(enabled = !wanted) {
                                    language = option
                                    prefs.edit().putString("language", option.name).apply()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // The ring grows with the voice level, so it's clear the phone hears you.
                    Box(
                        Modifier
                            .size(132.dp)
                            .scale(if (listening) 0.85f + level * 0.3f else 0.85f)
                            .clip(CircleShape)
                            .background((if (wanted) Palette.Coral else Palette.Mint).copy(alpha = 0.16f)),
                    )
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(if (wanted) Palette.Coral else Palette.Mint)
                            .clickable(enabled = available) {
                                when {
                                    wanted -> stop()
                                    !hasMic -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    else -> start()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            if (wanted) FlatIcons.stop else FlatIcons.mic,
                            contentDescription = if (wanted) "Stop" else "Talk",
                            tint = Palette.Ink,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    when {
                        !available -> "This phone has no speech recognizer. Install or enable Google's speech services."
                        wanted && listening -> "Listening… tap to stop"
                        wanted -> "Starting…"
                        autoSend && !connected -> "Not connected to $macName. What you say is kept below."
                        autoSend -> "Tap and speak. Words are typed on $macName where the cursor is."
                        else -> "Tap and speak. Check the text, then send it to $macName."
                    },
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (partial.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        partial,
                        color = Palette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                problem?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            if (!autoSend || !connected || draft.isNotEmpty()) {
                GlassCard {
                    Text("TO SEND", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(5_000) },
                        placeholder = { Text("What you say appears here") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Clear", Modifier.weight(1f)) { draft = "" }
                        if (connected) {
                            PrimaryButton("Send to Mac", Modifier.weight(1f)) {
                                if (draft.isNotBlank()) {
                                    Dictation.events(draft).forEach(send)
                                    typed = draft.takeLast(200)
                                    draft = ""
                                }
                            }
                        }
                    }
                }
            }

            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Type as I speak", color = Palette.TextPrimary, fontSize = 14.sp)
                        Text("Off: collect the text here first, then send it.", color = Palette.TextFaint, fontSize = 12.sp)
                    }
                    Switch(checked = autoSend, onCheckedChange = {
                        autoSend = it
                        prefs.edit().putBoolean("autoSend", it).apply()
                    })
                }
                Spacer(Modifier.height(12.dp))
                Text("SPOKEN COMMANDS", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (language == DictationLanguage.Indonesian) {
                        "\"titik\" .   \"koma\" ,   \"tanda tanya\" ?   \"tanda seru\" !\n\"titik dua\" :   \"baris baru\" Enter   \"paragraf baru\""
                    } else {
                        "\"period\" .   \"comma\" ,   \"question mark\" ?   \"exclamation mark\" !\n\"colon\" :   \"new line\" Enter   \"new paragraph\""
                    },
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.PanelRaised)
                        .padding(12.dp),
                )
            }
            Text(
                "Speech is turned into text by the phone's speech recognizer (usually Google), which may send the audio " +
                    "to Google unless the language is downloaded for offline use. Only the text goes to $macName.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
