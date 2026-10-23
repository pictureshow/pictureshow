/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.graphics.Color
import android.view.accessibility.CaptioningManager
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

enum class SubtitleSize { System, Small, Normal, Large, ExtraLarge }
enum class SubtitleBackground { System, None, Shadow, Box }

data class SubtitleAppearance(
    val size: SubtitleSize = SubtitleSize.System,
    val background: SubtitleBackground = SubtitleBackground.System
) {
    fun applyTo(view: SubtitleView, context: Context, heightScale: Float = 1f) {
        val manager = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        val enabled = manager?.isEnabled == true
        val systemStyle = if (enabled) CaptionStyleCompat.createFromCaptionStyle(manager!!.userStyle) else CaptionStyleCompat.DEFAULT
        val presentation = resolve(if (enabled) manager!!.fontScale else 1f, systemStyle)
        view.setViewType(SubtitleView.VIEW_TYPE_CANVAS)
        view.setStyle(presentation.style)
        view.setFractionalTextSize(presentation.textFraction * heightScale)
        // Explicit overrides should also override sizes/backgrounds embedded by the subtitle file.
        // SubtitleView preserves bitmap coordinates and dimensions when stripping text styling.
        view.setApplyEmbeddedStyles(background == SubtitleBackground.System)
        view.setApplyEmbeddedFontSizes(size == SubtitleSize.System)
    }

    internal fun resolve(systemScale: Float, systemStyle: CaptionStyleCompat): ResolvedSubtitleAppearance {
        val scale = when (size) {
            SubtitleSize.System -> systemScale.takeIf { it.isFinite() && it > 0 } ?: 1f
            SubtitleSize.Small -> 0.75f
            SubtitleSize.Normal -> 1f
            SubtitleSize.Large -> 1.25f
            SubtitleSize.ExtraLarge -> 1.5f
        }
        val style = when (background) {
            SubtitleBackground.System -> systemStyle
            SubtitleBackground.None -> overrideStyle(systemStyle, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE)
            SubtitleBackground.Shadow -> overrideStyle(systemStyle, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW)
            SubtitleBackground.Box -> overrideStyle(systemStyle, Color.argb(204, 0, 0, 0), CaptionStyleCompat.EDGE_TYPE_NONE)
        }
        return ResolvedSubtitleAppearance(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale, style)
    }

    private fun overrideStyle(base: CaptionStyleCompat, backgroundColor: Int, edge: Int) = CaptionStyleCompat(
        base.foregroundColor,
        backgroundColor,
        Color.TRANSPARENT,
        edge,
        Color.BLACK,
        base.typeface
    )
}

internal data class ResolvedSubtitleAppearance(val textFraction: Float, val style: CaptionStyleCompat)

/** Bounds of fit-mode video; bitmap subtitle fractions must be relative to these bounds. */
internal fun subtitleVideoFrame(width: Int, height: Int, videoSize: androidx.media3.common.VideoSize): android.graphics.Rect {
    if (width <= 0 || height <= 0 || videoSize.width <= 0 || videoSize.height <= 0) {
        return android.graphics.Rect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
    }
    val pixels = videoSize.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0 } ?: 1f
    val ratio = videoSize.width.toDouble() * pixels / videoSize.height
    val frameWidth: Int
    val frameHeight: Int
    if (width.toDouble() / height > ratio) {
        frameHeight = height
        frameWidth = (height * ratio).toInt().coerceAtMost(width)
    } else {
        frameWidth = width
        frameHeight = (width / ratio).toInt().coerceAtMost(height)
    }
    val left = (width - frameWidth) / 2
    val top = (height - frameHeight) / 2
    return android.graphics.Rect(left, top, left + frameWidth, top + frameHeight)
}

/** Keep the fitted video's origin and width, reserving only the area actually covered by our UI. */
internal fun subtitleSafeFrame(video: android.graphics.Rect, overlayTop: Int?, gap: Int): android.graphics.Rect =
    android.graphics.Rect(video).apply {
        if (overlayTop != null) bottom = minOf(bottom, (overlayTop - gap).coerceAtLeast(top + 1))
    }

/**
 * Text keeps its original size as the cue viewport shortens. Explicit bitmap heights need the same
 * compensation; implicit heights already use the unchanged width. Clamp bitmap anchors as Media3's
 * text painter does for text, so positioned PGS/DVD cues cannot extend beneath the safe viewport.
 */
internal fun subtitleCuesInFrame(cues: List<Cue>, videoWidth: Int, videoHeight: Int, safeHeight: Int): List<Cue> {
    if (safeHeight <= 0 || videoHeight <= 0 || safeHeight == videoHeight) return cues
    val scale = videoHeight.toFloat() / safeHeight
    return cues.map { cue ->
        val builder = cue.buildUpon()
        if (cue.textSize != Cue.DIMEN_UNSET &&
            cue.textSizeType != Cue.TEXT_SIZE_TYPE_ABSOLUTE
        ) {
            builder.setTextSize(cue.textSize * scale, cue.textSizeType)
        }
        cue.bitmap?.let { bitmap ->
            val height = if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                cue.bitmapHeight * scale
            } else {
                videoWidth * cue.size * bitmap.height / bitmap.width / safeHeight
            }.coerceIn(0f, 1f)
            val anchor = when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_END -> 1f
                Cue.ANCHOR_TYPE_MIDDLE -> 0.5f
                else -> 0f
            }
            builder.setBitmapHeight(height)
                .setLine(cue.line.coerceIn(height * anchor, 1f - height * (1f - anchor)), Cue.LINE_TYPE_FRACTION)
        }
        builder.build()
    }
}
