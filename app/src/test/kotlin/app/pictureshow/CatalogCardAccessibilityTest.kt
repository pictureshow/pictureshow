/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogCardAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://media.example.test", "12"), User("u", "Viewer"), "fake")

    @Test
    fun ordinarySeasonCardDoesNotAnnounceSelection() {
        compose.setContent {
            MaterialTheme {
                CatalogCard(MediaItem("season", "Season 1", "Season"), account, false, {}, Modifier.testTag("season"))
            }
        }
        compose.onNodeWithTag("season").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun seasonSelectorStillAnnouncesBothSelectedAndUnselectedChoices() {
        val seasons = listOf(MediaItem("s1", "Season 1", "Season"), MediaItem("s2", "Season 2", "Season"))
        compose.setContent {
            MaterialTheme {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        MediaItem("series", "Invented series", "Series"),
                        loading = false,
                        seasons = seasons,
                        selectedSeasonId = "s1"
                    ),
                    {},
                    {},
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithTag("detail:seasons:s1").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag("detail:seasons:s2").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun catalogRetryUsesItsTextAsTheOnlyAccessibleName() {
        compose.setContent {
            MaterialTheme {
                CatalogGrid(
                    CatalogGridState(account, MediaItem("library", "Library"), loading = false, error = "Unavailable"),
                    {},
                    {},
                    { _, _ -> },
                    {}
                )
            }
        }
        compose.onNodeWithText("Retry").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun nextPlayUsesItsDetailedNameWithoutRepeatingTheVisibleAction() {
        compose.setContent {
            MaterialTheme {
                NextEpisodeControl(account, MediaItem("next", "Next story", "Episode"), true, true, false, {}, {}, autoPlay = false)
            }
        }
        compose.onNodeWithTag("next:play").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }

    @Test
    fun retryWithoutAPageReloadReturnsToCardAndKeepsSortingAccessible() {
        var state by mutableStateOf(
            CatalogGridState(
                account,
                MediaItem("library", "Library"),
                items = listOf(MediaItem("card", "An invented series", "Series")),
                total = 1,
                loading = false,
                refreshError = "Unavailable"
            )
        )
        compose.setContent {
            MaterialTheme {
                CatalogGrid(state, {}, {}, { _, _ -> }, { state = state.copy(refreshError = null) })
            }
        }
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("grid:card").assertIsFocused()
        compose.onNodeWithTag("grid:sort-order").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
    }
}
