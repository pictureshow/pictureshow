/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

internal val DETAIL_PILL_HEIGHT = 52.dp
internal val DETAIL_ICON_CIRCLE_SIZE = 48.dp
internal val DETAIL_ICON_SLOT_WIDTH = 76.dp
internal val DETAIL_ICON_TOP_OFFSET = 2.dp
internal val DETAIL_PRIMARY_STABLE_WIDTH = 216.dp
internal val DETAIL_PRIMARY_WIDE_STABLE_WIDTH = 300.dp
internal val DETAIL_FROM_START_STABLE_WIDTH = 180.dp

/**
 * CAT-05/06: prominent filled primary pill (Play / Resume / Play S1:E1) or secondary translucent glass pill
 * (Play from start) that sits beside it when there is progress.
 */
@Composable
internal fun DetailPillButton(
    label: String,
    icon: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelModifier: Modifier = Modifier,
    stableWidth: Dp? = null,
    maxWidth: Dp = 360.dp
) {
    val pillHeight = with(LocalDensity.current) { maxOf(DETAIL_PILL_HEIGHT, 44.sp.toDp()) }
    val sizeModifier = if (stableWidth != null) {
        Modifier.width(stableWidth).height(pillHeight)
    } else {
        Modifier.widthIn(min = if (primary) 132.dp else 148.dp, max = maxWidth).heightIn(min = pillHeight)
    }
    val border = if (primary) {
        ButtonDefaults.border()
    } else {
        ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x47FFFFFF)), shape = CircleShape),
            focusedBorder = Border(BorderStroke(1.dp, Color.White), shape = CircleShape),
            disabledBorder = Border(BorderStroke(1.dp, Color(0x26FFFFFF)), shape = CircleShape)
        )
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(sizeModifier),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = detailPillColors(primary),
        border = border,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        if (stableWidth != null) {
            Box(
                Modifier.width(stableWidth - 40.dp).height(pillHeight - 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(tvIcon(icon), contentDescription = null, modifier = Modifier.align(Alignment.CenterStart).size(20.dp))
                Text(
                    label,
                    modifier = labelModifier.padding(horizontal = 26.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Box(Modifier.heightIn(min = pillHeight - 20.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(tvIcon(icon), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        modifier = labelModifier,
                        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * CAT-05/06: compact round icon button with a centered caption below for secondary detail actions
 * (Favorite, Download, Find subtitles, Trailer, Mark watched). Fixed slot width and font-scaled two-line caption height
 * keep sibling positions stable across label and state changes.
 */
@Composable
internal fun DetailIconActionButton(
    label: String,
    icon: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelModifier: Modifier = Modifier,
    active: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val accessible = rememberAssistiveServiceInUse()
    val captionHeight = with(LocalDensity.current) { 36.sp.toDp() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Button },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFD5DAE8),
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Color.White,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0x80E8EBF2)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (accessible) 1f else 1.06f)
    ) {
        Column(
            modifier = Modifier.width(DETAIL_ICON_SLOT_WIDTH).padding(top = DETAIL_ICON_TOP_OFFSET),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val circleBg = when {
                !enabled -> Color(0x59141824)
                focused -> Color.White
                active -> Color(0x40FFFFFF)
                else -> Color(0xB3141824)
            }
            val circleBorder = when {
                !enabled -> Color(0x26FFFFFF)
                focused -> Color.White
                active -> Color(0x88FFFFFF)
                else -> Color(0x47FFFFFF)
            }
            val iconTint = when {
                !enabled -> Color(0x73E8EBF2)
                focused -> Color(0xFF10131C)
                active -> Color.White
                else -> Color(0xFFF0F3FA)
            }
            Box(
                modifier = Modifier
                    .size(DETAIL_ICON_CIRCLE_SIZE)
                    .background(circleBg, CircleShape)
                    .border(1.dp, circleBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(tvIcon(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            val captionColor = when {
                !enabled -> Color(0x80E8EBF2)
                focused -> Color.White
                else -> Color(0xFFD5DAE8)
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(captionHeight),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    label,
                    modifier = labelModifier,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = captionColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun detailPillColors(primary: Boolean) = ButtonDefaults.colors(
    containerColor = if (primary) Color(0xFFE6E9F2) else Color(0xB3141824),
    contentColor = if (primary) Color(0xFF10131C) else Color(0xFFF0F3FA),
    focusedContainerColor = Color.White,
    focusedContentColor = Color(0xFF10131C),
    pressedContainerColor = Color(0xFFD5DAE6),
    pressedContentColor = Color(0xFF10131C),
    disabledContainerColor = if (primary) Color(0x33141824) else Color(0x59141824),
    disabledContentColor = Color(0x8CE8EBF2)
)
