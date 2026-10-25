/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
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
class ActionRemovalFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun focusedSkipRemovalRestoresOnceAfterDetachment() = skipRemoval(moveAway = false)

    @Test fun removingSkipAfterTheViewerMovesAwayDoesNotStealFocus() = skipRemoval(moveAway = true)

    private fun skipRemoval(moveAway: Boolean) {
        var position by mutableStateOf(0L)
        var restores = 0
        val anchor = FocusRequester()
        compose.setContent {
            MaterialTheme {
                Column {
                    Button({}, Modifier.focusRequester(anchor)) { Text("Other control") }
                    SegmentControl(
                        listOf(MediaSegment("intro", SegmentType.Intro, 0, 200_000_000)),
                        position,
                        emptyMap(),
                        onSeek = { position = it },
                        onFocusRemoved = {
                            restores++
                            anchor.requestFocus()
                        }
                    )
                }
            }
        }
        compose.onNodeWithTag("skip:Intro").performSemanticsAction(SemanticsActions.RequestFocus) { check(it()) }
        compose.onNodeWithTag("skip:Intro").assertIsFocused()
        if (moveAway) compose.runOnIdle { anchor.requestFocus() }
        compose.runOnIdle { position = 20_000 }
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.onNodeWithText("Other control").assertIsFocused()
        compose.runOnIdle { assertEquals(if (moveAway) 0 else 1, restores) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(if (moveAway) 0 else 1, restores) }
    }

    @Test fun aFocusedNextCardRestoresOnceAndAnUnfocusedCardDoesNotStealFocus() {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        var eligible by mutableStateOf(true)
        var restores = 0
        val anchor = FocusRequester()
        compose.setContent {
            MaterialTheme {
                Column {
                    Button({}, Modifier.focusRequester(anchor)) { Text("Other control") }
                    NextEpisodeControl(
                        account, MediaItem("next", "Next story", "Episode"), eligible, false, true,
                        onFocusRemoved = {
                            restores++
                            anchor.requestFocus()
                        }, onPlay = {}, compact = true, autoPlay = false
                    )
                }
            }
        }
        compose.onNodeWithTag("next:play").performSemanticsAction(SemanticsActions.RequestFocus) { check(it()) }
        compose.onNodeWithTag("next:play").assertIsFocused()
        compose.runOnIdle { eligible = false }
        compose.onNodeWithTag("next:play").assertDoesNotExist()
        compose.onNodeWithText("Other control").assertIsFocused()
        compose.runOnIdle {
            assertEquals(1, restores)
            eligible = true
        }
        compose.onNodeWithTag("next:play").assertExists()
        compose.onNodeWithText("Other control").assertIsFocused()
        compose.runOnIdle { eligible = false }
        compose.onNodeWithTag("next:play").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, restores) }
    }

    @Test fun cancellingAFocusedCardRestoresOnceEvenWhenItsCallerRemovesTheWholeCard() {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        var cancelled by mutableStateOf(false)
        var restores = 0
        val anchor = FocusRequester()
        compose.setContent {
            MaterialTheme {
                Column {
                    Button({}, Modifier.focusRequester(anchor)) { Text("Other control") }
                    if (!cancelled) {
                        NextEpisodeControl(
                            account, MediaItem("next", "Next story", "Episode"), true, false, true,
                            onFocusRemoved = {
                                restores++
                                anchor.requestFocus()
                            }, onPlay = {}, onCancel = { cancelled = true },
                            compact = true, autoPlay = false
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("next:cancel").performSemanticsAction(SemanticsActions.RequestFocus) { check(it()) }
        compose.onNodeWithTag("next:cancel").assertIsFocused()
        compose.onNodeWithTag("next:cancel").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithTag("next:cancel").assertDoesNotExist()
        compose.onNodeWithText("Other control").assertIsFocused()
        compose.runOnIdle { assertEquals(1, restores) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, restores) }
    }
}
