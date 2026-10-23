/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// The window background is dark (styles.xml) and screens don't sit on a tv-material Surface, so plain Text would take
// LocalContentColor's default, which is black. Buttons and cards still set their own content colors.
@Composable
internal fun PictureshowTheme(content: @Composable () -> Unit) {
    // The default #938F99 border is 2.95:1 against the #49454F card surface.
    MaterialTheme(colorScheme = darkColorScheme(border = Color(0xFF9A95A1))) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}
