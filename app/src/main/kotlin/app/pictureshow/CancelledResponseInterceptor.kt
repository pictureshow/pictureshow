/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody

private val responseCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Closes cancelled Ktor/Media3 responses discarded before their reader takes ownership. */
internal class CancelledResponseInterceptor(private val cleanup: Executor = Executor { task -> responseCleanup.launch { task.run() } }) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = HandoffBody(response.body, cleanup)
        val call = chain.call()
        call.addEventListener(object : EventListener() {
            override fun canceled(call: Call) = body.sweep()
        })
        // Cancellation can precede listener registration. Register first, then recheck.
        if (call.isCanceled()) body.sweep()
        return response.newBuilder().body(body).build()
    }

    private class HandoffBody(private val delegate: ResponseBody, private val cleanup: Executor) : ResponseBody() {
        private val state = AtomicInteger(UNCLAIMED)

        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()

        override fun source(): okio.BufferedSource {
            // Ktor registers cleanup, and Media3 stores the response, before requesting the source.
            state.compareAndSet(UNCLAIMED, CLAIMED)
            if (state.get() == CLOSED) throw IOException("Response body is closed")
            return delegate.source()
        }

        fun sweep() {
            // Never close a claimed reader's source, or perform I/O on the cancelling thread.
            if (state.compareAndSet(UNCLAIMED, CLOSED)) cleanup.execute { runCatching { delegate.close() } }
        }

        override fun close() {
            // OkHttp's cancellation check can also close the wrapper. Only one closer wins.
            if (state.getAndSet(CLOSED) != CLOSED) delegate.close()
        }

        private companion object {
            const val UNCLAIMED = 0
            const val CLAIMED = 1
            const val CLOSED = 2
        }
    }
}
