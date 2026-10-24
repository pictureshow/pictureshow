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
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.nio.ByteBuffer
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AudioOutputRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val hd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setSampleRate(48000).setChannelCount(2).build()
    private fun prefs() = context.getSharedPreferences("audio_output_failures", Context.MODE_PRIVATE)

    @AfterTest fun cleanup() {
        prefs().edit().clear().commit()
    }

    @Test fun refusalSurvivesNewInstancesButExpiresAndResetsOnRouteOrSurroundChangeAndIgnoresLateErrors() {
        var route = "speaker-auto"
        var time = 123_000L
        fun store() = AudioOutputFailures(prefs(), { route }, { time })
        assertTrue(store().reject(C.ENCODING_DTS_HD, route))
        assertEquals(setOf(C.ENCODING_DTS_HD), store().rejected())
        route = "arc-auto"
        assertTrue(store().rejected().isEmpty())
        assertFalse(store().reject(C.ENCODING_DTS, "speaker-auto"))
        assertTrue(store().reject(C.ENCODING_DTS, route))
        route = "arc-manual"
        assertTrue(store().rejected().isEmpty())
        store().reject(C.ENCODING_DTS_HD, route)
        time += AudioOutputFailures.RETRY_AFTER_MS
        assertTrue(store().rejected().isEmpty(), "A transient refusal must not disable a format forever")
    }

    @Test fun actualSinkAndProbeRemoveRefusedHdButRetainDtsAc3AndPcmAcrossNewPlayback() {
        context.sendStickyBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_DTS, C.ENCODING_DTS_HD))
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
        )
        val failures = AudioOutputFailures(context)
        failures.reject(C.ENCODING_DTS_HD, failures.routeKey())
        val sink = object : PictureshowRenderersFactory(context) {
            fun sink() = buildAudioSink(context, false, false)
        }.sink()
        try {
            assertFalse(sink.supportsFormat(hd))
            assertTrue(sink.supportsFormat(hd.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS).build()))
            assertTrue(sink.supportsFormat(hd.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AC3).build()))
            assertTrue(
                sink.supportsFormat(hd.buildUpon().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build())
            )
            val caps = DeviceCapabilityProbe(context, ffmpegSupport = {
                it == MimeTypes.AUDIO_DTS || it == MimeTypes.AUDIO_DTS_HD
            }).snapshot()
            assertFalse(PassthroughEncoding.DTS_HD in caps.audioOutput.passthrough)
            assertTrue(PassthroughEncoding.DTS in caps.audioOutput.passthrough)
            assertTrue(caps.audioDecoders.any { it.codec == AudioCodec.DTS })
            assertFalse("DTS-HD" in thisTvReport(caps, "Test", "Test", "PCM", emptyList()).summary)
        } finally {
            sink.release()
        }
    }

    @Test fun injectedOutputInitRefusalIsLearnedOnlyAtTerminalPlayerErrorAndInvokesAudioRecovery() {
        context.sendStickyBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_DTS_HD))
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
        )
        val failures = AudioOutputFailures(context)
        val delegate = object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                throw AudioOutputProvider.InitializationException()
        }
        val provider = RecoverableAudioOutputProvider(delegate, failures)
        val sink = DefaultAudioSink.Builder(context).setAudioOutputProvider(provider).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(hd).build())
        val buffer = ByteBuffer.allocateDirect(16)
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertTrue(failures.rejected().isEmpty(), "Sink smaller-buffer retries have not finished yet")
        var terminal: AudioSink.InitializationException? = null
        for (attempt in 1..10) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(50))
            try {
                sink.handleBuffer(buffer, 0, 1)
            } catch (failure: AudioSink.InitializationException) {
                terminal = failure
                break
            }
            assertTrue(failures.rejected().isEmpty(), "Pending sink retries must not learn a refusal")
        }
        val sinkFailure = assertNotNull(terminal, "Real DefaultAudioSink must eventually expose the init failure")
        val marker = generateSequence<Throwable>(sinkFailure) { it.cause }.filterIsInstance<EncodedAudioOutputRefused>().first()
        assertEquals(C.ENCODING_DTS_HD, marker.encoding)
        val output = Media3PlaybackOutput(context, Server("test", "Test", "https://example.test", "12"))
        val decision =
            PlayDecision("film", MediaSourceInfo(id = "source"), PlayMethod.DirectPlay, "https://example.test/video", emptyMap(), "session")
        var audioRecoveries = 0
        var genericRecoveries = 0
        output.onFailure = { _, _ -> genericRecoveries++ }
        output.onAudioFailure = { source, local ->
            assertSame(decision, source)
            assertTrue(local)
            audioRecoveries++
        }
        try {
            output.open(decision, 128_379, -1, -1, true)
            val listener = Media3PlaybackOutput::class.java.getDeclaredField("listener").apply {
                isAccessible = true
            }.get(output) as Player.Listener
            listener.onPlayerError(
                ExoPlaybackException.createForRenderer(
                    sinkFailure,
                    "Audio",
                    1,
                    hd,
                    C.FORMAT_HANDLED,
                    false,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                )
            )
            assertEquals(1, audioRecoveries)
            assertEquals(0, genericRecoveries)
            assertEquals(setOf(C.ENCODING_DTS_HD), failures.rejected())
        } finally {
            output.release()
            sink.release()
        }
    }
}
