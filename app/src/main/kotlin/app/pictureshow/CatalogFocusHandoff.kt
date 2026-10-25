/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Both cursors have reached an action; valid while every other clickable target is hidden. */
internal fun AccessibilityNodeInfo?.isFocusedAction(): Boolean = this != null && isVisibleToUser && isClickable && isFocused

/** Keep an incoming catalog's opener as its first accessibility target, without hiding its pixels. */
@Composable
internal fun rememberCatalogFocusHandoff(returning: Boolean, target: String?, inputReady: Boolean, interacted: Boolean): Boolean {
    val accessible = rememberAccessibilityEnabled()
    val host = LocalView.current
    var acknowledged by remember { mutableStateOf(false) }
    LaunchedEffect(returning, target, inputReady, accessible, interacted) {
        if (!returning || !accessible || interacted) {
            acknowledged = true
        } else if (inputReady && target != null) {
            // Like confirmation restoration, wait for TV TalkBack to acknowledge the intended action.
            // The timeout also allows navigation with services that do not report accessibility focus.
            val started = SystemClock.uptimeMillis()
            var providerAvailable = false
            var nodeAvailable = false
            var visible = false
            var clickable = false
            var textMatches = false
            var descriptionMatches = false
            var inputFocused = false
            var frames = 0
            val confirmed = withTimeoutOrNull(1_500) {
                while (true) {
                    val provider = host.accessibilityNodeProvider
                    val node = provider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    providerAvailable = provider != null
                    nodeAvailable = node != null
                    visible = node?.isVisibleToUser == true
                    clickable = node?.isClickable == true
                    textMatches = node?.text?.toString() == target
                    descriptionMatches = node?.contentDescription?.toString() == target
                    inputFocused = node?.isFocused == true
                    frames++
                    // Compose puts a merging card/button's label on a virtual child. The focused
                    // actionable parent has no label; both cursors on that parent prove arrival.
                    val matches = node.isFocusedAction()
                    @Suppress("DEPRECATION")
                    node?.recycle()
                    if (matches) return@withTimeoutOrNull true
                    withFrameNanos { }
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    "CatalogFocus",
                    "handoff=${if (confirmed == true) "acknowledged" else "timeout"} elapsedMs=${SystemClock.uptimeMillis() - started} " +
                        "provider=$providerAvailable node=$nodeAvailable visible=$visible clickable=$clickable " +
                        "textMatch=$textMatches descriptionMatch=$descriptionMatches inputFocused=$inputFocused frames=$frames"
                )
            }
            acknowledged = true
        }
    }
    return returning && accessible && !acknowledged && !interacted
}

/** Navigation and lazy placement can outlive one frame; give an available action a bounded retry. */
internal suspend fun awaitCatalogFocus(requester: FocusRequester): Boolean = withTimeoutOrNull(1_500) {
    while (true) {
        withFrameNanos { }
        if (withContext(Dispatchers.Main.immediate) { requester.requestFocus() }) return@withTimeoutOrNull true
    }
} == true

/** Input and accessibility have independent cursors; exclude fallback actions from both during arrival. */
internal fun Modifier.catalogFocusTarget(exposed: Boolean, inputReady: () -> Boolean = { true }): Modifier =
    // Read live state during focus search: the first arrow releases the gate in preview handling,
    // before recomposition can replace the modifier. That same arrow must still move focus.
    focusProperties { canFocus = inputReady() }.then(
        if (exposed) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
    )

/** A silent input receiver keeps the remote usable while a saved opener's data is still loading. */
@Composable
internal fun CatalogLoadingFocus(needed: Boolean, onNavigate: () -> Unit) {
    if (!needed) return
    val focus = remember { FocusRequester() }
    Box(
        Modifier.size(0.dp).focusRequester(focus).onPreviewKeyEvent {
            if (it.key in
                listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)
            ) {
                if (it.type == KeyEventType.KeyDown) onNavigate()
                true
            } else {
                false
            }
        }.focusTarget()
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
