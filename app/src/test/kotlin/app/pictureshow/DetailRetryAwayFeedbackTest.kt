/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DetailRetryAwayFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Sample TrueHD and DTS experimental", "Movie")
    private val failure = "The server could not complete the request (503)."
    private val retryStateDescription = "Retry. Loading failed. The server could not complete the request (503)"
    private val fallbackCuePrefix = "Loading failed."
    private val fallbackCueSuffix = "Retry is available."

    @Test fun aFailedRetryUsesOneOrdinaryCueOnTheCurrentReaderHeading() {
        val fixture = fixture()
        beginRetry(fixture)
        moveReaderToHeading(fixture)
        fixture.state.value = fixture.state.value.copy(loading = false, error = failure, readFailed = true)
        compose.waitForIdle()

        compose.onNodeWithTag("detail:retry").assertIsFocused()
        assertEquals(1, fixture.native.inputFocusedId, "The cue must not move input focus from Retry")
        assertCurrentReaderNode(fixture, id = 2, expectedTag = "detail:title")

        var cues = retryAwayCues(fixture)
        assertEquals(1, cues.size, "A failed Retry away from Retry must submit one cue: ${fixture.parent.events}")
        val cue = cues.single()
        assertEquals(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, cue.type)
        assertEquals(AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION, cue.changeTypes)
        assertTrue(cue.sourceRoot === fixture.host, "The cue source root must be this detail's native host")
        assertEquals(2, cue.sourceVirtualId, "The event must target the currently focused heading id")
        assertEquals("detail:title", cue.sourceResourceId)
        assertTrue(cue.text.startsWith(fallbackCuePrefix), "Unexpected cue text: ${cue.text}")
        assertTrue("(503)" in cue.text, "The new failure reason must be included: ${cue.text}")
        assertTrue(cue.text.endsWith(fallbackCueSuffix), "The cue needs an action hint: ${cue.text}")
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, cue.sourceLiveRegion)
        assertFalse(fixture.parent.events.any { it.type == AccessibilityEvent.TYPE_ANNOUNCEMENT })
        compose.onNodeWithTag("detail:retry").assert(
            androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)
        )

        // Unrelated state emissions/recomposition do not replay the same failure attempt.
        fixture.state.value = fixture.state.value.copy(busy = true)
        compose.waitForIdle()
        fixture.state.value = fixture.state.value.copy(busy = false, item = film.copy(name = "Refreshed title"))
        compose.waitForIdle()
        cues = retryAwayCues(fixture)
        assertEquals(1, cues.size, "Same read attempt must not replay its cue: ${fixture.parent.events}")
        assertEquals(1, fixture.retryCalls)
    }

    @Test fun aSuccessfulRetryDoesNotOwnAnUnrelatedLaterReadFailure() {
        val fixture = fixture()
        beginRetry(fixture)
        moveReaderToHeading(fixture)

        fixture.state.value = fixture.state.value.copy(
            loading = false,
            error = null,
            readFailed = false,
            readSucceeded = true
        )
        compose.waitForIdle()
        assertTrue(retryAwayCues(fixture).isEmpty(), "A successful read must not issue a failure cue")

        // A later refresh belongs to a new read episode, after the Retry succeeded.
        fixture.state.value = fixture.state.value.copy(
            loading = true,
            error = null,
            readFailed = false,
            readSucceeded = false,
            readAttempt = fixture.state.value.readAttempt + 1
        )
        compose.waitForIdle()
        fixture.state.value = fixture.state.value.copy(loading = false, error = failure, readFailed = true)
        compose.waitForIdle()

        assertTrue(
            retryAwayCues(fixture).isEmpty(),
            "A later unrelated read failure must not inherit the earlier Retry cue baseline: ${fixture.parent.events}"
        )
        assertEquals(1, fixture.retryCalls)
    }

    @Test fun aSecondRetryFailureCanIssueItsOwnSingleCue() {
        val fixture = fixture()
        beginRetry(fixture)
        moveReaderToHeading(fixture)
        fixture.state.value = fixture.state.value.copy(loading = false, error = failure, readFailed = true)
        compose.waitForIdle()
        assertEquals(1, retryAwayCues(fixture).size)

        // Keep AX on the heading while Retry is activated through its Compose action.
        compose.onNodeWithTag("detail:retry")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
        compose.waitForIdle()
        assertEquals(3, fixture.state.value.readAttempt)
        assertTrue(fixture.state.value.loading)
        assertEquals(1, fixture.native.inputFocusedId)
        assertCurrentReaderNode(fixture, id = 2, expectedTag = "detail:title")

        fixture.state.value = fixture.state.value.copy(loading = false, error = failure, readFailed = true)
        compose.waitForIdle()

        assertEquals(
            2,
            retryAwayCues(fixture).size,
            "The second accepted Retry attempt should contribute one new cue: ${fixture.parent.events}"
        )
        assertEquals(2, fixture.retryCalls)
    }

    @Test fun whenRetryStillOwnsReaderFocusTheRetryStateSpeaksWithoutAHeadingFallback() {
        val fixture = fixture()
        beginRetry(fixture)
        assertTrue(fixture.axFocus(1), "The app-wrapped provider accepted native AX focus on Retry")
        fixture.axQuery(1)
        fixture.parent.events.clear()

        fixture.state.value = fixture.state.value.copy(loading = false, error = failure, readFailed = true)
        compose.waitForIdle()

        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, retryStateDescription))
        assertCurrentReaderNode(fixture, id = 1, expectedTag = "detail:retry")
        assertTrue(
            fixture.parent.events.none { it.sourceResourceId == "detail:title" && fallbackCuePrefix in it.text },
            "Retry focus owns its normal state cue; do not add the away-from-Retry fallback: ${fixture.parent.events}"
        )
        assertTrue(retryAwayCues(fixture).isEmpty())
    }

    @Test fun aPausedOrDepartedDetailDoesNotDeliverAFailureCueLater() {
        val fixture = fixture()
        beginRetry(fixture)
        moveReaderToHeading(fixture)
        fixture.owner.registry.currentState = Lifecycle.State.STARTED
        fixture.state.value = fixture.state.value.copy(loading = false, error = failure, readFailed = true)
        compose.waitForIdle()
        assertTrue(retryAwayCues(fixture).isEmpty(), "A paused detail must not send the cue")

        // Dispose the page while that same failed attempt is still pending publication.
        fixture.showDetail.value = false
        compose.waitForIdle()
        fixture.owner.registry.currentState = Lifecycle.State.RESUMED
        compose.waitForIdle()
        fixture.state.value = fixture.state.value.copy(busy = true)
        compose.waitForIdle()
        assertTrue(retryAwayCues(fixture).isEmpty(), "A departed detail must not announce into its successor")
        assertEquals(1, fixture.retryCalls)
    }

    private data class NativeEvent(
        val type: Int,
        val changeTypes: Int,
        val text: String,
        val contentDescription: String?,
        val sourceRoot: View?,
        val sourceVirtualId: Int,
        val sourceResourceId: String?,
        val sourceLiveRegion: Int?
    )

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private inner class NativeFixture(initialState: CatalogDetailState) {
        val state = mutableStateOf(initialState)
        val showDetail = mutableStateOf(true)
        val owner = TestOwner()
        var retryCalls = 0
        val parent = CapturingParent()
        val container = object : FrameLayout(compose.activity) {
            @Suppress("DEPRECATION")
            override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
                // Match the real parent/delegate hop so the F4 retry-handoff filter is exercised.
                val captureParent = this@NativeFixture.parent
                val delegate = ViewCompat.getAccessibilityDelegate(captureParent)
                return delegate?.onRequestSendAccessibilityEvent(captureParent, this, event) ?: true
            }
        }
        lateinit var host: View
        val native: FakeProvider

        /** Route explicit reader traffic through the provider installed by the app's delegate bridge. */
        fun axProvider(): AccessibilityNodeProvider {
            val provider = ViewCompat.getAccessibilityDelegate(host)
                ?.getAccessibilityNodeProvider(host)
                ?.provider as? AccessibilityNodeProvider
            assertTrue(
                provider is DetailPresentationProvider,
                "AX traffic must use the app-installed DetailPresentationProvider, not the raw fake provider"
            )
            return requireNotNull(provider)
        }

        fun axFocus(id: Int): Boolean = axProvider().performAction(
            id,
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        fun axQuery(id: Int) {
            axProvider().createAccessibilityNodeInfo(id)?.recycle()
        }

        init {
            owner.registry.currentState = Lifecycle.State.RESUMED
            native = FakeProvider(compose.activity) { host }
            host = View(compose.activity)
            ViewCompat.setAccessibilityDelegate(
                host,
                object : AccessibilityDelegateCompat() {
                    override fun getAccessibilityNodeProvider(host: View) =
                        androidx.core.view.accessibility.AccessibilityNodeProviderCompat(native)
                }
            )
            parent.addView(container)
            container.addView(host)
            ViewCompat.setAccessibilityDelegate(parent, parent.captureDelegate)

            val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as android.view.accessibility.AccessibilityManager
            Shadows.shadowOf(manager).apply {
                setEnabled(true)
            }

            compose.setContent {
                CompositionLocalProvider(
                    LocalView provides host,
                    LocalLifecycleOwner provides owner
                ) {
                    PictureshowTheme {
                        if (showDetail.value) {
                            CatalogDetail(
                                state.value,
                                {},
                                {},
                                {},
                                {},
                                onRetry = {
                                    retryCalls++
                                    state.value = state.value.copy(
                                        loading = true,
                                        error = null,
                                        readFailed = false,
                                        readSucceeded = false,
                                        readAttempt = state.value.readAttempt + 1
                                    )
                                }
                            )
                        } else {
                            androidx.tv.material3.Text("Next detail page")
                        }
                    }
                }
            }
        }
    }

    private inner class CapturingParent : FrameLayout(compose.activity) {
        val events = mutableListOf<NativeEvent>()
        val captureDelegate = object : AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                // Robolectric records setSource(root, id) separately from getSource(); it
                // returns a node only when a synthetic source node was installed. Verify
                // the real setSource values and resolve that id from its provider instead.
                val sourceRecord = Shadow.extract<ShadowAccessibilityRecord>(event)
                val sourceRoot = sourceRecord.sourceRoot
                val sourceVirtualId = sourceRecord.virtualDescendantId
                val source = if (sourceRoot != null && sourceVirtualId != ShadowAccessibilityRecord.NO_VIRTUAL_ID) {
                    sourceRoot.accessibilityNodeProvider?.createAccessibilityNodeInfo(sourceVirtualId)
                } else {
                    null
                }
                try {
                    events += NativeEvent(
                        event.eventType,
                        event.contentChangeTypes,
                        event.text.joinToString(),
                        event.contentDescription?.toString(),
                        sourceRoot,
                        sourceVirtualId,
                        source?.viewIdResourceName,
                        source?.liveRegion
                    )
                } finally {
                    source?.recycle()
                }
                // This fixture stands in for the parent's acceptance boundary. Match
                // AccessibilityManager ownership for an accepted event.
                event.recycle()
                return true
            }
        }
    }

    private class FakeProvider(private val context: android.content.Context, private val host: () -> View) : AccessibilityNodeProvider() {
        var readerFocusedId = 1
        var inputFocusedId = 1

        fun focusReader(id: Int): Boolean = performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)

        fun query(id: Int) {
            createAccessibilityNodeInfo(id)?.recycle()
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                readerFocusedId = virtualViewId
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (readerFocusedId == virtualViewId) readerFocusedId = -1
                true
            }
            AccessibilityNodeInfo.ACTION_FOCUS -> {
                inputFocusedId = virtualViewId
                true
            }
            else -> false
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? = when (focus) {
            AccessibilityNodeInfo.FOCUS_ACCESSIBILITY -> readerFocusedId.takeIf { it >= 0 }?.let(::createAccessibilityNodeInfo)
            AccessibilityNodeInfo.FOCUS_INPUT -> inputFocusedId.takeIf { it >= 0 }?.let(::createAccessibilityNodeInfo)
            else -> null
        }

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId !in 1..3) return null
            return AccessibilityNodeInfo.obtain().apply {
                setSource(host(), virtualViewId)
                packageName = context.packageName
                isVisibleToUser = true
                isAccessibilityFocused = readerFocusedId == virtualViewId
                isFocused = inputFocusedId == virtualViewId
                isEnabled = true
                liveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                when (virtualViewId) {
                    1 -> {
                        className = "android.widget.Button"
                        text = if (false) "Retrying…" else "Retry"
                        viewIdResourceName = "detail:retry"
                    }
                    2 -> {
                        className = "android.view.View"
                        text = "Sample TrueHD and DTS experimental"
                        viewIdResourceName = "detail:title"
                        isHeading = true
                    }
                    else -> {
                        className = "android.view.View"
                        text = "Next detail page"
                        viewIdResourceName = "next:detail-title"
                        isHeading = true
                    }
                }
            }
        }
    }

    private fun fixture(): NativeFixture = NativeFixture(
        CatalogDetailState(
            account,
            film,
            loading = false,
            error = failure,
            readFailed = true,
            readAttempt = 1
        )
    )

    private fun beginRetry(fixture: NativeFixture) {
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        assertTrue(fixture.axFocus(1), "The fake provider accepted native AX focus on Retry")
        fixture.axQuery(1)
        fixture.parent.events.clear()
        compose.onNodeWithTag("detail:retry")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading item. Please wait.")
            )
        assertEquals(2, fixture.state.value.readAttempt)
        assertTrue(fixture.state.value.loading)
    }

    private fun moveReaderToHeading(fixture: NativeFixture) {
        assertTrue(fixture.axFocus(2), "The app-wrapped provider accepted native AX focus on the heading")
        fixture.axQuery(2)
        assertEquals(1, fixture.native.inputFocusedId, "AX movement must not move input focus")
    }

    private fun assertCurrentReaderNode(fixture: NativeFixture, id: Int, expectedTag: String) {
        val focused = fixture.axProvider().findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        assertNotNull(focused)
        try {
            assertTrue(focused.isAccessibilityFocused)
            assertEquals(id, fixture.native.readerFocusedId)
            assertEquals(expectedTag, focused.viewIdResourceName)
        } finally {
            focused.recycle()
        }
    }

    private fun retryAwayCues(fixture: NativeFixture): List<NativeEvent> = fixture.parent.events.filter { event ->
        event.type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.changeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION &&
            event.sourceResourceId == "detail:title" &&
            fallbackCuePrefix in event.text
    }
}
