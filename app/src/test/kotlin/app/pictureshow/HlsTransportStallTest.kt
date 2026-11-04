/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real playback HTTP/Media3 reads: a blocked response must release the loader in time to retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class HlsTransportStallTest {
    @Test
    fun nativeHlsMidBodyStallTimesOutAndNextReadUsesAFreshConnection() {
        val release = CountDownLatch(1)
        val peers = mutableListOf<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val attempt = synchronized(peers) {
                peers.add(exchange.remoteAddress.port)
                peers.size
            }
            exchange.sendResponseHeaders(200, 4)
            runCatching {
                exchange.responseBody.use {
                    it.write(byteArrayOf(1))
                    it.flush()
                    if (attempt == 1) release.await(30, TimeUnit.SECONDS)
                    it.write(byteArrayOf(2, 3, 4))
                }
            }
            exchange.close()
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val factory = PlaybackMedia.dataSourceFactory(
            Server("server", "Test", base, "12"),
            PlayDecision(
                "item",
                MediaSourceInfo(transcodingSubProtocol = MediaStreamProtocol.Hls),
                PlayMethod.Transcode,
                "$base/master.m3u8",
                emptyMap(),
                "session"
            )
        )
        val source = factory.createDataSource()
        val retry = factory.createDataSource()
        val start = System.nanoTime()
        try {
            source.open(DataSpec(Uri.parse("$base/segment.ts")))
            val buffer = ByteArray(4)
            assertEquals(1, source.read(buffer, 0, 4))
            val error = runCatching { source.read(buffer, 0, 4) }.exceptionOrNull()
            assertIs<java.io.IOException>(error)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(
                elapsedMs < 12_000,
                "Blocked native HLS read took ${elapsedMs}ms; must leave time for retry before the session deadline"
            )
            source.close()
            retry.open(DataSpec(Uri.parse("$base/segment.ts")))
            var bytes = 0
            while (true) {
                val n = retry.read(buffer, 0, 4)
                if (n == -1) break
                bytes += n
            }
            assertEquals(4, bytes)
            assertEquals(2, peers.size)
            assertTrue(peers[0] != peers[1], "Retry reused the timed-out socket")
        } finally {
            release.countDown()
            source.close()
            retry.close()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    @Test
    fun nativeHlsDribblingBodyHasATotalDeadline() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            runCatching {
                exchange.responseBody.use {
                    repeat(100) { _ ->
                        it.write(1)
                        it.flush()
                        Thread.sleep(1_000)
                    }
                }
            }
            exchange.close()
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = PlaybackMedia.dataSourceFactory(
            Server("server", "Test", base, "12"),
            PlayDecision(
                "item",
                MediaSourceInfo(transcodingSubProtocol = MediaStreamProtocol.Hls),
                PlayMethod.Transcode,
                "$base/master.m3u8",
                emptyMap(),
                "session"
            )
        ).createDataSource()
        val start = System.nanoTime()
        try {
            source.open(DataSpec(Uri.parse("$base/segment.ts")))
            val bytes = ByteArray(100)
            val error = runCatching { while (source.read(bytes, 0, bytes.size) != -1) { /* Keep reading the body. */ } }.exceptionOrNull()
            assertIs<java.io.IOException>(error)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(elapsed < 18_000, "Dribbling body escaped total deadline: ${elapsed}ms")
        } finally {
            source.close()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    @Test
    fun slowPlaylistsDirectPlayAndServerSeekKeepTheirExistingReadBudget() {
        val handlers = Executors.newCachedThreadPool()
        val workers = Executors.newFixedThreadPool(3)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = handlers
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use {
                it.write(1)
                it.flush()
                Thread.sleep(9_000)
                it.write(2)
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val native = PlayDecision(
            "item",
            MediaSourceInfo(transcodingSubProtocol = MediaStreamProtocol.Hls),
            PlayMethod.Transcode,
            "$base/master.m3u8",
            emptyMap(),
            "session"
        )
        val serverSeek = PlayDecision(
            itemId = "item",
            method = PlayMethod.Transcode,
            headers = emptyMap(),
            playSessionId = "session",
            url = "$base/master.m3u8?VideoCodec=h264&MaxFramerate=30",
            mediaSource = native.mediaSource.copy(
                transcodingContainer = "ts",
                runTimeTicks = 1_200_000_000,
                mediaStreams = listOf(
                    app.pictureshow.core.playback.MediaStream(
                        type = app.pictureshow.core.playback.MediaStreamType.Video,
                        realFrameRate = 60.0
                    )
                )
            )
        )
        assertTrue(app.pictureshow.core.playback.TranscodeSeek.required(serverSeek))
        try {
            val cases = listOf(
                native to false,
                PlayDecision("item", native.mediaSource, PlayMethod.DirectPlay, native.url, emptyMap(), "session") to true,
                serverSeek to true
            )
            val futures = cases.map { (decision, media) ->
                workers.submit<Int> {
                    val source = PlaybackMedia.httpFactory(
                        Server("server", "Test", base, "12"),
                        decision,
                        nativeHlsMedia = media
                    ).createDataSource()
                    try {
                        source.open(DataSpec(Uri.parse("$base/finite")))
                        val buffer = ByteArray(2)
                        var count = 0
                        while (true) {
                            val n = source.read(buffer, 0, 2)
                            if (n == -1) break
                            count += n
                        }
                        count
                    } finally {
                        source.close()
                    }
                }
            }
            futures.forEach { assertEquals(2, it.get(15, TimeUnit.SECONDS)) }
        } finally {
            server.stop(0)
            handlers.shutdownNow()
            workers.shutdownNow()
        }
    }
}
