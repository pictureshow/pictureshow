/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
class DetailRecoveryAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val failure = "The server took too long to respond. Try again."

    @Test fun firstErrorPublishesRetryAndReasonInOneFocusedNode() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, film, loading = false, error = failure, readFailed = true), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. $failure"))
        // An independent live reason could occupy speech before the Retry label.
        compose.onNodeWithTag("detail:read-error")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test fun eachFailedRetryChangesTheSpokenStateEvenWhenTheReasonIsIdentical() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = failure, readFailed = true))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    state.value =
                        state.value.copy(loading = true, error = null, readFailed = false, readAttempt = state.value.readAttempt + 1)
                })
            }
        }
        val id = compose.onNodeWithTag("detail:retry").fetchSemanticsNode().id
        repeat(2) {
            compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
            compose.onNodeWithTag("detail:retry")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading item. Please wait."))
            compose.runOnIdle { state.value = state.value.copy(loading = false, error = failure, readFailed = true) }
            compose.onNodeWithTag("detail:retry").assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. $failure"))
            assertEquals(id, compose.onNodeWithTag("detail:retry").fetchSemanticsNode().id)
        }
    }

    @Test fun failedAndPendingDependentReadsDoNotClaimAnEmptySeason() {
        val season = MediaItem("season", "Invented season", "Season")
        val state = mutableStateOf(CatalogDetailState(account, season, loading = true))
        compose.setContent { PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("No episodes are available.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "Server unavailable (503)", readFailed = true) }
        compose.onNodeWithText("No episodes are available.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = null, readFailed = false, readSucceeded = true) }
        compose.onNodeWithText("No episodes are available.").assertExists()
    }

    @Test fun heldFreshActionIsSemanticallyDisabledButKeepsInputFocusAndThenSpeaksReady() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        var expired = false
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {
                        state.value = state.value.copy(loading = true, error = null, readFailed = false)
                    },
                    origin = film, onPendingAction = { false }, onPendingQueueExpired = { expired }
                )
            }
        }
        compose.runOnIdle {
            expired = true
            state.value = state.value.copy(loading = false, error = failure, readFailed = true)
        }
        compose.onNodeWithTag("detail:play", useUnmergedTree = true).assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, readSucceeded = true) }
        val heldNode = compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id
        compose.onNodeWithTag("detail:play-pending").assertIsFocused().assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        compose.onNodeWithTag("detail:play-pending")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Play")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        compose.onNodeWithText("Play unavailable", useUnmergedTree = true).assertDoesNotExist()
        for (tag in listOf("play", "played", "favorite", "from-start")) {
            compose.onNodeWithTag("detail:$tag-pending").assertIsNotEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
        compose.runOnIdle { state.value = state.value.copy(playbackPending = false, playbackReportFinished = true) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertEquals(heldNode, compose.onNodeWithTag("detail:play").fetchSemanticsNode().id)
    }

    @Test fun heldFreshPublicationKeepsEveryActionSlotInEveryComposition() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        var expired = false
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {
                        state.value = state.value.copy(loading = true, error = null, readFailed = false)
                    }, origin = film, onPendingAction = { false }, onPendingQueueExpired = { expired }
                )
            }
        }
        compose.runOnIdle {
            expired = true
            state.value = state.value.copy(loading = false, error = failure, readFailed = true)
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false, readSucceeded = true) }
        repeat(12) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            for (tag in listOf("play", "played", "favorite", "from-start")) {
                val slots = compose.onAllNodes(
                    hasTestTag("detail:$tag") or hasTestTag("detail:$tag-pending"),
                    useUnmergedTree = true
                ).fetchSemanticsNodes()
                assertEquals(1, slots.size, "Action $tag must retain its slot in fresh frame $frame")
            }
        }
        compose.mainClock.autoAdvance = true
    }
}
