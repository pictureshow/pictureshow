/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Original line marks for the TV navigation rail and detail actions, drawn in code like the app's other icons.
 * Kinds the app already draws (home, search, play, heart, check, live TV, captions) come from those sets so a mark means
 * the same thing on every screen.
 */
internal fun tvIcon(kind: String): ImageVector {
    when (kind) {
        "home", "search", "libraries" -> return touchNavIcon(kind)
        "play", "heart", "heart-outline", "check", "eye", "eye-off", "more" -> return touchActionIcon(kind)
        "live-tv" -> return liveIcon("tv")
        "captions" -> return playerActionIcon("cc")
    }
    return ImageVector.Builder(kind, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            when (kind) {
                "restart" -> {
                    // An open circle turning back on itself, with its arrowhead at the top.
                    moveTo(5.5f, 9f)
                    arcTo(7.5f, 7.5f, 0f, true, true, 4.5f, 13f)
                    moveTo(5.5f, 4.5f)
                    lineTo(5.5f, 9f)
                    lineTo(10f, 9f)
                }
                "download" -> {
                    moveTo(12f, 4f)
                    lineTo(12f, 15f)
                    moveTo(7f, 10.5f)
                    lineTo(12f, 15.5f)
                    lineTo(17f, 10.5f)
                    moveTo(5f, 19.5f)
                    lineTo(19f, 19.5f)
                }
                "settings" -> {
                    // Three sliders.
                    moveTo(4f, 7f)
                    lineTo(20f, 7f)
                    moveTo(4f, 12f)
                    lineTo(20f, 12f)
                    moveTo(4f, 17f)
                    lineTo(20f, 17f)
                    circle(9f, 7f, 2f)
                    circle(15f, 12f, 2f)
                    circle(8f, 17f, 2f)
                }
                "requests" -> {
                    circle(12f, 12f, 9f)
                    moveTo(12f, 8f)
                    lineTo(12f, 16f)
                    moveTo(8f, 12f)
                    lineTo(16f, 12f)
                }
                "customize" -> {
                    // A pencil.
                    moveTo(4f, 20f)
                    lineTo(4.5f, 16f)
                    lineTo(15.5f, 5f)
                    lineTo(19f, 8.5f)
                    lineTo(8f, 19.5f)
                    close()
                    moveTo(13f, 7.5f)
                    lineTo(16.5f, 11f)
                }
                "movies" -> {
                    roundRect(3.5f, 5f, 20.5f, 19f)
                    moveTo(8f, 5f)
                    lineTo(8f, 19f)
                    moveTo(16f, 5f)
                    lineTo(16f, 19f)
                    moveTo(3.5f, 12f)
                    lineTo(20.5f, 12f)
                }
                "shows" -> {
                    roundRect(3f, 5f, 21f, 16.5f)
                    moveTo(8.5f, 20f)
                    lineTo(15.5f, 20f)
                }
                "music" -> {
                    moveTo(9f, 17.5f)
                    lineTo(9f, 5.5f)
                    lineTo(19f, 3.5f)
                    lineTo(19f, 15.5f)
                    circle(6.5f, 17.5f, 2.5f)
                    circle(16.5f, 15.5f, 2.5f)
                }
                "collection" -> {
                    moveTo(7f, 4f)
                    lineTo(17f, 4f)
                    moveTo(5f, 7f)
                    lineTo(19f, 7f)
                    roundRect(3.5f, 10f, 20.5f, 20f)
                }
                "playlist" -> {
                    moveTo(4f, 6f)
                    lineTo(17f, 6f)
                    moveTo(4f, 11f)
                    lineTo(17f, 11f)
                    moveTo(4f, 16f)
                    lineTo(10f, 16f)
                    moveTo(14f, 14f)
                    lineTo(14f, 20f)
                    lineTo(19f, 17f)
                    close()
                }
                "photos" -> {
                    roundRect(3.5f, 5f, 20.5f, 19f)
                    moveTo(4f, 17f)
                    lineTo(9f, 12f)
                    lineTo(13f, 16f)
                    lineTo(15.5f, 13.5f)
                    lineTo(20f, 18f)
                    circle(15.5f, 9f, 1.5f)
                }
                "books" -> {
                    moveTo(12f, 6.5f)
                    lineTo(12f, 19.5f)
                    moveTo(12f, 6.5f)
                    lineTo(5f, 4.5f)
                    lineTo(3.5f, 4.5f)
                    lineTo(3.5f, 17.5f)
                    lineTo(5f, 17.5f)
                    lineTo(12f, 19.5f)
                    lineTo(19f, 17.5f)
                    lineTo(20.5f, 17.5f)
                    lineTo(20.5f, 4.5f)
                    lineTo(19f, 4.5f)
                    close()
                }
                "downloads" -> {
                    moveTo(12f, 3.5f)
                    lineTo(12f, 13f)
                    moveTo(8f, 9f)
                    lineTo(12f, 13f)
                    lineTo(16f, 9f)
                    moveTo(4f, 14f)
                    lineTo(4f, 19.5f)
                    lineTo(20f, 19.5f)
                    lineTo(20f, 14f)
                }
                else -> {
                    // folder (any other library)
                    moveTo(3.5f, 6f)
                    lineTo(9.5f, 6f)
                    lineTo(11.5f, 8f)
                    lineTo(20.5f, 8f)
                    lineTo(20.5f, 19f)
                    lineTo(3.5f, 19f)
                    close()
                }
            }
        }
    }.build()
}

/** The rail mark for a library, from its collection type. */
internal fun libraryIconKind(collectionType: String?): String = when (collectionType?.lowercase()) {
    "movies" -> "movies"
    "tvshows" -> "shows"
    "music", "musicvideos" -> "music"
    "boxsets" -> "collection"
    "playlists" -> "playlist"
    "homevideos", "photos" -> "photos"
    "books" -> "books"
    "livetv" -> "live-tv"
    else -> "folder"
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, r: Float = 2f) {
    moveTo(left + r, top)
    lineTo(right - r, top)
    arcTo(r, r, 0f, false, true, right, top + r)
    lineTo(right, bottom - r)
    arcTo(r, r, 0f, false, true, right - r, bottom)
    lineTo(left + r, bottom)
    arcTo(r, r, 0f, false, true, left, bottom - r)
    lineTo(left, top + r)
    arcTo(r, r, 0f, false, true, left + r, top)
    close()
}
