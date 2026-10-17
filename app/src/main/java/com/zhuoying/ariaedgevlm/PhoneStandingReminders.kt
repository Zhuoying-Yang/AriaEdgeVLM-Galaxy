package com.zhuoying.ariaedgevlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Foreground pilot. Galaxy-only commands, SQLite service, autonomous TTS. */
internal object PhoneStandingReminders {
    private const val BASE = "http://127.0.0.1:18769"
    private val main = Handler(Looper.getMainLooper())
    private var poll: ScheduledExecutorService? = null
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var lastId = -1L

    private fun http(path: String, json: JSONObject? = null): JSONObject {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1700
            connection.readTimeout = 2700
            connection.requestMethod = if (json == null) "GET" else "POST"
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            val input = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader()?.use { it.readText() } ?: "{}"
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    fun start(context: Context) {
        if (poll != null) return
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { code ->
                speechReady = code == TextToSpeech.SUCCESS
                if (speechReady) tts?.language = Locale.US
            }
        }
        lastId = -1L
        poll = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "atlas-standing-poll").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                try {
                    val result = http("/reminders/events?after=${if (lastId < 0) 0 else lastId}")
                    val events = result.optJSONArray("events") ?: return@scheduleWithFixedDelay
                    var maxId = lastId
                    for (i in 0 until events.length()) {
                        val e = events.getJSONObject(i)
                        val id = e.optLong("id")
                        if (id > maxId) maxId = id
                        if (lastId >= 0 && id > lastId) {
                            val msg = e.optString("message")
                            main.post {
                                if (speechReady && poll != null && msg.isNotBlank())
                                    tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "atlas-stand-$id")
                            }
                            Log.i("AtlasStanding", "Triggered: $msg")
                        }
                    }
                    lastId = maxId.coerceAtLeast(0)
                } catch (e: Exception) { Log.w("AtlasStanding", "poll: ${e.message}") }
            }, 0, 2, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        poll?.shutdownNow()
        poll = null
        // Keep tts object until Activity lifecycle stops; cannot own an always-on background service.
    }

    /** Returns true iff a STANDING command was intercepted before WHICH/E4B. */
    fun tryHandle(question: String, done: (String) -> Unit): Boolean {
        val q = question.trim().lowercase(Locale.US)
        val create = listOf("remind me if", "remind me when", "let me know if", "let me know when",
            "notify me when", "notify me if", "tell me when", "alert me if", "alert me when")
            .any { q.contains(it) }
        val cancel = q.contains("cancel reminder") || q.contains("cancel all reminders") ||
            q.contains("stop reminding") || q.contains("stop watching")
        val list = q.contains("my reminders") || q.contains("what are you watching")
        if (!create && !cancel && !list) return false
        Thread({
            val response = try {
                when {
                    create -> {
                        val res = http("/reminders", JSONObject().put("text", question))
                        res.optString("message", res.optString("error", res.toString()))
                    }
                    cancel -> {
                        val res = http("/reminders/cancel", JSONObject().put("text", question)
                            .put("all", q.contains("all") || q.contains("everything")))
                        res.optString("message", res.toString())
                    }
                    else -> {
                        val rules = http("/reminders").optJSONArray("reminders")
                        val active = mutableListOf<String>()
                        if (rules != null) for (i in 0 until rules.length()) {
                            val r = rules.getJSONObject(i)
                            if (r.optBoolean("active")) active.add(r.optString("label"))
                        }
                        if (active.isEmpty()) "No active reminders." else "Watching for: ${active.joinToString(", ")}."
                    }
                }
            } catch (e: Exception) { "Reminder service unavailable: ${e.message}" }
            main.post { done("Route: STANDING (Galaxy visual reminder)\n\n$response") }
        }, "atlas-standing-command").apply { isDaemon = true }.start()
        return true
    }
}
