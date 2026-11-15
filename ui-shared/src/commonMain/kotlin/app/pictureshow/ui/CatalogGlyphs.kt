/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import app.pictureshow.core.MediaItem
import app.pictureshow.core.catalogPlaceholderKind

/** Line glyphs drawn here, so the shared module needs no icon library. 24-unit grid, 2-unit strokes. */
object CatalogGlyphs {
    val Check by lazy { line("Check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    val Movies by lazy {
        line("Movies") {
            // A film frame with sprocket holes down both sides.
            moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(8f, 4f); lineTo(8f, 20f); moveTo(16f, 4f); lineTo(16f, 20f)
            moveTo(4f, 8f); lineTo(8f, 8f); moveTo(4f, 12f); lineTo(8f, 12f); moveTo(4f, 16f); lineTo(8f, 16f)
            moveTo(16f, 8f); lineTo(20f, 8f); moveTo(16f, 12f); lineTo(20f, 12f); moveTo(16f, 16f); lineTo(20f, 16f)
        }
    }
    val Shows by lazy {
        line("Shows") {
            // A television on a stand.
            moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 17f); lineTo(3f, 17f); close()
            moveTo(9f, 21f); lineTo(15f, 21f); moveTo(12f, 17f); lineTo(12f, 21f)
            moveTo(8f, 2f); lineTo(12f, 6f); lineTo(16f, 2f)
        }
    }
    val LiveTv by lazy {
        line("LiveTv") {
            // A mast sending waves.
            moveTo(12f, 11f); lineTo(12f, 21f); moveTo(9f, 21f); lineTo(15f, 21f)
            moveTo(8.5f, 7.5f); curveTo(6.5f, 9.5f, 6.5f, 12.5f, 8.5f, 14.5f)
            moveTo(15.5f, 7.5f); curveTo(17.5f, 9.5f, 17.5f, 12.5f, 15.5f, 14.5f)
            moveTo(5.5f, 4.5f); curveTo(1.5f, 8.5f, 1.5f, 13.5f, 5.5f, 17.5f)
            moveTo(18.5f, 4.5f); curveTo(22.5f, 8.5f, 22.5f, 13.5f, 18.5f, 17.5f)
            moveTo(12f, 9f); lineTo(12f, 11f)
        }
    }
    val Music by lazy {
        line("Music") {
            // Two beamed notes.
            moveTo(9f, 18f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 16f)
            moveTo(9f, 18f); curveTo(9f, 20f, 4f, 20f, 4f, 18f); curveTo(4f, 16f, 9f, 16f, 9f, 18f)
            moveTo(20f, 16f); curveTo(20f, 18f, 15f, 18f, 15f, 16f); curveTo(15f, 14f, 20f, 14f, 20f, 16f)
        }
    }
    val Videos by lazy {
        line("Videos") {
            // A camcorder.
            moveTo(3f, 7f); lineTo(15f, 7f); lineTo(15f, 17f); lineTo(3f, 17f); close()
            moveTo(15f, 10.5f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(15f, 13.5f)
        }
    }
    val Photos by lazy {
        line("Photos") {
            // A framed landscape.
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            moveTo(3f, 16f); lineTo(9f, 10f); lineTo(14f, 15f); lineTo(16f, 13f); lineTo(21f, 18f)
            moveTo(16f, 9f); lineTo(16.01f, 9f)
        }
    }
    val Books by lazy {
        line("Books") {
            // An open book.
            moveTo(12f, 6f); curveTo(9f, 4f, 5f, 4f, 2f, 5f); lineTo(2f, 19f); curveTo(5f, 18f, 9f, 18f, 12f, 20f)
            curveTo(15f, 18f, 19f, 18f, 22f, 19f); lineTo(22f, 5f); curveTo(19f, 4f, 15f, 4f, 12f, 6f); lineTo(12f, 20f)
        }
    }
    val Playlists by lazy {
        line("Playlists") {
            moveTo(3f, 6f); lineTo(16f, 6f); moveTo(3f, 11f); lineTo(16f, 11f); moveTo(3f, 16f); lineTo(11f, 16f)
            moveTo(15f, 15f); lineTo(21f, 18.5f); lineTo(15f, 22f); close()
        }
    }
    val Collections by lazy {
        line("Collections") {
            // Stacked cases.
            moveTo(7f, 7f); lineTo(21f, 7f); lineTo(21f, 21f); lineTo(7f, 21f); close()
            moveTo(3f, 17f); lineTo(3f, 3f); lineTo(17f, 3f)
        }
    }
    val Folder by lazy {
        line("Folder") {
            moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        }
    }
}

/** The placeholder kind for an item without art, from the shared rule (`catalog-placeholder-kind` vectors). */
fun catalogPlaceholderType(item: MediaItem): String? = catalogPlaceholderKind(item)

fun catalogTypeGlyph(kind: String): ImageVector = when (kind) {
    "movies" -> CatalogGlyphs.Movies
    "shows" -> CatalogGlyphs.Shows
    "livetv" -> CatalogGlyphs.LiveTv
    "music" -> CatalogGlyphs.Music
    "videos" -> CatalogGlyphs.Videos
    "photos" -> CatalogGlyphs.Photos
    "books" -> CatalogGlyphs.Books
    "playlists" -> CatalogGlyphs.Playlists
    "collections" -> CatalogGlyphs.Collections
    else -> CatalogGlyphs.Folder
}

private fun line(name: String, body: PathBuilder.() -> Unit) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathFillType = PathFillType.NonZero
    ) { body() }
}.build()
