/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.playback.ConversionNotice
import kotlinx.coroutines.delay

internal const val CONVERSION_NOTICE_MS = 10_000L

/**
 * PLAY-27 host: shows the first qualifying notice of a viewing once, for [CONVERSION_NOTICE_MS].
 * Keep this composed for the whole viewing; its memory is what makes the notice once per viewing.
 * [allowed] hides it (without restarting it) while playback is not visibly running or a panel is open.
 */
@Composable
internal fun ConversionNoticeHost(notice: ConversionNotice?, allowed: Boolean, modifier: Modifier = Modifier, onDetails: () -> Unit) {
    // 0 waiting, 1 showing, 2 done for this viewing.
    var phase by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<ConversionNotice?>(null) }
    LaunchedEffect(notice, allowed) {
        if (phase == 0 && notice != null && allowed) {
            shown = notice
            phase = 1
        }
    }
    LaunchedEffect(phase) {
        if (phase == 1) {
            delay(CONVERSION_NOTICE_MS)
            phase = 2
        }
    }
    val current = shown
    if (phase == 1 && allowed && current != null) ConversionNoticeCard(current, modifier, onDetails)
}

/** Never focusable: the remote keeps its current target and playback continues underneath. */
@Composable
internal fun ConversionNoticeCard(notice: ConversionNotice, modifier: Modifier = Modifier, onDetails: () -> Unit) {
    Column(
        modifier.widthIn(max = 560.dp).testTag("player:conversion-notice")
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                onClick(label = "Open Playback info") {
                    onDetails()
                    true
                }
            }
            .playerTouchClick(onDetails)
            .background(Color(0xE610131C)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(notice.title, style = MaterialTheme.typography.titleMedium)
        Text(notice.message, style = MaterialTheme.typography.bodyMedium)
        Text(ConversionNotice.DETAILS, style = MaterialTheme.typography.bodySmall)
    }
}
