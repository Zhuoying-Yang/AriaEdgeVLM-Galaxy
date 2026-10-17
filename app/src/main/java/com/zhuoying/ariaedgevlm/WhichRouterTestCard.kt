package com.zhuoying.ariaedgevlm

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A callback owned by MainActivity. Reuses its existing LiteRT-LM engine. */
typealias PhoneLocalVisualExecutor = (String, Uri, (String) -> Unit) -> Unit
typealias PhoneTemporalExecutor = (String, Uri, (String) -> Unit) -> Unit

/** Development-only dispatcher; unimplemented backends are intentionally NOT simulated. */
@Composable
internal fun WhichRouterTestCard(
    onLocalVisual: PhoneLocalVisualExecutor? = null,
    onLocalTemporal: PhoneTemporalExecutor? = null
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("What am I holding?") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImage = uri
        answer = ""
    }
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedVideo = uri
        answer = ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF7F9FF),
        border = BorderStroke(1.dp, Color(0xFFDCE5F5)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Phone WHICH → Executor · Test", fontWeight = FontWeight.Bold)
            Text(
                "Routing runs on Galaxy. For SMALL, choose a photo to run E4B locally. " +
                    "TEMPORAL can replay a short gallery video with E4B; " +
                    "cloud and long-term memory are not connected yet.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = question,
                onValueChange = { question = it; result = null; error = ""; answer = "" },
                label = { Text("Question") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            Button(
                enabled = !running && question.isNotBlank(),
                onClick = {
                    running = true
                    result = null
                    error = ""
                    answer = ""
                    scope.launch {
                        try {
                            result = withContext(Dispatchers.Default) {
                                NativeWhichRouter.route(context, question.trim())
                            }
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                        } finally {
                            running = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Working..." else "Run WHICH on Galaxy")
            }
            if (error.isNotBlank()) Text("Error: $error", color = MaterialTheme.colorScheme.error)
            result?.let { r ->
                val high = r.optString("high_level")
                val backend = r.optString("backend")
                Text("Route: $high", fontWeight = FontWeight.SemiBold)
                if (high == "DIRECT_VISUAL") {
                    Text(
                        "Visual backend: $backend · rescue P=${"%.3f".format(r.optDouble("rescue_probability"))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedImage == null) "Choose a photo (no glasses needed)" else "Photo selected · Change")
                    }
                    if (backend == "LARGE") {
                        Text(
                            "LARGE selected. Cloud inference is not connected yet. " +
                                "It will not silently fall back to E4B.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val canRun = onLocalVisual != null && selectedImage != null
                    Button(
                        enabled = canRun && !running,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val uri = selectedImage ?: return@Button
                            val executor = onLocalVisual ?: return@Button
                            running = true
                            answer = ""
                            error = ""
                            executor(question.trim(), uri) { output ->
                                if (output.startsWith("ERROR:")) error = output
                                else answer = output
                                running = false
                            }
                        }
                    ) {
                        Text(
                            if (running) "Running E4B..."
                            else if (backend == "SMALL") "Run routed SMALL on Galaxy (E4B)"
                            else "Manually override LARGE → E4B (test only)"
                        )
                    }
                    if (onLocalVisual == null) Text("Local executor not registered", color = MaterialTheme.colorScheme.error)
                } else if (high == "TEMPORAL") {
                    Text(
                        "Test with a recorded video. Galaxy keeps up to 30 frames from its last " +
                            "15 seconds, samples 4 (or 6 for ordering) and sends a storyboard to E4B. " +
                            "Live Aria input is not connected yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { videoPicker.launch("video/*") },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedVideo == null) "Choose a video from Galaxy" else "Video selected · Change")
                    }
                    Button(
                        enabled = !running && selectedVideo != null && onLocalTemporal != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val uri = selectedVideo ?: return@Button
                            val executor = onLocalTemporal ?: return@Button
                            running = true
                            answer = ""
                            error = ""
                            executor(question.trim(), uri) { output ->
                                if (output.startsWith("ERROR:")) error = output
                                else answer = output
                                running = false
                            }
                        }
                    ) {
                        Text(if (running) "Processing video..." else "Run phone temporal replay (E4B)")
                    }
                } else {
                    Text(
                        "The $high executor is not connected yet. No answer was generated.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("Decision: ${r.optString("reason")}", style = MaterialTheme.typography.bodySmall)
            }
            if (answer.isNotBlank()) {
                Text("Galaxy inference result:", fontWeight = FontWeight.SemiBold)
                Text(answer)
            }
        }
    }
}
