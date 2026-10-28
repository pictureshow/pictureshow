/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@Suppress("DEPRECATION")
class PlaybackExitFocusEventsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val parent = FrameLayout(context)
    private val child = View(context)
    private var enabled = true
    private val forwarded = mutableListOf<AccessibilityEvent>()
    private val original = object : AccessibilityDelegateCompat() {
        override fun onRequestSendAccessibilityEvent(host: android.view.ViewGroup, child: View, event: AccessibilityEvent): Boolean {
            forwarded += event
            return true
        }
    }
    private val delegate = PlaybackExitFocusDelegate(original, child) { enabled }

    private fun event(type: Int = AccessibilityEvent.TYPE_VIEW_FOCUSED, klass: String = "android.view.ViewGroup") =
        AccessibilityEvent.obtain(type).apply { className = klass }

    @Test fun anEmptyHostFocusCannotSendTheReaderToTheFirstTextNode() {
        assertFalse(delegate.onRequestSendAccessibilityEvent(parent, child, event()))
        assertTrue(forwarded.isEmpty())
    }

    @Test fun theActualVirtualControlFocusStillReachesTheReader() {
        val focus = event(klass = "android.view.View")
        assertTrue(delegate.onRequestSendAccessibilityEvent(parent, child, focus))
        assertSame(focus, forwarded.single())
    }

    @Test fun labeledGroupsAndOtherEventTypesRemainVisible() {
        val labeled = event().apply { text += "Invented label" }
        val described = event().apply { contentDescription = "Invented action" }
        val changed = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        listOf(labeled, described, changed).forEach {
            assertTrue(delegate.onRequestSendAccessibilityEvent(parent, child, it))
        }
        assertEquals(listOf(labeled, described, changed), forwarded)
    }

    @Test fun otherChildrenAndAnInactiveHandoffKeepTheirFocusEvents() {
        val other = event()
        assertTrue(delegate.onRequestSendAccessibilityEvent(parent, View(context), other))
        enabled = false
        val inactive = event()
        assertTrue(delegate.onRequestSendAccessibilityEvent(parent, child, inactive))
        assertEquals(listOf(other, inactive), forwarded)
    }

    @Test fun existingNodeProvidersActionsAndDescriptionsArePreserved() {
        val provider = AccessibilityNodeProviderCompat()
        var action: Int? = null
        val inherited = object : AccessibilityDelegateCompat() {
            override fun getAccessibilityNodeProvider(host: View) = provider
            override fun performAccessibilityAction(host: View, value: Int, args: Bundle?): Boolean {
                action = value
                return true
            }
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                info.contentDescription = "Invented description"
            }
        }
        val wrapped = PlaybackExitFocusDelegate(inherited, child) { true }
        assertSame(provider, wrapped.getAccessibilityNodeProvider(parent))
        assertTrue(wrapped.performAccessibilityAction(parent, 42, null))
        assertEquals(42, action)
        val info = AccessibilityNodeInfoCompat.obtain()
        wrapped.onInitializeAccessibilityNodeInfo(parent, info)
        assertEquals("Invented description", info.contentDescription)
        info.recycle()
    }

    @Test fun disposalRestoresTheOriginalParentDelegate() {
        parent.addView(child)
        ViewCompat.setAccessibilityDelegate(parent, original)
        val restore = installPlaybackExitFocusEvents(child) { true }
        assertTrue(ViewCompat.getAccessibilityDelegate(parent) is PlaybackExitFocusDelegate)
        restore()
        assertSame(original, ViewCompat.getAccessibilityDelegate(parent))
    }

    @Test fun disposalDoesNotReplaceANewerDelegateOwner() {
        parent.addView(child)
        val restore = installPlaybackExitFocusEvents(child) { true }
        val newer = AccessibilityDelegateCompat()
        ViewCompat.setAccessibilityDelegate(parent, newer)
        restore()
        assertSame(newer, ViewCompat.getAccessibilityDelegate(parent))
    }

    @Test fun overlappingOwnersCanDisposeInInsertionOrder() {
        parent.addView(child)
        ViewCompat.setAccessibilityDelegate(parent, original)
        val first = installPlaybackExitFocusEvents(child) { true }
        val second = installPlaybackExitFocusEvents(child) { true }
        first()
        second()
        assertSame(original, ViewCompat.getAccessibilityDelegate(parent))
    }

    @Test fun overlappingOwnersCanDisposeInReverseOrder() {
        parent.addView(child)
        ViewCompat.setAccessibilityDelegate(parent, original)
        val before = ViewCompat.getAccessibilityDelegate(parent)
        val first = installPlaybackExitFocusEvents(child) { true }
        val second = installPlaybackExitFocusEvents(child) { true }
        second()
        assertFalse(
            requireNotNull(ViewCompat.getAccessibilityDelegate(parent))
                .onRequestSendAccessibilityEvent(parent, child, event())
        )
        first()
        assertSame(before, ViewCompat.getAccessibilityDelegate(parent))
    }

    @Test fun onlyVisibleStaticReaderTextIsAViewerSelection() {
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            isVisibleToUser = true
        }
        val provider = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun findFocus(focus: Int) = if (focus == android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
                android.view.accessibility.AccessibilityNodeInfo.obtain(node)
            } else {
                null
            }
        }
        val host = object : View(context) {
            override fun getAccessibilityNodeProvider() = provider
        }
        assertTrue(playbackReaderOnText(host))
        node.isClickable = true
        assertFalse(playbackReaderOnText(host))
        node.isClickable = false
        node.isEditable = true
        assertFalse(playbackReaderOnText(host))
        node.isEditable = false
        node.className = "android.view.View"
        assertFalse(playbackReaderOnText(host))
        node.isClickable = true
        node.isFocused = true
        assertEquals(PlaybackReaderTarget.ACTION, playbackReaderTarget(host))
        node.className = "android.widget.Button"
        assertEquals(PlaybackReaderTarget.ACTION, playbackReaderTarget(host))
        node.isClickable = false
        node.isFocused = false
        node.className = "android.widget.TextView"
        node.isVisibleToUser = false
        assertFalse(playbackReaderOnText(host))
        assertFalse(playbackReaderOnText(child))
        node.recycle()
    }

    @Test fun automaticTextArrivalIsNotAMoveUntilTheReaderHasVisitedTheAction() {
        val movement = PlaybackReaderMovement()
        assertFalse(movement.observe(PlaybackReaderTarget.TEXT))
        assertFalse(movement.observe(PlaybackReaderTarget.NONE))
        assertFalse(movement.observe(PlaybackReaderTarget.ACTION))
        assertTrue(movement.observe(PlaybackReaderTarget.TEXT))
        movement.reset()
        assertFalse(movement.observe(PlaybackReaderTarget.TEXT))
    }
}
