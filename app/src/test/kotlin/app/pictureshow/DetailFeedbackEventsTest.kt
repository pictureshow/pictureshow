/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@Suppress("DEPRECATION")
class DetailFeedbackEventsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val parent = FrameLayout(context)
    private val child = View(context)
    private var active = true
    private val received = mutableListOf<AccessibilityEvent>()
    private val original = object : AccessibilityDelegateCompat() {
        override fun onRequestSendAccessibilityEvent(host: android.view.ViewGroup, child: View, event: AccessibilityEvent): Boolean {
            received += event
            return true
        }
    }
    private val delegate = DetailFeedbackDelegate(original, child) { active }
    private fun changed(type: Int = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION) =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            className = "android.widget.Button"
            contentChangeTypes = type
            contentDescription = "Play"
        }

    @Test fun theDepartingActionsPlainNameCannotSpeakBeforeRetryTakesFocus() {
        for (type in listOf(
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        )) {
            assertFalse(delegate.onRequestSendAccessibilityEvent(parent, child, changed(type)))
        }
        assertTrue(received.isEmpty())
    }

    @Test fun retryFocusAndTreePublicationArePreservedDuringTheHandoff() {
        val focus = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply { text += "Retry" }
        val readerFocus = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        val structure = changed(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        val mixed = changed(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
        val reason = changed().apply { className = "android.widget.TextView" }
        for (event in listOf(focus, readerFocus, structure, mixed, reason)) {
            assertTrue(delegate.onRequestSendAccessibilityEvent(parent, child, event))
        }
        assertEquals(5, received.size)
    }

    @Test fun aSecondFailureOnTheFocusedRetryStillSendsItsNewState() {
        active = false
        assertTrue(
            delegate.onRequestSendAccessibilityEvent(parent, child, changed(AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION))
        )
        active = true
        assertTrue(delegate.onRequestSendAccessibilityEvent(parent, View(context), changed()))
        delegate.disposed = true
        assertTrue(delegate.onRequestSendAccessibilityEvent(parent, child, changed()))
        assertEquals(3, received.size)
    }

    @Test
    @Config(sdk = [23])
    fun olderAndroidKeepsUnsupportedStateBitsAndStillQuietsTextLabels() {
        assertTrue(
            delegate.onRequestSendAccessibilityEvent(parent, child, changed(AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION))
        )
        assertFalse(
            delegate.onRequestSendAccessibilityEvent(parent, child, changed(AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION))
        )
    }

    @Test fun readerPresentationIncludesDisabledAndTextlessPendingParents() {
        var enabled = true
        var inputFocused = true
        var resourceId: String? = "detail:play-pending"
        val presentation = DetailReaderPresentationState()
        val original = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun performAction(id: Int, action: Int, arguments: android.os.Bundle?) = true
            override fun createAccessibilityNodeInfo(id: Int) = if (id == 2) {
                android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                    className = "android.widget.TextView"
                    text = "Play when ready"
                    viewIdResourceName = "detail:play-pending-label"
                }
            } else {
                findFocus(2)
            }
            override fun findFocus(focus: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                className = "android.view.View"
                isVisibleToUser = true
                isAccessibilityFocused = true
                isFocused = inputFocused
                isEnabled = enabled
                isClickable = enabled
                viewIdResourceName = resourceId
            }
        }
        val provider = DetailPresentationProvider(original) { presentation }
        val host = object : View(context) {
            override fun getAccessibilityNodeProvider() = provider
        }
        assertFalse(provider.wasQueried(resourceId), "An assigned focus flag has not presented a label")
        provider.findFocus(2)?.recycle()
        assertFalse(provider.wasQueried(resourceId), "Internal focus polling must stay silent")
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertFalse(provider.wasQueried(resourceId), "A parent query does not fetch its held label")
        provider.createAccessibilityNodeInfo(2)?.recycle()
        assertFalse(provider.wasQueried(resourceId), "Prefetch cannot establish reader selection")
        assertTrue(provider.performAction(1, 64, null))
        provider.createAccessibilityNodeInfo(2)?.recycle()
        assertTrue(provider.wasQueried(resourceId))
        assertTrue(detailReaderFocusMatches(host, "primary"))
        resourceId = "player:play-pause"
        assertFalse(detailReaderFocusMatches(host, "primary"))
        resourceId = null
        assertFalse(detailReaderFocusMatches(host, "primary"))
        resourceId = "detail:play-pending"
        enabled = false
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertTrue(provider.wasQueried(resourceId))
        inputFocused = false
        assertFalse(detailReaderFocusMatches(host, "primary"))
    }

    @Test fun presentationProviderPreservesNativeActionsAndOnlyCountsAnActiveReaderQuery() {
        var active = true
        val presentation = DetailReaderPresentationState()
        val calls = mutableListOf<Int>()
        val original = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                viewIdResourceName = "detail:played-pending"
                className = "android.view.View"
                isFocused = true
                isAccessibilityFocused = true
                isVisibleToUser = true
                isEnabled = false
            }
            override fun performAction(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
                calls += action
                return true
            }
        }
        val provider = DetailPresentationProvider(original) { presentation.takeIf { active } }
        assertTrue(provider.performAction(1, 64, null))
        assertEquals(listOf(64), calls)
        assertFalse(provider.wasQueried("detail:played-pending"))
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertTrue(provider.wasQueried("detail:played-pending"))
        active = false
        assertFalse(provider.wasQueried("detail:played-pending"))
        val next = DetailReaderPresentationState()
        assertTrue(next.queriedHeldIds.isEmpty())
    }

    @Test fun freshFocusAndRejectedFocusCannotQualifyHeldPrefetch() {
        var held = false
        var accepted = true
        val presentation = DetailReaderPresentationState()
        val original = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                viewIdResourceName = if (held) "detail:play-pending" else "detail:play"
                isFocused = true
                isAccessibilityFocused = true
                isVisibleToUser = true
                isEnabled = false
            }
            override fun performAction(id: Int, action: Int, arguments: android.os.Bundle?) = accepted
        }
        val provider = DetailPresentationProvider(original) { presentation }
        provider.performAction(1, 64, null)
        held = true
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertFalse(provider.wasQueried("detail:play-pending"))
        accepted = false
        provider.performAction(1, 64, null)
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertFalse(provider.wasQueried("detail:play-pending"))
        accepted = true
        provider.performAction(1, 64, null)
        provider.performAction(1, 128, null)
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertFalse(provider.wasQueried("detail:play-pending"))
        provider.performAction(1, 64, null)
        provider.createAccessibilityNodeInfo(1)?.recycle()
        assertTrue(provider.wasQueried("detail:play-pending"))
    }

    @Test fun anOutOfOrderDisposedPresentationDelegateUnwrapsToItsNativeProvider() {
        val old = DetailPresentationDelegate(original) { null }
        old.disposed = true
        val current = DetailPresentationDelegate(old) { null }
        assertTrue(current.inherited() === original)
    }

    @Test fun ordinaryFeedbackCarriesItsTextWithoutChangingTheFocusedNodesState() = ordinaryFeedback(30)

    @Test
    @Config(sdk = [23])
    fun olderAndroidUsesAnOrdinaryContentDescriptionEvent() = ordinaryFeedback(23)

    @Suppress("DEPRECATION")
    private fun ordinaryFeedback(sdk: Int) {
        val manager = context.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        val messages = mutableListOf<String>()
        lateinit var host: View
        val original = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int) = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                setSource(host, id)
                viewIdResourceName = "detail:played"
                className = "android.widget.Button"
                isFocused = true
                isAccessibilityFocused = true
                isVisibleToUser = true
                isEnabled = true
                isClickable = true
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(this).stateDescription = "Unwatched"
            }
            override fun findFocus(focus: Int) = createAccessibilityNodeInfo(7)
        }
        val provider = DetailPresentationProvider(original) { null }
        host = object : View(context) {
            override fun getAccessibilityNodeProvider() = provider
        }
        val receiver = object : FrameLayout(context) {
            override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
                assertTrue(child === host)
                assertEquals(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, event.eventType)
                assertEquals(
                    if (sdk >=
                        30
                    ) {
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
                    } else {
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
                    },
                    event.contentChangeTypes
                )
                messages += if (sdk >= 30) event.text.joinToString() else event.contentDescription.toString()
                event.recycle()
                return true
            }
        }
        receiver.addView(host)
        provider.createAccessibilityNodeInfo(7)?.recycle()
        assertTrue(provider.sendFeedback(host, "played", "Ready"))
        assertEquals(listOf("Ready"), messages)
        val node = provider.findFocus(2)!!
        assertEquals("Unwatched", androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node).stateDescription)
        node.recycle()
        assertFalse(provider.sendFeedback(host, "primary", "Ready"))
        org.robolectric.Shadows.shadowOf(manager).setEnabled(false)
        assertFalse(provider.sendFeedback(host, "played", "Ready"))
        assertEquals(1, messages.size)
    }

    @Test fun rediscoveryLabelChangesAreQuietButExplicitAcceptanceAndFocusArePreserved() {
        active = false
        assertFalse(
            delegate.onRequestSendAccessibilityEvent(
                parent,
                child,
                changed().apply {
                    contentDescription =
                        "Watched already requested"
                }
            )
        )
        assertTrue(
            delegate.onRequestSendAccessibilityEvent(
                parent,
                child,
                changed().apply {
                    contentDescription = "Watched change requested"
                }
            )
        )
        assertTrue(
            delegate.onRequestSendAccessibilityEvent(
                parent,
                child,
                changed(AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION).apply {
                    contentDescription = null
                    text += "Watched change requested"
                }
            )
        )
        assertTrue(
            delegate.onRequestSendAccessibilityEvent(
                parent,
                child,
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
            )
        )
        assertEquals(3, received.size)
    }

    @Test fun anOutOfOrderDisposedPageDoesNotRestoreAnActiveOldFilter() {
        delegate.disposed = true
        val current = DetailFeedbackDelegate(delegate, child) { false }
        assertTrue(current.inherited() === original)
    }
}
