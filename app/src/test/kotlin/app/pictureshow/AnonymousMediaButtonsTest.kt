/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AnonymousMediaButtonsTest {
    @Test
    fun anonymousKeysToggleOnceAndSeekThroughTheActualSessionPlayer() = withSession { session, player ->
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(player.playWhenReady)
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP))
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeat = 1))
        assertFalse(player.playWhenReady)
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(player.playWhenReady)
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(22_000L, player.currentPosition)
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(12_000L, player.currentPosition)
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertFalse(player.playWhenReady)
    }

    @Test
    fun namedCallersAndNonMediaKeysRemainOnTheNormalMedia3Path() = withSession { session, player ->
        assertFalse(key(session, KeyEvent.KEYCODE_MEDIA_PAUSE, caller = "example.remote"))
        assertTrue(player.playWhenReady)
        val modern = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME,
            123,
            2000,
            1,
            1,
            false,
            Bundle.EMPTY,
            false
        )
        assertFalse(
            AnonymousMediaButtons.onMediaButtonEvent(
                session,
                modern,
                Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                )
            )
        )
        assertTrue(player.playWhenReady)
        assertFalse(key(session, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(12_000L, player.currentPosition)
        assertFalse(AnonymousMediaButtons.onMediaButtonEvent(session, caller(), Intent(Intent.ACTION_MEDIA_BUTTON)))
    }

    @Test
    fun unavailableSeekIsConsumedWithoutCallingThePlayer() = withSession(seekable = false) { session, player ->
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertTrue(key(session, KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(12_000L, player.currentPosition)
        assertEquals(0, player.seeks)
    }

    private fun caller(name: String = MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            name,
            123,
            2000,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            0,
            false,
            Bundle.EMPTY,
            false
        )

    private fun key(
        session: MediaSession,
        code: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0,
        caller: String = MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME
    ) = AnonymousMediaButtons.onMediaButtonEvent(
        session,
        caller(caller),
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0, 0, action, code, repeat))
    )

    private fun withSession(seekable: Boolean = true, run: (MediaSession, StatePlayer) -> Unit) {
        val player = StatePlayer(seekable)
        val session = PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), player)
        try {
            run(session.session, player)
        } finally {
            session.close()
            player.release()
        }
    }

    private class StatePlayer(seekable: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        var seeks = 0
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                )
                    .apply {
                        if (seekable) addAll(Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_BACK)
                    }.build()
            )
            .setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(120_000_000).setIsSeekable(seekable).build()))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(12_000)
            .setSeekForwardIncrementMs(10_000)
            .setSeekBackIncrementMs(10_000)
            .build()

        override fun getState(): State = state

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(currentPosition).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks++
            state = state.buildUpon().setContentPositionMs(positionMs).build()
        }

        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
