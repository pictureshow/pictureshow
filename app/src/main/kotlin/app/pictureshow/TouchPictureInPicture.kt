/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.pictureshow.core.playback.PictureInPictureAction
import app.pictureshow.core.playback.PictureInPictureState
import app.pictureshow.core.playback.pictureInPictureActions
import app.pictureshow.core.playback.pictureInPictureAllowed
import app.pictureshow.core.playback.pictureInPictureAspect

/** What the touch player hands the picture-in-picture window (TOUCH-15). */
internal class TouchPictureInPictureRequest(
    val state: PictureInPictureState,
    val primary: String?,
    val canSeekBack: Boolean,
    val canSeekForward: Boolean,
    val videoWidth: Int,
    val videoHeight: Int,
    val pixelRatio: Double,
    /** The video's on-screen bounds in window pixels, so the window grows out of the picture rather than the screen. */
    val sourceBounds: Rect?
)

/** The window's actions reach the player through one receiver, private to this app. */
private const val PIP_ACTION = "app.pictureshow.action.PICTURE_IN_PICTURE"
private const val PIP_EXTRA = "action"

internal fun Context.supportsPictureInPicture(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** The player's side of picture-in-picture: what's showing, and a way in from More. */
internal class TouchPictureInPicture(private val enter: () -> Boolean) {
    var active by mutableStateOf(false)
        internal set

    /** More's "Picture in picture": true when the system took the window. */
    fun enterNow(): Boolean = enter()
}

/**
 * TOUCH-15: keeps the activity's picture-in-picture parameters in step with the player, enters on Home (auto-enter on
 * Android 12+, the leave hint before it), routes the window's actions to [onAction], and calls [onClosed] when the
 * window is dismissed, so the viewing stops and reports as a Stop would.
 */
@Composable
internal fun rememberTouchPictureInPicture(
    request: TouchPictureInPictureRequest,
    onAction: (String) -> Unit,
    onClosed: () -> Unit
): TouchPictureInPicture {
    val context = LocalContext.current
    val activity = context.playerActivity() as? ComponentActivity
    val supported = remember(activity) { activity?.supportsPictureInPicture() == true }
    val latestRequest by rememberUpdatedState(request)
    val latestAction by rememberUpdatedState(onAction)
    val latestClosed by rememberUpdatedState(onClosed)
    val allowed = supported && pictureInPictureAllowed(request.state)
    val pip = remember(activity) {
        TouchPictureInPicture {
            val ready = activity != null && supported && pictureInPictureAllowed(latestRequest.state)
            if (!ready || activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                false
            } else {
                val params = pictureInPictureParams(activity, latestRequest, true)
                runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
            }
        }
    }
    // The parameters follow the player: Play becomes Pause, a live edge disables Forward, the video's shape arrives.
    val actions = pictureInPictureActions(request.primary, request.canSeekBack, request.canSeekForward)
    val aspect = pictureInPictureAspect(request.videoWidth, request.videoHeight, request.pixelRatio)
    if (activity != null && supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        DisposableEffect(activity, allowed, actions, aspect, request.sourceBounds) {
            runCatching { activity.setPictureInPictureParams(pictureInPictureParams(activity, latestRequest, allowed)) }
            onDispose { }
        }
        DisposableEffect(activity) {
            onDispose {
                // A player leaving takes auto-enter with it; the next screen is not something to watch in a small window.
                runCatching { activity.setPictureInPictureParams(pictureInPictureParams(activity, latestRequest, false)) }
            }
        }
    }
    DisposableEffect(activity, supported) {
        if (activity == null || !supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@DisposableEffect onDispose { }
        pip.active = activity.isInPictureInPictureMode
        val modeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
            pip.active = info.isInPictureInPictureMode
            // Closing the window stops the activity; expanding brings it back started. Either order of events lands here.
            if (!info.isInPictureInPictureMode && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) latestClosed()
        }
        val stopObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && pip.active) latestClosed()
        }
        // Before Android 12 nothing enters by itself: Home (button or swipe) arrives as the leave hint.
        val leaveHint = Runnable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !pip.active) pip.enterNow()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra(PIP_EXTRA)?.let { latestAction(it) }
            }
        }
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        activity.lifecycle.addObserver(stopObserver)
        activity.addOnUserLeaveHintListener(leaveHint)
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(modeListener)
            activity.lifecycle.removeObserver(stopObserver)
            activity.removeOnUserLeaveHintListener(leaveHint)
            runCatching { activity.unregisterReceiver(receiver) }
        }
    }
    return pip
}

@RequiresApi(Build.VERSION_CODES.O)
private fun pictureInPictureParams(context: Context, request: TouchPictureInPictureRequest, autoEnter: Boolean): PictureInPictureParams {
    val (width, height) = pictureInPictureAspect(request.videoWidth, request.videoHeight, request.pixelRatio)
    val actions = pictureInPictureActions(request.primary, request.canSeekBack, request.canSeekForward)
    val maxActions = 3
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(width, height))
        .setActions(actions.take(maxActions).mapIndexed { index, action -> remoteAction(context, action, index) })
    request.sourceBounds?.takeIf { !it.isEmpty }?.let { builder.setSourceRectHint(it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        // Video content: a seamless resize would stretch frames while the window animates.
        builder.setSeamlessResizeEnabled(false)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        builder.setTitle(context.getString(R.string.pip_title))
    }
    return builder.build()
}

@RequiresApi(Build.VERSION_CODES.O)
private fun remoteAction(context: Context, action: PictureInPictureAction, slot: Int): RemoteAction {
    val (icon, label) = when (action.action) {
        "back10" -> R.drawable.pip_back10 to R.string.pip_back10
        "forward10" -> R.drawable.pip_forward10 to R.string.pip_forward10
        "pause" -> R.drawable.pip_pause to R.string.pip_pause
        else -> R.drawable.pip_play to R.string.pip_play
    }
    val intent = Intent(PIP_ACTION).setPackage(context.packageName).putExtra(PIP_EXTRA, action.action)
    val pending = PendingIntent.getBroadcast(context, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val text = context.getString(label)
    return RemoteAction(Icon.createWithResource(context, icon), text, text, pending).apply { isEnabled = action.enabled }
}
