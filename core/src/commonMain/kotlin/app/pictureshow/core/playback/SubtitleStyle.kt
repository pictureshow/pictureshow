/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlinx.serialization.Serializable

/** Portable caption choices. Authored styling is retained until the viewer explicitly overrides it. */
@Serializable
data class SubtitleStyle(
    val foreground: String = "System",
    val edge: String = "System",
    val edgeColor: String = "Black",
    val backgroundColor: String = "Black",
    val textOpacity: Int = 100,
    val backgroundOpacity: Int = 80,
    val position: String = "Authored",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val imageOpacity: Int = 100,
    val overrideEmbedded: Boolean = false
) {
    fun normalized() = copy(
        foreground = foreground.takeIf { it in COLORS } ?: "System",
        edge = edge.takeIf { it in EDGES } ?: "System",
        edgeColor = edgeColor.takeIf { it in COLORS && it != "System" } ?: "Black",
        backgroundColor = backgroundColor.takeIf { it in COLORS && it != "System" } ?: "Black",
        textOpacity = textOpacity.coerceIn(0, 100),
        backgroundOpacity = backgroundOpacity.coerceIn(0, 100),
        imageOpacity = imageOpacity.coerceIn(0, 100),
        position = position.takeIf { it in POSITIONS } ?: "Authored"
    )

    companion object {
        val COLORS = listOf("System", "White", "Yellow", "Black", "Green", "Cyan")
        val EDGES = listOf("System", "None", "Outline", "Shadow")
        val POSITIONS = listOf("Authored", "Bottom", "Middle", "Top")
    }
}
