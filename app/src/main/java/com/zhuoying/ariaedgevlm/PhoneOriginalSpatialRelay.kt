package com.zhuoying.ariaedgevlm

import java.io.File
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Transport-only: forward received Aria JPEG to the original spatial_memory
 *  engine running in Galaxy Termux/Ubuntu. Detector and SQLite stay on PHONE.
 *  No VLM, tracking or memory inference is run on Mac.
 */
internal object PhoneOriginalSpatialRelay {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlas-original-spatial-forward").apply { isDaemon = true }
    }
    // ATLAS_SPATIAL_DIAG_1: visibility into HTTP failures; no inference changes.
    private const val TAG = "AtlasSpatialRelay"
    private val enqueued = AtomicLong(0L)
    private val busy = AtomicBoolean(false)
    private val lastSent = AtomicLong(0L)
    data class Sighting(val label: String, val time: String, val jpeg: File)

    /** Query actual ORIGINAL spatial DB; evidence remains on phone. Caller cleans JPEG. */
    fun lookup(question: String, cacheDir: File): Sighting? {
        val q = question.lowercase(java.util.Locale.ROOT)
        if (!Regex("\\b(where|last|previous|earlier|remember|put|left|seen|saw)\\b").containsMatchIn(q)) return null
        val query = URLEncoder.encode(question, "UTF-8")
        val connection = URL("http://127.0.0.1:18767/lookup?q=$query&evidence=1").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 180
            connection.readTimeout = 1200
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val result = json.optJSONArray("results")?.optJSONObject(0) ?: return null
            val b64 = result.optString("jpeg_base64")
            if (b64.isBlank()) return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val photo = File(cacheDir, "atlas_spatial_${System.nanoTime()}.jpg")
            photo.writeBytes(bytes)
            return Sighting(result.optString("label"), result.optString("last_seen_local"), photo)
        } catch (_: Exception) { return null }
        finally { connection.disconnect() }
    }

    fun offer(jpeg: File) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSent.get() < 1000L || !busy.compareAndSet(false, true)) return
        lastSent.set(now)
        val sequence = enqueued.incrementAndGet()
        if (sequence <= 3L || sequence % 25L == 0L) {
            android.util.Log.i(TAG, "Forward scheduled #$sequence: ${jpeg.absolutePath}")
        }
        executor.execute {
            try {
                val bytes = jpeg.readBytes()
                if (bytes.isEmpty() || bytes.size > 1_500_000) return@execute
                val connection = URL("http://127.0.0.1:18767/frame").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 20000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "image/jpeg")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val httpCode = connection.responseCode
                    val response = (if (httpCode in 200..299) connection.inputStream
                                    else connection.errorStream)?.bufferedReader()?.use { it.readText() }
                        ?: "<no response body>"
                    if (httpCode !in 200..299 || sequence <= 3L || sequence % 25L == 0L) {
                        android.util.Log.i(TAG, "POST #$sequence status=$httpCode bytes=${bytes.size} reply=${response.take(400)}")
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "POST #$sequence FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally { busy.set(false) }
        }
    }
}
