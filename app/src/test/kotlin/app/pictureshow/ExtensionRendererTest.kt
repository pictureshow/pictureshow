/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ExtensionRendererTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun realFfmpegExtensionLoadsAfterPlatformAndPassthroughWinsWithoutAnyDecoder() {
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "supportsFormat" -> (args!![0] as Format).sampleMimeType in setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_RAW)
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "getFormatOffloadSupport" -> androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink
        val factory = object : PictureshowRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean) = sink
        }.setMediaCodecSelector(MediaCodecSelector { _, _, _ -> emptyList() })
        val audio = factory.createRenderers(
            Handler(Looper.getMainLooper()), object : VideoRendererEventListener {}, object : AudioRendererEventListener {}, {}, {}
        ).filter { it.trackType == C.TRACK_TYPE_AUDIO }
        val platform = audio.indexOfFirst { it is FlacBufferAudioRenderer }
        val ffmpeg = audio.indexOfFirst { it.javaClass.name == "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer" }
        assertTrue(platform >= 0)
        assertTrue(ffmpeg > platform, "The actual FFmpeg extension must be reflectively present and follow platform audio")
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(48000).build()
        // With no decoders at all, HANDLED can only come from the renderer's encoded-sink bypass path.
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(audio[platform].capabilities.supportsFormat(format)))
        val selector = DefaultTrackSelector(context)
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        val result = selector.selectTracks(
            audio.map { it.capabilities }.toTypedArray(), TrackGroupArray(TrackGroup(format)), MediaPeriodId("test"), Timeline.EMPTY
        )
        assertNotNull(result.selections[platform])
        assertEquals(null, result.selections[ffmpeg])
    }
}
