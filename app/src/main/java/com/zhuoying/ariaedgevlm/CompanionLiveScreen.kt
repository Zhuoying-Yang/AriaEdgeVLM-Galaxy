package com.zhuoying.ariaedgevlm

// Drop-in native Jetpack Compose screen. No extra Gradle libraries required
// beyond Material3, Compose runtime, and Kotlin coroutines already used by the app.
// To show it, call CompanionLiveScreen() inside the existing SeniorUiApp navigation,
// or use the included CompanionLiveActivity wrapper.

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val COMPANION_URL = "http://127.0.0.1:8765"

internal object CompanionHttp {
    private fun request(path: String, data: JSONObject? = null): JSONObject {
        val conn = (URL(COMPANION_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 2000
            requestMethod = if (data == null) "GET" else "POST"
            if (data != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (data != null) conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val result = JSONObject(text)
            if (code >= 400) throw IllegalStateException(result.optString("error", "HTTP $code"))
            return result
        } finally { conn.disconnect() }
    }
    fun get(path: String) = request(path)
    fun post(path: String, body: JSONObject) = request(path, body)
}

private fun JSONArray.asObjects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun displayTime(epochSeconds: Double): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionLiveScreen(onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(JSONObject()) }
    var events by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var memories by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var person by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var offline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            val result = withContext(Dispatchers.IO) {
                listOf(CompanionHttp.get("/status"), CompanionHttp.get("/events?after=0"),
                    CompanionHttp.get("/memories"), CompanionHttp.get("/candidates"))
            }
            status = result[0]
            events = result[1].optJSONArray("events")?.asObjects()?.asReversed() ?: emptyList()
            memories = result[2].optJSONArray("memories")?.asObjects() ?: emptyList()
            suggestions = result[3].optJSONArray("candidates")?.asObjects() ?: emptyList()
            offline = false
        } catch (_: Exception) {
            offline = true
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(2000)
        }
    }

    val ready = !offline && status.optString("source") == "aria" &&
        (status.optJSONObject("bridge")?.optBoolean("ready") == true)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back", fontSize = 17.sp) }
            Text("My Glasses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (ready) "Connected" else if (offline) "Not connected to Mac" else "Glasses not ready",
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Activity", fontSize = 17.sp) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Memories", fontSize = 17.sp) })
        }
        if (tab == 0) {
            val recent = events.filter {
                it.optString("kind") in setOf("question_answered", "memory_saved", "memory_failed", "proactive_alert")
            }.take(20)
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recent.isEmpty()) item { Text("Your recent questions will appear here.",
                    style = MaterialTheme.typography.bodyLarge) }
                items(recent, key = { it.optInt("id") }) { e ->
                    CompanionTile(eventTitle(e.optString("kind"))) {
                        if (e.has("question")) Text(e.optString("question"),
                            style = MaterialTheme.typography.bodyLarge)
                        if (e.has("answer")) Text(e.optString("answer"),
                            style = MaterialTheme.typography.bodyLarge)
                        if (e.has("text")) Text(e.optString("text"),
                            style = MaterialTheme.typography.bodyLarge)
                        Text(displayTime(e.optDouble("ts", 0.0)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        onClick = { showAdd = !showAdd }) {
                        Text(if (showAdd) "Cancel" else "+ Add Memory", fontSize = 17.sp)
                    }
                }
                if (showAdd) item {
                    CompanionTile("Add a memory") {
                        OutlinedTextField(value = person, onValueChange = { person = it },
                            label = { Text("Person (optional)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = note, onValueChange = { note = it },
                            label = { Text("What should I remember?") }, minLines = 2,
                            modifier = Modifier.fillMaxWidth())
                        Button(enabled = note.trim().length >= 3 && !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        CompanionHttp.post("/memories", JSONObject()
                                            .put("text", note.trim()).put("person", person.trim()))
                                    }
                                    note = ""
                                    message = "Saving memory…"
                                    showAdd = false
                                    refresh()
                                } catch (_: Exception) { message = "Could not save. Try again." }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Save", fontSize = 17.sp)
                        }
                    }
                }
                if (suggestions.isNotEmpty()) item {
                    Text("Suggestions to Review (${suggestions.size})",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(suggestions, key = { it.optString("id") }) { c ->
                    CompanionTile("From your glasses") {
                        Text(c.optString("text"), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { CompanionHttp.post(
                                            "/candidates/${c.optString("id")}/approve", JSONObject()) }
                                        message = "Saving memory…"
                                        refresh()
                                    } catch (_: Exception) { message = "Couldn't save suggestion." }
                                    finally { busy = false }
                                }
                            }) { Text("Save") }
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { CompanionHttp.post(
                                            "/candidates/${c.optString("id")}/dismiss", JSONObject()) }
                                        refresh()
                                    } catch (_: Exception) { message = "Couldn't dismiss." }
                                    finally { busy = false }
                                }
                            }) { Text("Dismiss") }
                        }
                    }
                }
                item { Text("Saved Memories", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold) }
                if (memories.isEmpty()) item { Text("No memories yet.") }
                items(memories, key = { it.optString("id") }) { m ->
                    CompanionTile(m.optString("person").ifBlank { "Memory" }) {
                        Text(m.optString("text"), style = MaterialTheme.typography.bodyLarge)
                        Text(when (m.optString("state")) {
                            "saved" -> "Saved"
                            "failed" -> "Could not save"
                            else -> "Saving…"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}

private fun eventTitle(k: String): String = when (k) {
    "question_started" -> "Question in progress"
    "question_answered" -> "Question answered"
    "question_failed" -> "Question failed"
    "memory_pending" -> "Saving memory"
    "memory_saved" -> "Memory saved"
    "memory_failed" -> "Memory save failed"
    "memory_suggested" -> "New memory suggestion"
    "memory_approved" -> "Memory approved"
    "memory_dismissed" -> "Suggestion dismissed"
    "proactive_alert" -> "Glasses alert"
    else -> k.replace('_', ' ')
}

@Composable
private fun CompanionTile(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
