/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/** View choice stays apart from time travel, with both directions visible at narrow widths. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveGuideViewMode(linear: Boolean, tv: Boolean, onChange: (Boolean) -> Unit) {
    if (tv) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveChip(
                "Grid",
                !linear,
                true,
                modifier = Modifier.height(
                    (
                        36 *
                            androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
                        ).dp
                )
            ) { onChange(false) }
            LiveChip(
                "List",
                linear,
                true,
                modifier = Modifier.height(
                    (
                        36 *
                            androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
                        ).dp
                )
            ) { onChange(true) }
        }
    } else {
        androidx.compose.material3.SingleChoiceSegmentedButtonRow {
            listOf("Grid", "List").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = linear == (index == 1),
                    onClick = { onChange(index == 1) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, 2)
                ) { androidx.compose.material3.Text(label) }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveGuideDateBar(start: Long, now: Long, tv: Boolean, enabled: Boolean, onChange: (Long) -> Unit) {
    val time = liveTime()
    val zone = ZoneId.systemDefault()
    var days by remember { mutableStateOf(false) }
    val atNow = start == liveGuideWindowStart(now)
    Row(
        Modifier.fillMaxWidth().height(if (tv) 36.dp else 48.dp).testTag("live:guide-time"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tv) 0.dp else 4.dp)
    ) {
        LiveQuietIcon("earlier", "Earlier", tv, enabled) { onChange(start - LiveTvViewModel.WINDOW) }
        val label = if (tv) "${time.day(start)} ▾" else "${time.day(start)} · ${time.clock(start)} ▾"
        LiveQuietAction(label, tv, Modifier.weight(1f).testTag("live:guide-day"), enabled) { days = true }
        LiveQuietIcon("later", "Later", tv, enabled) { onChange(start + LiveTvViewModel.WINDOW) }
        if (!atNow) LiveQuietAction("Now", tv, Modifier.testTag("live:guide-now"), enabled) { onChange(liveGuideWindowStart(now)) }
    }
    if (days) {
        LiveMenu(
            "Guide day",
            tv,
            { days = false },
            (0L..6L).map { day ->
                val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(day)
                val target = if (day == 0L) liveGuideWindowStart(now) else date.atStartOfDay(zone).toInstant().toEpochMilli()
                time.day(target) to {
                    days = false
                    onChange(target)
                }
            }
        )
    }
}

@Composable
internal fun LiveQuietAction(label: String, tv: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    if (tv) {
        androidx.tv.material3.Button(
            onClick,
            modifier.height(36.dp),
            enabled = enabled,
            colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = Color.Transparent),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
        ) { androidx.tv.material3.Text(label, maxLines = 1, style = androidx.tv.material3.MaterialTheme.typography.bodySmall) }
    } else {
        androidx.compose.material3.TextButton(onClick, modifier.height(48.dp), enabled = enabled) {
            androidx.compose.material3.Text(label, maxLines = 1)
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveQuietIcon(kind: String, label: String, tv: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val icon = remember(kind) { liveIcon(kind) }
    if (tv) {
        androidx.tv.material3.IconButton(
            onClick,
            Modifier.size(32.dp),
            enabled = enabled,
            colors = androidx.tv.material3.IconButtonDefaults.colors(containerColor = Color.Transparent)
        ) { androidx.tv.material3.Icon(icon, label, Modifier.size(20.dp)) }
    } else {
        androidx.compose.material3.IconButton(onClick, enabled = enabled) {
            androidx.compose.material3.Icon(icon, label, Modifier.size(24.dp))
        }
    }
}
