/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

/** CAT-21 on the TV page: an episode the library doesn't have offers nothing that plays, in either action layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MissingEpisodeDetailTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val missing = MediaItem(
        "e1",
        "Invented missing episode",
        "Episode",
        seriesName = "Invented series",
        parentIndexNumber = 5,
        indexNumber = 1,
        locationType = "Virtual",
        premiereDate = "2014-10-05T00:00:00Z"
    )

    private fun assertNothingPlays() {
        check(compose.onAllNodesWithText("Not in your library", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("detail:played").assertExists()
        compose.onNodeWithTag("detail:from-start", useUnmergedTree = true).assertDoesNotExist()
        check(compose.onAllNodesWithText("Play from start", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "Play from start is offered"
        }
        compose.onNodeWithText("Download", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anOrdinaryMissingEpisodePageOffersNoPlayFromStart() {
        compose.setContent {
            PictureshowTheme { CatalogDetail(CatalogDetailState(account, missing, loading = false), {}, {}, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("detail:play").assertExists()
        check(compose.onAllNodesWithText("Play from start", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "Play from start is offered"
        }
    }

    // The projector reached this layout through a link (rc36): the returning layout reserves Play from start.
    @Test fun aReturningMissingEpisodePageOffersNoPlayFromStart() {
        val state = mutableStateOf(CatalogDetailState(account, missing, loading = true, playbackPending = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertNothingPlays()
    }
}
