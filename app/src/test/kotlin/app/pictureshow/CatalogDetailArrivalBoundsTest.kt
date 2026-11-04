/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogDetailArrivalBoundsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aVisiblePrimaryDoesNotScrollTheHeaderToRevealAnUnfocusedSecondActionRow() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem(
            "film",
            "Invented film",
            "Movie",
            productionYear = 2026,
            overview = "A short invented description.",
            genres = listOf("Invented genre"),
            runTimeTicks = 600_000_000
        )
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }) }
            }
        }
        fun hero() = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val beforeFocus = hero()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        repeat(8) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnUiThread { state.value = state.value.copy(loading = false, playbackPending = false) }
        repeat(90) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("detail:play").assertIsFocused()
        assertEquals(beforeFocus, hero(), "No input: focusing an already-visible Play must not scroll the whole wrapped group")
        val primary = compose.onNodeWithTag("detail:play").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot
        assertTrue(primary.top >= viewport.top && primary.bottom <= viewport.bottom, "Primary really is visible")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("detail:from-start").assertIsFocused().assertIsDisplayed()
        val fromStart = compose.onNodeWithTag("detail:from-start").fetchSemanticsNode().boundsInRoot
        assertTrue(
            fromStart.top >= viewport.top && fromStart.bottom <= viewport.bottom,
            "Deliberate DOWN still reveals the focused lower row"
        )
        compose.runOnIdle { state.value = state.value.copy(error = "Could not save the requested change") }
        compose.onNodeWithTag("detail:from-start").assertIsFocused().assertIsDisplayed()
        val afterError = compose.onNodeWithTag("detail:from-start").fetchSemanticsNode().boundsInRoot
        assertTrue(
            afterError.top >= viewport.top && afterError.bottom <= viewport.bottom,
            "A later error must not strand the focused action offscreen"
        )
    }
}
