/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackTransportReuseTest {
    @Test
    fun reopenedSourcesReuseTheirConnectionWithoutReusingThePreviousAccountsHeaders() {
        val ports = CopyOnWriteArrayList<Int>()
        val headers = CopyOnWriteArrayList<String>()
        val origin = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        origin.createContext("/media") { exchange ->
            ports += exchange.remoteAddress.port
            headers += exchange.requestHeaders.getFirst("Authorization")
            val bytes = "media".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        origin.start()
        try {
            val url = "http://localhost:${origin.address.port}/media"
            val server = Server("server", "Test", url.substringBeforeLast('/'), "12.0.0")
            repeat(8) { index ->
                // A new factory models every seek/track replacement, including an account change.
                val decision = PlayDecision(
                    "item",
                    MediaSourceInfo(id = "source"),
                    PlayMethod.DirectPlay,
                    url,
                    mapOf("Authorization" to "test-account-$index"),
                    "session-$index"
                )
                val source = PlaybackMedia.httpFactory(server, decision).createDataSource()
                try {
                    assertEquals(5L, source.open(DataSpec(Uri.parse(url))))
                    val buffer = ByteArray(16)
                    var received = 0
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        received += read
                    }
                    assertEquals(5, received)
                } finally {
                    source.close()
                }
            }
            assertEquals((0..7).map { "test-account-$it" }, headers.toList())
            assertEquals(
                1,
                ports.toSet().size,
                "Each replacement must reuse the transport rather than retain a separate idle socket/pool"
            )
        } finally {
            origin.stop(0)
        }
    }

    @Test
    fun replacementLoadersCanOverlapWithoutSharingDecisionHeaders() {
        val arrived = java.util.concurrent.CountDownLatch(8)
        val release = java.util.concurrent.CountDownLatch(1)
        val headers = CopyOnWriteArrayList<String>()
        val workers = java.util.concurrent.Executors.newFixedThreadPool(8)
        val handlers = java.util.concurrent.Executors.newCachedThreadPool()
        val origin = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        origin.executor = handlers
        origin.createContext("/media") { exchange ->
            headers += exchange.requestHeaders.getFirst("Authorization")
            arrived.countDown()
            try {
                release.await()
                val bytes = "media".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        origin.start()
        try {
            val url = "http://localhost:${origin.address.port}/media"
            val server = Server("server", "Test", url.substringBeforeLast('/'), "12.0.0")
            val loads = (0..7).map { index ->
                workers.submit {
                    val decision = PlayDecision(
                        "item",
                        MediaSourceInfo(id = "source"),
                        PlayMethod.Transcode,
                        url,
                        mapOf("Authorization" to "test-account-$index"),
                        "session-$index"
                    )
                    val source = PlaybackMedia.httpFactory(server, decision).createDataSource()
                    try {
                        source.open(DataSpec(Uri.parse(url)))
                        val buffer = ByteArray(16)
                        while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {
                            // Drain the response before closing the source.
                        }
                    } finally {
                        source.close()
                    }
                }
            }
            try {
                kotlin.test.assertTrue(
                    arrived.await(8, java.util.concurrent.TimeUnit.SECONDS),
                    "A shared dispatcher must admit overlapping old/new manifest, media and subtitle loaders"
                )
                assertEquals((0..7).map { "test-account-$it" }.toSet(), headers.toSet())
                assertEquals(8, headers.size)
            } finally {
                release.countDown()
            }
            loads.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            origin.stop(0)
            workers.shutdownNow()
            handlers.shutdownNow()
        }
    }
}
