/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.jumpLetter
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Round 3 (leg 194): a 1080p TV grid shows six posters across, says where the viewer is, and jumps by letter with a D-pad. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogLetterRailTest {
    @get:Rule(order = 0)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val stores = mutableListOf<ViewModelStore>()

    /** 330 titles: three under #, then runs of A to Z with no Q, in the server's order. */
    private val titles: List<MediaItem> = buildList {
        repeat(3) { add(MediaItem("film-$size", "${it + 1}0 Days", "Movie", sortName = "${it + 1}0 days")) }
        val letters = ('A'..'Z').filter { it != 'Q' }
        var n = 0
        while (size < 330) {
            val letter = letters[(n * letters.size) / 327]
            add(MediaItem("film-$size", "$letter Title $n", "Movie", sortName = "$letter title ${n.toString().padStart(3, '0')}"))
            n++
        }
    }

    private inner class Library : CatalogService by CatalogModelsTest.FakeCatalog() {
        val asked = mutableListOf<Pair<Int, Int>>()
        var sortNamesAsked = 0
        override suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage> {
            asked += query.startIndex to query.limit
            val ordered = if (query.descending) titles.reversed() else titles
            // A card read carries no sort name, as CARD_FIELDS leaves it out: the rail's letters come from sortNames alone.
            val page = ordered.drop(query.startIndex).take(query.limit).map { it.copy(sortName = null) }
            return ClientResult.Success(ItemPage(page, ordered.size))
        }

        override suspend fun sortNames(account: Account, query: BrowseQuery): ClientResult<ItemPage> {
            sortNamesAsked++
            val ordered = if (query.descending) titles.reversed() else titles
            return ClientResult.Success(ItemPage(ordered.map { MediaItem(it.id, it.name, it.type, sortName = it.sortName) }, ordered.size))
        }
    }

    private val library = Library()
    private lateinit var vm: CatalogGridViewModel

    @After fun cleanup() = stores.forEach(ViewModelStore::clear)

    private fun show() {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        vm = CatalogGridViewModel(account, MediaItem("library", "Movies", collectionType = "movies"), CatalogSession(library, accounts))
        stores += ViewModelStore().apply { put("vm", vm) }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme {
                CatalogGrid(state, {}, vm::nearEnd, vm::sort, vm::retry, onJump = vm::jump, onJumpDone = vm::jumpDone)
            }
        }
        settle()
    }

    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun focusedTag(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single().config[SemanticsProperties.TestTag]

    private fun position(): String =
        compose.onNodeWithTag("grid:position").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    @Test fun sixPostersFitAcrossA1080pScreen() {
        show()
        // film-0 has focus and is drawn larger, so row one is read from its neighbours.
        val top = compose.onNodeWithTag("grid:film-1").getUnclippedBoundsInRoot().top
        (2..5).forEach { assertEquals(top, compose.onNodeWithTag("grid:film-$it").getUnclippedBoundsInRoot().top, "film-$it in row one") }
        assertTrue(compose.onNodeWithTag("grid:film-6").getUnclippedBoundsInRoot().top > top, "film-6 starts row two")
        // The rail sits right of the last column, and nothing overlaps it.
        val rail = compose.onNodeWithTag("grid:rail").getUnclippedBoundsInRoot()
        assertTrue(compose.onNodeWithTag("grid:film-5").getUnclippedBoundsInRoot().right <= rail.left, "the rail is beside the cards")
    }

    @Test fun thePositionFollowsTheFocusedCardOverTheWholeLibrary() {
        show()
        assertEquals("grid:film-0", focusedTag())
        assertEquals("1 / 330", position())
        press(Key.DirectionRight)
        assertEquals("2 / 330", position())
        press(Key.DirectionDown)
        assertEquals("8 / 330", position())
    }

    @Test fun rightFromTheLastColumnReachesTheRailOnTheCardsLetterAndOkJumpsPastTheLoadedPage() {
        show()
        repeat(5) { press(Key.DirectionRight) }
        assertEquals("grid:film-5", focusedTag())
        // film-5 files under A, as its sort name begins with it.
        press(Key.DirectionRight)
        assertEquals("grid:rail:A", focusedTag())
        // Down skips Q, which has no titles; P to R is one press.
        repeat(15) { press(Key.DirectionDown) }
        assertEquals("grid:rail:P", focusedTag())
        press(Key.DirectionDown)
        assertEquals("grid:rail:R", focusedTag())
        press(Key.DirectionCenter)
        val first = titles.indexOfFirst { jumpLetter(it.sortName!!) == "R" }
        assertTrue(first >= 60, "R starts after the first page ($first)")
        assertEquals("grid:film-$first", focusedTag())
        compose.onNodeWithTag("grid:film-$first").assertIsDisplayed()
        assertEquals("${first + 1} / 330", position())
        // The list stayed whole: every title before R is loaded, in order.
        assertEquals(titles.take(first + 1).map { it.id }, vm.state.value.items.take(first + 1).map { it.id })
    }

    @Test fun theRailHoldsAtItsEndsAndLeftGoesBackToTheCard() {
        show()
        repeat(5) { press(Key.DirectionRight) }
        press(Key.DirectionRight)
        assertEquals("grid:rail:A", focusedTag())
        press(Key.DirectionUp)
        assertEquals("grid:rail:#", focusedTag())
        press(Key.DirectionUp)
        assertEquals("grid:rail:#", focusedTag())
        press(Key.DirectionRight)
        assertEquals("grid:rail:#", focusedTag())
        press(Key.DirectionLeft)
        assertEquals("grid:film-5", focusedTag())
    }

    @Test fun descendingReadsTheServersOwnOrder() {
        show()
        vm.sort("SortName", true)
        settle()
        compose.onNodeWithTag("grid:film-329").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        repeat(5) { press(Key.DirectionRight) }
        assertEquals("grid:film-324", focusedTag())
        press(Key.DirectionRight)
        // The last titles come first, and the rail starts on their letter.
        assertEquals("grid:rail:Z", focusedTag())
        press(Key.DirectionDown)
        assertEquals("grid:rail:Z", focusedTag())
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        // Descending, Y's first title in the grid is the last Y by name.
        assertEquals("grid:${titles.last { jumpLetter(it.sortName!!) == "Y" }.id}", focusedTag())
    }

    @Test fun noRailUnlessSortedByName() {
        show()
        compose.onNodeWithTag("grid:rail").assertIsDisplayed()
        vm.sort("DateCreated", true)
        settle()
        compose.onNodeWithTag("grid:rail").assertDoesNotExist()
        assertEquals(1, library.sortNamesAsked, "a date sort reads no sort names")
    }
}
