/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class VideoEndDiagnosticsTest {
    @Test
    fun codecProbePreservesDequeuesAndReleasesAndMissesAreNotDecoderProgress() {
        val input = ArrayDeque(listOf(-1, -1, 3, -1))
        val output = ArrayDeque(listOf(-1, -1, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, 5))
        val releases = mutableListOf<List<Any?>>()
        var bufferAccesses = 0
        var listenerRegistrations = 0
        val codec = Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader, arrayOf(MediaCodecAdapter::class.java)) {
                _,
                method,
                args
            ->
            when (method.name) {
                "useBuffer" -> {
                    bufferAccesses++
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as androidx.media3.common.util.ThrowingRunnable<Exception>).run()
                    null
                }
                "registerOnBufferAvailableListener" -> {
                    listenerRegistrations++
                    true
                }
                "dequeueInputBufferIndex" -> input.removeFirst()
                "dequeueOutputBufferIndex" -> output.removeFirst()
                "releaseOutputBuffer" -> {
                    releases += args!!.toList()
                    null
                }
                else -> null
            }
        } as MediaCodecAdapter
        val probe = VideoCodecProbe { codec }
        // The factory delegates configuration unchanged; this fake does not inspect it.
        val configuration = MediaCodecAdapter.Configuration.createForAudioDecoding(
            androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
                "test.decoder", "audio/aac", "audio/aac", null, false, true, false, false, false
            ),
            android.media.MediaFormat(),
            androidx.media3.common.Format.Builder().setSampleMimeType("audio/aac").build(),
            null,
            null
        )
        val wrapped = probe.createAdapter(configuration)
        var read = false
        wrapped.useBuffer<Exception> { read = true }
        kotlin.test.assertTrue(read)
        assertEquals(1, bufferAccesses, "The delegate owns buffer synchronization")
        kotlin.test.assertTrue(wrapped.registerOnBufferAvailableListener(object : MediaCodecAdapter.OnBufferAvailableListener {}))
        assertEquals(1, listenerRegistrations, "Preserve asynchronous adapter wakeups")
        assertEquals(listOf(-1, -1), List(2) { wrapped.dequeueInputBufferIndex() })
        assertEquals(2L, probe.inputMisses)
        assertEquals(3, wrapped.dequeueInputBufferIndex())
        assertEquals(0L, probe.inputMisses)
        assertEquals(-1, wrapped.dequeueInputBufferIndex())
        assertEquals(1L, probe.inputMisses)
        val info = MediaCodec.BufferInfo()
        assertEquals(listOf(-1, -1), List(2) { wrapped.dequeueOutputBufferIndex(info) })
        assertEquals(2L, probe.outputMisses)
        assertEquals(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, wrapped.dequeueOutputBufferIndex(info))
        assertEquals(0L, probe.outputMisses)
        assertEquals(5, wrapped.dequeueOutputBufferIndex(info))
        wrapped.releaseOutputBuffer(5, 123L)
        assertEquals(5, releases.single()[0])
        assertEquals(123L, releases.single()[1])
        val before = VideoEndSnapshot(queued = 20, rendered = 10, inputMisses = 1)
        assertEquals(before.progress(), before.copy(inputMisses = 200, outputMisses = 200, positionUs = 90_000_000).progress())
        assertNotEquals(before.progress(), before.copy(rendered = 11).progress())
    }

    @Test
    fun pendingVideoLogsOnceAfterAudioDrainOnEveryRouteAndPauseRestartsObservation() {
        var logs = 0
        var state = Renderer.STATE_STARTED
        var audioEnded = false
        var videoEnded = false
        fun fake(type: Int): Renderer =
            Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, _ ->
                when (method.name) {
                    "getTrackType" -> type
                    "getState" -> state
                    "isCurrentStreamFinal" -> type == C.TRACK_TYPE_AUDIO && audioEnded
                    "isEnded" -> if (type == C.TRACK_TYPE_AUDIO) audioEnded else videoEnded
                    else -> null
                }
            } as Renderer
        val renderers = VideoEndDiagnostics { logs++ }.wrap(arrayOf(fake(C.TRACK_TYPE_AUDIO), fake(C.TRACK_TYPE_VIDEO)))
        fun render(time: Long) = renderers[1].render(60_000_000, time)
        render(0)
        render(10_000_000)
        assertEquals(0, logs)
        audioEnded = true
        render(20_000_000)
        render(23_000_000)
        render(30_000_000)
        assertEquals(1, logs)
        state = Renderer.STATE_ENABLED
        render(40_000_000)
        state = Renderer.STATE_STARTED
        render(50_000_000)
        render(52_999_999)
        assertEquals(1, logs)
        render(53_000_000)
        assertEquals(2, logs)
        videoEnded = true
        render(60_000_000)
        render(70_000_000)
        assertEquals(2, logs)
    }
}
