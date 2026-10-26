/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem

/** Touch owns presentation only; the same host as TV owns every playback transition. */
@Composable
internal fun TouchPlayer(
    account: Account,
    item: MediaItem,
    startMs: Long,
    catalog: CatalogSession,
    onCurrentItem: (MediaItem) -> Unit = {},
    onExit: () -> Unit
) {
    var current by remember(account.key, item.id) { mutableStateOf(item) }
    var start by remember(account.key, item.id) { mutableLongStateOf(startMs) }
    LaunchedEffect(current) { onCurrentItem(current) }
    TouchPlayerWindow()
    key(current.id) {
        val host = rememberPlaybackHost(account, current, start, catalog, onNext = { next ->
            start = next.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000
            current = next
        }, onExit = onExit)
        TouchPlayerContent(account, host)
    }
}

internal fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}

@Composable
private fun TouchPlayerWindow() {
    val activity = LocalContext.current.playerActivity()
    val configuration = LocalConfiguration.current
    val containerSize = LocalWindowInfo.current.containerSize
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val orientation = activity?.requestedOrientation
        val behavior = controller?.systemBarsBehavior
        val bars = window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
        val statusVisible = bars?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = bars?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // Dialogs and background/foreground transitions can reveal bars on the same activity.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            if (orientation != null) activity?.requestedOrientation = orientation
            if (behavior != null) controller?.systemBarsBehavior = behavior
            if (statusVisible) {
                controller?.show(
                    WindowInsetsCompat.Type.statusBars()
                )
            } else {
                controller?.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (navigationVisible) {
                controller?.show(
                    WindowInsetsCompat.Type.navigationBars()
                )
            } else {
                controller?.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    LaunchedEffect(
        activity,
        configuration.orientation,
        containerSize,
        configuration.densityDpi
    ) {
        androidx.compose.runtime.withFrameNanos { }
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
    }
}
