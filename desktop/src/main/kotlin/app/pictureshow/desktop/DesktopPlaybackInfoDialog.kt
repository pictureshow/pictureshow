/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** PLAY-26: one scroll region; the Close action is always visible, with focus restored by the opener. */
@Composable
internal fun DesktopPlaybackInfoDialog(sections: List<PlaybackInfoSection>, close: () -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    AlertDialog(onDismissRequest = close, modifier = Modifier.widthIn(max = 720.dp).onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false
        else if (it.key == Key.Escape) { close(); true }
        else {
            val target = playbackInfoScrollTarget(it.key, scroll.value, scroll.maxValue)
            if (target != null) { scope.launch { scroll.scrollTo(target) }; true } else false
        }
    }, title = { Text("Playback info") }, text = {
        Box(Modifier.heightIn(max = 360.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 12.dp, bottom = 12.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                sections.forEachIndexed { i, section ->
                    if (i > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        section.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        section.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        section.fields.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(label, Modifier.width(140.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            if (scroll.value > 0) HorizontalDivider(Modifier.align(Alignment.TopCenter))
            if (scroll.value < scroll.maxValue) HorizontalDivider(Modifier.align(Alignment.BottomCenter))
        }
    }, confirmButton = {
        TextButton(onClick = close, modifier = Modifier.focusRequester(focus).controlFocus(allowInModal = true).probe("playback-info-close")) { Text("Close") }
    })
}

/** Both the window dialog and the native Wayland overlay consume scroll keys before transport keys. */
internal fun playbackInfoScrollTarget(key: Key, value: Int, max: Int): Int? {
    return when (key) {
    Key.DirectionDown -> value + 64; Key.DirectionUp -> value - 64
    Key.PageDown -> value + 300; Key.PageUp -> value - 300
    Key.MoveHome -> 0; Key.MoveEnd -> max
    else -> return null
    }.coerceIn(0, max)
}
