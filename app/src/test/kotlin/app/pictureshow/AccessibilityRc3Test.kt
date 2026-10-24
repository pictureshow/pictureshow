/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityRc3Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12"), User("u", "Viewer"), "fake")

    @Test fun positionCanTakeFocusAndOffersSeekEvenWithoutAKnownTotal() {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        Shadows.shadowOf(manager).setEnabled(true)
        compose.setContent { PictureshowTheme { PlayerTimeline(30_000, 30_000, 0) {} } }
        val position = compose.onNodeWithContentDescription("Playback position")
        position.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        position.assertIsFocused()
        assertEquals("Seek", position.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        position.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:30"))
    }

    @Test fun cardHasOneSpokenLabelInsteadOfTitlePlusDescription() {
        compose.setContent {
            PictureshowTheme {
                CatalogCard(MediaItem("m", "A Film", "Movie", productionYear = 2026), account, false, {}, Modifier.testTag("card"))
            }
        }
        val config = compose.onNodeWithTag("card").fetchSemanticsNode().config
        assertEquals(listOf("A Film, 2026"), config[SemanticsProperties.ContentDescription])
        assertEquals(null, config.getOrNull(SemanticsProperties.Text))
    }

    @Test fun loadingSeriesDoesNotAnnounceATemporarilyDisabledPlay() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, MediaItem("series", "A Series", "Series"), loading = true), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("detail:play").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test fun endedPlayButtonDoesNotClaimToBePlaying() {
        compose.setContent { PictureshowTheme { PlaybackToggle(false, true) {} } }
        compose.onNodeWithText("Play").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not playing"))
    }

    @Test fun manualNextAppearanceAnnouncesItsTargetWithoutAChangingCountdown() {
        compose.setContent {
            PictureshowTheme {
                NextEpisodeControl(account, MediaItem("e", "Next Story", "Episode"), true, false, false, {}, {}, autoPlay = false)
            }
        }
        compose.onNodeWithContentDescription(
            "Next episode, Next Story"
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun gridCardsPrecedeSortingAndRetainTheirInitialInputFocus() {
        compose.setContent {
            PictureshowTheme {
                CatalogGrid(
                    CatalogGridState(
                        account,
                        MediaItem("l", "Films"),
                        items = listOf(MediaItem("m", "A Film", "Movie")),
                        loading = false
                    ),
                    {
                    },
                    {},
                    { _, _ -> },
                    {}
                )
            }
        }
        compose.onNodeWithTag("grid:m").assertIsFocused()
        compose.onNodeWithTag("grid:items").assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 0f))
    }

    @Test fun libraryOffersSortingAfterItsInitialCardFocusIsReady() {
        var state by mutableStateOf(CatalogGridState(account, MediaItem("l", "Films"), loading = true))
        compose.setContent {
            PictureshowTheme { CatalogGrid(state, {}, {}, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("grid:sort:SortName").assertDoesNotExist()
        compose.onNodeWithTag("grid:sort:SortName", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            state = state.copy(items = listOf(MediaItem("m", "A Film", "Movie")), loading = false)
        }
        compose.onNodeWithTag("grid:m").assertIsFocused()
        compose.onNodeWithTag("grid").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Films"))
        compose.onNodeWithTag("grid:sort:SortName").assertExists()
    }

    @Test fun searchFieldStaysBeforeResultGroupsInBothTraversalDirections() {
        compose.setContent {
            PictureshowTheme { SearchScreen(SearchState(query = "sample", completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("search:field").assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
    }
}
