/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.Futures
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TranscodeSeekPlayerTest {
    @Test
    fun sourceZeroAndRestartedSourceExposeOneAbsoluteTimeline() {
        for (start in listOf(0L, 126_000L)) {
            val local = LocalPlayer()
            val wrapped = TranscodeSeekPlayer(local, start, 600_000) { true }
            try {
                assertEquals(start + 4_000, wrapped.currentPosition)
                assertEquals(start + 4_000, wrapped.contentPosition)
                assertEquals(start + 24_000, wrapped.bufferedPosition)
                assertEquals(start + 24_000, wrapped.contentBufferedPosition)
                assertEquals(20_000L, wrapped.totalBufferedDuration)
                assertEquals(600_000L, wrapped.duration)
                assertTrue(wrapped.isCurrentMediaItemSeekable)
                assertFalse(wrapped.isCurrentMediaItemDynamic)
                assertFalse(wrapped.isCurrentMediaItemLive)
                assertTrue(wrapped.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                assertTrue(wrapped.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
                assertTrue(wrapped.isCommandAvailable(Player.COMMAND_SEEK_BACK))
                val window = wrapped.currentTimeline.getWindow(0, androidx.media3.common.Timeline.Window())
                assertEquals(600_000L, window.durationMs)
                assertEquals(0L, window.defaultPositionMs)
            } finally {
                wrapped.release()
            }
        }
    }

    @Test
    fun heldForwardAndBackwardStepsAccumulateAgainstPendingTargetWithoutLocalSeeks() {
        val local = LocalPlayer()
        val targets = mutableListOf<Long>()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) {
            targets += it
            true
        }
        try {
            repeat(10) { wrapped.seekForward() }
            assertEquals((1..10).map { 130_000L + it * 10_000 }, targets)
            assertEquals(230_000L, wrapped.currentPosition)
            assertEquals(Player.STATE_BUFFERING, wrapped.playbackState)
            assertEquals(0L, wrapped.totalBufferedDuration)
            repeat(3) { wrapped.seekBack() }
            assertEquals(200_000L, wrapped.currentPosition)
            assertEquals(listOf(220_000L, 210_000L, 200_000L), targets.takeLast(3))
            assertTrue(local.seeks.isEmpty())
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun pendingTargetSurvivesUnderlyingPositionUpdatesAndPauseIsForwarded() {
        val local = LocalPlayer()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) { true }
        try {
            wrapped.play()
            assertTrue(local.playWhenReady)
            wrapped.seekTo(300_000)
            wrapped.pause()
            assertFalse(local.playWhenReady)
            assertFalse(wrapped.playWhenReady)
            local.moveTo(8_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(300_000L, wrapped.currentPosition)
            assertEquals(300_000L, wrapped.bufferedPosition)
            assertFalse(wrapped.isPlaying)
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun discontinuityCallbacksExposeOriginalCoordinatesExactlyOnce() {
        val local = LocalPlayer()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) { true }
        val changes = mutableListOf<Pair<Long, Long>>()
        wrapped.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                changes += oldPosition.positionMs to newPosition.positionMs
            }
        })
        try {
            // Listener registration is lazy in SimpleBasePlayer; establish the pre-event snapshot first.
            assertEquals(130_000L, wrapped.currentPosition)
            local.moveTo(12_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(138_000L, wrapped.currentPosition)
            assertEquals(130_000L to 138_000L, changes.last())
            wrapped.seekTo(200_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(200_000L, wrapped.currentPosition)
            assertEquals(138_000L to 200_000L, changes.last())
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun synchronousSourceStopDuringSeekPublishesOneAbsoluteDiscontinuityAndBufferingState() {
        val local = LocalPlayer()
        val targets = mutableListOf<Long>()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) { target ->
            local.stop()
            // Deliver the child's invalidation before the seek callback returns.
            shadowOf(Looper.getMainLooper()).idle()
            targets += target
            true
        }
        val changes = mutableListOf<Pair<Long, Long>>()
        wrapped.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                changes += oldPosition.positionMs to newPosition.positionMs
            }
        })
        try {
            assertEquals(130_000L, wrapped.currentPosition)
            wrapped.seekTo(200_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(130_000L to 200_000L), changes)
            assertEquals(listOf(200_000L), targets)
            assertEquals(200_000L, wrapped.currentPosition)
            assertEquals(Player.STATE_BUFFERING, wrapped.playbackState)
            assertEquals(Player.STATE_IDLE, local.playbackState)
            assertTrue(local.seeks.isEmpty())
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun absoluteSeekClampsAtTheFullMediaBoundsAndDefaultMeansBeginningOfFilm() {
        val local = LocalPlayer()
        val targets = mutableListOf<Long>()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) {
            targets += it
            true
        }
        try {
            wrapped.seekTo(Long.MAX_VALUE)
            assertEquals(600_000L, targets.last())
            wrapped.seekTo(0)
            assertEquals(0L, targets.last())
            wrapped.seekTo(50_000)
            wrapped.seekToDefaultPosition()
            assertEquals(0L, targets.last())
            assertEquals(0L, wrapped.currentPosition)
            assertTrue(local.seeks.isEmpty())
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun realMediaSessionControllerSeesAbsoluteDurationAndSeeksTheOriginalFilm() {
        val local = LocalPlayer()
        val targets = mutableListOf<Long>()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) {
            targets += it
            true
        }
        val handle = PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), wrapped)
        val future = MediaController.Builder(RuntimeEnvironment.getApplication(), handle.session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get(2, TimeUnit.SECONDS)
        try {
            assertEquals(130_000L, controller.currentPosition)
            assertEquals(600_000L, controller.duration)
            assertTrue(controller.isCurrentMediaItemSeekable)
            repeat(10) { controller.seekForward() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(230_000L, wrapped.currentPosition)
            assertEquals(230_000L, targets.last())
            assertEquals(230_000L, controller.currentPosition)
            controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(local.playWhenReady)
        } finally {
            controller.release()
            handle.close()
            wrapped.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun rejectedSeekOnErroredIdleSourceKeepsTheErrorPositionAndValidIdleState() {
        val local = LocalPlayer()
        val error = PlaybackException("Synthetic decode failure", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        local.fail(error)
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) { false }
        try {
            assertEquals(130_000L, wrapped.currentPosition)
            wrapped.seekTo(200_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Player.STATE_IDLE, wrapped.playbackState)
            assertSame(error, wrapped.playerError)
            assertEquals(130_000L, wrapped.currentPosition)
            assertFalse(wrapped.isPlaying)
            assertTrue(local.seeks.isEmpty())
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun healthyRejectedSeekDoesNotFreezeTheClockOrInventPendingBuffering() {
        val local = LocalPlayer()
        val requests = mutableListOf<Long>()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) {
            requests += it
            false
        }
        try {
            assertEquals(130_000L, wrapped.currentPosition)
            wrapped.seekTo(200_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(200_000L), requests)
            assertEquals(Player.STATE_READY, wrapped.playbackState)
            assertEquals(130_000L, wrapped.currentPosition)
            assertEquals(150_000L, wrapped.bufferedPosition)
            assertEquals(20_000L, wrapped.totalBufferedDuration)
            local.moveTo(8_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(134_000L, wrapped.currentPosition)
            assertEquals(Player.STATE_READY, wrapped.playbackState)
            assertTrue(local.seeks.isEmpty())
        } finally {
            wrapped.release()
        }
    }

    @Test
    fun childErrorDuringAcceptedPendingSeekRetainsTheErrorWithoutInvalidBufferingState() {
        val local = LocalPlayer()
        val wrapped = TranscodeSeekPlayer(local, 126_000, 600_000) { true }
        val error = PlaybackException("Synthetic decode failure", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        try {
            wrapped.seekTo(200_000)
            assertEquals(Player.STATE_BUFFERING, wrapped.playbackState)
            local.fail(error)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Player.STATE_IDLE, wrapped.playbackState)
            assertSame(error, wrapped.playerError)
            assertEquals(200_000L, wrapped.currentPosition)
            assertFalse(wrapped.isPlaying)
            assertTrue(local.seeks.isEmpty())
        } finally {
            wrapped.release()
        }
    }

    private class LocalPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var local = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA
                ).build()
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("stream").setMediaItem(MediaItem.Builder().setMediaId("item-1").build())
                        .setDurationUs(C.TIME_UNSET).setIsSeekable(false).build()
                )
            )
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(4_000)
            .setContentBufferedPositionMs(PositionSupplier { 24_000 })
            .setTotalBufferedDurationMs(PositionSupplier { 20_000 })
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        override fun getState(): State = local

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            local = local.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(currentPosition).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks += positionMs
            local = local.buildUpon().setContentPositionMs(positionMs).build()
        }

        override fun handleStop() = Futures.immediateVoidFuture().also {
            local = local.buildUpon().setContentPositionMs(currentPosition).setPlaybackState(Player.STATE_IDLE).build()
        }

        override fun handleRelease() = Futures.immediateVoidFuture()

        fun fail(error: PlaybackException) {
            local = local.buildUpon().setPlaybackState(Player.STATE_IDLE).setPlayerError(error).build()
            invalidateState()
        }

        fun moveTo(positionMs: Long) {
            local = local.buildUpon().setContentPositionMs(positionMs)
                .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL, positionMs).build()
            invalidateState()
        }
    }
}
