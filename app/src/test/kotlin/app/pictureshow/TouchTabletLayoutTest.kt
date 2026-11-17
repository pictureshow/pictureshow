/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import org.robolectric.annotation.LooperMode

/** Tablet widths: the detail page's actions stay compact and a short home row fills the width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchTabletLayoutTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val overview = "Warriors and scientists gather in a future city to restage a moment from the past."
    private val movie = MediaItem(
        "movie",
        "A Film",
        "Movie",
        productionYear = 2012,
        overview = overview,
        userData = ItemUserData(playbackPositionTicks = 300_000_000)
    )

    private fun showDetail() = compose.setContent {
        MaterialTheme {
            TouchCatalogDetail(
                CatalogDetailState(account, movie, loading = false),
                {},
                {},
                {},
                {},
                {},
                { _, _ -> },
                {},
                onDownload = { _, _, _ -> },
                onSubtitleDownload = {}
            )
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().top

    // A 7-inch tablet in landscape, beside the navigation rail: three secondary actions on one line, Download and Find
    // subtitles on the next, and the overview inside the first screen.
    @Config(qualifiers = "w880dp-h560dp-land-xhdpi")
    @Test
    fun aTabletDetailKeepsItsActionsCompactAndTheOverviewInView() {
        showDetail()
        assertEquals(top("touch:detail:played"), top("touch:detail:favorite"))
        assertEquals(top("touch:detail:played"), top("touch:detail:tv"))
        assertEquals(top("touch:detail:download"), top("touch:detail:subtitles"))
        val window = compose.onRoot().getUnclippedBoundsInRoot().bottom
        val text = compose.onNodeWithText(overview).getUnclippedBoundsInRoot()
        assertTrue(text.bottom <= window, "overview ends at ${text.bottom}, below the window's $window")
    }

    // A phone keeps one full-width action per line below the grid.
    @Config(qualifiers = "w400dp-h900dp-port-xhdpi")
    @Test
    fun aPhoneDetailStacksDownloadAboveFindSubtitles() {
        showDetail()
        assertTrue(top("touch:detail:subtitles") > top("touch:detail:download"))
        assertTrue(top("touch:detail:tv") > top("touch:detail:played"))
    }

    @Test fun aPhoneRowKeepsItsFixedCards() {
        assertEquals(244.dp, touchShelfCardWidth(360.dp, 244.dp, 3))
    }

    // The 10-inch listing shot: three Continue watching cards filled a third less than the row.
    @Test fun aShortTabletRowStretchesItsCardsAcrossTheWidth() {
        val width = touchShelfCardWidth(1200.dp, 244.dp, 3)
        val filled = width * 3 + 12.dp * 2
        assertTrue(filled >= (1200.dp - 40.dp) * 0.95f, "three cards fill $filled of 1160 dp")
        // Never past half again the card's size: one card doesn't become a banner.
        assertEquals(366.dp, touchShelfCardWidth(1200.dp, 244.dp, 1))
    }

    @Test fun aLongTabletRowShowsPartOfTheNextCard() {
        val width = touchShelfCardWidth(1200.dp, 244.dp, 20)
        assertTrue(width >= 244.dp, "$width")
        val usable = 1160.dp
        val whole = ((usable + 12.dp) / (width + 12.dp)).toInt()
        val left = usable - (width + 12.dp) * whole
        assertTrue(left > width * 0.1f && left < width, "the next card shows $left of $width")
    }
}
