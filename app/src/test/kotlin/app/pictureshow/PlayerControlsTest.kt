/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test
    fun idleControlsHideAfterTenSeconds() {
        // Ten seconds: Media3's touch control view defaults to five; on TV each button is several D-pad
        // presses away, and every key restarts the count, so ten only bounds an idle viewer.
        assertEquals(10_000L, CONTROLS_TIMEOUT_MS)
        var hides = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { ControlsAutoHide(true, false, 0L) { hides++ } }
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle { assertEquals(0, hides) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(1, hides) }
    }

    @Test
    fun anyKeyInteractionRestartsTheIdleCount() {
        var interaction by mutableLongStateOf(0L)
        var hides = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { ControlsAutoHide(true, false, interaction) { hides++ } }
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle { interaction++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle { assertEquals(0, hides) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(1, hides) }
    }

    @Test
    fun pinnedControlsStayUpUntilUnpinned() {
        var pinned by mutableStateOf(true)
        var hides = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { ControlsAutoHide(true, pinned, 0L) { hides++ } }
        compose.mainClock.advanceTimeBy(30_000)
        compose.runOnIdle { assertEquals(0, hides) }
        compose.runOnIdle { pinned = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle { assertEquals(0, hides) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(1, hides) }
    }

    @Test
    fun pauseAndResumeEventsDriveShowAndHide() {
        val player = StatePlayer()
        val events = mutableListOf<String>()
        var playback: PlayerPlayback? = null
        compose.setContent { playback = rememberPlayerPlayback(player, { events += "pause" }, { events += "resume" }) }
        compose.runOnIdle { assertEquals(PlayerPlayback(paused = false, showPlayButton = false), playback) }
        compose.runOnIdle { player.pause() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("pause"), events)
            assertEquals(PlayerPlayback(paused = true, showPlayButton = true), playback)
        }
        compose.runOnIdle { player.play() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("pause", "resume"), events)
            assertEquals(PlayerPlayback(paused = false, showPlayButton = false), playback)
        }
    }

    @Test
    fun playPauseLabelFollowsBufferingPauseAndEndOfStream() {
        val player = StatePlayer()
        var playback: PlayerPlayback? = null
        compose.setContent { playback = rememberPlayerPlayback(player, {}, {}) }
        compose.runOnIdle { assertEquals(false, playback?.showPlayButton) }
        compose.runOnIdle { player.seekTo(60_000) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Player.STATE_BUFFERING, player.playbackState)
            assertEquals(false, playback?.showPlayButton)
        }
        compose.runOnIdle { player.pause() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(true, playback?.showPlayButton) }
        compose.runOnIdle { player.play() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, playback?.showPlayButton) }
        compose.runOnIdle { player.stop() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Player.STATE_ENDED, player.playbackState)
            assertEquals(true, playback?.showPlayButton)
        }
    }

    private class StatePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE
                ).build()
            )
            .setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(600_000_000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(42_000)
            .build()

        override fun getState(): State = state

        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setPlaybackState(Player.STATE_BUFFERING).setContentPositionMs(positionMs).build()
        }

        override fun handleStop() = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setPlaybackState(Player.STATE_ENDED).build()
        }

        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
