/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Server
import java.net.HttpURLConnection
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
}
