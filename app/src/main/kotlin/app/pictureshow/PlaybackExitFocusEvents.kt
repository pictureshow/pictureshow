/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import kotlinx.coroutines.withTimeoutOrNull

/** Keep the host's empty focus record from preceding the actual virtual control's focus. */
@Composable
internal fun PlaybackExitFocusEvents(active: Boolean) {
    val host = LocalView.current
    val enabled by rememberUpdatedState(active)
    val expiresAt by rememberUpdatedState(
        remember(active) {
            android.os.SystemClock.uptimeMillis() + app.pictureshow.core.ApiTimeouts.REQUEST_MILLIS
        }
    )
    DisposableEffect(host) {
        val restore = installPlaybackExitFocusEvents(host) { enabled && android.os.SystemClock.uptimeMillis() < expiresAt }
        onDispose(restore)
    }
}

internal fun installPlaybackExitFocusEvents(child: View, active: () -> Boolean): () -> Unit {
    val parent = child.parent as? ViewGroup ?: return {}
    val original = ViewCompat.getAccessibilityDelegate(parent)
    val delegate = PlaybackExitFocusDelegate(original, child, active)
    ViewCompat.setAccessibilityDelegate(parent, delegate)
    return {
        delegate.disposed = true
        if (ViewCompat.getAccessibilityDelegate(parent) === delegate) {
            ViewCompat.setAccessibilityDelegate(parent, delegate.inherited())
        }
    }
}

/** Delegation preserves an existing parent's provider, actions and all unrelated events. */
internal class PlaybackExitFocusDelegate(
    private var previous: AccessibilityDelegateCompat?,
    private val ownedChild: View,
    private val active: () -> Boolean
) : AccessibilityDelegateCompat() {
    internal var disposed = false
    private val fallback = AccessibilityDelegateCompat()
    internal fun inherited(): AccessibilityDelegateCompat? {
        while ((previous as? PlaybackExitFocusDelegate)?.disposed == true) {
            previous = (previous as PlaybackExitFocusDelegate).inherited()
        }
        return previous
    }
    private val original: AccessibilityDelegateCompat get() = inherited() ?: fallback

    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
        if (!disposed && child === ownedChild && active() && emptyHostFocus(event)) {
            if (BuildConfig.DEBUG) PlaybackDiagnostics.write("exit-focus phase=host-suppressed")
            return false
        }
        return original.onRequestSendAccessibilityEvent(host, child, event)
    }

    override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
        original.dispatchPopulateAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) =
        original.onInitializeAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) =
        original.onInitializeAccessibilityNodeInfo(host, info)

    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) = original.onPopulateAccessibilityEvent(host, event)

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? = original.getAccessibilityNodeProvider(host)

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
        original.performAccessibilityAction(host, action, args)

    override fun sendAccessibilityEvent(host: View, eventType: Int) = original.sendAccessibilityEvent(host, eventType)

    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) =
        original.sendAccessibilityEventUnchecked(host, event)

    private fun emptyHostFocus(event: AccessibilityEvent): Boolean = event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
        event.className?.toString() == "android.view.ViewGroup" &&
        event.text.all { it.isNullOrBlank() } &&
        event.contentDescription.isNullOrBlank()
}

/** Reader text selection is independent of Compose's input-focused action. */
internal enum class PlaybackReaderTarget { NONE, ACTION, TEXT }

internal class PlaybackReaderMovement {
    private var sawAction = false
    fun observe(target: PlaybackReaderTarget): Boolean {
        if (target == PlaybackReaderTarget.ACTION) sawAction = true
        return sawAction && target == PlaybackReaderTarget.TEXT
    }
    fun reset() {
        sawAction = false
    }
}

internal fun playbackReaderOnText(host: View): Boolean = playbackReaderTarget(host) == PlaybackReaderTarget.TEXT

@Suppress("DEPRECATION")
internal fun playbackReaderTarget(host: View): PlaybackReaderTarget {
    val node = host.accessibilityNodeProvider?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        ?: return PlaybackReaderTarget.NONE
    return try {
        when {
            !node.isVisibleToUser || node.isEditable -> PlaybackReaderTarget.NONE
            node.isFocused &&
                node.isClickable &&
                node.className?.toString() in setOf("android.view.View", "android.widget.Button") -> PlaybackReaderTarget.ACTION
            !node.isClickable && node.className?.toString() == "android.widget.TextView" -> PlaybackReaderTarget.TEXT
            else -> PlaybackReaderTarget.NONE
        }
    } finally {
        node.recycle()
    }
}

/** Keep the departing reader node alive through Compose's accessibility dispatch. */
internal suspend fun awaitPlaybackReaderInputFocus(host: View): Boolean {
    val manager = host.context.getSystemService(
        android.content.Context.ACCESSIBILITY_SERVICE
    ) as? android.view.accessibility.AccessibilityManager
    if (manager?.isEnabled != true ||
        manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty()
    ) {
        return true
    }
    return withTimeoutOrNull(1_500L) {
        while (playbackReaderTarget(host) != PlaybackReaderTarget.ACTION) withFrameNanos { }
        true
    } ?: false
}
