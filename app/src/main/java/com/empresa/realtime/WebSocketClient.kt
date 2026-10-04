// [Android / JVM, Kotlin, any arch] — RFC 6455 WebSocket client, stdlib only, auto-reconnect
package com.empresa.realtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock

class WebSocketClient(
    private val serverUrl: String,
    private val clientId: String,
    private val authToken: String,
    private val listener: MessageListener
) {

    interface MessageListener {
        fun onConnected()
        fun onMessage(data: String)
        fun onDisconnected(reason: String)
        fun onError(error: Exception)
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val PING_INTERVAL_MS = 20_000L
        const val PONG_DEADLINE_MS = 45_000L
        const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L

        val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

        fun base64(input: ByteArray): String {
            val sb = StringBuilder((input.size + 2) / 3 * 4)
            var i = 0
            while (i < input.size) {
                val b0 = input[i].toInt() and 0xFF
                val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else -1
                sb.append(B64[b0 ushr 2])
                sb.append(B64[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
                sb.append(if (b1 >= 0) B64[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)] else '=')
                sb.append(if (b2 >= 0) B64[b2 and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }
    }

    private class ServerCloseException(val code: Int, val reason: String) :
        IOException("server close $code: $reason")

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val writeLock = ReentrantLock()
    private val sleepLock = Object()
    private val random = SecureRandom()

    @Volatile private var ioThread: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var lastRxAt = 0L
    @Volatile private var lastPingAt = 0L

    // ── Public API ──────────────────────────────────────────────────────────────

    fun connect() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ ioLoop() }, "ws-io-$clientId")
        t.isDaemon = true
        ioThread = t
        t.start()
    }

    fun disconnect() {
        if (!running.compareAndSet(true, false)) return
        if (connected.get()) {
            runCatching { sendFrame(OP_CLOSE, closePayload(1000, "client disconnect")) }
        }
        closeSocketQuietly()
        synchronized(sleepLock) { sleepLock.notifyAll() }
        ioThread?.interrupt()
    }

    /** Send a UTF-8 text frame. Returns false if not connected. */
    fun send(message: String): Boolean {
        if (!connected.get()) return false
        return try {
            sendFrame(OP_TEXT, message.toByteArray(StandardCharsets.UTF_8))
            true
        } catch (e: IOException) {
            closeSocketQuietly()
            false
        }
    }

    /**
     * Send a binary frame (opcode 0x2).
     * Used by DispatchService to push screen-capture JPEG packets.
     * Returns false if not connected or on write error.
     */
    fun sendBinary(data: ByteArray): Boolean {
        if (!connected.get()) return false
        return try {
            sendFrame(OP_BINARY, data)
            true
        } catch (e: IOException) {
            closeSocketQuietly()
            false
        }
    }

    fun isConnected(): Boolean = connected.get()

    // ── I/O loop ────────────────────────────────────────────────────────────────

    private fun ioLoop() {
        var backoff = INITIAL_BACKOFF_MS
        while (running.get()) {
            var reason = "unknown"
            var wasConnected = false
            try {
                openAndHandshake()
                connected.set(true)
                wasConnected = true
                backoff = INITIAL_BACKOFF_MS
                safeCall { listener.onConnected() }
                readLoop()
                reason = "stream ended"
            } catch (e: ServerCloseException) {
                reason = "server closed (${e.code}) ${e.reason}"
            } catch (e: Exception) {
                reason = e.message ?: e.javaClass.simpleName
                if (running.get()) safeCall { listener.onError(e) }
            } finally {
                connected.set(false)
                closeSocketQuietly()
            }

            if (!running.get()) {
                safeCall { listener.onDisconnected("client disconnect") }
                break
            }
            if (wasConnected) safeCall { listener.onDisconnected(reason) }

            val jitter = (backoff * 0.2 * (random.nextDouble() * 2 - 1)).toLong()
            sleepInterruptibly(backoff + jitter)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        ioThread = null
    }

    private fun sleepInterruptibly(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        synchronized(sleepLock) {
            while (running.get()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                try { sleepLock.wait(left) } catch (_: InterruptedException) { return }
            }
        }
    }

    // ── Handshake (RFC 6455 §4.1) ───────────────────────────────────────────────

    private fun openAndHandshake() {
        val uri = URI(serverUrl)
        val scheme = uri.scheme?.lowercase() ?: throw IOException("URL sem scheme")
        val secure = when (scheme) {
            "wss" -> true
            "ws" -> false
            else -> throw IOException("scheme inválido: $scheme")
        }
        val host = uri.host ?: throw IOException("URL sem host")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            // Append client_id as query param so server can identify before first hello frame.
            append("?client_id=").append(urlEncode(clientId))
            if (!uri.rawQuery.isNullOrEmpty()) append('&').append(uri.rawQuery)
        }

        val s: Socket = if (secure) {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = CONNECT_TIMEOUT_MS
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                ssl.close()
                throw IOException("TLS hostname verification falhou para $host")
            }
            ssl
        } else {
            Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        }
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = CONNECT_TIMEOUT_MS
        socket = s

        val out = s.getOutputStream()
        val inp = BufferedInputStream(s.getInputStream())

        val keyBytes = ByteArray(16).also { random.nextBytes(it) }
        val key = base64(keyBytes)
        val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"

        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Authorization: Bearer ").append(sanitizeHeader(authToken)).append("\r\n")
            append("X-Client-ID: ").append(sanitizeHeader(clientId)).append("\r\n")
            append("User-Agent: FleetWS/1.0\r\n")
            append("\r\n")
        }
        out.write(req.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()

        val status = readHttpLine(inp)
        val parts = status.split(' ', limit = 3)
        if (parts.size < 2 || parts[1] != "101") {
            throw IOException("handshake rejeitado: $status")
        }
        val headers = HashMap<String, String>()
        while (true) {
            val line = readHttpLine(inp)
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (!headers["upgrade"].equals("websocket", ignoreCase = true))
            throw IOException("Upgrade header inválido")
        if (headers["connection"]?.lowercase()?.contains("upgrade") != true)
            throw IOException("Connection header inválido")

        val expected = base64(
            MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).toByteArray(StandardCharsets.ISO_8859_1))
        )
        if (headers["sec-websocket-accept"] != expected)
            throw IOException("Sec-WebSocket-Accept inválido")

        s.soTimeout = READ_TIMEOUT_MS
        output = out
        inputStream = inp
        val now = System.currentTimeMillis()
        lastRxAt = now
        lastPingAt = now
    }

    @Volatile private var inputStream: InputStream? = null

    private fun readHttpLine(inp: InputStream): String {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = inp.read()
            if (b == -1) throw EOFException("EOF durante handshake")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) throw IOException("linha HTTP grande demais")
        }
        return String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun sanitizeHeader(v: String): String =
        v.replace("\r", "").replace("\n", "")

    // ── Read loop (RFC 6455 §5) ─────────────────────────────────────────────────

    private fun readLoop() {
        val inp = inputStream ?: throw IOException("sem input stream")
        val fragments = ByteArrayOutputStream()
        var fragOpcode = -1

        while (running.get()) {
            val b0: Int
            try {
                b0 = inp.read()
            } catch (_: SocketTimeoutException) {
                keepAliveTick()
                continue
            }
            if (b0 == -1) throw EOFException("servidor fechou o TCP")

            val fin = (b0 and 0x80) != 0
            if ((b0 and 0x70) != 0) throw IOException("RSV bits sem extensão negociada")
            val opcode = b0 and 0x0F

            val b1 = readByteBlocking(inp)
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            when (len) {
                126L -> len = ((readByteBlocking(inp) shl 8) or readByteBlocking(inp)).toLong()
                127L -> {
                    len = 0
                    repeat(8) { len = (len shl 8) or readByteBlocking(inp).toLong() }
                    if (len < 0) throw IOException("payload length 64-bit com MSB setado")
                }
            }
            if (len > MAX_MESSAGE_BYTES) throw IOException("frame grande demais: $len")

            val mask = if (masked) ByteArray(4).also { readFully(inp, it) } else null
            val payload = ByteArray(len.toInt())
            readFully(inp, payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            lastRxAt = System.currentTimeMillis()

            when (opcode) {
                OP_TEXT, OP_BINARY -> {
                    if (fragOpcode != -1) throw IOException("novo data frame no meio de fragmentação")
                    if (fin) {
                        if (opcode == OP_TEXT) deliver(payload)
                        // Binary frames from server are not expected; silently drop.
                    } else {
                        fragOpcode = opcode
                        fragments.reset()
                        fragments.write(payload)
                    }
                }
                OP_CONT -> {
                    if (fragOpcode == -1) throw IOException("continuation sem frame inicial")
                    if (fragments.size() + payload.size > MAX_MESSAGE_BYTES) throw IOException("mensagem fragmentada grande demais")
                    fragments.write(payload)
                    if (fin) {
                        if (fragOpcode == OP_TEXT) deliver(fragments.toByteArray())
                        fragments.reset()
                        fragOpcode = -1
                    }
                }
                OP_PING -> {
                    if (!fin || payload.size > 125) throw IOException("PING inválido")
                    sendFrame(OP_PONG, payload)
                }
                OP_PONG -> { /* lastRxAt already updated */ }
                OP_CLOSE -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, StandardCharsets.UTF_8) else ""
                    runCatching { sendFrame(OP_CLOSE, if (payload.size >= 2) payload.copyOf(2) else ByteArray(0)) }
                    throw ServerCloseException(code, reason)
                }
                else -> throw IOException("opcode desconhecido: $opcode")
            }
        }
    }

    private fun deliver(bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        safeCall { listener.onMessage(text) }
    }

    private fun keepAliveTick() {
        val now = System.currentTimeMillis()
        if (now - lastRxAt > PONG_DEADLINE_MS) throw IOException("sem tráfego há ${now - lastRxAt}ms — conexão morta")
        if (now - lastPingAt >= PING_INTERVAL_MS) {
            lastPingAt = now
            sendFrame(OP_PING, now.toString().toByteArray(StandardCharsets.US_ASCII))
        }
    }

    private fun readByteBlocking(inp: InputStream): Int {
        var waited = 0L
        while (true) {
            try {
                val b = inp.read()
                if (b == -1) throw EOFException("EOF no meio do frame")
                return b
            } catch (e: SocketTimeoutException) {
                waited += READ_TIMEOUT_MS
                if (waited >= PONG_DEADLINE_MS || !running.get()) throw IOException("timeout no meio do frame", e)
            }
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        var waited = 0L
        while (off < buf.size) {
            try {
                val n = inp.read(buf, off, buf.size - off)
                if (n == -1) throw EOFException("EOF no meio do payload")
                off += n
                waited = 0
            } catch (e: SocketTimeoutException) {
                waited += READ_TIMEOUT_MS
                if (waited >= PONG_DEADLINE_MS || !running.get()) throw IOException("timeout no meio do payload", e)
            }
        }
    }

    // ── Frame writer (client always masks, §5.3) ────────────────────────────────

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: throw IOException("não conectado")
        val len = payload.size
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)   // FIN=1
        when {
            len <= 125 -> header.write(0x80 or len)
            len <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(len ushr 8); header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val l = len.toLong()
                for (shift in 56 downTo 0 step 8) header.write(((l ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

        writeLock.withLock {
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    private fun closePayload(code: Int, reason: String): ByteArray {
        val r = reason.toByteArray(StandardCharsets.UTF_8).let { if (it.size > 123) it.copyOf(123) else it }
        return byteArrayOf((code ushr 8).toByte(), (code and 0xFF).toByte()) + r
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    private fun closeSocketQuietly() {
        val s = socket
        socket = null
        output = null
        inputStream = null
        runCatching { s?.close() }
    }

    private inline fun safeCall(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (t is Exception) runCatching { listener.onError(t) }
        }
    }

    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")
}
