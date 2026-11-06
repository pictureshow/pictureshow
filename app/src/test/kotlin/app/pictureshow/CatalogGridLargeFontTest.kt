/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Kills the test JVM if one test outlasts [seconds]: a scroll or focus loop in a mutant would otherwise spin in
 * waitForIdle and hold the build. Compose's rule needs the test thread, so JUnit's thread-based Timeout can't do this.
 */
class HangGuard(private val seconds: Long) : TestWatcher() {
    private var guard: Thread? = null

    override fun starting(description: Description) {
        guard = Thread {
            try {
                Thread.sleep(seconds * 1_000)
                System.err.println("HangGuard: ${description.displayName} ran over $seconds s, stopping the JVM")
                Runtime.getRuntime().halt(3)
            } catch (_: InterruptedException) {
                // The test finished in time.
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun finished(description: Description) {
        guard?.interrupt()
    }
}

/** The library grid on a 960x540 dp TV: column kept, focused card whole, at each size the TV's Settings offer and above. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogGridLargeFontTest {
    @get:Rule(order = 0)
    val hangGuard = HangGuard(180)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")

    // Every third title wraps to several lines, as the demo library's "Copied Audio Clock 1440p" does.
    private val films = (0 until 35).map {
        MediaItem(
            "film-$it",
            if (it % 3 ==
                0
            ) {
                "A long title that wraps over several lines"
            } else {
                "Film"
            },
            "Movie"
        )
    }
    private var fontScale by mutableFloatStateOf(1f)

    private fun show(items: List<MediaItem> = films, scale: Float = 1f) {
        fontScale = scale
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PictureshowTheme {
                    CatalogGrid(CatalogGridState(account, MediaItem("library", "Films"), items, items.size), {}, {}, { _, _ -> }, {})
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun focusedTag(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single().config[SemanticsProperties.TestTag]

    private fun assertFocus(tag: String) = assertEquals(tag, focusedTag())

    /** The card's scaled edges and border lie on screen. Layout bounds exclude tv-material's 1.1x, so add its 5%. */
    private fun assertFocusedCardWhole(context: String, clippedBy: String? = null) {
        val tag = focusedTag()
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val root = (clippedBy?.let(compose::onNodeWithTag) ?: compose.onRoot()).getBoundsInRoot()
        val overhang = (bounds.bottom - bounds.top) * .05f
        assertTrue(bounds.top - overhang >= root.top, "$context: $tag cut at the top: $bounds in $root")
        assertTrue(bounds.bottom + overhang <= root.bottom, "$context: $tag cut at the bottom: $bounds in $root")
    }

    private fun walkTheGrid(scale: Float) {
        show(scale = scale)
        assertFocus("grid:film-0")
        assertFocusedCardWhole("first card")
        repeat(3) { press(Key.DirectionRight) }
        assertFocus("grid:film-3")
        for (film in listOf(8, 13, 18, 23, 28, 33)) {
            press(Key.DirectionDown)
            assertFocus("grid:film-$film")
            assertFocusedCardWhole("down to $film")
        }
        for (film in listOf(28, 23, 18, 13, 8, 3)) {
            press(Key.DirectionUp)
            assertFocus("grid:film-$film")
            assertFocusedCardWhole("up to $film")
        }
        // The first row sits under a whole header.
        assertEquals(
            compose.onNodeWithTag("grid:count").getUnclippedBoundsInRoot().top.value > 0f,
            true,
            "Header scrolled away on the first row"
        )
        press(Key.DirectionUp)
        assertTrue(focusedTag().startsWith("grid:sort"), "Up from the first row reaches the sort controls, got ${focusedTag()}")
        press(Key.DirectionDown)
        assertFocus("grid:film-3")
    }

    @Test fun defaultFontKeepsTheColumnAndTheCardWhole() = walkTheGrid(1f)

    @Test fun largeFontKeepsTheColumnAndTheCardWhole() = walkTheGrid(1.15f)

    @Test fun largestFontKeepsTheColumnAndTheCardWhole() = walkTheGrid(1.3f)

    @Test fun beyondTheLargestSettingStillKeepsTheColumnAndTheCardWhole() = walkTheGrid(1.6f)

    @Test fun downToAShorterLastRowLandsOnItsLastCardAndDownThereStays() {
        show(films.take(7))
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        assertFocus("grid:film-6")
        press(Key.DirectionDown)
        assertFocus("grid:film-6")
    }

    @Test fun inPlaceFontChangeKeepsTheFocusedCardWhole() {
        show()
        repeat(3) { press(Key.DirectionRight) }
        repeat(2) { press(Key.DirectionDown) }
        assertFocus("grid:film-13")
        fontScale = 1.3f
        settle()
        settle()
        assertFocus("grid:film-13")
        assertFocusedCardWhole("in place to 1.3")
        press(Key.DirectionDown)
        assertFocus("grid:film-18")
        assertFocusedCardWhole("moved at 1.3")
        fontScale = 1f
        settle()
        settle()
        assertFocus("grid:film-18")
        assertFocusedCardWhole("in place back to 1.0")
    }

    @Test fun aCardTallerThanTheScreenStillReachesTheSortControlsAndComesBack() {
        val title = "A title long enough to outgrow the whole screen ".repeat(12)
        show(listOf(MediaItem("film-0", title, "Movie")) + films.drop(1), scale = 1.6f)
        assertFocus("grid:film-0")
        val card = compose.onNodeWithTag("grid:film-0").getUnclippedBoundsInRoot()
        assertTrue(card.bottom - card.top > 540.dp, "The fixture must be taller than the screen: $card")
        // It shows from its top, under the screen's edge, with the header scrolled away.
        assertTrue(card.top >= 0.dp, "Card's top cut: $card")
        press(Key.DirectionUp)
        assertTrue(focusedTag().startsWith("grid:sort"), "Up reaches the sort controls, got ${focusedTag()}")
        press(Key.DirectionDown)
        assertFocus("grid:film-0")
    }

    private val longFilms = (0 until 25).map {
        MediaItem("long-$it", "A Long Film Title That Needs More Than Two Lines On A Poster Card", "Movie")
    }
    private val longTitles = (0 until 3).map {
        MediaItem("long-$it", "A Long Film Title That Needs More Than Two Lines On A Poster Card", "Movie")
    }

    private fun showLarge(scale: Float = 1.3f, content: @Composable () -> Unit) {
        fontScale = scale
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PictureshowTheme { content() }
            }
        }
        settle()
    }

