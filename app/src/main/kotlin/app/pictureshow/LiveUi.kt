/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LiveBackground = Color(0xFF10131C)
internal val LiveMuted = Color(0xFFC3C9D8)
internal val LiveAccent = Color(0xFFB8C4FF)

@Composable
internal fun LiveText(
    text: String,
    tv: Boolean,
    modifier: Modifier = Modifier,
    heading: Boolean = false,
    warning: Boolean = false,
    small: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    val target = modifier.then(if (heading) Modifier.semantics { heading() } else Modifier)
    val color = if (warning) Color(0xFFFFCCB8) else Color.Unspecified
    if (tv) {
        androidx.tv.material3.Text(
            text,
            target,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = with(androidx.tv.material3.MaterialTheme.typography) {
                when {
                    heading -> headlineSmall
                    small -> bodySmall
                    else -> bodyLarge
                }
            }
        )
    } else {
        androidx.compose.material3.Text(
            text,
            target,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = with(androidx.compose.material3.MaterialTheme.typography) {
                when {
                    heading -> headlineSmall
                    small -> bodySmall
                    else -> bodyLarge
                }
            }
        )
    }
}

/** Requests only an attached target, including the first item after a load and the exact Back destination. */
@Composable
internal fun Modifier.liveFocus(key: String, wanted: Boolean, enabled: Boolean = true, onFocused: () -> Unit = {}): Modifier {
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(key, wanted, enabled) {
        if (wanted && enabled) {
            repeat(8) {
                withFrameNanos { }
                if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }
    return focusRequester(focus).onFocusChanged { if (it.isFocused) onFocused() }.testTag("live:$key")
}

/** Actions only: tabs, list items, filters and program cells use their own native roles. */
@Composable
internal fun LiveAction(label: String, tv: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    if (tv) {
        androidx.tv.material3.Button(
            onClick,
            modifier.heightIn(min = 40.dp),
            enabled = enabled,
            contentPadding = PaddingValues(16.dp, 10.dp)
        ) {
            androidx.tv.material3.Text(label)
        }
    } else {
        androidx.compose.material3.Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) {
            androidx.compose.material3.Text(label)
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveChip(
    label: String,
    selected: Boolean,
    tv: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (tv) {
        androidx.tv.material3.FilterChip(selected, onClick, modifier = modifier, enabled = enabled) { androidx.tv.material3.Text(label) }
    } else {
        androidx.compose.material3.FilterChip(selected, onClick, label = {
            androidx.compose.material3.Text(label)
        }, modifier = modifier, enabled = enabled)
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveIcon(kind: String, description: String, tv: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val icon = remember(kind) { liveIcon(kind) }
    if (tv) {
        androidx.tv.material3.IconButton(onClick, enabled = enabled) { androidx.tv.material3.Icon(icon, description, Modifier.size(22.dp)) }
    } else {
        androidx.compose.material3.IconButton(onClick, enabled = enabled) {
            androidx.compose.material3.Icon(icon, description, Modifier.size(24.dp))
        }
    }
}

internal fun liveIcon(kind: String): ImageVector {
    if (kind == "search") return touchNavIcon("search")
    return ImageVector.Builder(kind, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            when (kind) {
                "back", "earlier", "later" -> {
                    val right = kind == "later"
                    moveTo(if (right) 8f else 16f, 4f)
                    lineTo(if (right) 16f else 8f, 12f)
                    lineTo(if (right) 8f else 16f, 20f)
                    lineTo(if (right) 6f else 18f, 18f)
                    lineTo(if (right) 12f else 12f, 12f)
                    lineTo(if (right) 6f else 18f, 6f)
                    close()
                }
                "trash" -> {
                    moveTo(5f, 6f)
                    lineTo(19f, 6f)
                    lineTo(18f, 22f)
                    lineTo(6f, 22f)
                    close()
                    moveTo(3f, 3f)
                    lineTo(9f, 3f)
                    lineTo(9f, 1f)
                    lineTo(15f, 1f)
                    lineTo(15f, 3f)
                    lineTo(21f, 3f)
                    lineTo(21f, 5f)
                    lineTo(3f, 5f)
                    close()
                }
                "warning" -> {
                    moveTo(12f, 2f)
                    lineTo(23f, 22f)
                    lineTo(1f, 22f)
                    close()
                    moveTo(11f, 8f)
                    lineTo(13f, 8f)
                    lineTo(13f, 15f)
                    lineTo(11f, 15f)
                    close()
                    moveTo(11f, 17f)
                    lineTo(13f, 17f)
                    lineTo(13f, 19f)
                    lineTo(11f, 19f)
                    close()
                }
                "more" -> {
                    listOf(5f, 12f, 19f).forEach { x ->
                        moveTo(x, 10f)
                        arcTo(2f, 2f, 0f, true, true, x, 14f)
                        arcTo(2f, 2f, 0f, true, true, x, 10f)
                        close()
                    }
                }
                "refresh" -> {
                    moveTo(20f, 4f)
                    lineTo(20f, 11f)
                    lineTo(13f, 11f)
                    lineTo(16f, 8f)
                    arcTo(6f, 6f, 0f, true, false, 17f, 16f)
                    lineTo(19f, 17f)
                    arcTo(8f, 8f, 0f, true, true, 17.5f, 6.5f)
                    close()
                }
                else -> {
                    moveTo(12f, 2f)
                    lineTo(15f, 8f)
                    lineTo(22f, 9f)
                    lineTo(17f, 14f)
                    lineTo(18f, 21f)
                    lineTo(12f, 18f)
                    lineTo(6f, 21f)
                    lineTo(7f, 14f)
                    lineTo(2f, 9f)
                    lineTo(9f, 8f)
                    close()
                    if (kind == "star-outline") {
                        val points = listOf(
                            12f to 2f, 15f to 8f, 22f to 9f, 17f to 14f, 18f to 21f, 12f to 18f, 6f to 21f, 7f to 14f,
                            2f to 9f, 9f to 8f
                        )
                        points.forEachIndexed { index, (x, y) ->
                            val px = 12f + (x - 12f) * .65f
                            val py = 12f + (y - 12f) * .65f
                            if (index == 0) moveTo(px, py) else lineTo(px, py)
                        }
                        close()
                    }
                }
            }
        }
    }.build()
}

@Composable
internal fun LiveFavorite(checked: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    androidx.compose.material3.IconToggleButton(checked, { onClick() }, enabled = enabled) {
        androidx.compose.material3.Icon(
            remember(checked) { liveIcon(if (checked) "star" else "star-outline") },
            if (checked) "Remove favorite" else "Add favorite",
            Modifier.size(24.dp)
        )
    }
}

@Composable
internal fun LiveRecordingDot(tv: Boolean) {
    val color = Color(0xFFFF6B6B)
    if (tv) {
        androidx.tv.material3.Text("●", color = color, style = androidx.tv.material3.MaterialTheme.typography.labelSmall)
    } else {
        androidx.compose.material3.Text("●", color = color, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}
