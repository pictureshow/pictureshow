/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.legacy.PlaybackStateCompat
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackReopenStateTest {
    @Test fun pausedTrackReopenKeepsBufferingAndPauseIntent() = reopen(paused = true)

    @Test fun playingTrackReopenKeepsBufferingAndPlayIntent() = reopen(paused = false)

    @Test fun progressiveSeekBurstKeepsBufferingAndUsesTheLastAbsoluteTarget() = reopen(paused = true, progressive = true)

    private fun reopen(paused: Boolean, progressive: Boolean = false) {
        val output = Media3PlaybackOutput(
            RuntimeEnvironment.getApplication(),
            Server("server", "Test", "https://example.test", "12"),
            upstream = DataSource.Factory { WaitingSource() }
        )
        fun decision(id: String) = PlayDecision(
            "episode",
            MediaSourceInfo(
                id = "source",
                runTimeTicks = 600_000_000,
                transcodingSubProtocol = MediaStreamProtocol.Hls,
                transcodingContainer = "ts",
                mediaStreams = if (progressive) {
                    listOf(
                        MediaStream(type = MediaStreamType.Video, codec = "av1", realFrameRate = 60.0),
                        MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", channels = 6)
                    )
                } else {
                    emptyList()
                }
            ),
            PlayMethod.Transcode,
            "https://example.test/Videos/episode/master.m3u8" + if (progressive) "?MaxFramerate=30&AudioCodec=aac" else "",
            emptyMap(),
            id
        )
        fun handle(): PlaybackMediaSession.Handle = Media3PlaybackOutput::class.java.getDeclaredField("mediaSession")
            .apply { isAccessible = true }.get(output) as PlaybackMediaSession.Handle
        try {
            output.open(decision("first"), 40_000, -1, -1, paused)
            val session = handle().session
            val token = session.platformToken
            val future = MediaController.Builder(RuntimeEnvironment.getApplication(), session.token).buildAsync()
            shadowOf(Looper.getMainLooper()).idle()
            val controller = future.get(2, TimeUnit.SECONDS)
            val observed = mutableListOf<Int>()
            controller.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    observed += playbackState
                }
            })
            val atRetirement = mutableListOf<Int>()
            output.onSeek = { _, _ ->
                output.beginSeek()
                atRetirement += session.player.playbackState
                true
            }
            if (progressive) {
                assertIs<TranscodeSeekPlayer>(output.player.value)
                session.player.seekForward()
                session.player.seekForward()
                assertEquals(60_000, output.position().milliseconds)
                assertEquals(listOf(Player.STATE_BUFFERING, Player.STATE_BUFFERING), atRetirement)
            } else {
                output.beginSeek()
            }
            // A viewing that has not played yet presents preparation with play intent as PLAYING at speed 0
            // (PlaybackMediaSession), so the TV Now Playing card is not posted again as audio starts.
            val preparing = if (paused) Player.STATE_BUFFERING else Player.STATE_READY
            assertEquals(preparing, session.player.playbackState, "An active viewing must keep its preparation state during negotiation")
            assertEquals(false, session.player.isPlaying)
            assertEquals(!paused, session.player.playWhenReady)
            shadowOf(Looper.getMainLooper()).idle()
            assertLegacy(handle(), if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING)
            if (paused) Util.handlePlayButtonAction(controller) else controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(paused, output.player.value!!.playWhenReady)
            val decoder = Media3PlaybackOutput::class.java.getDeclaredField("activePlayer")
                .apply { isAccessible = true }.get(output) as ExoPlayer
            assertEquals(Player.STATE_IDLE, decoder.playbackState, "Play must not re-prepare the retired stream")
            output.open(decision("replacement"), 50_000, -1, -1, output.position().paused)
            assertEquals(token, handle().session.platformToken)
            // The viewer toggled play intent inside the first preparation. Its grace survives both the toggle and
            // the replacement, so the session stays READY: legacy PLAYING with play intent, PAUSED without.
            assertEquals(Player.STATE_READY, session.player.playbackState)
            assertEquals(false, session.player.isPlaying)
            assertEquals(paused, session.player.playWhenReady)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Player.STATE_READY, controller.playbackState)
            assertEquals(paused, controller.playWhenReady)
            assertEquals(false, controller.isPlaying)
            assertLegacy(handle(), if (paused) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            assertEquals(false, Player.STATE_IDLE in observed, "Controllers must never observe no media between decoders")
            controller.release()
        } finally {
            output.release()
        }
    }

    /** The legacy state external controllers and the TV launcher read: state, speed zero, and a frozen position. */
    private fun assertLegacy(handle: PlaybackMediaSession.Handle, expected: Int) {
        val first = legacy(handle)
        assertEquals(expected, first.state)
        assertEquals(0f, first.playbackSpeed)
        shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)
        val later = legacy(handle)
        assertEquals(expected, later.state)
        assertEquals(first.position, later.position, "A preparing viewing must not extrapolate position")
        assertEquals(handle.session.player.currentPosition, later.position)
    }

    // Robolectric's platform controller shadow does not mirror MediaSession's state; read pinned Media3's own copy.
    private fun legacy(handle: PlaybackMediaSession.Handle): PlaybackStateCompat {
        var value: Any = handle.session
        for (name in listOf("impl", "sessionLegacyStub", "sessionCompat", "impl", "playbackState")) {
            var type: Class<*>? = value.javaClass
            var field: java.lang.reflect.Field? = null
            while (type != null && field == null) {
                field = runCatching { type!!.getDeclaredField(name) }.getOrNull()
                type = type.superclass
            }
            value = checkNotNull(checkNotNull(field).apply { isAccessible = true }.get(value))
        }
        return value as PlaybackStateCompat
    }

    private class WaitingSource : DataSource {
        private var uri: android.net.Uri? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
            Thread.sleep(Long.MAX_VALUE)
            C.RESULT_END_OF_INPUT
        } catch (_: InterruptedException) {
            throw java.io.InterruptedIOException()
        }
        override fun close() = Unit
    }
}
