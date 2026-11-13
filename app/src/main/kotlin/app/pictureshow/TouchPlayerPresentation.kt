/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import app.pictureshow.core.sync.syncPlayerPrimaryAction

/** The edge reveals the picture; the title and native controls have a dark backing at every height. */
internal fun Modifier.touchPlayerScrim(): Modifier = drawWithCache {
    val height = size.height.coerceAtLeast(1f)
    val title = (16.dp.toPx() / height).coerceAtMost(1f)
    val actions = (64.dp.toPx() / height).coerceIn(title, 1f)
    val brush = Brush.verticalGradient(
        0f to Color.Transparent,
        (1.dp.toPx() / height).coerceAtMost(title) to Color.Transparent,
        title to Color.Black.copy(alpha = .8f),
        actions to Color.Black.copy(alpha = .9f),
        1f to Color.Black.copy(alpha = .95f),
        endY = height
    )
    onDrawBehind { drawRect(brush) }
}

internal fun touchPlayerPrimaryAction(
    sync: SyncPlayHost?,
    state: SyncPlayPresentation?,
    localShowPlayButton: Boolean
): String? = syncPlayerPrimaryAction(
    participating = sync?.participating == true,
    usable = state?.let { it.groupId != null && !it.busy && it.phase !in setOf("Joining", "Reconnecting", "Leaving", "Error") } == true,
    queueEntries = state?.queue?.size ?: 0,
    phase = state?.phase.orEmpty(),
    localShowPlayButton = localShowPlayButton
)

/** Re-read membership and group phase at the click, then send the explicit intent through the wrapper.
 * Standalone retains Media3's complete idle/end policy.
 */
internal fun Player.performTouchPrimaryAction(sync: SyncPlayHost?) {
    if (sync?.participating != true) {
        togglePlayback()
        return
    }
    when (touchPlayerPrimaryAction(sync, sync.state.value, Util.shouldShowPlayButton(this))) {
        "play" -> play()
        "pause" -> pause()
    }
}
