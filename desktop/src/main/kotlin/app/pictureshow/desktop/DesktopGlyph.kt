/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small local Material-style glyphs for desktop actions; keeps desktop free of an icons dependency. */
object DesktopGlyph {
    val TransportBack by lazy { outlineGlyph("TransportBack") { moveTo(5f,12f); curveTo(5f,16.4f,8.6f,20f,13f,20f); curveTo(17.4f,20f,21f,16.4f,21f,12f); curveTo(21f,7.6f,17.4f,4f,13f,4f); curveTo(10f,4f,7.3f,5.6f,5.9f,8f); moveTo(6f,4f); lineTo(6f,8f); lineTo(10f,8f) } }
    val Play by lazy { glyph("Play") { moveTo(7f,4f); lineTo(20f,12f); lineTo(7f,20f); close() } }
    val Pause by lazy { glyph("Pause") { moveTo(6f,4f); lineTo(10f,4f); lineTo(10f,20f); lineTo(6f,20f); close(); moveTo(14f,4f); lineTo(18f,4f); lineTo(18f,20f); lineTo(14f,20f); close() } }
    val TransportForward by lazy { outlineGlyph("TransportForward") { moveTo(19f,12f); curveTo(19f,16.4f,15.4f,20f,11f,20f); curveTo(6.6f,20f,3f,16.4f,3f,12f); curveTo(3f,7.6f,6.6f,4f,11f,4f); curveTo(14f,4f,16.7f,5.6f,18.1f,8f); moveTo(18f,4f); lineTo(18f,8f); lineTo(14f,8f) } }
    val Audio by lazy { glyph("Audio") { moveTo(3f,9f); lineTo(7f,9f); lineTo(12f,5f); lineTo(12f,19f); lineTo(7f,15f); lineTo(3f,15f); close(); moveTo(15f,9f); curveTo(17f,11f,17f,13f,15f,15f); close(); moveTo(18f,6f); curveTo(22f,10f,22f,14f,18f,18f); lineTo(16f,16f); curveTo(19f,13f,19f,11f,16f,8f); close() } }
    val Subtitles by lazy { glyph("Subtitles") { moveTo(3f,5f); lineTo(21f,5f); lineTo(21f,19f); lineTo(3f,19f); close(); moveTo(6f,9f); lineTo(18f,9f); lineTo(18f,11f); lineTo(6f,11f); close(); moveTo(6f,14f); lineTo(13f,14f); lineTo(13f,16f); lineTo(6f,16f); close() } }
    val Stop by lazy { glyph("Stop") { moveTo(5f,5f); lineTo(19f,5f); lineTo(19f,19f); lineTo(5f,19f); close() } }
    val Back by lazy { glyph("Back") { moveTo(20f,10f); lineTo(7f,10f); lineTo(13f,4f); lineTo(10f,1f); lineTo(1f,12f); lineTo(10f,23f); lineTo(13f,20f); lineTo(7f,14f); lineTo(20f,14f); close() } }
    val Fullscreen by lazy { glyph("Fullscreen") { moveTo(3f,3f); lineTo(10f,3f); lineTo(10f,6f); lineTo(6f,6f); lineTo(6f,10f); lineTo(3f,10f); close(); moveTo(14f,3f); lineTo(21f,3f); lineTo(21f,10f); lineTo(18f,10f); lineTo(18f,6f); lineTo(14f,6f); close(); moveTo(3f,14f); lineTo(6f,14f); lineTo(6f,18f); lineTo(10f,18f); lineTo(10f,21f); lineTo(3f,21f); close(); moveTo(18f,14f); lineTo(21f,14f); lineTo(21f,21f); lineTo(14f,21f); lineTo(14f,18f); lineTo(18f,18f); close() } }
    val Keyboard by lazy { glyph("Keyboard") { moveTo(2f,5f); lineTo(22f,5f); lineTo(22f,19f); lineTo(2f,19f); close(); moveTo(5f,8f); lineTo(8f,8f); lineTo(8f,11f); lineTo(5f,11f); close(); moveTo(10f,8f); lineTo(13f,8f); lineTo(13f,11f); lineTo(10f,11f); close(); moveTo(15f,8f); lineTo(18f,8f); lineTo(18f,11f); lineTo(15f,11f); close(); moveTo(5f,13f); lineTo(8f,13f); lineTo(8f,16f); lineTo(5f,16f); close(); moveTo(10f,13f); lineTo(18f,13f); lineTo(18f,16f); lineTo(10f,16f); close() } }
    val Watched by lazy { glyph("Watched") { moveTo(12f,1f); arcTo(11f,11f,0f,true,true,11.9f,23f); arcTo(11f,11f,0f,false,true,12f,1f); close(); moveTo(5f,12f); lineTo(8f,9f); lineTo(11f,12f); lineTo(17f,6f); lineTo(20f,9f); lineTo(11f,18f); close() } }
    val Favorite by lazy { glyph("Favorite") { moveTo(12f,21f); curveTo(10f,19f,2f,14f,2f,8f); curveTo(2f,3f,9f,1f,12f,7f); curveTo(15f,1f,22f,3f,22f,8f); curveTo(22f,14f,14f,19f,12f,21f); close() } }

    val WatchedOutline by lazy { outlineGlyph("WatchedOutline") { moveTo(12f,1f); arcTo(11f,11f,0f,true,true,11.9f,23f); arcTo(11f,11f,0f,false,true,12f,1f); close(); moveTo(6f,12f); lineTo(9f,15f); lineTo(18f,7f) } }
    val FavoriteOutline by lazy { outlineGlyph("FavoriteOutline") { moveTo(12f,21f); curveTo(10f,19f,2f,14f,2f,8f); curveTo(2f,3f,9f,1f,12f,7f); curveTo(15f,1f,22f,3f,22f,8f); curveTo(22f,14f,14f,19f,12f,21f); close() } }
    val ChevronDown by lazy { glyph("ChevronDown") { moveTo(5f,8f); lineTo(12f,15f); lineTo(19f,8f); lineTo(21f,10f); lineTo(12f,19f); lineTo(3f,10f); close() } }
    val Remove by lazy { glyph("Remove") { moveTo(7f,3f); lineTo(17f,3f); lineTo(17f,5f); lineTo(21f,5f); lineTo(21f,7f); lineTo(3f,7f); lineTo(3f,5f); lineTo(7f,5f); close(); moveTo(5f,8f); lineTo(19f,8f); lineTo(18f,21f); lineTo(6f,21f); close(); moveTo(9f,10f); lineTo(11f,10f); lineTo(11f,19f); lineTo(9f,19f); close(); moveTo(13f,10f); lineTo(15f,10f); lineTo(15f,19f); lineTo(13f,19f); close() } }

}

private fun glyph(name: String, body: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {

    path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) { body() }
}.build()

private fun outlineGlyph(name: String, body: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) { body() }

}.build()
