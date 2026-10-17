package com.zhuoying.ariaedgevlm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Alerts are persisted by the Mac Companion backend in SQLite.
// This composable receives real Mac API records; it never fabricates alerts.
internal class PhoneSafetyUiState {
    var alerts by mutableStateOf<List<JSONObject>>(emptyList())
    var activeAlert by mutableStateOf<JSONObject?>(null)
    val pending = mutableStateListOf<JSONObject>()
    // PHONE_REMINDER_POPUP_V1: reminder popups are separate from Safety Alert History.
    var activeReminder by mutableStateOf<JSONObject?>(null)
    val pendingReminders = mutableStateListOf<JSONObject>()
}

private fun alarmTime(ts: Double): String = SimpleDateFormat(
    "MMM d, h:mm a", Locale.getDefault()
).format(Date((ts * 1000).toLong()))

@Composable
internal fun PhoneControlsCard(companion: CompanionFeed, alertCount: Int, onHistory: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val state = companion.status.optString("assistant", "")
    val speakingOrProcessing = state == "Processing" || state == "Speaking"
    val running = companion.status.optBoolean("manual_control_available", false)
    val armed = companion.status.optBoolean("manual_listening", false)
    Surface(
        modifier = Modifier.fillMaxWidth(), color = Color(0xFFF0F4FF),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFDDE4F5))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Manual Glasses Control", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Tap to ask using the glasses microphone without a wake word. " +
                    "Tap while AI is speaking to stop it and listen to your next question.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    busy = true
                    result = ""
                    scope.launch {
                        try {
                            val response = withContext(Dispatchers.IO) {
                                CompanionHttp.post("/control/ask", JSONObject())
                            }
                            result = if (response.optBoolean("interrupted"))
                                "Answer interrupted. Speak your question now."
                            else "Listening — speak your question now."
                        } catch (e: Exception) {
                            result = "Cannot start: ${e.message ?: "Mac offline"}"
                        } finally { busy = false }
                    }
                },
                enabled = companion.online && running && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                Text(
                    if (busy) "Sending..." else if (speakingOrProcessing) "Stop & Ask Again"
                    else if (armed) "Restart Listening" else "Ask AI Now",
                    fontSize = 17.sp
                )
            }
            if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
            if (companion.online && !running) Text(
                "Start the full Aria pipeline with --companion to enable the microphone control.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Alert History ($alertCount)")
            }
        }
    }
}

