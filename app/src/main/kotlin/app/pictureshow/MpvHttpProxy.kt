/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Server
import app.pictureshow.core.playback.PlayDecisions
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * PLAY-60: mpv's FFmpeg is built without TLS and never sees the viewer's token. It reads `http://127.0.0.1:<port>/<secret>/...`
 * from this proxy, which forwards the path and query to the server's origin through [client] (the same scoped
 * OkHttp client Media3 uses: TLS, the decision's headers on the server's origin only, ApiKey stripping). The random
 * path secret keeps other apps on the device from borrowing the viewer's sign-in through the open port.
 *
 * The socket binds to 127.0.0.1 only, so nothing off the device can connect. Another app on the device can, so a
 * connection proves the secret on its request line before anything else is read, the headers that follow are capped in
 * count, bytes and time, and at most [MAX_WORKERS] connections are served at once (mpv opens one or two).
 */
internal class MpvHttpProxy(private val server: Server, private val client: OkHttpClient) {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val workers = ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
        Thread(runnable, "mpv-proxy").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val origin = URI(server.url.trimEnd('/'))

    init {
        Thread({
            while (!closed.get()) {
                val connection = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    workers.execute { serve(connection) }
                } catch (_: RejectedExecutionException) {
                    runCatching { connection.close() }
                }
            }
        }, "mpv-proxy-accept").apply { isDaemon = true }.start()
    }

    /** The loopback URL for a server URL, or null when [url] isn't on the server's origin (never proxied). */
    fun local(url: String): String? {
        val clean = app.pictureshow.core.playback.Redaction.stripApiKey(url)
        if (!PlayDecisions.isSameOrigin(server, clean)) return null
        val uri = URI(clean)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:${socket.localPort}/$secret${uri.rawPath.orEmpty()}$query"
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private fun serve(connection: Socket) = connection.use { socket ->
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEADER_DEADLINE_MS)
        val requestLine = readLine(socket, input, deadline, MAX_LINE) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1).orEmpty()
        if ((method != "GET" && method != "HEAD") || !authorized(target)) {
            status(output, 404, "Not Found")
            return
        }
        val kept = mutableListOf<Pair<String, String>>()
        var headerBytes = 0
        var count = 0
        while (true) {
            val line = readLine(socket, input, deadline, MAX_HEADER_BYTES - headerBytes) ?: return
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (++count > MAX_HEADERS) {
                status(output, 431, "Request Header Fields Too Large")
                return
            }
            val name = line.substringBefore(':').trim()
            if (name.equals("Range", true) || name.equals("If-Range", true)) kept += name to line.substringAfter(':').trim()
        }
        socket.soTimeout = READ_TIMEOUT_MS
        val prefix = "/$secret"
        val upstream = "${origin.scheme}://${origin.rawAuthority}${target.removePrefix(prefix)}"
        val request = Request.Builder().url(upstream).apply {
            if (method == "HEAD") head()
            kept.forEach { (name, value) -> header(name, value) }
        }.build()
        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                val head = StringBuilder("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
                for (name in FORWARDED) response.header(name)?.let { head.append("$name: $it\r\n") }
                head.append("Connection: close\r\n\r\n")
                output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (method != "HEAD") response.body.byteStream().copyTo(output, BUFFER_BYTES)
                output.flush()
            }
        } catch (_: IOException) {
            // mpv closed the connection (a seek or stop) or the server failed; mpv sees the short read and retries.
            call.cancel()
        }
    }

    private fun status(output: OutputStream, code: Int, text: String) {
        output.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Whether [target] starts with `/<secret>/`, compared in constant time. */
    private fun authorized(target: String): Boolean {
        val expected = "/$secret/".toByteArray(Charsets.ISO_8859_1)
        val given = target.take(expected.size).toByteArray(Charsets.ISO_8859_1)
        return MessageDigest.isEqual(expected, given)
    }

    /** One CRLF line of at most [limit] bytes, or null at end of stream, past the limit or past [deadline]. */
    private fun readLine(socket: Socket, input: InputStream, deadline: Long, limit: Int): String? {
        val line = StringBuilder()
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return null
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val byte = try {
                input.read()
            } catch (_: java.net.SocketTimeoutException) {
                return null
            }
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= limit) return null
            line.append(byte.toChar())
        }
    }

    private companion object {
        const val BACKLOG = 16
        const val SECRET_BYTES = 16
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_LINE = 16 * 1024
        const val MAX_HEADERS = 32
        const val MAX_HEADER_BYTES = 8 * 1024
        const val HEADER_DEADLINE_MS = 5_000L
        const val MAX_WORKERS = 8
        val FORWARDED = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")
    }
}
