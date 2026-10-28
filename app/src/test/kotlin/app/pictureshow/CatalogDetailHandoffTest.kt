/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogDetailHandoffTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun completionWaitsForTheFreshTargetAndItsActualFocusRequest() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(
            CatalogDetailState(account, item, loading = true, playbackPending = true, playbackReportFinished = true)
        )
        val finished = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {}, onFocusRestored = { target, focused -> finished += target to focused })
            }
        }
        compose.mainClock.advanceTimeBy(8_000)
        compose.runOnIdle { assertTrue(finished.isEmpty()) }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("primary" to true), finished) }
    }

    @Test fun pendingRightMovesFromTheFreshPrimaryInsteadOfBeingLost() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {}, onFocusRestored = { target, focused ->
                    handoff.finish("entry", target, focused)
                })
            }
        }
        compose.runOnIdle {
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                val event = android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(
                    handoff.defer(event.keyCode, action == android.view.KeyEvent.ACTION_DOWN, 0) {
                        compose.activity.dispatchKeyEvent(event)
                    }
                )
            }
        }
        compose.mainClock.advanceTimeBy(8_000)
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:played").assertIsFocused()
    }

    @Test fun anEmptyContainerReportsTheActualFavoriteTarget() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("series", "Invented empty series", "Series", childCount = 0)
        val finished = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, item, loading = false), {}, {}, {}, {}, {}, onFocusRestored = { target, focused ->
                    finished += target to focused
                })
            }
        }
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("favorite" to true), finished) }
    }

    @Test fun pendingTalkBackClickPlaysTheFreshItemOnceAfterActualFocus() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val old = MediaItem("film", "Invented film", "Movie")
        val fresh = old.copy(name = "Invented refreshed film")
        val state = mutableStateOf(CatalogDetailState(account, old, loading = true, playbackPending = true))
        val plays = mutableListOf<MediaItem>()
        val logs = mutableListOf<String>()
        val handoff = PlaybackExitHandoff({ 0L }, logs::add)
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, onPlay = { item, _ -> plays += item },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) },
                    onPendingAction = { handoff.accessibleSelect("entry", it) }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(8_000)
        compose.runOnIdle {
            assertTrue(plays.isEmpty())
            state.value =
                state.value.copy(item = fresh, loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(fresh), plays, logs.joinToString()) }
    }

    @Test fun pendingPlayDoesNotStartWhenReloadFailsWithARetainedItem() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val plays = mutableListOf<MediaItem>()
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, onPlay = { playable, _ -> plays += playable },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) },
                    onPendingAction = { handoff.accessibleSelect("entry", it) }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            state.value = state.value.copy(loading = false, playbackPending = false, error = "Could not load item.")
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.runOnIdle { assertTrue(plays.isEmpty()) }
    }

    @Test fun aSeriesPendingPlayUsesItsFreshNextEpisode() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val series = MediaItem("series", "Invented series", "Series")
        val old = MediaItem("old", "Invented old episode", "Episode")
        val fresh = MediaItem("fresh", "Invented next episode", "Episode")
        val state = mutableStateOf(CatalogDetailState(account, series, loading = true, playbackPending = true, nextUp = listOf(old)))
        val plays = mutableListOf<MediaItem>()
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, onPlay = { playable, _ -> plays += playable },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) },
                    onPendingAction = { handoff.accessibleSelect("entry", it) }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, nextUp = listOf(fresh)) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(fresh), plays) }
    }

    @Test fun anEmptySeriesDoesNotOfferAnImpossiblePendingPlay() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("series", "Invented empty series", "Series", childCount = 0)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, item, loading = true, playbackPending = true), {
                }, {}, {}, {}, {}, onPendingAction = { true })
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertDoesNotExist()
        compose.onNodeWithText("Saving progress…").assertExists()
    }

    @Test fun pendingPlayHasActionableFocusWhileTheReloadIsHeld() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, item, loading = true, playbackPending = true), {
                }, {}, {}, {}, {}, onPendingAction = { true })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
    }

    @Test fun pendingPrimaryRemainsFirstInTheReaderFallbackOrderWhileFocused() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, item, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    onPendingAction = { true }
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        val queue = ArrayDeque<androidx.compose.ui.semantics.SemanticsNode>()
        queue.add(compose.onRoot().fetchSemanticsNode())
        var first: String? = null
        while (queue.isNotEmpty() && first == null) {
            val node = queue.removeFirst()
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            if (tag in setOf("detail:play-pending", "detail:played-pending", "detail:favorite-pending")) first = tag
            queue.addAll(node.children)
        }
        assertEquals("detail:play-pending", first)
    }

    @Test fun pendingReaderMovementIsAvailableBeforeAnyArrivalAcknowledgement() {
        val manager = compose.activity.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
        try {
            val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
            val item = MediaItem("film", "Invented film", "Movie")
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle = registry
            }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    PictureshowTheme {
                        CatalogDetail(
                            CatalogDetailState(account, item, loading = true, playbackPending = true),
                            {},
                            {},
                            {},
                            {},
                            {},
                            onPendingAction = { true }
                        )
                    }
                }
            }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            repeat(20) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            val offered = compose.onNodeWithTag("detail:played-pending").fetchSemanticsNode()
            assertFalse(offered.config.contains(SemanticsProperties.HideFromAccessibility))
            // No reader ACK and less than its former timeout: the first move must have a target.
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.onNodeWithTag("detail:played-pending").assertIsFocused()
        } finally {
            compose.mainClock.autoAdvance = true
            org.robolectric.Shadows.shadowOf(manager).apply {
                setTouchExplorationEnabled(false)
                setEnabled(false)
            }
        }
    }

    @Test fun readerMovementToPendingWatchedSurvivesFreshRestore() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val finished = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }, onFocusRestored = { target, focused ->
                    finished += target to focused
                })
            }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:played-pending").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:played").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("played" to true), finished) }
    }

    @Test fun readerMovementToPendingFavoriteSurvivesFreshRestore() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }) }
        }
        compose.onNodeWithTag("detail:favorite-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
    }

    @Test fun aPendingWatchedIntentCannotUndoFreshNaturalEndProgress() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        var toggles = 0
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value,
                    {},
                    {},
                    { toggles++ },
                    {},
                    {},
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            state.value =
                state.value.copy(item = item.copy(userData = item.userData.copy(played = true)), loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(0, toggles) }
    }

    @Test fun aPendingFavoriteClickRunsOnceOnlyAfterFreshReadiness() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        var toggles = 0
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value,
                    {},
                    {},
                    {},
                    { toggles++ },
                    {},
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        repeat(2) { compose.onNodeWithTag("detail:favorite-pending").performSemanticsAction(SemanticsActions.OnClick) { it() } }
        compose.runOnIdle {
            assertEquals(0, toggles)
            state.value = state.value.copy(loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun aPendingReaderHeaderChoiceTakesPriorityOverTheSavedSeriesShelf() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val series = MediaItem("series", "Invented series", "Series")
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val state = mutableStateOf(CatalogDetailState(account, series, loading = false, nextUp = listOf(episode)))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }) }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("detail:next:episode").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = true, playbackPending = true) }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:played").assertIsFocused()
    }

    @Test fun aConsumedPendingChoiceDoesNotOverrideALaterLifecycleRestore() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }) }
            }
        }
        compose.onNodeWithTag("detail:favorite-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test fun thePendingReaderTargetIsPublishedUnfocusedBeforeReceivingInputFocus() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    CatalogDetail(CatalogDetailState(account, item, loading = true, playbackPending = true), {
                    }, {}, {}, {}, {}, onPendingAction = { true })
                }
            }
        }
        // A returning control may already hold input focus while its page is still hidden.
        compose.onNodeWithTag("detail:play-pending", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:play-pending", useUnmergedTree = true).assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        var firstVisibleFocused: Boolean? = null
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            val nodes = compose.onAllNodesWithTag("detail:play-pending").fetchSemanticsNodes()
            if (firstVisibleFocused == null && nodes.isNotEmpty()) {
                firstVisibleFocused = nodes.single().config[SemanticsProperties.Focused]
            }
        }
        assertFalse(requireNotNull(firstVisibleFocused), "The pending reader target must first be published unfocused")
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
    }

    @Test fun aDiscardedStaleItemKeepsPendingRouteCapabilitiesButOnlyPlaysFreshData() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val route = MediaItem("film", "Invented route film", "Movie")
        val fresh = route.copy(name = "Invented fresh film")
        val state = mutableStateOf(CatalogDetailState(account, item = null, loading = true, playbackPending = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        val plays = mutableListOf<MediaItem>()
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, onPlay = { playable, _ -> plays += playable },
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }, origin = route
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertTrue(plays.isEmpty())
            state.value = state.value.copy(item = fresh, loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(fresh), plays) }
    }

    @Test fun aColdSeriesStillOffersReaderTargetsAndKeepsTheViewersChoice() = assertColdContainerReaderChoice("Series")

    @Test fun aColdSeasonStillOffersReaderTargetsAndKeepsTheViewersChoice() = assertColdContainerReaderChoice("Season")

    private fun assertColdContainerReaderChoice(type: String) {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val route = MediaItem("container", "Invented container", type, childCount = 2)
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val state = mutableStateOf(CatalogDetailState(account, item = null, loading = true, playbackPending = true))
        val finished = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, origin = route, onPendingAction = { true },
                    onFocusRestored = { target, focused -> finished += target to focused }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithTag("detail:favorite-pending").assertExists()
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(8_000)
        compose.runOnIdle {
            state.value = state.value.copy(item = route, episodes = listOf(episode), loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:played").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("played" to true), finished) }
    }

    @Test fun pendingMetadataKeepsItsReaderNodeWhenFreshDataArrives() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented title", "Movie", overview = "Invented overview")
        val state = mutableStateOf(CatalogDetailState(account, item = null, loading = true, playbackPending = true))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = item, onPendingAction = { true })
            }
        }
        val title = compose.onNodeWithText("Invented title").fetchSemanticsNode().id
        val overview = compose.onNodeWithText("Invented overview").fetchSemanticsNode().id
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        val primary = compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state.value = state.value.copy(item = item, loading = false, playbackPending = false) }
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(title, compose.onNodeWithText("Invented title").fetchSemanticsNode().id)
            assertEquals(overview, compose.onNodeWithText("Invented overview").fetchSemanticsNode().id)
            val action = compose.onAllNodes(hasTestTag("detail:play") or hasTestTag("detail:play-pending"))
                .fetchSemanticsNodes().single()
            assertEquals(primary, action.id)
            assertTrue(action.config[SemanticsProperties.Focused])
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun theSelectedPendingActionKeepsItsNodeAcrossFreshResumeActions() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented title", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true })
            }
        }
        val primary = compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id
        val played = compose.onNodeWithTag("detail:played-pending").fetchSemanticsNode().id
        val favorite = compose.onNodeWithTag("detail:favorite-pending").fetchSemanticsNode().id
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle {
            state.value = state.value.copy(
                item = item.copy(userData = app.pictureshow.core.ItemUserData(playbackPositionTicks = 40_000_000)),
                loading = false,
                playbackPending = false
            )
        }
        compose.onNodeWithTag("detail:played").assertIsFocused()
        compose.onNodeWithText("Play from start").assertExists()
        assertEquals(primary, compose.onNodeWithTag("detail:play").fetchSemanticsNode().id)
        assertEquals(played, compose.onNodeWithTag("detail:played").fetchSemanticsNode().id)
        assertEquals(favorite, compose.onNodeWithTag("detail:favorite").fetchSemanticsNode().id)
    }

    @Test fun aPendingLoadingPhaseChangeCannotCancelItsPublishedFocusHandoff() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        var published = false
        repeat(12) {
            if (!published) {
                compose.mainClock.advanceTimeByFrame()
                published = compose.onAllNodesWithTag("detail:play-pending").fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertTrue(published)
        compose.onNodeWithTag("detail:play-pending").assertIsNotFocused()
        compose.runOnUiThread { state.value = state.value.copy(loading = false) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
    }

    @Test fun pendingPhysicalWatchedSelectCapturesTheOfferedStateBeforeFreshness() {
        assertPhysicalSelectAcrossFreshness(played = true)
    }

    @Test fun pendingPhysicalPlaySelectRunsOnceEvenWhenItsReleaseArrivesFresh() {
        assertPhysicalSelectAcrossFreshness(played = false)
    }

    private fun assertPhysicalSelectAcrossFreshness(played: Boolean) {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        var plays = 0
        var mutations = 0
        val logs = mutableListOf<String>()
        val handoff = PlaybackExitHandoff({ 0L }, logs::add)
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, { mutations++ }, {}, {}, onPlay = { _, _ -> plays++ },
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onPendingControlsReady = { handoff.live("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        val target = if (played) "played" else "play"
        compose.onNodeWithTag("detail:$target-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        fun dispatch(action: Int) {
            val event = android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            if (!handoff.defer(event.keyCode, action == android.view.KeyEvent.ACTION_DOWN, 0) {
                    dispatch(action)
                }
            ) {
                compose.activity.dispatchKeyEvent(event)
            }
        }
        compose.runOnIdle { dispatch(android.view.KeyEvent.ACTION_DOWN) }
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(0, mutations)
            state.value = state.value.copy(
                item = item.copy(userData = app.pictureshow.core.ItemUserData(played = played)),
                loading = false,
                playbackPending = false
            )
        }
        compose.onNodeWithTag("detail:$target").assertIsFocused()
        compose.runOnIdle {
            dispatch(android.view.KeyEvent.ACTION_UP)
            assertEquals(if (played) 0 else 1, plays)
            assertEquals(0, mutations)
            assertTrue(logs.single { "phase=complete" in it }.contains("inputScope=activity inputBefore=true firstInput=ok"))
        }
    }

    @Test fun ordinaryDetailArrivalKeepsItsEmptyHostFocusEvent() = assertHostFilterScope(pending = false)

    @Test fun aPlaybackHostFocusFilterExpiresAtTheRequestBudget() = assertHostFilterScope(pending = true)

    private fun assertHostFilterScope(pending: Boolean) {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        var host: android.view.View? = null
        compose.setContent {
            host = androidx.compose.ui.platform.LocalView.current
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, item, loading = true, playbackPending = pending),
                    {},
                    {},
                    {},
                    {},
                    {},
                    onPendingAction = { true }
                )
            }
        }
        compose.runOnIdle {
            val view = requireNotNull(host)
            val parent = view.parent as android.view.ViewGroup
            val delegate = requireNotNull(androidx.core.view.ViewCompat.getAccessibilityDelegate(parent))
            fun forwarded(): Boolean {
                val event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED
                ).apply { className = "android.view.ViewGroup" }
                return try {
                    delegate.onRequestSendAccessibilityEvent(parent, view, event)
                } finally {
                    event.recycle()
                }
            }
            if (pending) {
                assertFalse(forwarded())
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(30_001))
            }
            assertTrue(forwarded())
        }
    }

    @Test fun anUnprimedTextCursorCannotClaimAViewerArrival() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            isVisibleToUser = true
        }
        val provider = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun findFocus(focus: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain(node)
        }
        val host = object : android.view.View(compose.activity) {
            override fun getAccessibilityNodeProvider() = provider
        }
        val finished = mutableListOf<Pair<String, Boolean>>()
        var selections = 0
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalView provides host) {
                PictureshowTheme {
                    CatalogDetail(
                        state.value, {}, {}, {}, {}, {}, onPendingAction = { true },
                        onPendingFocus = { selections++ }, onFocusRestored = { target, focused -> finished += target to focused }
                    )
                }
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("primary" to true), finished)
            assertEquals(0, selections)
            node.recycle()
        }
    }

    @Test fun aReportedPlaybackCompletionCannotWithdrawTheHeaderBeforeItsFreshRead() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented title", "Movie", genres = listOf("Invented genre"))
        val state = mutableStateOf(CatalogDetailState(account, item = null, loading = true, playbackPending = true))
        val finished = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, origin = item, onPendingAction = { true },
                    onFocusRestored = { target, focused -> finished += target to focused }
                )
            }
        }
        val primary = compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id
        val text = compose.onNodeWithText("Invented genre").fetchSemanticsNode().id
        compose.runOnIdle { state.value = state.value.copy(playbackPending = false) }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        assertEquals(primary, compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id)
        assertEquals(text, compose.onNodeWithText("Invented genre").fetchSemanticsNode().id)
        compose.runOnIdle {
            assertTrue(finished.isEmpty())
            state.value = state.value.copy(item = item, loading = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        assertEquals(primary, compose.onNodeWithTag("detail:play").fetchSemanticsNode().id)
        assertEquals(text, compose.onNodeWithText("Invented genre").fetchSemanticsNode().id)
    }

    @Test fun retryCannotOfferDeadPendingActionsAfterTheHandoffHasFinished() = assertRetryAfterFinishedHandoff(stillPending = false)

    @Test fun retryWithAnUnresolvedReportOffersStatusUntilFreshDataArrives() = assertRetryAfterFinishedHandoff(stillPending = true)

    @Test fun aRecoveredRetryCanPublishTheNextPlaybackReturn() = assertRetryAfterFinishedHandoff(stillPending = true, returnAgain = true)

    private fun assertRetryAfterFinishedHandoff(stillPending: Boolean, returnAgain: Boolean = false) {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(
            CatalogDetailState(
                account,
                item = null,
                loading = false,
                playbackPending = true,
                error = "Invented read failure."
            )
        )
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.begin("entry")
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {
                        state.value = state.value.copy(error = null, loading = true, playbackPending = stillPending)
                    }, origin = item, onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:play-pending").assertDoesNotExist()
        compose.onNodeWithText(if (stillPending) "Saving progress…" else "Loading item…").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(item = item, loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        if (returnAgain) {
            compose.runOnIdle {
                handoff.begin("entry")
                state.value = state.value.copy(item = null, loading = true, playbackPending = true)
            }
            compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Play requested").assertExists()
        }
    }
}
