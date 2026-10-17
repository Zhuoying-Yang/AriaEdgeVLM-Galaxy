package com.zhuoying.ariaedgevlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Block 10B: real 3D-Speaker CAM++ embeddings, CPU, ON GALAXY.
 * Evaluation ONLY; does not reject any Hey Atlas requests or gate safety events.
 * No waveform or embeddings are sent to Mac/cloud.
 */
internal class PhoneCamPlusVerifier(private val context: Context) {
    companion object {
        private const val MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        private const val MAGIC = "ATLAS_CAMPP_V1"
    }
    private val directory = File(context.noBackupFilesDir, "aria_voiceprint_pilot")
    private val profileFile = File(directory, "owner_embedding.bin")
    private val modelFile = File(context.filesDir, MODEL)
    private val handler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)

    var busy by mutableStateOf(false)
        private set
    var info by mutableStateOf(if (profileFile.isFile) "Owner embedding saved; identity blocking OFF." else "No owner embedding yet. Identity blocking OFF.")
        private set
    var scoreSummary by mutableStateOf("")
        private set
    val modelPresent get() = modelFile.isFile && modelFile.length() > 10_000_000
    val enrolled get() = profileFile.isFile

    /** Execute CPU model off the Compose/main thread. */
    private fun submit(label: String, operation: () -> String) {
        if (!processing.compareAndSet(false, true)) return
        busy = true
        info = "$label running on Galaxy CPU..."
        Thread({
            val outcome = try { operation() } catch (e: Exception) {
                "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            } catch (e: UnsatisfiedLinkError) {
                "ERROR: sherpa-onnx native library missing: ${e.message}"
            }
            handler.post {
                info = outcome
                busy = false
                processing.set(false)
            }
        }, "campp-phone-embedding").start()
    }

    private fun extractor(): SpeakerEmbeddingExtractor {
        check(modelPresent) { "CAM++ model missing in app filesDir; install it before scoring." }
        return SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
    }

    private fun wavToFloat(file: File): FloatArray {
        require(file.isFile) { "Missing ${file.name}. Capture audio first." }
        val bytes = file.readBytes()
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Invalid WAV: ${file.name}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getShort(20).toInt() == 1 && buf.getShort(22).toInt() == 1 &&
            buf.getInt(24) == 16_000 && buf.getShort(34).toInt() == 16) {
            "Expected 16 kHz mono PCM16 WAV: ${file.name}"
        }
        // Block 10A writer creates a canonical 44-byte WAV header.
        require(String(bytes, 36, 4, Charsets.US_ASCII) == "data") { "WAV data chunk missing" }
        val length = buf.getInt(40)
        require(length >= 2 * 16_000 && length % 2 == 0 && 44L + length <= bytes.size) {
            "Invalid/too short audio: ${file.name}"
        }
        return FloatArray(length / 2) { n -> buf.getShort(44 + n * 2) / 32768.0f }
    }

    private fun pcmToFloat(pcm: ByteArray): FloatArray {
        require(pcm.size >= 64_000 && pcm.size % 2 == 0) { "At least 2s of recent Aria audio required." }
        return FloatArray(pcm.size / 2) { n ->
            val lo = pcm[2 * n].toInt() and 255
            val hi = pcm[2 * n + 1].toInt() and 255
            (((hi shl 8) or lo).toShort().toInt()) / 32768.0f
        }
    }

    private fun embedding(model: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray {
        require(samples.size >= 32_000) { "Voice too short for CAM++." }
        val stream = model.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            stream.inputFinished()
            check(model.isReady(stream)) { "CAM++ feature extraction not ready." }
            val emb = model.compute(stream)
            check(emb.size == 192) { "Unexpected embedding dimension ${emb.size}, expected 192" }
            return unit(emb)
        } finally {
            stream.release()
        }
    }

    private fun unit(raw: FloatArray): FloatArray {
        val norm = sqrt(raw.sumOf { it.toDouble() * it }.toFloat())
        require(norm > 1e-8f) { "Invalid zero-length CAM++ embedding." }
        return FloatArray(raw.size) { raw[it] / norm }
    }
    private fun average(vectors: List<FloatArray>) = unit(FloatArray(vectors[0].size) { k ->
        vectors.sumOf { it[k].toDouble() }.toFloat() / vectors.size
    })
    private fun similarity(x: FloatArray, y: FloatArray): Float {
        require(x.size == y.size) { "Voice embedding dimensions differ." }
        return x.indices.sumOf { (x[it] * y[it]).toDouble() }.toFloat()
    }
    private fun format(x: Float) = "%.3f".format(Locale.US, x)

    private data class Profile(val threshold: Float, val vector: FloatArray)
    private fun store(profile: Profile) {
        directory.mkdirs()
        val temp = File(directory, "owner_embedding.bin.tmp")
        try {
            DataOutputStream(FileOutputStream(temp)).use { output ->
                output.writeUTF(MAGIC)
                output.writeFloat(profile.threshold)
                output.writeInt(profile.vector.size)
                profile.vector.forEach(output::writeFloat)
            }
            if (!temp.renameTo(profileFile)) {
                temp.copyTo(profileFile, overwrite = true)
                temp.delete()
            }
        } finally { temp.delete() }
    }
    private fun load(): Profile {
        check(profileFile.isFile) { "Build owner profile first." }
        return DataInputStream(FileInputStream(profileFile)).use { input ->
            require(input.readUTF() == MAGIC) { "Unknown profile format" }
            val threshold = input.readFloat()
            val length = input.readInt()
            require(length == 192 && threshold in 0.0f..1.0f) { "Invalid stored voice profile" }
            Profile(threshold, unit(FloatArray(length) { input.readFloat() }))
        }
    }

    fun buildProfile() = submit("CAM++ enrollment") {
        val takes = (1..6).map { File(directory, "take_%02d.wav".format(Locale.US, it)) }
        takes.forEach { require(it.isFile) { "Missing ${it.name}; capture six enrollment phrases first." } }
        val model = extractor()
        try {
            val vectors = takes.map { embedding(model, wavToFloat(it)) }
            val self = vectors.indices.map { idx ->
                similarity(vectors[idx], average(vectors.filterIndexed { i, _ -> i != idx }))
            }
            val threshold = (self.minOrNull()!! - 0.15f).coerceIn(0.30f, 0.55f)
            // provisional threshold ONLY; must evaluate impostor recordings before owner-only mode.
            store(Profile(threshold, average(vectors)))
            "192-D owner profile saved locally. LOO similarity: ${self.joinToString { format(it) }}. " +
                "Pilot threshold ${format(threshold)} (NOT validated). Commands are NOT gated."
        } finally { model.release() }
    }

    fun scoreValidation() = submit("Held-out validation") {
        val profile = load()
        val model = extractor()
        try {
            val score = similarity(profile.vector,
                embedding(model, wavToFloat(File(directory, "validation.wav"))))
            val message = "Held-out OWN voice score ${format(score)} / pilot threshold ${format(profile.threshold)}. " +
                "Result: ${if (score >= profile.threshold) "similar" else "below threshold"}. " +
                "Need other-speaker tests; no identity blocking enabled."
            handler.post { scoreSummary = message }
            message
        } finally { model.release() }
    }

    fun scoreFresh(ingress: PhoneAudioIngress) {
        val pcm = try { ingress.recentPcm(5) } catch (e: Exception) {
            info = "ERROR: No fresh Aria audio: ${e.message}"; return
        }
        submit("Live speaker test") {
            val profile = load()
            val model = extractor()
            try {
                val score = similarity(profile.vector, embedding(model, pcmToFloat(pcm)))
                val message = "Recent Aria voice score ${format(score)} / pilot threshold " +
                    "${format(profile.threshold)}: ${if (score >= profile.threshold) "similar" else "below threshold"}. " +
                    "Record other speakers for comparison; NO automatic blocking."
                handler.post { scoreSummary = message }
                message
            } finally { model.release() }
        }
    }

    // VOICE_GATE_BLOCK10C: compare real Aria PCM against the saved owner profile.
    // The callback always runs on the main thread. No speech/model data leaves Galaxy.
    // A busy/missing-model/error result has score=null and MUST fail closed in owner-only mode.
    fun scoreCommandPcm(pcm: ByteArray, callback: (Float?, String?) -> Unit) {
        if (!processing.compareAndSet(false, true)) {
            handler.post { callback(null, "CAM++ busy; try again") }
            return
        }
        Thread({
            var score: Float? = null
            var error: String? = null
            try {
                val profile = load()
                val model = extractor()
                try {
                    score = similarity(profile.vector, embedding(model, pcmToFloat(pcm)))
                } finally {
                    model.release()
                }
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            } catch (e: UnsatisfiedLinkError) {
                error = "CAM++ native library not loaded"
            }
            handler.post {
                processing.set(false)
                callback(score, error)
            }
        }, "aria-voice-gate").apply { isDaemon = true; start() }
    }

    fun clearScore() { scoreSummary = ""; info = "Voiceprint scores cleared from screen. Stored profile is unchanged." }
}
