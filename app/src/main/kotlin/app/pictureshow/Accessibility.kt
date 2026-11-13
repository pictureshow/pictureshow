/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pictureshow.core.MediaItem

/** Shared catalog identity and state, identical in desktop and Android accessibility trees. */
internal fun catalogAnnouncement(item: MediaItem): String = app.pictureshow.core.catalogAnnouncement(item)

/** A read-only TV field must open the IME through an accessibility click too, without a hardware key event. */
internal fun Modifier.editWithKeyboard(onEdit: () -> Unit): Modifier = semantics {
    onClick(label = "Edit with keyboard") {
        onEdit()
        true
    }
}

/** Keep a shelf's heading and cards together before moving to the next shelf. */
internal fun Modifier.accessibilityGroup(): Modifier = semantics { isTraversalGroup = true }

/** Long technical text is D-pad focusable so it can scroll; make that focus visible too. */
@androidx.compose.runtime.Composable
internal fun Modifier.readableFocus(): Modifier {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    return this.onFocusChanged { focused = it.isFocused }
        .border(
            2.dp,
            if (focused) androidx.tv.material3.MaterialTheme.colorScheme.border else androidx.compose.ui.graphics.Color.Transparent
        )
        .padding(4.dp).focusable()
}

/**
 * Whether a person reads or operates the screen through an assistive service. AccessibilityManager.isEnabled answers
 * something else: it is true for any service, such as a password manager, and for a `uiautomator dump`.
 * TV TalkBack is driven by direction keys and may leave touch exploration off, so its spoken feedback has to count.
 * Switch Access and Voice Access declare generic feedback, like the password managers, and differ by acting on the
 * screen through gestures. No password manager we read declares that, and a dump's service is not in the list at all.
 * A non-assistive app that declares gestures counts too: its viewer gets pinned controls, which is the safer mistake.
 * The capability is a compile-time constant from API 24; on API 23 no service can have it, so that check is just false.
 */
@SuppressLint("InlinedApi")
internal fun assistiveServiceInUse(enabled: Boolean, touchExploration: Boolean, services: List<AccessibilityServiceInfo>): Boolean {
    val readerFeedback = AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE
    val actsOnTheScreen = AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
    return enabled &&
        (touchExploration || services.any { it.feedbackType and readerFeedback != 0 || it.capabilities and actsOnTheScreen != 0 })
}

private fun AccessibilityManager.assistiveServiceInUse(): Boolean = assistiveServiceInUse(
    isEnabled,
    isTouchExplorationEnabled,
    getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
)

/**
 * Below API 33 nothing announces a change in the service list. isEnabled stays true when a reader joins a password
 * manager, and it turns true while a first service is still binding, before that service is in the list. So follow
 * the setting that names the enabled services, then read again while the service binds.
 */
private val ASSISTIVE_SERVICE_SETTLE_MILLIS = longArrayOf(500, 2_000, 5_000)

private fun watchAccessibilityServices(context: Context, manager: AccessibilityManager, handler: Handler, read: Runnable): () -> Unit {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return watchServicesFromApi33(manager, read)
    val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = settleAssistiveServiceRead(handler, read)
    }
    val setting = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    context.contentResolver.registerContentObserver(setting, false, observer)
    return { context.contentResolver.unregisterContentObserver(observer) }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun watchServicesFromApi33(manager: AccessibilityManager, read: Runnable): () -> Unit {
    val listener = AccessibilityManager.AccessibilityServicesStateChangeListener { read.run() }
    manager.addAccessibilityServicesStateChangeListener(listener)
    return { manager.removeAccessibilityServicesStateChangeListener(listener) }
}

private fun settleAssistiveServiceRead(handler: Handler, read: Runnable) {
    read.run()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    handler.removeCallbacks(read)
    ASSISTIVE_SERVICE_SETTLE_MILLIS.forEach { handler.postDelayed(read, it) }
}

/** Live: follows a reader turned on or off during playback, on phone and TV. */
@androidx.compose.runtime.Composable
internal fun rememberAssistiveServiceInUse(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = androidx.compose.runtime.remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    var inUse by androidx.compose.runtime.remember(manager) { androidx.compose.runtime.mutableStateOf(manager.assistiveServiceInUse()) }
    androidx.compose.runtime.DisposableEffect(context, manager, lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        val read = Runnable { inUse = manager.assistiveServiceInUse() }
        val enabledListener = AccessibilityManager.AccessibilityStateChangeListener { settleAssistiveServiceRead(handler, read) }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { settleAssistiveServiceRead(handler, read) }
        // The viewer may come back from the system's accessibility settings after the settle reads ran out.
        val resumeObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) read.run() }
        manager.addAccessibilityStateChangeListener(enabledListener)
        manager.addTouchExplorationStateChangeListener(touchListener)
        val stopWatchingServices = watchAccessibilityServices(context, manager, handler, read)
        lifecycle.addObserver(resumeObserver)
        read.run()
        onDispose {
            lifecycle.removeObserver(resumeObserver)
            stopWatchingServices()
            manager.removeTouchExplorationStateChangeListener(touchListener)
            manager.removeAccessibilityStateChangeListener(enabledListener)
            handler.removeCallbacks(read)
        }
    }
    return inUse
}
