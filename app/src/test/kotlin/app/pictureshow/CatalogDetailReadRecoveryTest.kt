/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import app.pictureshow.core.AccountStore
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogDetailReadRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")

    private fun advanceFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            // In the paused Robolectric looper a clock tick alone does not finish
            // Android layout/publication. Observe each actual frame before the next.
            compose.waitForIdle()
        }
    }

    private fun awaitFreshRetryPublication() {
        repeat(8) {
            advanceFrames(1)
            val fresh = compose.onAllNodes(hasTestTag("detail:play") and isEnabled()).fetchSemanticsNodes()
            val retiring = compose.onAllNodes(hasTestTag("detail:retry")).fetchSemanticsNodes()
            if (fresh.isNotEmpty() && retiring.isNotEmpty()) return
        }
        error("Fresh enabled actions must publish while the old Retry is still present")
    }

    @Test fun freshReadTransfersFocusWithoutAnInvisibleParkingFrame() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Server unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    state.value = state.value.copy(loading = true, error = null)
                }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        repeat(8) { frame ->
            advanceFrames(1)
            compose.waitForIdle()
            val focusedRetry = compose.onAllNodes(hasTestTag("detail:retry") and isFocused()).fetchSemanticsNodes()
            val focusedPlay = compose.onAllNodes(hasTestTag("detail:play") and isFocused()).fetchSemanticsNodes()
            assertTrue(
                focusedRetry.isNotEmpty() || focusedPlay.isNotEmpty(),
                "Fresh frame $frame must keep a visible recovery or fresh command focused"
            )
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aFreshHandoffKeepsThePublishedReasonAndRejectsAnotherRetry() {
        val item = MediaItem("film", "Invented film", "Movie")
        val reason = "The server is unavailable. The previous request expired and will not run."
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = reason))
        var retries = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    retries++
                    state.value = state.value.copy(loading = true, error = null)
                }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        awaitFreshRetryPublication()
        compose.onNodeWithText(reason).assertExists()
        compose.onNodeWithText("Retrying…").assertExists()
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, retries) }
        advanceFrames(12)
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.mainClock.autoAdvance = true
        // The snapshot must not re-arm recovery on an unrelated subsequent load.
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
    }

    @Test fun aSelectHeldAcrossFreshRecoveryCannotStartPlayback() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        var retries = 0
        var plays = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    retries++
                    state.value = state.value.copy(loading = true, error = null)
                }, onPlay = { _, _ -> plays++ }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(0, plays) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun aViewersFreshControlChoiceDuringHandoffIsNotStolenBack() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        awaitFreshRetryPublication()
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        advanceFrames(12)
        compose.onNodeWithTag("detail:played").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun recoveryReturnsToTheVisibleHeaderInsteadOfScrollingToAnOldShelf() {
        val item = MediaItem("series", "Invented series", "Series", childCount = 1)
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, episodes = listOf(episode)))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:episodes:episode").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:episodes:episode").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(error = "Read unavailable", readFailed = true) }
        compose.onNodeWithTag("detail:retry").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, readFailed = false) }
        compose.onNodeWithTag("detail:play").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
    }

    @Test fun anEmptyContainerRecoversToItsAvailableFavoriteAction() {
        val item = MediaItem("series", "Empty series", "Series", childCount = 0)
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.onNodeWithTag("detail:play")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test fun aLifecyclePauseDuringHandoffStillPublishesAndFocusesTheFreshTargetOnResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    CatalogDetail(state.value, {
                    }, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
                }
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        awaitFreshRetryPublication()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        advanceFrames(1)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        advanceFrames(18)
        compose.onNodeWithTag("detail:play").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aSelectWithoutItsOldReleaseDoesNotBlockTheFirstRetryAfterResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    CatalogDetail(state.value, {}, {}, {}, {}, {
                        retries++
                        state.value = state.value.copy(loading = true, error = null)
                    }, origin = item)
                }
            }
        }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        fun send(action: Int) = compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        }
        send(android.view.KeyEvent.ACTION_DOWN)
        compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "Still unavailable") }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        send(android.view.KeyEvent.ACTION_DOWN)
        send(android.view.KeyEvent.ACTION_UP)
        compose.runOnIdle { assertEquals(2, retries) }
    }

    @Test fun aNewReadDuringHandoffKeepsRetryUntilTheNewReadSettles() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        awaitFreshRetryPublication()
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        advanceFrames(6)
        compose.onNodeWithText("Retrying…").assertIsFocused().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        advanceFrames(12)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aViewerMayLeaveRetryWhileASecondReadIsHeld() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        awaitFreshRetryPublication()
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        advanceFrames(6)
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        advanceFrames(12)
        compose.onNodeWithTag("detail:played").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.runOnIdle { assertTrue(state.value.loading) }
        compose.mainClock.autoAdvance = true
    }

    @Suppress("DEPRECATION")
    @Test
    fun readerTextIsNotAnAcknowledgementOfFreshInputFocus() {
        val host = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider() = object : AccessibilityNodeProvider() {
                override fun findFocus(focus: Int): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                    className = "android.widget.TextView"
                    isVisibleToUser = true
                    isAccessibilityFocused = true
                }
            }
        }
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme {
                    CatalogDetail(state.value, {
                    }, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
                }
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
    }

    @Test fun anEnabledNonReaderServiceDoesNotRetainTheOldErrorForTheReaderTimeout() {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        compose.runOnUiThread { shadowOf(manager).setEnabled(true) }
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        advanceFrames(12)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aSpokenReaderKeepsRetryUntilItsFocusAcknowledgesTheFreshAction() = readerAcknowledgement(true)

    @Test fun aSpokenReaderWithoutAnAcknowledgementCannotLeaveRetryStuck() = readerAcknowledgement(false)

    @Test fun aSecondReadAfterInputFocusStillFinishesTheReaderHandoff() = readerAcknowledgement(true, secondRead = true)

    @Suppress("DEPRECATION")
    private fun readerAcknowledgement(acknowledge: Boolean, secondRead: Boolean = false) {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        compose.runOnUiThread {
            shadowOf(manager).setEnabled(true)
            shadowOf(manager).setEnabledAccessibilityServiceList(
                listOf(
                    AccessibilityServiceInfo().apply {
                        feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
                    }
                )
            )
        }
        var readerOnAction = false
        val host = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider() = object : AccessibilityNodeProvider() {
                override fun findFocus(focus: Int): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                    className = if (readerOnAction) "android.widget.Button" else "android.widget.TextView"
                    isVisibleToUser = true
                    isAccessibilityFocused = true
                    isClickable = readerOnAction
                    isFocused = readerOnAction
                }
            }
        }
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme {
                    CatalogDetail(state.value, {
                    }, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
                }
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        advanceFrames(8)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithText("Retrying…").assertIsDisplayed()
        if (secondRead) {
            compose.runOnIdle { state.value = state.value.copy(loading = true) }
            advanceFrames(8)
            compose.onNodeWithTag("detail:play").assertIsFocused()
            compose.onNodeWithText("Retrying…").assertIsDisplayed()
        }
        if (acknowledge) {
            compose.runOnIdle { readerOnAction = true }
            advanceFrames(4)
        } else {
            advanceFrames(110)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aWriteFailureDuringHandoffLeavesTheNormalWriteRetryUsable() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Read unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) }, origin = item)
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        awaitFreshRetryPublication()
        compose.runOnIdle { state.value = state.value.copy(error = "Write unavailable") }
        advanceFrames(18)
        compose.onNodeWithText("Write unavailable").assertExists()
        compose.onNodeWithText("Retry").assertIsFocused().assertIsEnabled()
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.onNodeWithText("Retrying…").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aDetachedFreshTargetCannotLeaveTheRecoveryOverlayStuck() {
        val item = MediaItem("film", "Invented film", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false, error = "Unavailable"))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, { state.value = state.value.copy(loading = true, error = null) })
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(item = null, loading = false) }
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.onNodeWithText("Unavailable").assertDoesNotExist()
    }

    @Test fun movieReturnDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Movie", true)

    @Test fun seriesReturnDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Series", true)

    @Test fun seasonReturnDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Season", true)

    @Test fun ordinaryFailedOpenDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Movie", false)

    private fun recovery(type: String, returning: Boolean) {
        val item = MediaItem("item", "Invented item", type, overview = "A short description.", childCount = 2)
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val freshItem = if (returning) {
            item
        } else {
            item.copy(
                productionYear = 2026,
                overview = "A richer authoritative description after recovery.",
                genres = listOf("Invented genre"),
                userData = item.userData.copy(playbackPositionTicks = 300_000_000L)
            )
        }
        val state =
            mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = returning, episodes = listOf(episode)))
        var retries = 0
        var mutations = 0
        val playedItems = mutableListOf<MediaItem>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, { mutations++ }, { mutations++ },
                    {
                        retries++
                        state.value = state.value.copy(loading = true, error = null)
                    },
                    onPlay = { fresh, _ -> playedItems += fresh }, onPendingAction = { true }, origin = item
                )
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val hero = bounds("detail:hero")
        val suffix = if (returning) "-pending" else ""
        val played = bounds("detail:played$suffix")
        val favorite = bounds("detail:favorite$suffix")
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "The server took too long to respond. Try again.") }
        assertEquals(hero, bounds("detail:hero"), "A read error must not insert a band above the published header")
        assertEquals(played, bounds("detail:played"), "The published Watched slot must remain in place")
        assertEquals(favorite, bounds("detail:favorite"), "The published Favorite slot must remain in place")
        compose.onNodeWithText("Retry").assertIsEnabled().assertIsFocused()
        val retry = compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused().assertIsEnabled()
        compose.onNodeWithText("The server took too long to respond. Try again.").assertExists()
        assertEquals(retry, compose.onNodeWithText("Retrying…").fetchSemanticsNode().boundsInRoot)
        assertEquals(hero, bounds("detail:hero"), "A held Retry retains its header and a real focused control")
        compose.onNodeWithText("Retrying…").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, mutations)
            assertEquals(emptyList(), playedItems)
        }
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "The server is still unavailable.") }
        compose.onNodeWithText("Retry").assertIsFocused()
        assertEquals(retry, compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot)
        assertEquals(hero, bounds("detail:hero"))
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(item = freshItem, loading = false, playbackPending = false, error = null) }
        compose.onNodeWithTag("detail:play").assertIsFocused().assertIsDisplayed()
        if (returning) {
            assertEquals(hero, bounds("detail:hero"), "A returning presentation stays frozen through recovery")
        } else {
            // Like an ordinary successful rc18 open, successful recovery adopts server metadata.
            compose.onNodeWithText("Resume from 0:30").assertExists()
        }
        compose.runOnIdle {
            assertEquals(2, retries)
            assertEquals(0, mutations)
            assertEquals(emptyList(), playedItems)
        }
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(listOf(if (type == "Movie") freshItem else episode), playedItems) }
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(1, mutations) }
        compose.runOnIdle { state.value = state.value.copy(loading = true, playbackPending = true) }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
    }

    @Test fun aFastReadFailureAfterFreshDataAndAnIdenticalFailedRetryKeepARealRetry() {
        val item = MediaItem("film", "Invented film", "Movie")
        val service = CatalogModelsTest.FakeCatalog().apply { detail = ClientResult.Success(item) }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        lateinit var vm: CatalogDetailViewModel
        compose.runOnUiThread {
            vm = CatalogDetailViewModel(account, item, CatalogSession(service, accounts))
            compose.activity.viewModelStore.put("recovery", vm)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme { CatalogDetail(state, {}, {}, {}, {}, vm::reload, origin = item) }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        val hero = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        var reads = 0
        compose.runOnIdle {
            service.detailBlock = {
                reads++
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Server unavailable"))
            }
            vm.reload()
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retrying…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
        assertEquals(hero, compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun aSeasonBrowsingReadFailureIntentionallyOffersTheSameExplicitRetry() {
        val series = MediaItem("series", "Invented series", "Series", childCount = 2)
        val season = MediaItem("season", "Invented season", "Season", seriesId = "series")
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val service = CatalogModelsTest.FakeCatalog().apply {
            detail = ClientResult.Success(series)
            seasonItems = ClientResult.Success(ItemPage(listOf(season)))
            episodeBlock = { _, _ -> ClientResult.Success(ItemPage(listOf(episode))) }
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        lateinit var vm: CatalogDetailViewModel
        compose.runOnUiThread {
            vm = CatalogDetailViewModel(account, series, CatalogSession(service, accounts))
            compose.activity.viewModelStore.put("season-recovery", vm)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme { CatalogDetail(state, {}, vm::season, {}, {}, vm::reload, origin = series) }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:seasons:season").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:seasons:season").assertIsFocused()
        compose.runOnIdle {
            service.episodeBlock = { _, _ -> ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Episodes unavailable")) }
            vm.season(season)
        }
        compose.onNodeWithText("Retry").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Episodes unavailable").assertExists()
    }

    @Test fun thePendingDeadlineSupersedesOffersWithARealRetryAndNeverReplaysTheAcceptedAction() {
        val item = MediaItem("item", "Invented item", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        var now = 0L
        val handoff = PlaybackExitHandoff({ now }, {})
        handoff.begin("entry")
        var mutations = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, { mutations++ }, {}, {},
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onPendingQueueExpired = { handoff.expired("entry") },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        val hero = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { now = 30_001 }
        compose.mainClock.advanceTimeBy(30_100)
        compose.onNodeWithText("Retry").assertIsFocused()
        assertEquals(hero, compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0, mutations) }
    }

    @Test fun successfulRetryClearsTheOldErrorWhileThePlaybackReportIsStillPending() {
        val item = MediaItem("episode", "Invented episode", "Episode")
        val service = CatalogModelsTest.FakeCatalog().apply {
            detail = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Old timed out read"))
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        val session = CatalogSession(service, accounts)
        val report = Any()
        session.playbackPending(item.id, report)
        lateinit var vm: CatalogDetailViewModel
        var plays = 0
        var mutations = 0
        compose.runOnUiThread {
            vm = CatalogDetailViewModel(account, item, session)
            compose.activity.viewModelStore.put("pending-report-retry", vm)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme {
                CatalogDetail(
                    state, {}, {}, { mutations++ }, { mutations++ }, vm::reload,
                    onPlay = { _, _ -> plays++ }, onPendingAction = { false }, origin = item
                )
            }
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.runOnIdle { service.detail = ClientResult.Success(item) }
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        advanceFrames(12)
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Old timed out read").assertDoesNotExist()
        compose.onNodeWithText("Item loaded. Saving progress…").assertExists()
        compose.runOnIdle { assertTrue(vm.state.value.playbackPending) }
        compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(0, mutations)
        }
        compose.runOnIdle { session.refresh(item.id, report) }
        advanceFrames(12)
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("detail:play").assertIsFocused().assertIsEnabled()
        compose.runOnIdle { assertTrue(!vm.state.value.playbackPending) }
    }

    @Test fun naturalEndSeasonRetryReturnsFocusToPlayAfterCompletionChangesWatchedState() {
        val season = MediaItem("season", "Invented season", "Season", seriesId = "series", childCount = 1)
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val state = mutableStateOf(CatalogDetailState(account, season, loading = true, playbackPending = true, episodes = listOf(episode)))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }, origin = season) }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle {
            state.value = state.value.copy(loading = false, readFailed = true, error = "Timed out")
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            state.value = state.value.copy(
                item = season.copy(userData = season.userData.copy(played = true)),
                loading = false,
                readFailed = false,
                error = null,
                playbackPending = false
            )
        }
        advanceFrames(12)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithText("Mark unwatched").assertExists()
    }

    @Test fun aDisabledSeasonActionKeepsItsPublishedLabelUntilTheFreshRead() {
        val season = MediaItem("season", "Invented season", "Season", seriesId = "series", childCount = 1)
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val state = mutableStateOf(CatalogDetailState(account, season, loading = true, playbackPending = true, episodes = listOf(episode)))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, onPendingAction = { true }, origin = season) }
        }
        compose.onNodeWithText("Mark watched when ready").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(
                item = season.copy(userData = season.userData.copy(played = true)),
                loading = false,
                readFailed = true,
                error = "Episodes unavailable"
            )
        }
        compose.onNodeWithText("Mark watched").assertExists()
        compose.onNodeWithText("Mark unwatched").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(playbackPending = false, readFailed = false, error = null) }
        advanceFrames(12)
        compose.onNodeWithText("Mark unwatched").assertExists()
    }

    @Test fun aMovieRetrySaysLoadingItemRatherThanLoadingEpisodes() {
        val item = MediaItem("film", "Invented film", "Movie")
        compose.setContent { PictureshowTheme { CatalogDetail(CatalogDetailState(account, item), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Loading item…", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Loading episodes…", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anExpiredQueuedActionCannotReturnThroughASuccessfulReadWhileReportingIsPending() {
        val item = MediaItem("episode", "Invented episode", "Episode")
        val first = kotlinx.coroutines.CompletableDeferred<ClientResult<MediaItem>>()
        val service = CatalogModelsTest.FakeCatalog().apply { detailBlock = { first.await() } }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        val session = CatalogSession(service, accounts)
        val report = Any()
        session.playbackPending(item.id, report)
        var now = 0L
        var mutations = 0
        val handoff = PlaybackExitHandoff({ now }, {})
        handoff.begin("entry")
        lateinit var vm: CatalogDetailViewModel
        compose.runOnUiThread {
            vm = CatalogDetailViewModel(account, item, session)
            compose.activity.viewModelStore.put("expired-pending-retry", vm)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme {
                CatalogDetail(
                    state, {}, {}, { mutations++ }, {}, vm::reload,
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onPendingQueueExpired = { handoff.expired("entry") },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }, origin = item
                )
            }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Watched change requested").assertExists()
        compose.runOnIdle {
            now = 30_001
            first.complete(ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Timed out")))
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.runOnIdle {
            service.detailBlock = null
            service.detail = ClientResult.Success(item)
        }
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        advanceFrames(12)
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithText("Watched change requested").assertDoesNotExist()
        compose.onNodeWithText("Mark watched when ready").assertDoesNotExist()
        compose.onNodeWithText("Watched unavailable").assertExists()
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("The request expired. Try again when the item loads.").assertExists()
        compose.runOnIdle {
            assertEquals(0, mutations)
            session.refresh(item.id, report)
        }
        advanceFrames(12)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(0, mutations) }
    }
}
