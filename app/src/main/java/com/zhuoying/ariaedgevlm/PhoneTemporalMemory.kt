package com.zhuoying.ariaedgevlm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil


/** A single time-stamped frame kept inside the Galaxy app's private cache. */
data class PhoneTemporalFrame(
    val timeMs: Long,
    val image: File
)

/** Video-import test fixture: the final 15 seconds of a recorded clip at ~2 fps. */
data class PhoneReplayWindow(
    val durationMs: Long,
    val startMs: Long,
    val frames: List<PhoneTemporalFrame>
)

/**
 * Phone-owned short visual history. The future Mac->Galaxy sensor bridge can call
 * appendLiveJpeg() at up to 2 fps. Test mode separately imports a recorded video:
 * unlike live memory, that replay window remains frozen for repeated QA tests.
 * No background recording or runtime permissions are added by this class.
 */
class PhoneTemporalMemory(private val context: Context) {
    companion object {
        const val WINDOW_MS = 15_000L
        const val SAMPLE_PERIOD_MS = 500L
        const val MAX_FRAMES = 30

        fun isOrderingQuestion(question: String): Boolean =
            Regex("\\b(first|before|after|earlier|later|order)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(question)

        /** Same chronological uniform selection as the Mac temporal router. */
        fun uniformSample(frames: List<PhoneTemporalFrame>, k: Int): List<PhoneTemporalFrame> {
            if (k <= 0 || frames.isEmpty()) return emptyList()
            if (frames.size <= k) return frames
            if (k == 1) return listOf(frames.last())
            return (0 until k).map { index ->
                val nearest = kotlin.math.round(index.toDouble() * (frames.size - 1) / (k - 1))
                    .toInt().coerceIn(0, frames.lastIndex)
                frames[nearest]
            }
        }
    }

    private val lock = Any()
    private val live = ArrayDeque<Pair<Long, PhoneTemporalFrame>>()
    private val liveDir = File(context.cacheDir, "phone_temporal_live").apply { mkdirs() }
    private var lastLiveAtMs = Long.MIN_VALUE
    private var replayDir: File? = null
    private var replay: PhoneReplayWindow? = null

    /** Future live transport API. JPEG bytes come from Aria via Mac, never decoded on Mac. */
    fun appendLiveJpeg(jpeg: ByteArray, receivedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
        synchronized(lock) {
            pruneLive(receivedAtMs)
            if (lastLiveAtMs != Long.MIN_VALUE && receivedAtMs - lastLiveAtMs < SAMPLE_PERIOD_MS) {
                return false
            }
            val file = File(liveDir, "f_${receivedAtMs}_${System.nanoTime()}.jpg")
            file.writeBytes(jpeg)
            live.addLast(receivedAtMs to PhoneTemporalFrame(receivedAtMs, file))
            lastLiveAtMs = receivedAtMs
            pruneLive(receivedAtMs)
            return true
        }
    }

    fun recentLiveFrames(nowMs: Long = SystemClock.elapsedRealtime()): List<PhoneTemporalFrame> =
        synchronized(lock) {
            pruneLive(nowMs)
            live.map { it.second }
        }

    private fun pruneLive(nowMs: Long) {
        while (live.isNotEmpty() &&
            (live.first.first < nowMs - WINDOW_MS || live.size > MAX_FRAMES)) {
            live.removeFirst().second.image.delete()
        }
    }

    /**
     * Read recent history from a gallery video without requiring Aria. This is a
     * frozen test replay, not a claim that these frames were seen in live time.
     */
    suspend fun loadVideoReplay(uri: Uri): PhoneReplayWindow = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val pendingDir = File(context.cacheDir, "temporal_replay_${System.nanoTime()}")
        pendingDir.mkdirs()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Could not read the video duration")
            require(durationMs >= 700L) { "Choose a video at least 1 second long" }
            val startMs = (durationMs - WINDOW_MS).coerceAtLeast(0L)
            val windowMs = durationMs - startMs
            val desired = ceil(windowMs / SAMPLE_PERIOD_MS.toDouble()).toInt()
                .coerceIn(2, MAX_FRAMES)
            val extracted = ArrayList<PhoneTemporalFrame>(desired)
            for (index in 0 until desired) {
                val clipMs = startMs + (index.toLong() * (windowMs - 1L) / (desired - 1L))
                val bitmap = retriever.getScaledFrameAtTime(
                    clipMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    640,
                    360
                ) ?: continue
                val target = File(pendingDir, "frame_${index.toString().padStart(3, '0')}.jpg")
                try {
                    target.outputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)) {
                            "Could not encode extracted frame"
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                extracted.add(PhoneTemporalFrame(clipMs, target))
            }
            require(extracted.size >= 2) { "Could not extract two video frames" }
            val newWindow = PhoneReplayWindow(durationMs, startMs, extracted)
            synchronized(lock) {
                replayDir?.deleteRecursively()
                replayDir = pendingDir
                replay = newWindow
            }
            newWindow
        } catch (e: Exception) {
            pendingDir.deleteRecursively()
            throw e
        } finally {
            retriever.release()
        }
    }

    fun currentReplay(): PhoneReplayWindow? = synchronized(lock) { replay }

    /** Assemble 4 (or 6 for ordering) labeled chronological frames as one image. */
    suspend fun createStoryboard(frames: List<PhoneTemporalFrame>): File =
        withContext(Dispatchers.IO) {
            require(frames.size >= 2) { "Need at least two distinct frames" }
            val cellW = 480
            val cellH = 270
            val labelH = 44
            val cols = 2
            val rows = (frames.size + 1) / 2
            val montage = Bitmap.createBitmap(cols * cellW, rows * (cellH + labelH), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(montage)
                canvas.drawColor(Color.WHITE)
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 28f
                    isFakeBoldText = true
                }
                for ((index, frame) in frames.withIndex()) {
                    val x = (index % cols) * cellW
                    val y = (index / cols) * (cellH + labelH)
                    val image = BitmapFactory.decodeFile(frame.image.absolutePath)
                        ?: error("Frame ${index + 1} cannot be decoded")
                    try {
                        canvas.drawBitmap(image, null, Rect(x, y, x + cellW, y + cellH), null)
                    } finally {
                        image.recycle()
                    }
                    val seconds = frame.timeMs / 1000.0
                    canvas.drawText("Frame ${index + 1}   t=${"%.1f".format(java.util.Locale.US, seconds)}s", x + 12f, y + cellH + 31f, titlePaint)
                }
                val output = File(context.cacheDir, "temporal_storyboard_${System.nanoTime()}.jpg")
                output.outputStream().use { out ->
                    check(montage.compress(Bitmap.CompressFormat.JPEG, 86, out)) {
                        "Could not save temporal storyboard"
                    }
                }
                output
            } finally {
                montage.recycle()
            }
        }
}
