/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small original navigation marks, kept in code alongside their destinations. */
internal fun touchNavIcon(route: String): ImageVector = ImageVector.Builder(
    name = route,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
        when (route) {
            "home" -> {
                moveTo(3f, 11f)
                lineTo(12f, 3f)
                lineTo(21f, 11f)
                lineTo(19f, 11f)
                lineTo(19f, 21f)
                lineTo(14f, 21f)
                lineTo(14f, 14f)
                lineTo(10f, 14f)
                lineTo(10f, 21f)
                lineTo(5f, 21f)
                lineTo(5f, 11f)
                close()
            }
            "libraries" -> {
                moveTo(3f, 5f)
                lineTo(9f, 5f)
                lineTo(9f, 21f)
                lineTo(3f, 21f)
                close()
                moveTo(11f, 3f)
                lineTo(16f, 3f)
                lineTo(16f, 21f)
                lineTo(11f, 21f)
                close()
                moveTo(18f, 7f)
                lineTo(21f, 7f)
                lineTo(21f, 21f)
                lineTo(18f, 21f)
                close()
            }
            "search" -> {
                moveTo(10f, 2f)
                arcTo(8f, 8f, 0f, true, true, 4.6f, 15.9f)
                arcTo(8f, 8f, 0f, false, true, 10f, 2f)
                close()
                moveTo(10f, 4f)
                arcTo(6f, 6f, 0f, true, false, 10f, 16f)
                arcTo(6f, 6f, 0f, false, false, 10f, 4f)
                close()
                moveTo(15f, 14f)
                lineTo(22f, 21f)
                lineTo(20f, 23f)
                lineTo(13f, 16f)
                close()
            }
            else -> {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 7f)
                lineTo(3f, 7f)
                close()
                moveTo(3f, 11f)
                lineTo(21f, 11f)
                lineTo(21f, 13f)
                lineTo(3f, 13f)
                close()
                moveTo(3f, 17f)
                lineTo(21f, 17f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
                moveTo(7f, 3f)
                lineTo(10f, 3f)
                lineTo(10f, 9f)
                lineTo(7f, 9f)
                close()
                moveTo(15f, 9f)
                lineTo(18f, 9f)
                lineTo(18f, 15f)
                lineTo(15f, 15f)
                close()
                moveTo(7f, 15f)
                lineTo(10f, 15f)
                lineTo(10f, 21f)
                lineTo(7f, 21f)
                close()
            }
        }
    }
}.build()
