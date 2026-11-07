/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
class DetailPendingPresentationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")

    // The held layout fixes each pill's size, and TV Material leaves content at the top start: "Play" sat in the corner.
    @Test fun heldPillsKeepTheirWordingInTheMiddle() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.waitForIdle()
        listOf("play", "played", "favorite", "from-start").forEach { tag ->
            val pill = compose.onNodeWithTag("detail:$tag-pending").getBoundsInRoot()
            val label = compose.onNodeWithTag("detail:$tag-pending-label", useUnmergedTree = true).getBoundsInRoot()
            assertEquals(((pill.left + pill.right) / 2).value, ((label.left + label.right) / 2).value, 1f, "$tag across")
            assertEquals(((pill.top + pill.bottom) / 2).value, ((label.top + label.bottom) / 2).value, 1f, "$tag down")
        }
    }

    @Test fun aColdReadOffersPlainPlayDuringArrivalWithoutRemovingTheQueue() {
        compose.mainClock.autoAdvance = false
        var queued = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = {
                        queued++
                        true
                    }
                )
            }
        }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play")
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, queued) }
    }

    @Test fun aReturnReadUsesTheSameQuietArrivalThenRevealsARealHold() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play")
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play when ready")
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
    }

    @Test fun aReadThatFinishesDuringTheGraceNeverPublishesWhenReady() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
        }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play")
        compose.runOnIdle { state.value = state.value.copy(loading = false, readSucceeded = true) }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:play-label", useUnmergedTree = true).assertTextEquals("Play")
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("detail:play-label", useUnmergedTree = true).assertTextEquals("Play")
    }

    @Test fun aHiddenTransitionCannotSpendTheReadersArrivalGrace() {
        compose.mainClock.autoAdvance = false
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    CatalogDetail(
                        CatalogDetailState(account, film, loading = true, playbackPending = true),
                        {},
                        {},
                        {},
                        {},
                        {},
                        origin = film,
                        onPendingAction = { true }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play")
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play when ready")
    }

    @Test fun pausingARealHoldRestartsItsPresentationGrace() {
        compose.mainClock.autoAdvance = false
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    CatalogDetail(
                        CatalogDetailState(account, film, loading = true, playbackPending = true),
                        {},
                        {},
                        {},
                        {},
                        {},
                        origin = film,
                        onPendingAction = { true }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_800)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play when ready")
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play")
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("detail:play-pending-label", useUnmergedTree = true).assertTextEquals("Play when ready")
    }
}
