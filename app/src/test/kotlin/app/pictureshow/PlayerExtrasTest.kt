/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType
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
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerExtrasTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val account = Account(Server("s", "Demo", "http://localhost/base", "12"), User("u", "Demo"), "token")
    private val intro = MediaSegment("intro", SegmentType.Intro, 100_000_000, 200_000_000)

    @Test
    fun productionRemoteKeysSeekOnSurfaceAndLetFocusedSkipHandleCenter() {
        val root = FocusRequester()
        var focused by mutableStateOf(false)
        var sought = 0L
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.focusRequester(root).onFocusChanged { focused = it.isFocused }
                        .playerRemoteKeys(true, focused, { sought += it }, {}, {}, back = {})
                        .focusable()
                ) {
                    Row {
                        SegmentControl(listOf(intro), 12_000, emptyMap(), { sought = it }, {})
                        Button(onClick = {}) { Text("Other control") }
                    }
                }
            }
        }
        compose.runOnIdle { root.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(10_000, sought) }
        compose.onNodeWithTag("skip:Intro").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("skip:Intro").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(20_000, sought) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Other control").assertIsFocused()
        compose.runOnIdle { assertEquals(20_000, sought) }
    }

    @Test
    fun backKeyClosesPanelOnceEvenWhenOtherPlayerKeysAreDisabled() {
        var open by mutableStateOf(true)
        var backs = 0
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.playerRemoteKeys(false, false, {}, {}, {}, back = {
                        backs++
                        open = false
                    }).focusable()
                ) {
                    if (open) Button(onClick = {}, modifier = Modifier.testTag("panel")) { Text("Panel content") }
                }
            }
        }
        compose.onNodeWithTag("panel").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("panel").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.runOnIdle { assertEquals(1, backs) }
        compose.onNodeWithTag("panel").assertDoesNotExist()
    }

    @Test
    fun skipAppearsWithoutStealingFocusIsReachableAndDisappearsAtEnd() {
        var position by mutableStateOf(9_000L)
        var sought = -1L
        val existing = FocusRequester()
        compose.setContent {
            MaterialTheme {
                Column {
                    Button(onClick = {}, modifier = Modifier.focusRequester(existing).then(Modifier)) { Text("Existing control") }
                    SegmentControl(listOf(intro), position, emptyMap(), onSeek = {
                        sought = it
                    }, onFocusRemoved = { existing.requestFocus() })
                }
            }
        }
        compose.runOnIdle { existing.requestFocus() }
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.runOnIdle { position = 10_000 }
        compose.onNodeWithText("Existing control").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("skip:Intro").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(20_000, sought) }
        compose.runOnIdle { position = 20_000 }
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.onNodeWithText("Existing control").assertIsFocused()
    }

    @Test
    fun offHidesSkipAndAutomaticSeeksOnlyOnceForAnActiveSegment() {
        var mode by mutableStateOf(SegmentMode.Off)
        var position by mutableStateOf(12_000L)
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                SegmentControl(listOf(intro), position, mapOf(SegmentType.Intro to mode), onSeek = { seeks += it }, onFocusRemoved = {})
            }
        }
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList(), seeks)
            mode = SegmentMode.Automatic
        }
        compose.waitForIdle()
        compose.runOnIdle { position = 13_000 }
        compose.waitForIdle()
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(20_000L), seeks)
            mode = SegmentMode.Ask
        }
        compose.onNodeWithTag("skip:Intro").assertIsDisplayed()
    }

    @Test
    fun countdownIsFocusableCanBeCancelledAndNeverPlaysAfterCancelEvenAtEnd() {
        var ended by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var plays = 0
        val existing = FocusRequester()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column {
                    Button(onClick = {}, modifier = Modifier.focusRequester(existing)) { Text("Existing control") }
                    NextEpisodeControl(
                        account, MediaItem("next", "Next story"), true, ended, false, { existing.requestFocus() }, { plays++ },
                        countdownSeconds = 3, visible = visible
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Next story").assertIsDisplayed()
        compose.onNodeWithTag("next:cancel").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("next:cancel").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Existing control").assertIsFocused()
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { ended = true }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("next:cancel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test
    fun countdownPlaysOnceAndPausingStopsTheClock() {
        var paused by mutableStateOf(true)
        var plays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                NextEpisodeControl(account, MediaItem("next", "Next story"), true, false, paused, {}, { plays++ }, countdownSeconds = 2)
            }
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle {
            assertEquals(0, plays)
            paused = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(3_000)
        val tree = compose.onRoot().printToString()
        compose.runOnIdle { assertEquals(1, plays, tree) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test
    fun withoutCreditsTheCardShowsTimeLeftAndPlaysOnlyWhenTheEpisodeEnds() {
        var ended by mutableStateOf(false)
        var left by mutableStateOf(9_200L)
        var plays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                NextEpisodeControl(
                    account, MediaItem("next", "Next story"), true, ended, false, {}, { plays++ }, countdownSeconds = 2,
                    remainingMs = left
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Next episode · 10").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(20_000)
        compose.runOnIdle {
            assertEquals(0, plays)
            left = 2_500
        }
        compose.waitForIdle() // delivers the state change; the next frame recomposes
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Next episode · 3").assertIsDisplayed()
        compose.runOnIdle { ended = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, plays) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test
    fun chapterListFocusesCurrentChapterAndSelectionSeeksToItsStart() {
        val chapters = listOf(Chapter(0, "Intro"), Chapter(300_000_000, "Story"), Chapter(900_000_000, "Credits"))
        var sought = -1L
        compose.setContent { MaterialTheme { ChapterPanel(account, "item", chapters, 35_000) { sought = it } } }
        compose.onNodeWithTag("chapter:1").assertIsFocused()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.runOnIdle { assertEquals(90_000, sought) }
    }
}
