/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.Futures
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, shadows = [PlaybackMediaSessionTest.RecordingMediaController::class])
class PlaybackMediaSessionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun media3ControllerSeesActualStateMetadataPositionAndCanPausePlayAndSeek() {
        val player = StatePlayer()
        val handle = PlaybackMediaSession.open(context, player)
        val future = MediaController.Builder(context, handle.session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get(2, TimeUnit.SECONDS)
        try {
            assertTrue(controller.isConnected)
            assertEquals("Example feature", controller.mediaMetadata.title)
            assertEquals(12_000L, controller.currentPosition)
            assertTrue(controller.isPlaying)
            controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.isPlaying)
            assertFalse(controller.isPlaying)
            controller.play()
            controller.seekForward()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(player.isPlaying)
            assertEquals(22_000L, player.currentPosition)
            controller.seekBack()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(12_000L, player.currentPosition)
            player.moveTo(15_000)
            assertEquals(15_000L, player.currentPosition)
            // Session position snapshots are periodic, not a separate hand-maintained playback clock.
            shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
            assertEquals(18_000L, controller.currentPosition)
            assertEquals(player.currentPosition, controller.currentPosition)
            controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(controller.isPlaying)
            val stoppedPosition = controller.currentPosition
            shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
            assertEquals(stoppedPosition, controller.currentPosition)
            assertEquals(player.currentPosition, controller.currentPosition)
            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(controller.isConnected)
            assertFalse(PlaybackMediaSession.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)))
        } finally {
            controller.release()
            handle.close()
            player.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun replacingSessionDoesNotLetOldHandleCloseTheNewSessionAndDpadIsNotConsumed() {
        val first = StatePlayer()
        val second = StatePlayer()
        val old = PlaybackMediaSession.open(context, first)
        val active = PlaybackMediaSession.open(context, second)
        old.close()
        try {
            assertFalse(PlaybackMediaSession.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
            assertEquals(second, active.session.player)
            assertTrue(PlaybackMediaSession.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
            val delivered = Shadow.extract<RecordingMediaController>(active.controller)
            assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY), delivered.keys)
        } finally {
            active.close()
            first.release()
            second.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** The framework shadow has no real system media-button service; record bridge delivery only. */
    @Implements(android.media.session.MediaController::class)
    class RecordingMediaController : org.robolectric.shadows.ShadowMediaController() {
        val keys = mutableListOf<Int>()

        @Implementation
        protected fun dispatchMediaButtonEvent(event: KeyEvent): Boolean {
            keys += event.keyCode
            return true
        }
    }

    private class StatePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_RELEASE
                ).build()
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("example").setMediaItem(
                        MediaItem.Builder().setMediaId("example").setMediaMetadata(
                            MediaMetadata.Builder().setTitle("Example feature").build()
                        ).build()
                    ).setDurationUs(120_000_000).setIsSeekable(true).build()
                )
            )
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setContentPositionMs(12_000)
            .build()

        override fun getState(): State = state

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(currentPosition).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setContentPositionMs(positionMs).build()
        }

        override fun handleRelease() = Futures.immediateVoidFuture()

        fun moveTo(positionMs: Long) {
            state = state.buildUpon().setContentPositionMs(positionMs)
                .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL, positionMs).build()
            invalidateState()
        }
    }
}
