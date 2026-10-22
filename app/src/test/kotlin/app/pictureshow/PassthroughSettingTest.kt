/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PassthroughSettingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setSampleRate(48000).setChannelCount(6).build()
    private val pcm = ac3.buildUpon().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    @AfterTest
    fun clearCodecs() {
        ShadowMediaCodecList.reset()
        MediaCodecUtil.clearDecoderInfoCache()
    }

    @Test
    fun actualRouteAwareSinkAllowsEncodedOnButOnlyDecodedPcmOff() {
        connectHdmi()
        val enabled = SinkFactory(true).sink()
        val disabled = SinkFactory(false).sink()
        try {
            assertTrue(enabled.supportsFormat(ac3), "The actual HDMI sink must advertise encoded AC3 for this control")
            assertFalse(disabled.supportsFormat(ac3), "Passthrough off must prevent the actual renderer's encoded bypass")
            assertTrue(enabled.supportsFormat(pcm))
            assertTrue(disabled.supportsFormat(pcm), "Platform and FFmpeg decoders can still output PCM")
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, disabled.getFormatSupport(ac3))
            assertFalse(disabled.getFormatOffloadSupport(ac3).isFormatSupported)
        } finally {
            enabled.release()
            disabled.release()
        }
    }

    @Test
    fun pcmProviderRejectsEncodedConfigurationEvenIfCallerSkipsSupportQuery() {
        val provider = PcmAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build())
        try {
            assertFailsWith<AudioOutputProvider.ConfigurationException> {
                provider.getOutputConfig(AudioOutputProvider.FormatConfig.Builder(ac3).build())
            }
        } finally {
            provider.release()
        }
    }

    @Test
    fun disabledPassthroughRequiresDecoderAndRegisteredPlatformWinsWhenBothDecodersHandle() {
        connectHdmi()
        ShadowMediaCodecList.reset()
        MediaCodecUtil.clearDecoderInfoCache()
        val factory = PictureshowRenderersFactory(context, passthroughEnabled = false)
        val renderers = factory.createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            {},
            {}
        ).filter { it.trackType == C.TRACK_TYPE_AUDIO }
        val platform = renderers.indexOfFirst { it is FlacBufferAudioRenderer }
        val ffmpeg = renderers.indexOfFirst { it.javaClass.name == "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer" }
        assertTrue(platform >= 0 && ffmpeg > platform)
        // No platform codec: PCM-only sink cannot claim compressed HANDLED through passthrough.
        assertEquals(
            C.FORMAT_UNSUPPORTED_SUBTYPE,
            RendererCapabilities.getFormatSupport(renderers[platform].capabilities.supportsFormat(ac3))
        )
        val caps = CodecCapabilitiesBuilder.newBuilder().setMediaFormat(
            MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MimeTypes.AUDIO_AC3)
                setString("max-channel-count", "6")
                setString("sample-rate-ranges", "32000,44100,48000")
            }
        ).build()
        // The previous empty lookup cached MediaCodecList's inventory, independently of Media3's cache.
        ShadowMediaCodecList.reset()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("test.platform.ac3.decoder").setCapabilities(caps).build()
        )
        MediaCodecUtil.clearDecoderInfoCache()
        assertTrue(MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.AUDIO_AC3, false, false).isNotEmpty())
        val platformCapabilities = renderers[platform].capabilities
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(platformCapabilities.supportsFormat(ac3)))
        // Android JNI is unavailable on the host. Simulate only FFmpeg's HANDLED capability;
        // the platform capability above and selector arbitration below are the real implementations.
        val availableFfmpeg = Proxy.newProxyInstance(
            RendererCapabilities::class.java.classLoader,
            arrayOf(RendererCapabilities::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "Available software audio"
                "getTrackType" -> C.TRACK_TYPE_AUDIO
                "supportsFormat" -> RendererCapabilities.create(C.FORMAT_HANDLED)
                "supportsMixedMimeTypeAdaptation" -> RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
                else -> null
            }
        } as RendererCapabilities
        val selector = DefaultTrackSelector(context)
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        val result = selector.selectTracks(
            arrayOf(platformCapabilities, availableFfmpeg),
            TrackGroupArray(TrackGroup(ac3)),
            MediaPeriodId("test"),
            Timeline.EMPTY
        )
        assertNotNull(result.selections[0])
        assertEquals(null, result.selections[1])
        selector.release()
    }

    private fun connectHdmi() {
        context.sendStickyBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3))
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
        )
    }

    private inner class SinkFactory(enabled: Boolean) : PictureshowRenderersFactory(context, passthroughEnabled = enabled) {
        fun sink() = buildAudioSink(context, false, false)
    }
}
