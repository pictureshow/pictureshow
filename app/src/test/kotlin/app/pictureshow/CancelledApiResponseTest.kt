/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import org.junit.Test

class CancelledApiResponseTest {
    @Test
    fun cancelledCallbackDropsAnUnclaimedResponseButItsBodyStillCloses() {
        exercise(claim = false, closeInCallback = false)
    }

    @Test
    fun cancellationDoesNotCloseASourceAlreadyOwnedByTheReader() {
        exercise(claim = true, closeInCallback = true)
    }

    @Test
    fun cancelledResponseCannotBeClaimedOrClosedTwiceWhileCleanupIsQueued() {
        exercise(claim = false, closeInCallback = true, queued = true)
    }

    @Test
    fun cancellationBeforeTheFinalOkHttpCheckClosesOnlyOnce() {
        exercise(claim = false, closeInCallback = false, queued = true, cancelInNetwork = true)
    }

    @Test
    fun normalBodyCloseStillReleasesTheConnection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val client = OkHttpClient.Builder().addInterceptor(CancelledResponseInterceptor()).build()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    assertEquals("payload", it.body.string())
                }
                assertEquals(0, client.connectionPool.connectionCount() - client.connectionPool.idleConnectionCount())
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun exercise(claim: Boolean, closeInCallback: Boolean, queued: Boolean = claim, cancelInNetwork: Boolean = false) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val closed = CountDownLatch(1)
            val callbackDone = CountDownLatch(1)
            val closeCount = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val closeThread = AtomicReference<String>()
            val callbackThread = AtomicReference<String>()
            val pending = ConcurrentLinkedQueue<Runnable>()
            val guard = if (queued) CancelledResponseInterceptor(Executor { pending.add(it) }) else CancelledResponseInterceptor()
            val client = OkHttpClient.Builder()
                .addInterceptor(guard)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val delegate = response.body
                    val tracked = response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = delegate.contentType()
                        override fun contentLength() = delegate.contentLength()
                        override fun source(): BufferedSource = delegate.source()
                        override fun close() {
                            closeThread.set(Thread.currentThread().name)
                            closeCount.incrementAndGet()
                            delegate.close()
                            closed.countDown()
                        }
                    }).build()
                    if (cancelInNetwork) chain.call().cancel()
                    tracked
                }.build()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cancelInNetwork) failure.set(e)
                        callbackDone.countDown()
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            callbackThread.set(Thread.currentThread().name)
                            check(!cancelInNetwork) { "Cancelled call unexpectedly delivered a response" }
                            if (claim) response.body.source()
                            // Reproduce the Ktor handoff gap: cancel after OkHttp's last check,
                            // before the engine owns the body, then drop the response.
                            call.cancel()
                            if (claim) assertEquals(0, closeCount.get(), "Cancellation must leave the reader's source alone")
                            if (queued && !claim) assertFailsWith<IOException> { response.body.source() }
                            if (closeInCallback) response.close()
                        } catch (t: Throwable) {
                            failure.set(t)
                        } finally {
                            callbackDone.countDown()
                        }
                    }
                })
                assertTrue(callbackDone.await(5, TimeUnit.SECONDS), "Callback did not run")
                failure.get()?.let { throw it }
                if (queued) {
                    if (claim) assertTrue(pending.isEmpty(), "Cancellation queued a close for a claimed source")
                    while (true) (pending.poll() ?: break).run()
                }
                assertTrue(closed.await(2, TimeUnit.SECONDS), "Dropped response body was not closed")
                assertEquals(1, closeCount.get())
                if (claim) assertEquals(callbackThread.get(), closeThread.get(), "The reader must retain close ownership")
                if (!claim && !cancelInNetwork) {
                    assertTrue(closeThread.get() != callbackThread.get(), "Cleanup blocked the cancelling callback")
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
