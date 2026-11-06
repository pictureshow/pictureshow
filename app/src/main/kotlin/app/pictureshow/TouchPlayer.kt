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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import java.util.WeakHashMap

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
    TouchPlayerWindow()
    PlayerViewing(account, item, startMs, onCurrentItem) { current, start, onNext ->
        val host = rememberPlaybackHost(account, current, start, catalog, onNext = onNext, onExit = onExit)
        TouchPlayerContent(account, host)
    }
}

internal fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}

/**
 * What players on screen hold of the window. Two overlap while one route replaces another (the next downloaded episode,
 * a download moving to a stream): the first takes what to give back, and only the last gives it. Each keeping its own
 * copy made the second one's "what to give back" the first one's lock, so the screen stayed landscape after playback.
 */
internal class TouchPlayerWindowHold<T : Any> {
    private var holders = 0
    private var saved: T? = null

    fun acquire(read: () -> T, take: () -> Unit) {
        if (holders++ == 0) {
            saved = read()
            take()
        }
    }

    fun release(restore: (T) -> Unit) {
        if (--holders == 0) {
            saved?.let(restore)
            saved = null
        }
    }
}

private class TouchPlayerBars(val behavior: Int?, val status: Boolean, val navigation: Boolean)

private class TouchPlayerWindowHolds {
    val orientation = TouchPlayerWindowHold<Int>()
    val bars = TouchPlayerWindowHold<TouchPlayerBars>()
}

/** One per activity, touched only from composition's main thread. */
private val touchPlayerWindowHolds = WeakHashMap<Activity, TouchPlayerWindowHolds>()

@Composable
internal fun TouchPlayerWindow() {
    val activity = LocalContext.current.playerActivity()
    val configuration = LocalConfiguration.current
    val containerSize = LocalWindowInfo.current.containerSize
    // Its own effect, keyed on the rule: unfolding mid-play hands the lock back, and folding takes it up again (TOUCH-10).
    val locksOrientation = touchPlayerLocksOrientation(configuration.smallestScreenWidthDp)
    // What the player last asked for, the viewer's toggle included, so folding again takes up that rather than landscape.
    val request = remember { intArrayOf(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) }
    val holds = activity?.let { touchPlayerWindowHolds.getOrPut(it) { TouchPlayerWindowHolds() } }
    DisposableEffect(activity, locksOrientation) {
        val hold = holds?.orientation?.takeIf { locksOrientation }
        if (activity != null) hold?.acquire({ activity.requestedOrientation }) { activity.requestedOrientation = request[0] }
        onDispose {
            if (activity != null) {
                hold?.release { before ->
                    request[0] = activity.requestedOrientation
                    activity.requestedOrientation = before
                }
            }
        }
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        holds?.bars?.acquire({
            val bars = window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
            TouchPlayerBars(
                controller?.systemBarsBehavior,
                bars?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
                bars?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
            )
        }) {}
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // Dialogs and background/foreground transitions can reveal bars on the same activity.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            holds?.bars?.release { before ->
                before.behavior?.let { controller?.systemBarsBehavior = it }
                if (before.status) {
                    controller?.show(
                        WindowInsetsCompat.Type.statusBars()
                    )
                } else {
                    controller?.hide(WindowInsetsCompat.Type.statusBars())
                }
                if (before.navigation) {
                    controller?.show(
                        WindowInsetsCompat.Type.navigationBars()
                    )
                } else {
                    controller?.hide(WindowInsetsCompat.Type.navigationBars())
                }
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
