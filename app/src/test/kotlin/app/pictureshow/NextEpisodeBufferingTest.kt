/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class NextEpisodeBufferingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test
    fun countdownWaitsForActualReadyPlaybackAndSuspendsAgainOnRebuffer() {
        val player = BufferingPlayer()
        val account = Account(Server("server", "Test", "https://example.test", "12.0"), User("viewer", "Viewer"), "token")
        var plays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val playback = rememberPlayerPlayback(player, {}, {})
            MaterialTheme {
                NextEpisodeControl(
                    account,
                    MediaItem("next", "Next story"),
                    true,
                    false,
                    playback.paused || playback.buffering,
                    {},
                    { plays++ },
                    countdownSeconds = 3
                )
            }
        }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText("Next episode · 3 s left").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, plays)
            player.ready(true)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("Next episode · 2 s left").assertIsDisplayed()
        compose.runOnIdle { player.ready(false) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText("Next episode · 2 s left").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, plays)
            player.ready(true)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(3_000)
        compose.runOnIdle { assertEquals(1, plays) }
        compose.runOnIdle { player.release() }
    }

    private class BufferingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setPlaylist(listOf(MediaItemData.Builder("fixture").setDurationUs(120_000_000).build()))
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        override fun getState() = state
        fun ready(value: Boolean) {
            state = state.buildUpon().setPlaybackState(if (value) Player.STATE_READY else Player.STATE_BUFFERING).build()
            invalidateState()
        }
    }
}
