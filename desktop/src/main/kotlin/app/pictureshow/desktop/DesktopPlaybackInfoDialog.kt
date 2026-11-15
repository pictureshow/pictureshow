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
            val amount = when (it.key) {
                Key.DirectionDown -> 64; Key.DirectionUp -> -64
                Key.PageDown -> 300; Key.PageUp -> -300
                Key.MoveHome -> -scroll.maxValue; Key.MoveEnd -> scroll.maxValue
                else -> 0
            }
            if (amount != 0) { scope.launch { scroll.scrollTo((scroll.value + amount).coerceIn(0, scroll.maxValue)) }; true } else false
        }
    }, title = { Text("Playback info") }, text = {
        Box(Modifier.heightIn(max = 360.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                sections.forEachIndexed { i, section ->
                    if (i > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        section.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }, confirmButton = {
        TextButton(onClick = close, modifier = Modifier.focusRequester(focus).controlFocus(allowInModal = true).probe("playback-info-close")) { Text("Close") }
    })
}
