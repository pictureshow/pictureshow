/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Playback choices precede transfer and independent library toggles. */
internal fun recordingDetailActionOrder(hasFromStart: Boolean, hasDownload: Boolean): List<String> = buildList {
    add("primary")
    if (hasFromStart) add("from-start")
    if (hasDownload) add("download")
    add("played")
    add("favorite")
}

internal const val RECORDING_DETAIL_MUTATION_SIZE_DP = 48
internal const val RECORDING_DETAIL_LABEL_MIN_HEIGHT_DP = 28

/** Reserve caption height, while its label never changes the action row's pitch. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun RecordingDetailMutationButton(
    target: String,
    label: String,
    selected: Boolean,
    focused: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    containerModifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        containerModifier.width(RECORDING_DETAIL_MUTATION_SIZE_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.tv.material3.IconButton(
            onClick,
            modifier.size(RECORDING_DETAIL_MUTATION_SIZE_DP.dp),
            enabled = enabled,
            colors = androidx.tv.material3.IconButtonDefaults.colors(
                containerColor = if (selected) {
                    androidx.tv.material3.MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.tv.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)
                },
                contentColor = if (selected) {
                    androidx.tv.material3.MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    androidx.tv.material3.MaterialTheme.colorScheme.onSurface
                }
            )
        ) {
            androidx.tv.material3.Icon(
                touchActionIcon(
                    if (target == "played") {
                        "check"
                    } else if (selected) {
                        "heart"
                    } else {
                        "heart-outline"
                    }
                ),
                label,
                Modifier.size(24.dp)
            )
        }
        Box(
            Modifier.heightIn(min = 32.dp).padding(top = 8.dp).alpha(if (focused) 1f else 0f).clearAndSetSemantics {},
            contentAlignment = Alignment.TopCenter
        ) {
            androidx.tv.material3.Text(
                label,
                modifier = Modifier.wrapContentWidth(unbounded = true),
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
