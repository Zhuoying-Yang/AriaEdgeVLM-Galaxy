package com.zhuoying.ariaedgevlm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** State from the live Mac companion API; NEVER replace missing data with mock events. */
internal data class CompanionFeed(
    val online: Boolean = false,
    val status: JSONObject = JSONObject(),
    val events: List<JSONObject> = emptyList(),
    val memories: List<JSONObject> = emptyList(),
    // AUTO_MEMORY_SYNC_V1_FEED: real graph/spatial records, not manual saves.
    val automaticMemories: List<JSONObject> = emptyList(),
    val candidates: List<JSONObject> = emptyList(),
)

/** Query on IO; polling stops automatically when the hosting composable is disposed. */
@Composable
internal fun rememberCompanionFeed(): CompanionFeed {
    var feed by remember { mutableStateOf(CompanionFeed()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    val status = CompanionHttp.get("/status")
                    val events = CompanionHttp.get("/events?after=0")
                    val memories = CompanionHttp.get("/memories")
                    // Optional endpoint, so an older Mac backend won't break the
                    // already-working status, Safety Alerts or manual memories.
                    val automatic = try {
                        CompanionHttp.get("/automatic-memories")
                    } catch (_: Exception) { JSONObject() }
                    val candidates = CompanionHttp.get("/candidates")
                    fun objects(obj: JSONObject, name: String): List<JSONObject> {
                        val arr = obj.optJSONArray(name) ?: return emptyList()
                        return (0 until arr.length()).map { arr.getJSONObject(it) }
                    }
                    CompanionFeed(
                        online = true,
                        status = status,
                        events = objects(events, "events").asReversed(),
                        memories = objects(memories, "memories"),
                        automaticMemories = objects(automatic, "memories"),
                        candidates = objects(candidates, "candidates"),
                    )
                }
                feed = fresh
            } catch (_: Exception) {
                // Do not keep displaying an old Connected status when the Mac went offline.
                feed = CompanionFeed()
            }
            delay(2000L)
        }
    }
    return feed
}

internal fun companionEventTitle(kind: String): String = when (kind) {
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
    else -> kind.replace('_', ' ')
}

internal fun companionEventDescription(event: JSONObject): String = when {
    event.has("question") -> {
        val question = event.optString("question")
        val answer = event.optString("answer", "")
        if (answer.isBlank()) question else "$question — $answer"
    }
    event.has("text") -> event.optString("text")
    event.has("error") -> event.optString("error")
    else -> "Tap to review the real glasses event."
}
