/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.LibraryFilters
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
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogRevisionPresentationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Cinema", "https://media.example.test", "12"), User("v", "Viewer"), "fake")
    private val library = MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")

    @Test fun filteredEmptyTvFocusesClearAndCountsEachActiveFacet() {
        var cleared = false
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CatalogGrid(
                    CatalogGridState(
                        account,
                        library,
                        loading = false,
                        filters = LibraryFilters(genre = "Animation", unwatched = true, favorite = true)
                    ),
                    {},
                    {},
                    { _, _ -> },
                    {},
                    onFilters = {},
                    onClearFilters = { cleared = true }
                )
            }
        }
        compose.onNodeWithText("No movies match these filters").assertExists()
        compose.onNodeWithText("This library is empty.").assertDoesNotExist()
        compose.onNodeWithText("Filters (3)").assertExists()
        compose.onNodeWithTag("grid:clear-filters").assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchEmptyStateCanClearOrRemoveOneFilter() {
        var removed: String? = null
        var cleared = false
        compose.setContent {
            MaterialTheme {
                TouchCatalogGrid(
                    CatalogGridState(account, library, loading = false, filters = LibraryFilters(genre = "Animation", unwatched = true)),
                    {}, {}, { _, _ -> }, {}, onFilters = {}, onDisplayList = {}, onClearFilters = { cleared = true },
                    onRemoveFilter = { removed = it }, onBack = {}
                )
            }
        }
        compose.onNodeWithText("Filters (2)").assertExists()
        compose.onNodeWithText("Animation").performClick()
        compose.runOnIdle { assertEquals("genre", removed) }
        compose.onNodeWithText("No movies match these filters").assertExists()
        compose.onNodeWithTag("touch:clear-filters").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun phoneListUsesCompactRowsAndOpensTheExactItem() {
        val film = MediaItem("film", "A movie", "Movie", productionYear = 2026, officialRating = "PG", runTimeTicks = 600000000)
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                TouchCatalogGrid(
                    CatalogGridState(account, library, items = listOf(film), loading = false, list = true),
                    { opened = it.id },
                    {},
                    { _, _ -> },
                    {},
                    {}
                )
            }
        }
        val row = compose.onNodeWithTag("touch:card:film")
        val bounds = row.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 120.dp, "List rows remain compact")
        row.performClick()
        compose.runOnIdle { assertEquals("film", opened) }
    }
}
