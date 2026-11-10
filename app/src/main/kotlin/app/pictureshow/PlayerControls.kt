/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * How long the controls stay on screen without a key press. Media3's touch-oriented control view hides after five
 * seconds; on TV every button is several D-pad presses away, so the bar stays for ten, and any key restarts the
 * count. Paused playback pins the bar instead (see [ControlsAutoHide]).
 */
internal const val CONTROLS_TIMEOUT_MS = 10_000L

/** The system's "Time to take action" and "Time to read" settings (API 29+) can lengthen the wait, never shorten it. */
private fun recommendedControlsTimeout(context: Context, timeoutMs: Long): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return timeoutMs
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val controlsAndText = AccessibilityManager.FLAG_CONTENT_CONTROLS or
        AccessibilityManager.FLAG_CONTENT_ICONS or
        AccessibilityManager.FLAG_CONTENT_TEXT
    return manager.getRecommendedTimeoutMillis(timeoutMs.toInt(), controlsAndText).toLong()
}

/** The two playback facts the controls render, refreshed from player events rather than a polling loop. */
internal data class PlayerPlayback(val paused: Boolean, val showPlayButton: Boolean, val buffering: Boolean = false)

/**
 * Follows play/pause, buffering and end-of-stream transitions as they happen, including media keys delivered
 * through the session, which never pass through Compose keys. [onPause] lets the screen show and pin the controls;
 * [onResume] restarts the idle count so they hide again.
 */
@Composable
internal fun rememberPlayerPlayback(player: Player?, onPause: () -> Unit, onResume: () -> Unit): PlayerPlayback {
    var paused by remember(player) { mutableStateOf(player?.let { !it.playWhenReady } ?: false) }
    var buffering by remember(player) {
        mutableStateOf(
            player == null || player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
        )
    }
    var showPlayButton by remember(player) { mutableStateOf(player?.let { Util.shouldShowPlayButton(it) } ?: true) }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val wasPaused = paused
                paused = !player.playWhenReady
                buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
                showPlayButton = Util.shouldShowPlayButton(player)
                if (paused && !wasPaused) {
                    onPause()
                } else if (!paused && wasPaused) {
                    onResume()
                }
            }
        }
        player.addListener(listener)
        paused = !player.playWhenReady
        showPlayButton = Util.shouldShowPlayButton(player)
        if (paused) onPause()
        onDispose { player.removeListener(listener) }
    }
    return PlayerPlayback(paused, showPlayButton, buffering)
}

/** Hides the controls after an idle [timeoutMs] unless pinned (paused playback, an open panel or focused actions). */
@Composable
internal fun ControlsAutoHide(
    visible: Boolean,
    pinned: Boolean,
    interaction: Long,
    timeoutMs: Long = CONTROLS_TIMEOUT_MS,
    onHide: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(visible, pinned, interaction, timeoutMs) {
        if (visible && !pinned) {
            delay(recommendedControlsTimeout(context, timeoutMs))
            onHide()
        }
    }
}

/** Keep player pills inside TalkBack's native outline; remote-only focus retains TV enlargement. */
@Composable
internal fun playerButtonScale() = ButtonDefaults.scale(focusedScale = if (rememberAssistiveServiceInUse()) 1f else 1.1f)

internal fun playbackToggleState(paused: Boolean, showPlayButton: Boolean): String = if (paused) {
    "Paused"
} else if (showPlayButton) {
    "Not playing"
} else {
    "Playing"
}

/** The label names the next action; the state names what playback is doing now. */
@Composable
internal fun PlaybackToggle(
    paused: Boolean,
    showPlayButton: Boolean,
    modifier: Modifier = Modifier,
    ended: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val current = playbackToggleState(paused, showPlayButton)
    // The end of the video stops the player, which isn't the viewer pausing. A changed state on the
    // focused button is read aloud, so keep it still until focus moves; a later visit reads the real state.
    // The end sets `ended` in the same main-thread turn that releases the player, so anything the release
    // changes composes after it. A pause composed before the end was a real pause and has already been read.
    val beforeEnd = remember { arrayOf(current) }
    if (!ended) beforeEnd[0] = current
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        scale = playerButtonScale(),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.semantics {
            stateDescription = if (ended && focused) beforeEnd[0] else current
        }
    ) {
        Text(if (showPlayButton) "Play" else "Pause")
    }
}
