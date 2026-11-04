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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat

private const val DETAIL_FAILURE_CUE = "app.pictureshow.detail.failure-cue"

private val heldDetailActionIds = setOf(
    "detail:play-pending",
    "detail:played-pending",
    "detail:favorite-pending",
    "detail:from-start-pending"
)

internal fun detailActionTarget(id: String?): String? = when (id?.removeSuffix("-label")?.removeSuffix("-pending")) {
    "detail:play" -> "primary"
    "detail:played" -> "played"
    "detail:favorite" -> "favorite"
    "detail:from-start" -> "from-start"
    else -> null
}

@Suppress("DEPRECATION")
internal fun detailReaderFocusMatches(host: View, target: String?): Boolean {
    val node = host.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return false
    return try {
        detailActionTarget(node.viewIdResourceName) == target &&
            target != null &&
            node.isAccessibilityFocused &&
            node.isFocused &&
            node.isVisibleToUser
    } finally {
        node.recycle()
    }
}

private val detailPresentationBridges = java.util.WeakHashMap<View, java.lang.ref.WeakReference<DetailPresentationBridge>>()

internal class DetailFeedbackChannel internal constructor(
    private val host: View,
    private val bridge: DetailPresentationBridge,
    private val owner: Any
) {
    val accessibilityEnabled: Boolean
        get() = (
            host.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as android.view.accessibility.AccessibilityManager
            ).isEnabled

    fun sendReadFailure(message: String): Boolean {
        val provider = host.accessibilityNodeProvider as? DetailPresentationProvider
            ?: bridge.delegate.getAccessibilityNodeProvider(host)?.provider as? DetailPresentationProvider
        return provider?.sendReadFailure(host, message, bridge.isCurrent(owner)) == true
    }

    fun clearReaderFocus(): Boolean {
        val provider = host.accessibilityNodeProvider as? DetailPresentationProvider
            ?: bridge.delegate.getAccessibilityNodeProvider(host)?.provider as? DetailPresentationProvider
        return provider?.clearReaderFocus(bridge.isCurrent(owner)) == true
    }

    fun send(target: String, message: String): Boolean {
        val provider = host.accessibilityNodeProvider as? DetailPresentationProvider
            ?: bridge.delegate.getAccessibilityNodeProvider(host)?.provider as? DetailPresentationProvider
        return provider?.sendFeedback(host, target, message, bridge.isCurrent(owner)) == true
    }
}

/** One bridge per native host; player decorators can wrap and restore that same bridge. */
internal class DetailPresentationBridge(previous: AccessibilityDelegateCompat) {
    private val owners = linkedMapOf<Any, () -> DetailReaderPresentationState?>()
    private val actionIds = mutableMapOf<String, Int>()
    private val reader = DetailReaderCursor()
    val delegate = DetailPresentationDelegate(
        previous,
        actionIds,
        { owners.isNotEmpty() },
        reader,
        { owners.values.lastOrNull()?.invoke() }
    )
    private var installed = false
    fun install(host: View) {
        if (!installed) {
            ViewCompat.setAccessibilityDelegate(host, delegate)
            installed = true
        }
    }
    fun acquire(owner: Any, presentation: () -> DetailReaderPresentationState?) {
        actionIds.clear()
        reader.clear()
        owners[owner] = presentation
    }
    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) actionIds.clear()
    }
    fun isCurrent(owner: Any): Boolean = owners.keys.lastOrNull() === owner
}

