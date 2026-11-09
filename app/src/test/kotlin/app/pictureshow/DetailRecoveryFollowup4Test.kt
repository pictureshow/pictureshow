/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
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
class DetailRecoveryFollowup4Test {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val failure = "The server could not complete the request (503)."

    @Test fun aHealthyReturnWithoutReaderPresentationDoesNotQueueAnAnnouncementAheadOfFocus() = settlement(false)

    @Test fun aConsumedRequestDoesNotAnnounceReadyForAnActionThatIsAlreadyActing() = settlement(true)

    private fun settlement(requested: Boolean) {
        val speech = mutableListOf<String>()
        val host = object : View(compose.activity) {
            @Suppress("DEPRECATION")
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        if (requested) compose.onNodeWithTag("detail:play-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { assertTrue(speech.isEmpty(), "A short app announcement blocks the focus label: $speech") }
    }

    @Test fun aReaderPresentedHoldUsesOneInterruptibleStateAndDoesNotLeaveReadyOnPeers() = presentedSettlement(false)

    @Test fun aPlayerActionCannotArmReadinessForTheReturningDetailPage() = presentedSettlement(false, false)

    @Test fun anAssignedFocusWithoutAReaderQueryDoesNotArmReadiness() = presentedSettlement(false, queried = false)

    @Test fun prefetchedHeldLabelsWithoutReaderSelectionCannotArmReadiness() = presentedSettlement(false, selected = false)

    @Test fun aParentQueryWithoutTheHeldLabelCannotArmReadiness() = presentedSettlement(false, labelQueried = false)

    @Test fun aLateHeldLabelQueryIsNotLostBeforePublication() = presentedSettlement(false, immediate = true)

    @Test fun aReaderDepartureBeforePublicationCannotReplayReadyOnRediscovery() = presentedSettlement(false, departed = true)

    @Test fun aRejectedReadyEventIsRetriedUntilSubmitted() = presentedSettlement(false, rejectFirst = true)

    @Test fun anAcceptedMutationCancelsReadinessBeforeThePublicationFailureBand() = presentedSettlement(true)

    @Suppress("DEPRECATION")
    private fun presentedSettlement(
        requested: Boolean,
        detailOwned: Boolean = true,
        queried: Boolean = true,
        selected: Boolean = true,
        labelQueried: Boolean = true,
        immediate: Boolean = false,
        rejectFirst: Boolean = false,
        departed: Boolean = false
    ) {
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
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        var readerOnSelected = true
        val speech = mutableListOf<String>()
        val feedback = mutableListOf<String>()
        lateinit var host: View
        val original = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun performAction(id: Int, action: Int, arguments: android.os.Bundle?) = true
            override fun createAccessibilityNodeInfo(id: Int) = if (id == 2) {
                android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                    setSource(host, 2)
                    className = "android.widget.TextView"
                    text = "Play when ready"
                    viewIdResourceName = "detail:play-pending-label"
                }
            } else {
                findFocus(2)
            }
            override fun findFocus(focus: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                setSource(host, 1)
                className = "android.widget.Button"
                isVisibleToUser = true
                isAccessibilityFocused = true
                isFocused = readerOnSelected
                isClickable = true
                isEnabled = true
                // Compose puts visible Text on a child; the selected parent has no text.
                text = null
                viewIdResourceName =
                    if (!detailOwned) {
                        "player:play-pause"
                    } else if (state.value.playbackPending) {
                        "detail:play-pending"
                    } else {
                        "detail:play"
                    }
            }
        }
        host = object : View(compose.activity) {
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        androidx.core.view.ViewCompat.setAccessibilityDelegate(
            host,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun getAccessibilityNodeProvider(host: View) =
                    androidx.core.view.accessibility.AccessibilityNodeProviderCompat(original)
            }
        )
        var rejected = false
        val receiver = object : android.widget.FrameLayout(compose.activity) {
            override fun requestSendAccessibilityEvent(child: View, event: android.view.accessibility.AccessibilityEvent): Boolean {
                if (rejectFirst && !rejected) {
                    rejected = true
                    return false
                }
                feedback += event.text.joinToString()
                event.recycle()
                return true
            }
        }
        receiver.addView(host)
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        if (queried) {
            compose.runOnIdle {
                if (selected) host.accessibilityNodeProvider?.performAction(1, 64, null)
                host.accessibilityNodeProvider?.createAccessibilityNodeInfo(1)?.recycle()
                if (labelQueried) host.accessibilityNodeProvider?.createAccessibilityNodeInfo(2)?.recycle()
            }
        }
        if (!immediate) compose.mainClock.advanceTimeBy(150)
        if (requested) {
            compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(150)
        }
        compose.runOnIdle {
            if (departed) readerOnSelected = false
            state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true)
        }
        compose.mainClock.advanceTimeBy(150)
        if (departed) {
            compose.runOnIdle {
                readerOnSelected = true
                state.value = state.value.copy(item = film.copy(userData = film.userData.copy(playbackPositionTicks = 100_000_000)))
            }
            compose.mainClock.advanceTimeBy(150)
        }
        if (requested) {
            compose.onNodeWithTag("detail:played").assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unwatched"))
            compose.runOnIdle { state.value = state.value.copy(error = failure, writeAttempt = 1) }
            compose.onNodeWithText("Change failed. $failure").assertExists()
        } else if (!detailOwned || !queried || !labelQueried || !selected || departed) {
            compose.onNodeWithTag("detail:play").assertIsFocused()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        } else {
            compose.onNodeWithTag("detail:play").assertIsFocused()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            compose.runOnIdle { kotlin.test.assertEquals(listOf("Ready. Play"), feedback) }
            compose.runOnIdle { readerOnSelected = false }
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("detail:play").assertIsFocused()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            compose.runOnIdle { readerOnSelected = true }
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("detail:play").assertIsFocused()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            compose.onNodeWithTag("detail:played").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unwatched"))
            compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
        compose.runOnIdle {
            assertTrue(speech.isEmpty(), "Node changes must not also enqueue announcements: $speech")
            kotlin.test.assertEquals(
                if (!requested &&
                    detailOwned &&
                    queried &&
                    labelQueried &&
                    selected &&
                    !departed
                ) {
                    listOf("Ready. Play")
                } else {
                    emptyList()
                },
                feedback
            )
        }
    }

