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
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * PLAY-60: mpv's FFmpeg is built without TLS and never sees the viewer's token. It reads `http://127.0.0.1:<port>/<secret>/...`
 * from this proxy, which forwards the path and query to the server's origin through [client] (the same scoped
 * OkHttp client Media3 uses: TLS, the decision's headers on the server's origin only, ApiKey stripping). The random
 * path secret keeps other apps on the device from borrowing the viewer's sign-in through the open port.
 */
internal class MpvHttpProxy(private val server: Server, private val client: OkHttpClient) {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "mpv-proxy").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val origin = URI(server.url.trimEnd('/'))

    init {
        workers.execute {
            while (!closed.get()) {
                val connection = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                workers.execute { serve(connection) }
            }
        }
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
        socket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf(String::isNotEmpty) }.toList()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1).orEmpty()
        val prefix = "/$secret"
        if ((method != "GET" && method != "HEAD") || !target.startsWith("$prefix/")) {
            status(output, 404, "Not Found")
            return
        }
        val upstream = "${origin.scheme}://${origin.rawAuthority}${target.removePrefix(prefix)}"
        val request = Request.Builder().url(upstream).apply {
            if (method == "HEAD") head()
            headers.forEach { line ->
                val name = line.substringBefore(':').trim()
                if (name.equals("Range", true) || name.equals("If-Range", true)) header(name, line.substringAfter(':').trim())
            }
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

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > MAX_LINE) return null
            line.append(byte.toChar())
        }
    }

    private companion object {
        const val BACKLOG = 16
        const val SECRET_BYTES = 16
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_LINE = 16 * 1024
        val FORWARDED = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")
    }
}
