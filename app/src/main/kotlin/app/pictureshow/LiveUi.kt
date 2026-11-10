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
internal fun LiveChip(label: String, selected: Boolean, tv: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    if (tv) {
        androidx.tv.material3.FilterChip(selected, onClick, enabled = enabled) { androidx.tv.material3.Text(label) }
    } else {
        androidx.compose.material3.FilterChip(selected, onClick, label = { androidx.compose.material3.Text(label) }, enabled = enabled)
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

private fun liveIcon(kind: String): ImageVector {
    if (kind == "search") return touchNavIcon("search")
    return ImageVector.Builder(kind, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
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
                }
            }
        }
    }.build()
}
