/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * How long the controls stay on screen without a key press. Media3's touch-oriented control view hides after five
 * seconds; on TV every button is several D-pad presses away, so the bar stays for ten, and any key restarts the
 * count. Paused playback pins the bar instead (see [ControlsAutoHide]).
 */
internal const val CONTROLS_TIMEOUT_MS = 10_000L

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
    LaunchedEffect(visible, pinned, interaction, timeoutMs) {
        if (visible && !pinned) {
            delay(timeoutMs)
            onHide()
        }
    }
}

/** The label names the next action; the state names what playback is doing now. */
@Composable
internal fun PlaybackToggle(paused: Boolean, showPlayButton: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics {
            stateDescription = if (paused) {
                "Paused"
            } else if (showPlayButton) {
                "Not playing"
            } else {
                "Playing"
            }
        }
    ) {
        Text(if (showPlayButton) "Play" else "Pause")
    }
}
