/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original player marks; play/pause/Cast reuse the app's existing action vectors. */
internal fun playerActionIcon(action: String): ImageVector {
    if (action == "group") return syncIcon("group")
    if (action in setOf("play", "pause", "cast", "casting")) return touchActionIcon(action)
    return ImageVector.Builder(action, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            when (action) {
                "audio" -> {
                    moveTo(3f, 9f)
                    lineTo(7f, 9f)
                    lineTo(12f, 4f)
                    lineTo(12f, 20f)
                    lineTo(7f, 15f)
                    lineTo(3f, 15f)
                    close()
                    moveTo(15f, 6f)
                    lineTo(16.5f, 4.5f)
                    curveTo(22f, 8f, 22f, 16f, 16.5f, 19.5f)
                    lineTo(15f, 18f)
                    curveTo(19f, 15f, 19f, 9f, 15f, 6f)
                    close()
                }
                "cc" -> {
                    moveTo(2f, 5f)
                    lineTo(22f, 5f)
                    lineTo(22f, 19f)
                    lineTo(2f, 19f)
                    close()
                    moveTo(4f, 7f)
                    lineTo(4f, 17f)
                    lineTo(20f, 17f)
                    lineTo(20f, 7f)
                    close()
                    moveTo(10f, 9f)
                    lineTo(6f, 9f)
                    lineTo(6f, 15f)
                    lineTo(10f, 15f)
                    lineTo(10f, 13f)
                    lineTo(8f, 13f)
                    lineTo(8f, 11f)
                    lineTo(10f, 11f)
                    close()
                    moveTo(18f, 9f)
                    lineTo(14f, 9f)
                    lineTo(14f, 15f)
                    lineTo(18f, 15f)
                    lineTo(18f, 13f)
                    lineTo(16f, 13f)
                    lineTo(16f, 11f)
                    lineTo(18f, 11f)
                    close()
                }
                "speed" -> {
                    moveTo(3f, 17f)
                    curveTo(0f, 5f, 12f, 0f, 20f, 7f)
                    lineTo(18.5f, 8.5f)
                    curveTo(12f, 3f, 3f, 7f, 5f, 17f)
                    close()
                    moveTo(10f, 14f)
                    lineTo(20f, 5f)
                    lineTo(14f, 17f)
                    close()
                }
                "fullscreen" -> {
                    moveTo(3f, 3f)
                    lineTo(10f, 3f)
                    lineTo(10f, 5f)
                    lineTo(5f, 5f)
                    lineTo(5f, 10f)
                    lineTo(3f, 10f)
                    close()
                    moveTo(14f, 3f)
                    lineTo(21f, 3f)
                    lineTo(21f, 10f)
                    lineTo(19f, 10f)
                    lineTo(19f, 5f)
                    lineTo(14f, 5f)
                    close()
                    moveTo(3f, 14f)
                    lineTo(5f, 14f)
                    lineTo(5f, 19f)
                    lineTo(10f, 19f)
                    lineTo(10f, 21f)
                    lineTo(3f, 21f)
                    close()
                    moveTo(19f, 14f)
                    lineTo(21f, 14f)
                    lineTo(21f, 21f)
                    lineTo(14f, 21f)
                    lineTo(14f, 19f)
                    lineTo(19f, 19f)
                    close()
                }
                "portrait" -> {
                    moveTo(3f, 8f)
                    lineTo(8f, 8f)
                    lineTo(8f, 3f)
                    lineTo(10f, 3f)
                    lineTo(10f, 10f)
                    lineTo(3f, 10f)
                    close()
                    moveTo(14f, 3f)
                    lineTo(16f, 3f)
                    lineTo(16f, 8f)
                    lineTo(21f, 8f)
                    lineTo(21f, 10f)
                    lineTo(14f, 10f)
                    close()
                    moveTo(3f, 14f)
                    lineTo(10f, 14f)
                    lineTo(10f, 21f)
                    lineTo(8f, 21f)
                    lineTo(8f, 16f)
                    lineTo(3f, 16f)
                    close()
                    moveTo(14f, 14f)
                    lineTo(21f, 14f)
                    lineTo(21f, 16f)
                    lineTo(16f, 16f)
                    lineTo(16f, 21f)
                    lineTo(14f, 21f)
                    close()
                }
                "quality" -> {
                    moveTo(2f, 4f)
                    lineTo(22f, 4f)
                    lineTo(22f, 18f)
                    lineTo(2f, 18f)
                    close()
                    moveTo(4f, 6f)
                    lineTo(4f, 16f)
                    lineTo(20f, 16f)
                    lineTo(20f, 6f)
                    close()
                }
                "chapters" -> {
                    for (y in listOf(5f, 11f, 17f)) {
                        moveTo(3f, y)
                        lineTo(6f, y)
                        lineTo(6f, y + 2f)
                        lineTo(3f, y + 2f)
                        close()
                        moveTo(9f, y)
                        lineTo(21f, y)
                        lineTo(21f, y + 2f)
                        lineTo(9f, y + 2f)
                        close()
                    }
                }
                "more" -> {
                    for (y in listOf(4f, 11f, 18f)) {
                        moveTo(10f, y)
                        lineTo(14f, y)
                        lineTo(14f, y + 3f)
                        lineTo(10f, y + 3f)
                        close()
                    }
                }
                else -> {
                    val back = action == "back10"
                    if (back) {
                        moveTo(11f, 3f)
                        lineTo(3f, 9f)
                        lineTo(11f, 15f)
                        lineTo(11f, 11f)
                        curveTo(19f, 10f, 20f, 19f, 12f, 20f)
                        lineTo(12f, 22f)
                        curveTo(23f, 21f, 23f, 8f, 11f, 9f)
                        close()
                    } else {
                        moveTo(13f, 3f)
                        lineTo(21f, 9f)
                        lineTo(13f, 15f)
                        lineTo(13f, 11f)
                        curveTo(5f, 10f, 4f, 19f, 12f, 20f)
                        lineTo(12f, 22f)
                        curveTo(1f, 21f, 1f, 8f, 13f, 9f)
                        close()
                    }
                }
            }
        }
    }.build()
}
