/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.provider.Settings
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SubtitlePresentationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun systemDefaultsFollowCaptioningManagerAndExplicitSizeOverridesItsScale() {
        Settings.Secure.putInt(context.contentResolver, "accessibility_captioning_enabled", 1)
        Settings.Secure.putFloat(context.contentResolver, "accessibility_captioning_font_scale", 1.6f)
        val view = SubtitleView(context)
        SubtitleAppearance().applyTo(view, context)
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.6f, field(view, "defaultTextSize"))
        SubtitleAppearance(size = SubtitleSize.Large).applyTo(view, context)
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.25f, field(view, "defaultTextSize"))
    }

    @Test
    fun eachSizeAndBackgroundMapsToDistinctUsablePresentation() {
        val base = CaptionStyleCompat(Color.YELLOW, Color.BLUE, Color.RED, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.GREEN, null)
        assertSame(base, SubtitleAppearance().resolve(1.4f, base).style)
        val sizes = listOf(SubtitleSize.Small, SubtitleSize.Normal, SubtitleSize.Large, SubtitleSize.ExtraLarge)
        val fractions = sizes.map { SubtitleAppearance(size = it).resolve(3f, base).textFraction }
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f).map { SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * it }, fractions)
        for (background in listOf(SubtitleBackground.None, SubtitleBackground.Shadow, SubtitleBackground.Box)) {
            val style = SubtitleAppearance(background = background).resolve(1f, base).style
            assertEquals(Color.YELLOW, style.foregroundColor)
            assertEquals(Color.TRANSPARENT, style.windowColor)
            assertEquals(if (background == SubtitleBackground.Box) Color.argb(204, 0, 0, 0) else Color.TRANSPARENT, style.backgroundColor)
            val edge = if (background ==
                SubtitleBackground.Shadow
            ) {
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            } else {
                CaptionStyleCompat.EDGE_TYPE_NONE
            }
            assertEquals(edge, style.edgeType)
        }
    }

    @Test
    fun bitmapCoordinatesAndScaleSurviveEveryTextOverride() {
        val view = SubtitleView(context)
        view.layout(0, 0, 1920, 800)
        view.getChildAt(0).layout(0, 0, 1920, 800)
        val bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val cue = Cue.Builder().setBitmap(bitmap).setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.3f).setBitmapHeight(0.1f).build()
        view.setCues(listOf(cue))
        val canvas = Canvas(Bitmap.createBitmap(1920, 800, Bitmap.Config.ARGB_8888))
        for (size in SubtitleSize.entries) {
            for (background in SubtitleBackground.entries) {
                SubtitleAppearance(size, background).applyTo(view, context)
                view.draw(canvas)
                val painters = field(view.getChildAt(0), "painters") as List<*>
                assertEquals(Rect(672, 600, 1248, 680), field(painters.first()!!, "bitmapRect"))
            }
        }
    }

    @Test
    fun letterboxAndPillarboxFramesUseVideoDimensionsAndPixelAspect() {
        assertEquals(Rect(0, 140, 1920, 940), subtitleVideoFrame(1920, 1080, VideoSize(1920, 800)))
        assertEquals(Rect(240, 0, 1680, 1080), subtitleVideoFrame(1920, 1080, VideoSize(4, 3)))
        assertEquals(Rect(0, 156, 1920, 924), subtitleVideoFrame(1920, 1080, VideoSize(720, 576, 0, 2f)))
        assertEquals(Rect(0, 0, 1920, 1080), subtitleVideoFrame(1920, 1080, VideoSize.UNKNOWN))
    }

    @Test
    fun bottomOverlaysMoveTextAndBitmapCuesAboveTheirTopAndRestoreWhenHidden() {
        val video = subtitleVideoFrame(1920, 1080, VideoSize(1920, 800))
        for (overlayTop in listOf(800, 620, 460)) {
            val safe = subtitleSafeFrame(video, overlayTop, 16)
            assertEquals(video.left, safe.left)
            assertEquals(video.top, safe.top)
            assertEquals(overlayTop - 16, safe.bottom)
            val view = SubtitleView(context)
            view.layout(0, 0, safe.width(), safe.height())
            view.getChildAt(0).layout(0, 0, safe.width(), safe.height())
            SubtitleAppearance(size = SubtitleSize.Large).applyTo(view, context, video.height().toFloat() / safe.height())
            val bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888)
            val cues = listOf(
                Cue.Builder().setText("A paused line").build(),
                Cue.Builder().setText("A positioned line").setLine(0.95f, Cue.LINE_TYPE_FRACTION).build(),
                Cue.Builder().setText("A numbered line").setLine(-1f, Cue.LINE_TYPE_NUMBER).build(),
                Cue.Builder().setBitmap(bitmap).setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(0.95f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(0.3f).setBitmapHeight(0.1f).build()
            )
            view.setCues(subtitleCuesInFrame(cues, video.width(), video.height(), safe.height()))
            view.draw(Canvas(Bitmap.createBitmap(safe.width(), safe.height(), Bitmap.Config.ARGB_8888)))
            val painters = field(view.getChildAt(0), "painters") as List<*>
            for (painter in painters.take(3)) {
                val top = field(painter!!, "textTop") as Int
                val layout = field(painter, "textLayout") as android.text.StaticLayout
                kotlin.test.assertTrue(top >= 0 && top + layout.height <= safe.height())
                assertEquals(
                    SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.25f * video.height(),
                    field(painter, "defaultTextSizePx") as Float,
                    0.001f
                )
            }
            val rect = field(painters.last()!!, "bitmapRect") as Rect
            kotlin.test.assertTrue(rect.bottom <= safe.height())
            assertEquals(80, rect.height())
        }
        assertEquals(video, subtitleSafeFrame(video, null, 16))
        val cue = Cue.Builder().setText("Original").build()
        assertSame(cue, subtitleCuesInFrame(listOf(cue), video.width(), video.height(), video.height()).single())
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(instance)
    }
}
