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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
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
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The library grid on a 960x540 dp TV: column kept, focused card whole, at each size the TV's Settings offer and above. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogGridLargeFontTest {
    @get:Rule(order = 0)
    val timeLimit = PerTestTimeLimit(180)

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
        assertFocus("grid:sort:SortName")
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
        val episodes = longTitles.map {
            it.copy(name = title, type = "Episode", seriesName = "A series", indexNumber = 1, parentIndexNumber = 1)
        }
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

    private var gridState by mutableStateOf(CatalogGridState(account, MediaItem("library", "Films")))

    private fun showState(state: CatalogGridState, scale: Float = 1f) {
        gridState = state
        showLarge(scale) { CatalogGrid(gridState, {}, {}, { _, _ -> }, {}) }
    }

    private fun filmsState(items: List<MediaItem> = films, loading: Boolean = false) = CatalogGridState(
        account,
        MediaItem("library", "Films"),
        items,
        items.size,
        loading = loading
    )

    /** Twelve cards in, focus placed, and the next page goes in flight, the order the app sees them in. */
    private fun showPageLoading() {
        showState(filmsState(films.take(12)))
        gridState = filmsState(films.take(12), loading = true)
        settle()
    }

    private fun stripText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The strip and the focused card's lifted top don't meet. */
    private fun assertStripClearsTheCard(tag: String) {
        val strip = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag(focusedTag()).getUnclippedBoundsInRoot()
        val lifted = card.top - (card.bottom - card.top) * .05f
        assertTrue(lifted >= strip.bottom, "Strip $strip covers ${focusedTag()} lifted to $lifted")
    }

    private fun assertStripNamesTheLibraryOnceScrolled(scale: Float) {
        show(scale = scale)
        compose.onNodeWithTag("grid:strip").assertDoesNotExist()
        repeat(3) { press(Key.DirectionDown) }
        assertEquals("Films · Name, ascending", stripText("grid:strip"))
        assertStripClearsTheCard("grid:strip")
        assertTrue(compose.onNodeWithTag("grid:strip").fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
        repeat(3) { press(Key.DirectionUp) }
        compose.onNodeWithTag("grid:strip").assertDoesNotExist()
    }

    @Test fun stripNamesTheLibraryOnceScrolledAtDefaultFont() = assertStripNamesTheLibraryOnceScrolled(1f)

    @Test fun stripNamesTheLibraryOnceScrolledAtLargestFont() = assertStripNamesTheLibraryOnceScrolled(1.3f)

    @Test fun stripUsesTheSortInForce() {
        showState(filmsState().copy(sortBy = "PremiereDate", descending = true))
        repeat(3) { press(Key.DirectionDown) }
        assertEquals("Films · Release date, descending", stripText("grid:strip"))
        gridState = gridState.copy(sortBy = "DateCreated", descending = false)
        settle()
        assertEquals("Films · Date added, ascending", stripText("grid:strip"))
    }

    @Test fun aLongLibraryNameGivesWayToTheSort() {
        gridState = filmsState().copy(parent = MediaItem("library", "A library with a very long name ".repeat(12)))
        showLarge(1.3f) { CatalogGrid(gridState, {}, {}, { _, _ -> }, {}) }
        repeat(3) { press(Key.DirectionDown) }
        val strip = compose.onNodeWithTag("grid:strip").getUnclippedBoundsInRoot()
        assertEquals(ContextStripHeight, strip.bottom - strip.top)
        val sort = compose.onNode(hasText(" · Name, ascending"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(sort.right <= strip.right, "Sort pushed off the strip: $sort in $strip")
    }

    @Test fun aCardTallerThanTheScreenStartsBelowTheStrip() = assertEquals(
        50f - (950f * .05f + 8f) - 40f,
        focusedCardScrollDistance(50f, 950f, 1000f, 8f, wanted = 0f, topInset = 40f),
        .01f
    )

    @Test fun aCardTooCloseToTheTopDropsBelowTheStrip() = assertEquals(
        // Card 100 at 20 in 1000: 13 of scale and margin plus the strip's 40 above it.
        20f - 13f - 40f,
        focusedCardScrollDistance(20f, 100f, 1000f, 8f, wanted = 0f, topInset = 40f),
        .01f
    )

    @Test fun searchStripKeepsTheQueryOnceTheHeadCollapses() {
        showLarge { SearchScreen(SearchState("film", longRows.take(1), completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        // Search arrives on its results, so the head has already collapsed.
        assertEquals("Results for \"film\"", stripText("search:strip"))
        assertStripClearsTheCard("search:strip")
        press(Key.DirectionUp)
        assertFocus("search:field")
        compose.onNodeWithTag("search:strip").assertDoesNotExist()
        press(Key.DirectionDown)
        assertEquals("Results for \"film\"", stripText("search:strip"))
    }

    @Test fun searchKeepsItsHeadAndNoStripBelowTheCollapseSize() {
        showLarge(1.15f) { SearchScreen(SearchState("film", longRows.take(1), completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        press(Key.DirectionDown)
        compose.onNodeWithTag("search:strip").assertDoesNotExist()
    }

    @Test fun personStripNamesThePersonOnceTheHeadCollapses() {
        showLarge {
            val person = MediaItem("person", "A Person", "Person")
            PersonScreen(PersonState(CatalogGridState(account, person, longTitles, longTitles.size), person), {}, {}, {}, {})
        }
        assertEquals("A Person", stripText("grid:strip"))
        assertStripClearsTheCard("grid:strip")
    }

    @Test fun downWaitsForTheLoadingPageAndLandsInTheColumn() {
        showPageLoading()
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        assertFocus("grid:film-8")
        press(Key.DirectionDown)
        assertFocus("grid:film-8")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-13")
    }

    @Test fun aHeldDownIsDroppedOnceFocusMoves() {
        showPageLoading()
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        assertFocus("grid:film-7")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-7")
    }

    @Test fun aHeldDownLandsOnTheLastCardWhenThePageFails() {
        showPageLoading()
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        gridState = filmsState(films.take(12)).copy(error = "Could not load")
        settle()
        assertFocus("grid:film-11")
    }

    @Test fun upFromTheFirstRowLandsOnTheSelectedSort() {
        showState(filmsState().copy(sortBy = "DateCreated"))
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionUp)
        assertFocus("grid:sort:DateCreated")
    }

    @Test fun upWithNoSortControlsStillUsesFocusSearch() {
        showLarge(1f) {
            val person = MediaItem("person", "A Person", "Person")
            PersonScreen(PersonState(CatalogGridState(account, person, films, films.size), person), {}, {}, {}, {})
        }
        press(Key.DirectionUp)
        assertFocus("person:back")
    }

    @Test fun aMoveWhoseCardIsGoneTakesWhatHoldsItsPlaceNow() {
        // Each row fills the screen, so the row below isn't composed and the move has to scroll for it.
        val tall = (0 until 15).map { MediaItem("film-$it", "A title long enough to fill the whole screen ".repeat(30), "Movie") }
        showState(filmsState(tall), scale = 1.6f)
        assertFocus("grid:film-0")
        compose.mainClock.autoAdvance = false
        // The new list arrives with the press, before the grid has composed anything for the move.
        gridState = filmsState(tall.filterNot { it.id == "film-5" })
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        settle()
        assertFocus("grid:film-6")
    }

    private fun assertReturnRestoresTheDeepRow(scale: Float) {
        var open by mutableStateOf(false)
        gridState = filmsState()
        showLarge(scale) {
            val holder = rememberSaveableStateHolder()
            if (!open) holder.SaveableStateProvider("grid") { CatalogGrid(gridState, {}, {}, { _, _ -> }, {}) } else Text("Away")
        }
        repeat(2) { press(Key.DirectionRight) }
        repeat(4) { press(Key.DirectionDown) }
        assertFocus("grid:film-22")
        val top = compose.onNodeWithTag("grid:film-22").getUnclippedBoundsInRoot().top
        val strip = stripText("grid:strip")
        open = true
        settle()
        open = false
        settle()
        settle()
        assertFocus("grid:film-22")
        assertEquals(top.value, compose.onNodeWithTag("grid:film-22").getUnclippedBoundsInRoot().top.value, 1f, "Scroll moved")
        assertEquals(strip, stripText("grid:strip"))
        assertFocusedCardWhole("on return")
    }

    @Test fun returningAtDefaultFontRestoresTheDeepRow() = assertReturnRestoresTheDeepRow(1f)

    @Test fun returningAtLargestFontRestoresTheDeepRow() = assertReturnRestoresTheDeepRow(1.3f)
}
