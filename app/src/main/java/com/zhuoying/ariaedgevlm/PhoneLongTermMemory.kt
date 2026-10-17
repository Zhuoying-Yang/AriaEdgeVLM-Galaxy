package com.zhuoying.ariaedgevlm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** BLOCK12_EXPLICIT_MEMORY: durable, user-confirmed object locations and notes.
 * No silent scene inference, continuous recording, or automated people tracking.
 */
internal object AtlasMemoryIntent {
    sealed class Action {
        data class SaveLocation(val subject: String, val location: String): Action()
        data class FindLocation(val subject: String, val explicit: Boolean): Action()
        data class SaveNote(val note: String): Action()
        data class FindNote(val topic: String): Action()
        data class ForgetLocation(val subject: String): Action()
    }

    private fun clean(text: String): String = text.trim()
        .trim(' ', '\t', '\n', '.', ',', '?', '!', ';', ':', '"', '\'')
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    fun subject(value: String): String = clean(value)
        .replace(Regex("^(?:my|the|a|an)\\s+"), "")
        .removeSuffix(" now").trim()

    // BLOCK12A_ASR_LOOKUP: compare singular/plural nouns without altering stored notes.
    fun normalizedObject(value: String): String {
        val parts = subject(value).split(' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val word = parts.last()
        val root = when {
            word in setOf("glasses", "sunglasses", "scissors", "pants", "dress") -> word
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("s") && word.length > 3 &&
                !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
            else -> word
        }
        return (parts.dropLast(1) + root).joinToString(" ")
    }

    private fun looksLikeTime(place: String): Boolean =
        Regex("^(?:at|on) (?:\\d{1,2}(?::\\d{2})?(?: am| pm)?|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
            .containsMatchIn(place)

    fun parse(text: String): Action? {
        val q = clean(text).replace(Regex("^(?:hey\\s+atlas[ ,]*)"), "")
        if (q.isBlank()) return null
        val pos = "(?:next to|inside of|inside|underneath|under|behind|beside|near|above|in|on|at|by)"
        val obj = "(?:my |the |a |an )?(.+?)"

        // Explicit deletions only. Never treat an ordinary question as permission to forget.
        Regex("^(?:please )?forget (?:where i (?:put|left) )?$obj$", RegexOption.IGNORE_CASE)
            .matchEntire(q)?.let { return Action.ForgetLocation(subject(it.groupValues[1])) }

        // Save only when the user clearly says 'remember', 'save', or 'note'.
        val save = Regex("^(?:please )?(?:remember|save|note)(?: that)?[ ,]*(.+)$")
            .matchEntire(q)?.groupValues?.get(1)
        if (save != null) {
            val s = clean(save)
            Regex("^(?:i |we )?(?:put|left|placed|stored|keep|kept) $obj ($pos) (.+)$")
                .matchEntire(s)?.let {
                    val thing = subject(it.groupValues[1]); val place = clean(it.groupValues[2] + " " + it.groupValues[3])
                    if (thing.length >= 2 && place.length >= 4 && !looksLikeTime(place)) return Action.SaveLocation(thing, place)
                }
            Regex("^$obj (?:is|are|was|were) ($pos) (.+)$")
                .matchEntire(s)?.let {
                    val thing = subject(it.groupValues[1]); val place = clean(it.groupValues[2] + " " + it.groupValues[3])
                    if (thing.length >= 2 && place.length >= 4 && !looksLikeTime(place)) return Action.SaveLocation(thing, place)
                }
            if (s.length >= 4) return Action.SaveNote(s)
        }

        listOf(
            Regex("^where (?:did|have) (?:i|we) (?:put|leave|left|place|store|keep) (?:my |the |a |an )?(.+?)(?: (?:last|earlier))?$"),
            Regex("^(?:(?:do|can) you )?remember where (?:(?:did|have) )?(?:i|we) (?:put|left|placed|stored|kept) (?:my |the |a |an )?(.+)$"),
            Regex("^can you (?:tell me|remember) where (?:i|we) (?:put|left) (?:my |the |a |an )?(.+)$"),
            Regex("^where (?:is|are|was|were) my (.+)$"),
            Regex("^(?:do you )?know where (?:(?:did|have) )?(?:i|we) (?:put|left|placed|stored) (?:my |the |a |an )?(.+)$")
        ).forEach { r -> r.matchEntire(q)?.let {
            val thing = subject(it.groupValues[1]); if (thing.length >= 2) return Action.FindLocation(thing, true)
        } }
        // Unqualified 'where is the X' is answered only for an ALREADY SAVED item;
        // otherwise let WHICH handle general knowledge/current-visual locations.
        Regex("^where (?:is|are|was|were) (?:the |a |an )?(.+)$")
            .matchEntire(q)?.let {
                val thing = subject(it.groupValues[1]); if (thing.length >= 2) return Action.FindLocation(thing, false)
            }

        Regex("^(?:what (?:do|can) you remember about|what did i (?:tell|say) (?:you )?about|recall (?:my |the )?notes about) (.+)$")
            .matchEntire(q)?.let {
                val topic = subject(it.groupValues[1]); if (topic.length >= 2) return Action.FindNote(topic)
            }
        return null
    }
}

internal class PhoneLongTermMemory private constructor(private val context: Context) {
    private data class Entry(val kind: String, val subject: String, val detail: String, val timeMs: Long)
    companion object {
        private const val MAX_ENTRIES = 120
        @Volatile private var instance: PhoneLongTermMemory? = null
        fun get(context: Context): PhoneLongTermMemory =
            instance ?: synchronized(this) {
                instance ?: PhoneLongTermMemory(context.applicationContext).also { instance = it }
            }
    }
    private val file = File(context.noBackupFilesDir, "atlas_explicit_longterm_v12.json")
    private val prefs = context.getSharedPreferences("atlas_longterm_12", 0)
    private val entries = ArrayList<Entry>()
    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var savedEntries by mutableIntStateOf(0)
        private set
    init { load() }

    fun setMemoryEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
    }

    /** Returns null for non-memory queries; they continue through recent MEMORY / WHICH. */
    fun handle(question: String): String? {
        if (!enabled) return null
        val action = AtlasMemoryIntent.parse(question) ?: return null
        return synchronized(entries) {
            when (action) {
                is AtlasMemoryIntent.Action.SaveLocation -> {
                    entries.removeAll { it.kind == "location" &&
                        AtlasMemoryIntent.normalizedObject(it.subject) == AtlasMemoryIntent.normalizedObject(action.subject) }
                    entries.add(Entry("location", action.subject, action.location, System.currentTimeMillis()))
                    trimAndPersist()
                    "I'll remember: your ${action.subject} ${action.location}."
                }
                is AtlasMemoryIntent.Action.FindLocation -> {
                    val note = entries.asReversed().firstOrNull {
                        it.kind == "location" &&
                            AtlasMemoryIntent.normalizedObject(it.subject) == AtlasMemoryIntent.normalizedObject(action.subject)
                    }
                    note?.let {
                        val verb = if (it.subject.endsWith("s") && !it.subject.endsWith("ss")) "were" else "was"
                        "You told me your ${it.subject} $verb ${it.detail}. I haven't verified it's still there."
                    }
                        ?: if (action.explicit) "I don't have a saved location for your ${action.subject}." else null
                }
                is AtlasMemoryIntent.Action.SaveNote -> {
                    entries.add(Entry("note", action.note.take(400), action.note.take(400), System.currentTimeMillis()))
                    trimAndPersist()
                    "Saved that note on this phone."
                }
                is AtlasMemoryIntent.Action.FindNote -> {
                    val terms = action.topic.split(' ').filter { it.length >= 3 }.toSet()
                    val relevant = entries.asReversed().filter {
                        terms.any { term -> term in (it.subject + " " + it.detail) }
                    }.take(2)
                    if (relevant.isEmpty()) "I don't have a saved note about ${action.topic}."
                    else "I remember: " + relevant.joinToString("; ") {
                        if (it.kind == "location") "your ${it.subject} was ${it.detail}" else it.detail
                    }
                }
                is AtlasMemoryIntent.Action.ForgetLocation -> {
                    val n = entries.count { it.kind == "location" &&
                        AtlasMemoryIntent.normalizedObject(it.subject) == AtlasMemoryIntent.normalizedObject(action.subject) }
                    entries.removeAll { it.kind == "location" &&
                        AtlasMemoryIntent.normalizedObject(it.subject) == AtlasMemoryIntent.normalizedObject(action.subject) }
                    if (n > 0) { trimAndPersist(); "I deleted the saved location for ${action.subject}." }
                    else "I don't have a saved location for ${action.subject}."
                }
            }
        }
    }

    fun preview(): String = synchronized(entries) {
        if (entries.isEmpty()) "No long-term notes saved yet."
        else entries.takeLast(6).asReversed().joinToString("\n") {
            val date = java.text.SimpleDateFormat("MMM d, HH:mm", Locale.US).format(java.util.Date(it.timeMs))
            "[$date] " + if (it.kind == "location") "${it.subject}: ${it.detail}" else "Note: ${it.detail}"
        }
    }

    fun clearAll() = synchronized(entries) {
        entries.clear(); savedEntries = 0
        file.delete()
    }

    private fun trimAndPersist() {
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        savedEntries = entries.size
        try {
            val data = JSONArray()
            entries.forEach {
                data.put(JSONObject().put("kind", it.kind).put("subject", it.subject)
                    .put("detail", it.detail).put("time", it.timeMs))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(data.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        } catch (_: Exception) {
            // A storage issue must not break live Hey Atlas inference.
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val data = JSONArray(file.readText(Charsets.UTF_8))
            for (i in (data.length() - MAX_ENTRIES).coerceAtLeast(0) until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val kind = o.optString("kind")
                val subject = o.optString("subject").take(400)
                val detail = o.optString("detail").take(400)
                if (kind in listOf("note", "location") && subject.isNotBlank() && detail.isNotBlank()) {
                    entries.add(Entry(kind, subject, detail, o.optLong("time")))
                }
            }
            savedEntries = entries.size
        } catch (_: Exception) { entries.clear(); savedEntries = 0 }
    }
}