/** Preserve structural/focus events; quiet only obsolete labels before Retry takes focus. */
@Composable
internal fun DetailFeedbackEvents(handoff: Boolean) {
    val host = LocalView.current
    val active by rememberUpdatedState(handoff)
    DisposableEffect(host) {
        // Use the next ancestor: the immediate parent already preserves Compose's
        // empty-host focus handoff and owns an independent delegate lifetime.
        val child = host.parent as? ViewGroup
        val parent = child?.parent as? ViewGroup
        val previous = parent?.let(ViewCompat::getAccessibilityDelegate)
        val delegate = DetailFeedbackDelegate(previous, child ?: host) { active }
        if (parent != null) ViewCompat.setAccessibilityDelegate(parent, delegate)
        onDispose {
            delegate.disposed = true
            if (parent != null && ViewCompat.getAccessibilityDelegate(parent) === delegate) {
                ViewCompat.setAccessibilityDelegate(parent, delegate.inherited())
            }
        }
    }
}

internal class DetailFeedbackDelegate(
    previous: AccessibilityDelegateCompat?,
    private val ownedChild: View,
    private val handoff: () -> Boolean
) : AccessibilityDelegateCompat() {
    private var previous = previous
    private val fallback = AccessibilityDelegateCompat()
    internal var disposed = false
    internal fun inherited(): AccessibilityDelegateCompat? {
        while ((previous as? DetailFeedbackDelegate)?.disposed == true) {
            previous = (previous as DetailFeedbackDelegate).inherited()
        }
        return previous
    }
    private val original: AccessibilityDelegateCompat get() = inherited() ?: fallback

    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
        // This exact one-shot cue has already validated the current reader source and
        // page owner. It is not an obsolete action-label change during Retry handoff.
        if (!disposed &&
            child === ownedChild &&
            (event.parcelableData as? Bundle)?.getBoolean(DETAIL_FAILURE_CUE) == true
        ) {
            return original.onRequestSendAccessibilityEvent(host, child, event)
        }
        val stateChange = if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION else 0
        val labels =
            AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or stateChange
        val onlyLabel = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.contentChangeTypes and labels != 0 &&
            event.contentChangeTypes and labels.inv() == 0
        val action = event.className?.toString() in setOf("android.view.View", "android.widget.Button")
        val targets = listOf("primary", "played", "favorite", "from-start")
        val description = event.contentDescription?.toString()
        val rediscovery = description in targets.map { app.pictureshow.core.detailRequestedActionLabel(it, false) }
        if (!disposed &&
            child === ownedChild &&
            app.pictureshow.core.detailQuietsRequestedRediscoveryChange(action, onlyLabel, rediscovery)
        ) {
            return false
        }
        if (!disposed &&
            child === ownedChild &&
            app.pictureshow.core.detailQuietsDepartingActionChange(
                handoff(),
                action,
                onlyLabel
            )
        ) {
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
}

/** Prefetch is not selection: a reader must select the held node before fetching its label. */
internal class DetailReaderPresentationState(private val onPresented: (String) -> Unit = {}) {
    val selectedHeldIds = mutableSetOf<String>()
    val queriedHeldIds = mutableSetOf<String>()
    fun record(id: String) {
        if (!app.pictureshow.core.detailHeldLabelIsPresentation(id in selectedHeldIds, true)) return
        queriedHeldIds += id
        detailActionTarget(id)?.let(onPresented)
    }
}

@Composable
internal fun rememberDetailFeedbackChannel(active: Boolean, onPresented: (String) -> Unit): DetailFeedbackChannel {
    val host = LocalView.current
    val owner = remember { Any() }
    val latestCallback by rememberUpdatedState(onPresented)
    val state = remember(active) { DetailReaderPresentationState { latestCallback(it) } }
    val latestState by rememberUpdatedState(state)
    val enabled by rememberUpdatedState(active)
    val bridge = remember(host) {
        detailPresentationBridges[host]?.get() ?: run {
            val previous = ViewCompat.getAccessibilityDelegate(host) ?: AccessibilityDelegateCompat()
            DetailPresentationBridge(previous).also {
                detailPresentationBridges[host] = java.lang.ref.WeakReference(it)
            }
        }
    }
    val channel = remember(host, bridge, owner) { DetailFeedbackChannel(host, bridge, owner) }
    SideEffect { if (!active) state.queriedHeldIds.clear() }
    DisposableEffect(bridge, owner) {
        bridge.install(host)
        bridge.acquire(owner) { latestState.takeIf { enabled } }
        onDispose { bridge.release(owner) }
    }
    return channel
}

internal class DetailReaderCursor {
    var id: Int? = null
    var resource: String? = null
    fun clear() {
        id = null
        resource = null
    }
    fun record(id: Int, node: AccessibilityNodeInfo) {
        if (node.isAccessibilityFocused && node.isVisibleToUser) {
            this.id = id
            resource = node.viewIdResourceName
        }
    }
}

internal class DetailPresentationProvider(
    private val original: AccessibilityNodeProvider,
    private val actionIds: MutableMap<String, Int> = mutableMapOf(),
    private val reader: DetailReaderCursor = DetailReaderCursor(),
    private val presentation: () -> DetailReaderPresentationState?
) : AccessibilityNodeProvider() {
    fun wasQueried(id: String?): Boolean = id != null && presentation()?.queriedHeldIds?.contains(id) == true

    @Suppress("DEPRECATION")
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
        original.createAccessibilityNodeInfo(virtualViewId)?.also { node ->
            reader.record(virtualViewId, node)
            val id = node.viewIdResourceName
            val target = detailActionTarget(id)
            val state = presentation()
            if (target != null && id?.endsWith("-label") == false) actionIds[target] = virtualViewId
            if (state != null &&
                id in heldDetailActionIds &&
                node.isAccessibilityFocused &&
                node.isFocused &&
                node.isVisibleToUser &&
                !node.isEnabled
            ) {
                state.record(id)
            } else if (state != null && id?.endsWith("-pending-label") == true && node.text?.contains("when ready") == true) {
                val focused = original.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                try {
                    if (focused != null &&
                        detailActionTarget(focused.viewIdResourceName) == target &&
                        focused.viewIdResourceName in heldDetailActionIds &&
                        focused.isAccessibilityFocused &&
                        focused.isFocused &&
                        focused.isVisibleToUser
                    ) {
                        state.record(focused.viewIdResourceName)
                    }
                } finally {
                    focused?.recycle()
                }
            }
        }

    @Suppress("DEPRECATION")
    fun sendReadFailure(host: View, message: String, currentPage: Boolean): Boolean {
        if (!currentPage) return false
        val manager = host.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        if (!manager.isEnabled) return false
        val focused = original.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return false
        // Retry's own state change owns feedback while the reader remains on Retry.
        if (focused.viewIdResourceName == "detail:retry") {
            focused.recycle()
            return true
        }
        val id = reader.id
        val node = id?.let(original::createAccessibilityNodeInfo)
        val valid = node != null &&
            node == focused &&
            node.viewIdResourceName == reader.resource &&
            node.isAccessibilityFocused &&
            node.isVisibleToUser &&
            node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_NONE
        val role = node?.className
        node?.recycle()
        focused.recycle()
        if (!valid) return false
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            setSource(host, id!!)
            packageName = host.context.packageName
            className = role
            isEnabled = true
            parcelableData = Bundle().apply { putBoolean(DETAIL_FAILURE_CUE, true) }
            if (Build.VERSION.SDK_INT >= 30) {
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
                text.add(message)
            } else {
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
                contentDescription = message
            }
        }
        val sent = host.parent?.requestSendAccessibilityEvent(host, event) == true
        if (!sent) event.recycle()
        return sent
    }

    @Suppress("DEPRECATION")
    fun clearReaderFocus(currentPage: Boolean): Boolean {
        if (!currentPage) return false
        val focused = original.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return false
        val target = detailActionTarget(focused.viewIdResourceName)
        val id = target?.let(actionIds::get)
        val node = id?.let(original::createAccessibilityNodeInfo)
        val valid = app.pictureshow.core.detailReaderFocusCanClear(
            currentPage,
            target != null,
            node != null && node == focused && node.isAccessibilityFocused
        )
        node?.recycle()
        focused.recycle()
        return valid && original.performAction(id!!, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
    }

    @Suppress("DEPRECATION")
    fun sendFeedback(host: View, target: String, message: String, currentPage: Boolean = true): Boolean {
        val manager = host.context.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        if (!manager.isEnabled) return false
        val id = actionIds[target] ?: return false
        val focused = original.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return false
        val node = original.createAccessibilityNodeInfo(id)
        val valid = app.pictureshow.core.detailFeedbackCanSubmit(
            currentPage,
            node != null &&
                node == focused &&
                detailActionTarget(node.viewIdResourceName) == target &&
                node.isAccessibilityFocused &&
                node.isFocused &&
                node.isVisibleToUser &&
                node.isEnabled
        )
        val role = node?.className
        node?.recycle()
        focused.recycle()
        if (!valid) return false
        val parent = host.parent ?: return false
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            setSource(host, id)
            packageName = host.context.packageName
            className = role
            isEnabled = true
            if (Build.VERSION.SDK_INT >= 30) {
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
                text.add(message)
            } else {
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
                contentDescription = message
            }
        }
        // The accepted event belongs to AccessibilityManager, which recycles it.
        val sent = parent.requestSendAccessibilityEvent(host, event)
        if (!sent) event.recycle()
        return sent
    }

    // Internal focus polling must not manufacture evidence that the reader received a label.
    override fun findFocus(focus: Int): AccessibilityNodeInfo? = original.findFocus(focus)
    override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfo>? =
        original.findAccessibilityNodeInfosByText(text, virtualViewId)

    @Suppress("DEPRECATION")
    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        val accepted = original.performAction(virtualViewId, action, arguments)
        if (accepted &&
            action in setOf(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            )
        ) {
            val node = original.createAccessibilityNodeInfo(virtualViewId)
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS && reader.id == virtualViewId) reader.clear()
            if (node != null && action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) reader.record(virtualViewId, node)
            try {
                val target = detailActionTarget(node?.viewIdResourceName)
                val id = node?.viewIdResourceName
                if (target != null && id?.endsWith("-label") == false) actionIds[target] = virtualViewId
                if (id in heldDetailActionIds) {
                    if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS &&
                        node?.isFocused == true &&
                        node.isAccessibilityFocused &&
                        node.isVisibleToUser
                    ) {
                        presentation()?.selectedHeldIds?.add(id!!)
                    } else {
                        presentation()?.selectedHeldIds?.remove(id)
                    }
                }
            } finally {
                node?.recycle()
            }
        }
        return accepted
    }
    override fun addExtraDataToAccessibilityNodeInfo(id: Int, info: AccessibilityNodeInfo, key: String, arguments: Bundle?) {
        if (Build.VERSION.SDK_INT >= 26) original.addExtraDataToAccessibilityNodeInfo(id, info, key, arguments)
    }
}

internal class DetailPresentationDelegate(
    private var previous: AccessibilityDelegateCompat,
    private val actionIds: MutableMap<String, Int> = mutableMapOf(),
    private val attached: () -> Boolean = { true },
    private val reader: DetailReaderCursor = DetailReaderCursor(),
    private val presentation: () -> DetailReaderPresentationState?
) : AccessibilityDelegateCompat() {
    internal var disposed = false
    internal fun inherited(): AccessibilityDelegateCompat {
        while ((previous as? DetailPresentationDelegate)?.disposed == true) previous = (previous as DetailPresentationDelegate).inherited()
        return previous
    }
    private val original get() = inherited()
    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? {
        val inherited = original.getAccessibilityNodeProvider(host) ?: return null
        if (disposed || !attached()) return inherited
        val provider = inherited.provider as? AccessibilityNodeProvider ?: return null
        return AccessibilityNodeProviderCompat(
            DetailPresentationProvider(provider, actionIds, reader) {
                presentation().takeUnless { disposed }
            }
        )
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
