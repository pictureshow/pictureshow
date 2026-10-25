/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.withTimeoutOrNull

internal val LocalRestoringConfirmationFocus = staticCompositionLocalOf { false }

/** Offer the intended action first, then reveal the rest once TV TalkBack acknowledges it. */
@Composable
internal fun ConfirmationFocus(target: String?, onRestored: () -> Unit = {}, content: @Composable () -> Unit) {
    val accessible = rememberAccessibilityEnabled()
    var ready by remember(target, accessible) { mutableStateOf(target == null || !accessible) }
    val host = LocalView.current
    LaunchedEffect(target, accessible) {
        if (target != null && accessible) {
            val started = SystemClock.uptimeMillis()
            var visible = false
            var clickable = false
            var inputFocused = false
            var labelMatches = false
            val confirmed = withTimeoutOrNull(1_500) {
                while (true) {
                    val node = host.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    visible = node?.isVisibleToUser == true
                    clickable = node?.isClickable == true
                    inputFocused = node?.isFocused == true
                    labelMatches = node?.text?.toString() == target || node?.contentDescription?.toString()?.endsWith(target) == true
                    // Compose moves a merging button's label to a virtual child. Other actions
                    // are hidden here, so both cursors on the visible action prove arrival.
                    val matches = node.isFocusedAction()
                    @Suppress("DEPRECATION")
                    node?.recycle()
                    if (matches) return@withTimeoutOrNull true
                    withFrameNanos { }
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    "ConfirmationFocus",
                    "phase=" + (if (target == "Cancel") "arrival" else "return") +
                        " handoff=" + (if (confirmed == true) "acknowledged" else "timeout") +
                        " elapsedMs=" + (SystemClock.uptimeMillis() - started) +
                        " visible=$visible clickable=$clickable inputFocused=$inputFocused labelMatch=$labelMatches"
                )
            }
            ready = true
        }
        onRestored()
    }
    CompositionLocalProvider(LocalRestoringConfirmationFocus provides !ready, content = content)
}
