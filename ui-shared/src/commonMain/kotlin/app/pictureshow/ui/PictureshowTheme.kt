/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import coil3.compose.AsyncImage

/** Values from spec/design/tokens.json touch palette. Native text scaling remains active. */
@Composable
fun PictureshowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFD0BCFF), secondary = Color(0xFFCCC2DC),
        background = Color(0xFF10131C), surface = Color(0xFF10131C),
        surfaceContainer = Color(0xFF202330),
        surfaceContainerHigh = Color(0xFF262A38), surfaceContainerHighest = Color(0xFF30344A)
    )) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}
