/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import java.util.Locale

@Composable
internal fun SubtitleDelayControls(delayMs: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    fun adjust(amount: Int) = onChange((delayMs + amount).coerceIn(-10_000, 10_000))
    Column(
        Modifier.onPreviewKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) {
                false
            } else {
                when (event.key) {
                    Key.DirectionLeft -> {
                        adjust(-100)
                        true
                    }
                    Key.DirectionRight -> {
                        adjust(100)
                        true
                    }
                    else -> false
                }
            }
        }
    ) {
        Text("Subtitle delay: ${String.format(Locale.ROOT, "%+.1f s", delayMs / 1000.0)}")
        Text("Positive delay shows subtitles later. Applies to this playback only. ← / → adjusts.")
        Button(onClick = { adjust(-100) }, enabled = enabled && delayMs > -10_000, modifier = Modifier.testTag("delay:earlier")) {
            Text("Subtitles earlier (−0.1 s)")
        }
        Button(onClick = { adjust(100) }, enabled = enabled && delayMs < 10_000, modifier = Modifier.testTag("delay:later")) {
            Text("Subtitles later (+0.1 s)")
        }
        Button(onClick = { onChange(0) }, enabled = enabled, modifier = Modifier.testTag("delay:reset")) { Text("Reset subtitle delay") }
    }
}
