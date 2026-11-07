/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.accessibilityservice.AccessibilityServiceInfo
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
import app.pictureshow.core.isEmptyContainer
import app.pictureshow.core.isWatched
import app.pictureshow.core.resumeLabel

/** A single stable announcement, independent of artwork load, truncation and decorative badges. */
internal fun catalogAnnouncement(item: MediaItem): String = buildList {
    add(item.name)
    if (item.type == "Episode") {
        item.seriesName?.takeIf(String::isNotBlank)?.let(::add)
        item.parentIndexNumber?.let { add("Season $it") }
        item.indexNumber?.let { add("Episode $it") }
    }
    item.productionYear?.let { add(it.toString()) }
    if (item.isWatched) {
        add("Watched")
    } else if (!item.isEmptyContainer && item.userData.playbackPositionTicks > 0) {
        add(resumeLabel(item.userData.playbackPositionTicks))
    }
    item.userData.unplayedItemCount?.takeUnless { item.isEmptyContainer }?.let { add("$it unplayed") }
    if (item.userData.isFavorite) add("Favorite")
}.joinToString(", ")

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
 * Whether a person is following the screen through a reader. AccessibilityManager.isEnabled answers something else:
 * it is true for any service, such as a password manager, and for a `uiautomator dump`, whose feedback is generic.
 * TV TalkBack is driven by direction keys and may leave touch exploration off, so its spoken feedback has to count.
 */
internal fun screenReaderInUse(enabled: Boolean, touchExploration: Boolean, feedbackTypes: List<Int>): Boolean {
    val readerFeedback = AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE
    return enabled && (touchExploration || feedbackTypes.any { it and readerFeedback != 0 })
}

private fun AccessibilityManager.screenReaderInUse(): Boolean = screenReaderInUse(
    isEnabled,
    isTouchExplorationEnabled,
    getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty().map { it.feedbackType }
)

/**
 * Below API 33 nothing announces a change in the service list. isEnabled stays true when a reader joins a password
 * manager, and it turns true while a first service is still binding, before that service is in the list. So follow
 * the setting that names the enabled services, then read again while the service binds.
 */
private val SCREEN_READER_SETTLE_MILLIS = longArrayOf(500, 2_000, 5_000)

private fun watchAccessibilityServices(context: Context, manager: AccessibilityManager, handler: Handler, read: Runnable): () -> Unit {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return watchServicesFromApi33(manager, read)
    val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = settleScreenReaderRead(handler, read)
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

private fun settleScreenReaderRead(handler: Handler, read: Runnable) {
    read.run()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    handler.removeCallbacks(read)
    SCREEN_READER_SETTLE_MILLIS.forEach { handler.postDelayed(read, it) }
}

/** Live: follows a reader turned on or off during playback, on phone and TV. */
@androidx.compose.runtime.Composable
internal fun rememberScreenReaderInUse(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = androidx.compose.runtime.remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    var inUse by androidx.compose.runtime.remember(manager) { androidx.compose.runtime.mutableStateOf(manager.screenReaderInUse()) }
    androidx.compose.runtime.DisposableEffect(context, manager, lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        val read = Runnable { inUse = manager.screenReaderInUse() }
        val enabledListener = AccessibilityManager.AccessibilityStateChangeListener { settleScreenReaderRead(handler, read) }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { settleScreenReaderRead(handler, read) }
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
