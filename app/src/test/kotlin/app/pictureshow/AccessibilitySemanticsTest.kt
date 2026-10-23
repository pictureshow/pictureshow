/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilitySemanticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Screen room", "https://media.example.test", "12.0.0"), User("viewer", "Viewer"), "fake")

    @Test
    fun movieCardAnnouncesTitleYearResumeAndFavoriteAndOpensDetails() {
        val movie = MediaItem(
            "movie",
            "A Film",
            "Movie",
            productionYear = 2024,
            userData = ItemUserData(playbackPositionTicks = 900_000_000, isFavorite = true)
        )
        var opened = false
        compose.setContent { PictureshowTheme { CatalogCard(movie, account, false, { opened = true }, Modifier.testTag("card")) } }
        val card = compose.onNodeWithTag("card")
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        card.assertContentDescriptionEquals("A Film, 2024, Resume from 1:30, Favorite")
        assertEquals("Open details", card.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        card.performSemanticsAction(SemanticsActions.OnClick) { kotlin.test.assertTrue(it()) }
        assertEquals(true, opened)
    }

    @Test
    fun episodeCardAnnouncesEpisodeAndWatchedWithoutDecorativeImageText() {
        val episode = MediaItem(
            "episode",
            "An Episode",
            "Episode",
            seriesName = "A Series",
            parentIndexNumber = 2,
            indexNumber = 3,
            userData = ItemUserData(played = true)
        )
        compose.setContent { PictureshowTheme { CatalogCard(episode, account, true, {}, Modifier.testTag("card")) } }
        compose.onNodeWithTag("card").assertContentDescriptionEquals("An Episode, A Series, Season 2, Episode 3, Watched")
        assertEquals(
            listOf(androidx.compose.ui.text.AnnotatedString("An Episode")),
            compose.onNodeWithTag("card").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        )
    }

    @Test
    fun selectedSeasonHasSelectionAndUnplayedCount() {
        compose.setContent {
            PictureshowTheme {
                CatalogCard(
                    MediaItem("season", "Season 2", "Season", userData = ItemUserData(unplayedItemCount = 4)),
                    account,
                    false,
                    {},
                    Modifier.testTag("card"),
                    selected = true
                )
            }
        }
        compose.onNodeWithTag("card").assertIsSelected().assertContentDescriptionEquals("Season 2, 4 unplayed, selected season")
    }

    @Test
    fun sortChoiceExposesSelectionRatherThanOnlyCheckmark() {
        compose.setContent {
            PictureshowTheme {
                CatalogGrid(CatalogGridState(account, MediaItem("library", "Films"), loading = false), {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("grid:sort:SortName").assertIsSelected()
        compose.onNodeWithTag("grid:sort-order").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ascending"))
    }

    @Test
    fun detailActionsExposeWatchedAndFavoriteState() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        MediaItem("movie", "A Film", "Movie", userData = ItemUserData(played = true)),
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
        compose.onNodeWithTag("detail:played").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithTag(
            "detail:favorite"
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun passthroughAnnouncesStateAndChangesItThroughAccessibilityAction() {
        val state = mutableStateOf(PlaybackSettingsState(local = PlaybackPreferences(passthrough = false), loading = false))
        compose.setContent {
            PictureshowTheme {
                PlaybackSettingsScreen(state.value, "Stereo", { change ->
                    state.value = state.value.copy(local = change(state.value.local))
                }, { _, _ -> }, {}, onBack = {})
            }
        }
        val toggle = compose.onNodeWithTag("settings:passthrough")
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        toggle.performSemanticsAction(SemanticsActions.OnClick) { kotlin.test.assertTrue(it()) }
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun nextEpisodePlayNamesItsTargetWithoutReadingCountdownEverySecond() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme {
                NextEpisodeControl(
                    account,
                    MediaItem(
                        "next",
                        "Next Story",
                        "Episode",
                        parentIndexNumber = 1,
                        indexNumber = 2
                    ),
                    true,
                    false,
                    true,
                    {
                    },
                    {}
                )
            }
        }
        compose.onNodeWithTag("next:play").assertContentDescriptionEquals("Play next episode, Next Story, Season 1, Episode 2")
    }

    @Test
    fun currentChapterExposesSelectionAndItsActionSeeks() {
        var seek = -1L
        compose.setContent {
            PictureshowTheme {
                ChapterPanel(account, "movie", listOf(Chapter(name = "Opening"), Chapter(600_000_000, "A New Scene")), 65_000) { seek = it }
            }
        }
        compose.onNodeWithTag("chapter:1").assertIsSelected().performSemanticsAction(SemanticsActions.OnClick) {
            kotlin.test.assertTrue(it())
        }
        assertEquals(60_000, seek)
    }

    @Test
    fun subtitleDelayButtonsKeepTheirCurrentValueInTheirState() {
        compose.setContent { PictureshowTheme { SubtitleDelayControls(500, true, {}) } }
        compose.onNodeWithTag("delay:later").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "+0.5 s"))
    }

    @Test
    fun focusedWatchedBadgeKeepsLightTextAgainstItsDarkArtworkScrim() {
        compose.setContent {
            PictureshowTheme {
                CatalogCard(MediaItem("movie", "A Film", "Movie", userData = ItemUserData(played = true)), account, false, {
                }, Modifier.testTag("card"))
            }
        }
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(1000)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(
            androidx.compose.ui.test.hasText("✓ Watched"),
            useUnmergedTree = true
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        val foreground = layouts.single().layoutInput.style.color
        val background = androidx.compose.ui.graphics.Color(0xFF3E4350) // darkest badge over a white artwork pixel
        val ratio = (foreground.luminance() + .05f) / (background.luminance() + .05f)
        kotlin.test.assertTrue(ratio >= 4.5f, "badge contrast $ratio")
    }
}
