/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LivePlaybackPlayerTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    @Test fun pauseExpiryStaysPausedAndExplicitPlaySeeksMeasuredLiveDefault() {
        val decoder = Decoder()
        val live = LivePlaybackPlayer(decoder)
        live.pause()
        assertFalse(decoder.playWhenReady)
        decoder.slide(20_000)
        live.observe()
        assertTrue(live.bufferExpired)
        assertFalse(decoder.playWhenReady)
        // Reading and seeking do not resume a viewer whose paused position expired.
        live.seekTo(3_000)
        assertTrue(decoder.seeks.isEmpty())
        assertFalse(decoder.playWhenReady)
        live.play()
        assertFalse(live.bufferExpired)
        assertTrue(decoder.playWhenReady)
        assertEquals(listOf(C.TIME_UNSET), decoder.seeks)
        assertEquals(27_000L, decoder.currentPosition)
        live.release()
    }

    @Test fun seeksRejectUnknownOrOutOfRangeTargetsAndAcceptMeasuredEndpoints() {
        val decoder = Decoder()
        val live = LivePlaybackPlayer(decoder)
        live.seekTo(30_001)
        assertTrue(decoder.seeks.isEmpty())
        live.seekTo(30_000)
        assertEquals(listOf(30_000L), decoder.seeks)
        live.release()
    }

    @Test fun nonseekableLiveDoesNotOfferPauseOrSeekAndGoLiveStillUsesObservedDefault() {
        val decoder = Decoder(seekable = false)
        val live = LivePlaybackPlayer(decoder)
        assertFalse(live.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertFalse(live.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        live.pause()
        live.seekTo(5_000)
        assertTrue(decoder.playWhenReady)
        assertTrue(decoder.seeks.isEmpty())
        live.goLive()
        assertEquals(listOf(C.TIME_UNSET), decoder.seeks)
        live.release()
    }

    private class Decoder(private val seekable: Boolean = true) : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var offset = 0L
        private var playing = true
        private var position = 10_000L
        private fun media() = MediaItemData.Builder("live")
            .setMediaItem(MediaItem.Builder().setMediaId("channel").build())
            .setDurationUs(30_000_000).setDefaultPositionUs(27_000_000)
            .setPositionInFirstPeriodUs(offset * 1_000).setIsDynamic(true).setIsSeekable(seekable).build()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_PREPARE
                ).build()
            )
            .setPlaylist(listOf(media())).setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(position).build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also { playing = playWhenReady }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks += positionMs
            position = if (positionMs == C.TIME_UNSET) 27_000L else positionMs
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
        fun slide(offsetMs: Long) {
            offset = offsetMs
            invalidateState()
        }
    }
}
