/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Server
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** PLAY-60: mpv reads the server only through this loopback proxy, which must not leak the sign-in to other apps. */
class MpvHttpProxyTest {
    private val upstream = MockWebServer()
    private lateinit var proxy: MpvHttpProxy

    @Before
    fun start() {
        upstream.start()
        val server = Server("s", "Home", upstream.url("/").toString().trimEnd('/'), "10.11.0")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "MediaBrowser Token=\"secret\"").build())
        }.build()
        proxy = MpvHttpProxy(server, client)
    }

    @After
    fun stop() {
        proxy.close()
        upstream.shutdown()
    }

    @Test
    fun forwardsPathQueryAndRangeAndStripsApiKey() {
        upstream.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-13/100").setBody("abcd"))
        val local = requireNotNull(proxy.local(upstream.url("/Videos/1/stream.mkv?Static=true&ApiKey=leak").toString()))
        assertTrue(local.startsWith("http://127.0.0.1:"))
        assertTrue("ApiKey" !in local)
        val connection = URL(local).openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=10-13")
        assertEquals(206, connection.responseCode)
        assertEquals("bytes 10-13/100", connection.getHeaderField("Content-Range"))
        assertEquals("abcd", connection.inputStream.readBytes().decodeToString())
        val recorded = upstream.takeRequest()
        assertEquals("/Videos/1/stream.mkv?Static=true", recorded.path)
        assertEquals("bytes=10-13", recorded.getHeader("Range"))
        assertEquals("MediaBrowser Token=\"secret\"", recorded.getHeader("Authorization"))
    }

    @Test
    fun refusesRequestsWithoutTheSecretAndOtherOrigins() {
        val local = requireNotNull(proxy.local(upstream.url("/Videos/1/stream").toString()))
        val port = URL(local).port
        val guessed = URL("http://127.0.0.1:$port/Videos/1/stream").openConnection() as HttpURLConnection
        assertEquals(404, guessed.responseCode)
        assertEquals(0, upstream.requestCount)
        assertNull(proxy.local("https://elsewhere.example/Videos/1/stream"))
    }

    private fun port() = URL(requireNotNull(proxy.local(upstream.url("/Videos/1/stream").toString()))).port

    private fun secretPath() = URL(requireNotNull(proxy.local(upstream.url("/Videos/1/stream").toString()))).path

    /** The first response line, or null when the proxy closed the connection without answering. */
    private fun statusLine(socket: Socket): String? =
        socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()

    @Test
    fun aWrongSecretIsRefusedOnItsRequestLineBeforeAnyHeaderArrives() {
        Socket("127.0.0.1", port()).use { socket ->
            socket.soTimeout = 2_000
            // Headers never finish: an answer can only come from checking the request line alone.
            socket.getOutputStream().apply { write("GET /Videos/1/stream HTTP/1.1\r\nHost: x\r\n".toByteArray()); flush() }
            assertEquals("HTTP/1.1 404 Not Found", statusLine(socket))
        }
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun endlessHeadersAreCutOffWithoutReachingTheServer() {
        Socket("127.0.0.1", port()).use { socket ->
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            out.write("GET ${secretPath()} HTTP/1.1\r\n".toByteArray())
            val answer = runCatching {
                repeat(10_000) { out.write("X-Filler-$it: ${"a".repeat(64)}\r\n".toByteArray()) }
                out.flush()
                statusLine(socket)
            }.getOrNull()
            assertTrue(answer == null || answer.startsWith("HTTP/1.1 431"), "got $answer")
        }
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun aClientThatStopsMidHeadersIsDroppedAtTheDeadline() {
        Socket("127.0.0.1", port()).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply { write("GET ${secretPath()} HTTP/1.1\r\nRange: bytes=0-\r\n".toByteArray()); flush() }
            val started = System.nanoTime()
            assertNull(statusLine(socket))
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(waitedMs in 3_000..8_000, "dropped after ${waitedMs}ms")
        }
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun connectionsWithoutTheSecretCannotTakeTheSlotsMpvNeeds() {
        // More silent connections than the proven-connection cap: none of them may block a real request.
        val held = (1..12).map { Socket("127.0.0.1", port()).apply { getOutputStream().write("GET ".toByteArray()) } }
        try {
            upstream.enqueue(MockResponse().setBody("ok"))
            val connection = URL(requireNotNull(proxy.local(upstream.url("/Videos/1/stream").toString()))).openConnection() as HttpURLConnection
            connection.readTimeout = 2_000
            assertEquals(200, connection.responseCode)
            assertEquals("ok", connection.inputStream.readBytes().decodeToString())
        } finally {
            held.forEach(Socket::close)
        }
    }

    @Test
    fun aSilentConnectionIsDroppedWithinTheRequestLineDeadline() {
        Socket("127.0.0.1", port()).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write("GET /".toByteArray()); flush() }
            val started = System.nanoTime()
            assertNull(statusLine(socket))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        }
    }
}
