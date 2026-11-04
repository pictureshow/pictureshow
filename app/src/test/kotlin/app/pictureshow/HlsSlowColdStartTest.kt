/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real HLS/Loader/HTTP integration: segment generation continues across canceled pre-header waits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HlsSlowColdStartTest {
    @Test
    fun aMediaSegmentAvailableAtFortySecondsBecomesReadyWithoutAFatalLoaderError() {
        checkColdStart(40, expectReady = true)
    }

    @Test
    fun aPausedPermanentlyUnavailableMediaLoadStillHasAFiniteRetryBound() {
        checkColdStart(60, expectReady = false)
    }

    private fun checkColdStart(availableSeconds: Long, expectReady: Boolean) {
        val segment = requireNotNull(javaClass.getResourceAsStream("/playback/copied-audio-preroll.ts")).use { it.readBytes() }
        val firstRequest = AtomicLong()
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handlers = Executors.newCachedThreadPool()
        server.executor = handlers
        server.createContext("/") { exchange ->
            runCatching {
                val body = when (exchange.requestURI.path.substringAfterLast('/')) {
                    "master.m3u8" -> (
                        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS=\"avc1.64000a,mp4a.40.2\"\n" +
                            "main.m3u8\n"
                        ).toByteArray()
                    "main.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3.0,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
                    else -> {
                        attempts.incrementAndGet()
                        firstRequest.compareAndSet(0, System.nanoTime())
                        val remaining = firstRequest.get() + TimeUnit.SECONDS.toNanos(availableSeconds) - System.nanoTime()
                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                        segment
                    }
                }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val output = Media3PlaybackOutput(
            RuntimeEnvironment.getApplication(),
            Server("server", "Test", base, "12"),
            renderersFactory = RenderersFactory { _, _, _, _, _ -> arrayOf(ReadingVideoRenderer()) }
        )
        val decision = PlayDecision(
            "item",
            MediaSourceInfo(transcodingSubProtocol = MediaStreamProtocol.Hls),
            PlayMethod.Transcode,
            "$base/master.m3u8",
            emptyMap(),
            "session"
        )
        try {
            output.open(decision, 0, 0, -1, true)
            val player = requireNotNull(output.player.value)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(52)
            var previous = System.nanoTime()
            while (player.playbackState != Player.STATE_READY && player.playerError == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
                val now = System.nanoTime()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofNanos(now - previous))
                previous = now
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstRequest.get())
            if (expectReady) {
                assertNull(player.playerError, "Fatal before late segment: attempts=${attempts.get()} elapsedMs=$elapsedMs")
                assertEquals(Player.STATE_READY, player.playbackState, "Late segment never completed; elapsedMs=$elapsedMs")
                assertTrue(elapsedMs in 40_000..44_999, "Expected completion inside session window, elapsedMs=$elapsedMs")
                assertEquals(5, attempts.get(), "Test must cross four 8-second timeouts")
            } else {
                // open(paused=true) has no PlaybackSession timer: Loader must still stop by itself.
                assertTrue(player.playerError != null, "Paused load retried without a finite bound; elapsedMs=$elapsedMs")
                assertTrue(elapsedMs in 45_000..50_999, "Expected fifth idle error near 46 seconds, elapsedMs=$elapsedMs")
                assertEquals(5, attempts.get())
            }
        } finally {
            output.release()
            shadowOf(Looper.getMainLooper()).idle()
            server.stop(0)
            handlers.shutdownNow()
        }
    }

    private class ReadingVideoRenderer : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        private val holder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false
        override fun getName() = "ReadingVideoRenderer"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (MimeTypes.isVideo(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
        )
        override fun isReady() = isSourceReady
        override fun isEnded() = ended
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            ended = false
        }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            while (!ended) {
                buffer.clear()
                when (readSource(holder, buffer, 0)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_BUFFER_READ -> ended = buffer.isEndOfStream
                }
            }
        }
    }
}
