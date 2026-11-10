/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.accessibility.CaptioningManager
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.pictureshow.core.playback.SubtitleStyle

typealias SubtitleSize = app.pictureshow.core.playback.SubtitleSize
typealias SubtitleBackground = app.pictureshow.core.playback.SubtitleBackground

data class SubtitleAppearance(
    val size: SubtitleSize = SubtitleSize.System,
    val background: SubtitleBackground = SubtitleBackground.System,
    val styling: SubtitleStyle = SubtitleStyle()
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
        view.setApplyEmbeddedStyles(background == SubtitleBackground.System && !styling.overrideEmbedded)
        view.setApplyEmbeddedFontSizes(size == SubtitleSize.System && !styling.overrideEmbedded)
    }

    internal fun resolve(systemScale: Float, systemStyle: CaptionStyleCompat): ResolvedSubtitleAppearance {
        val scale = when (size) {
            SubtitleSize.System -> systemScale.takeIf { it.isFinite() && it > 0 } ?: 1f
            SubtitleSize.Small -> 0.75f
            SubtitleSize.Normal -> 1f
            SubtitleSize.Large -> 1.25f
            SubtitleSize.ExtraLarge -> 1.5f
        }
        val base = when (background) {
            SubtitleBackground.System -> systemStyle
            SubtitleBackground.None -> overrideStyle(systemStyle, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE)
            SubtitleBackground.Shadow -> overrideStyle(systemStyle, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW)
            SubtitleBackground.Box -> overrideStyle(systemStyle, Color.argb(204, 0, 0, 0), CaptionStyleCompat.EDGE_TYPE_NONE)
        }
        val custom = styling.normalized()
        val style = if (custom == SubtitleStyle()) {
            base
        } else {
            CaptionStyleCompat(
                withOpacity(color(custom.foreground, base.foregroundColor), custom.textOpacity),
                if (background == SubtitleBackground.None || background == SubtitleBackground.Shadow) {
                    Color.TRANSPARENT
                } else if (background == SubtitleBackground.System &&
                    custom.backgroundColor == "Black" &&
                    custom.backgroundOpacity == 80
                ) {
                    base.backgroundColor
                } else {
                    withOpacity(color(custom.backgroundColor, Color.BLACK), custom.backgroundOpacity)
                },
                base.windowColor,
                when (custom.edge) {
                    "None" -> CaptionStyleCompat.EDGE_TYPE_NONE
                    "Outline" -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    "Shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    else -> base.edgeType
                },
                color(custom.edgeColor, base.edgeColor),
                if (custom.bold || custom.italic) {
                    Typeface.create(
                        base.typeface,
                        (if (custom.bold) Typeface.BOLD else 0) or
                            (if (custom.italic) Typeface.ITALIC else 0)
                    )
                } else {
                    base.typeface
                }
            )
        }
        return ResolvedSubtitleAppearance(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale, style)
    }

    /** Called once per cue group/style, before safe-frame layout; image alpha never fades text. */
    internal fun cues(cues: List<Cue>): List<Cue> {
        val style = styling.normalized()
        if (style == SubtitleStyle()) return cues
        return cues.map { cue ->
            val builder = cue.buildUpon()
            cue.bitmap?.let { original ->
                if (style.imageOpacity < 0) {
                    val copy = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
                    Canvas(copy).drawBitmap(original, 0f, 0f, Paint().apply { alpha = style.imageOpacity * 255 / 100 })
                    builder.setBitmap(copy)
                }
            }
            if (cue.text != null) {
                if (style.bold || style.italic) {
                    val text = SpannableString(if (style.overrideEmbedded) cue.text.toString() else cue.text)
                    text.setSpan(
                        StyleSpan(
                            (if (style.bold) Typeface.BOLD else 0) or
                                (if (style.italic) Typeface.ITALIC else 0)
                        ),
                        0,
                        text.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setText(text)
                }
                if (style.position != "Authored") {
                    builder.setLine(
                        when (style.position) {
                            "Top" -> 0.08f
                            "Middle" -> 0.5f
                            else -> 0.92f
                        },
                        Cue.LINE_TYPE_FRACTION
                    )
                        .setLineAnchor(
                            when (style.position) {
                                "Top" -> Cue.ANCHOR_TYPE_START
                                "Middle" -> Cue.ANCHOR_TYPE_MIDDLE
                                else -> Cue.ANCHOR_TYPE_END
                            }
                        )
                }
            }
            builder.build()
        }
    }

    private fun color(name: String, fallback: Int): Int = when (name) {
        "White" -> Color.WHITE
        "Yellow" -> Color.YELLOW
        "Black" -> Color.BLACK
        "Green" -> Color.GREEN
        "Cyan" -> Color.CYAN
        else -> fallback
    }

    private fun withOpacity(value: Int, opacity: Int) =
        Color.argb(opacity * 255 / 100, Color.red(value), Color.green(value), Color.blue(value))

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

/**
 * PLAY-27 yields to subtitles placed in the top half of the frame (signs, forced translations, WebVTT `line:0`,
 * SSA `\an8`). Cues without an explicit line sit at the bottom, and a negative line number counts from the bottom.
 */
internal fun cuesNearTop(cues: List<Cue>): Boolean = cues.any { cue ->
    cue.line != Cue.DIMEN_UNSET &&
        when (cue.lineType) {
            Cue.LINE_TYPE_FRACTION -> cue.line < 0.5f
            Cue.LINE_TYPE_NUMBER -> cue.line >= 0f
            else -> false
        }
}
