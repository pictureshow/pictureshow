/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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

/**
 * PLAY-17 on the TV page: back from a film's natural end, Play reads "Play again", holds focus, starts at the beginning,
 * and Play from start isn't offered. Without the Finished line the same return keeps Resume and Play from start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DetailPlayAgainTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")

    // A leftover position stands in for anything that would otherwise make Play resume.
    private val film = MediaItem("film", "Invented film", "Movie", userData = ItemUserData(playbackPositionTicks = 300_000_000))
    private val line = "Finished. You watched Invented film to the end."

    private fun texts(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun returnTo(finishedLine: String?): MutableList<Pair<MediaItem, Boolean>> {
        val plays = mutableListOf<Pair<MediaItem, Boolean>>()
        val state = mutableStateOf(
            CatalogDetailState(account, film, loading = true, playbackPending = true, finishedLine = finishedLine)
        )
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {}, onPlay = { item, resume -> plays += item to resume })
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        if (finishedLine != null) {
            check(texts("Play from start when ready") == 0 && texts("Play from start") == 0) { "the wait offers Play from start" }
        }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        return plays
    }

    @Test fun aFinishedFilmReturnsToPlayAgainFromTheStart() {
        val plays = returnTo(line)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        check(texts(line) == 1) { "the Finished line is missing" }
        check(texts("Play again") > 0) { "Play doesn't read Play again" }
        check(texts("Resume from 0:30") == 0) { "Play still resumes" }
        check(texts("Play from start") == 0) { "Play from start is offered after a finish" }
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(film to false), plays) }
    }

    @Test fun aReturnWithoutTheFinishedLineKeepsResumeAndPlayFromStart() {
        val plays = returnTo(null)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        check(texts("Play again") == 0) { "Play again without a finish" }
        check(texts("Play from start") > 0) { "Play from start is missing" }
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(film to true), plays) }
    }
}
