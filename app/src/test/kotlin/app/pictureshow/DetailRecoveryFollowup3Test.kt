/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
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
class DetailRecoveryFollowup3Test {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val oldReason = "The previous request expired and will not run."
    private val failure = "The server could not complete the request (503)."

    @Test fun aColdHeldMovieOffersAQueueAndRunsOnePlayWhenItsReadFinishes() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true))
        val handoff = PlaybackExitHandoff({ 0L }, {})
        handoff.destinationChanged("cold")
        var plays = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, origin = film,
                    onPlay = { _, _ -> plays++ },
                    onPendingQueueNeeded = { handoff.prepare("cold") },
                    onPendingAction = { handoff.accessibleSelect("cold", it) },
                    onPendingControlsReady = { handoff.live("cold", it) },
                    onFocusRestored = { target, focused -> handoff.finish("cold", target, focused) }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(0, plays) }
        compose.runOnIdle {
            state.value = state.value.copy(
                item = film.copy(overview = "Authoritative movie overview.", productionYear = 2026),
                loading = false,
                readSucceeded = true
            )
        }
        compose.onNodeWithText("Authoritative movie overview.").assertExists()
        compose.onNodeWithText("More details").assertDoesNotExist()
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun aColdHeldSeriesLetsTheReaderChooseWatchedBeforeTheReadsReturn() {
        val series = MediaItem("series", "Invented series", "Series", childCount = 2)
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, series, loading = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = series,
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
    }

    @Test fun retryingPublishesLoadingWithoutThePreviousFailureText() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = oldReason, readFailed = true))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    state.value = state.value.copy(loading = true, error = null, readAttempt = 2)
                })
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused()
        compose.onNodeWithText(oldReason).assertDoesNotExist()
        compose.onNodeWithTag("detail:retry").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading item. Please wait.")
        )
    }

    @Test fun aRetryThatFailsBeforeAnotherCompositionGetsItsOwnReason() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = oldReason, readFailed = true))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    // A fast new transport can finish before Compose observes its pending emission.
                    state.value = state.value.copy(loading = false, error = failure, readFailed = true, readAttempt = 2)
                })
            }
        }
        compose.onNodeWithTag("detail:retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText(oldReason).assertDoesNotExist()
        compose.onNodeWithTag("detail:retry").assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. ${failure.dropLast(1)}")
        )
    }

    @Test fun requestedWatchedRevisitReportsAlreadyRequestedAndCurrentValue() {
        var requests = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = {
                        requests++
                        true
                    }
                )
            }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithContentDescription("Watched already requested").assertExists()
        compose.onNodeWithTag("detail:favorite-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unwatched"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithContentDescription("Watched already requested").assertExists()
        compose.onNodeWithContentDescription("Watched change requested").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun aSupersededFailureFlagWithoutAReasonCannotLatchAnInventedReason() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, readFailed = true, readAttempt = 2))
        compose.setContent { PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = failure, readFailed = true) }
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithTag("detail:retry").assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. ${failure.dropLast(1)}")
        )
    }

    @Test fun pendingRequestEndsItsCueWhenOnlyTheReaderMovesToText() = readerDeparture(true)

    @Test fun queuedFreshRequestEndsItsCueWhenOnlyTheReaderMovesToText() = readerDeparture(false)

    @Test fun pendingRequestEndsItsCueWhenOnlyTheReaderMovesToAnotherButton() = readerDeparture(true, false)

    @Test fun queuedFreshRequestEndsItsCueWhenOnlyTheReaderMovesToAnotherButton() = readerDeparture(false, false)

    @Test fun aLaterQueuedRequestOnTheSameActionGetsAFreshReaderObservation() = readerDeparture(false, repeatAfterIdle = true)

    @Suppress("DEPRECATION")
    private fun readerDeparture(pending: Boolean, toText: Boolean = true, repeatAfterIdle: Boolean = false) {
        val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        compose.runOnUiThread {
            org.robolectric.Shadows.shadowOf(manager).setEnabledAccessibilityServiceList(
                listOf(
                    android.accessibilityservice.AccessibilityServiceInfo().apply {
                        feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
                    }
                )
            )
            org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        }
        var readerOnText = false
        val announcements = mutableListOf<String>()
        lateinit var host: android.view.View
        host = object : android.view.View(compose.activity) {
            override fun getAccessibilityNodeProvider() = object : android.view.accessibility.AccessibilityNodeProvider() {
                override fun findFocus(focus: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                    setSource(host, 7)
                    viewIdResourceName = when {
                        readerOnText -> if (toText) "detail:overview" else "detail:play"
                        pending -> "detail:played-pending"
                        else -> "detail:favorite"
                    }
                    isEnabled = true
                    className = if (readerOnText && toText) "android.widget.TextView" else "android.widget.Button"
                    isVisibleToUser = true
                    isAccessibilityFocused = true
                    isClickable = !readerOnText || !toText
                    isFocused = !readerOnText
                }
            }
            override fun announceForAccessibility(text: CharSequence) {
                announcements += text.toString()
            }
        }
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        var accepted = 0
        var firstWrites = 0
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme {
                    CatalogDetail(
                        state.value,
                        {},
                        {},
                        {
                            firstWrites++
                            state.value = state.value.copy(busy = true)
                        },
                        {},
                        {},
                        origin = film,
                        onPendingAction = {
                            accepted++
                            true
                        }
                    )
                }
            }
        }
        if (!pending) {
            compose.runOnIdle {
                state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true)
            }
            compose.onNodeWithTag("detail:played").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertEquals(1, firstWrites) }
        }
        val target = compose.onNodeWithTag(if (pending) "detail:played-pending" else "detail:favorite")
        val action = if (pending) "Watched" else "Favorite"
        target.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
            .assertIsFocused()
        compose.onNodeWithContentDescription("$action already requested").assertExists()
        if (repeatAfterIdle) {
            compose.runOnIdle { state.value = state.value.copy(busy = false) }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("Favorite").assertExists()
            target.assertIsFocused()
            val beforeIdle = android.os.SystemClock.uptimeMillis()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31))
            compose.mainClock.advanceTimeBy(31_000)
            compose.runOnIdle {
                assertTrue(android.os.SystemClock.uptimeMillis() - beforeIdle >= 30_000)
                state.value = state.value.copy(busy = true)
            }
            target.assertIsFocused().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithContentDescription("Favorite already requested").assertExists()
        }
        target.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            announcements.clear()
            readerOnText = true
        }
        compose.mainClock.advanceTimeBy(150)
        compose.waitForIdle()
        target.assertIsFocused()
        compose.onNodeWithContentDescription("$action already requested").assertExists()
        compose.onNodeWithContentDescription("$action change requested").assertDoesNotExist()
        target.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (pending) "Unwatched" else "Not favorite"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle {
            assertTrue(announcements.isEmpty(), "Reader departure must not announce over the selected text")
            readerOnText = false
        }
        compose.mainClock.advanceTimeBy(100)
        target.assertIsFocused().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("$action already requested").assertExists()
        compose.runOnIdle {
            assertEquals(if (pending) 1 else 0, accepted)
            assertEquals(if (pending) 0 else 1, firstWrites)
            assertTrue(announcements.isEmpty())
        }
    }
}
