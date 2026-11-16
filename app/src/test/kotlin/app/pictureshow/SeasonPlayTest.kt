/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** A season plays (rc36 projector finding 4): TV opens the season's page from its card; touch has a season Play. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SeasonPlayTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val series = MediaItem("series", "Invented series", "Series", childCount = 2)
    private val season1 = MediaItem("s1", "Season 1", "Season", seriesId = "series", indexNumber = 1, childCount = 1)
    private val season2 = MediaItem("s2", "Season 2", "Season", seriesId = "series", indexNumber = 2, childCount = 3)
    private fun episode(id: String, index: Int, data: ItemUserData = ItemUserData(), location: String? = null) = MediaItem(
        id,
        "Episode $index",
        "Episode",
        seriesId = "series",
        seriesName = "Invented series",
        parentIndexNumber = 2,
        indexNumber = index,
        userData = data,
        locationType = location
    )
    private val episodes = listOf(
        episode("e1", 1, ItemUserData(played = true)),
        episode("e2", 2, location = "Virtual"),
        episode("e3", 3, ItemUserData(playbackPositionTicks = 915_080_000)),
        episode("e4", 4)
    )

    @Test fun okOnATvSeasonCardOpensTheSeasonPageWhoseShelfStillFollowsFocus() {
        val opened = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        series,
                        loading = false,
                        seasons = listOf(season1, season2),
                        selectedSeasonId = "s1",
                        episodes = listOf(episode("a", 1)),
                        readSucceeded = true
                    ),
                    { opened += it.id },
                    { chosen += it.id },
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithTag("detail:seasons:s2").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("s2"), opened) }
    }

    @Test fun theTvSeasonPageResumesItsFirstUnfinishedEpisodeAndSkipsMissingOnes() {
        val played = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        season2,
                        loading = false,
                        selectedSeasonId = "s2",
                        episodes = episodes,
                        readSucceeded = true
                    ),
                    {},
                    {},
                    {},
                    {},
                    {},
                    onPlay = { item, resume -> played += item.id to resume }
                )
            }
        }
        compose.onNodeWithText("Resume S2:E3 · Episode 3").assertExists()
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("e3" to true), played) }
    }

    @Config(qualifiers = "w600dp-h3000dp-port-xhdpi")
    @Test
    fun touchOffersPlayForTheChosenSeasonOnceItsEpisodesLoad() {
        val state = CatalogDetailState(
            account,
            series,
            loading = false,
            seasons = listOf(season1, season2),
            selectedSeasonId = "s2",
            episodes = episodes,
            readSucceeded = true
        )
        val (season, episode) = requireNotNull(touchSeasonPlay(state))
        assertEquals("s2" to "e3", season.id to episode.id)
        assertNull(touchSeasonPlay(state.copy(loading = true)))
        assertNull(touchSeasonPlay(state.copy(episodes = listOf(episode("gone", 1, location = "Virtual")))))
        assertNull(touchSeasonPlay(state.copy(item = season2)))

        val played = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                TouchCatalogDetail(state, {}, {}, {}, {}, {}, { item, resume -> played += item.id to resume }, {})
            }
        }
        compose.onNodeWithTag("touch:detail:season-play").performClick()
        compose.runOnIdle { assertEquals(listOf("e3" to true), played) }
    }
}
