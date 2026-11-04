/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.graphics.Bitmap
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
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentType
import app.pictureshow.core.Server
import app.pictureshow.core.TrickplayInfo
import app.pictureshow.core.TrickplayTile
import app.pictureshow.core.User
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.google.common.util.concurrent.Futures
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun loadedThumbnailDoesNotMoveSliderDuringHeldDrag() {
        launch(paused = true)
        val info = TrickplayInfo(16, 9, 10, 6, 60, 1_000)
        val image = ByteArrayOutputStream().also {
            Bitmap.createBitmap(160, 54, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val http = HttpClient(MockEngine { respond(image) })
        val cache = TrickplayCache(http, account, ClientIdentity("Phone", "device"), "movie")
        try {
            // Load the actual sheet before the gesture, so the test includes bitmap layout.
            runBlocking { checkNotNull(cache.thumbnail("source", TrickplayTile(info, 0, 0, 0))) }
            compose.runOnIdle {
                host.metadata = host.metadata.copy(trickplay = mapOf("source" to mapOf("16" to info)))
                host.tiles = cache
                host.stateSource = androidx.compose.runtime.mutableStateOf(
                    PlaybackState(
                        preparing = false,
                        decision = PlayDecision(
                            "movie",
                            MediaSourceInfo(id = "source"),
                            PlayMethod.DirectPlay,
                            "https://example.test/video",
                            emptyMap(),
                            "session"
                        )
                    )
                )
            }
            val before = compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("touch:scrubber").performTouchInput {
                down(Offset(width * 0.3f, height / 2f))
                moveTo(Offset(width * 0.7f, height / 2f))
            }
            compose.onNodeWithTag("touch:thumbnail").assertIsDisplayed()
            val during = compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode().boundsInRoot
            assertEquals(before.center.y, during.center.y, "A loaded preview must not move the track away from the held finger")
            compose.runOnIdle { assertEquals(20_000, decoder.currentPosition) }
            compose.onNodeWithTag("touch:scrubber").performTouchInput { up() }
            compose.runOnIdle {
                assertTrue(host.position > 20_000)
                assertEquals(host.position, decoder.currentPosition)
            }
        } finally {
            cache.close()
            http.close()
        }
    }

    @Test fun heldScrubberSuspendsAutomaticNextEpisodeCountdown() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            host.position = 50_000
            host.segments = listOf(MediaSegment("outro", SegmentType.Outro, 450_000_000, 600_000_000))
            host.nextLookup = TouchEpisodeLookup(0, ClientResult.Success(MediaItem("next", "Another episode")))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("next:play").assertIsDisplayed()
        compose.onNodeWithTag("touch:scrubber").performTouchInput {
            down(Offset(width * 0.8f, height / 2f))
            moveTo(Offset(width * 0.3f, height / 2f))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(15_000)
        compose.runOnIdle { assertEquals(0, exits, "Continuation must not replace the item during a held seek") }
        compose.onNodeWithTag("next:play").assertIsDisplayed()
        compose.onNodeWithTag("touch:scrubber").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(decoder.seeks.last() < 45_000, "Release commits the seek back before the outro")
            assertEquals(host.position, decoder.seeks.last())
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
