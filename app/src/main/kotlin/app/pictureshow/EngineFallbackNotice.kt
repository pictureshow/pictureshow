/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** PLAY-61's notice, word for word on every platform with a second engine. */
internal const val ENGINE_FALLBACK_NOTICE = "MPV couldn't decode this video on this device; playing with the built-in player"
internal const val ENGINE_FALLBACK_NOTICE_MS = 8_000L

/**
 * PLAY-61: after mpv gives way to the built-in player, says so once the built-in player has a picture, for
 * [ENGINE_FALLBACK_NOTICE_MS]. Never focusable: the remote keeps its target and playback continues underneath.
 */
@Composable
internal fun EngineFallbackNotice(host: PlaybackHostState, shown: Boolean, modifier: Modifier = Modifier, large: Boolean = true) {
    if (!host.engineFallbackNotice || !shown) return
    LaunchedEffect(Unit) {
        delay(ENGINE_FALLBACK_NOTICE_MS)
        host.engineFallbackNotice = false
    }
    BasicText(
        ENGINE_FALLBACK_NOTICE,
        modifier.widthIn(max = 560.dp).testTag("player:engine-fallback-notice")
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(Color(0xE610131C)).padding(20.dp),
        style = TextStyle(color = Color.White, fontSize = if (large) 20.sp else 16.sp)
    )
}