    /** Moves Down until a card under [prefix] holds focus, then checks it whole inside the list that clips it. */
    private fun downToWholeCard(prefix: String, clippedBy: String) {
        val path = mutableListOf(focusedTag())
        while (!path.last().startsWith(prefix) && path.size < 8) {
            press(Key.DirectionDown)
            path += focusedTag()
        }
        assertTrue(path.last().startsWith(prefix), "Never reached $prefix: $path")
        assertFocusedCardWhole(prefix, clippedBy)
        press(Key.DirectionRight)
        assertFocusedCardWhole("$prefix, one to the right", clippedBy)
    }

    private val longRows =
        listOf(HomeRow("first", "Movies", longTitles), HomeRow("second", "Shows", longTitles), HomeRow("third", "More", longTitles))

    @Test fun homeShelfShowsAWholeLongTitleCard() {
        showLarge { CatalogHome(CatalogHomeState(longRows, loading = false), account, {}, {}, {}, {}) }
        downToWholeCard("home:second:", "home")
        downToWholeCard("home:third:", "home")
        press(Key.DirectionUp)
        assertFocusedCardWhole("back up on Home", "home")
    }

    @Test fun searchResultsShowAWholeLongTitleCard() {
        showLarge { SearchScreen(SearchState("film", longRows, completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        downToWholeCard("search:second:", "search:results")
        downToWholeCard("search:third:", "search:results")
    }

    @Test fun detailShelfShowsAWholeLongTitleCard() {
        val episodes = longTitles.map {
            it.copy(type = "Episode", seriesName = "A series with a long name", indexNumber = 1, parentIndexNumber = 1)
        }
        showLarge {
            CatalogDetail(CatalogDetailState(account, MediaItem("season", "A season", "Season"), loading = false, episodes = episodes), {
            }, {}, {}, {}, {})
        }
        downToWholeCard("detail:episodes:", "detail")
    }

    @Test fun detailShelfShowsAWholeCardWithAVeryLongTitle() {
        val title = "A Long Episode Title That Needs Many Lines ".repeat(4)
        val episodes = longTitles.map { it.copy(name = title, type = "Episode", seriesName = "A series", indexNumber = 1, parentIndexNumber = 1) }
        showLarge {
            CatalogDetail(
                CatalogDetailState(account, MediaItem("season", "A season", "Season"), loading = false, episodes = episodes),
                {},
                {},
                {},
                {},
                {}
            )
        }
        downToWholeCard("detail:episodes:", "detail")
    }

    @Test fun searchFieldComesBackWholeAboveTheResults() {
        showLarge { SearchScreen(SearchState("film", longRows.take(1), completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        downToWholeCard("search:first:", "search:results")
        press(Key.DirectionUp)
        assertFocus("search:field")
        assertTrue(compose.onNodeWithTag("search:field").getUnclippedBoundsInRoot().top.value >= 0f, "The field stayed above the screen")
    }

    @Test fun personGridShowsAWholeLongTitleCardAndBackIsStillReachable() {
        showLarge {
            val person = MediaItem("person", "A Person", "Person")
            PersonScreen(PersonState(CatalogGridState(account, person, longTitles, longTitles.size), person), {}, {}, {}, {})
        }
        downToWholeCard("grid:long-", "grid")
        press(Key.DirectionUp)
        assertFocus("person:back")
        assertTrue(compose.onNodeWithTag("person:back").getUnclippedBoundsInRoot().top.value >= 0f, "Back stayed above the screen")
    }

    /** A focused control low on the page keeps its place when the text above it grows; the app stays alive through this. */
    private fun growWithFocusOn(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        assertFocus(tag)
        fontScale = 1.3f
        settle()
        settle()
        assertFocus(tag)
        assertFocusedCardWhole("$tag in place to 1.3")
    }

    @Test fun inPlaceFontChangeRevealsSettingsLastControl() {
        showLarge(1f) { PlaybackSettingsScreen(PlaybackSettingsState(loading = false), "PCM", {}, { _, _ -> }, {}, onBack = {}) }
        growWithFocusOn("settings:back")
    }

    @Test fun inPlaceFontChangeRevealsATallGridCard() {
        showLarge(1f) {
            CatalogGrid(CatalogGridState(account, MediaItem("library", "Films"), longFilms, longFilms.size), {}, {}, { _, _ -> }, {})
        }
        repeat(2) { press(Key.DirectionDown) }
        assertFocus("grid:long-10")
        fontScale = 1.3f
        settle()
        settle()
        assertFocus("grid:long-10")
        assertFocusedCardWhole("grid in place to 1.3", "grid:items")
    }

    @Test fun platformDistanceIsKeptWhenTheScaledCardFits() =
        // Card 100 at offset 400 in 1000: the platform's 30% pivot puts it at 300, whole with its overhang.
        assertEquals(100f, focusedCardScrollDistance(400f, 100f, 1000f, 8f, wanted = 100f))

    @Test fun aCardFlushWithTheEndRisesByItsOverhang() =
        // Card 600 at 500 in 1000: platform goes flush with the end, at 400. 30 scale + 8 margin more.
        assertEquals(138f, focusedCardScrollDistance(500f, 600f, 1000f, 8f, wanted = 100f))

    @Test fun aCardFlushWithTheStartDropsByItsOverhang() = assertEquals(
        -138f,
        focusedCardScrollDistance(-100f, 600f, 1000f, 8f, wanted = -100f)
    )

    @Test fun aCardTallerThanTheScreenShowsFromItsTop() =
        assertEquals(50f - (950f * .05f + 8f), focusedCardScrollDistance(50f, 950f, 1000f, 8f, wanted = 0f), .01f)

    @Test fun theFirstRowScrollsBackUnderTheHeaderWhenItFits() =
        assertEquals(-200f, focusedCardScrollDistance(150f, 500f, 1000f, 8f, wanted = 0f, scrollToTop = 200f))

    @Test fun theFirstRowLeavesTheTopWhenItWouldNotFitThere() =
        // At the top it would end past 1000 (600 + 300 + 600), so it rises just enough: 600 + 600 + 38 - 1000.
        assertEquals(238f, focusedCardScrollDistance(600f, 600f, 1000f, 8f, wanted = 0f, scrollToTop = 300f))
}
