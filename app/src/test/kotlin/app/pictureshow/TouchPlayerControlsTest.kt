/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import com.google.common.util.concurrent.Futures
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlayerControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var host: PlaybackHostState
    private var exits = 0

    @After fun cleanup() {
        compose.runOnIdle {
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
    }

    @Test fun tapHidesAndShowsControls() {
        launch()
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        assertEquals("Show controls", compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
    }

    @Test fun playingHidesAfterFiveSecondsAndPausePinsControls() {
        compose.mainClock.autoAdvance = false
        launch()
        compose.mainClock.advanceTimeBy(5_100)
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        compose.runOnIdle { decoder.pause() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test fun leftAndRightDoubleTapSeekTenSecondsAndClamp() {
        launch()
        compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.2f, height * 0.3f)) }
        compose.runOnIdle { assertEquals(10_000, host.position) }
        compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.8f, height * 0.3f)) }
        compose.runOnIdle { assertEquals(20_000, host.position) }
        assertEquals(0, touchSeekTarget(2_000, 60_000, false))
        assertEquals(60_000, touchSeekTarget(58_000, 60_000, true))
    }

    @Test fun scrubberSemanticsCommitsAbsolutePositionToDecoder() {
        launch(paused = true)
        compose.onNodeWithTag("touch:scrubber").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(37_000f)) }
        compose.runOnIdle {
            assertEquals(37_000, host.position)
            assertEquals(37_000, decoder.currentPosition)
        }
    }

    @Test fun scrubberDragPreviewsBeforeCommitAndCommitsOnRelease() {
        launch(paused = true)
        compose.onNodeWithTag("touch:scrubber").performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveTo(Offset(width * 0.7f, height / 2f))
        }
        compose.runOnIdle {
            assertEquals(20_000, host.position, "Dragging previews without committing")
            assertEquals(20_000, decoder.currentPosition, "The decoder must not seek until release")
        }
        compose.onNodeWithTag("touch:scrubber").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(host.position > 20_000)
            assertEquals(host.position, decoder.currentPosition)
        }
    }

    @Test fun allSheetsOpenAndDoneDismisses() {
        launch()
        TouchPlayerSheet.entries.forEach { sheet ->
            compose.onNodeWithTag("touch:open:$sheet").performClick()
            compose.onNodeWithTag("touch:sheet:$sheet").assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("touch:sheet:$sheet").assertDoesNotExist()
        }
    }

    @Test fun chapterSelectionSeeksAndDismisses() {
        launch()
        compose.onNodeWithTag("touch:open:Chapters").performClick()
        compose.onNodeWithTag("touch:chapter:1").performClick()
        compose.runOnIdle { assertEquals(30_000, host.position) }
        compose.onNodeWithTag("touch:sheet:Chapters").assertDoesNotExist()
    }

    @Test fun systemBackClosesSheetThenControlsThenExitsOnce() {
        launch()
        compose.onNodeWithTag("touch:open:Audio").performClick()
        back()
        compose.onNodeWithTag("touch:sheet:Audio").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        back()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        back()
        back()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun subtitleDelayIsBoundedAndResettable() {
        launch()
        compose.onNodeWithTag("touch:open:Subtitles").performClick()
        compose.onNodeWithText("Later").performClick()
        compose.runOnIdle { assertEquals(100, host.subtitleDelayMs) }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(0, host.subtitleDelayMs) }
    }

    @Test fun talkBackCanShowControlsAndReachEveryActionWithoutAutoHide() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val manager = compose.activity.getSystemService(
                android.content.Context.ACCESSIBILITY_SERVICE
            ) as android.view.accessibility.AccessibilityManager
            org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        }
        launch()
        compose.mainClock.advanceTimeBy(12_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("touch:gestures").assertHasClickAction()
        assertEquals("Hide controls", compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("touch:gestures").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitForIdle()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        assertEquals("Show controls", compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("touch:gestures").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitForIdle()
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("touch:open:Audio").assertHasClickAction()
        compose.onNodeWithTag("touch:open:Subtitles").assertHasClickAction()
        compose.onNodeWithTag("touch:open:Chapters").assertHasClickAction()
        compose.onNodeWithTag("touch:stop").assertHasClickAction()
        compose.onNodeWithTag("touch:scrubber").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(25_000f)) }
        compose.runOnIdle { assertEquals(25_000, decoder.seeks.last()) }
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun launch(paused: Boolean = false) {
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder(!paused)
            host = PlaybackHostState(MediaItem("movie", "A film"), 20_000, output, {}, { exits++ })
            host.playerSource = androidx.compose.runtime.mutableStateOf(decoder)
            host.stateSource = androidx.compose.runtime.mutableStateOf(PlaybackState(preparing = false))
            host.duration = 60_000
            host.metadata = host.item.copy(chapters = listOf(Chapter(0, "Opening"), Chapter(300_000_000, "Middle")))
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TouchPlayerContent(account, host) } }
    }

    private class Decoder(playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var value = State.Builder().setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE
            ).build()
        ).setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(20_000).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks += positionMs
            value = value.buildUpon().setContentPositionMs(positionMs).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
