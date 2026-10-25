/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import app.pictureshow.core.displayRuntimeMillis

internal fun spokenPlaybackPosition(positionMs: Long, durationMs: Long): String {
    val position = playerTime(playbackEndPosition(positionMs, durationMs))
    val total = displayRuntimeMillis(durationMs)
    return if (total != null) "$position of ${playerTime(total)}" else position
}

/**
 * Keep Compose's snapshot quiet, but answer a screen reader's node query with the live clock.
 * Changing stateDescription on every tick emits content-change speech on a focused TV button.
 * Match resource IDs on the focusable parents: Compose may put their labels on generated children.
 * This decorator changes only returned nodes, leaving events, actions and the modal tree intact.
 */
@Composable
internal fun CurrentPositionAccessibility(tags: Set<String>, position: () -> String) {
    val host = LocalView.current
    val latest = rememberUpdatedState(position)
    DisposableEffect(host, tags) {
        val original = ViewCompat.getAccessibilityDelegate(host) ?: AccessibilityDelegateCompat()
        val delegate = CurrentPositionDelegate(original, tags) { latest.value() }
        ViewCompat.setAccessibilityDelegate(host, delegate)
        onDispose { if (ViewCompat.getAccessibilityDelegate(host) === delegate) ViewCompat.setAccessibilityDelegate(host, original) }
    }
}

internal class CurrentPositionDelegate(
    private val original: AccessibilityDelegateCompat,
    private val tags: Set<String>,
    private val position: () -> String
) : AccessibilityDelegateCompat() {
    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? {
        val provider = original.getAccessibilityNodeProvider(host)?.provider as? AccessibilityNodeProvider ?: return null
        fun decorate(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = node?.also {
            if (it.viewIdResourceName in tags) {
                AccessibilityNodeInfoCompat.wrap(it).stateDescription = position()
            }
        }
        // Compat may merely wrap a native provider: its virtual methods do not forward to that
        // object. Call the native provider so Compose retains its virtual-node actions and tree.
        return AccessibilityNodeProviderCompat(object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(virtualViewId: Int) = decorate(provider.createAccessibilityNodeInfo(virtualViewId))
            override fun findFocus(focus: Int) = decorate(provider.findFocus(focus))
            override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int) =
                provider.findAccessibilityNodeInfosByText(text, virtualViewId)?.mapNotNull { decorate(it) }
            override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?) =
                provider.performAction(virtualViewId, action, arguments)
            override fun addExtraDataToAccessibilityNodeInfo(id: Int, info: AccessibilityNodeInfo, key: String, arguments: Bundle?) {
                if (Build.VERSION.SDK_INT >= 26) provider.addExtraDataToAccessibilityNodeInfo(id, info, key, arguments)
                decorate(info)
            }
        })
    }

    override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
        original.dispatchPopulateAccessibilityEvent(host, event)
    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) =
        original.onInitializeAccessibilityEvent(host, event)
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) =
        original.onInitializeAccessibilityNodeInfo(host, info)
    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) = original.onPopulateAccessibilityEvent(host, event)
    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent) =
        original.onRequestSendAccessibilityEvent(host, child, event)
    override fun sendAccessibilityEvent(host: View, eventType: Int) = original.sendAccessibilityEvent(host, eventType)
    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) =
        original.sendAccessibilityEventUnchecked(host, event)
    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) =
        original.performAccessibilityAction(host, action, args)
}
