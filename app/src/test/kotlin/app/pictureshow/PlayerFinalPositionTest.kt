/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Server
import com.google.common.collect.ImmutableList
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerFinalPositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun endRefreshesTheQuietSpokenClockWithoutTurningEveryTickIntoSpeech() {
        var position by mutableStateOf(48_000L)
        var ended by mutableStateOf(false)
        compose.setContent { PictureshowTheme { PlayerTimeline(position, 48_000, 60_031, ended = ended) } }
        val clock = compose.onNodeWithContentDescription("Playback position")
        clock.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:48 of 1:00"))
        compose.runOnIdle { position = 55_000 }
        clock.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:48 of 1:00"))
        compose.runOnIdle {
            position = 60_031
            ended = true
        }
        clock.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:00 of 1:00"))
    }

    @Test fun liveSpokenClockStaysWithinDurationDuringTheRendererTail() {
        compose.setContent { PictureshowTheme { PlayerTimeline(62_059, 62_059, 60_031) } }
        compose.onNodeWithContentDescription("Playback position")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:00 of 1:00"))
    }

    @Test fun liveOutputPositionIsBoundedBeforeAnyEndCallbackOrManualStop() {
        val player = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun handleRelease() = com.google.common.util.concurrent.Futures.immediateVoidFuture()
            override fun getState(): State = State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(ImmutableList.of(MediaItemData.Builder("media").setDurationUs(60_031_000).build()))
                .setCurrentMediaItemIndex(0).setContentPositionMs(62_059)
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        val output = Media3PlaybackOutput(compose.activity, Server("s", "Test", "https://example.test", "12"))

        // Supply only the published player clock: exercise the production position()/Stop boundary
        // without claiming that this fixture extracted or rendered a real stream.
        @Suppress("UNCHECKED_CAST")
        val published = Media3PlaybackOutput::class.java.getDeclaredField("mutablePlayer").let {
            it.isAccessible = true
            it.get(output) as MutableStateFlow<Player?>
        }
        try {
            published.value = player
            assertEquals(60_031L, output.position().milliseconds)
        } finally {
            published.value = null
            output.release()
            player.release()
        }
    }

    @Test fun completedPositionUsesStreamTimelineInsteadOfDecoderOverrunOrServerMetadata() {
        assertEquals(60_031L, playbackEndPosition(62_059, 60_031))
        // A server runtime shorter than the actual stream is not an argument to this policy.
        assertEquals(90_000L, playbackEndPosition(90_000, 120_000))
        // A physical sample end can precede a damaged container's declared duration.
        assertEquals(60_000L, playbackEndPosition(60_000, 4_294_982_295))
        assertEquals(60_000L, playbackEndPosition(60_000, 0))
        assertEquals(60_000L, playbackEndPosition(60_000, Long.MIN_VALUE + 1))
        assertEquals(0L, playbackEndPosition(-1, 60_000))
    }

    @Test fun serverSeekEndUsesTheAbsoluteItemTimelineWithAndWithoutDecoderOverrun() {
        for (overrun in listOf(0L, 2_000L)) {
            val decoder = object : SimpleBasePlayer(Looper.getMainLooper()) {
                override fun handleRelease() = com.google.common.util.concurrent.Futures.immediateVoidFuture()
                override fun getState(): State = State.Builder()
                    .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                    .setPlaylist(
                        ImmutableList.of(
                            MediaItemData.Builder("media").setMediaItem(PlayerMediaItem.EMPTY)
                                .setDurationUs(1_800_000_000L).build()
                        )
                    )
                    .setCurrentMediaItemIndex(0).setContentPositionMs(1_800_000L + overrun)
                    .setPlaybackState(Player.STATE_ENDED).setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .build()
            }
            val exposed = TranscodeSeekPlayer(decoder, 1_800_000L, 3_600_000L) { false }
            assertEquals(3_600_000L, boundedPlaybackPosition(exposed).milliseconds)
            exposed.release()
        }
    }
}
