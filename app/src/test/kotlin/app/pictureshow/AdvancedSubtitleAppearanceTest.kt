/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import app.pictureshow.core.playback.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AdvancedSubtitleAppearanceTest {
    @Test fun textAndImageOpacityAreIndependentAndNeverModifyDecoderBitmap() {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val style = SubtitleStyle(foreground = "Yellow", textOpacity = 60, imageOpacity = 40, edge = "Outline", edgeColor = "Cyan")
        val appearance = SubtitleAppearance(styling = style)
        val resolved = appearance.resolve(1f, CaptionStyleCompat.DEFAULT).style
        assertEquals(Color.argb(153, 255, 255, 0), resolved.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, resolved.edgeType)
        assertEquals(Color.CYAN, resolved.edgeColor)
        val rendered = appearance.cues(listOf(Cue.Builder().setBitmap(image).setSize(0.4f).build())).single().bitmap!!
        assertEquals(102, Color.alpha(rendered.getPixel(0, 0)))
        assertEquals(255, Color.alpha(image.getPixel(0, 0)))
    }

    @Test fun explicitTextPositionAndWeightLeaveImageCoordinatesIntact() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val text = Cue.Builder().setText("A line").setLine(0.9f, Cue.LINE_TYPE_FRACTION).build()
        val image = Cue.Builder().setBitmap(bitmap).setLine(0.7f, Cue.LINE_TYPE_FRACTION).setSize(0.2f).build()
        val result = SubtitleAppearance(styling = SubtitleStyle(position = "Top", bold = true, italic = true)).cues(listOf(text, image))
        assertEquals(0.08f, result[0].line)
        assertEquals(0.7f, result[1].line)
        val spans = (result[0].text as Spanned).getSpans(0, 6, StyleSpan::class.java)
        assertTrue(spans.any { it.style == Typeface.BOLD_ITALIC })
    }

    @Test fun selectedBackgroundColorAndAlphaReachRenderer() {
        val style = SubtitleAppearance(
            SubtitleSize.Normal,
            SubtitleBackground.Box,
            SubtitleStyle(backgroundColor = "Cyan", backgroundOpacity = 40)
        ).resolve(1f, CaptionStyleCompat.DEFAULT).style
        assertEquals(Color.argb(102, 0, 255, 255), style.backgroundColor)
    }
}
