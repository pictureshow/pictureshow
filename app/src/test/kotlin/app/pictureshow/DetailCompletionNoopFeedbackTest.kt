/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
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
class DetailCompletionNoopFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aQueuedMarkWatchedThatAlreadyMatchesTheFreshItemExplainsNoChange() {
        assertExplainedNoChange(initiallyPlayed = false)
    }

    @Test fun aQueuedMarkUnwatchedThatAlreadyMatchesTheFreshItemExplainsNoChange() {
        assertExplainedNoChange(initiallyPlayed = true)
    }

    private fun assertExplainedNoChange(initiallyPlayed: Boolean) {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie", userData = ItemUserData(played = initiallyPlayed))
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        var writes = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, { writes++ }, {}, {}, origin = item,
                    onPendingQueueNeeded = { handoff.prepare("entry") },
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onPendingControlsReady = { handoff.live("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        compose.onNodeWithTag("detail:played-pending")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(0, writes) }
        compose.runOnIdle {
            state.value = state.value.copy(
                item = item.copy(userData = item.userData.copy(played = !initiallyPlayed)),
                loading = false,
                playbackPending = false,
                readSucceeded = true
            )
        }
        val value = if (initiallyPlayed) "unwatched" else "watched"
        compose.onNodeWithText("Nothing changed. This item is already $value.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { assertEquals(0, writes, "An already satisfied desired state must not issue a redundant write") }
    }
}