@Composable
internal fun PhoneAlertDialogs(ui: PhoneSafetyUiState, online: Boolean, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PhoneLocalAlerts.ensureLoaded(context.applicationContext)
    val prefs = remember(context) { context.getSharedPreferences(
        "ai_glasses_alert_popup", android.content.Context.MODE_PRIVATE
    ) }
    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        while (isActive) {
            try {
                val response = withContext(Dispatchers.IO) { CompanionHttp.get("/alerts") }
                val list = response.optJSONArray("alerts")
                val incoming = if (list == null) emptyList() else
                    (0 until list.length()).map { list.getJSONObject(it) }
                ui.alerts = incoming
                val last = prefs.getLong("last_popup_id", 0L)
                // Only show newly arriving alarms. Old history must not pop up
                // repeatedly after reinstall/relaunch, or flood the user.
                val newestId = incoming.maxOfOrNull { it.optLong("id") } ?: 0L
                // On first launch, show recent TEST/real alerts, never replay old history.
                val fresh = incoming
                    .filter { it.optLong("id") > last &&
                        it.optDouble("ts") >= System.currentTimeMillis() / 1000.0 - 60.0 }
                    .sortedBy { it.optLong("id") }
                if (fresh.isNotEmpty()) ui.pending.addAll(fresh)
                if (newestId > last) {
                    prefs.edit().putLong("last_popup_id", newestId).apply()
                }
                if (ui.activeAlert == null && ui.pending.isNotEmpty()) {
                    ui.activeAlert = ui.pending.removeAt(0)
                }
            } catch (_: Exception) {
                // Keep earlier alarm history on screen if Mac is briefly disconnected.
            }
            delay(1500L)
        }
    }

    // Reminder events come from STANDING monitors firing. They are shown once
    // and never inserted into the safety alerts table or Alert History.
    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        while (isActive) {
            try {
                val response = withContext(Dispatchers.IO) { CompanionHttp.get("/events") }
                val events = response.optJSONArray("events")
                val all = if (events == null) emptyList() else
                    (0 until events.length()).map { events.getJSONObject(it) }
                val last = prefs.getLong("last_reminder_event_id", 0L)
                val latest = all.maxOfOrNull { it.optLong("id") } ?: 0L
                val fresh = all.filter {
                    it.optString("kind") == "reminder_fired" &&
                        it.optLong("id") > last &&
                        it.optDouble("ts") >= System.currentTimeMillis() / 1000.0 - 60.0
                }.sortedBy { it.optLong("id") }
                if (fresh.isNotEmpty()) ui.pendingReminders.addAll(fresh)
                if (latest > last) prefs.edit().putLong("last_reminder_event_id", latest).apply()
                if (ui.activeReminder == null && ui.pendingReminders.isNotEmpty()) {
                    ui.activeReminder = ui.pendingReminders.removeAt(0)
                }
            } catch (_: Exception) {
                // Losing the Mac connection must not trigger a fake reminder.
            }
            delay(1500L)
        }
    }

    fun closeCurrent() {
        val id = ui.activeAlert?.optLong("id") ?: return
        scope.launch {
            try { withContext(Dispatchers.IO) {
                CompanionHttp.post("/alerts/$id/ack", JSONObject())
            } } catch (_: Exception) {}
        }
        ui.activeAlert = if (ui.pending.isNotEmpty()) ui.pending.removeAt(0) else null
    }

    val localCurrent = PhoneLocalAlerts.active
    val current = ui.activeAlert
    if (localCurrent != null) {
        AlertDialog(
            onDismissRequest = { PhoneLocalAlerts.dismiss(context.applicationContext) },
            title = { Text("Potential Safety Sound") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localCurrent.optString("label"), fontWeight = FontWeight.Bold)
                    Text("Galaxy YAMNet · " + alarmTime(localCurrent.optDouble("ts")))
                    Text("AI sound detection may be inaccurate. Check your surroundings. " +
                        "This is not a certified emergency warning system.")
                }
            },
            confirmButton = { TextButton(onClick = {
                PhoneLocalAlerts.dismiss(context.applicationContext)
            }) { Text("Dismiss") } },
            dismissButton = { TextButton(onClick = {
                PhoneLocalAlerts.dismiss(context.applicationContext)
                onOpenHistory()
            }) { Text("History") } }
        )
    } else if (current != null) {
        AlertDialog(
            onDismissRequest = { closeCurrent() },
            title = { Text("Safety Alert") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.optString("label", "Sound detected"), fontWeight = FontWeight.Bold)
                    Text("${alarmTime(current.optDouble("ts"))} · ${current.optString("source")}")
                    Text("AI detection may be imperfect. Check your surroundings.")
                }
            },
            confirmButton = { TextButton(onClick = { closeCurrent() }) { Text("Dismiss") } },
            dismissButton = { TextButton(onClick = {
                closeCurrent()
                onOpenHistory()
            }) { Text("History") } }
        )
    } else {
        // Prioritize urgent safety alerts if both notifications arrive together.
        val reminder = ui.activeReminder
        if (reminder != null) {
            AlertDialog(
                onDismissRequest = {
                    ui.activeReminder = if (ui.pendingReminders.isNotEmpty())
                        ui.pendingReminders.removeAt(0) else null
                },
                title = { Text("Reminder") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            reminder.optString("text", "Your requested condition was detected."),
                            fontWeight = FontWeight.SemiBold
                        )
                        val original = reminder.optString("question", "")
                        if (original.isNotBlank()) Text(
                            "Requested: $original",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        ui.activeReminder = if (ui.pendingReminders.isNotEmpty())
                            ui.pendingReminders.removeAt(0) else null
                    }) { Text("Got it") }
                }
            )
        }
    }
}

@Composable
internal fun PhoneAlertHistoryScreen(ui: PhoneSafetyUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    PhoneLocalAlerts.ensureLoaded(context.applicationContext)
    val allAlerts = (PhoneLocalAlerts.alerts + ui.alerts)
        .sortedByDescending { it.optDouble("ts") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text("Alert History", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Alerts detected by your glasses and the AI system are saved here.",
            style = MaterialTheme.typography.bodyMedium)
        if (allAlerts.isEmpty()) {
            Text("No alerts have been recorded yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(allAlerts) { alarm ->
                    Surface(
                        color = Color(0xFFF8F9FB), shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(alarm.optString("label", "Alarm"), fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text("${alarmTime(alarm.optDouble("ts"))} · ${alarm.optString("source")}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Type: ${alarm.optString("category")} · " +
                                (if (alarm.optString("source") == "visual_router") "confidence: unavailable"
                                 else "confidence: ${"%.2f".format(Locale.US, alarm.optDouble("confidence"))}"),
                                style = MaterialTheme.typography.labelSmall)
                            Text(if (alarm.optInt("acknowledged") == 1) "Dismissed" else "New",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
