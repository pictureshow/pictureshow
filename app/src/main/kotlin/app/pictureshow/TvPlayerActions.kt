/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private class PlayerActionsViewport(val scroll: ScrollState) {
    var bounds by mutableStateOf<Rect?>(null)
}

private val LocalPlayerActionsViewport = staticCompositionLocalOf<PlayerActionsViewport?> { null }

/** Retain every direct action for native D-pad focus; expose overflow without another focus stop. */
@Composable
internal fun TvPlayerActions(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scroll = rememberScrollState()
    val viewport = remember(scroll) { PlayerActionsViewport(scroll) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).onGloballyPositioned {
                viewport.bounds =
                    Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
            }
        ) {
            CompositionLocalProvider(LocalPlayerActionsViewport provides viewport) {
                Row(
                    Modifier.horizontalScroll(scroll).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = verticalAlignment,
                    content = content
                )
            }
        }
        // Reserve the slot even at an end, so the viewport does not move during focus-driven scrolling.
        val forward = scroll.canScrollForward
        val backward = scroll.canScrollBackward
        val pointsRight = forward != rtl
        val description = stringResource(if (pointsRight) R.string.player_more_actions_right else R.string.player_more_actions_left)
        Canvas(
            Modifier.size(24.dp).then(
                if (forward || backward) {
                    Modifier.testTag("player:actions-overflow").semantics { contentDescription = description }
                } else {
                    Modifier
                }
            )
        ) {
            if (forward || backward) {
                val direction = if (pointsRight) 1f else -1f
                val center = Offset(size.width / 2f, size.height / 2f)
                val dx = 4.dp.toPx() * direction
                val dy = 6.dp.toPx()
                val path = Path().apply {
                    moveTo(center.x - dx, center.y - dy)
                    lineTo(center.x + dx, center.y)
                    lineTo(center.x - dx, center.y + dy)
                }
                drawCircle(Color.Black.copy(alpha = .8f), radius = size.minDimension / 2f)
                drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** A cut label is not an overflow hint. Paint the button once native scrolling reveals its whole target. */
@Composable
internal fun Modifier.wholePlayerActionPaint(): Modifier {
    val viewport = LocalPlayerActionsViewport.current ?: return this
    val scrollDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var measured by remember { mutableStateOf<Pair<Rect, Int>?>(null) }
    return onGloballyPositioned {
        val position = it.positionInRoot()
        measured =
            Rect(position, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) to viewport.scroll.value
    }.graphicsLayer {
        val view = viewport.bounds
        val current = measured
        val rect = current?.let { it.first.translate((it.second - viewport.scroll.value).toFloat() * scrollDirection, 0f) }
        alpha = if (view != null && rect != null && rect.left >= view.left - .5f && rect.right <= view.right + .5f) 1f else 0f
    }
}
