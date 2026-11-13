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

/** Original, simple transport marks. Their buttons supply localized accessible names. */
internal fun syncIcon(kind: String): ImageVector = ImageVector.Builder(kind, 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
        when (kind) {
            "play", "next", "previous" -> {
                if (kind == "previous") {
                    moveTo(17f, 5f)
                    lineTo(7f, 12f)
                    lineTo(17f, 19f)
                    close()
                    moveTo(4f, 5f)
                    lineTo(6f, 5f)
                    lineTo(6f, 19f)
                    lineTo(4f, 19f)
                    close()
                } else {
                    moveTo(6f, 5f)
                    lineTo(17f, 12f)
                    lineTo(6f, 19f)
                    close()
                    if (kind == "next") {
                        moveTo(18f, 5f)
                        lineTo(20f, 5f)
                        lineTo(20f, 19f)
                        lineTo(18f, 19f)
                        close()
                    }
                }
            }
            "pause" -> {
                moveTo(6f, 5f)
                lineTo(10f, 5f)
                lineTo(10f, 19f)
                lineTo(6f, 19f)
                close()
                moveTo(14f, 5f)
                lineTo(18f, 5f)
                lineTo(18f, 19f)
                lineTo(14f, 19f)
                close()
            }
            "close", "remove" -> {
                moveTo(5f, 3f)
                lineTo(12f, 10f)
                lineTo(19f, 3f)
                lineTo(21f, 5f)
                lineTo(14f, 12f)
                lineTo(21f, 19f)
                lineTo(19f, 21f)
                lineTo(12f, 14f)
                lineTo(5f, 21f)
                lineTo(3f, 19f)
                lineTo(10f, 12f)
                lineTo(3f, 5f)
                close()
            }
            "warning" -> {
                moveTo(12f, 2f)
                lineTo(23f, 22f)
                lineTo(1f, 22f)
                close()
                moveTo(11f, 8f)
                lineTo(11f, 15f)
                lineTo(13f, 15f)
                lineTo(13f, 8f)
                close()
                moveTo(11f, 17f)
                lineTo(11f, 19f)
                lineTo(13f, 19f)
                lineTo(13f, 17f)
                close()
            }
            else -> {
                moveTo(9f, 3f)
                arcTo(4f, 4f, 0f, true, true, 8.99f, 3f)
                close()
                moveTo(2f, 21f)
                lineTo(2f, 18f)
                curveTo(2f, 12f, 16f, 12f, 16f, 18f)
                lineTo(16f, 21f)
                close()
                moveTo(17f, 5f)
                curveTo(23f, 5f, 23f, 12f, 17f, 12f)
                lineTo(17f, 10f)
                curveTo(20f, 10f, 20f, 7f, 17f, 7f)
                close()
                moveTo(18f, 15f)
                curveTo(24f, 15f, 24f, 19f, 24f, 21f)
                lineTo(20f, 21f)
                lineTo(20f, 18f)
                close()
            }
        }
    }
}.build()
