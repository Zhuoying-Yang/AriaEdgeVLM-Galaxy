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

/** BLOCK11_CONVERSATION: bounded on-phone dialogue context, NOT long-term object tracking. */
internal class PhoneConversationMemory private constructor(private val context: Context) {
    private data class Turn(
        val question: String,
        val answer: String,
        val routeQuestion: String,
        val route: String,
        val timeMs: Long
    )

    companion object {
        private const val MAX_SAVED_TURNS = 12
        private const val CONTEXT_AGE_MS = 30L * 60 * 1000
        @Volatile private var instance: PhoneConversationMemory? = null

        fun get(context: Context): PhoneConversationMemory =
            instance ?: synchronized(this) {
                instance ?: PhoneConversationMemory(context.applicationContext).also { instance = it }
            }

        fun shortAnswerInstruction(question: String): String {
            val askDetail = Regex(
                "\\b(explain|details?|elaborate|tell me more|step.by.step|list all|describe fully|why exactly)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(question)
            return if (askDetail) {
                "You are Atlas, a wearable visual assistant. Answer directly, in up to three " +
                    "concise sentences. Only provide more detail because it was requested. " +
                    "Avoid uncertain guesses; acknowledge what is not visible."
            } else {
                "You are Atlas, a wearable visual assistant. Answer in ONE short, natural " +
                    "sentence, preferably under 25 words. Answer only the question; " +
                    "do not enumerate frames, add an introduction, or describe unrelated objects. " +
                    "If unsure, briefly say so."
            }
        }
    }

    private val prefs = context.getSharedPreferences("aria_conversation_11", 0)
    private val file = File(context.noBackupFilesDir, "atlas_recent_dialogue_v11.json")
    private val turns = ArrayList<Turn>()
    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var savedTurns by mutableIntStateOf(0)
        private set

    init { load() }

    private fun activeHistory(now: Long = System.currentTimeMillis()): List<Turn> =
        synchronized(turns) {
            if (!enabled) emptyList()
            else turns.filter { it.timeMs in (now - CONTEXT_AGE_MS)..now }
        }

    fun setRemembering(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("enabled", on).apply()
    }

    fun clear() {
        synchronized(turns) {
            turns.clear()
            savedTurns = 0
            file.delete()
        }
    }

    fun preview(): String {
        val last = activeHistory().lastOrNull() ?: return "No recent conversation in this session."
        return "Last Q: ${last.question.take(150)}\nLast A: ${last.answer.take(200)}"
    }

    private fun followUp(question: String): Boolean =
        Regex("^(tell me more|more details|can you elaborate|explain that|explain it|what about that|and that|and it)[?.! ]*$",
            RegexOption.IGNORE_CASE).matches(question.trim())

    /** For an elliptical follow-up use the previous question's type; do NOT change the router's model. */
    fun routingQuestion(question: String): String =
        if (enabled && followUp(question)) activeHistory().lastOrNull()?.routeQuestion ?: question
        else question

    // BLOCK11C_MEMORY_INTENT: robust on-phone recall BEFORE the visual/temporal router.
    /**
     * Deterministic recent-dialogue recall. Tolerates common Vosk variants and
     * paraphrases without letting a camera model invent older conversation history.
     * This is intentionally limited to the last 30 minutes of SAVED text turns.
     */
    fun recall(question: String): String? {
        if (!enabled) return null
        val q = question.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (q.isEmpty()) return null
        val tokens = q.split(' ').filter { it.isNotBlank() }.toSet()
        fun any(vararg w: String): Boolean = w.any { it in tokens }
        val history = activeHistory()
        val last = history.lastOrNull()

        // 1) 'want did i just ask you', 'what did i just asked', etc.
        val pastQuestion =
            ((any("ask", "asked") && any("i", "my") &&
                any("did", "just", "last", "previous", "earlier", "before")) ||
                (any("question", "questions") && any("my", "i") &&
                    any("last", "previous", "just", "earlier")) ||
                Regex("\\b(?:repeat|remind)\\b.*\\b(?:my|last|previous)\\b.*\\bquestion\\b")
                    .containsMatchIn(q))
        if (pastQuestion) {
            return last?.let { "You just asked: ${it.question}" }
                ?: "I don't have a recent question saved on this phone."
        }

        // 2) 'what did you say', 'repeat your previous answer'.
        val pastAnswer =
            ((any("say", "said", "answer", "answered") && any("you", "your") &&
                any("did", "just", "last", "previous", "earlier")) ||
                (q.contains("repeat") && any("answer", "response")))
        if (pastAnswer) {
            return last?.answer ?: "I don't have a recent answer saved on this phone."
        }

        // 3) 'what was the color of the previous object', including badly transcribed
        // 'why do you remember from the color of the previous object'.
        // Do NOT intercept 'what color is this' (a question about the CURRENT image).
        val pastRef = any("previous", "earlier", "last", "before", "ago") ||
            (any("remember", "recall") && any("object", "thing", "item", "color", "colour"))
        val historicalColor = any("color", "colors", "colour", "colours") && pastRef &&
            (any("object", "thing", "item", "one", "cup", "bottle", "mouse", "it") ||
                any("remember", "recall", "said", "told"))
        if (historicalColor) {
            val colourWord = Regex("\\b(?:red|orange|yellow|green|blue|purple|pink|black|white|gray|grey|brown|silver|gold|maroon|lavender)\\b")
            val matchingTurn = history.asReversed().firstOrNull {
                val priorQ = it.question.lowercase(Locale.ROOT)
                (priorQ.contains("color") || priorQ.contains("colour")) &&
                    !it.answer.contains("do not have", ignoreCase = true) &&
                    !it.answer.contains("don't have", ignoreCase = true)
            } ?: history.asReversed().firstOrNull {
                colourWord.containsMatchIn(it.answer.lowercase(Locale.ROOT)) &&
                    !it.answer.contains("do not have", ignoreCase = true) &&
                    !it.answer.contains("don't have", ignoreCase = true)
            }
            return matchingTurn?.let {
                "Earlier, when you asked '${it.question}', I answered: ${it.answer}"
            } ?: "I don't have a recorded answer about the previous object's color."
        }

        // 4) Recap an actual recent phone dialogue, never the model's imagined past.
        val chatRef = any("conversation", "conversations", "chat", "chats", "discussion") ||
            q.contains("talked about") || q.contains("talking about")
        val recallRef = any("previous", "last", "earlier", "recent", "before", "remember", "recall", "summarize", "summary", "discussed")
        val askRecap = q.contains("what did we talk") ||
            q.contains("what have we talked") || q.contains("what were we talking") ||
            q.contains("what did we discuss")
        if (askRecap || (chatRef && recallRef)) {
            if (history.isEmpty()) return "I don't have a recent conversation saved on this phone."
            return "Recently, you asked: " + history.takeLast(3).joinToString("; ") {
                it.question.take(90)
            } + ". I only retain limited recent phone conversations."
        }
        return null
    }

    fun prompt(question: String, visualContext: String): String {
        val history = activeHistory().takeLast(4)
        val prefix = shortAnswerInstruction(question)
        val snippets = if (history.isEmpty()) "" else buildString {
            append("\nPrevious exchanges for reference resolution only (NOT evidence of current camera contents):\n")
            history.forEachIndexed { i, turn ->
                append("${i + 1}. User: ${turn.question.take(180)}\n")
                append("   Atlas: ${turn.answer.take(260)}\n")
            }
            append("Do not claim a previously seen object is still visible without current evidence.\n")
        }
        return "$prefix\n$visualContext$snippets\nCurrent question: $question"
    }

    /** Save only completed assistant responses. Never persist RGB/audio or recognition embeddings. */
    fun record(question: String, answer: String, routeQuestion: String, route: String) {
        if (!enabled || question.isBlank() || answer.isBlank()) return
        if (answer.startsWith("ERROR:") || answer.contains("not connected", ignoreCase = true)) return
        val cleaned = answer.substringAfter("\n\n", answer).trim()
        if (cleaned.isBlank()) return
        synchronized(turns) {
            turns.add(Turn(question.take(500), cleaned.take(1200), routeQuestion.take(500),
                route.take(80), System.currentTimeMillis()))
            while (turns.size > MAX_SAVED_TURNS) turns.removeAt(0)
            savedTurns = turns.size
            persist()
        }
    }

    private fun persist() {
        try {
            val array = JSONArray()
            for (item in turns) {
                array.put(JSONObject()
                    .put("question", item.question)
                    .put("answer", item.answer)
                    .put("routeQuestion", item.routeQuestion)
                    .put("route", item.route)
                    .put("time", item.timeMs))
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(array.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            // A storage error must not block the live visual assistant.
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val array = JSONArray(file.readText(Charsets.UTF_8))
            synchronized(turns) {
                val start = (array.length() - MAX_SAVED_TURNS).coerceAtLeast(0)
                for (i in start until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val question = obj.optString("question").take(500)
                    val answer = obj.optString("answer").take(1200)
                    if (question.isNotBlank() && answer.isNotBlank()) {
                        turns.add(Turn(question, answer,
                            obj.optString("routeQuestion", question).take(500),
                            obj.optString("route").take(80), obj.optLong("time")))
                    }
                }
                savedTurns = turns.size
            }
        } catch (_: Exception) {
            synchronized(turns) { turns.clear(); savedTurns = 0 }
        }
    }
}
