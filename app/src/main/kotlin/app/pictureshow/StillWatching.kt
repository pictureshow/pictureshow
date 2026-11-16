/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.StillWatching
import app.pictureshow.core.stillWatchingDue

/**
 * When the viewer last pressed something, for "Are you still watching?" (PLAY-30) and the TV screensaver (SAVER-01).
 * The activity reports every key and touch; the player reports each episode that started by itself.
 */
internal object ViewerInput {
    @Volatile var lastInputUptimeMs: Long = SystemClock.uptimeMillis()
        private set

    @Volatile var automaticAdvances: Int = 0
        private set

    fun input() {
        lastInputUptimeMs = SystemClock.uptimeMillis()
        automaticAdvances = 0
    }

    fun automaticallyAdvanced() {
        automaticAdvances++
    }

    fun idleMs(now: Long = SystemClock.uptimeMillis()): Long = (now - lastInputUptimeMs).coerceAtLeast(0)

    /** Whether the episode ending now should ask first instead of starting the next one by itself. */
    fun stillWatchingDue(policy: StillWatching): Boolean = stillWatchingDue(policy, automaticAdvances + 1, idleMs() / 60_000)
}

/** TV: the question in the next-episode card's place. Keep watching takes focus, so OK answers it. */
@Composable
internal fun TvStillWatchingPrompt(nextName: String, onContinue: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.width(420.dp).background(Color(0xF510131C)).padding(20.dp).focusGroup().testTag("still-watching"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.tv.material3.Text(
            "Are you still watching?",
            color = Color.White,
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Assertive
            }
        )
        androidx.tv.material3.Text("Next: $nextName", color = Color(0xFFDDDDDD))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.tv.material3.Button(
                onClick = onContinue,
                scale = playerButtonScale(),
                modifier = Modifier.focusRequester(focus).testTag("still-watching:continue")
            ) { androidx.tv.material3.Text("Keep watching") }
            androidx.tv.material3.Button(
                onClick = onStop,
                scale = playerButtonScale(),
                modifier = Modifier.testTag("still-watching:stop")
            ) { androidx.tv.material3.Text("Stop") }
        }
    }
}

/** Phone and tablet: the same question as a card in the next-episode card's place. */
@Composable
internal fun TouchStillWatchingPrompt(nextName: String, onContinue: () -> Unit, onStop: () -> Unit) {
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth().testTag("still-watching"),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        shape = androidx.compose.material3.MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.Text(
                "Are you still watching?",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Assertive
                }
            )
            androidx.compose.material3.Text("Next: $nextName", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onContinue, Modifier.testTag("still-watching:continue")) {
                    androidx.compose.material3.Text("Keep watching")
                }
                androidx.compose.material3.TextButton(onClick = onStop, Modifier.testTag("still-watching:stop")) {
                    androidx.compose.material3.Text("Stop")
                }
            }
        }
    }
}
