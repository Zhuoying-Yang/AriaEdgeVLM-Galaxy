package com.zhuoying.ariaedgevlm

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import java.util.Locale

/**
 * One-shot Android ON-DEVICE transcription of PCM injected from Aria.
 * Does not read the Galaxy microphone itself. Android's recognition service may
 * ignore EXTRA_AUDIO_SOURCE; we cannot guarantee external audio injection on
 * every OS/recognizer. No cloud fallback is used by this implementation.
 */
internal class PhoneAriaSpeech(private val context: Context) {
    companion object {
        /** Only activated commands are dispatched. Avoid substring matches in normal conversation. */
        fun activatedQuestion(recognized: String): String? {
            val wake = Regex("^\\s*(?:hey|hi)[,\\s]*(?:atlas|atlus|atilus|adlus|adlas)\\b[,.:!?\\s-]*", RegexOption.IGNORE_CASE)
            val match = wake.find(recognized) ?: return null
            return recognized.substring(match.range.last + 1).trim().takeIf { it.isNotBlank() }
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var readFd: ParcelFileDescriptor? = null
    private var writeFd: ParcelFileDescriptor? = null
    private val main = Handler(Looper.getMainLooper())
    private var token = 0

    fun start(pcm16: ByteArray, onDone: (String?, String?) -> Unit) {
        require(Looper.myLooper() == Looper.getMainLooper()) { "Run ASR on the main thread" }
        cancel()
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onDone(null, "No on-device speech recognition service is installed. " +
                "No cloud ASR fallback was attempted.")
            return
        }
        val instance: SpeechRecognizer
        try {
            instance = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            onDone(null, "On-device recognizer unavailable: ${e.message}")
            return
        }
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: Exception) {
            instance.destroy()
            onDone(null, "Could not create audio source: ${e.message}")
            return
        }
        readFd = pipe[0]
        writeFd = pipe[1]
        recognizer = instance
        val current = ++token
        var finished = false
        fun finish(text: String?, error: String?) {
            if (finished || current != token) return
            finished = true
            // Cleanup is deferred until after callback; callbacks often arrive on main thread.
            onDone(text, error)
            main.post { if (current == token) cancel() }
        }
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { finish(null, "On-device ASR error code $error. " +
                "This recognizer may not support external PCM input.") }
            override fun onResults(results: Bundle?) {
                val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finish(alternatives?.firstOrNull(), if (alternatives.isNullOrEmpty()) "No recognized speech" else null)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
        try { instance.startListening(intent) } catch (e: Exception) {
            finish(null, "ASR could not start: ${e.message}")
            return
        }
        // PHONE_ARIA_ASR_TIMEOUT_V2
        main.postDelayed({
            if (!finished && current == token) {
                android.util.Log.w("AriaPhoneASR", "ASR_TIMEOUT_15S")
                finish(null, "Aria PCM ASR timed out after 15 seconds: Android returned no transcription.")
            }
        }, 15_000L)
        // Provide the 6s snapshot at close to real-time, then close to signal EOF.
        // A service that ignores this FD might instead open the phone microphone.
        Thread({
            try {
                Thread.sleep(150)
                FileOutputStream(pipe[1].fileDescriptor).use { out ->
                    var off = 0
                    while (off < pcm16.size && current == token) {
                        val n = minOf(3200, pcm16.size - off) // 100ms / 16k Hz / 16-bit
                        out.write(pcm16, off, n)
                        off += n
                        Thread.sleep(100)
                    }
                }
            } catch (_: Exception) { /* recognizer cancelled/closed the pipe */ }
        }, "aria-audio-to-asr").apply { isDaemon = true; start() }
    }

    fun cancel() {
        token++
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { readFd?.close() } catch (_: Exception) {}
        try { writeFd?.close() } catch (_: Exception) {}
        readFd = null
        writeFd = null
    }
}
