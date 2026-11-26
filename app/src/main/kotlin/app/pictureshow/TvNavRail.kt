/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** One place the TV rail goes. [avatar] replaces the icon with a lettered circle (the signed-in account). */
internal class RailEntry(
    val key: String,
    val label: String,
    val icon: ImageVector?,
    val tag: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val modifier: Modifier = Modifier,
    val description: String = label,
    val avatar: String? = null,
    /** A gap before this entry separates groups (navigation, libraries, settings). */
    val groupStart: Boolean = false
)

internal val RailCollapsedWidth = 72.dp
private val RailExpandedWidth = 248.dp

/**
 * HOME-24: TV Home's navigation, a rail of icons along the left edge that opens into labels while it holds focus and
 * closes when focus goes back to the content. Right (or Back) returns to the content through [onExit], so the viewer
 * lands on the card they left rather than wherever spatial search would put them.
 */
@Composable
internal fun TvNavRail(entries: List<RailEntry>, onExit: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) RailExpandedWidth else RailCollapsedWidth, tween(180), label = "rail-width")
    val labelAlpha by animateFloatAsState(if (expanded) 1f else 0f, tween(if (expanded) 200 else 90), label = "rail-labels")
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    BackHandler(enabled = expanded) { onExit() }
    Box(
        modifier.fillMaxHeight().width(width).clipToBounds().background(
            if (expanded) {
                // Opaque behind the labels, so hero text underneath never reads through them; only the last edge fades.
                Brush.horizontalGradient(0f to Color(0xFA080A10), .8f to Color(0xF2080A10), 1f to Color(0x00080A10))
            } else {
                Brush.horizontalGradient(listOf(Color(0x99080A10), Color(0x00080A10)))
            }
        ).testTag("home:rail")
    ) {
        Column(
            Modifier.fillMaxHeight().width(RailExpandedWidth).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 16.dp, top = 28.dp, bottom = 20.dp)
                .onFocusChanged { expanded = it.hasFocus }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == forward) {
                        onExit()
                        true
                    } else {
                        false
                    }
                }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            entries.forEach { entry ->
                if (entry.groupStart) Spacer(Modifier.height(14.dp))
                RailItem(entry, labelAlpha)
            }
        }
    }
}

@Composable
private fun RailItem(entry: RailEntry, labelAlpha: Float) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = entry.onClick,
        enabled = entry.enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFC3C9D8),
            focusedContainerColor = Color(0xFFF1F3F8),
            focusedContentColor = Color(0xFF10131C),
            pressedContainerColor = Color(0xFFDADFEA),
            pressedContentColor = Color(0xFF10131C),
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0x80C3C9D8)
        ),
        modifier = entry.modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused }.testTag(entry.tag)
            .semantics {
                contentDescription = entry.description
                if (entry.selected) selected = true
            }
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (entry.avatar != null) {
                    Box(
                        Modifier.size(26.dp).background(if (focused) Color(0xFF3D4A80) else Color(0xFF5B6AA8), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(entry.avatar, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                } else if (entry.icon != null) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        // The current place keeps the accent colour, so the closed rail still says where the viewer is.
                        tint = if (entry.selected && !focused) Color(0xFFB8C4FF) else androidx.tv.material3.LocalContentColor.current,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                entry.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).alpha(labelAlpha)
            )
        }
    }
}

/** Left (Right in a right-to-left layout) on this card moves focus to [rail] instead of stopping at the shelf's start. */
internal fun Modifier.leadsToRail(rail: FocusRequester): Modifier = composed {
    val backward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft
    onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == backward) rail.requestFocus() else false
    }
}
