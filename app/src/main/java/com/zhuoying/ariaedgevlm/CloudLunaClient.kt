package com.zhuoying.ariaedgevlm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * AriaEdge LARGE only. Calls the existing Mac Luna relay through USB `adb reverse`.
 * No OpenAI API key is stored in the Android app.
 * Mac relay reads a current Aria JPEG itself, so Galaxy need only send the question.
 */
object CloudLunaClient {
    private const val URL_ASK = "http://127.0.0.1:18772/ask"

    data class Result(val answer: String, val model: String, val latencySeconds: Double)

    suspend fun ask(question: String): Result = withContext(Dispatchers.IO) {
        val c = (URL(URL_ASK).openConnection() as HttpURLConnection)
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 5_000
            c.readTimeout = 105_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Cache-Control", "no-store")
            val body = JSONObject()
                .put("question", question)
                .put("use_live", true)
                .toString().toByteArray(Charsets.UTF_8)
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            val responseString = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val response = JSONObject(responseString)
            if (status !in 200..299 || !response.optBoolean("ok")) {
                throw IOException("LARGE Mac Luna: " + response.optString("error", "HTTP $status"))
            }
            val text = response.optString("answer").trim()
            if (text.isEmpty()) throw IOException("LARGE returned an empty answer")
            Result(text, response.optString("model"), response.optDouble("latency_s"))
        } catch (e: Exception) {
            // Propagate a real error to the existing GALAXY ANSWER UI. Never invent a fallback.
            throw IOException("LARGE unavailable: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }
}
