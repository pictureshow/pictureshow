/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

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

    @Before fun matchUiModeServiceToDeviceConfiguration() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
    }

    private fun backFromTopDialog() {
        compose.runOnIdle {
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
    }

    @Test fun savingAndFailureNeverMoveTheHomeRows() = stableHomeRows()

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchSnackbarNeverMovesTheHomeRows() = stableHomeRows()

    private fun stableHomeRows() {
        var state by mutableStateOf(CatalogHomeState(loading = false))
        compose.setContent { MaterialTheme { HomeCustomization(state, account, service, {}, {}) } }
        val before = compose.onNodeWithTag("home:row:resume").getUnclippedBoundsInRoot()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Saving layout…").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("home:row:resume").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            state =
                state.copy(
                    saving = false,
                    preferenceStatus = "Couldn't save to your server. Saved on this device.",
                    preferenceRetryable = true
                )
        }
        compose.onNodeWithTag("home:retry-save").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("home:row:resume").getUnclippedBoundsInRoot())
        compose.runOnIdle { state = state.copy(preferenceStatus = null, preferenceRetryable = false) }
        assertEquals(before, compose.onNodeWithTag("home:row:resume").getUnclippedBoundsInRoot())
    }

    @Test fun tvReorderHandlesShareAColumnWithAndWithoutOverflow() = handleColumns(tv = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchReorderHandlesShareAColumnWithAndWithoutOverflow() = handleColumns(tv = false)

    private fun handleColumns(tv: Boolean) {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    listOf("builtin", "pin").forEach { key ->
                        HomeReorderRow(
                            key, key, true, tv, false, true, false, true, {}, {}, {}, {},
                            unpin = if (key == "pin") ({}) else null
                        )
                    }
                }
            }
        }
        val a = compose.onNodeWithTag("home:handle:builtin", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag("home:handle:pin", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(a.left, b.left)
        assertEquals(a.right, b.right)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun touchResetIsACompactNativeAlertWithTrailingActions() {
        compose.setContent { androidx.compose.material3.MaterialTheme { HomeLayoutResetConfirmation({}, {}) } }
        compose.onNodeWithText("Restore default layout?").assertIsDisplayed()
        compose.onNodeWithTag("catalog:editor").assertDoesNotExist()
        val cancel = compose.onNodeWithTag("home:reset-cancel").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithTag("home:reset-confirm").getUnclippedBoundsInRoot()
        assertTrue(cancel.right <= confirm.left)
    }

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
        compose.onNodeWithTag("home:reorder:resume").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithTag("home:combine").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(preferences.mergeWatching) }
        compose.onNodeWithTag("home:combine").assertIsDisplayed()
        compose.onNodeWithTag("home:row:resume").assertDoesNotExist()
        compose.onNodeWithTag("home:row:nextup").assertDoesNotExist()
        compose.onNodeWithTag("home:visibility:watching").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue("watching" in preferences.hidden) }
        compose.onNodeWithText("Hidden").assertIsDisplayed()
        compose.onNodeWithTag("home:visibility:watching").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertFalse("watching" in preferences.hidden) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
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
        compose.onNodeWithTag("home:reorder:resume").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Moving · Up/Down · OK to drop").assertIsDisplayed()
        compose.onNodeWithTag("home:visibility:resume").assertIsNotEnabled()
        compose.onNodeWithTag("home:reorder:nextup").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsActions.CustomActions, emptyList()))
        compose.onNodeWithTag("home:reorder:resume").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("home:reorder:resume").performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle { assertTrue(preferences.order.isEmpty(), "Moving a row must not save until drop") }
        compose.onNodeWithTag("home:reorder:resume").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("nextup", "resume", "libraries"), preferences.order) }
        compose.onNodeWithTag("home:reorder:nextup").assertIsEnabled()
            .assert(
                SemanticsMatcher("Top row regains only the valid movement direction") {
                    val labels = it.config[SemanticsActions.CustomActions].map { action -> action.label }
                    "Move down" in labels && "Move up" !in labels
                }
            )
    }

    @Test fun repeatedNativeMovesKeepThePickedRowAndItsNeighborVisibleUntilDrop() {
        var preferences by mutableStateOf(HomePreferences())
        val libraries = (1..12).map { library.copy(id = "library-$it", name = "Library $it") }
        compose.setContent {
            MaterialTheme {
                HomeCustomization(
                    CatalogHomeState(loading = false, libraries = libraries, preferences = preferences),
                    account,
                    service,
                    { preferences = it(preferences) },
                    {}
                )
            }
        }
        val row = compose.onNodeWithTag("home:reorder:resume")
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        row.performSemanticsAction(SemanticsActions.OnClick)
        row.performKeyInput { pressKey(Key.DirectionDown) }
        row.assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("home:row:nextup").assertIsDisplayed()
        val scroll = compose.onNodeWithTag("catalog:editor").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertEquals(0f, scroll.value(), "Moving a visible row must preserve its surrounding viewport") }
        repeat(7) {
            row.performKeyInput { pressKey(Key.DirectionDown) }
            row.assertIsDisplayed().assertIsFocused()
        }
        repeat(2) {
            row.performKeyInput { pressKey(Key.DirectionUp) }
            row.assertIsDisplayed().assertIsFocused()
        }
        compose.runOnIdle { assertTrue(preferences.order.isEmpty()) }
        row.performSemanticsAction(SemanticsActions.OnClick)
        row.assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf("nextup", "libraries", "latest:library-1", "latest:library-2", "latest:library-3", "latest:library-4", "resume"),
                preferences.order.take(7)
            )
        }
    }

    @Test fun pickedPinKeepsVisibilityAndUnpinDisabledUntilDrop() {
        val pin = app.pictureshow.core.HomePin(app.pictureshow.core.HomePinKind.FAVORITES)
        var preferences by mutableStateOf(HomePreferences(pins = listOf(pin)))
        compose.setContent {
            MaterialTheme {
                HomeCustomization(CatalogHomeState(loading = false, preferences = preferences), account, service, {
                    preferences =
                        it(preferences)
                }, {})
            }
        }
        compose.onNodeWithTag("home:reorder:${pin.key}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Moving · Up/Down · OK to drop").assertIsDisplayed()
        compose.onNodeWithTag("home:visibility:${pin.key}").assertIsNotEnabled()
        compose.onNodeWithTag("home:overflow:${pin.key}").assertIsNotEnabled()
        compose.onNodeWithTag("home:reorder:${pin.key}").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("home:visibility:${pin.key}").assertIsEnabled()
        compose.onNodeWithTag("home:overflow:${pin.key}").assertIsEnabled()
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
        compose.onNodeWithTag("home:retry-save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertTrue(retried)
            screen = screen.copy(preferenceStatus = null, preferenceRetryable = false, saving = true)
        }
        compose.onNodeWithTag("catalog:editor").performScrollToIndex(0)
        compose.onNodeWithText("Saving layout…").assertIsDisplayed()
        compose.onNodeWithTag("home:reorder:resume")
            .assert(SemanticsMatcher.expectValue(SemanticsActions.CustomActions, emptyList()))
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
        compose.onNodeWithTag("home:overflow:${pin.key}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        backFromTopDialog()
        compose.onNodeWithTag("home:overflow:${pin.key}").assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Row options").assertDoesNotExist()
        compose.onNodeWithTag("home:unpin").assertIsFocused().assert(
            SemanticsMatcher("Unpin is a command rather than a radio choice") {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    !it.config.contains(SemanticsProperties.Selected) &&
                    !it.config.contains(SemanticsProperties.ToggleableState)
            }
        ).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(preferences.pins.isEmpty()) }
    }

    @Test fun resetRequiresConfirmationAndCancelPreservesLayout() {
        val before = HomePreferences(
            hidden = setOf("resume"),
            landing = "libraries",
            libraries = mapOf("lib" to app.pictureshow.core.LibraryDisplay("DateCreated", descending = true, list = true))
        )
        var preferences by mutableStateOf(before)
        compose.setContent {
            MaterialTheme {
                HomeCustomization(CatalogHomeState(loading = false, preferences = preferences), account, service, {
                    preferences = it(preferences)
                }, {})
            }
        }
        compose.onNodeWithTag("catalog:editor").performScrollToNode(hasTestTag("home:reset-opener"))
        compose.onNodeWithTag("home:reset-opener").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(before, preferences) }
        val cancel = compose.onNodeWithTag("home:reset-cancel").getUnclippedBoundsInRoot()
        val restore = compose.onNodeWithTag("home:reset-confirm").getUnclippedBoundsInRoot()
        assertTrue(restore.right < cancel.left, "Primary Restore precedes content-sized Cancel")
        assertTrue(restore.right - restore.left < 160.dp, "Restore sizes to its content")
        assertTrue(cancel.right - cancel.left < 160.dp, "Cancel sizes to its content")
        assertEquals(cancel.top, restore.top)
        compose.onNodeWithTag("home:reset-cancel").assert(
            SemanticsMatcher("Cancel is a command, not a selection") {
                !it.config.contains(SemanticsProperties.Selected)
            }
        ).assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(before, preferences) }
        compose.onNodeWithTag("home:reset-opener").assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        backFromTopDialog()
        compose.onNodeWithTag("home:reset-opener").assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("home:reset-confirm").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(HomePreferences(libraries = before.libraries), preferences) }
    }

    @Test fun nextUpExplainsRemovedStateAndAvoidsRedundantBulkRestore() {
        var preferences by mutableStateOf(HomePreferences(hiddenSeries = setOf("series")))
        val episode = MediaItem("episode", "Episode", "Episode", seriesId = "series", seriesName = "The series")
        compose.setContent {
            MaterialTheme {
                HomeCustomization(
                    CatalogHomeState(
                        loading = false,
                        preferences = preferences,
                        availableRows = listOf(app.pictureshow.core.HomeRow("nextup", "Next up", listOf(episode)))
                    ),
                    account,
                    service,
                    { preferences = it(preferences) },
                    {}
                )
            }
        }
        compose.onNodeWithTag("catalog:editor").performScrollToNode(hasTestTag("home:nextup:series"))
        compose.onNodeWithText("Removed from Next up").assertIsDisplayed()
        compose.onNodeWithText("Restore all removed series").assertDoesNotExist()
        compose.onNodeWithTag("home:nextup:series").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Shown in Next up").assertIsDisplayed()
        compose.runOnIdle { assertTrue(preferences.hiddenSeries.isEmpty()) }
    }

    @Test fun homePinPickerUsesPinnedStatusInsteadOfRadioChoice() {
        service.named = { ClientResult.Success(ItemPage(listOf(MediaItem("c", "My collection", childCount = 2)), 1)) }
        service.detail = { ClientResult.Success(MediaItem("c", "My collection", childCount = 2)) }
        compose.setContent {
            MaterialTheme {
                NamedCatalogPicker(account, service, "BoxSet", select = {}, close = {}, selectedKeys = setOf("c"), pickingHomeRows = true)
            }
        }
        compose.onNodeWithText("On your home · 2 titles").assertIsDisplayed()
        compose.onNodeWithTag("catalog:choice:c").assert(
            SemanticsMatcher("Pinned item has a check/status rather than a radio role") {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    !it.config.contains(SemanticsProperties.Selected) &&
                    !it.config.contains(SemanticsProperties.ToggleableState)
            }
        )
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
        compose.onNodeWithTag("filters:unwatched").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("filters:favorites").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Apply").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(LibraryFilters(unwatched = true, favorite = true), applied) }
        compose.onNodeWithText("Clear all").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Apply").performSemanticsAction(SemanticsActions.OnClick)
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
            compose.onNodeWithTag("catalog:option:1").performScrollTo().performClick()
            compose.onNodeWithTag("filters:year").performScrollTo().performClick()
            compose.onNodeWithText("2020–2029").performScrollTo().performClick()
            compose.onNodeWithTag("catalog:option:0").performScrollTo().performClick()
            compose.onNodeWithTag("filters:genre").performScrollTo().performClick()
            compose.onNodeWithTag("catalog:option:1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
            compose.onNodeWithTag("catalog:option:1").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.runOnIdle { assertEquals(LibraryFilters(genre = "Genre 1", year = 2026), applied) }
        }
    }

    @Test fun selectedPersonOutsideFirstPageIsAuthorizedMarkedAndFocused() {
        service.named = { ClientResult.Success(ItemPage(listOf(MediaItem("first", "First person", "Person")), 200)) }
        service.detail = { id -> ClientResult.Success(MediaItem(id, "Selected performer", "Person")) }
        compose.setContent {
            MaterialTheme {
                NamedCatalogPicker(account, service, "Persons", library.id, {}, {}, setOf("saved"))
            }
        }
        compose.onNodeWithTag("catalog:choice:saved").assertIsDisplayed().assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("Selected performer").assertIsDisplayed()
        compose.onNodeWithTag("catalog:choice:first").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test fun tvNamedPickerHasNoEditableFieldUntilSearchIsActivated() {
        service.named = { ClientResult.Success(ItemPage(listOf(MediaItem("p", "A person")), 1)) }
        compose.setContent { MaterialTheme { NamedCatalogPicker(account, service, "Persons", select = {}, close = {}) } }
        compose.onNodeWithTag("catalog:choice:p").assertIsFocused()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithTag("catalog:editor").performScrollToIndex(0)
        compose.onNodeWithTag("catalog:search").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
    }

    @Test fun tvEmptySearchReturnsFocusToSearchActionOnBack() {
        compose.setContent { MaterialTheme { NamedCatalogPicker(account, service, "Persons", select = {}, close = {}) } }
        compose.onNodeWithTag("catalog:search").assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).performTextInput("nobody")
        compose.mainClock.advanceTimeBy(350)
        backFromTopDialog()
        compose.onNodeWithTag("catalog:search").assertIsFocused()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("No matches for “nobody”").assertIsDisplayed()
    }

    @Test fun unresolvedCurrentPersonStillOffersAnyAsAFocusTargetWithoutClearing() {
        val pending = CompletableDeferred<ClientResult<ItemPage>>()
        service.named = { pending.await() }
        service.detail = { ClientResult.Failure(ClientError(ErrorKind.SERVER, "Not found")) }
        var cleared = false
        compose.setContent {
            MaterialTheme {
                NamedCatalogPicker(account, service, "Persons", select = {}, close = {}, selectedKeys = setOf("missing"), selectAny = {
                    cleared =
                        true
                })
            }
        }
        compose.onNodeWithTag("catalog:choice:any").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithText("Untitled").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(cleared)
            pending.complete(ClientResult.Success(ItemPage(emptyList(), 0)))
        }
        compose.onNodeWithTag("catalog:choice:any").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.runOnIdle { assertFalse(cleared) }
    }

    @Test fun personAnyClearsOnlyThePersonConstraint() {
        var applied: LibraryFilters? = null
        val before = LibraryFilters(genre = "Animation", personId = "p", unwatched = true)
        compose.setContent {
            MaterialTheme {
                LibraryFilterEditor(CatalogGridState(account, library, filters = before), service, {
                    applied =
                        it
                }, {})
            }
        }
        compose.onNodeWithTag("catalog:editor").performScrollToNode(hasTestTag("filters:person"))
        compose.onNodeWithTag("filters:person").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("catalog:choice:any").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("filters:person").assertIsFocused()
        compose.onNodeWithTag("filters:apply").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(before.copy(personId = null), applied) }
    }

    @Test fun deniedLibraryCannotResolveSavedPersonMetadata() {
        service.detail = { id ->
            if (id == library.id) {
                ClientResult.Failure(ClientError(ErrorKind.SERVER, "Denied"))
            } else {
                ClientResult.Success(MediaItem(id, "Previously selected person", "Person"))
            }
        }
        compose.setContent {
            MaterialTheme { NamedCatalogPicker(account, service, "Persons", library.id, {}, {}, setOf("saved")) }
        }
        compose.onNodeWithText("Previously selected person").assertDoesNotExist()
        compose.onNodeWithTag("catalog:choice:saved").assertDoesNotExist()
    }

    @Test fun pendingSearchRemovesPreviouslySelectableChoices() {
        val pending = CompletableDeferred<ClientResult<ItemPage>>()
        service.named = { term ->
            if (term.isEmpty()) ClientResult.Success(ItemPage(listOf(MediaItem("old", "Old choice")), 1)) else pending.await()
        }
        compose.setContent { MaterialTheme { NamedCatalogPicker(account, service, "Genres", select = {}, close = {}) } }
        compose.onNodeWithText("Old choice").assertIsDisplayed()
        compose.onNodeWithTag("catalog:editor").performScrollToIndex(0)
        compose.onNodeWithTag("catalog:search").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).performTextInput("replacement")
        compose.onNodeWithText("Old choice").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("Old choice").assertDoesNotExist()
        compose.runOnIdle { pending.complete(ClientResult.Success(ItemPage(listOf(MediaItem("new", "Replacement choice")), 1))) }
        compose.onNodeWithText("Replacement choice").assertIsDisplayed()
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
        compose.onNodeWithTag("catalog:editor").performScrollToIndex(0)
        compose.onNodeWithTag("catalog:search").performSemanticsAction(SemanticsActions.OnClick)
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
        compose.onNodeWithTag("filters:genre").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("tv-settings:option:1").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { old.complete(ClientResult.Failure(ClientError(ErrorKind.SERVER, "Old facets failed"))) }
        compose.onNodeWithTag("tv-settings:option:1").assertIsDisplayed()
        compose.onNodeWithTag("tv-settings:option:1").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("filters:unwatched").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Old facets failed").assertDoesNotExist()
    }
}

