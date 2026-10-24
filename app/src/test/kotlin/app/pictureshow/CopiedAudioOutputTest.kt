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
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real ExoPlayer extraction and real MediaCodecAudioRenderer bypass, with a capturing audio sink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CopiedAudioOutputTest {
    @Test
    fun copiedAudioProductionOutputRendersVideoAtTargetAndNeverSendsPrerollToTheSink() {
        checkOutput(copy = true, residualMs = 0)
        checkOutput(copy = true, residualMs = 300)
    }

    @Test
    fun alreadyEncodedAudioKeepsTheOriginalExtractorAndAbsoluteController() {
        checkOutput(copy = false, residualMs = 0)
    }

    private fun checkOutput(copy: Boolean, residualMs: Long) {
        val context = RuntimeEnvironment.getApplication()
        val data = requireNotNull(javaClass.getResourceAsStream("/playback/copied-audio-preroll.ts")).use { it.readBytes() }
        val sinkTimes = Collections.synchronizedList(mutableListOf<Long>())
        var sinkOffset = 0L
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "supportsFormat" -> true // Exercise Media3's actual compressed-audio bypass, without a platform codec.
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "getFormatOffloadSupport" -> androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
                "getCurrentPositionUs" -> AudioSink.CURRENT_POSITION_NOT_SET
                "setOutputStreamOffsetUs" -> {
                    sinkOffset = args!![0] as Long
                    null
                }
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    sinkTimes += (args[1] as Long) - sinkOffset
                    buffer.position(buffer.limit())
                    true
                }
                "isEnded" -> true
                "getPlaybackParameters" -> androidx.media3.common.PlaybackParameters.DEFAULT
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 1f
                    else -> null
                }
            }
        } as AudioSink
        val video = Video()
        val renderers = RenderersFactory { _, _, _, _, _ ->
            arrayOf(video, MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, null, null, sink))
        }
        val output = Media3PlaybackOutput(
            context,
            Server("server", "Test", "https://example.test", "12.0"),
            upstream = DataSource.Factory {
                val delegate = ByteArrayDataSource(data)
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        delegate.open(dataSpec)
                        return if (copy) C.LENGTH_UNSET.toLong() else data.size.toLong()
                    }
                }
            },
            renderersFactory = renderers
        )
        val target = 120_000L + residualMs
        var ended = false
        output.onEnded = { ended = true }
        val decision = PlayDecision(
            "item-copy",
            MediaSourceInfo(
                id = "source-copy",
                runTimeTicks = 1_250_000_000,
                transcodingContainer = "ts",
                transcodingSubProtocol = MediaStreamProtocol.Hls,
                defaultAudioStreamIndex = 1,
                mediaStreams = listOf(
                    MediaStream(type = MediaStreamType.Video, index = 0, codec = "av1", realFrameRate = 60.0),
                    MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", channels = 6)
                )
            ),
            PlayMethod.Transcode,
            "https://example.test/Videos/item-copy/master.m3u8?VideoCodec=h264&MaxFramerate=30&AudioCodec=aac&" +
                "MaxAudioChannels=6&AllowAudioStreamCopy=$copy",
            emptyMap(),
            "encode-copy"
        )
        try {
            output.open(decision, target, 1, -1, false)
            val player = assertNotNull(output.player.value)
            assertTrue(player is TranscodeSeekPlayer)
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!ended && player.playerError == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(2)
            }
            assertNull(player.playerError)
            assertTrue(ended, "Real extractors/renderers must reach media EOS; state=${player.playbackState}")
            assertTrue(video.frames.isNotEmpty(), "A READY callback alone does not prove a video sample rendered")
            val firstVideoUs = video.frames.first()
            if (copy) {
                assertTrue(firstVideoUs in residualMs * 1000..residualMs * 1000 + 34_000, "first video=$firstVideoUs, clipping=$residualMs")
            } else {
                assertTrue(
                    firstVideoUs in 733_000..767_000,
                    "The encoded route must retain its original TS timestamp origin: $firstVideoUs"
                )
            }
            assertTrue(sinkTimes.isNotEmpty(), "Media3's actual bypass must deliver audio to the sink")
            assertTrue(sinkTimes.first() <= residualMs * 1000 + 21_334, "Audio must start within one AAC frame of the target: $sinkTimes")
            assertTrue(sinkTimes.all { it >= residualMs * 1000 }, "No audio before the requested position may reach the sink: $sinkTimes")
            assertEquals(125_000L, player.duration)
            assertTrue(output.position().milliseconds >= target)
        } finally {
            output.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Video : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        private val holder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var pending = false
        private var ended = false
        override fun getName() = "Capturing video"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (MimeTypes.isVideo(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
        )
        override fun isReady() = pending || isSourceReady
        override fun isEnded() = ended
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            pending = false
            ended = false
        }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            while (!ended) {
                if (!pending) {
                    buffer.clear()
                    when (readSource(holder, buffer, 0)) {
                        C.RESULT_FORMAT_READ -> continue
                        C.RESULT_NOTHING_READ -> return
                        C.RESULT_BUFFER_READ -> {
                            if (buffer.isEndOfStream) {
                                ended = true
                                return
                            }
                            pending = true
                        }
                    }
                }
                if (buffer.timeUs > positionUs) return
                if (buffer.timeUs >= lastResetPositionUs) frames += buffer.timeUs - streamOffsetUs
                pending = false
            }
        }
    }
}
