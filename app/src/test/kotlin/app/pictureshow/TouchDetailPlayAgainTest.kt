/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** PLAY-17 on the phone page: back from a film's natural end, Play reads "Play again", starts at 0, and From start goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h900dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchDetailPlayAgainTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie", userData = ItemUserData(playbackPositionTicks = 300_000_000))
    private val line = "Finished. You watched Invented film to the end."

    private fun label(target: String) = compose.onNodeWithTag("touch:detail:$target").fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }

    private fun page(
        finishedLine: String?
    ): Pair<androidx.compose.runtime.MutableState<CatalogDetailState>, MutableList<Pair<MediaItem, Boolean>>> {
        val plays = mutableListOf<Pair<MediaItem, Boolean>>()
        val state = mutableStateOf(
            CatalogDetailState(account, film, loading = true, playbackPending = true, finishedLine = finishedLine)
        )
        compose.setContent {
            MaterialTheme {
                TouchCatalogDetail(state.value, {}, {}, {}, {}, {}, { item, resume -> plays += item to resume }, {}, origin = film)
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        return state to plays
    }

    private fun texts(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun aFinishedFilmReturnsToPlayAgainFromTheStart() {
        val (state, plays) = page(line)
        assertEquals("Play again when ready", label("primary"))
        check(texts("Play from start when ready") == 0 && texts("Play from start") == 0) { "the wait offers Play from start" }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Play again", label("primary"))
        check(texts(line) == 1) { "the Finished line is missing" }
        check(texts("Play from start") == 0) { "Play from start is offered after a finish" }
        compose.onNodeWithTag("touch:detail:primary").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf(film to false), plays) }
    }

    @Test fun aReturnWithoutTheFinishedLineKeepsResumeAndPlayFromStart() {
        val (state, plays) = page(null)
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Resume from 0:30", label("primary"))
        check(texts("Play from start") > 0) { "Play from start is missing" }
        compose.onNodeWithTag("touch:detail:primary").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(listOf(film to true), plays) }
    }
}
