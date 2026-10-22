/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Native Canvas exercises actual TV border drawing and the enclosing lazy container's clip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CardClippingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val movies = (0..9).map { MediaItem("film-$it", "A Film", "Movie") }
    private var border = 0

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            PictureshowTheme {
                border = MaterialTheme.colorScheme.border.toArgb()
                content()
            }
        }
    }

    private fun assertBorders(tag: String, vertical: Boolean = false) {
        val index = tag.substringAfterLast('-').toInt()
        val axisProperty = if (tag.startsWith(
                "grid:"
            )
        ) {
            SemanticsProperties.VerticalScrollAxisRange
        } else {
            SemanticsProperties.HorizontalScrollAxisRange
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(axisProperty)).performScrollToIndex(index)
        val node = compose.onNodeWithTag(tag)
        node.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        node.assertIsFocused()
        val bounds = node.getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) }
        // tv-material1.1.0: 1.1 focused scale and a centered 3dp stroke. Sample each full side's midpoint.
        val start = if (vertical) bounds.top.value else bounds.left.value
        val end = if (vertical) bounds.bottom.value else bounds.right.value
        val outset = (end - start) * .05f + 1.65f
        val middle = if (vertical) (bounds.left.value + bounds.right.value) / 2 else (bounds.top.value + bounds.bottom.value) / 2
        try {
            for ((side, edge) in listOf(start - outset, end + outset).withIndex()) {
                val axis = if (side == 0) ceil(edge * density + .5f).toInt() else floor(edge * density - 1.5f).toInt()
                val cross = (middle * density).roundToInt()
                val pixels = listOf(0).map {
                    val x = if (vertical) cross else axis + it
                    val y = if (vertical) axis + it else cross
                    if (x in 0..1919 && y in 0..1079) bitmap.getPixel(x, y) else Color.TRANSPARENT
                }
                assertTrue(
                    pixels.any {
                        abs(Color.red(it) - Color.red(border)) < 35 &&
                            abs(Color.green(it) - Color.green(border)) < 35 &&
                            abs(Color.blue(it) - Color.blue(border)) < 35
                    },
                    "$tag missing focused border on ${if (vertical) "vertical" else "horizontal"} axis=$axis: $pixels; expected=$border"
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun firstAndLastHomeShelfCardsDrawBothFocusedBorders() {
        show {
            CatalogHome(
                CatalogHomeState(rows = listOf(HomeRow("resume", "Continue watching", movies, wide = true)), loading = false),
                account,
                {},
                {},
                {},
                {}
            )
        }
        assertBorders("home:resume:film-0")
        assertBorders("home:resume:film-9")
    }

    @Test
    fun firstAndLastSearchCardsDrawBothFocusedBorders() {
        show {
            SearchScreen(
                SearchState(query = "film", rows = listOf(HomeRow("MOVIES", "Movies", movies)), completed = true),
                account, false, {}, {}, {}, {}, {}, {}
            )
        }
        assertBorders("search:MOVIES:film-0")
        assertBorders("search:MOVIES:film-9")
    }

    @Test
    fun firstAndLastSeasonCardsDrawBothFocusedBorders() {
        val seasons = movies.map { it.copy(type = "Season") }
        show {
            CatalogDetail(
                CatalogDetailState(
                    account,
                    MediaItem("series", "A Series", "Series"),
                    loading = false,
                    seasons = seasons,
                    selectedSeasonId = seasons.first().id
                ),
                {},
                {},
                {},
                {},
                {}
            )
        }
        assertBorders("detail:seasons:film-0")
        assertBorders("detail:seasons:film-9")
    }

    @Test
    fun firstAndLastEpisodeCardsDrawBothFocusedBorders() {
        val episodes = movies.map { it.copy(type = "Episode", seriesName = "A Series", indexNumber = 1, parentIndexNumber = 1) }
        show {
            CatalogDetail(
                CatalogDetailState(
                    account,
                    MediaItem("season", "A Season", "Season"),
                    loading = false,
                    episodes = episodes
                ),
                {},
                {},
                {},
                {},
                {}
            )
        }
        assertBorders("detail:episodes:film-0")
        assertBorders("detail:episodes:film-9")
    }

    @Test
    fun gridEdgeCardsDrawWholeFocusedBorder() {
        val items = (0..49).map { MediaItem("film-$it", "A long title that wraps onto two lines", "Movie") }
        show { CatalogGrid(CatalogGridState(account, MediaItem("library", "Cinema"), items, items.size), {}, {}, { _, _ -> }, {}) }
        for (id in listOf("film-0", "film-4", "film-45", "film-49")) {
            assertBorders("grid:$id", vertical = true)
            assertBorders("grid:$id")
        }
    }
}
