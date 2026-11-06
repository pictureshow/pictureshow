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

/** Small original action marks: a check, two chevrons and a heart, outlined or filled. */
internal fun touchActionIcon(kind: String): ImageVector = ImageVector.Builder(
    name = kind,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
        when (kind) {
            "check" -> {
                moveTo(4f, 12.5f)
                lineTo(6f, 10.5f)
                lineTo(10f, 14.5f)
                lineTo(18f, 6.5f)
                lineTo(20f, 8.5f)
                lineTo(10f, 18.5f)
                close()
            }
            "cast", "casting" -> {
                // A screen with its lower-left corner open for the signal.
                moveTo(3f, 7f)
                lineTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(16f, 20f)
                lineTo(16f, 18f)
                lineTo(19f, 18f)
                lineTo(19f, 6f)
                lineTo(5f, 6f)
                lineTo(5f, 7f)
                close()
                moveTo(3f, 17f)
                arcTo(3f, 3f, 0f, false, true, 6f, 20f)
                lineTo(3f, 20f)
                close()
                moveTo(3f, 13f)
                arcTo(7f, 7f, 0f, false, true, 10f, 20f)
                lineTo(8f, 20f)
                arcTo(5f, 5f, 0f, false, false, 3f, 15f)
                close()
                moveTo(3f, 9f)
                arcTo(11f, 11f, 0f, false, true, 14f, 20f)
                lineTo(12f, 20f)
                arcTo(9f, 9f, 0f, false, false, 3f, 11f)
                close()
                if (kind == "casting") {
                    // The screen lit: something is playing over there.
                    moveTo(7f, 8f)
                    lineTo(17f, 8f)
                    lineTo(17f, 16f)
                    lineTo(15f, 16f)
                    arcTo(12.65f, 12.65f, 0f, false, false, 7f, 8f)
                    close()
                }
            }
            "chevron-down" -> {
                moveTo(6f, 8f)
                lineTo(12f, 14f)
                lineTo(18f, 8f)
                lineTo(20f, 10f)
                lineTo(12f, 18f)
                lineTo(4f, 10f)
                close()
            }
            "chevron-right" -> {
                moveTo(8.5f, 4f)
                lineTo(16.5f, 12f)
                lineTo(8.5f, 20f)
                lineTo(6.5f, 18f)
                lineTo(12.5f, 12f)
                lineTo(6.5f, 6f)
                close()
            }
            "previous" -> {
                moveTo(5f, 5f)
                lineTo(8f, 5f)
                lineTo(8f, 19f)
                lineTo(5f, 19f)
                close()
                moveTo(19f, 5f)
                lineTo(19f, 19f)
                lineTo(9f, 12f)
                close()
            }
            "next" -> {
                moveTo(16f, 5f)
                lineTo(19f, 5f)
                lineTo(19f, 19f)
                lineTo(16f, 19f)
                close()
                moveTo(5f, 5f)
                lineTo(15f, 12f)
                lineTo(5f, 19f)
                close()
            }
            "play" -> {
                moveTo(7f, 4f)
                lineTo(20f, 12f)
                lineTo(7f, 20f)
                close()
            }
            "pause" -> {
                moveTo(6f, 4f)
                lineTo(10f, 4f)
                lineTo(10f, 20f)
                lineTo(6f, 20f)
                close()
                moveTo(14f, 4f)
                lineTo(18f, 4f)
                lineTo(18f, 20f)
                lineTo(14f, 20f)
                close()
            }
            else -> {
                moveTo(12f, 21f)
                curveTo(5f, 15.5f, 2f, 12f, 2f, 8f)
                curveTo(2f, 5f, 4.5f, 3f, 7f, 3f)
                curveTo(9f, 3f, 11f, 4f, 12f, 6f)
                curveTo(13f, 4f, 15f, 3f, 17f, 3f)
                curveTo(19.5f, 3f, 22f, 5f, 22f, 8f)
                curveTo(22f, 12f, 19f, 15.5f, 12f, 21f)
                close()
                if (kind == "heart-outline") {
                    moveTo(12f, 18.4f)
                    curveTo(17.4f, 14f, 20f, 11.2f, 20f, 8f)
                    curveTo(20f, 6.1f, 18.5f, 5f, 17f, 5f)
                    curveTo(15.4f, 5f, 13.9f, 5.9f, 12f, 9f)
                    curveTo(10.1f, 5.9f, 8.6f, 5f, 7f, 5f)
                    curveTo(5.5f, 5f, 4f, 6.1f, 4f, 8f)
                    curveTo(4f, 11.2f, 6.6f, 14f, 12f, 18.4f)
                    close()
                }
            }
        }
    }
}.build()
