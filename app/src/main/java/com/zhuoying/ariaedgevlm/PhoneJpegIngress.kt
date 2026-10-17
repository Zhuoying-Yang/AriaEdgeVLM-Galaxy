package com.zhuoying.ariaedgevlm

import android.os.Handler
import android.os.Looper
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

/**
 * Development-only Mac -> Galaxy JPEG inlet.
 *
 * Binds ONLY to Android's 127.0.0.1. adb forward tcp:18765 tcp:18765 allows
 * the Mac to POST to this port over USB. It never creates an Internet endpoint.
 * Starts on explicit user request; not a background service. NEVER handles audio.
 */
class PhoneJpegIngress(private val memory: PhoneTemporalMemory) {
    // ATLAS_DEMO_UI_V1: only expose a read-only snapshot reference to the existing live buffer.
    fun atlasDemoLatestJpeg(): java.io.File? = memory.recentLiveFrames().lastOrNull()?.image

    companion object {
        const val PORT = 18765
        private const val MAX_JPEG_BYTES = 1_500_000
    }

    var onStoredFrame: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private var listener: ServerSocket? = null

    var running by mutableStateOf(false)
        private set
    var totalReceived by mutableIntStateOf(0)
        private set
    var storedFrames by mutableIntStateOf(0)
        private set
    var windowFrames by mutableIntStateOf(0)
        private set
    var lastError by mutableStateOf("")
        private set

    fun start() {
        synchronized(lifecycleLock) {
            if (listener != null) return
            try {
                val bound = ServerSocket()
                bound.reuseAddress = true
                bound.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
                listener = bound
                running = true
                lastError = ""
                Thread({ serve(bound) }, "galaxy-jpeg-inlet").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                lastError = "Receiver failed to start: ${e.message}"
                running = false
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            val old = listener
            listener = null
            try { old?.close() } catch (_: Exception) { }
            running = false
        }
    }

    private fun serve(server: ServerSocket) {
        try {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: java.net.SocketException) { break }
                try {
                    client.use { handle(it) }
                } catch (e: Exception) {
                    main.post { lastError = "Frame request: ${e.message}" }
                }
            }
        } finally {
            main.post {
                synchronized(lifecycleLock) {
                    if (listener === server) {
                        listener = null
                        running = false
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
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
            return respond(socket, 200, "ready", "\"received\":$totalReceived,\"stored\":$storedFrames")
        }
        if (parts[0] != "POST" || parts[1] != "/frame") {
            return respond(socket, 404, "not_found")
        }
        if (!headers["content-type"].orEmpty().lowercase().startsWith("image/jpeg")) {
            return respond(socket, 415, "jpeg_required")
        }
        if (headers.containsKey("transfer-encoding")) return respond(socket, 400, "chunked_not_supported")
        val length = headers["content-length"]?.toIntOrNull()
            ?: return respond(socket, 411, "content_length_required")
        if (length < 4 || length > MAX_JPEG_BYTES) return respond(socket, 413, "jpeg_too_large")
        val image = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(image, at, length - at)
            if (n < 0) return respond(socket, 400, "truncated_jpeg")
            at += n
        }
        if (image[0] != 0xff.toByte() || image[1] != 0xd8.toByte() ||
            image[length - 2] != 0xff.toByte() || image[length - 1] != 0xd9.toByte()) {
            return respond(socket, 400, "invalid_jpeg")
        }
        val stored = memory.appendLiveJpeg(image)
        val recent = memory.recentLiveFrames().size
        main.post {
            totalReceived += 1
            if (stored) {
                storedFrames += 1
                try { onStoredFrame?.invoke() } catch (e: Exception) {
                    android.util.Log.e("AtlasSpatialRelay", "Frame callback FAILED: ${e.message}", e)
                }
            }
            windowFrames = recent
        }
        respond(socket, 200, if (stored) "stored" else "rate_limited", "\"window_frames\":$recent")
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream(128)
        while (bytes.size() <= 2048) {
            val ch = input.read()
            if (ch < 0) return null
            if (ch == 10) {
                val raw = bytes.toString(StandardCharsets.US_ASCII.name())
                return raw.trimEnd('\r')
            }
            bytes.write(ch)
        }
        throw IllegalArgumentException("HTTP header line too long")
    }

    private fun respond(socket: Socket, code: Int, state: String, extra: String = "") {
        val message = "{\"status\":\"$state\"${if (extra.isEmpty()) "" else ",$extra"}}"
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }
}
