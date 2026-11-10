/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

internal const val RECONNECT_STREAM = "Stream from the server"
internal const val RECONNECT_STAY = "Stay on the download"

/** Closing the panel any way is a no. An app going to the background says no without restarting playback behind itself. */
@Composable
internal fun OfflineReconnectLifetime(reconnect: OfflineReconnect) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(reconnect, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) reconnect.stay(resume = false) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            reconnect.stay()
        }
    }
}

/** TV: replaces the rows inside the panel that was already open, so no window opens or closes behind the viewer. */
@Composable
internal fun OfflineReconnectTvContent(state: OfflineReconnectState, title: String, onStream: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val latest by rememberUpdatedState(state)
    Column(Modifier.background(Color(0xF510131C)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() }
        )
        Text(
            offlineReconnectMessage(state),
            Modifier.testTag("reconnect:message").semantics { liveRegion = LiveRegionMode.Polite }
        )
        when (state) {
            OfflineReconnectState.Idle, is OfflineReconnectState.Leaving -> Unit
            is OfflineReconnectState.Offer -> {
                Button(onClick = onStream, Modifier.focusRequester(first).testTag("reconnect:stream")) { Text(RECONNECT_STREAM) }
                Button(onClick = onClose, Modifier.testTag("reconnect:stay")) { Text(RECONNECT_STAY) }
            }
            is OfflineReconnectState.Refused ->
                Button(onClick = onClose, Modifier.focusRequester(first).testTag("reconnect:ok")) { Text("OK") }
            is OfflineReconnectState.Checking, is OfflineReconnectState.Connecting ->
                Button(onClick = onClose, Modifier.focusRequester(first).testTag("reconnect:stay")) { Text(RECONNECT_STAY) }
        }
    }
    // The row that held focus just left the window; each state hands it to its first action.
    LaunchedEffect(state::class) {
        if (latest != OfflineReconnectState.Idle && latest !is OfflineReconnectState.Leaving) first.requestFocus()
    }
}

/** Touch: the same words and tags inside the bottom sheet. */
@Composable
internal fun OfflineReconnectTouchContent(state: OfflineReconnectState, onStream: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Text(
            offlineReconnectMessage(state),
            Modifier.testTag("reconnect:message").semantics { liveRegion = LiveRegionMode.Polite }
        )
        when (state) {
            OfflineReconnectState.Idle, is OfflineReconnectState.Leaving -> Unit
            is OfflineReconnectState.Offer -> {
                TextButton(onClick = onStream, Modifier.fillMaxWidth().testTag("reconnect:stream")) {
                    androidx.compose.material3.Text(RECONNECT_STREAM)
                }
                TextButton(onClick = onClose, Modifier.fillMaxWidth().testTag("reconnect:stay")) {
                    androidx.compose.material3.Text(RECONNECT_STAY)
                }
            }
            is OfflineReconnectState.Refused -> TextButton(onClick = onClose, Modifier.fillMaxWidth().testTag("reconnect:ok")) {
                androidx.compose.material3.Text("OK")
            }
            is OfflineReconnectState.Checking, is OfflineReconnectState.Connecting ->
                TextButton(onClick = onClose, Modifier.fillMaxWidth().testTag("reconnect:stay")) {
                    androidx.compose.material3.Text(RECONNECT_STAY)
                }
        }
    }
}

/** The real player's side of a handoff. */
internal class OutputReconnectPlayer(
    private val output: Media3PlaybackOutput,
    private val tracks: () -> Pair<Int?, Int?>,
    private val closeSession: (() -> Unit) -> Unit
) : OfflineReconnectPlayer {
    override val paused get() = output.position().paused
    override val positionMs get() = output.position().milliseconds
    override val audioIndex get() = tracks().first
    override val subtitleIndex get() = tracks().second
    override fun pause() = output.pause()
    override fun resume() {
        output.player.value?.play()
    }
    override fun close(next: () -> Unit) = closeSession(next)
}
