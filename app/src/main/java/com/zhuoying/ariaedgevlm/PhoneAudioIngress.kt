package com.zhuoying.ariaedgevlm

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Explicit, foreground-only receiver for Aria PCM16 audio forwarded by Mac.
 * TCP is bound to the PHONE's loopback; ADB port forward bridges Mac -> phone.
 * 16 kHz, 1 channel, signed little-endian PCM16. Keeps at most 12 seconds in RAM.
 * NO ASR, TTS, microphone capture, cloud upload or file writes occur here.
 */
internal class PhoneAudioIngress {
    companion object {
        const val PORT = 18766
        const val SAMPLE_RATE = 16_000
        private const val MAX_POST = 32_000 // max 1 s of PCM16
        private const val MAX_BUFFER = 12 * SAMPLE_RATE * 2
    }

    /** Multiple independent consumers can share the same Aria PCM (Vosk, YAMNet, voiceprint). */
    private val pcmObservers = java.util.concurrent.CopyOnWriteArrayList<(ByteArray) -> Unit>()

    fun addPcmObserver(callback: (ByteArray) -> Unit) {
        pcmObservers.addIfAbsent(callback)
    }

    fun removePcmObserver(callback: (ByteArray) -> Unit) {
        pcmObservers.remove(callback)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var server: ServerSocket? = null
    private val pcmChunks = ArrayDeque<ByteArray>()
    private var bufferedBytes = 0
    private var lastChunkAt = 0L

    var running by mutableStateOf(false)
        private set
    var receivedChunks by mutableIntStateOf(0)
        private set
    var bufferedSeconds by mutableStateOf(0.0)
        private set
    var level by mutableStateOf(0.0)
        private set
    var lastError by mutableStateOf("")
        private set

    fun start() {
        synchronized(lock) {
            if (server != null) return
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
                server = s
                running = true
                lastError = ""
                Thread({ acceptLoop(s) }, "phone-aria-pcm").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                running = false
                lastError = "Audio receiver: ${e.message}"
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val s = server
            server = null
            try { s?.close() } catch (_: Exception) {}
            running = false
        }
    }

    /** Returns the latest 6 seconds of actual PCM bytes (NOT the Android microphone). */
    fun recentPcm(seconds: Int = 6): ByteArray {
        synchronized(lock) {
            require(lastChunkAt != 0L && SystemClock.elapsedRealtime() - lastChunkAt < 2500L) {
                "No fresh Aria audio. Keep Mac audio sender running."
            }
            val take = (seconds.coerceIn(1, 12) * SAMPLE_RATE * 2).coerceAtMost(bufferedBytes)
            val output = ByteArray(take)
            var dest = take
            val it = pcmChunks.descendingIterator()
            while (it.hasNext() && dest > 0) {
                val next = it.next()
                val n = minOf(dest, next.size)
                System.arraycopy(next, next.size - n, output, dest - n, n)
                dest -= n
            }
            require(output.size >= SAMPLE_RATE * 2) { "Need at least 1 second of Aria audio" }
            return output
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        try {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (_: java.net.SocketException) { break }
                try { socket.use { handle(it) } }
                catch (e: Exception) { main.post { lastError = "Audio request: ${e.message}" } }
            }
        } finally {
            main.post { synchronized(lock) { if (server === s) { server = null; running = false } } }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 4000
        val input = BufferedInputStream(socket.getInputStream())
        val request = readLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return respond(socket, 400, "bad_request")
        val headers = HashMap<String, String>()
        for (i in 0 until 24) {
            val line = readLine(input) ?: return respond(socket, 400, "bad_headers")
            if (line.isEmpty()) break
            val pos = line.indexOf(':')
            if (pos > 0) headers[line.substring(0, pos).trim().lowercase()] =
                line.substring(pos + 1).trim()
            if (i == 23) return respond(socket, 431, "too_many_headers")
        }
        if (parts[0] == "GET" && parts[1] == "/health") {
            return respond(socket, 200, "ready", "\"received\":$receivedChunks,\"seconds\":$bufferedSeconds")
        }
        if (parts[0] != "POST" || parts[1] != "/pcm16") return respond(socket, 404, "not_found")
        if (headers["content-type"] != "application/octet-stream") return respond(socket, 415, "pcm16_required")
        if (headers.containsKey("transfer-encoding")) return respond(socket, 400, "chunked_not_supported")
        val length = headers["content-length"]?.toIntOrNull() ?: return respond(socket, 411, "length_required")
        if (length !in 2..MAX_POST || length % 2 != 0) return respond(socket, 413, "invalid_pcm_length")
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) return respond(socket, 400, "truncated")
            read += n
        }
        var square = 0.0
        for (i in 0 until length step 2) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 255)).toShort().toInt()
            val normalized = sample.toDouble() / 32768.0
            square += normalized * normalized
        }
        val rms = sqrt(square / (length / 2))
        val duration = synchronized(lock) {
            pcmChunks.addLast(bytes)
            bufferedBytes += length
            lastChunkAt = SystemClock.elapsedRealtime()
            while (bufferedBytes > MAX_BUFFER && pcmChunks.isNotEmpty()) {
                bufferedBytes -= pcmChunks.removeFirst().size
            }
            bufferedBytes / (2.0 * SAMPLE_RATE)
        }
        // Vosk worker gets the actual PCM packet, not a microphone recording.
        for (listener in pcmObservers) {
            try { listener(bytes) }
            catch (_: Exception) { /* one consumer cannot break the proven audio transport */ }
        }
        main.post {
            receivedChunks += 1
            bufferedSeconds = duration
            level = rms
        }
        respond(socket, 200, "stored")
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream(128)
        while (bytes.size() <= 2048) {
            val ch = input.read()
            if (ch < 0) return null
            if (ch == 10) return bytes.toString(StandardCharsets.US_ASCII.name()).trimEnd('\r')
            bytes.write(ch)
        }
        throw IllegalArgumentException("HTTP header too long")
    }

    private fun respond(socket: Socket, code: Int, state: String, extra: String = "") {
        val body = "{\"status\":\"$state\"${if (extra.isEmpty()) "" else ",$extra"}}"
            .toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.US_ASCII)); write(body); flush()
        }
    }
}
