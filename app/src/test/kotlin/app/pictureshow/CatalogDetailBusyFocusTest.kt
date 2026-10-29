/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
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
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogDetailBusyFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theFocusedFavoriteSurvivesBothAcceptedPendingWrites() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        val calls = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value,
                    {},
                    {},
                    {
                        calls += "played"
                        state.value = state.value.copy(busy = true, item = item.copy(userData = item.userData.copy(played = true)))
                    },
                    {
                        calls += "favorite"
                        state.value = state.value.copy(busy = true)
                    },
                    {},
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithTag("detail:favorite-pending").assertIsFocused()
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf("played"), calls) }
        compose.onNodeWithTag("detail:favorite").assertIsEnabled().assertIsFocused()
        compose.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf("played", "favorite"), calls) }
        compose.onNodeWithTag("detail:favorite").assertIsEnabled().assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
    }

    @Test fun aFreshSelectionDuringTheFirstWriteWaitsInsteadOfDisappearing() = assertFreshBusySelection(false)

    @Test fun leavingDuringTheFirstWriteCancelsTheUnstartedFreshSelection() = assertFreshBusySelection(true)

    @Test fun aFailedAuthoritativeReadCannotTurnTheRetainedItemIntoMutationData() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val calls = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, { calls += "favorite" }, {}, onPendingAction = { true })
            }
        }
        compose.runOnIdle {
            state.value = state.value.copy(loading = false, playbackPending = false, error = "Authoritative read failed")
        }
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(emptyList(), calls) }
        compose.onNodeWithText("Reload the item before changing it.").assertExists()
        compose.runOnIdle { state.value = state.value.copy(loading = true, playbackPending = true, error = null) }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf("favorite"), calls) }
    }

    @Test fun aFailedWriteDoesNotMakeTheReturningControlsSilentlyRejectRetries() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        val calls = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value,
                    {},
                    {},
                    {
                        calls += "played"
                        state.value = state.value.copy(busy = true)
                    },
                    { calls += "favorite" },
                    {},
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:played").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("detail:favorite").assertIsEnabled()
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = "Could not update item") }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf("played", "favorite"), calls) }
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(listOf("played", "favorite", "favorite"), calls, "A ready mutation error must still permit a new retry")
        }
    }

    private fun assertFreshBusySelection(leave: Boolean) {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val visible = mutableStateOf(true)
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        val calls = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                if (visible.value) {
                    CatalogDetail(
                        state.value,
                        {},
                        {},
                        {
                            calls += "played"
                            state.value = state.value.copy(busy = true)
                        },
                        { calls += "favorite" },
                        {},
                        onPendingAction = { handoff.accessibleSelect("entry", it) },
                        onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                    )
                }
            }
        }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:played").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf("played"), calls) }
        compose.onNodeWithTag("detail:favorite").assertIsEnabled()
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Favorite change requested").assertExists()
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf("played"), calls, "The first write still owns the mutation slot") }
        compose.runOnIdle {
            if (leave) visible.value = false
            state.value = state.value.copy(busy = false)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(if (leave) listOf("played") else listOf("played", "favorite"), calls) }
    }
}
