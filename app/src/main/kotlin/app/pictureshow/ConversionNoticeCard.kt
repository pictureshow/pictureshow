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
import androidx.compose.runtime.DisposableEffect
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
import app.pictureshow.core.playback.PlayDecision
import kotlinx.coroutines.delay

internal const val CONVERSION_NOTICE_MS = 10_000L

/** The TV app names the device this way everywhere (Settings "This TV", "This TV's audio output"). */
internal const val CONVERSION_NOTICE_DEVICE = "this TV"

/** Down always opens the player controls (OK may skip an intro instead); Playback info is a button there. */
internal const val CONVERSION_NOTICE_ACTION = "Press Down, then choose Playback info."

/** What the player composes: the viewer's switch (SET-11) decides whether a qualifying decision becomes a notice. */
@Composable
internal fun PlayerConversionNotice(
    decision: PlayDecision?,
    subtitleIndex: Int,
    preferences: PlaybackPreferences,
    allowed: Boolean,
    modifier: Modifier = Modifier,
    onDetails: () -> Unit
) {
    val notice = decision?.takeIf { preferences.conversionNotice }?.let {
        ConversionNotice.of(it, subtitleIndex, CONVERSION_NOTICE_DEVICE)
    }
    ConversionNoticeHost(notice, allowed, modifier, onDetails)
}

/**
 * PLAY-27 host: shows the first qualifying notice of a viewing once, for [CONVERSION_NOTICE_MS].
 * Keep this composed for the whole viewing; its memory is what makes the notice once per viewing.
 * [notice] is the current decision's notice: when a later decision no longer converts (null), a notice
 * that already appeared leaves at once and stays gone for the viewing, so it never states a conversion
 * that has stopped. [allowed] hides it (without restarting it) while playback is not visibly running,
 * a panel is open or a top-positioned subtitle is on screen. Only its first appearance is a live region,
 * so a screen reader hears it once even when a panel hides it and it comes back.
 */
@Composable
internal fun ConversionNoticeHost(notice: ConversionNotice?, allowed: Boolean, modifier: Modifier = Modifier, onDetails: () -> Unit) {
    // 0 waiting, 1 showing, 2 done for this viewing.
    var phase by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<ConversionNotice?>(null) }
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(notice, allowed) {
        if (phase == 0 && notice != null && allowed) {
            shown = notice
            phase = 1
        } else if (phase == 1 && notice == null) {
            phase = 2
        }
    }
    LaunchedEffect(phase) {
        if (phase == 1) {
            delay(CONVERSION_NOTICE_MS)
            phase = 2
        }
    }
    val current = shown
    // `notice != null` hides it in the same frame the conversion stops, before the effect retires it.
    if (phase == 1 && allowed && current != null && notice != null) {
        ConversionNoticeCard(current, modifier, announce = !announced, onDetails = onDetails)
        DisposableEffect(Unit) { onDispose { announced = true } }
    }
}

/** Never focusable: the remote keeps its current target and playback continues underneath. */
@Composable
internal fun ConversionNoticeCard(
    notice: ConversionNotice,
    modifier: Modifier = Modifier,
    announce: Boolean = true,
    onDetails: () -> Unit
) {
    Column(
        modifier.widthIn(max = 560.dp).testTag("player:conversion-notice")
            .semantics(mergeDescendants = true) {
                if (announce) liveRegion = LiveRegionMode.Polite
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
        Text("${notice.reason} $CONVERSION_NOTICE_ACTION", style = MaterialTheme.typography.bodyLarge)
    }
}
