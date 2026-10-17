package com.zhuoying.ariaedgevlm

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ATLAS_DEMO_UI_V1: state updated from the actual speech/question executor.
// No fake results or recorded/synthetic predictions.
internal object AtlasDemoEvents {
    var question by mutableStateOf("")
        private set
    var answer by mutableStateOf("")
        private set
    fun onQuestion(value: String) { question = value; answer = "Processing on Galaxy…" }
    fun onAnswer(value: String) { answer = value }
}

private data class FaceResult(val name: String, val similarity: Double, val observations: Int)
private data class High3Result(
    val frameCount: Int, val faces: Int, val face: FaceResult?,
    val consolidated: String, val serviceOk: Boolean
)

private fun fetchJson(url: String, method: String = "GET"): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    return try {
        c.requestMethod = method
        c.connectTimeout = 700
        c.readTimeout = 1500
        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally { c.disconnect() }
}

private fun fetchHigh3(): High3Result {
    val data = fetchJson("http://127.0.0.1:18769/status")
    val people = data.optJSONArray("people") ?: JSONArray()
    val last = data.optJSONObject("last_results") ?: JSONObject()
    val recognised = last.optJSONArray("recognized") ?: JSONArray()
    val match = recognised.optJSONObject(0)
    val name = match?.optString("name").orEmpty()
    var observationCount = 0
    for (i in 0 until people.length()) {
        val p = people.optJSONObject(i) ?: continue
        if (p.optString("name") == name) observationCount = p.optInt("observations")
    }
    var summary = ""
    try {
        val entries = fetchJson("http://127.0.0.1:18769/consolidated").optJSONArray("results")
        if (entries != null) for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("name") == name || (name.isEmpty() && i == 0)) {
                val objects = item.optJSONObject("co_visible_objects") ?: JSONObject()
                val formatted = mutableListOf<String>()
                for (key in objects.keys()) formatted.add("$key ×${objects.optInt(key)}")
                summary = "${item.optString("name")}: ${item.optInt("observations")} sightings" +
                    (if (formatted.isEmpty()) "" else " · ${formatted.joinToString(", ")}")
                break
            }
        }
    } catch (_: Exception) { /* main status is still useful */ }
    return High3Result(data.optInt("frames_handled"),last.optInt("faces_in_frame"),
        if (name.isNotBlank()) FaceResult(name,match!!.optDouble("similarity"),observationCount) else null,
        summary,true)
}

