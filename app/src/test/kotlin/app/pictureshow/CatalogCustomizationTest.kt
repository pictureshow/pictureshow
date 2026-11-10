/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.HomePreferences
import app.pictureshow.core.ItemPage
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.LibraryFacets
import app.pictureshow.core.LibraryFilters
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogCustomizationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Cinema", "https://media.example.test", "12.0.0"), User("v", "Viewer"), "fake")
    private val library = MediaItem("lib", "Cinema", "CollectionFolder", collectionType = "movies", isFolder = true)
    private val service = EditorCatalog()

    @Test fun homeBuilderShowsCurrentChoiceAndPersistsReversibleRowEdits() {
        var preferences by mutableStateOf(HomePreferences())
        var closed = false
        compose.setContent {
            MaterialTheme {
                HomeCustomization(
                    CatalogHomeState(loading = false, libraries = listOf(library), preferences = preferences),
                    account,
                    service,
                    { change -> preferences = change(preferences) },
                    { closed = true }
                )
            }
        }
        compose.onNodeWithTag("home:visibility:resume").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithTag("home:combine").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(preferences.mergeWatching) }
        compose.onNodeWithTag("home:combine").assertIsDisplayed()
        compose.onNodeWithTag("home:row:resume").assertDoesNotExist()
        compose.onNodeWithTag("home:row:nextup").assertDoesNotExist()
        compose.onNodeWithText("Hide Continue watching and next up").performScrollTo().performClick()
        compose.runOnIdle { assertTrue("watching" in preferences.hidden) }
        compose.onNodeWithText("Show Continue watching and next up").performClick()
        compose.runOnIdle { assertFalse("watching" in preferences.hidden) }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun pickedRowMovesWithDpadAndSavesOnlyOnDrop() {
        var preferences by mutableStateOf(HomePreferences())
        compose.setContent {
            MaterialTheme {
                HomeCustomization(
                    CatalogHomeState(loading = false, preferences = preferences),
                    account,
                    service,
                    { preferences = it(preferences) },
                    {}
                )
            }
        }
        compose.onNodeWithTag("home:reorder:resume").performClick()
        compose.onNodeWithTag("home:reorder:resume").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertTrue(preferences.order.isEmpty(), "Moving a row must not save until drop") }
        compose.onNodeWithTag("home:reorder:resume").performClick()
        compose.runOnIdle { assertEquals(listOf("nextup", "resume", "libraries"), preferences.order) }
    }

    @Test fun retryOnlyAppearsAfterARealSaveFailure() {
        var screen by mutableStateOf(CatalogHomeState(loading = false))
        var retried = false
        compose.setContent { MaterialTheme { HomeCustomization(screen, account, service, { retried = true }, {}) } }
        compose.onNodeWithTag("home:retry-save").assertDoesNotExist()
        compose.runOnIdle {
            screen =
                screen.copy(preferenceStatus = "Couldn’t save to your server. Saved on this device.", preferenceRetryable = true)
        }
        compose.onNodeWithTag("home:retry-save").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(retried)
            screen = screen.copy(preferenceStatus = null, preferenceRetryable = false, saving = true)
        }
        compose.onNodeWithText("Saving layout…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("home:retry-save").assertDoesNotExist()
    }

    @Test fun emptySavedPinUsesGenericEditorLabelAndRemainsRemovable() {
        val pin = app.pictureshow.core.HomePin(app.pictureshow.core.HomePinKind.STUDIO, "Unavailable studio")
        var preferences by mutableStateOf(HomePreferences(pins = listOf(pin)))
        compose.setContent {
            MaterialTheme {
                HomeCustomization(
                    CatalogHomeState(
                        preferences = preferences,
                        availableRows = listOf(app.pictureshow.core.HomeRow(pin.key, "Unavailable studio", emptyList()))
                    ),
                    account,
                    service,
                    { transform -> preferences = transform(preferences) },
                    {}
                )
            }
        }
        compose.onNodeWithTag("home:overflow:${pin.key}").performScrollTo().performClick()
        compose.onNodeWithText("Unpin").performClick()
        compose.runOnIdle { assertTrue(preferences.pins.isEmpty()) }
    }

    @Test fun filtersApplyDesiredCombinationAndClearRestoresUnfilteredQuery() {
        var applied: LibraryFilters? = null
        compose.setContent {
            MaterialTheme {
                LibraryFilterEditor(CatalogGridState(account, library), service, { applied = it }, {})
            }
        }
        compose.onNodeWithTag("filters:unwatched").assertIsFocused()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithTag("filters:unwatched").performScrollTo().performClick()
        compose.onNodeWithTag("filters:favorites").performScrollTo().performClick()
        compose.onNodeWithText("Apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LibraryFilters(unwatched = true, favorite = true), applied) }
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(LibraryFilters(), applied) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun selectedGenreSurvivesLazyDisposalAndOtherFacetEdits() {
        service.facet = { ClientResult.Success(LibraryFacets(genres = (1..60).map { "Genre $it" }, years = listOf(2026))) }
        var applied: LibraryFilters? = null
        compose.setContent { MaterialTheme { LibraryFilterEditor(CatalogGridState(account, library), service, { applied = it }, {}) } }
        repeat(3) {
            compose.onNodeWithTag("filters:genre").performScrollTo().performClick()
            compose.onNodeWithText("Genre 1").performScrollTo().performClick()
            compose.onNodeWithTag("filters:year").performScrollTo().performClick()
            compose.onNodeWithText("2020–2029").performScrollTo().performClick()
            compose.onNodeWithText("2026").performScrollTo().performClick()
            compose.onNodeWithTag("filters:genre").performScrollTo().performClick()
            compose.onNodeWithText("Genre 1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
            compose.onNodeWithText("Genre 1").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.runOnIdle { assertEquals(LibraryFilters(genre = "Genre 1", year = 2026), applied) }
        }
    }

    @Test fun lateFailedChoiceCannotReplaceNewSuccessfulSearch() {
        val old = CompletableDeferred<ClientResult<ItemPage>>()
        service.named = { term ->
            if (term.isEmpty()) {
                withContext(NonCancellable) { old.await() }
            } else {
                ClientResult.Success(ItemPage(listOf(MediaItem("new", "New permitted choice")), 1))
            }
        }
        compose.setContent { MaterialTheme { NamedCatalogPicker(account, service, "Genres", select = {}, close = {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("new")
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("New permitted choice").assertIsDisplayed()
        compose.runOnIdle { old.complete(ClientResult.Failure(ClientError(ErrorKind.SERVER, "Old request failed"))) }
        compose.waitForIdle()
        compose.onNodeWithText("New permitted choice").assertIsDisplayed()
        compose.onNodeWithText("Old request failed").assertDoesNotExist()
    }

    @Test fun lateFacetFailureCannotReplaceNewLibraryChoices() {
        val old = CompletableDeferred<ClientResult<LibraryFacets>>()
        var parent by mutableStateOf(library)
        service.facet = { id ->
            if (id == "lib") {
                withContext(NonCancellable) { old.await() }
            } else {
                ClientResult.Success(LibraryFacets(genres = listOf("New library genre")))
            }
        }
        compose.setContent { MaterialTheme { LibraryFilterEditor(CatalogGridState(account, parent), service, {}, {}) } }
        compose.runOnIdle { parent = library.copy(id = "new") }
        // The replacement result is below the lazy viewport: find it through the list, then
        // release the old noncancellable failure only after the new choices are displayed.
        compose.onNodeWithTag("filters:genre").performScrollTo().performClick()
        compose.onNodeWithText("New library genre").assertIsDisplayed()
        compose.runOnIdle { old.complete(ClientResult.Failure(ClientError(ErrorKind.SERVER, "Old facets failed"))) }
        compose.onNodeWithText("New library genre").assertIsDisplayed()
        compose.onNodeWithText("New library genre").performClick()
        compose.onNodeWithTag("filters:unwatched").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Old facets failed").assertDoesNotExist()
    }
}

private class EditorCatalog : CatalogService {
    var named: suspend (String) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
    var facet: suspend (String) -> ClientResult<LibraryFacets> = { ClientResult.Success(LibraryFacets()) }
    override suspend fun namedItems(account: Account, kind: String, parentId: String, term: String, startIndex: Int) = named(term)
    override suspend fun facets(account: Account, parentId: String) = facet(parentId)
    override suspend fun views(account: Account) = ClientResult.Success(ItemPage())
    override suspend fun resume(account: Account) = ClientResult.Success(ItemPage())
    override suspend fun nextUp(account: Account, seriesId: String?) = ClientResult.Success(ItemPage())
    override suspend fun latest(account: Account, parentId: String) = ClientResult.Success(emptyList<MediaItem>())
    override suspend fun items(account: Account, query: app.pictureshow.core.BrowseQuery) = ClientResult.Success(ItemPage())
    override suspend fun item(account: Account, id: String) = ClientResult.Success(MediaItem(id))
    override suspend fun seasons(account: Account, seriesId: String) = ClientResult.Success(ItemPage())
    override suspend fun episodes(account: Account, seriesId: String, seasonId: String) = ClientResult.Success(ItemPage())
    override suspend fun played(account: Account, id: String, value: Boolean) = ClientResult.Success(ItemUserData())
    override suspend fun favorite(account: Account, id: String, value: Boolean) = ClientResult.Success(ItemUserData())
}
