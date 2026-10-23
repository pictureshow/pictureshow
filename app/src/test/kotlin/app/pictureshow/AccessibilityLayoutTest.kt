/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Viewer"), "fake")

    private fun large(content: @Composable () -> Unit) {
        compose.setContent {
            PictureshowTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f), content = content)
            }
        }
    }

    @Test
    fun largeDetailActionsWrapAndDpadCanReachTheSecondLineBeforeLeavingTheHeader() {
        large {
            Box(Modifier.width(360.dp).fillMaxHeight()) {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        MediaItem("movie", "A Film", "Movie", userData = ItemUserData(playbackPositionTicks = 72_000_000_000)),
                        loading = false
                    ),
                    {},
                    {},
                    {},
                    {},
                    {}
                )
            }
        }
        val favorite = compose.onNodeWithTag("detail:favorite")
        favorite.performScrollTo()
        val bounds = favorite.getUnclippedBoundsInRoot()
        assertTrue(bounds.right.value <= 360f, "Favorite extends past the viewport: $bounds")
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        val playTop = compose.onNodeWithTag("detail:play").getUnclippedBoundsInRoot().top.value
        val focusedTop = compose.onNode(isFocused()).getUnclippedBoundsInRoot().top.value
        assertTrue(focusedTop > playTop, "Down did not reach the wrapped action row")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        favorite.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        favorite.assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun largeLibrarySortControlsStayInsideTheViewport() {
        large { CatalogGrid(CatalogGridState(account, MediaItem("library", "Films")), {}, {}, { _, _ -> }, {}) }
        listOf("SortName", "DateCreated", "PremiereDate").map { "grid:sort:$it" }.plus("grid:sort-order").forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.right.value <= 920f, "$tag extends outside viewport: $bounds")
        }
    }

    @Test
    fun readOnlyTvFieldHasAnAccessibilityClickThatStartsEditing() {
        large { RemoteField("Server address", "https://media.example.test", {}, initial = true) }
        val field = compose.onNodeWithContentDescription("Server address")
        assertEquals("Edit with keyboard", field.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        field.performSemanticsAction(SemanticsActions.OnClick) { kotlin.test.assertTrue(it()) }
        field.assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
    }

    @Test
    fun searchFieldHasAnAccessibilityClickAndKeepsItsValue() {
        large { SearchScreen(SearchState(query = "story"), account, false, {}, {}, {}, {}, {}, {}) }
        val field = compose.onNodeWithTag("search:field")
        assertEquals("Edit with keyboard", field.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        field.performSemanticsAction(SemanticsActions.OnClick) { kotlin.test.assertTrue(it()) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
    }

    @Test
    fun enlargedCardTitleKeepsAllLinesInsteadOfEllipsizingNeededText() {
        val title = "A Long Film Title That Needs More Than Two Lines On A Poster Card"
        large { CatalogCard(MediaItem("movie", title, "Movie"), account, false, {}) }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val titles = compose.onAllNodes(androidx.compose.ui.test.hasText(title), useUnmergedTree = true)
        // The earlier match is the decorative artwork fallback; the last is the visible title below it.
        titles[titles.fetchSemanticsNodes().lastIndex].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 2, "The enlarged title fixture must exercise more than two lines")
        assertTrue(
            !layout.hasVisualOverflow && (0 until layout.lineCount).none(layout::isLineEllipsized),
            "Enlarged title was clipped or ellipsized"
        )
    }

    @Test
    fun lateEpisodeMetadataKeepsTheFocusedActionFullyInsideTheViewport() {
        val item = MediaItem("episode", "A Film", "Episode", seriesName = "A Series", productionYear = 2026)
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false))
        large { CatalogDetail(state.value, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle {
            state.value =
                state.value.copy(item = item.copy(overview = "A long description of an episode. ".repeat(9), genres = listOf("Animation")))
        }
        compose.waitForIdle()
        val play = compose.onNodeWithTag("detail:play").assertIsFocused()
        val bounds = play.getUnclippedBoundsInRoot()
        assertTrue(bounds.top.value >= 0f && bounds.bottom.value <= 540f, "Late metadata stranded the focused action: $bounds")
    }
}
