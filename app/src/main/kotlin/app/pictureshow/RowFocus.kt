/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * A press along a row stays in that row. Past its end Compose searches the whole screen and accepts anything whose
 * edges lie further that way, so a short shelf hands Right to an unrelated button below it, Sign out on home.
 */
@Composable
internal fun Modifier.staysInRow(first: Boolean, last: Boolean): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val forward = if (rtl) Key.DirectionLeft else Key.DirectionRight
    val backward = if (rtl) Key.DirectionRight else Key.DirectionLeft
    return onPreviewKeyEvent {
        it.type == KeyEventType.KeyDown && (last && it.key == forward || first && it.key == backward)
    }
}
