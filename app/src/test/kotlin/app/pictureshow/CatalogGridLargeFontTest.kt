/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

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
    private val hugeFont = 2.4f
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

    private fun assertLibraryTilesAreWide(scale: Float) {
        val libraries = HomeRow("libraries", "Libraries", listOf(MediaItem("movies", "Movies", "CollectionFolder")), library = true)
        val films = HomeRow("latest", "Latest in Movies", listOf(MediaItem("film", "Film", "Movie")))
        showLarge(scale) { CatalogHome(CatalogHomeState(rows = listOf(libraries, films), loading = false), account, {}, {}, {}, {}) }
        val tile = compose.onNodeWithTag("home:libraries:movies").getUnclippedBoundsInRoot()
        assertEquals(240f, (tile.right - tile.left).value, 1f, "Library art is 16:9, so its tile is wide")
        val poster = compose.onNodeWithTag("home:latest:film", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(144f, (poster.right - poster.left).value, 1f, "Films stay posters")
    }

    @Test fun libraryTilesAreWideAtDefaultFont() = assertLibraryTilesAreWide(1f)

    @Test fun libraryTilesAreWideAtLargestFont() = assertLibraryTilesAreWide(1.3f)

    /** At 1.6 each row fills the screen, so the row below isn't composed and a move has to scroll for it. */
    private fun tallCards(count: Int) = (0 until count).map {
        MediaItem("film-$it", "A title long enough to fill the whole screen ".repeat(30), "Movie")
    }

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

    /** Down the grid and back up, with the strip clear of the focused card, measured as composed, at every stop it shows. */
    private fun assertEveryStopClearsTheStrip(strip: String, rows: Int = 4) {
        var stops = 0
        fun check() {
            if (compose.onAllNodes(hasTestTag(strip)).fetchSemanticsNodes().isNotEmpty()) {
                assertStripClearsTheCard(strip)
                stops++
            }
        }
        repeat(rows) {
            press(Key.DirectionDown)
            check()
        }
        repeat(rows - 1) {
            press(Key.DirectionUp)
            check()
        }
        assertTrue(stops >= rows, "The strip showed at only $stops stops")
    }

    @Test fun aCardComingBackUpStaysClearOfTheStripAtDefaultFont() {
        show(films, 1f)
        assertEveryStopClearsTheStrip("grid:strip")
    }

    @Test fun aCardComingBackUpStaysClearOfTheStripAtLargestFont() {
        show(films, 1.3f)
        assertEveryStopClearsTheStrip("grid:strip")
    }

    @Test fun aCardFillingTheScreenStaysClearOfTheStripAtBeyondLargestFont() {
        show(tallCards(25), 1.6f)
        assertEveryStopClearsTheStrip("grid:strip")
    }

    @Test fun personGridStaysClearOfItsStripAtLargestFont() {
        showLarge {
            val person = MediaItem("person", "A Person", "Person")
            PersonScreen(PersonState(CatalogGridState(account, person, longTitles, longTitles.size), person), {}, {}, {}, {})
        }
        assertEquals("A Person", stripText("grid:strip"))
        assertEveryStopClearsTheStrip("grid:strip")
    }

    private class Landing(val refused: Set<String> = emptySet()) {
        val asked = mutableListOf<String>()
        var lookedAround = false
        fun land(card: String, now: String?) = runBlocking {
            landOnCard(card, { now }, {
                asked += it
                it !in refused
            }) { lookedAround = true }
        }
    }

    @Test fun aCardThatRefusesFocusIsAskedOnceBeforeLookingAround() {
        val landing = Landing(refused = setOf("a"))
        landing.land("a", "a")
        assertEquals(listOf("a"), landing.asked)
        assertTrue(landing.lookedAround)
    }

    @Test fun aCardThatLandsLooksNowhereElse() {
        val landing = Landing()
        landing.land("a", "b")
        assertEquals(listOf("a"), landing.asked)
        assertFalse(landing.lookedAround)
    }

    @Test fun whatHoldsThePlaceNowIsAskedWhenTheCardIsGone() {
        val landing = Landing(refused = setOf("a"))
        landing.land("a", "b")
        assertEquals(listOf("a", "b"), landing.asked)
        assertFalse(landing.lookedAround)
    }

    @Test fun aPlaceWithNothingInItLooksAround() {
        val landing = Landing(refused = setOf("a"))
        landing.land("a", null)
        assertEquals(listOf("a"), landing.asked)
        assertTrue(landing.lookedAround)
    }

    @Test fun searchStripKeepsTheQueryOnceTheHeadCollapses() {
        showLarge { SearchScreen(SearchState("film", longRows.take(1), completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        // Search arrives on its results, so the head has already collapsed.
        assertEquals("Results for “film”", stripText("search:strip"))
        assertStripClearsTheCard("search:strip")
        press(Key.DirectionUp)
        assertFocus("search:field")
        compose.onNodeWithTag("search:strip").assertDoesNotExist()
        press(Key.DirectionDown)
        assertEquals("Results for “film”", stripText("search:strip"))
    }

    private val accessibility get() = shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)

    private fun enabledService(feedback: Int, capabilities: Int = 0) {
        val service = AccessibilityServiceInfo().apply {
            feedbackType = feedback
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setCapabilities",
                ReflectionHelpers.ClassParameter.from(Int::class.java, capabilities)
            )
        }
        accessibility.setEnabledAccessibilityServiceList(listOf(service))
        accessibility.setEnabled(true)
    }

    private fun showSearch(scale: Float = 1.3f, rows: List<HomeRow> = longRows.take(1)) =
        showLarge(scale) { SearchScreen(SearchState("film", rows, completed = true), account, false, {}, {}, {}, {}, {}, {}) }

    private val threeRowsOfLongTitles =
        longTitles + longTitles.map { it.copy(id = it.id + "-more") } + longTitles.map { it.copy(id = it.id + "-last") }

    private fun showPerson(items: List<MediaItem> = longTitles, scale: Float = 1.3f, refreshError: String? = null) = showLarge(scale) {
        val person = MediaItem("person", "A Person", "Person")
        val grid = CatalogGridState(account, person, items, items.size, refreshError = refreshError)
        PersonScreen(PersonState(grid, person), {}, {}, {}, {})
    }

    /** Nothing fixed sits over it: the head has moved into the grid, above it. */
    private fun assertNotCutByTheHead(text: String) {
        val head = compose.onAllNodes(hasTestTag("person:name")).fetchSemanticsNodes().singleOrNull()?.let {
            compose.onNodeWithTag("person:back").getUnclippedBoundsInRoot().bottom
        } ?: return
        val found = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().singleOrNull() ?: return
        assertTrue(found.boundsInRoot.height > 0f, "$text is hidden")
        assertTrue(compose.onNode(hasText(text)).getUnclippedBoundsInRoot().top >= head, "$text is under the head")
    }

    /** A section title is whole inside the list or wholly out of it: never a cut word under the strip. */
    private fun assertSectionTitleWholeOrGone(title: String, context: String) {
        val list = compose.onNodeWithTag("search:results").getBoundsInRoot()
        val node = compose.onAllNodes(hasText(title)).fetchSemanticsNodes().singleOrNull() ?: return
        val bounds = compose.onNode(hasText(title)).getUnclippedBoundsInRoot()
        val whole = bounds.top >= list.top && bounds.bottom <= list.bottom
        val gone = bounds.bottom <= list.top || node.boundsInRoot.height == 0f
        val card = compose.onNodeWithTag(focusedTag()).getUnclippedBoundsInRoot()
        assertTrue(whole || gone, "$context: $title cut by the list's edge: $bounds in $list, card $card")
    }

    /** Where the focused card and its row's title fit on screen together, the title shows whole above it. */
    private fun assertSectionTitleShown(title: String, context: String) {
        val list = compose.onNodeWithTag("search:results").getBoundsInRoot()
        val bounds = compose.onNode(hasText(title)).getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag(focusedTag()).getUnclippedBoundsInRoot()
        val shown = bounds.top >= list.top && bounds.bottom <= card.top
        assertTrue(shown, "$context: $title not shown with its card: $bounds in $list, card $card")
    }

    private fun walkSearchRowsCheckingTitles(scale: Float, titleFitsWithCard: Boolean = false) {
        fun focusedRow(title: String, context: String) {
            assertSectionTitleWholeOrGone(title, context)
            if (titleFitsWithCard) assertSectionTitleShown(title, context)
        }
        showSearch(scale = scale, rows = longRows)
        assertSectionTitleWholeOrGone("Movies", "arrival at $scale")
        press(Key.DirectionDown)
        focusedRow("Shows", "second row at $scale")
        assertSectionTitleWholeOrGone("Movies", "second row at $scale")
        press(Key.DirectionDown)
        focusedRow("More", "third row at $scale")
        press(Key.DirectionUp)
        focusedRow("Shows", "back up at $scale")
        assertSectionTitleWholeOrGone("Movies", "back up at $scale")
    }

    /** The card's request is at offset 600; its row's title takes the 59 above the card's top, the last 28 of it a gap. */
    private fun titleDistance(distance: Float, size: Float) = sectionTitleScrollDistance(distance, 600f, size, 460f, 27f, 59f, 28f)

    @Test fun aTitleCutByTheListsTopComesIntoViewWhenItFitsWithTheCard() {
        // Card top at 40 would cut the title; 59 + 300 + its 15 overhang + 27 fits in 460, so the title shows whole.
        assertEquals(541f, titleDistance(560f, 300f))
    }

    @Test fun aTitleThatDoesNotFitWithTheCardGoesWhollyUnderTheList() {
        // 59 + 393 + 19.65 + 27 is past 460: the title's bottom goes to the list's top, the card's top 28 below it.
        assertEquals(572f, titleDistance(560f, 393f))
        // A card whose focus overhang passes the gap keeps the overhang whole instead.
        assertEquals(565f, titleDistance(560f, 700f))
    }

    @Test fun aTitleAlreadyWholeOrGoneLeavesTheScrollAlone() {
        assertEquals(530f, titleDistance(530f, 300f))
        assertEquals(580f, titleDistance(580f, 300f))
    }

    @Test fun searchSectionTitlesAreNeverHalfHiddenAtDefaultFont() = walkSearchRowsCheckingTitles(1f, titleFitsWithCard = true)

    @Test fun searchSectionTitlesAreNeverHalfHiddenAtTheCollapseSize() = walkSearchRowsCheckingTitles(1.15f, titleFitsWithCard = true)

    @Test fun searchSectionTitlesAreNeverHalfHiddenAtLargestFont() = walkSearchRowsCheckingTitles(1.3f)

    @Test fun searchSectionTitlesAreNeverHalfHiddenPastTheLargestFont() = walkSearchRowsCheckingTitles(1.6f)

    @Test fun searchHasItsHeadInTheListForAScreenReader() {
        accessibility.setScreenReader(true)
        showSearch()
        compose.onNodeWithTag("search:strip").assertDoesNotExist()
        assertFocusedCardWhole("the long title", clippedBy = "search:results")
        press(Key.DirectionUp)
        assertFocus("search:field")
        assertTrue(compose.onNodeWithTag("search:field").getUnclippedBoundsInRoot().top.value >= 0f, "the field left the screen")
        press(Key.DirectionDown)
        assertFocusedCardWhole("back on the card", clippedBy = "search:results")
    }

    @Test fun aScreenReaderReachesTheFieldFromADeepSearchRow() {
        accessibility.setScreenReader(true)
        showSearch(rows = longRows)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertFocusedCardWhole("the third row", clippedBy = "search:results")
        repeat(2) { press(Key.DirectionUp) }
        assertFocusedCardWhole("the first row", clippedBy = "search:results")
        press(Key.DirectionUp)
        assertFocus("search:field")
    }

    // Past the largest setting a row outgrows the screen: the row after it isn't composed, and the head scrolls right off.
    @Test fun aScreenReaderCrossesRowsTallerThanTheScreenToTheField() {
        accessibility.setScreenReader(true)
        showSearch(scale = hugeFont, rows = longRows)
        val first = focusedTag()
        press(Key.DirectionDown)
        val second = focusedTag()
        assertTrue(second != first, "Down left the first row")
        press(Key.DirectionDown)
        assertTrue(focusedTag() != second, "Down reached the third row")
        press(Key.DirectionUp)
        assertFocus(second)
        press(Key.DirectionUp)
        assertFocus(first)
        press(Key.DirectionUp)
        assertFocus("search:field")
    }

    @Test fun backFromADeepRowReachesTheFieldAfterTheHeadHasScrolledAway() {
        accessibility.setScreenReader(true)
        showSearch(scale = hugeFont, rows = longRows)
        repeat(2) { press(Key.DirectionDown) }
        compose.onNodeWithTag("search:field").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertFocus("search:field")
    }

    @Test fun aScreenReaderReachesBackFromADeepPersonRowAtAHugeFont() {
        accessibility.setScreenReader(true)
        showPerson(threeRowsOfLongTitles, hugeFont)
        val first = focusedTag()
        var guard = 0
        while (compose.onAllNodes(hasTestTag("person:back")).fetchSemanticsNodes().isNotEmpty()) {
            press(Key.DirectionDown)
            assertTrue(guard++ < 8, "The head never left")
        }
        while (focusedTag() != first) {
            press(Key.DirectionUp)
            assertTrue(guard++ < 16, "Never got back to the first card")
        }
        press(Key.DirectionUp)
        assertFocus("person:back")
    }

    @Test fun aScreenReaderReachesBackFromACardThatPushedTheHeadOffAtAHugeFont() {
        accessibility.setScreenReader(true)
        showPerson(scale = hugeFont)
        press(Key.DirectionUp)
        assertFocus("person:back")
        compose.onNodeWithTag("person:name").assertIsDisplayed()
    }

    @Test fun personHasItsHeadInTheGridForAScreenReader() {
        accessibility.setScreenReader(true)
        showPerson()
        compose.onNodeWithTag("grid:strip").assertDoesNotExist()
        assertFocusedCardWhole("the long title")
        assertNotCutByTheHead("Movies and shows")
        press(Key.DirectionUp)
        assertFocus("person:back")
        compose.onNodeWithTag("person:name").assertIsDisplayed()
        assertNotCutByTheHead("Movies and shows")
    }

    // As on a library grid: the Retry the viewer must act on comes before Back.
    @Test fun upFromPersonsFirstRowReachesARefreshErrorsRetryBeforeBack() {
        accessibility.setScreenReader(true)
        showPerson(refreshError = "Unavailable")
        press(Key.DirectionUp)
        assertFocus("grid:retry")
    }

    @Test fun aPasswordManagerDoesNotStopSearchsHeadCollapsing() {
        enabledService(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        showSearch()
        assertEquals("Results for “film”", stripText("search:strip"))
    }

    @Test fun aPasswordManagerDoesNotStopPersonsHeadCollapsing() {
        enabledService(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        showPerson()
        assertEquals("A Person", stripText("grid:strip"))
    }

    @Test fun aGestureServiceCountsLikeAReader() {
        enabledService(AccessibilityServiceInfo.FEEDBACK_GENERIC, AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
        showSearch()
        compose.onNodeWithTag("search:strip").assertDoesNotExist()
        press(Key.DirectionUp)
        assertFocus("search:field")
    }

    @Test fun touchExplorationAloneCountsLikeAReader() {
        accessibility.setTouchExplorationEnabled(true)
        accessibility.setEnabled(true)
        showPerson()
        compose.onNodeWithTag("grid:strip").assertDoesNotExist()
    }

    private fun flipReaderBothWays(whole: String?) {
        val focused = focusedTag()
        accessibility.setScreenReader(true)
        settle()
        assertFocus(focused)
        assertFocusedCardWhole("reader turned on", clippedBy = whole)
        accessibility.setScreenReader(false)
        settle()
        assertFocus(focused)
        assertFocusedCardWhole("reader turned off", clippedBy = whole)
        accessibility.setScreenReader(true)
        settle()
        assertFocus(focused)
        assertFocusedCardWhole("reader turned on again", clippedBy = whole)
    }

    @Test fun aReaderTurnedOnAndOffKeepsTheCardOnSearch() {
        showSearch(rows = longRows)
        press(Key.DirectionDown)
        assertEquals("Results for “film”", stripText("search:strip"))
        flipReaderBothWays("search:results")
        compose.onNodeWithTag("search:strip").assertDoesNotExist()
    }

    @Test fun aReaderTurnedOnAndOffKeepsTheCardOnSearchsFirstRow() {
        showSearch()
        flipReaderBothWays("search:results")
    }

    @Test fun aReaderTurnedOnAndOffKeepsTheCardOnPerson() {
        showPerson(threeRowsOfLongTitles)
        press(Key.DirectionDown)
        flipReaderBothWays(null)
    }

    @Test fun aReaderTurnedOnKeepsFocusOnSearchsField() {
        showSearch()
        press(Key.DirectionUp)
        assertFocus("search:field")
        accessibility.setScreenReader(true)
        settle()
        assertFocus("search:field")
        accessibility.setScreenReader(false)
        settle()
        assertFocus("search:field")
    }

    @Test fun aReaderTurnedOnKeepsFocusOnPersonsBack() {
        showPerson()
        press(Key.DirectionUp)
        assertFocus("person:back")
        accessibility.setScreenReader(true)
        settle()
        assertEquals("person:back", focusedTag(), "reader turned on")
        accessibility.setScreenReader(false)
        settle()
        assertEquals("person:back", focusedTag(), "reader turned off")
    }

    @Test fun aReaderTurnedOnKeepsPersonsBackInViewAtAHugeFont() {
        showPerson(scale = hugeFont)
        press(Key.DirectionUp)
        assertFocus("person:back")
        accessibility.setScreenReader(true)
        settle()
        assertEquals("person:back", focusedTag(), "reader turned on")
        compose.onNodeWithTag("person:name").assertIsDisplayed()
    }

    private fun flipReaderKeeps(tag: String) {
        accessibility.setScreenReader(true)
        settle()
        assertEquals(tag, focusedTag(), "reader turned on")
        accessibility.setScreenReader(false)
        settle()
        assertEquals(tag, focusedTag(), "reader turned off")
    }

    @Test fun aReaderTurnedOnKeepsFocusOnSearchsRetry() {
        val state = SearchState("film", longRows, completed = true, someFailed = true)
        showLarge { SearchScreen(state, account, false, {}, {}, {}, {}, {}, {}) }
        var guard = 0
        while (focusedTag() != "search:retry") {
            press(Key.DirectionUp)
            assertTrue(guard++ < 4, "Never reached Retry")
        }
        flipReaderKeeps("search:retry")
    }

    @Test fun aReaderTurnedOnKeepsFocusOnARecentSearch() {
        showLarge { SearchScreen(SearchState(recent = listOf("clock", "film")), account, false, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("recent:0").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        flipReaderKeeps("recent:0")
    }

    @Test fun aReaderTurnedOnWhileTypingKeepsTheKeyboard() {
        showSearch()
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        assertTrue(SemanticsActions.SetText in compose.onNodeWithTag("search:field").fetchSemanticsNode().config, "typing")
        accessibility.setScreenReader(true)
        settle()
        assertFocus("search:field")
        assertTrue(SemanticsActions.SetText in compose.onNodeWithTag("search:field").fetchSemanticsNode().config, "still typing")
    }

    @Test fun aReaderTurnedOnKeepsWhatTheFieldHolds() {
        showSearch()
        press(Key.DirectionUp)
        accessibility.setScreenReader(true)
        settle()
        val field = compose.onNodeWithTag("search:field").fetchSemanticsNode().config
        assertEquals("film", field[SemanticsProperties.EditableText].text)
    }

    /** The strip's text clears the overscan margin: its centre is fixed, so a size can't push the ink up. */
    private fun assertStripTextCentre(tag: String, text: String) {
        val node = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        val bounds = node.getUnclippedBoundsInRoot()
        assertEquals(38f, (bounds.top + bounds.bottom).value / 2, .75f, "$tag text at $bounds")
        val strip = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertEquals(0f, strip.top.value, .5f)
        assertEquals(56f, strip.bottom.value, .5f)
    }

    private fun assertGridStripBelowTheMargin(scale: Float) {
        show(scale = scale)
        repeat(3) { press(Key.DirectionDown) }
        assertStripTextCentre("grid:strip", "Films")
    }

    @Test fun gridStripTextSitsBelowTheMarginAtDefaultFont() = assertGridStripBelowTheMargin(1f)

    @Test fun gridStripTextSitsBelowTheMarginAtLargestFont() = assertGridStripBelowTheMargin(1.3f)

    @Test fun searchsListStartsAtTheStripsBottom() {
        showSearch()
        assertStripTextCentre("search:strip", "Results")
        assertEquals(
            compose.onNodeWithTag("search:strip").getUnclippedBoundsInRoot().bottom.value,
            compose.onNodeWithTag("search:results").getUnclippedBoundsInRoot().top.value,
            .5f
        )
    }

    @Test fun theStripShowsTheQueryAsNormalized() {
        showLarge { SearchScreen(SearchState("  film  ", longRows.take(1), completed = true), account, false, {}, {}, {}, {}, {}, {}) }
        assertEquals("Results for “film”", stripText("search:strip"))
    }

    @Test fun aNextPageLoadingDoesNotPushTheFirstRowDown() {
        showState(filmsState(films.take(12)))
        val before = compose.onNodeWithTag("grid:film-0").getUnclippedBoundsInRoot().top
        gridState = filmsState(films.take(12), loading = true)
        settle()
        assertEquals(before.value, compose.onNodeWithTag("grid:film-0").getUnclippedBoundsInRoot().top.value, .5f)
    }

    @Test fun aFirstLoadStillSaysLoading() {
        showState(filmsState(emptyList(), loading = true))
        compose.onNode(hasText("Loading…")).assertIsDisplayed()
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
        holdDownOnFilm8()
        runFrames(500)
        assertFocus("grid:film-8")
        assertLandsAtOnce(filmsState(films.take(20)), "grid:film-13")
    }

    @Test fun aHeldDownIsDroppedOnceFocusMoves() {
        holdDownOnFilm8()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        runFrames(100)
        assertFocus("grid:film-7")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-7")
    }

    @Test fun anUpThatGoesNowhereStillDropsTheHold() {
        val few = films.take(3)
        gridState = filmsState(few)
        showLarge(1f) { CatalogGrid(gridState, {}, {}, { _, _ -> }, {}, showSort = false) }
        gridState = filmsState(few, loading = true)
        settle()
        holdDown()
        assertFocus("grid:film-0")
        // No sort controls above: Up falls to a focus search that finds nothing.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        runFrames(100)
        assertFocus("grid:film-0")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-0")
    }

    @Test fun aHeldDownIsDroppedWhenFocusGoesToTheSortWithNoKey() {
        val few = films.take(3)
        showState(filmsState(few))
        gridState = filmsState(few, loading = true)
        settle()
        holdDown()
        assertFocus("grid:film-0")
        compose.onNodeWithTag("grid:sort:SortName").performSemanticsAction(SemanticsActions.RequestFocus)
        runFrames(100)
        assertFocus("grid:sort:SortName")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:sort:SortName")
    }

    @Test fun aHeldDownIsDroppedWhenFocusMovesWithNoKey() {
        holdDownOnFilm8()
        // A screen reader moves focus itself, and the card's key handler never sees it.
        compose.onNodeWithTag("grid:film-7").performSemanticsAction(SemanticsActions.RequestFocus)
        runFrames(100)
        assertFocus("grid:film-7")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-7")
    }

    @Test fun aHeldDownLandsOnTheLastCardWhenThePageFails() {
        holdDownOnFilm8()
        assertLandsAtOnce(filmsState(films.take(12)).copy(error = "Could not load"), "grid:film-11")
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

    @Test fun aCardTooTallForTheScreenStaysBelowTheStripInTheGrid() {
        val tall = tallCards(15)
        showState(filmsState(tall), scale = 1.6f)
        press(Key.DirectionDown)
        assertFocus("grid:film-5")
        assertStripClearsTheCard("grid:strip")
    }

    @Test fun aMoveWhoseCardIsGoneTakesWhatHoldsItsPlaceNow() {
        // Each row fills the screen, so the row below isn't composed and the move has to scroll for it.
        val tall = tallCards(15)
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

    /** Frame by frame with the looper drained between them, as a screen runs. A long advance runs less than it says. */
    private fun runFrames(millis: Long) = repeat((millis / 16).toInt()) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** A Down that holds, with the clock stopped: the cap counts frames, and an idling test would run straight past it. */
    private fun holdDown() {
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        runFrames(100)
    }

    /** Down on a card the page below hasn't reached: film-8 of twelve, in the fourth column. */
    private fun holdDownOnFilm8() {
        showPageLoading()
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        assertFocus("grid:film-8")
        holdDown()
    }

    @Test fun aHeldDownLandsOnTheLastCardAtTheCap() {
        holdDownOnFilm8()
        runFrames(1_200)
        assertFocus("grid:film-8")
        runFrames(500)
        assertFocus("grid:film-11")
    }

    @Test fun aPageArrivingAfterTheCapLeavesFocusWhereTheViewerIs() {
        holdDownOnFilm8()
        runFrames(2_000)
        assertFocus("grid:film-11")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-11")
    }

    @Test fun aRepeatingDownDoesNotPutTheCapOff() {
        holdDownOnFilm8()
        repeat(5) {
            runFrames(400)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        assertFocus("grid:film-11")
    }

    /** Well before the cap, so the release is the page's doing. */
    private fun assertLandsAtOnce(state: CatalogGridState, tag: String) {
        gridState = state
        runFrames(200)
        assertFocus(tag)
    }

    @Test fun aHeldDownLandsOnTheLastCardWhenThePageIsEmpty() {
        holdDownOnFilm8()
        assertLandsAtOnce(filmsState(films.take(12)), "grid:film-11")
    }

    @Test fun theCapFromTheLastRowLeavesFocusThere() {
        showPageLoading()
        repeat(2) { press(Key.DirectionDown) }
        assertFocus("grid:film-10")
        press(Key.DirectionDown)
        settle()
        assertFocus("grid:film-10")
        gridState = filmsState(films.take(20))
        settle()
        assertFocus("grid:film-10")
    }

    /** Up on a held card with the page arriving in the same frame, before Up's own focus job has had one. */
    private fun pressUpAsThePageArrives(page: CatalogGridState) {
        compose.mainClock.autoAdvance = false
        gridState = page
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        settle()
    }

    @Test fun upFromTheFirstRowWhileHeldLandsOnlyOnTheSort() {
        val few = tallCards(3)
        showState(filmsState(few), scale = 1.6f)
        gridState = filmsState(few, loading = true)
        settle()
        assertFocus("grid:film-0")
        holdDown()
        assertFocus("grid:film-0")
        pressUpAsThePageArrives(filmsState(tallCards(15)))
        assertFocus("grid:sort:SortName")
    }

    @Test fun upToARowNotComposedWhileHeldLandsOnlyThere() {
        val tall = tallCards(8)
        showState(filmsState(tall), scale = 1.6f)
        gridState = filmsState(tall, loading = true)
        settle()
        press(Key.DirectionDown)
        assertFocus("grid:film-5")
        holdDown()
        assertFocus("grid:film-5")
        pressUpAsThePageArrives(filmsState(tallCards(15)))
        assertFocus("grid:film-0")
    }

    private fun assertSortChangeDropsTheHold(chip: String, change: CatalogGridState.() -> CatalogGridState) {
        val few = films.take(3)
        gridState = filmsState(few)
        showLarge(1f) { CatalogGrid(gridState, {}, {}, { _, _ -> gridState = gridState.change() }, {}) }
        gridState = filmsState(few, loading = true)
        settle()
        holdDown()
        assertFocus("grid:film-0")
        // A reader's click: it reaches the chip with no key and no focus move.
        compose.onNodeWithTag("grid:sort:${gridState.sortBy}").performSemanticsAction(SemanticsActions.OnClick)
        runFrames(100)
        compose.onNodeWithText(if (chip == "grid:sort-order") "Name · Descending" else "Date added · Ascending")
            .performSemanticsAction(SemanticsActions.OnClick)
        runFrames(200)
        gridState = gridState.copy(items = films.take(20), total = 20, loading = false)
        settle()
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertTrue("grid:film-5" !in focused, "The old hold landed: $focused")
    }

    @Test fun aSortClickWhileHeldDropsTheHold() = assertSortChangeDropsTheHold("grid:sort:DateCreated") {
        copy(sortBy = "DateCreated", items = emptyList(), loading = true)
    }

    @Test fun anOrderClickWhileHeldDropsTheHold() = assertSortChangeDropsTheHold("grid:sort-order") {
        copy(descending = true, items = emptyList(), loading = true)
    }

    // The grid's own view model empties the list on a sort, which drops focus and the hold with it. The drop doesn't lean on that.
    @Test fun aSortClickKeepingTheListWhileHeldDropsTheHold() = assertSortChangeDropsTheHold("grid:sort:DateCreated") {
        copy(sortBy = "DateCreated", loading = true)
    }

    @Test fun anOrderClickKeepingTheListWhileHeldDropsTheHold() = assertSortChangeDropsTheHold("grid:sort-order") {
        copy(descending = true, loading = true)
    }

    @Test fun aKeyThatMovesNothingStillDropsTheHold() {
        showPageLoading()
        repeat(2) { press(Key.DirectionDown) }
        assertFocus("grid:film-10")
        holdDown()
        // First column: Left has nowhere to go.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        gridState = filmsState(films.take(20))
        settle()
        settle()
        assertFocus("grid:film-10")
    }

    /** Both keys go in before the grid has had a frame to compose the row the first one is scrolling for. */
    private fun pressTwoInOneFrame(first: Key, second: Key) {
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput {
            keyDown(first)
            keyUp(first)
            keyDown(second)
            keyUp(second)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        settle()
    }

    @Test fun aRightDuringAMoveGivesOnlyTheRight() {
        showState(filmsState(tallCards(15)), scale = 1.6f)
        assertFocus("grid:film-0")
        pressTwoInOneFrame(Key.DirectionDown, Key.DirectionRight)
        assertFocus("grid:film-1")
    }

    @Test fun anUpDuringADownGivesOnlyTheUp() {
        showState(filmsState(tallCards(15)), scale = 1.6f)
        press(Key.DirectionDown)
        assertFocus("grid:film-5")
        pressTwoInOneFrame(Key.DirectionDown, Key.DirectionUp)
        assertFocus("grid:film-0")
    }

    @Test fun aSecondDownDuringAMoveIsTheSameMove() {
        showState(filmsState(tallCards(15)), scale = 1.6f)
        pressTwoInOneFrame(Key.DirectionDown, Key.DirectionDown)
        assertFocus("grid:film-5")
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
