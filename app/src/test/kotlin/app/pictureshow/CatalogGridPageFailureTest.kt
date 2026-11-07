/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The next page fails for real here: the view model's own load gets the failure and its own retry asks again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogGridPageFailureTest {
    @get:Rule(order = 0)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val stores = mutableListOf<ViewModelStore>()
    private val message = "The server took too long to respond."
    private val failure = ClientResult.Failure(ClientError(ErrorKind.NETWORK, message))

    private fun film(index: Int) = MediaItem("film-$index", "Film", "Movie")

    /** 30 cards of 60: the page after them is what fails. */
    private inner class Pages : CatalogService by CatalogModelsTest.FakeCatalog() {
        val asked = mutableListOf<Int>()
        var next: suspend () -> ClientResult<ItemPage> = { failure }
        override suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage> {
            asked += query.startIndex
            return if (query.startIndex == 0) ClientResult.Success(ItemPage((0 until 30).map(::film), 60)) else next()
        }
    }

    private val pages = Pages()
    private var retries = 0
    private val secondPage = ClientResult.Success(ItemPage((30 until 60).map(::film), 60))

    @After fun cleanup() = stores.forEach(ViewModelStore::clear)

    private fun show() {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val vm = CatalogGridViewModel(account, MediaItem("library", "Films", collectionType = "movies"), CatalogSession(pages, accounts))
        stores += ViewModelStore().apply { put("vm", vm) }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme {
                CatalogGrid(state, {}, vm::nearEnd, { sort, descending -> vm.sort(sort, descending) }, {
                    retries++
                    vm.retry()
                })
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

    /** Down to the last row, column kept, as a viewer gets there; the visit near the end is what asks for the page. */
    private fun walkToLastRow() {
        var guard = 0
        while (focusedTag().substringAfter("film-").toInt() < 25) {
            press(Key.DirectionDown)
            assertTrue(guard++ < 20, "Never reached the last row")
        }
    }

    private fun assertOnCardInColumnAfterRetry(from: String) {
        val columns = 5
        val tag = focusedTag()
        assertTrue(tag.startsWith("grid:film-"), "Focus on $tag")
        val index = tag.substringAfter("film-").toInt()
        assertEquals(from.substringAfter("film-").toInt() + columns, index, "Lands in the column on the new cards")
    }

    @Test fun downFromTheLastRowLandsOnRetryAndAReaderGetsTheMessage() {
        show()
        walkToLastRow()
        assertEquals(listOf(0, 30), pages.asked)
        press(Key.DirectionDown)
        assertEquals("grid:page-retry", focusedTag())
        compose.onNodeWithTag("grid:page-retry").assertIsDisplayed()
        val text = compose.onNodeWithTag("grid:page-failure")
        text.assertIsDisplayed()
        assertEquals(message, text.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""))
        assertEquals(LiveRegionMode.Polite, text.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        assertTrue(!text.fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
    }

    @Test fun aRetryThatSucceedsLandsInTheColumnOnTheNewCards() {
        show()
        walkToLastRow()
        val from = focusedTag()
        press(Key.DirectionDown)
        pages.next = { secondPage }
        press(Key.DirectionCenter)
        assertOnCardInColumnAfterRetry(from)
        compose.onNodeWithTag("grid:page-retry").assertDoesNotExist()
        compose.onNodeWithTag("grid:page-failure").assertDoesNotExist()
    }

    @Test fun aRetryThatFailsStaysOnRetryAndCanBePressedAgain() {
        show()
        walkToLastRow()
        val from = focusedTag()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals("grid:page-retry", focusedTag())
        assertEquals(listOf(0, 30, 30), pages.asked)
        compose.onNodeWithTag("grid:page-failure").assertIsDisplayed()
        pages.next = { secondPage }
        press(Key.DirectionCenter)
        assertOnCardInColumnAfterRetry(from)
    }

    @Test fun retryKeepsFocusWhileTheRetryIsInFlight() {
        show()
        walkToLastRow()
        val from = focusedTag()
        press(Key.DirectionDown)
        val answer = CompletableDeferred<ClientResult<ItemPage>>()
        pages.next = { answer.await() }
        press(Key.DirectionCenter)
        assertEquals("grid:page-retry", focusedTag())
        val line = compose.onNodeWithTag("grid:page-failure").fetchSemanticsNode().config[SemanticsProperties.Text]
        assertEquals("Loading…", line.joinToString(""))
        press(Key.DirectionCenter)
        assertEquals(listOf(0, 30, 30), pages.asked, "A second press while one is in flight asks for nothing")
        assertEquals(1, retries, "and doesn't reach the view model")
        answer.complete(secondPage)
        settle()
        assertOnCardInColumnAfterRetry(from)
    }

    // A reader moves focus itself, so Retry's own keys never see the viewer leave.
    private fun retryLandsAfterFocusMovedTo(tag: String, scrollTo: Int? = null) {
        show()
        walkToLastRow()
        press(Key.DirectionDown)
        val answer = CompletableDeferred<ClientResult<ItemPage>>()
        pages.next = { answer.await() }
        press(Key.DirectionCenter)
        assertEquals("grid:page-retry", focusedTag())
        // The header leaves composition once scrolled away, so a reader or a touch would scroll it back first.
        if (scrollTo != null) compose.onNodeWithTag("grid:items").performScrollToIndex(scrollTo)
        settle()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        assertEquals(tag, focusedTag())
        answer.complete(secondPage)
        settle()
        assertEquals(tag, focusedTag(), "The page that arrived took focus")
    }

    @Test fun aRetryThatLandsAfterTheViewerMovedToACardLeavesFocusThere() = retryLandsAfterFocusMovedTo("grid:film-20")

    @Test fun aRetryThatLandsAfterTheViewerMovedToTheHeaderLeavesFocusThere() = retryLandsAfterFocusMovedTo("grid:sort-order", scrollTo = 0)

    @Test fun anEmptyPageOnRetryReturnsToTheCard() {
        show()
        walkToLastRow()
        val from = focusedTag()
        press(Key.DirectionDown)
        pages.next = { ClientResult.Success(ItemPage(emptyList(), 30)) }
        press(Key.DirectionCenter)
        assertEquals(from, focusedTag())
        compose.onNodeWithTag("grid:page-retry").assertDoesNotExist()
    }

    @Test fun upFromRetryReturnsToTheCardItCameFrom() {
        show()
        // A column away from Retry, so a nearest-thing search would land somewhere else.
        repeat(3) { press(Key.DirectionRight) }
        walkToLastRow()
        val from = focusedTag()
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertEquals(from, focusedTag())
        press(Key.DirectionDown)
        assertEquals("grid:page-retry", focusedTag())
    }

    @Test fun upFromTheFirstRowStillReachesTheSelectedSortWithAPageFailed() {
        show()
        repeat(3) { press(Key.DirectionRight) }
        walkToLastRow()
        press(Key.DirectionDown)
        assertEquals("grid:page-retry", focusedTag())
        press(Key.DirectionUp)
        var guard = 0
        while (focusedTag().substringAfter("film-").toInt() > 4) {
            press(Key.DirectionUp)
            assertTrue(guard++ < 20, "Never reached the first row")
        }
        press(Key.DirectionUp)
        assertEquals("grid:sort:SortName", focusedTag())
    }

    @Test fun downOnRetryStaysThere() {
        show()
        walkToLastRow()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals("grid:page-retry", focusedTag())
    }

    @Test fun theHeaderKeepsOnlyAFirstLoadsError() {
        pages.next = { failure }
        show()
        walkToLastRow()
        var guard = 0
        while (focusedTag().startsWith("grid:film")) {
            press(Key.DirectionUp)
            assertTrue(guard++ < 20, "Never left the cards")
        }
        assertEquals("grid:sort:SortName", focusedTag(), "Up from the first row still lands on the selected sort")
        assertEquals(0, compose.onAllNodes(hasText("Retry")).fetchSemanticsNodes().size, "No Retry in the header for a later page")
    }

    @Test fun aFailureWhileDownIsHeldLandsOnRetry() {
        val answer = CompletableDeferred<ClientResult<ItemPage>>()
        pages.next = { answer.await() }
        show()
        walkToLastRow()
        val before = focusedTag()
        // Still loading: the press waits for the page. The clock stops, so the wait's cap can't be what ends it.
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(before, focusedTag())
        answer.complete(failure)
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("grid:page-retry", focusedTag())
    }

    @Test fun retryIsWholeOnScreen() {
        show()
        walkToLastRow()
        press(Key.DirectionDown)
        val retry = compose.onNodeWithTag("grid:page-retry").getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(retry.bottom <= root.bottom && retry.top >= root.top, "Retry cut: $retry in $root")
    }
}
