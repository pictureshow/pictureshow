/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackActionsTest {
    @Test
    fun idleTogglePreparesAtTheSavedPositionEvenIfPlayWhenReadyIsAlreadyTrue() {
        for (playWhenReady in listOf(false, true)) {
            val player = StatePlayer(Player.STATE_IDLE, playWhenReady)
            try {
                player.togglePlayback()
                assertEquals(1, player.prepares)
                assertTrue(player.playWhenReady)
                assertEquals(Player.STATE_READY, player.playbackState)
                assertEquals(42_000L, player.currentPosition)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun endedToggleRestartsAtTheDefaultPositionEvenIfPlayWhenReadyIsAlreadyTrue() {
        for (playWhenReady in listOf(false, true)) {
            val player = StatePlayer(Player.STATE_ENDED, playWhenReady)
            try {
                player.togglePlayback()
                assertEquals(1, player.seeks)
                assertEquals(0L, player.currentPosition)
                assertTrue(player.playWhenReady)
                assertEquals(Player.STATE_READY, player.playbackState)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun readyAndBufferingTogglesStillPauseAndResumeNormally() {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
            val player = StatePlayer(state, true)
            try {
                player.togglePlayback()
                assertFalse(player.playWhenReady)
                player.togglePlayback()
                assertTrue(player.playWhenReady)
                assertEquals(0, player.prepares)
                assertEquals(0, player.seeks)
            } finally {
                player.release()
            }
        }
    }

    private class StatePlayer(playbackState: Int, playWhenReady: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        var prepares = 0
        var seeks = 0
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE
                ).build()
            )
            .setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(120_000_000).setIsSeekable(true).build()))
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(42_000)
            .build()

        override fun getState(): State = state

        override fun handlePrepare() = Futures.immediateVoidFuture().also {
            prepares++
            state = state.buildUpon().setPlaybackState(Player.STATE_READY).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks++
            state = state.buildUpon().setContentPositionMs(0).setPlaybackState(Player.STATE_READY).build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }

        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
