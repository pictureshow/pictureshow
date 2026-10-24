/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EncodedBufferSizingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val hd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setSampleRate(48000).setChannelCount(2).build()
    private fun route() {
        context.sendStickyBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                .putExtra(
                    AudioManager.EXTRA_ENCODINGS,
                    intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS, C.ENCODING_DTS_HD)
                )
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
        )
    }

    @AfterTest fun cleanup() {
        context.getSharedPreferences("audio_output_failures", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun configured(sink: AudioSink, format: Format): AudioOutputProvider.OutputConfig {
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        val configuration = DefaultAudioSink::class.java.getDeclaredField("configuration").apply { isAccessible = true }.get(sink)
        return configuration.javaClass.getDeclaredField("outputConfig").apply {
            isAccessible = true
        }.get(configuration) as AudioOutputProvider.OutputConfig
    }

    private fun appSink() = object : PictureshowRenderersFactory(context) {
        fun sink() = buildAudioSink(context, false, false)
    }.sink()

    @Test fun realFactoryCapsUnknownDtsHdBeforeAnyAudioTrackAttempt() {
        route()
        val sink = appSink()
        try {
            assertEquals(2_097_152, configured(sink, hd).bufferSize)
        } finally {
            sink.release()
        }
    }

    @Test fun ac3Eac3DtsCoreAndPcmKeepTheExactMedia3Sizes() {
        route()
        val app = appSink()
        val original = DefaultAudioSink.Builder(
            context
        ).setAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()).build()
        try {
            for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_RAW)) {
                val format = hd.buildUpon().setSampleMimeType(mime).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
                assertEquals(configured(original, format).bufferSize, configured(app, format).bufferSize, mime)
            }
        } finally {
            app.release()
            original.release()
        }
    }

    @Test fun refusalFromTheOldBufferPolicyCannotHideHdBeforeTheNewAttempt() {
        route()
        val store = AudioOutputFailures(context)
        context.getSharedPreferences("audio_output_failures", Context.MODE_PRIVATE).edit()
            .putString("route", store.routeKey()).putLong("at", System.currentTimeMillis())
            .putStringSet("encodings", setOf(C.ENCODING_DTS_HD.toString())).commit()
        val caps = DeviceCapabilityProbe(context).snapshot()
        assertTrue(PassthroughEncoding.DTS_HD in caps.audioOutput.passthrough)
        assertTrue(PassthroughEncoding.DTS in caps.audioOutput.passthrough)
        assertTrue(store.rejected().isEmpty())
    }

    @Test fun highRateEncodedBuffersAreBoundedButWorkingFormatsAndPlatformMinimumAreUnchanged() {
        val original = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
        for (encoding in listOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_PCM_16BIT)) {
            for (mode in listOf(
                DefaultAudioSink.OUTPUT_MODE_PCM,
                DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
                DefaultAudioSink.OUTPUT_MODE_OFFLOAD
            )) {
                if (encoding == C.ENCODING_PCM_16BIT && mode != DefaultAudioSink.OUTPUT_MODE_PCM) continue
                for (speed in listOf(1.0, 2.0, 8.0)) {
                    assertEquals(
                        original.getBufferSizeInBytes(4096, encoding, mode, 1, 48000, Format.NO_VALUE, speed),
                        EncodedAudioBuffers.getBufferSizeInBytes(4096, encoding, mode, 1, 48000, Format.NO_VALUE, speed)
                    )
                }
            }
        }
        for (encoding in listOf(C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD)) {
            assertEquals(
                2_097_152,
                EncodedAudioBuffers.getBufferSizeInBytes(
                    4096,
                    encoding,
                    DefaultAudioSink.OUTPUT_MODE_OFFLOAD,
                    1,
                    48000,
                    Format.NO_VALUE,
                    1.0
                )
            )
        }
        assertEquals(
            2_097_152,
            EncodedAudioBuffers.getBufferSizeInBytes(
                4096,
                C.ENCODING_DTS_HD,
                DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
                1,
                48000,
                24_500_000,
                1.0
            )
        )
        assertEquals(
            3_000_000,
            EncodedAudioBuffers.getBufferSizeInBytes(
                3_000_000,
                C.ENCODING_DTS_HD,
                DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
                1,
                48000,
                Format.NO_VALUE,
                1.0
            )
        )
    }

    @Test fun realSinkCanSucceedAtFirstOrSmallerBufferWithoutLearningARefusal() {
        route()
        for (limit in listOf(2_097_152, 1_048_576)) {
            val attempts = mutableListOf<Int>()
            val output = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when {
                    method.name == "write" -> {
                        (args!![0] as ByteBuffer).position((args[0] as ByteBuffer).limit())
                        true
                    }
                    method.name == "getSampleRate" -> 48000
                    method.returnType == java.lang.Boolean.TYPE -> false
                    method.returnType == java.lang.Long.TYPE -> 0L
                    method.returnType == java.lang.Integer.TYPE -> 0
                    else -> null
                }
            } as AudioOutput
            val delegate = object : ForwardingAudioOutputProvider(
                AudioTrackAudioOutputProvider.Builder(context).setAudioTrackBufferSizeProvider(EncodedAudioBuffers).build()
            ) {
                override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                    attempts += config.bufferSize
                    if (config.bufferSize > limit) throw AudioOutputProvider.InitializationException()
                    return output
                }
            }
            val store = AudioOutputFailures(context)
            val sink = DefaultAudioSink.Builder(context).setAudioOutputProvider(RecoverableAudioOutputProvider(delegate, store)).build()
            try {
                configured(sink, hd)
                sink.handleBuffer(ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN), 0, 1)
                assertEquals(if (limit == 2_097_152) listOf(2_097_152) else listOf(2_097_152, 1_048_576), attempts)
                assertTrue(store.rejected().isEmpty())
                assertTrue(PassthroughEncoding.DTS_HD in DeviceCapabilityProbe(context).snapshot().audioOutput.passthrough)
            } finally {
                sink.release()
            }
        }
    }

    @Test fun onlyExhaustedRightSizedAttemptsReachTheRealAudioRecoveryListener() {
        route()
        val attempts = mutableListOf<Int>()
        val delegate = object : ForwardingAudioOutputProvider(
            AudioTrackAudioOutputProvider.Builder(context).setAudioTrackBufferSizeProvider(EncodedAudioBuffers).build()
        ) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                attempts += config.bufferSize
                throw AudioOutputProvider.InitializationException()
            }
        }
        val store = AudioOutputFailures(context)
        val sink = DefaultAudioSink.Builder(context).setAudioOutputProvider(RecoverableAudioOutputProvider(delegate, store)).build()
        val output = Media3PlaybackOutput(context, Server("test", "Test", "https://example.test", "12"))
        try {
            configured(sink, hd)
            val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
            var terminal: AudioSink.InitializationException? = null
            for (attempt in 0..10) {
                try {
                    sink.handleBuffer(buffer, 0, 1)
                } catch (failure: AudioSink.InitializationException) {
                    terminal = failure
                    break
                }
                assertTrue(store.rejected().isEmpty())
                ShadowSystemClock.advanceBy(Duration.ofMillis(50))
            }
            val error = assertNotNull(terminal)
            assertTrue(attempts.size >= 2)
            assertTrue(attempts.chunked(2).all { it == listOf(2_097_152, 1_048_576) })
            val decision =
                PlayDecision(
                    "film",
                    MediaSourceInfo(id = "source"),
                    PlayMethod.DirectPlay,
                    "https://example.test/video",
                    emptyMap(),
                    "session"
                )
            var recovered = false
            output.onAudioFailure = { _, local -> recovered = local }
            output.onFailure = { _, _ -> error("Must use audio recovery") }
            output.open(decision, 128_379, -1, -1, true)
            val listener = Media3PlaybackOutput::class.java.getDeclaredField("listener").apply {
                isAccessible = true
            }.get(output) as Player.Listener
            listener.onPlayerError(
                ExoPlaybackException.createForRenderer(
                    error,
                    "Audio",
                    1,
                    hd,
                    C.FORMAT_HANDLED,
                    false,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                )
            )
            assertTrue(recovered)
            assertEquals(setOf(C.ENCODING_DTS_HD), store.rejected())
        } finally {
            sink.release()
            output.release()
        }
    }
}
