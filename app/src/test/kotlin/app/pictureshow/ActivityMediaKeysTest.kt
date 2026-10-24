/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager.RemoteUserInfo
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real activity -> platform controller -> API 30 callback -> pinned Media3 -> actual wrapper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, shadows = [ActivityMediaKeysTest.ControllerBridge::class])
class ActivityMediaKeysTest {
    @Test
    fun toggleInsideTheGracePausesOnceAndStaysPausedAfterReplacement() = withViewing { old, reopening, handle, activity ->
        reopening.beginReopen()
        old.state(Player.STATE_IDLE)
        idle()
        val legacy = field(requireNotNull(ControllerBridge.platform), "mPlaybackState") as android.media.session.PlaybackState
        assertEquals(3, legacy.state)
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle()
        assertFalse(reopening.playWhenReady)
        val replacement = StatePlayer(Player.STATE_BUFFERING, reopening.playWhenReady)
        try {
            handle.replacePlayer(ReopeningPlayer(replacement))
            idle(400)
            replacement.state(Player.STATE_READY)
            assertFalse(replacement.playWhenReady)
            assertEquals(1, old.pauseCommands)
            assertEquals(0, replacement.playCommands + replacement.pauseCommands)
        } finally {
            replacement.release()
        }
    }

    @Test
    fun activityShortcutRequiresBothOwnPackageAndOwnUidAndLegacyVersion() = withViewing { old, _, handle, _ ->
        val context = RuntimeEnvironment.getApplication()
        val callback = ActivityMediaButtons(context)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        )
        fun caller(name: String, uid: Int, version: Int = MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) =
            MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, 123, uid, version, 0, false, Bundle.EMPTY, false)
        assertFalse(callback.onMediaButtonEvent(handle.session, caller(context.packageName, Process.myUid() + 1), intent))
        assertFalse(callback.onMediaButtonEvent(handle.session, caller("example.remote", Process.myUid()), intent))
        assertFalse(callback.onMediaButtonEvent(handle.session, caller(context.packageName, Process.myUid(), 1), intent))
        assertTrue(old.playWhenReady)
        assertTrue(callback.onMediaButtonEvent(handle.session, caller(context.packageName, Process.myUid()), intent))
        assertFalse(old.playWhenReady)
        assertEquals(1, old.pauseCommands)
        val anonymousPlay = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        )
        assertTrue(
            callback.onMediaButtonEvent(
                handle.session,
                caller(MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME, 2000),
                anonymousPlay
            )
        )
        assertTrue(old.playWhenReady, "The installed callback must preserve anonymous system-key handling")
    }

    @Test
    fun remoteTogglePausesWhileTheOldDecoderIsRetired() = withViewing { old, reopening, handle, activity ->
        retire(old, reopening)
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle(400)
        assertFalse(reopening.playWhenReady, "BUFFERING is still a playing intent: toggle must pause")
        val replacement = StatePlayer(Player.STATE_BUFFERING, reopening.playWhenReady)
        try {
            handle.replacePlayer(ReopeningPlayer(replacement))
            replacement.state(Player.STATE_READY)
            assertFalse(handle.session.player.playWhenReady, "The new stream must inherit that last press")
            assertEquals(1, old.pauseCommands)
        } finally {
            replacement.release()
        }
    }

    @Test
    fun remoteTogglePausesTheNewBufferingDecoder() = withViewing { old, reopening, handle, activity ->
        retire(old, reopening)
        val replacement = StatePlayer(Player.STATE_BUFFERING, true)
        try {
            handle.replacePlayer(ReopeningPlayer(replacement))
            press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            idle(400)
            assertFalse(replacement.playWhenReady, "A replacement not READY yet must honor Pause")
            replacement.state(Player.STATE_READY)
            assertFalse(handle.session.player.playWhenReady)
            assertEquals(1, replacement.pauseCommands)
        } finally {
            replacement.release()
        }
    }

    @Test
    fun onePressHasNoDoubleTapDelayOrKeyUpRepeatToggle() = withViewing { old, _, _, activity ->
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle()
        assertFalse(old.playWhenReady, "A remote pause must not wait for the double-tap timeout")
        event(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, repeat = 1)
        event(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP)
        idle(400)
        assertFalse(old.playWhenReady)
        assertEquals(1, old.pauseCommands)
        assertEquals(0, old.playCommands)
    }

    @Test
    fun rapidDistinctPressesToggleTwiceAndTheLastIntentSurvivesReplacement() = withViewing { old, reopening, handle, activity ->
        retire(old, reopening)
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle()
        assertFalse(reopening.playWhenReady)
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle()
        assertTrue(reopening.playWhenReady)
        val replacement = StatePlayer(Player.STATE_BUFFERING, reopening.playWhenReady)
        try {
            handle.replacePlayer(ReopeningPlayer(replacement))
            press(activity, KeyEvent.KEYCODE_MEDIA_PAUSE)
            idle()
            assertFalse(replacement.playWhenReady)
            press(activity, KeyEvent.KEYCODE_MEDIA_PLAY)
            idle(400)
            replacement.state(Player.STATE_READY)
            assertTrue(replacement.playWhenReady)
            assertEquals(1, old.pauseCommands)
            assertEquals(1, old.playCommands)
            assertEquals(1, replacement.pauseCommands)
            assertEquals(1, replacement.playCommands)
            assertEquals(0, old.seeks + replacement.seeks, "Two toggles must not become Skip Next")
        } finally {
            replacement.release()
        }
    }

    @Test
    fun explicitPauseAndPlayAreHonoredOnBothSidesOfReplacement() = withViewing { old, reopening, handle, activity ->
        retire(old, reopening)
        press(activity, KeyEvent.KEYCODE_MEDIA_PAUSE)
        idle()
        assertFalse(reopening.playWhenReady)
        val replacement = StatePlayer(Player.STATE_BUFFERING, reopening.playWhenReady)
        try {
            handle.replacePlayer(ReopeningPlayer(replacement))
            press(activity, KeyEvent.KEYCODE_MEDIA_PLAY)
            idle()
            assertTrue(replacement.playWhenReady)
            press(activity, KeyEvent.KEYCODE_MEDIA_PAUSE)
            idle()
            assertFalse(replacement.playWhenReady)
            replacement.state(Player.STATE_READY)
            assertFalse(replacement.playWhenReady)
        } finally {
            replacement.release()
        }
    }

    @Test
    fun keyQueuedAcrossReplacementUsesTheCurrentPlayer() = withViewing { old, reopening, handle, activity ->
        retire(old, reopening)
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val replacement = StatePlayer(Player.STATE_BUFFERING, true)
        try {
            handle.replacePlayer(ReopeningPlayer(replacement))
            idle(400)
            assertFalse(replacement.playWhenReady)
            assertEquals(0, old.pauseCommands)
            assertEquals(1, replacement.pauseCommands)
        } finally {
            replacement.release()
        }
    }

    @Test
    fun pausedReopenCanResume() = withViewing(paused = true) { old, reopening, _, activity ->
        retire(old, reopening)
        press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle(400)
        assertTrue(reopening.playWhenReady)
    }

    private fun retire(old: StatePlayer, reopening: ReopeningPlayer) {
        reopening.beginReopen()
        old.state(Player.STATE_IDLE)
        idle(5_100) // Follow-up 1's grace must not mask the fallback's BUFFERING toggle bug.
        assertEquals(Player.STATE_BUFFERING, reopening.playbackState)
        val legacy = field(requireNotNull(ControllerBridge.platform), "mPlaybackState") as android.media.session.PlaybackState
        assertEquals(if (reopening.playWhenReady) 6 else 2, legacy.state)
    }

    private fun press(activity: MainActivity, code: Int) {
        event(activity, code, KeyEvent.ACTION_DOWN)
        event(activity, code, KeyEvent.ACTION_UP)
    }

    private fun event(activity: MainActivity, code: Int, action: Int, repeat: Int = 0) {
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 0, action, code, repeat)))
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun withViewing(
        paused: Boolean = false,
        run: (StatePlayer, ReopeningPlayer, PlaybackMediaSession.Handle, MainActivity) -> Unit
    ) {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        val old = StatePlayer(Player.STATE_READY, !paused)
        val reopening = ReopeningPlayer(old)
        val handle = PlaybackMediaSession.open(context, reopening) {}
        val impl = field(handle.session, "impl")
        val legacy = field(impl, "sessionLegacyStub")
        val compat = legacy.javaClass.getDeclaredMethod("getSessionCompat").apply { isAccessible = true }.invoke(legacy)
        ControllerBridge.platform = compat.javaClass.getMethod("getMediaSession").invoke(compat) as android.media.session.MediaSession
        ControllerBridge.callerPackage = context.packageName
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        try {
            idle()
            run(old, reopening, handle, activity)
        } finally {
            handle.close()
            old.release()
            ControllerBridge.platform = null
            idle(400)
        }
    }

    /** Only substitute the absent system-service IPC. API30's real callback/default code runs. */
    @Implements(android.media.session.MediaController::class)
    class ControllerBridge : org.robolectric.shadows.ShadowMediaController() {
        @Implementation
        protected fun dispatchMediaButtonEvent(event: KeyEvent): Boolean {
            val target = requireNotNull(platform)
            val caller = RemoteUserInfo(callerPackage, Process.myPid(), Process.myUid())
            target.javaClass.getDeclaredMethod("dispatchMediaButton", RemoteUserInfo::class.java, Intent::class.java)
                .apply {
                    isAccessible = true
                }.invoke(target, caller, Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
            return true
        }

        companion object {
            var platform: android.media.session.MediaSession? = null
            var callerPackage = ""
        }
    }

    private class StatePlayer(state: Int, playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        var pauseCommands = 0
        var playCommands = 0
        var seeks = 0
        private var value = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                ).build()
            )
            .setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(120_000_000).build()))
            .setPlaybackState(state)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(12_000)
            .build()

        override fun getState(): State = value

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            if (playWhenReady) playCommands++ else pauseCommands++
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also { seeks++ }

        override fun handleRelease() = Futures.immediateVoidFuture()

        fun state(state: Int) {
            value = value.buildUpon().setPlaybackState(state).build()
            invalidateState()
        }
    }

    companion object {
        private fun field(target: Any, name: String): Any =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)!!
    }
}
