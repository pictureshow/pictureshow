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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * A focused card shows one outline: its own scaled focus border. Native Canvas draws the real borders; a second
 * outline at the card's unscaled bounds would cross the badge at the top and the label at the bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CardFocusOutlineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val seasons = (1..3).map {
        MediaItem("season-$it", "Season $it", "Season", userData = ItemUserData(unplayedItemCount = 3))
    }
    private val outline = SelectedSeasonOutline.toArgb()
    private var focusBorder = 0

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            PictureshowTheme {
                focusBorder = androidx.tv.material3.MaterialTheme.colorScheme.border.toArgb()
                content()
            }
        }
    }

    private fun focus(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return node.assertIsFocused()
    }

    private fun close(pixel: Int, color: Int) = abs(Color.red(pixel) - Color.red(color)) < 35 &&
        abs(Color.green(pixel) - Color.green(color)) < 35 &&
        abs(Color.blue(pixel) - Color.blue(color)) < 35

    /** Pixels 1dp inside the card's unscaled layout bounds: the top edge through the badge, the bottom under the label. */
    private fun innerEdges(node: SemanticsNodeInteraction): List<Int> {
        val bounds = node.getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) }
        try {
            val left = bounds.left.value
            val right = bounds.right.value
            val top = bounds.top.value + 1f
            val bottom = bounds.bottom.value - 1f
            // Top edge near the right end crosses the "N unplayed" badge; bottom edge runs under the label.
            return listOf(
                (right - 12f) to top,
                ((left + right) / 2) to bottom,
                (left + 1f) to ((bounds.top.value + bounds.bottom.value) / 2),
                (right - 1f) to ((bounds.top.value + bounds.bottom.value) / 2)
            ).map { (x, y) -> bitmap.getPixel((x * density).roundToInt(), (y * density).roundToInt()) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun focusedSelectedSeasonDrawsOnlyItsFocusBorderAndRestingSelectionStaysVisible() {
        show {
            CatalogDetail(
                CatalogDetailState(
                    account,
                    MediaItem("series", "A Series", "Series"),
                    loading = false,
                    seasons = seasons,
                    selectedSeasonId = "season-2"
                ),
                {},
                {},
                {},
                {},
                {}
            )
        }
        val selected = focus("detail:seasons:season-2")
        innerEdges(selected).forEach {
            assertFalse(close(it, outline), "second outline inside the focused selected season: ${Integer.toHexString(it)}")
        }
        // Unfocused, the selected season keeps an outline of its own on all four sides.
        focus("detail:seasons:season-1")
        val bounds = compose.onNodeWithTag("detail:seasons:season-2").getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) }
        val midX = (bounds.left.value + bounds.right.value) / 2
        val midY = (bounds.top.value + bounds.bottom.value) / 2
        listOf(
            bounds.left.value + .5f to midY,
            bounds.right.value - .5f to midY,
            midX to bounds.top.value + .5f,
            midX to bounds.bottom.value - .5f
        ).forEach { (x, y) ->
            val pixel = bitmap.getPixel((x * density).toInt(), (y * density).toInt())
            assertTrue(close(pixel, outline), "selected season lost its resting outline at ($x, $y): ${Integer.toHexString(pixel)}")
        }
        bitmap.recycle()
    }

    @Test
    fun otherFocusedCardTypesDrawNoInnerOutline() {
        val movies = (0..2).map { MediaItem("film-$it", "A Film", "Movie", userData = ItemUserData(unplayedItemCount = 2)) }
        show {
            CatalogHome(
                CatalogHomeState(rows = listOf(HomeRow("latest", "Latest", movies)), loading = false),
                account,
                {},
                {},
                {},
                {}
            )
        }
        val card = focus("home:latest:film-1")
        innerEdges(card).forEach {
            assertFalse(close(it, outline) || close(it, focusBorder), "inner outline on a focused home card: ${Integer.toHexString(it)}")
        }
    }
}