    @Test fun pendingActionsPublishTheirNativeOwnershipWithoutChangingTheirLabels() {
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
                    onPendingAction = { true }
                )
            }
        }
        for (tag in listOf("play", "played", "favorite", "from-start")) {
            compose.onNodeWithTag("detail:$tag-pending")
                .assert(SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true))
        }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("Play when ready").assertExists()
    }

    @Test fun requestSemanticsUseRediscoveryWithoutAnUninterruptibleLiveRegion() {
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
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:played-pending")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Watched already requested")))
    }

    @Test fun aFastRequestWaitsForItsOwnReaderTargetAndEmitsAcceptanceOnlyOnce() {
        val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        compose.runOnUiThread { org.robolectric.Shadows.shadowOf(manager).setScreenReader(true) }
        var readerId = 1
        var readerInputFocused = true
        lateinit var host: View
        val cues = mutableListOf<String>()
        val original = object : android.view.accessibility.AccessibilityNodeProvider() {
            @Suppress("DEPRECATION")
            override fun createAccessibilityNodeInfo(id: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                setSource(host, id)
                className = "android.widget.Button"
                isVisibleToUser = true
                isAccessibilityFocused = id == readerId
                isClickable = true
                isEnabled = true
                // A stale old node may still claim input focus after Compose moved it.
                isFocused = id == readerId && readerInputFocused
                viewIdResourceName = if (id == 1) "detail:play-pending" else "detail:played-pending"
            }
            override fun findFocus(focus: Int) = createAccessibilityNodeInfo(readerId)
        }
        val provider = DetailPresentationProvider(original) { null }
        host = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider() = provider
        }
        val receiver = object : android.widget.FrameLayout(compose.activity) {
            override fun requestSendAccessibilityEvent(child: View, event: android.view.accessibility.AccessibilityEvent): Boolean {
                kotlin.test.assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, event.eventType)
                kotlin.test.assertEquals(
                    android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION,
                    event.contentChangeTypes
                )
                cues += event.text.joinToString()
                event.recycle()
                return true
            }
        }
        receiver.addView(host)
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme {
                    CatalogDetail(
                        CatalogDetailState(account, film, loading = true, playbackPending = true),
                        {},
                        {},
                        {},
                        {},
                        {},
                        origin = film,
                        onPendingAction = { true }
                    )
                }
            }
        }
        compose.runOnIdle {
            provider.createAccessibilityNodeInfo(1)?.recycle()
            provider.createAccessibilityNodeInfo(2)?.recycle()
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { assertTrue(cues.isEmpty()) }
        compose.onNodeWithTag("detail:played-pending")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Watched already requested")))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { readerInputFocused = false }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle {
            assertTrue(cues.isEmpty())
            readerInputFocused = true
            readerId = 2
        }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { kotlin.test.assertEquals(listOf("Watched change requested"), cues) }
        compose.mainClock.advanceTimeBy(250)
        compose.runOnIdle {
            kotlin.test.assertEquals(1, cues.size)
            readerId = 1
        }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("detail:played-pending")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unwatched"))
        compose.runOnIdle { readerId = 2 }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { kotlin.test.assertEquals(1, cues.size) }
    }

    @Test fun aSeasonReadFailureCannotBeReplacedByTheClosedQueuesSyntheticBudgetReason() {
        val season = film.copy(id = "season", type = "Season", seriesId = "series", childCount = 1)
        val state = mutableStateOf(CatalogDetailState(account, season, loading = true))
        var expired = false
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, {}, {}, {}, origin = season,
                    onPendingAction = { true }, onPendingQueueExpired = { expired }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.runOnIdle {
            expired = true
            state.value = state.value.copy(loading = false, error = failure, readFailed = true)
        }
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText("The server has not finished loading. Retry to reload the item.").assertDoesNotExist()
    }

    @Test fun retryReasonHasNoTrailingSeparatorPeriodForTalkBackToDouble() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = false, error = failure, readFailed = true),
                    {},
                    {},
                    {},
                    {},
                    {}
                )
            }
        }
        val description = compose.onNodeWithTag("detail:retry").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(!description.endsWith('.'), "TalkBack supplies the separator before the button role: $description")
        compose.onNodeWithText(failure).assertExists()
    }
}
