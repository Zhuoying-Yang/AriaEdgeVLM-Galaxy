package com.zhuoying.ariaedgevlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental, foreground-only danger sound classifier.
 * Uses the same 16 kHz mono PCM16 stream as Vosk. Ingress callbacks only enqueue.
 * YAMNet runs on its own CPU worker. No audio is uploaded or stored by this class.
 */
internal class PhoneYamnetSafety(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(40)
    private var worker: Thread? = null

    var status by mutableStateOf("Off")
        private set
    var windows by mutableIntStateOf(0)
        private set
    var lastTopClass by mutableStateOf("-")
        private set

    // Conservative pilot thresholds. Not clinical-grade or validated hazard alarms.
    private data class Group(
        val key: String,
        val title: String,
        val threshold: Float,
        val classes: List<String>
    )

    private val groups = listOf(
        Group("siren", "Siren / emergency vehicle", 0.30f,
            listOf("Siren", "Emergency vehicle", "Police car (siren)",
                "Ambulance (siren)", "Fire engine, fire truck (siren)", "Civil defense siren")),
        Group("alarm", "Smoke / fire alarm", 0.35f,
            listOf("Smoke detector, smoke alarm", "Fire alarm", "Car alarm")),
        Group("horn", "Vehicle horn", 0.40f,
            listOf("Vehicle horn, car horn, honking", "Air horn, truck horn", "Toot")),
        Group("impact", "Crash / breakage / scream", 0.45f,
            listOf("Shatter", "Smash, crash", "Explosion", "Gunshot, gunfire", "Screaming"))
    )

    fun feed(chunk: ByteArray) {
        if (!running.get()) return
        if (!queue.offer(chunk)) { queue.poll(); queue.offer(chunk) }
    }

    fun start(onAlert: (key: String, label: String, confidence: Float) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        windows = 0
        lastTopClass = "-"
        val t = Thread({
            var interpreter: Interpreter? = null
            try {
                val path = File(context.filesDir, "yamnet.tflite")
                check(path.isFile && path.length() > 1_000_000L) {
                    "YAMNet model missing: ${path.absolutePath}. Transfer yamnet.tflite first."
                }
                val labels = context.assets.open("yamnet_class_map.csv").bufferedReader().use { rdr ->
                    rdr.lineSequence().drop(1).mapNotNull { line ->
                        val pieces = line.split(',', limit = 3)
                        if (pieces.size < 3) null else {
                            val id = pieces[0].toIntOrNull()
                            if (id == null) null else id to pieces[2].trim().trim('"').replace("\"\"", "\"")
                        }
                    }.toMap()
                }
                check(labels.size == 521) { "Expected 521 YAMNet class labels, got ${labels.size}" }
                val model = Interpreter(path, Interpreter.Options().setNumThreads(2))
                interpreter = model
                check(model.inputTensorCount == 1 && model.outputTensorCount >= 1) {
                    "Unexpected model signature"
                }
                val input = model.getInputTensor(0)
                val output = model.getOutputTensor(0)
                check(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32) {
                    "Expected float32 YAMNet model"
                }
                val dims = input.shape()
                check(dims.contentEquals(intArrayOf(15600)) || dims.contentEquals(intArrayOf(1, 15600))) {
                    "Expected fixed input 15600 samples, got ${dims.contentToString()}"
                }
                check(output.numBytes() == 521 * 4) {
                    "Expected 521 output scores, got ${output.numBytes()} bytes"
                }
                val classIds = groups.associate { group ->
                    group.key to group.classes.mapNotNull { name ->
                        labels.entries.firstOrNull { it.value == name }?.key
                    }
                }
                check(classIds.values.any { it.isNotEmpty() }) {
                    "No requested hazard classes found in YAMNet label map"
                }
                main.post { if (running.get()) status = "Monitoring Aria audio on Galaxy (YAMNet CPU)" }

                val ring = FloatArray(15600)
                var writePos = 0
                var total = 0L
                var nextRun = 15600L
                val inBuf = ByteBuffer.allocateDirect(15600 * 4).order(ByteOrder.nativeOrder())
                val outBuf = ByteBuffer.allocateDirect(521 * 4).order(ByteOrder.nativeOrder())
                val consecutive = groups.associate { it.key to 0 }.toMutableMap()
                val lastAlert = mutableMapOf<String, Long>()
                var processed = 0
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val packet = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    if (packet.size % 2 != 0) continue
                    for (n in 0 until packet.size step 2) {
                        val pcm = (((packet[n + 1].toInt() and 255) shl 8) or
                            (packet[n].toInt() and 255)).toShort().toInt()
                        ring[writePos] = pcm / 32768.0f
                        writePos = (writePos + 1) % ring.size
                        total++
                    }
                    if (total < nextRun) continue
                    // A recent ~0.975s window every ~0.5s; only complete recent signal.
                    nextRun = total + 8000L
                    inBuf.clear()
                    for (i in ring.indices) inBuf.putFloat(ring[(writePos + i) % ring.size])
                    inBuf.rewind()
                    outBuf.clear()
                    model.run(inBuf, outBuf)
                    outBuf.rewind()
                    val values = FloatArray(521) { outBuf.getFloat() }
                    val idx = values.indices.maxByOrNull { values[it] } ?: 0
                    processed++
                    val topLabel = "${labels[idx] ?: "class $idx"} (%.2f)".format(java.util.Locale.US, values[idx])
                    if (processed % 2 == 0) main.post {
                        if (running.get()) { windows = processed; lastTopClass = topLabel }
                    }
                    for (group in groups) {
                        val best = classIds.getValue(group.key).maxOfOrNull { values[it] } ?: 0f
                        val n = if (best >= group.threshold) (consecutive[group.key] ?: 0) + 1 else 0
                        consecutive[group.key] = n
                        val now = SystemClock.elapsedRealtime()
                        if (n >= 2 && now - (lastAlert[group.key] ?: -60_000L) >= 45_000L) {
                            lastAlert[group.key] = now
                            consecutive[group.key] = 0
                            main.post { if (running.get()) onAlert(group.key, group.title, best) }
                            Log.i("AriaYamNet", "Potential sound key=${group.key} confidence=$best")
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e("AriaYamNet", "YAMNet stopped", e)
                main.post { status = "YAMNet error: ${e.message}" }
            } finally {
                interpreter?.close()
                running.set(false)
                queue.clear()
            }
        }, "galaxy-yamnet")
        t.isDaemon = true
        worker = t
        status = "Loading YAMNet..."
        t.start()
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
        queue.clear()
        status = "Off"
    }
}
