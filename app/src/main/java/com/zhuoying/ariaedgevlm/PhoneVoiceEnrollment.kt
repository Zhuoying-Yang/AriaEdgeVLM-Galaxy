package com.zhuoying.ariaedgevlm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Block 10A - voiceprint ENROLLMENT INPUT, not speaker identity inference.
 * Captures only explicitly user-requested PCM clips from the already running
 * Aria audio transport; this class NEVER opens the phone microphone.
 * The samples live in noBackupFilesDir and are deleted at the user's request.
 * The actual CAM++ embedding extractor and threshold calibration come in 10B.
 */
internal class PhoneVoiceEnrollment(context: Context) {
    private val dir = File(context.noBackupFilesDir, "aria_voiceprint_pilot")

    val prompts = listOf(
        "Tell me what is in front of me right now.",
        "Please remind me to bring my water bottle.",
        "What did I put on the table a moment ago?",
        "I would like to know what I just heard.",
        "Can you help me find my phone again?",
        "Today I am testing the AI glasses assistant."
    )

    var recorded by mutableIntStateOf(countSamples())
        private set
    var validationAvailable by mutableStateOf(File(dir, "validation.wav").isFile)
        private set
    var message by mutableStateOf("Capture six short phrases, then a separate validation phrase. Identity verification remains OFF.")
        private set

    private fun countSamples(): Int = prompts.indices.count { takeFile(it).isFile }
    private fun takeFile(index: Int) = File(dir, "take_%02d.wav".format(java.util.Locale.US, index + 1))
    private fun nextIndex(): Int? = prompts.indices.firstOrNull { !takeFile(it).isFile }

    fun nextPrompt(): String? = nextIndex()?.let { prompts[it] }

    fun captureTake(ingress: PhoneAudioIngress) {
        val index = nextIndex()
        if (index == null) {
            message = "Six clips are already captured. Use the validation clip or Clear samples."
            return
        }
        try {
            val raw = ingress.recentPcm(6)
            val (wave, rms) = trimPcm(raw)
            require(wave.size >= 16_000 * 2 * 2) { "Too little speech audio; speak for 3-4 seconds and tap Capture right away." }
            require(rms >= 0.0025) { "Audio too quiet (RMS %.4f); try speaking closer to Aria.".format(java.util.Locale.US, rms) }
            dir.mkdirs()
            writeWave(takeFile(index), wave)
            recorded = countSamples()
            message = "Saved take ${index + 1}/6 on Galaxy (%.1fs, RMS %.3f). Not verified yet."
                .format(java.util.Locale.US, wave.size / 32000.0, rms)
        } catch (e: Exception) {
            message = "Capture failed: ${e.message}"
        }
    }

    fun captureValidation(ingress: PhoneAudioIngress) {
        if (recorded != prompts.size) {
            message = "Capture all six enrollment phrases first."
            return
        }
        try {
            val (wave, rms) = trimPcm(ingress.recentPcm(6))
            require(wave.size >= 16_000 * 2 * 2) { "Speak for 3-4 seconds before tapping Validate capture." }
            require(rms >= 0.0025) { "Audio too quiet (RMS %.4f).".format(java.util.Locale.US, rms) }
            dir.mkdirs()
            writeWave(File(dir, "validation.wav"), wave)
            validationAvailable = true
            message = "Validation audio saved on Galaxy. CAM++ scoring is NOT installed in Block 10A."
        } catch (e: Exception) {
            message = "Validation capture failed: ${e.message}"
        }
    }

    fun clear() {
        try {
            if (dir.exists()) check(dir.deleteRecursively()) { "Could not erase enrollment directory" }
            recorded = 0
            validationAvailable = false
            message = "Local voice samples erased. Automatic assistant remains unchanged."
        } catch (e: Exception) {
            message = "Erase failed: ${e.message}"
        }
    }

    /** Rough -30dB leading/trailing silence trimming, exactly 16 kHz mono PCM16. */
    private fun trimPcm(input: ByteArray): Pair<ByteArray, Double> {
        require(input.size >= 32000 && input.size % 2 == 0) { "Expected fresh 16 kHz PCM16 audio" }
        val samplesPerFrame = 160
        val frames = input.size / (samplesPerFrame * 2)
        val energy = DoubleArray(frames)
        for (f in 0 until frames) {
            var sq = 0.0
            val start = f * samplesPerFrame * 2
            for (i in 0 until samplesPerFrame) {
                val idx = start + i * 2
                val sample = (((input[idx + 1].toInt() and 255) shl 8) or
                    (input[idx].toInt() and 255)).toShort().toInt() / 32768.0
                sq += sample * sample
            }
            energy[f] = sqrt(sq / samplesPerFrame)
        }
        val peak = energy.maxOrNull() ?: 0.0
        require(peak >= 0.004) { "No sufficiently audible voice detected. Try again." }
        val cutoff = peak * 0.0316227766  // -30 dB relative amplitude
        val first = energy.indexOfFirst { it >= cutoff }.coerceAtLeast(0)
        val last = energy.indexOfLast { it >= cutoff }.coerceAtLeast(first)
        val head = (first - 10).coerceAtLeast(0) * samplesPerFrame * 2
        val tail = ((last + 11).coerceAtMost(frames) * samplesPerFrame * 2)
        val clipped = input.copyOfRange(head, tail.coerceAtLeast(head))
        var square = 0.0
        for (i in clipped.indices step 2) {
            val v = (((clipped[i + 1].toInt() and 255) shl 8) or
                (clipped[i].toInt() and 255)).toShort().toInt() / 32768.0
            square += v * v
        }
        return clipped to sqrt(square / (clipped.size / 2))
    }

    private fun writeWave(file: File, pcm: ByteArray) {
        // Standard 44-byte little-endian RIFF/WAVE header.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16)
        header.putShort(1); header.putShort(1); header.putInt(16_000)
        header.putInt(32_000); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(pcm.size)
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { it.write(header.array()); it.write(pcm); it.fd.sync() }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }
}
