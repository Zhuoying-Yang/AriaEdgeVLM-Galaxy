package com.zhuoying.ariaedgevlm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * REAL controls, not local preview switches.
 * ADB development link: adb reverse tcp:8766 tcp:8766
 * Mac: python3 -m integration.memory_mode_server
 * The backend is the source of truth. Offline means controls are disabled,
 * not a falsely-affirmed local recording mode.
 */
private const val MEMORY_MODES_URL = "http://127.0.0.1:8766/api/memory-modes"
private val MemBlue = Color(0xFF2854C7)
private val MemCard = Color(0xFFF8F9FB)
private val MemBorder = Color(0xFFE6E9EF)
private val MemText = Color(0xFF202938)
private val MemMuted = Color(0xFF697386)
private val MemGreen = Color(0xFF187E48)

private suspend fun requestMemoryModes(change: Pair<String, Boolean>? = null): JSONObject =
    withContext(Dispatchers.IO) {
        val conn = URL(MEMORY_MODES_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = if (change == null) "GET" else "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 2500
            conn.setRequestProperty("Accept", "application/json")
            if (change != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val bytes = JSONObject().put(change.first, change.second).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            if (code !in 200..299) {
                throw IllegalStateException(json.optString("error", "HTTP $code"))
            }
            json
        } finally {
            conn.disconnect()
        }
    }

private object MemoryControlsLiveState {
    var online by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var automaticMemory by mutableStateOf(false)
    var conversationMemory by mutableStateOf(false)
    var pipelineOnline by mutableStateOf(false)
    var memoryAvailable by mutableStateOf(false)
    var voiceprintReady by mutableStateOf(false)
    var activeMemory by mutableStateOf(false)
    var activeConversation by mutableStateOf(false)
    var error by mutableStateOf("")

    private fun apply(json: JSONObject) {
        online = json.optBoolean("service_online", false)
        automaticMemory = json.optBoolean("memory_writing", false)
        conversationMemory = json.optBoolean("conversation_memory", false)
        pipelineOnline = json.optBoolean("pipeline_online", false)
        memoryAvailable = json.optBoolean("memory_available", false)
        voiceprintReady = json.optBoolean("voiceprint_ready", false)
        activeMemory = json.optBoolean("active_memory_writing", false)
        activeConversation = json.optBoolean("active_conversation_memory", false)
        error = ""
    }

    suspend fun refresh() {
        if (busy) return
        try {
            apply(requestMemoryModes())
        } catch (_: Exception) {
            online = false
            pipelineOnline = false
            activeMemory = false
            activeConversation = false
            voiceprintReady = false
        }
    }

    suspend fun update(key: String, enabled: Boolean) {
        if (!online || busy) return
        busy = true
        try {
            apply(requestMemoryModes(key to enabled))
        } catch (e: Exception) {
            error = e.message ?: "Could not change memory settings"
        } finally {
            busy = false
        }
    }
}

@Composable
private fun SyncMemoryModes() {
    LaunchedEffect(Unit) {
        while (true) {
            MemoryControlsLiveState.refresh()
            delay(1800)
        }
    }
}

@Composable
private fun MemBadge(label: String, color: Color = MemBlue) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.09f)) {
        Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontWeight = FontWeight.Bold, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun CaptureConsentDialog(visible: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enable Conversation Memory?") },
        text = {
            Text("When the glasses pipeline and speaker recognition are running, nearby speech may " +
                 "be transcribed and stored in long-term memory. Please inform everyone involved " +
                 "and obtain any required consent before enabling. You can turn this off at any time. " +
                 "Already saved memories are not deleted when you turn it off.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Enable") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun StatusExplanation() {
    val state = MemoryControlsLiveState
    Text(
        when {
            !state.online -> "Mac settings service offline. Switches cannot be changed."
            !state.pipelineOnline -> "Settings saved on Mac · pipeline offline · not recording"
            !state.memoryAvailable -> "Pipeline running without long-term memory (--memory required)."
            !state.activeMemory -> "Automatic memory writing is off on the running pipeline."
            state.conversationMemory && !state.voiceprintReady ->
                "Conversation memory requested, but speaker recognition is not ready."
            state.activeConversation -> "Conversation Memory is ACTIVE in the running pipeline."
            else -> "Pipeline connected · Conversation Memory is not active."
        },
        fontSize = 12.sp, lineHeight = 17.sp,
        color = if (state.activeConversation) MemGreen else MemMuted,
    )
}

@Composable
fun MeetingNotesQuickControl(modifier: Modifier = Modifier) {
    SyncMemoryModes()
    val state = MemoryControlsLiveState
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MemCard, border = BorderStroke(1.dp, MemBorder)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conversation Memory", fontSize = 18.sp, color = MemText,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                MemBadge(if (state.activeConversation) "ACTIVE" else if (!state.online) "OFFLINE"
                else if (state.conversationMemory) "SAVED" else "OFF",
                    if (state.activeConversation) MemGreen else MemBlue)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Meeting notes & nearby conversations", fontSize = 14.sp,
                        color = MemText, fontWeight = FontWeight.Medium)
                    Text("Requires Long-term Memory and speaker recognition",
                        fontSize = 12.sp, lineHeight = 17.sp, color = MemMuted)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.online && state.conversationMemory,
                    enabled = state.online && !state.busy && state.automaticMemory,
                    onCheckedChange = {
                        if (it) confirmation = true
                        else scope.launch { state.update("conversation_memory", false) }
                    })
            }
            StatusExplanation()
            if (state.error.isNotEmpty()) Text(state.error, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error)
        }
    }
    CaptureConsentDialog(confirmation, { confirmation = false }) {
        confirmation = false
        scope.launch { state.update("conversation_memory", true) }
    }
}

@Composable
private fun MemoryToggleRow(title: String, details: String, checked: Boolean,
                            enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MemText)
            Text(details, fontSize = 13.sp, lineHeight = 18.sp, color = MemMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
fun MemorySettingsPanel(modifier: Modifier = Modifier) {
    SyncMemoryModes()
    val state = MemoryControlsLiveState
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MemBorder), color = MemCard) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Memory Settings", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), color = MemText)
                MemBadge(if (state.online) "CONNECTED" else "OFFLINE")
            }
            MemoryToggleRow("Long-term Memory", "Automatically save visual events and interactions",
                checked = state.online && state.automaticMemory,
                enabled = state.online && !state.busy,
                onChange = { scope.launch { state.update("memory_writing", it) } })
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(MemBorder))
            MemoryToggleRow("Conversation Memory", "Remember nearby speech only when enabled",
                checked = state.online && state.conversationMemory,
                enabled = state.online && !state.busy && state.automaticMemory,
                onChange = {
                    if (it) confirmation = true
                    else scope.launch { state.update("conversation_memory", false) }
                })
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(MemBorder))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speaker Recognition", fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = MemText)
                    Text("Required to distinguish the wearer from other speakers",
                        fontSize = 13.sp, color = MemMuted)
                }
                Text(if (state.pipelineOnline && state.voiceprintReady) "READY" else "NOT READY",
                    fontSize = 12.sp, color = if (state.voiceprintReady && state.pipelineOnline) MemGreen else MemMuted)
            }
            StatusExplanation()
            if (state.error.isNotEmpty()) Text(state.error, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error)
        }
    }
    CaptureConsentDialog(confirmation, { confirmation = false }) {
        confirmation = false
        scope.launch { state.update("conversation_memory", true) }
    }
}
