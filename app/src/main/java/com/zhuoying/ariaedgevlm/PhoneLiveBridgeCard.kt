package com.zhuoying.ariaedgevlm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

typealias PhoneLiveQuestionExecutor = (String, (String) -> Unit) -> Unit

/** Block 5 only: foreground receiver + explicit tests. Existing UI stays unchanged. */
@Composable
internal fun PhoneLiveBridgeCard(
    inlet: PhoneJpegIngress,
    runLiveQuestion: PhoneLiveQuestionExecutor
) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        if (appContext.getSharedPreferences("aria_auto_voice", 0).getBoolean("enabled", false) ||
            appContext.getSharedPreferences("atlas_original_memory_12b", 0).getBoolean("enabled", false)) {
            inlet.start() // automatically restore RGB inlet when auto assistant is enabled
        }
    }
    // ATLAS_DEMO_UI_V1
    var showAtlasDemo by remember { mutableStateOf(false) }
    if (showAtlasDemo) { AtlasDemoView(inlet, runLiveQuestion) { showAtlasDemo = false } }
    var question by remember { mutableStateOf("What did I just do?") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF5FCFA),
        border = BorderStroke(1.dp, Color(0xFFD6EAE3)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Phone Live Frame Bridge · Test", fontWeight = FontWeight.Bold)
            Button(onClick = { showAtlasDemo = true }, modifier = Modifier.fillMaxWidth()) {
                Text("OPEN FULLSCREEN RECORD DEMO")
            }
            Text(
                "Mac transports JPEG frames over USB. Galaxy keeps the recent 15s " +
                    "at up to 2 FPS and performs WHICH / E4B locally. " +
                    "Receiver works only while this app is running.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Receiver: ${if (inlet.running) "ON" else "OFF"} · Port ${PhoneJpegIngress.PORT}")
            Text(
                "Packets: ${inlet.totalReceived} · Saved: ${inlet.storedFrames} · " +
                    "Latest window: ${inlet.windowFrames} frames",
                style = MaterialTheme.typography.bodySmall
            )
            if (inlet.lastError.isNotBlank()) {
                Text(inlet.lastError, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                enabled = !busy,
                onClick = { if (inlet.running) inlet.stop() else inlet.start() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (inlet.running) "Stop frame receiver" else "Start frame receiver") }
            OutlinedTextField(
                value = question,
                onValueChange = { question = it; output = "" },
                label = { Text("Question for live buffer") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && question.isNotBlank(),
                onClick = {
                    busy = true
                    output = ""
                    runLiveQuestion(question.trim()) { text ->
                        output = text
                        busy = false
                    }
                }
            ) { Text(if (busy) "Routing / running E4B..." else "Route + answer from live frames") }
            if (output.isNotBlank()) {
                Text("Galaxy live-buffer result:", fontWeight = FontWeight.SemiBold)
                Text(output)
            }
            // ORIGINAL_MEMORY12B: original 30s memory clip writer on Galaxy.
            PhoneOriginalMemoryCard(inlet)
            // PHONE_BLOCK7_ARIA_AUDIO_WHEN_TEST
            PhoneAudioBridgeCard(runLiveQuestion, onAutoChanged = { enabled ->
                if (enabled) inlet.start()
            })
        }
    }
}
