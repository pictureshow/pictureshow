/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class KtorResponseCancellationTest {
    @Test
    fun cancellationAfterCallbackBeforeEngineResumeClosesTheUnclaimedBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val engineExecutor = Executors.newSingleThreadExecutor()
            val engineDispatcher = engineExecutor.asCoroutineDispatcher()
            val blocked = CountDownLatch(1)
            val resumeEngine = CountDownLatch(1)
            val callbackFinished = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val closeCount = AtomicInteger()
            val callbacks = object : ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, SynchronousQueue()) {
                override fun afterExecute(task: Runnable?, failure: Throwable?) {
                    super.afterExecute(task, failure)
                    callbackFinished.countDown()
                }
            }
            val transport = OkHttpClient.Builder().dispatcher(Dispatcher(callbacks))
                .addInterceptor(CancelledResponseInterceptor())
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    // The engine is suspended awaiting the callback. Hold its next dispatch so
                    // cancellation lands after resume(response), before body-close ownership.
                    engineExecutor.execute {
                        blocked.countDown()
                        check(resumeEngine.await(10, TimeUnit.SECONDS))
                    }
                    check(blocked.await(5, TimeUnit.SECONDS))
                    val delegate = response.body
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = delegate.contentType()
                        override fun contentLength() = delegate.contentLength()
                        override fun source() = delegate.source()
                        override fun close() {
                            closeCount.incrementAndGet()
                            delegate.close()
                            closed.countDown()
                        }
                    }).build()
                }.build()
            val http = HttpClient(OkHttp) {
                configureApiHttpClient()
                engine {
                    dispatcher = engineDispatcher
                    preconfigured = transport
                }
            }
            val request = async(Dispatchers.Default) { http.get(server.url("/").toString()).bodyAsText() }
            try {
                assertTrue(callbackFinished.await(5, TimeUnit.SECONDS), "OkHttp callback did not finish")
                request.cancel()
                assertTrue(closed.await(2, TimeUnit.SECONDS), "Ktor discarded the resumed response without closing it")
                assertEquals(1, closeCount.get())
            } finally {
                resumeEngine.countDown()
                request.cancelAndJoin()
                http.close()
                transport.connectionPool.evictAll()
                callbacks.shutdownNow()
                engineDispatcher.close()
            }
        }
    }
}
