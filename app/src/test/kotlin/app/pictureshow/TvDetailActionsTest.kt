/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TrailerChoice
import app.pictureshow.core.User
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * CAT-05/06 TV detail, series and season action hierarchy: one prominent Play/Resume pill, Play from start beside it
 * only when there is progress, then a compact single row of round icon buttons with captions (Favorite, Download,
 * Find subtitles, Trailer, Mark watched — never destructive next to Play), and Series/Season sharing the same layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvDetailActionsTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Demo", "https://media.example.test", "12.0.0"), User("u", "Viewer"), "fake")
    private val trailerEntry = DiscoveryEntry(
        card = MediaItem("tr1", "Official Trailer", "Trailer"),
        subtitle = "YouTube",
        trailer = TrailerChoice(key = "tr1", title = "Official Trailer", url = "https://youtu.be/dQw4w9WgXcQ", youTubeId = "dQw4w9WgXcQ")
    )
    private val trailerRow = DiscoveryRow("trailers", "Trailer", true, listOf(trailerEntry))

    private fun key(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertSingleRowInOrder(tags: List<String>) {
        val rects = tags.map { it to bounds(it) }
        val firstTop = rects.first().second.top
        rects.forEach { (tag, rect) ->
            assertTrue(abs((rect.top - firstTop).value) <= 6f, "$tag wrapped onto another row: $rect vs firstTop=$firstTop")
            assertTrue(rect.right <= 916.dp, "$tag extends past TV safe margin: $rect")
        }
        rects.zipWithNext().forEach { (left, right) ->
            assertTrue(left.second.right <= right.second.left + 1.dp, "${left.first} must sit left of ${right.first}: $left / $right")
        }
    }

    @Test
    fun movieWithoutProgressHidesPlayFromStartAndRendersCompactRoundIconRow() {
        val movie = MediaItem(
            "movie",
            "Invented Film",
            "Movie",
            productionYear = 2024,
            overview = "A short invented overview.",
            genres = listOf("Drama", "Sci-Fi"),
            canDownload = true
        )
        val state = mutableStateOf(CatalogDetailState(account, movie, loading = true))
        var favorites = 0
        var downloads = 0
        var subtitles = 0
        var trailers = 0
        var watched = 0
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value,
                    onOpen = {},
                    onSeason = {},
                    onPlayed = { watched++ },
                    onFavorite = { favorites++ },
                    onRetry = {},
                    onPendingAction = { true },
                    origin = movie,
                    onDownload = { _, _, _ -> downloads++ },
                    discovery = listOf(trailerRow),
                    onDiscovery = { trailers++ },
                    onSubtitleDownload = { subtitles++ }
                )
            }
        }
        // Cold open completes with no progress: Play from start must NOT appear.
        compose.runOnIdle { state.value = state.value.copy(loading = false, readSucceeded = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("detail:play").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("detail:from-start", useUnmergedTree = true).assertDoesNotExist()
        val expected = listOf("detail:play", "detail:favorite", "detail:download", "detail:subtitles", "detail:trailer", "detail:played")
        assertSingleRowInOrder(expected)
        listOf("detail:favorite", "detail:download", "detail:subtitles", "detail:trailer", "detail:played").forEach { tag ->
            val width = bounds(tag).right - bounds(tag).left
            assertTrue(width <= 84.dp, "$tag should be a compact round icon slot, was $width")
        }
        // Every rc42 action plus Trailer is reachable by D-pad Right in order, with Mark watched last.
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
        key(Key.DirectionCenter)
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:download").assertIsFocused()
        key(Key.DirectionCenter)
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:subtitles").assertIsFocused()
        key(Key.DirectionCenter)
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:trailer").assertIsFocused()
        key(Key.DirectionCenter)
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:played").assertIsFocused()
        key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(1, favorites)
            assertEquals(1, downloads)
            assertEquals(1, subtitles)
            assertEquals(1, trailers)
            assertEquals(1, watched)
        }
    }

    @Test
    fun movieWithProgressPlacesPlayFromStartBesideResumeOnOneRow() {
        val movie = MediaItem(
            "movie",
            "Invented Film",
            "Movie",
            productionYear = 2024,
            overview = "A short invented overview.",
            canDownload = true,
            userData = ItemUserData(playbackPositionTicks = 600_000_000L)
        )
        val plays = mutableListOf<Pair<MediaItem, Boolean>>()
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, movie, loading = false, readSucceeded = true),
                    onOpen = {},
                    onSeason = {},
                    onPlayed = {},
                    onFavorite = {},
                    onRetry = {},
                    onPlay = { item, resume -> plays += item to resume },
                    onDownload = { _, _, _ -> },
                    discovery = listOf(trailerRow),
                    onSubtitleDownload = {}
                )
            }
        }
        compose.waitForIdle()
        val expected = listOf(
            "detail:play",
            "detail:from-start",
            "detail:favorite",
            "detail:download",
            "detail:subtitles",
            "detail:trailer",
            "detail:played"
        )
        assertSingleRowInOrder(expected)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:from-start").assertIsFocused()
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf(movie to false), plays) }
    }

    @Test
    fun seriesAdoptsOverviewAboveCompactActionsAndOmitsFromStartWithoutProgress() {
        val ep = MediaItem("ep1", "Pilot", "Episode", parentIndexNumber = 1, indexNumber = 1)
        val season = MediaItem("s1", "Season 1", "Season", seriesId = "ser")
        val series = MediaItem(
            "ser",
            "Invented Series",
            "Series",
            productionYear = 2023,
            overview = "An anthology of invented stories.",
            genres = listOf("Sci-Fi", "Thriller")
        )
        var trailers = 0
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        series,
                        loading = false,
                        readSucceeded = true,
                        nextUp = listOf(ep),
                        seasons = listOf(season),
                        selectedSeasonId = season.id,
                        episodes = listOf(ep)
                    ),
                    onOpen = {},
                    onSeason = {},
                    onPlayed = {},
                    onFavorite = {},
                    onRetry = {},
                    onDownload = { _, _, _ -> },
                    discovery = listOf(trailerRow),
                    onDiscovery = { trailers++ },
                    onSubtitleDownload = {}
                )
            }
        }
        compose.waitForIdle()
        val overview = compose.onNodeWithText("An anthology of invented stories.").getUnclippedBoundsInRoot()
        val play = bounds("detail:play")
        assertTrue(overview.bottom <= play.top, "Series overview must sit above actions like Movie detail: $overview / $play")
        compose.onNodeWithTag("detail:from-start", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("detail:subtitles", useUnmergedTree = true).assertDoesNotExist()
        assertSingleRowInOrder(listOf("detail:play", "detail:favorite", "detail:download", "detail:trailer", "detail:played"))
        repeat(3) { key(Key.DirectionRight) }
        compose.onNodeWithTag("detail:trailer").assertIsFocused()
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(1, trailers) }
    }

    @Test
    fun seriesWithProgressPlacesPlayFromStartBesideResume() {
        val ep = MediaItem(
            "ep2",
            "Second Chapter",
            "Episode",
            parentIndexNumber = 1,
            indexNumber = 2,
            userData = ItemUserData(playbackPositionTicks = 900_000_000L)
        )
        val series = MediaItem("ser", "Invented Series", "Series", overview = "Overview text.")
        val plays = mutableListOf<Pair<MediaItem, Boolean>>()
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, series, loading = false, readSucceeded = true, nextUp = listOf(ep)),
                    onOpen = {},
                    onSeason = {},
                    onPlayed = {},
                    onFavorite = {},
                    onRetry = {},
                    onPlay = { item, resume -> plays += item to resume },
                    onDownload = { _, _, _ -> }
                )
            }
        }
        compose.waitForIdle()
        assertSingleRowInOrder(listOf("detail:play", "detail:from-start", "detail:favorite", "detail:download", "detail:played"))
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:from-start").assertIsFocused()
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf(ep to false), plays) }
    }

    @Test
    fun seasonAdoptsOverviewAboveCompactActionsAndKeepsSeasonNameVisible() {
        val ep = MediaItem("ep1", "First Episode", "Episode", parentIndexNumber = 3, indexNumber = 1)
        val season = MediaItem(
            "s3",
            "Season 3",
            "Season",
            seriesId = "ser",
            parentLogoItemId = "ser",
            parentLogoImageTag = "logo-tag",
            overview = "Six new stories in the third season."
        )
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, season, loading = false, readSucceeded = true, episodes = listOf(ep)),
                    onOpen = {},
                    onSeason = {},
                    onPlayed = {},
                    onFavorite = {},
                    onRetry = {},
                    onDownload = { _, _, _ -> }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("detail:title").assertIsDisplayed()
        val overview = compose.onNodeWithText("Six new stories in the third season.").getUnclippedBoundsInRoot()
        val play = bounds("detail:play")
        assertTrue(overview.bottom <= play.top, "Season overview must sit above actions like Movie detail: $overview / $play")
        compose.onNodeWithTag("detail:from-start", useUnmergedTree = true).assertDoesNotExist()
        assertSingleRowInOrder(listOf("detail:play", "detail:favorite", "detail:download", "detail:played"))
    }

    @Test
    fun seasonWithProgressPlacesPlayFromStartBesideResume() {
        val ep = MediaItem(
            "ep1",
            "First Episode",
            "Episode",
            parentIndexNumber = 3,
            indexNumber = 1,
            userData = ItemUserData(playbackPositionTicks = 450_000_000L)
        )
        val season = MediaItem("s3", "Season 3", "Season", seriesId = "ser", overview = "Season overview.")
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, season, loading = false, readSucceeded = true, episodes = listOf(ep)),
                    onOpen = {},
                    onSeason = {},
                    onPlayed = {},
                    onFavorite = {},
                    onRetry = {},
                    onDownload = { _, _, _ -> }
                )
            }
        }
        compose.waitForIdle()
        assertSingleRowInOrder(listOf("detail:play", "detail:from-start", "detail:favorite", "detail:download", "detail:played"))
    }
}