@Composable
internal fun AtlasDemoView(
    inlet: PhoneJpegIngress,
    runQuestion: PhoneLiveQuestionExecutor,
    onClose: () -> Unit
) {
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var face by remember { mutableStateOf<FaceResult?>(null) }
    var matchedAt by remember { mutableLongStateOf(0L) }
    var high3Status by remember { mutableStateOf("Connecting to on-phone HIGH3…") }
    var observations by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf("") }
    var questionInput by remember { mutableStateOf("Where did you last see the TV?") }
    var busy by remember { mutableStateOf(false) }
    var lastFrameAt by remember { mutableLongStateOf(0L) }
    var lastFramePath by remember { mutableStateOf("") }
    // Dialog does not navigate away from the existing Activity: foreground receivers remain active.
    LaunchedEffect(inlet) {
        while (true) {
            val frameFile = inlet.atlasDemoLatestJpeg()
            val img = withContext(Dispatchers.IO) {
                try { frameFile?.let { if (it.exists()) BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() else null } }
                catch (_: Exception) { null }
            }
            if (img != null) {
                photo = img
                if (frameFile?.absolutePath != lastFramePath) {
                    lastFramePath = frameFile?.absolutePath.orEmpty()
                    lastFrameAt = SystemClock.elapsedRealtime()
                }
            }
            delay(650)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) { try { fetchHigh3() } catch (_: Exception) { null } }
            if (s == null) high3Status = "HIGH3 offline · Keep Termux service running"
            else {
                high3Status = "HIGH3 live · ${s.frameCount} frames · ${s.faces} faces in latest sample"
                summary = s.consolidated
                if (s.face != null) {
                    face = s.face
                    observations = s.face.observations
                    matchedAt = SystemClock.elapsedRealtime()
                }
            }
            delay(1800)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0B1424))
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ATLAS · LIVE DEMO", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("Aria camera + Galaxy inference", color = Color(0xFFA9BED6))
                }
                TextButton(onClick = onClose) { Text("EXIT",color = Color(0xFFA9D7FF)) }
            }
            Box(Modifier.fillMaxWidth().height(285.dp).background(Color(0xFF172C46), RoundedCornerShape(16.dp))) {
                if (photo != null) {
                    Image(photo!!, "Live Aria glasses camera", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text("Waiting for Aria JPEG frames…",color=Color.White,modifier=Modifier.align(Alignment.Center))
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    color = Color(0xD90A1E34), shape = RoundedCornerShape(8.dp)
                ) {
                    Text("● ARIA POV  ·  ${if (inlet.running && photo != null && SystemClock.elapsedRealtime()-lastFrameAt < 3000) "LIVE" else "WAITING"}",
                        color=Color(0xFFB7F9D4), modifier=Modifier.padding(8.dp))
                }
                if (face != null && SystemClock.elapsedRealtime()-matchedAt < 15000) {
                    Surface(modifier=Modifier.align(Alignment.BottomCenter).padding(10.dp),
                        color=Color(0xE718614A),shape=RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(horizontal=14.dp,vertical=9.dp)) {
                            Text("Looks like this is ${face!!.name}",fontWeight=FontWeight.Bold,color=Color.White)
                            Text("Face similarity: ${"%.3f".format(java.util.Locale.US,face!!.similarity)} · ${face!!.observations} sightings",
                                color=Color(0xFFE6FCEF), style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(high3Status, color=Color(0xFFA9BED6),style=MaterialTheme.typography.bodySmall)
            if (summary.isNotBlank()) {
                Surface(color=Color(0xFF213A50),shape=RoundedCornerShape(10.dp)) {
                    Text("PERSON MEMORY  ·  $summary", modifier=Modifier.fillMaxWidth().padding(10.dp), color=Color.White)
                }
            }
            Surface(color=Color(0xFF1B2D43),shape=RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("YOU ASKED",color=Color(0xFF95CCF3),fontWeight=FontWeight.Bold)
                    Text(AtlasDemoEvents.question.ifBlank { "Say ‘Hey Atlas…’ or use a question below" },color=Color.White)
                    Text("GALAXY ANSWER",color=Color(0xFF95CCF3),fontWeight=FontWeight.Bold)
                    Text(AtlasDemoEvents.answer.ifBlank { "Waiting for a question" },color=Color.White)
                }
            }
            OutlinedTextField(value=questionInput,onValueChange={questionInput=it},
                label={Text("Manual demo question")},
                modifier=Modifier.fillMaxWidth(),singleLine=false, maxLines=2,
                colors=OutlinedTextFieldDefaults.colors(
                    focusedTextColor=Color.White,unfocusedTextColor=Color.White,
                    focusedLabelColor=Color(0xFFA9D7FF), unfocusedLabelColor=Color(0xFFA9D7FF),
                    focusedBorderColor=Color(0xFFA9D7FF),unfocusedBorderColor=Color(0xFF54718E)))
            Button(modifier=Modifier.fillMaxWidth(),enabled=!busy && questionInput.isNotBlank(),onClick={
                busy = true
                AtlasDemoEvents.onQuestion(questionInput.trim())
                runQuestion(questionInput.trim()) { result ->
                    AtlasDemoEvents.onAnswer(result)
                    busy = false
                }
            }) { Text(if (busy) "Galaxy processing…" else "ASK ON GALAXY") }
            Text("Voice questions update this screen automatically. Recognition names are shown only when HIGH3 matches an enrolled face.",
                color=Color(0xFF94AABF),style=MaterialTheme.typography.bodySmall)
        }
    }
}
