package com.zhuoying.ariaedgevlm

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/** AUTO_ATLAS_BLOCK8: on-device PCM → continuous Vosk → wake gate → existing executor. */
@Composable
internal fun PhoneAudioBridgeCard(
    runLiveQuestion: PhoneLiveQuestionExecutor,
    onAutoChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val ingress = remember { PhoneAudioIngress() }
    val manualSpeech = remember { PhoneVoskSpeech(context.applicationContext) }
    val autoSpeech = remember { PhoneAutoAtlas(context.applicationContext) }
    val audioConsumer = remember { { pcm: ByteArray -> autoSpeech.feed(pcm) } }
    // galaxy_yamnet_block9: independent PCM consumer, no impact on Vosk wake detection.
    val safety = remember { PhoneYamnetSafety(context.applicationContext) }
    // Block 10A: capture samples from the existing on-phone Aria PCM buffer.
    // No identity model, no owner gating, and no new microphones/network calls.
    val enrollment = remember { PhoneVoiceEnrollment(context.applicationContext) }
    val verifier = remember { PhoneCamPlusVerifier(context.applicationContext) }
    val recentConversation = remember { PhoneConversationMemory.get(context.applicationContext) }
    var showRecentConversation by remember { mutableStateOf(false) }
    // BLOCK12_LONGTERM_UI: explicit notes never change CAM++ or YAMNet.
    val explicitMemory = remember { PhoneLongTermMemory.get(context.applicationContext) }
    var memoryText by remember { mutableStateOf("remember i put my keys on the kitchen table") }
    var memoryTestOutput by remember { mutableStateOf("") }
    var showExplicitNotes by remember { mutableStateOf(false) }
    var confirmingClearNotes by remember { mutableStateOf(false) }
    // VOICE_GATE_BLOCK10C: start in shadow mode; require explicit owner-only opt-in.
    val gatePrefs = remember { context.applicationContext.getSharedPreferences("aria_voice_gate_10c", 0) }
    var ownerOnly by remember { mutableStateOf(gatePrefs.getBoolean("owner_only", false)) }
    val ownerOnlyCurrent by rememberUpdatedState(ownerOnly)
    var lastGateResult by remember { mutableStateOf("No automatic speaker checks yet.") }
    val voiceThreshold = 0.50f  // PILOT ONLY: reported owner ~0.5-0.6, others ~0.2-0.3.
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val safetyConsumer = remember { { pcm: ByteArray -> safety.feed(pcm) } }
    val safetyPrefs = remember { context.applicationContext.getSharedPreferences("phone_yamnet_settings", 0) }
    var safetyEnabled by remember { mutableStateOf(safetyPrefs.getBoolean("enabled", false)) }
    val runCurrent by rememberUpdatedState(runLiveQuestion)
    val prefs = remember { context.applicationContext.getSharedPreferences("aria_auto_voice", 0) }
    var autoEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var transcript by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var queuedQuestion by remember { mutableStateOf<String?>(null) }
    var speakReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context.applicationContext) { code ->
            speakReady = (code == TextToSpeech.SUCCESS)
        }
    }

    // Launch the existing phone executor; don't send commands to Mac for processing.
    fun dispatchQuestion(question: String) {
        if (busy) {
            queuedQuestion = question
            state = "New question queued while E4B finishes (GPU inference is not cancellable yet)."
            return
        }
        busy = true
        result = ""
        state = "Processing on Galaxy: $question"
        runCurrent(question) { answer ->
            result = answer
            busy = false
            val next = queuedQuestion
            queuedQuestion = null
            if (next != null && autoEnabled) {
                state = "Processing newer Hey Atlas question..."
                // Defer one main-loop cycle, avoiding reentrancy into the executor.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    dispatchQuestion(next)
                }
            } else {
                state = "Ready / listening for Hey Atlas"
                if (speakReady && !answer.startsWith("ERROR:") &&
                    !answer.contains("not connected") && !answer.contains("No received frames")) {
                    // Do not read route/diagnostic headers aloud.
                    val spoken = answer.substringAfter("\n\n", answer).take(1100)
                    tts.language = Locale.US
                    tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "aria-auto-answer")
                }
            }
        }
    }

    // First-time opt-in via the toggle. On future app/dashboard openings, auto-resume.
    LaunchedEffect(autoEnabled) {
        if (autoEnabled) {
            ingress.start()
            if (!ingress.running) {
                state = "ERROR: ${ingress.lastError}"
            } else {
                ingress.addPcmObserver(audioConsumer)
                autoSpeech.start(
                    onStatus = { state = it },
                    onWake = {
                        tts.stop() // audible barge-in during playback
                        state = "Hey Atlas heard; waiting for complete question..."
                    },
                    onCommand = { raw, question ->
                        transcript = raw
                        tts.stop()
                        // BLOCK11B: clear stale on-screen answer even if speaker check rejects.
                        if (!busy) result = ""
                        state = "Checking voice for: $question"
                        // Snapshot at ASR dispatch time; do not collect speech after the fact.
                        // SHADOW: dispatch immediately and log a score. OWNER_ONLY: wait for CAM++.
                        val strict = ownerOnlyCurrent
                        val wave = try { ingress.recentPcm(5) } catch (e: Exception) {
                            lastGateResult = "Voice check: no fresh Aria audio (${e.message})"
                            if (strict) state = "Hey Atlas rejected: missing microphone evidence."
                            else dispatchQuestion(question) // shadow mode never blocks
                            null
                        }
                        if (wave != null) {
                            if (!strict) dispatchQuestion(question)
                            verifier.scoreCommandPcm(wave) { score, error ->
                                if (alive.get()) {
                                    if (score == null) {
                                        lastGateResult = "CAM++ unavailable: ${error ?: "unknown"}"
                                        if (strict) state = "Owner-only blocked command: CAM++ unavailable."
                                    } else {
                                        val passed = score >= voiceThreshold
                                        lastGateResult = "CAM++ score %.3f / pilot %.2f · %s (%s)".format(
                                            Locale.US, score, voiceThreshold,
                                            if (passed) "owner-like" else "other/uncertain",
                                            if (strict) "OWNER-ONLY" else "SHADOW")
                                        if (strict) {
                                            if (passed && autoEnabled) dispatchQuestion(question)
                                            else {
                                                state = "Owner-only blocked a command (score %.3f)".format(Locale.US, score)
                                                if (!busy) result = "Command not executed: speaker verification below threshold."
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onError = { state = it }
                )
            }
        } else {
            ingress.removePcmObserver(audioConsumer)
            autoSpeech.stop()
        }
    }
    LaunchedEffect(safetyEnabled) {
        if (safetyEnabled) {
            ingress.start()
            ingress.addPcmObserver(safetyConsumer)
            safety.start { key, label, confidence ->
                PhoneLocalAlerts.record(context.applicationContext, key, label, confidence)
            }
        } else {
            ingress.removePcmObserver(safetyConsumer)
            safety.stop()
        }
    }
    DisposableEffect(Unit) {
        alive.set(true)
        onDispose {
            alive.set(false)
            ingress.removePcmObserver(safetyConsumer)
            safety.stop()
            ingress.removePcmObserver(audioConsumer)
            autoSpeech.stop()
            manualSpeech.cancel()
            ingress.stop()
            tts.stop()
            tts.shutdown()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Block 8 · Automatic Hey Atlas (foreground pilot)", style = MaterialTheme.typography.titleMedium)
        Text("Aria PCM → phone Vosk → WHEN → WHICH → E4B. Enable once, then leave " +
            "the dashboard open. Ordinary speech without Hey Atlas is ignored. " +
            "No Galaxy microphone or cloud ASR is used.",
            style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Automatic listening: ${if (autoEnabled) "ON" else "OFF"}")
            Switch(checked = autoEnabled, onCheckedChange = { on ->
                autoEnabled = on
                prefs.edit().putBoolean("enabled", on).apply()
                onAutoChanged(on)
                state = if (on) "Starting phone audio and Vosk..." else "Automatic listening stopped"
            })
        }
        Text("Audio receiver: ${if (ingress.running) "ON" else "OFF"} · " +
            "${ingress.receivedChunks} chunks · ${"%.1f".format(Locale.US, ingress.bufferedSeconds)}s " +
            "· RMS ${"%.3f".format(Locale.US, ingress.level)}")
        if (ingress.lastError.isNotBlank()) {
            Text(ingress.lastError, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            enabled = !autoEnabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (ingress.running) ingress.stop() else ingress.start() }
        ) {
            Text(if (ingress.running) "Stop audio receiver" else "Start audio receiver (manual)")
        }
        // Keep the Block 7B one-shot path as an ASR troubleshooting fallback.
        Button(enabled = !busy && !autoEnabled, modifier = Modifier.fillMaxWidth(), onClick = {
            try {
                state = "Vosk decoding the last six seconds..."
                val captured = ingress.recentPcm(6)
                busy = true
                manualSpeech.start(captured) { raw, error ->
                    busy = false
                    if (error != null) {
                        state = error
                    } else {
                        transcript = raw.orEmpty()
                        val question = PhoneAriaSpeech.activatedQuestion(transcript)
                        if (question == null) state = "WHEN ignored: no clear Hey Atlas + question."
                        else dispatchQuestion(question)
                    }
                }
            } catch (e: Exception) {
                busy = false
                state = "ERROR: ${e.message}"
            }
        }) { Text("Manual six-second ASR test (auto OFF)") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            manualSpeech.cancel()
            tts.stop()
            queuedQuestion = null
            state = "Speech stopped. Auto listener ${if (autoEnabled) "still active" else "off"}."
        }) { Text("Stop speech / clear queued question") }
        HorizontalDivider()
        Text("Block 9 · Safety Sound Monitoring (YAMNet)",
            style = MaterialTheme.typography.titleMedium)
        Text("Experimental on-phone detection from Aria audio. No Hey Atlas needed. " +
            "Not a substitute for certified smoke or emergency alarms.",
            style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Monitor dangerous sounds: ${if (safetyEnabled) "ON" else "OFF"}")
            Switch(checked = safetyEnabled, onCheckedChange = { enabled ->
                safetyEnabled = enabled
                safetyPrefs.edit().putBoolean("enabled", enabled).apply()
            })
        }
        Text("YAMNet: ${safety.status} · ${safety.windows} windows")
        Text("Last classification: ${safety.lastTopClass}",
            style = MaterialTheme.typography.bodySmall)
        Text("Local safety events: ${PhoneLocalAlerts.alerts.size}. " +
            "Open the existing Alert History page to review them.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Block 10A · Voiceprint Enrollment (capture only)",
            style = MaterialTheme.typography.titleMedium)
        Text("Six explicit recordings from the Aria microphone, kept in this phone's " +
            "private no-backup storage. The CAM++ verification model is NOT installed " +
            "yet; owner-only command blocking remains OFF. Hey Atlas and safety " +
            "monitoring keep working normally.",
            style = MaterialTheme.typography.bodySmall)
        Text("Captured: ${enrollment.recorded}/6 phrases" +
            if (enrollment.validationAvailable) " · validation clip ready" else "")
        val phrase = enrollment.nextPrompt()
        if (phrase != null) {
            Text("Read aloud: \"$phrase\"")
            Text("Speak for about 3–4 seconds, then immediately tap Capture. " +
                "Do not say Hey Atlas during enrollment.",
                style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                enrollment.captureTake(ingress)
            }) { Text("Capture enrollment phrase ${enrollment.recorded + 1}/6") }
        } else {
            Text("All six enrollment samples captured.")
            Text("Say a NEW sentence (not one of the six above), then tap below.",
                style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                enrollment.captureValidation(ingress)
            }) { Text("Capture separate validation phrase") }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            enrollment.clear()
        }) { Text("Clear all locally saved voice samples") }
        Text(enrollment.message, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Block 10B · CAM++ Speaker Verification (testing only)",
            style = MaterialTheme.typography.titleMedium)
        Text("CAM++ runs on Galaxy CPU. Manual scoring remains available here. " +
            "Block 10C below controls optional automatic speaker verification; " +
            "YAMNet alerts are never speaker-gated.",
            style = MaterialTheme.typography.bodySmall)
        Text("Model: ${if (verifier.modelPresent) "installed" else "MISSING"} · " +
            "Owner profile: ${if (verifier.enrolled) "saved" else "not built"}")
        Button(modifier = Modifier.fillMaxWidth(),
            enabled = !verifier.busy && verifier.modelPresent && enrollment.recorded == 6,
            onClick = { verifier.buildProfile() }) {
            Text("Generate owner CAM++ embedding (6 takes)")
        }
        Button(modifier = Modifier.fillMaxWidth(),
            enabled = !verifier.busy && verifier.enrolled && enrollment.validationAvailable,
            onClick = { verifier.scoreValidation() }) {
            Text("Score separate validation phrase")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(),
            enabled = !verifier.busy && verifier.enrolled,
            onClick = { verifier.scoreFresh(ingress) }) {
            Text("Score last 5 seconds of Aria audio (test another speaker)")
        }
        Text(verifier.info, style = MaterialTheme.typography.bodySmall)
        if (verifier.scoreSummary.isNotBlank())
            Text(verifier.scoreSummary, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Block 10C · Automatic Speaker Gate (pilot)",
            style = MaterialTheme.typography.titleMedium)
        Text("CAM++ checks spoken Hey Atlas commands on Galaxy. Shadow mode logs scores " +
            "without blocking. Owner-only is experimental, not secure authentication. " +
            "Safety sound alerts stay independent.", style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Owner-only commands: ${if (ownerOnly) "ON" else "OFF (shadow)"}")
            Switch(checked = ownerOnly, enabled = ownerOnly ||
                (verifier.modelPresent && verifier.enrolled),
                onCheckedChange = { next ->
                    ownerOnly = next
                    gatePrefs.edit().putBoolean("owner_only", next).apply()
                    lastGateResult = if (next) "Owner-only ON (pilot threshold 0.50). " +
                        "If you get blocked, switch this OFF." else
                        "Shadow mode ON: no commands blocked."
                })
        }
        Text("Pilot similarity threshold: 0.50 · owner observed 0.5–0.6; " +
            "others observed 0.2–0.3. Test in different noise conditions.",
            style = MaterialTheme.typography.bodySmall)
        Text(lastGateResult, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        // BLOCK11_CONVERSATION: the recent-dialogue store is unrelated to CAM++
        // enrollment, YAMNet alert history, and 15-second visual frame cache.
        Text("Block 11 · Conversation Memory", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Remember recent Q&A: ${if (recentConversation.enabled) "ON" else "OFF"}")
            Switch(checked = recentConversation.enabled,
                onCheckedChange = { recentConversation.setRemembering(it) })
        }
        Text("Saved turns: ${recentConversation.savedTurns} (max 12). The latest 4 " +
            "turns within 30 min inform follow-ups. All text stays in Galaxy " +
            "private no-backup storage; images/audio are not stored here.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.fillMaxWidth(),
            onClick = { showRecentConversation = !showRecentConversation }) {
            Text(if (showRecentConversation) "Hide recent conversation" else "Show latest Q&A")
        }
        if (showRecentConversation) {
            Text(recentConversation.preview(), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            recentConversation.clear()
            showRecentConversation = false
        }) { Text("Clear conversation history on Galaxy") }
        Text("Default E4B answer: one short sentence. Say 'tell me more' for details. " +
            "Block 11 retains recent conversations; Block 12 stores explicit long-term notes.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        // BLOCK12_LONGTERM_UI: safe explicit long-term notes, tested without Aria.
        Text("Block 12 · Long-Term Object & Note Memory", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Remember explicit notes: ${if (explicitMemory.enabled) "ON" else "OFF"}")
            Switch(checked = explicitMemory.enabled,
                onCheckedChange = { explicitMemory.setMemoryEnabled(it) })
        }
        Text("${explicitMemory.savedEntries} saved notes on Galaxy (max 120). " +
            "Only stores things you explicitly tell Atlas to remember. " +
            "No automatic camera tracking or cloud synchronization.",
            style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.OutlinedTextField(
            value = memoryText, onValueChange = { memoryText = it },
            label = { Text("Test a memory command without Aria") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            memoryTestOutput = explicitMemory.handle(memoryText)
                ?: "This is not an explicit long-term memory command."
        }) { Text("Run Galaxy long-term memory test") }
        if (memoryTestOutput.isNotBlank()) {
            Text(memoryTestOutput, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(),
            onClick = { showExplicitNotes = !showExplicitNotes }) {
            Text(if (showExplicitNotes) "Hide saved notes" else "Show recent saved notes")
        }
        if (showExplicitNotes) Text(explicitMemory.preview(),
            style = MaterialTheme.typography.bodySmall)
        if (!confirmingClearNotes) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                confirmingClearNotes = true
            }) { Text("Clear long-term notes…") }
        } else {
            Text("Confirm deleting all long-term notes? Voiceprint and recent Q&A remain.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                explicitMemory.clearAll()
                memoryTestOutput = "Long-term notes cleared."
                confirmingClearNotes = false
            }) { Text("Confirm delete long-term notes") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                confirmingClearNotes = false
            }) { Text("Cancel") }
        }
        HorizontalDivider()
        Text("Status: $state", style = MaterialTheme.typography.bodySmall)
        if (transcript.isNotBlank()) Text("Galaxy ASR: $transcript")
        if (result.isNotBlank()) Text("Galaxy answer:\n$result")
        Text("Foreground-only pilot: stops when this dashboard leaves composition or the " +
            "app process ends. A new Hey Atlas interrupts TTS, but cannot cancel " +
            "an already-running E4B GPU inference. CAM++ voiceprint gate is a pilot; " +
            "recent multi-turn conversation is local; full long-term memory and " +
            "background service remain separate blocks.",
            style = MaterialTheme.typography.bodySmall)
    }
}
