/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DetailAccessibilityEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playReceivesInputFocusWhenTheDetailIsReady() {
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val episode = MediaItem("episode", "An episode", "Episode", overview = "A long invented overview.")
        compose.setContent {
            PictureshowTheme { CatalogDetail(CatalogDetailState(account, episode, loading = false), {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test fun returningDetailWaitsForTheNavigationTransitionBeforeRestoringFocus() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val episode = MediaItem("episode", "An episode", "Episode", overview = "A long invented overview.")
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(CatalogDetailState(account, episode, loading = false), {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("detail:play").assertDoesNotExist()
        compose.onNodeWithTag("detail:play", useUnmergedTree = true).assertIsNotFocused()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test fun restoredTargetIsPublishedUnfocusedBeforeReceivingVisibleInputFocus() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val episode = MediaItem("episode", "An episode", "Episode", overview = "An invented overview.")
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(CatalogDetailState(account, episode, loading = false), {}, {}, {}, {}, {}) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        var firstVisibleFocused: Boolean? = null
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            val nodes = compose.onAllNodesWithTag("detail:play").fetchSemanticsNodes()
            if (firstVisibleFocused == null && nodes.isNotEmpty()) {
                firstVisibleFocused = nodes.single().config[SemanticsProperties.Focused]
            }
        }
        assertFalse(requireNotNull(firstVisibleFocused), "The first exposed snapshot must contain an unfocused Play target")
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test fun failedInputRestoreStillExposesThePageToAccessibility() {
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val episode = MediaItem("episode", "An episode", "Episode", overview = "An invented overview.")
        compose.setContent {
            PictureshowTheme {
                // A parent rejecting focus models a target that could not be restored. Accessibility
                // must recover even though the page's ordinary input restore reports failure.
                Box(Modifier.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()) {
                    CatalogDetail(CatalogDetailState(account, episode, loading = false), {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("detail:play").assertIsNotFocused()
    }
}
