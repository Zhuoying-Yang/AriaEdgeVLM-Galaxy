package com.zhuoying.ariaedgevlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Block 7B pilot: decode PCM16 directly with a local Vosk model.
 * Input is the already-forwarded Aria PCM; we never invoke AudioRecord or SpeechRecognizer.
 * Single-shot on a background thread, with generation-based cancellation.
 */
internal class PhoneVoskSpeech(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val modelFolder = "vosk-model-small-en-us-0.15"
    @Volatile private var generation = 0
    @Volatile private var currentWorker: Thread? = null

    fun start(pcm16: ByteArray, onDone: (String?, String?) -> Unit) {
        cancel()
        val request = generation
        currentWorker = Thread({
            try {
                require(pcm16.size >= 2 && pcm16.size % 2 == 0) { "Aria PCM16 is missing or malformed" }
                val modelDir = File(appContext.filesDir, modelFolder)
                require(File(modelDir, "am/final.mdl").isFile) {
                    "Offline Vosk model not installed: ${modelDir.absolutePath}"
                }
                Log.i("AriaPhoneASR", "VOSK_BEGIN bytes=${pcm16.size} source=ARIA_PCM16")
                val segments = ArrayList<String>()
                Model(modelDir.absolutePath).use { model ->
                    Recognizer(model, 16_000.0f).use { recognizer ->
                        var offset = 0
                        while (offset < pcm16.size && request == generation &&
                            !Thread.currentThread().isInterrupted) {
                            val size = minOf(6400, pcm16.size - offset) // 200ms chunks
                            if (recognizer.acceptWaveForm(pcm16.copyOfRange(offset, offset + size), size)) {
                                val text = JSONObject(recognizer.result).optString("text").trim()
                                if (text.isNotEmpty()) segments.add(text)
                            }
                            offset += size
                        }
                        if (request == generation && !Thread.currentThread().isInterrupted) {
                            val finalText = JSONObject(recognizer.finalResult).optString("text").trim()
                            if (finalText.isNotEmpty()) segments.add(finalText)
                        }
                    }
                }
                if (request == generation && !Thread.currentThread().isInterrupted) {
                    val text = segments.joinToString(" ").trim()
                    Log.i("AriaPhoneASR", "VOSK_END chars=${text.length}")
                    main.post {
                        if (request == generation) onDone(text, if (text.isEmpty()) "No speech recognized in the latest 6 seconds of Aria audio." else null)
                    }
                }
            } catch (e: Exception) {
                Log.e("AriaPhoneASR", "VOSK_ERROR", e)
                main.post { if (request == generation) onDone(null, "Offline ASR: ${e.message}") }
            }
        }, "galaxy-vosk-aria").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        generation++
        currentWorker?.interrupt()
        currentWorker = null
    }
}
