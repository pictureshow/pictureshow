/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

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
            withTimeoutOrNull(1_500) {
                while (true) {
                    val node = host.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    val matches = node?.let {
                        it.isVisibleToUser &&
                            it.isClickable &&
                            (it.text?.toString() == target || it.contentDescription?.toString()?.endsWith(target) == true)
                    } == true
                    @Suppress("DEPRECATION")
                    node?.recycle()
                    if (matches) break
                    withFrameNanos { }
                }
            }
            ready = true
        }
        onRestored()
    }
    CompositionLocalProvider(LocalRestoringConfirmationFocus provides !ready, content = content)
}
