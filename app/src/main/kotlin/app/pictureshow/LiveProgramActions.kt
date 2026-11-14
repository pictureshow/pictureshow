/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A single next step on touch; one focus-driven action group on TV. */
@Composable
internal fun LiveProgramActions(
    tv: Boolean,
    canWatch: Boolean,
    canRecord: Boolean,
    hasSeries: Boolean,
    watch: @Composable (Modifier, Boolean) -> Unit,
    record: @Composable (Modifier, Boolean) -> Unit,
    series: @Composable (Modifier, Boolean) -> Unit
) {
    val recordModifier = Modifier.testTag("live:program:record")
    val seriesModifier = Modifier.testTag("live:program:series")
    if (tv) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canWatch) watch(Modifier, true)
            if (canRecord) record(recordModifier, !canWatch)
            if (hasSeries) series(seriesModifier, !canWatch && !canRecord)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canWatch) watch(Modifier.fillMaxWidth(), true)
            if (canWatch && (canRecord || hasSeries)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canRecord) record(recordModifier.weight(1f), false)
                    if (hasSeries) series(seriesModifier.weight(1f), false)
                }
            } else {
                if (canRecord) record(recordModifier.fillMaxWidth(), true)
                if (hasSeries) series(seriesModifier.fillMaxWidth(), !canRecord)
            }
        }
    }
}

@Composable
internal fun LiveProgramAction(
    label: String,
    tv: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    playIcon: Boolean = false,
    onClick: () -> Unit
) {
    if (tv) {
        LiveAction(label, true, modifier, enabled, primary, onClick)
    } else {
        val content: @Composable RowScope.() -> Unit = {
            if (playIcon) {
                val icon = remember { touchActionIcon("play") }
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
        if (primary) {
            Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
        } else {
            OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
        }
    }
}
