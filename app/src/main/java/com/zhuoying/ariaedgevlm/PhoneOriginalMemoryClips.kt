package com.zhuoying.ariaedgevlm

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * PHONE_ORIGINAL_MEMORY12B: Android transport adapter for the ORIGINAL
 * integration/memory_writer.py clip protocol. 30 s clips, sample every 1 s,
 * max 8 visual panels, episodic/semantic arrays, do not write quiet clips.
 * This is NOT the StreamMeCo pickle graph, YOLO tracker, or face consolidation.
 */
internal class PhoneOriginalMemoryClips private constructor(private val context: Context) {
    data class Clip(val startWallMs: Long, val endWallMs: Long, val frames: List<PhoneTemporalFrame>)
    // ORIGINAL_MEMORY12B1_MOTION_AUDIT: preserve 30s clips, but sample peak-motion evidence.
    data class Analysis(val idle: Boolean, val chosen: List<PhoneTemporalFrame>,
                        val peakDiff: Double, val endDiff: Double)

    companion object {
        private const val CLIP_MS = 30_000L
        private const val SAMPLE_MS = 500L
        private const val MAX_CLIPS = 240
        @Volatile private var instance: PhoneOriginalMemoryClips? = null
        fun get(context: Context): PhoneOriginalMemoryClips = instance ?: synchronized(this) {
            instance ?: PhoneOriginalMemoryClips(context.applicationContext).also { instance = it }
        }

        fun parseModelJson(raw: String): Pair<List<String>, List<String>>? {
            val begin = raw.indexOf('{')
            if (begin < 0) return null
            val obj = try { org.json.JSONTokener(raw.substring(begin)).nextValue() as JSONObject }
                      catch (_: Exception) { return null }
            fun strings(key: String): List<String> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return (0 until minOf(arr.length(), 32)).mapNotNull { i ->
                    val s = arr.optString(i).trim()
                    s.takeIf { it.isNotEmpty() && it.length <= 600 }
                }
            }
            if (!obj.has("video_description") || !obj.has("high_level_conclusions")) return null
            return strings("video_description") to strings("high_level_conclusions")
        }
    }

    private val prefs = context.getSharedPreferences("atlas_original_memory_12b", 0)
    private val root = File(context.noBackupFilesDir, "original_memory_clips").apply { mkdirs() }
    private val index = File(root, "clips.json")
    private val staging = File(context.cacheDir, "original_memory_pending").apply { mkdirs() }
    private val queue = ArrayDeque<Clip>()
    private val captured = ArrayList<PhoneTemporalFrame>()
    private var currentStart = 0L
    private var lastSample = 0L
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var written by mutableIntStateOf(0)
        private set
    var skipped by mutableIntStateOf(0)
        private set
    var status by mutableStateOf("Original 30s memory clip writer is off")
        private set
    var sampledFrames by mutableIntStateOf(0)
        private set
    var lastPeakDiff by mutableStateOf<Double?>(null)
        private set

    init {
        try { written = JSONObject(index.readText()).optJSONArray("clips")?.length() ?: 0 }
        catch (_: Exception) { }
        // Incomplete clips are never treated as verified stored memories.
        staging.listFiles()?.forEach { it.delete() }
    }

    fun setMonitoringEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
        if (!value) resetCapture()
        status = if (value) "Collecting 1 frame/sec into original 30s clips" else "Memory capture paused"
    }

    private fun resetCapture() {
        captured.forEach { it.image.delete() }
        captured.clear()
        sampledFrames = 0
        currentStart = 0L
        lastSample = 0L
        while (queue.isNotEmpty()) queue.removeFirst().frames.forEach { it.image.delete() }
    }

    /** Called by the existing MJPEG receiver after one JPEG is saved (foreground-only). */
    fun acceptFrame(frames: List<PhoneTemporalFrame>) {
        if (!enabled || frames.isEmpty()) return
        val newest = frames.last()
        val now = SystemClock.elapsedRealtime()
        if (now - newest.timeMs !in 0L..3_000L) return
        if (lastSample > 0L && newest.timeMs - lastSample < SAMPLE_MS) return
        // The existing 15s ring deletes its JPEGs: keep a separate 30s copy.
        val copy = File(staging, "clip_${newest.timeMs}_${System.nanoTime()}.jpg")
        try { newest.image.copyTo(copy, overwrite = true) } catch (_: Exception) { return }
        if (currentStart == 0L) currentStart = System.currentTimeMillis()
        captured.add(PhoneTemporalFrame(System.currentTimeMillis(), copy))
        sampledFrames = captured.size
        lastSample = newest.timeMs
        if (captured.size > 66) { captured.removeAt(0).image.delete() }
        val span = captured.last().timeMs - captured.first().timeMs
        if (span < CLIP_MS && captured.size < 61) return
        val made = Clip(captured.first().timeMs, captured.last().timeMs, captured.toList())
        captured.clear()
        sampledFrames = 0
        currentStart = 0L
        if (queue.size >= 6) {
            queue.removeFirst().frames.forEach { it.image.delete() }
            skipped += 1
        }
        queue.addLast(made)
        status = "Original MemoryWriter: a 30s clip is ready (${made.frames.size} frames)"
    }

    fun dequeue(): Clip? = if (enabled && queue.isNotEmpty()) queue.removeFirst() else null
    fun release(clip: Clip) = clip.frames.forEach { it.image.delete() }

    /**
     * Original MemoryWriter 30s protocol, with a phone-specific correction:
     * first/last may be identical even when a short pick-up or placement happened
     * in the middle. Inspect adjacent sampled frames before declaring an idle clip.
     * Keep 8 E4B panels, but reserve some for the strongest motion transitions.
     * This is a visual-change heuristic, NOT object detection/confirmation.
     */
    fun analyze(clip: Clip): Analysis {
        val n = clip.frames.size
        if (n < 2) return Analysis(true, clip.frames, 0.0, 0.0)
        fun small(f: File): IntArray? {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            try {
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 32, true)
                try { return IntArray(1024).also { scaled.getPixels(it, 0, 32, 0, 0, 32, 32) } }
                finally { if (scaled !== bmp) scaled.recycle() }
            } finally { bmp.recycle() }
        }
        fun diff(a: IntArray?, b: IntArray?): Double {
            if (a == null || b == null) return 0.0
            var sum = 0.0
            for (i in a.indices) {
                sum += abs(((a[i] shr 16) and 255) - ((b[i] shr 16) and 255))
                sum += abs(((a[i] shr 8) and 255) - ((b[i] shr 8) and 255))
                sum += abs((a[i] and 255) - (b[i] and 255))
            }
            return sum / (1024 * 3)
        }
        val thumbs = clip.frames.map { small(it.image) }
        val changes = (1 until n).map { diff(thumbs[it - 1], thumbs[it]) }
        val peak = changes.maxOrNull() ?: 0.0
        val end = diff(thumbs.first(), thumbs.last())
        lastPeakDiff = peak
        // Original end-to-end criterion (6.0) plus mid-clip motion (3.5).
        // Do not lower both thresholds further without checking false positives.
        val idle = end < 6.0 && peak < 3.5
        val chosen = linkedSetOf(0, (n - 1) / 3, 2 * (n - 1) / 3, n - 1)
        val peakCandidates = changes.indices.sortedByDescending { changes[it] }
        val peaks = mutableListOf<Int>()
        for (at in peakCandidates) {
            val right = at + 1
            if (changes[at] < 3.0 || peaks.any { abs(it - right) < 3 }) continue
            if ((chosen + setOf(right - 1, right)).size > 8) continue
            peaks.add(right)
            chosen.add(right - 1)
            chosen.add(right)
            if (peaks.size >= 2 || chosen.size == 8) break
        }
        if (chosen.size < minOf(8, n)) {
            for (idx in (0 until 8).map { (it.toDouble() * (n - 1) / 7).toInt() }) {
                if (chosen.size >= minOf(8, n)) break
                chosen.add(idx)
            }
        }
        if (chosen.size < minOf(8, n)) {
            for (idx in 0 until n) {
                if (chosen.size >= minOf(8, n)) break
                chosen.add(idx)
            }
        }
        return Analysis(idle, chosen.sorted().map { clip.frames[it] }, peak, end)
    }

    fun skippedClip(reason: String) { skipped += 1; status = reason.take(170) }

    /** Store the ORIGINAL episodic/semantic JSON format, ready for later graph import. */
    fun commit(clip: Clip, raw: String, evidenceStoryboard: File? = null,
               selected: List<PhoneTemporalFrame> = emptyList()) {
        val (episodic, semantic) = parseModelJson(raw) ?: run {
            skippedClip("E4B did not return original MemoryWriter JSON; clip not saved")
            return
        }
        if (episodic.isEmpty() && semantic.isEmpty()) {
            skippedClip("No meaningful episodic / semantic memories in clip")
            return
        }
        val id = "clip_${clip.startWallMs}"
        val evidence = File(root, "$id.jpg")
        // Save the actual 8-panel image that E4B saw, not only the last frame.
        try { (evidenceStoryboard ?: clip.frames.last().image).copyTo(evidence, overwrite = true) }
        catch (_: Exception) { skippedClip("Could not preserve evidence"); return }
        val item = JSONObject().put("clip_id", id).put("start_ms", clip.startWallMs)
            .put("end_ms", clip.endWallMs).put("video_description", JSONArray(episodic))
            .put("high_level_conclusions", JSONArray(semantic))
            .put("evidence", evidence.name).put("source", "Aria RGB / Galaxy E4B storyboard")
            .put("evidence_type", if (evidenceStoryboard != null) "8-panel storyboard" else "last JPEG")
            .put("selected_frame_ms", JSONArray(selected.map { it.timeMs }))
            .put("verified", false)
        val clips = try { JSONObject(index.readText()).optJSONArray("clips") ?: JSONArray() }
                    catch (_: Exception) { JSONArray() }
        clips.put(item)
        while (clips.length() > MAX_CLIPS) {
            val old = clips.optJSONObject(0)
            old?.optString("evidence")?.takeIf { it.isNotEmpty() }?.let { File(root, it).delete() }
            clips.remove(0)
        }
        val temp = File(root, "clips.tmp")
        try {
            temp.writeText(JSONObject().put("schema", "atlas-memory-writer-clips-v1")
                .put("clips", clips).toString())
            check(temp.renameTo(index))
            written = clips.length()
            status = "Original-style memory: +${episodic.size} episodic, +${semantic.size} semantic"
        } catch (e: Exception) { status = "Memory file write failed: ${e.message}" }
    }

    fun preview(): String {
        val arr = try { JSONObject(index.readText()).optJSONArray("clips") ?: JSONArray() }
                  catch (_: Exception) { JSONArray() }
        return (maxOf(0, arr.length() - 5) until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val e = c.optJSONArray("video_description")?.optString(0).orEmpty()
            "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(c.optLong("start_ms")))}: ${e.take(160)}"
        }.joinToString("\n").ifBlank { "No saved 30s clips yet" }
    }

    /** Simple text lookup only: actual M3 embedding graph is NOT running on Android yet. */
    fun lookup(question: String): String? {
        val q = question.lowercase(Locale.ROOT)
        if (!Regex("(last|previous|earlier|before|remember|where did|where have|where was|what happened|when did)").containsMatchIn(q)) return null
        val tokens = Regex("[a-z]{3,}").findAll(q).map { it.value }.filterNot {
            it in setOf("where","what","have","this","that","when","last","remember","previous","earlier","before","happened","were","your","mine","about","just","did","the","you","was","put")
        }.toSet()
        if (tokens.isEmpty()) return null
        val arr = try { JSONObject(index.readText()).optJSONArray("clips") ?: JSONArray() }
                  catch (_: Exception) { JSONArray() }
        for (i in arr.length() - 1 downTo 0) {
            val item = arr.optJSONObject(i) ?: continue
            val evidence = item.optJSONArray("video_description") ?: continue
            val lines = (0 until evidence.length()).map { evidence.optString(it) }
            val matched = lines.firstOrNull { line -> tokens.any { tok -> Regex("\\b" + Regex.escape(tok) + "s?\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) } }
            if (matched != null) return "From an earlier, unverified camera observation: $matched. I can't confirm it's still true."
        }
        return null
    }

    fun clear() {
        resetCapture()
        root.listFiles()?.forEach { it.delete() }
        written = 0; skipped = 0
        status = "Cleared original-style visual clip records"
    }
}
