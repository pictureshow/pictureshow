/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun personChipNamesItsPersonAndRemovalTarget() {
        compose.setContent {
            MaterialTheme {
                TouchCatalogGrid(
                    CatalogGridState(
                        account,
                        library,
                        loading = false,
                        filters = LibraryFilters(personId = "person", personName = "Alice Marcolino")
                    ),
                    {}, {}, { _, _ -> }, {}, onFilters = {}, onDisplayList = {}, onRemoveFilter = {}, onBack = {}
                )
            }
        }
        compose.onNodeWithText("Alice Marcolino").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Alice Marcolino").assertExists()
    }

    @Test fun tvHeaderUsesEqualChipGeometryAndNamesItsViewState() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CatalogGrid(
                    CatalogGridState(account, library, loading = false, list = true, filters = LibraryFilters(unwatched = true)),
                    {},
                    {},
                    { _, _ -> },
                    {},
                    onFilters = {},
                    onDisplayList = {}
                )
            }
        }
        val sort = compose.onNodeWithTag("grid:sort:SortName").getUnclippedBoundsInRoot()
        for (tag in listOf("grid:filters", "grid:display")) {
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertEquals(sort.bottom - sort.top, bounds.bottom - bounds.top, tag)
        }
        compose.onNodeWithText("View: List").assertExists()
        for (tag in listOf("grid:sort:SortName", "grid:display")) {
            compose.onNodeWithTag(tag).assert(
                SemanticsMatcher("Actions expose neither a selection nor a toggle state") {
                    it.config.getOrNull(SemanticsProperties.ToggleableState) == null &&
                        it.config.getOrNull(SemanticsProperties.Selected) == null
                }
            )
        }
        compose.onNodeWithTag("grid:filters").assertIsSelected()
    }

    @Test fun tvSectionAndNativeRowLabelShareAnInset() = sectionInset(tv = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchSectionAndNativeRowLabelShareAnInset() = sectionInset(tv = false)

    private fun sectionInset(tv: Boolean) {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    CatalogSection("Section", first = true)
                    if (tv) {
                        androidx.tv.material3.MaterialTheme { TvSettingsRow("Label", null, selected = false, onClick = {}) }
                    } else {
                        androidx.compose.material3.ListItem(headlineContent = { androidx.compose.material3.Text("Label") })
                    }
                }
            }
        }
        val heading = compose.onNodeWithText("Section", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("Label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(heading.left, label.left)
    }

    @Test fun tvListRowsKeepPosterBoundedAndMoreThanOneTitleVisible() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CatalogGrid(
                    CatalogGridState(
                        account,
                        library,
                        items = listOf(MediaItem("one", "First movie", "Movie"), MediaItem("two", "Second movie", "Movie")),
                        loading = false,
                        list = true
                    ),
                    {
                    },
                    {},
                    { _, _ -> },
                    {}
                )
            }
        }
        val row = compose.onNodeWithTag("grid:one")
        val bounds = row.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 130.dp, "TV List must bound the poster within a compact row")
        compose.onNodeWithTag("grid:two").assertIsDisplayed()
    }

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
        compose.onNodeWithTag("grid:filters").assertIsSelected()
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

    @Test fun tvHomeEditorWaitsForInitialViewerPreferences() = homeEditorWaitsForPreferences(tv = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchLibraryAppBarUsesWindowInsetsIndependentlyOfGridContentPadding() {
        compose.setContent {
            MaterialTheme {
                TouchCatalogGrid(
                    state = CatalogGridState(account, library, loading = false, items = listOf(MediaItem("one", "First movie", "Movie"))),
                    onOpen = {},
                    onNearEnd = {},
                    onSort = { _, _ -> },
                    onRetry = {},
                    onFilters = {},
                    onDisplayList = {},
                    onBack = {}
                )
            }
        }
        val bar = compose.onNodeWithTag("touch:library-appbar").getUnclippedBoundsInRoot()
        assertEquals(0.dp, bar.left, "Native app bars share the window edge, outside the grid gutter")
        assertEquals(0.dp, bar.top, "The grid top padding must not inset the app bar")
        assertEquals(411.dp, bar.right - bar.left)
        assertTrue(compose.onNodeWithTag("touch:back").getUnclippedBoundsInRoot().left < 12.dp)
        compose.onNodeWithTag("touch:sort").assertIsDisplayed()
        compose.onNodeWithTag("touch:filters").assertIsDisplayed()
        compose.onNodeWithTag("touch:card:one").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchHomeEditorWaitsForInitialViewerPreferences() = homeEditorWaitsForPreferences(tv = false)

    private fun homeEditorWaitsForPreferences(tv: Boolean) {
        val state = mutableStateOf(CatalogHomeState())
        var opens = 0
        compose.setContent {
            MaterialTheme {
                androidx.tv.material3.MaterialTheme {
                    if (tv) {
                        CatalogHome(state.value, account, {}, {}, {}, {}, onCustomize = { opens++ })
                    } else {
                        TouchCatalogHome(state.value, account, {}, {}, onCustomize = { opens++ })
                    }
                }
            }
        }
        val entry = compose.onNodeWithTag(if (tv) "home:customize" else "touch:home-menu")
        entry.assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(0, opens, "Pending read must not open an editor populated with defaults")
            state.value = state.value.copy(preferencesReady = true, loading = false)
        }
        entry.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        if (!tv) compose.onNodeWithTag("touch:customize").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
