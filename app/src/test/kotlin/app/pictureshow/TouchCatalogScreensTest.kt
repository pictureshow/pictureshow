/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaPerson
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.resumeLabel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h900dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchCatalogScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val movie = MediaItem("movie", "A Film", "Movie")

    @Test fun homeTransitionsFromLoadingToFailureToBrowsableContent() {
        var state by mutableStateOf(CatalogHomeState())
        var retries = 0
        var opened: MediaItem? = null
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, { opened = it }, { retries++ }) } }
        compose.onNodeWithText("Loading your library…").assertExists()
        compose.runOnIdle { state = CatalogHomeState(loading = false, error = "Connection unavailable") }
        compose.onNodeWithText("Connection unavailable").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state = CatalogHomeState(rows = listOf(HomeRow("latest", "Recently added", listOf(movie))), loading = false) }
        compose.onNodeWithTag("touch:card:movie").performClick()
        assertEquals(movie, opened)
        compose.onNodeWithText("Connection unavailable").assertDoesNotExist()
    }

    @Test fun librariesTabOnlyExposesLibrariesWhileHomeKeepsWatchingRows() {
        val library = MediaItem("library", "A Library", "CollectionFolder", isFolder = true)
        val state = CatalogHomeState(
            rows = listOf(
                HomeRow("resume", "Continue watching", listOf(movie)),
                HomeRow("libraries", "Libraries", listOf(library), library = true)
            ),
            loading = false
        )
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, {}, {}, librariesOnly = true) } }
        compose.onNodeWithTag("touch:card:library").assertExists()
        compose.onNodeWithTag("touch:card:movie").assertDoesNotExist()
    }

    @Test fun gridOffersAnEmptyStateAndReportsSortAndBackActions() {
        var sorted: Pair<String, Boolean>? = null
        var backs = 0
        compose.setContent {
            MaterialTheme {
                TouchCatalogGrid(CatalogGridState(account, MediaItem("library", "A Library")), {}, {}, { key, descending ->
                    sorted =
                        key to descending
                }, {}, { backs++ })
            }
        }
        compose.onNodeWithText("No titles are available.").assertExists()
        compose.onNodeWithText("Ascending").performClick()
        assertEquals("SortName" to true, sorted)
        compose.onNodeWithText("‹ Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun detailResumeAndRestartUseDifferentPlaybackOffsets() {
        val resumable = movie.copy(userData = ItemUserData(playbackPositionTicks = 300_000_000))
        var resume: Boolean? = null
        compose.setContent {
            MaterialTheme {
                TouchCatalogDetail(CatalogDetailState(account, resumable, loading = false), {}, {}, {}, {}, {}, { item, shouldResume ->
                    assertEquals(resumable, item)
                    resume = shouldResume
                }, {})
            }
        }
        compose.onNodeWithTag("touch:detail:primary").performScrollTo().performClick()
        assertEquals(true, resume)
        compose.onNodeWithText("Play from start").performScrollTo().performClick()
        assertEquals(false, resume)
    }

    @Test fun pendingPlaybackKeepsThePageAndWithholdsStaleResumeUntilFresh() {
        // The page used to be hidden here (a recorded gap against CAT-08); the controls now stay, without the stale resume.
        val resumable = movie.copy(userData = ItemUserData(playbackPositionTicks = 300_000_000))
        var state by mutableStateOf(CatalogDetailState(account, resumable, loading = false, playbackPending = true))
        compose.setContent { MaterialTheme { TouchCatalogDetail(state, {}, {}, {}, {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Saving progress…").assertExists()
        compose.onNodeWithTag("touch:detail:primary").assertIsEnabled().assertTextEquals("Play")
        compose.runOnIdle { state = state.copy(playbackPending = false) }
        compose.onNodeWithTag("touch:detail:primary").assertIsEnabled().assertTextEquals(resumeLabel(300_000_000))
    }

    @Test fun emptySeriesCannotPlayOrBeMarkedWatched() {
        val series = MediaItem("series", "A Series", "Series", childCount = 0)
        compose.setContent {
            MaterialTheme {
                val empty = CatalogDetailState(account, series, loading = false, readSucceeded = true)
                TouchCatalogDetail(empty, {}, {}, {}, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("touch:detail:primary").assertIsNotEnabled()
        compose.onNodeWithText("Mark watched").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("No episodes are available.").performScrollTo().assertExists()
    }

    @Test fun personScreenKeepsBiographyAndItsCatalogFailureReachable() {
        val person = MediaItem("person", "A Performer", "Person", overview = "An original biography.")
        var retries = 0
        compose.setContent {
            MaterialTheme {
                TouchPersonScreen(PersonState(CatalogGridState(account, person, error = "Titles unavailable"), person), {
                }, {}, { retries++ }, {})
            }
        }
        compose.onNodeWithText("An original biography.").assertExists()
        compose.onNodeWithText("Titles unavailable").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun searchKeyboardSubmitsAndRecentSearchCanBeReusedAndCleared() {
        var query = ""
        var submits = 0
        var recent: String? = null
        var clears = 0
        compose.setContent {
            MaterialTheme {
                TouchSearchScreen(SearchState(recent = listOf("A query")), account, {
                    query = it
                }, { submits++ }, { recent = it }, { clears++ }, {}, {})
            }
        }
        compose.onNodeWithTag("touch:search:query").performTextReplacement("film")
        assertEquals("film", query)
        compose.onNodeWithTag("touch:search:query").performImeAction()
        assertEquals(1, submits)
        compose.onNodeWithText("A query").performScrollTo().performClick()
        assertEquals("A query", recent)
        compose.onNodeWithText("Clear recent searches").performScrollTo().performClick()
        assertEquals(1, clears)
    }

    @Test fun searchDistinguishesEmptyResultsFromOfflineFailureAndAllowsRetry() {
        var state by mutableStateOf(SearchState(query = "film", completed = true))
        var retries = 0
        compose.setContent { MaterialTheme { TouchSearchScreen(state, account, {}, {}, {}, {}, { retries++ }, {}) } }
        compose.onNodeWithText("No results for “film”.").assertExists()
        compose.runOnIdle { state = state.copy(allFailed = true, someFailed = true) }
        compose.onNodeWithText("No results for “film”.").assertDoesNotExist()
        compose.onNodeWithText("Could not search the server. Check your connection and try again.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun castCreditOpensAPersonWithTheirPortraitInsteadOfAnActorItem() {
        val credit = MediaPerson("performer", "A Performer", role = "The Guide", type = "Actor", primaryImageTag = "portrait")
        var opened: MediaItem? = null
        compose.setContent {
            MaterialTheme {
                TouchCatalogDetail(CatalogDetailState(account, movie.copy(people = listOf(credit)), loading = false), {
                    opened = it
                }, {}, {}, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("touch:person:performer").performScrollTo().performClick()
        assertEquals("Person", opened?.type)
        assertEquals("performer", opened?.id)
        assertEquals("portrait", opened?.imageTags?.get("Primary"))
    }

    @Test fun playbackChoiceUsesNextUpAndNeverStartsWhileEpisodesAreLoading() {
        val first = MediaItem("first", "First episode", "Episode", userData = ItemUserData(played = true))
        val second = MediaItem("second", "Second episode", "Episode")
        val next = MediaItem("next", "Upcoming episode", "Episode")
        val series = MediaItem("series", "A Series", "Series")
        val state = CatalogDetailState(account, series, loading = false, nextUp = listOf(next), episodes = listOf(first, second))
        assertEquals(next, touchDetailPlayItem(state))
        assertEquals(second, touchDetailPlayItem(state.copy(nextUp = emptyList())))
        assertNull(touchDetailPlayItem(state.copy(loading = true)))
        assertNull(touchDetailPlayItem(state.copy(playbackPending = true)))
    }
}
