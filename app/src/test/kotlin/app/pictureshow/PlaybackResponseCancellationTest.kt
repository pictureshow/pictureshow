/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackResponseCancellationTest {
    @Test
    fun interruptedMedia3OpenClosesTheResponseDeliveredToItsAbandonedFuture() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val callbackReady = CountDownLatch(1)
            val deliverResponse = CountDownLatch(1)
            val callbackDone = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val closeCount = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val heldResponse = AtomicReference<Response>()
            val transport = countingClient(closeCount, closed)
            val factory = Call.Factory { request ->
                val actual = transport.newCall(request)
                object : Call by actual {
                    override fun enqueue(callback: Callback) {
                        actual.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) = callback.onFailure(call, e)
                            override fun onResponse(call: Call, response: Response) {
                                // RealCall has passed its final cancellation check. Interrupt the
                                // loader before Media3 can accept this response from its future.
                                heldResponse.set(response)
                                callbackReady.countDown()
                                check(deliverResponse.await(10, TimeUnit.SECONDS))
                                try {
                                    callback.onResponse(call, response)
                                } finally {
                                    callbackDone.countDown()
                                }
                            }
                        })
                    }
                }
            }
            val source = OkHttpDataSource.Factory(factory).createDataSource()
            val loader = thread(name = "cancelled-open") {
                try {
                    source.open(DataSpec(Uri.parse(server.url("/").toString())))
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    source.close()
                }
            }
            try {
                assertTrue(callbackReady.await(5, TimeUnit.SECONDS), "Response did not reach callback handoff")
                loader.interrupt()
                loader.join(5000)
                assertFalse(loader.isAlive, "Media3 loader did not leave its interrupted open")
                val openFailure = assertIs<HttpDataSource.HttpDataSourceException>(failure.get())
                assertIs<InterruptedIOException>(openFailure.cause)
                deliverResponse.countDown()
                assertTrue(callbackDone.await(5, TimeUnit.SECONDS), "Media3 callback did not complete")
                assertTrue(closed.await(2, TimeUnit.SECONDS), "Interrupted Media3 future abandoned its response body")
                assertEquals(1, closeCount.get())
            } finally {
                deliverResponse.countDown()
                loader.interrupt()
                loader.join(5000)
                heldResponse.get()?.close()
                transport.connectionPool.evictAll()
                transport.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test
    fun successfulMedia3ReadRetainsNormalBodyOwnershipAndConnectionReuse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val closes = AtomicInteger()
            val transport = countingClient(closes)
            val source = OkHttpDataSource.Factory(transport).createDataSource()
            try {
                assertEquals(7L, source.open(DataSpec(Uri.parse(server.url("/").toString()))))
                val bytes = ByteArray(16)
                assertEquals(7, source.read(bytes, 0, bytes.size))
                assertEquals("payload", bytes.copyOf(7).decodeToString())
                assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
                source.close()
                assertEquals(1, closes.get())
                assertEquals(1, transport.connectionPool.idleConnectionCount())
            } finally {
                source.close()
                transport.connectionPool.evictAll()
                transport.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test
    fun failedMedia3OpenClosesItsErrorBodyOnce() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val closes = AtomicInteger()
            val transport = countingClient(closes)
            val source = OkHttpDataSource.Factory(transport).createDataSource()
            try {
                assertFailsWith<HttpDataSource.InvalidResponseCodeException> {
                    source.open(DataSpec(Uri.parse(server.url("/").toString())))
                }
                source.close()
                assertEquals(1, closes.get())
                assertEquals(1, transport.connectionPool.idleConnectionCount())
            } finally {
                source.close()
                transport.connectionPool.evictAll()
                transport.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun countingClient(closes: AtomicInteger, closed: CountDownLatch? = null): OkHttpClient =
        PlaybackMedia.createPlaybackHttpClient().newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val delegate = response.body
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = delegate.contentType()
                    override fun contentLength() = delegate.contentLength()
                    override fun source() = delegate.source()
                    override fun close() {
                        closes.incrementAndGet()
                        delegate.close()
                        closed?.countDown()
                    }
                }).build()
            }.build()
}
