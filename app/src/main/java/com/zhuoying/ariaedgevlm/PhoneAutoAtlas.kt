package com.zhuoying.ariaedgevlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Block 8: foreground-only continuous Vosk decoder of ACTUAL Aria PCM16.
 * Runs Vosk entirely on a worker thread. The ingress callback only queues bytes.
 * It never uses the Galaxy microphone or sends audio to a network ASR service.
 * Only a recognized Hey Atlas (or configured spelling variant) can dispatch.
 */
internal class PhoneAutoAtlas(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<ByteArray>(80)  // <= 8 seconds at 100ms packets
    private val enabled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val wakeOnly = Regex(
        "^\\s*(?:hey|hi)[,\\s]*(?:atlas|atlus|atilus|adlus|adlas)\\b[,.:!?\\s-]*$",
        RegexOption.IGNORE_CASE
    )
    private val wakePrefix = Regex(
        "^\\s*(?:hey|hi)[,\\s]*(?:atlas|atlus|atilus|adlus|adlas)\\b",
        RegexOption.IGNORE_CASE
    )

    fun feed(pcm16: ByteArray) {
        if (!enabled.get()) return
        if (!queue.offer(pcm16)) {
            queue.poll() // latest audio matters more than stale audio
            queue.offer(pcm16)
        }
    }

    fun start(
        onStatus: (String) -> Unit,
        onWake: () -> Unit,
        onCommand: (String, String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!enabled.compareAndSet(false, true)) return
        queue.clear()
        val session = Thread({
            try {
                val dir = File(context.filesDir, "vosk-model-small-en-us-0.15")
                check(File(dir, "am/final.mdl").isFile) {
                    "Vosk model missing: ${dir.absolutePath}"
                }
                main.post { if (enabled.get()) onStatus("Loading Vosk once for continuous listening...") }
                Model(dir.absolutePath).use { model ->
                    Recognizer(model, 16_000.0f).use { recognizer ->
                        main.post { if (enabled.get()) onStatus("Listening automatically for Hey Atlas") }
                        var armedUntil = 0L
                        var wakeNotified = false
                        var lastPartialCheck = 0L
                        var lastCommand = ""
                        var lastCommandAt = 0L
                        while (enabled.get() && !Thread.currentThread().isInterrupted) {
                            val data = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                            val boundary = recognizer.acceptWaveForm(data, data.size)
                            val now = SystemClock.elapsedRealtime()
                            if (!boundary) {
                                if (now - lastPartialCheck >= 300) {
                                    lastPartialCheck = now
                                    val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                                    if (!wakeNotified && wakePrefix.containsMatchIn(partial)) {
                                        wakeNotified = true
                                        main.post { if (enabled.get()) onWake() }
                                    }
                                }
                                continue
                            }
                            val utterance = JSONObject(recognizer.result).optString("text").trim()
                            val question = PhoneAriaSpeech.activatedQuestion(utterance)
                            val selected = when {
                                question != null -> question
                                wakeOnly.matches(utterance) -> {
                                    armedUntil = now + 7000L
                                    null
                                }
                                now < armedUntil && utterance.isNotBlank() -> utterance
                                else -> null
                            }
                            if (selected != null) {
                                armedUntil = 0L
                                if (selected != lastCommand || now - lastCommandAt > 4000L) {
                                    lastCommand = selected
                                    lastCommandAt = now
                                    main.post { if (enabled.get()) onCommand(utterance, selected) }
                                    Log.i("AriaAutoAtlas", "command dispatched chars=${selected.length}")
                                }
                            } else if (wakeOnly.matches(utterance)) {
                                main.post { if (enabled.get()) onStatus("Hey Atlas detected; listening for the question...") }
                            }
                            wakeNotified = false
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e("AriaAutoAtlas", "continuous ASR stopped", e)
                main.post { if (enabled.get()) onError("Automatic ASR: ${e.message}") }
            } finally {
                enabled.set(false)
                queue.clear()
            }
        }, "galaxy-auto-atlas")
        worker = session
        session.isDaemon = true
        session.start()
    }

    fun stop() {
        enabled.set(false)
        worker?.interrupt()
        worker = null
        queue.clear()
    }
}
