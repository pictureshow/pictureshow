package app.pictureshow.ffmpeg

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays synthetic clips (tools/ffmpeg/make-test-clips.sh: a different sine tone on every channel) through ExoPlayer with
 * FfmpegAudioRenderer as the only renderer, and checks FFmpeg decoded each one into PCM with the right channel count
 * and sound on every channel.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FfmpegAudioRendererTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ac3_5_1() = assertDecodes("ac3_5.1.mkv", MimeTypes.AUDIO_AC3, "ac3", channels = 6)

    @Test
    fun eac3_7_1() = assertDecodes("eac3_7.1.mkv", MimeTypes.AUDIO_E_AC3, "eac3", channels = 8)

    @Test
    fun dts_5_1() = assertDecodes("dts_5.1.mkv", MimeTypes.AUDIO_DTS, "dca", channels = 6)

    @Test
    fun truehd_5_1() = assertDecodes("truehd_5.1.mkv", MimeTypes.AUDIO_TRUEHD, "truehd", channels = 6)

    @Test
    fun flac_5_1_24bit() = assertDecodes("flac_5.1_24bit.mkv", MimeTypes.AUDIO_FLAC, "flac", channels = 6)

    @Test
    fun alac_5_1() = assertDecodes("alac_5.1.m4a", MimeTypes.AUDIO_ALAC, "alac", channels = 6)

    @Test
    fun opus() = assertDecodes("opus_2.0.mkv", MimeTypes.AUDIO_OPUS, "opus", channels = 2)

    @Test
    fun vorbis() = assertDecodes("vorbis_2.0.mkv", MimeTypes.AUDIO_VORBIS, "vorbis", channels = 2)

    @Test
    fun mp3() = assertDecodes("mp3_2.0.mkv", MimeTypes.AUDIO_MPEG, "mp3", channels = 2)

    @Test
    fun defaultRenderersFactoryFindsTheFfmpegRenderer() {
        var renderers = emptyList<Any>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            renderers = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .createRenderers(
                    Handler(Looper.getMainLooper()),
                    object : VideoRendererEventListener {},
                    object : AudioRendererEventListener {},
                    object : TextOutput {
                        override fun onCues(cueGroup: CueGroup) = Unit
                    },
                    MetadataOutput { }
                ).toList()
        }
        val ffmpeg = renderers.indexOfFirst { it is FfmpegAudioRenderer }
        assertTrue(ffmpeg >= 0, "DefaultRenderersFactory did not load FfmpegAudioRenderer: $renderers")
        assertTrue(ffmpeg < renderers.indexOfFirst { it is MediaCodecAudioRenderer }, "FFmpeg renderer not preferred")
    }

    private fun assertDecodes(asset: String, mimeType: String, codec: String, channels: Int) {
        assertTrue(FfmpegLibrary.isAvailable(), "FFmpeg native libraries did not load")
        val result = play(asset)
        assertNull(result.error, "playback error for $asset")
        assertEquals(mimeType, result.inputFormat?.sampleMimeType, "input format of $asset")
        val decoder = assertNotNull(result.decoderName, "no audio decoder initialized for $asset")
        assertTrue(decoder.startsWith("ffmpeg") && decoder.endsWith("-$codec"), "decoder $decoder for $asset")
        assertTrue(result.renderedBuffers > 0, "no output buffers rendered for $asset")
        val pcm = assertNotNull(result.sink.pcmFormat, "audio sink never configured for $asset")
        Log.i(
            "FfmpegAudioRendererTest",
            "$asset: decoder=$decoder channels=${pcm.channelCount} encoding=${pcm.pcmEncoding} " +
                "buffers=${result.renderedBuffers} peaks=${result.sink.peaks.joinToString { "%.2f".format(it) }}"
        )
        assertEquals(channels, pcm.channelCount, "PCM channel count for $asset")
        val error = result.sink.error
        if (error != null) fail("$asset: $error")
        result.sink.peaks.forEachIndexed { channel, peak ->
            assertTrue(peak > 0.1, "channel $channel of $asset is silent (peak $peak)")
        }
    }

    private class Result(val sink: MeasuringSink) {
        var error: PlaybackException? = null
        var inputFormat: Format? = null
        var decoderName: String? = null
        var renderedBuffers = 0
    }

    private fun play(asset: String): Result {
        val thread = HandlerThread("player").apply { start() }
        val handler = Handler(thread.looper)
        val result = Result(MeasuringSink(DefaultAudioSink.Builder(context).build()))
        val ended = CountDownLatch(1)
        var player: ExoPlayer? = null
        handler.post {
            val renderers = RenderersFactory { eventHandler, _, audioListener, _, _ ->
                arrayOf(FfmpegAudioRenderer(eventHandler, audioListener, result.sink))
            }
            val exo = ExoPlayer.Builder(context, renderers).setLooper(thread.looper).build()
            player = exo
            exo.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    result.decoderName = decoderName
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?
                ) {
                    result.inputFormat = format
                }
            })
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        result.renderedBuffers = exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                        ended.countDown()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    result.error = error
                    ended.countDown()
                }
            })
            exo.setMediaItem(MediaItem.fromUri("asset:///clips/$asset"))
            exo.prepare()
            exo.play()
        }
        val finished = ended.await(60, TimeUnit.SECONDS)
        val released = CountDownLatch(1)
        handler.post {
            player?.release()
            released.countDown()
        }
        released.await(10, TimeUnit.SECONDS)
        thread.quitSafely()
        assertTrue(finished, "$asset did not finish playing")
        return result
    }

    /** Passes audio through to a real sink while recording the PCM format and the peak level of every channel. */
    private class MeasuringSink(sink: DefaultAudioSink) : ForwardingAudioSink(sink) {
        @Volatile var pcmFormat: Format? = null

        @Volatile var peaks = DoubleArray(0)

        @Volatile var error: String? = null

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            pcmFormat = audioSinkConfig.format
            peaks = DoubleArray(audioSinkConfig.format.channelCount)
            super.configure(audioSinkConfig)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            measure(buffer.duplicate().order(ByteOrder.nativeOrder()))
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        private fun measure(data: ByteBuffer) {
            val format = pcmFormat ?: return
            val channels = format.channelCount
            val (bytes, read) = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 2 to { b: ByteBuffer, i: Int -> b.getShort(i) / 32768.0 }
                C.ENCODING_PCM_FLOAT -> 4 to { b: ByteBuffer, i: Int -> b.getFloat(i).toDouble() }
                else -> {
                    error = "unexpected PCM encoding ${format.pcmEncoding}"
                    return
                }
            }
            val frame = bytes * channels
            var position = data.position()
            while (position + frame <= data.limit()) {
                for (channel in 0 until channels) {
                    val level = abs(read(data, position + channel * bytes))
                    if (level > peaks[channel]) peaks[channel] = level
                }
                position += frame
            }
        }
    }
}