private class EditorCatalog : CatalogService {
    var named: suspend (String) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
    var detail: suspend (String) -> ClientResult<MediaItem> = { ClientResult.Success(MediaItem(it)) }
    var facet: suspend (String) -> ClientResult<LibraryFacets> = { ClientResult.Success(LibraryFacets()) }
    override suspend fun namedItems(account: Account, kind: String, parentId: String, term: String, startIndex: Int) = named(term)
    override suspend fun facets(account: Account, parentId: String) = facet(parentId)
    override suspend fun views(account: Account) = ClientResult.Success(ItemPage())
    override suspend fun resume(account: Account) = ClientResult.Success(ItemPage())
    override suspend fun nextUp(account: Account, seriesId: String?) = ClientResult.Success(ItemPage())
    override suspend fun latest(account: Account, parentId: String) = ClientResult.Success(emptyList<MediaItem>())
    override suspend fun items(account: Account, query: app.pictureshow.core.BrowseQuery) = ClientResult.Success(ItemPage())
    override suspend fun item(account: Account, id: String) = detail(id)
    override suspend fun seasons(account: Account, seriesId: String) = ClientResult.Success(ItemPage())
    override suspend fun episodes(account: Account, seriesId: String, seasonId: String) = ClientResult.Success(ItemPage())
    override suspend fun played(account: Account, id: String, value: Boolean) = ClientResult.Success(ItemUserData())
    override suspend fun favorite(account: Account, id: String, value: Boolean) = ClientResult.Success(ItemUserData())
}
