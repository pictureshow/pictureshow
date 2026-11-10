/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.createBitmap
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import app.pictureshow.core.playback.CapabilityOverride
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlaybackSettingsPresentation
import app.pictureshow.core.playback.PostCredits
import app.pictureshow.core.playback.SubtitleBurnIn
import app.pictureshow.core.playback.SubtitleStyle

internal val playbackGroups = listOf(
    "Network quality",
    "Subtitle appearance",
    "Subtitle conversion",
    "Playback",
    "Audio output",
    "Video output"
)

/** The same settings and consequences, presented with each surface's native controls. */
@Composable
internal fun AdvancedPlaybackSettings(
    group: String,
    local: PlaybackPreferences,
    touch: Boolean,
    onLocal: ((PlaybackPreferences) -> PlaybackPreferences) -> Unit,
    onClose: () -> Unit
) {
    val first = remember { FocusRequester() }
    val content: @Composable () -> Unit = {
        PlaybackSettingRows(group, local, touch, first, onLocal)
    }
    if (touch) {
        val scroll = rememberScrollState()
        val scrolled by remember { derivedStateOf { scroll.value > 0 } }
        val compact = isShortTouchWindow()
        Dialog(onDismissRequest = onClose) {
            androidx.compose.material3.Surface(
                modifier = Modifier.testTag("settings:touch-panel"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 24.dp)
                ) {
                    androidx.compose.material3.Text(
                        group,
                        modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
                    )
                    if (group == "Subtitle appearance") SubtitlePreview(local.appearance, touch)
                    if (scrolled) androidx.compose.material3.HorizontalDivider()
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(scroll).testTag("settings:touch-list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { content() }
                    if (scroll.canScrollForward) androidx.compose.material3.HorizontalDivider()
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                            androidx.compose.material3.Text("Done")
                        }
                    }
                }
            }
        }
    } else {
        TvSettingsPanel(
            title = group,
            onClose = onClose,
            firstFocus = first,
            preview = if (group == "Subtitle appearance") ({ SubtitlePreview(local.appearance, touch) }) else null
        ) { content() }
    }
}

@Composable
private fun PlaybackSettingRows(
    group: String,
    local: PlaybackPreferences,
    touch: Boolean,
    first: FocusRequester,
    onLocal: ((PlaybackPreferences) -> PlaybackPreferences) -> Unit
) {
    val tuning = local.tuning
    val style = local.subtitleStyle
    val firstRow = if (touch) Modifier else Modifier.focusRequester(first)
    when (group) {
        "Network quality" -> {
            ChoiceRow(
                "Home limit",
                local.bitrateMbps,
                PlaybackPreferences.BITRATES,
                touch,
                ::bitrateLabel,
                firstRow,
                "On the same network as your server. A lower limit may convert the video."
            ) { value ->
                onLocal { it.copy(bitrateMbps = value) }
            }
            ChoiceRow(
                "Remote limit",
                tuning.remoteBitrateMbps,
                PlaybackPreferences.BITRATES,
                touch,
                ::bitrateLabel,
                supporting = "Away from home. A lower limit may convert the video."
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(remoteBitrateMbps = value)) }
            }
            ChoiceRow(
                "Metered limit",
                tuning.meteredBitrateMbps,
                PlaybackPreferences.BITRATES,
                touch,
                ::bitrateLabel,
                supporting = "Mobile data or a metered hotspot. This takes priority over Home and Remote."
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(meteredBitrateMbps = value)) }
            }
            ToggleRow(
                "Auto-adjust quality",
                tuning.autoAdjustQuality,
                touch,
                supporting = "Reduces quality to avoid buffering when your connection slows. The player Quality button " +
                    "overrides it for this viewing."
            ) {
                onLocal { it.copy(tuning = it.tuning.copy(autoAdjustQuality = !it.tuning.autoAdjustQuality)) }
            }
        }
        "Subtitle appearance" -> {
            ChoiceRow("Font size", local.subtitleSize, SubtitleSize.entries, touch, { subtitleSizeLabel(it) }, firstRow) { value ->
                onLocal { it.copy(subtitleSize = value) }
            }
            ChoiceRow("Text color", style.foreground, SubtitleStyle.COLORS, touch, ::subtitleValueLabel) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(foreground = value)) }
            }
            ChoiceRow("Text opacity", style.textOpacity, listOf(100, 80, 60, 40, 20, 0), touch, { "$it%" }) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(textOpacity = value)) }
            }
            ChoiceRow("Edge", style.edge, SubtitleStyle.EDGES, touch, ::subtitleValueLabel) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(edge = value)) }
            }
            ChoiceRow("Edge color", style.edgeColor, SubtitleStyle.COLORS.drop(1), touch, ::subtitleValueLabel) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(edgeColor = value)) }
            }
            ChoiceRow("Background", local.subtitleBackground, SubtitleBackground.entries, touch, { subtitleBackgroundLabel(it) }) { value ->
                onLocal { it.copy(subtitleBackground = value) }
            }
            ChoiceRow("Background color", style.backgroundColor, SubtitleStyle.COLORS.drop(1), touch, ::subtitleValueLabel) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(backgroundColor = value)) }
            }
            ChoiceRow("Background opacity", style.backgroundOpacity, listOf(80, 100, 60, 40, 20, 0), touch, { "$it%" }) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(backgroundOpacity = value)) }
            }
            ChoiceRow(
                "Position",
                style.position,
                SubtitleStyle.POSITIONS,
                touch,
                ::subtitleValueLabel,
                supporting = "Image subtitles keep their position from the file."
            ) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(position = value)) }
            }
            ToggleRow("Bold", style.bold, touch) {
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(bold = !it.subtitleStyle.bold)) }
            }
            ToggleRow("Italic", style.italic, touch) {
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(italic = !it.subtitleStyle.italic)) }
            }
            ChoiceRow("Image subtitle opacity", style.imageOpacity, listOf(100, 80, 60, 40, 20, 0), touch, { "$it%" }) { value ->
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(imageOpacity = value)) }
            }
            ToggleRow(
                "Use my style for styled subtitles",
                style.overrideEmbedded,
                touch,
                supporting = "Replaces fonts and colors in styled (ASS/SSA) subtitles. Subtitles burned into the video " +
                    "cannot use these changes."
            ) {
                onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(overrideEmbedded = !it.subtitleStyle.overrideEmbedded)) }
            }
        }
        "Subtitle conversion" -> {
            ChoiceRow(
                "Burn in subtitles",
                tuning.subtitleBurnIn,
                SubtitleBurnIn.entries,
                touch,
                ::burnLabel,
                firstRow,
                burnConsequence(tuning.subtitleBurnIn)
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(subtitleBurnIn = value)) }
            }
        }
        "Playback" -> {
            ChoiceRow(
                "After credits",
                tuning.postCredits,
                PostCredits.entries,
                touch,
                ::postCreditsLabel,
                firstRow,
                if (tuning.postCredits == PostCredits.KeepScenes) {
                    "Plays any scenes after the credits before the next episode starts."
                } else {
                    "Starts the next episode during the credits. You can cancel the countdown to keep watching."
                }
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(postCredits = value)) }
            }
            ChoiceRow(
                "Rewind on resume",
                tuning.rewindSeconds,
                listOf(0, 5, 10, 15, 30),
                touch,
                { if (it == 0) "Off" else "$it seconds" },
                supporting = "Replays a few seconds when you return to a saved position."
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(rewindSeconds = value)) }
            }
        }
        "Audio output" -> {
            ChoiceRow(
                "Audio channel limit",
                tuning.maxAudioChannels,
                listOf(null, 2, 6, 8),
                touch,
                ::audioChannelLabel,
                firstRow,
                "Uses the channels your audio system supports. A lower limit may convert the audio."
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(maxAudioChannels = value)) }
            }
            ToggleRow(
                "Downmix to stereo",
                tuning.downmix,
                touch,
                supporting = "Plays all sound through two channels and turns off passthrough. May convert the audio."
            ) {
                onLocal { it.copy(tuning = it.tuning.copy(downmix = !it.tuning.downmix)) }
            }
            SettingSupportingText("Send to receiver (passthrough)", touch)
            PassthroughEncoding.entries.forEach { format ->
                ToggleRow(
                    passthroughLabel(format),
                    format !in tuning.disabledPassthrough,
                    touch,
                    supporting = "Only used when passthrough is on and your connected audio system supports this format."
                ) {
                    onLocal {
                        val disabled = it.tuning.disabledPassthrough
                        it.copy(
                            tuning = it.tuning.copy(
                                disabledPassthrough =
                                if (format in disabled) disabled - format else disabled + format
                            )
                        )
                    }
                }
            }
        }
        "Video output" -> {
            ToggleRow(
                "Match frame rate",
                tuning.matchFrameRate,
                touch,
                firstRow,
                "Matches the video when your display allows it. The screen may briefly go black while switching."
            ) {
                onLocal { it.copy(tuning = it.tuning.copy(matchFrameRate = !it.tuning.matchFrameRate)) }
            }
            ChoiceRow(
                "HDR",
                tuning.hdr,
                CapabilityOverride.entries,
                touch,
                { capabilityLabel(it, hdr = true) },
                supporting = capabilityConsequence(tuning.hdr, hdr = true)
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(hdr = value)) }
            }
            ChoiceRow(
                "Dolby Vision",
                tuning.dolbyVision,
                CapabilityOverride.entries,
                touch,
                { capabilityLabel(it, hdr = false) },
                supporting = capabilityConsequence(tuning.dolbyVision, hdr = false)
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(dolbyVision = value)) }
            }
            ToggleRow(
                "Prefer Dolby Vision over HDR10+",
                tuning.preferDolbyVision,
                touch,
                supporting = "Chooses Dolby Vision for compatible videos when both formats are available. Your device must support it."
            ) {
                onLocal { it.copy(tuning = it.tuning.copy(preferDolbyVision = !it.tuning.preferDolbyVision)) }
            }
        }
    }
}

internal fun bitrateLabel(value: Int?) = value?.let { "$it Mbps" } ?: "Auto (up to 200 Mbps)"
internal fun subtitleSizeLabel(value: SubtitleSize) = PlaybackSettingsPresentation.subtitleSizeLabel(value)
internal fun subtitleBackgroundLabel(value: SubtitleBackground) = PlaybackSettingsPresentation.subtitleBackgroundLabel(value)
internal fun subtitleValueLabel(value: String) = PlaybackSettingsPresentation.subtitleValueLabel(value)
internal fun postCreditsLabel(value: PostCredits) = PlaybackSettingsPresentation.postCreditsLabel(value)
internal fun audioChannelLabel(value: Int?) = PlaybackSettingsPresentation.audioChannelLabel(value)
internal fun passthroughLabel(value: PassthroughEncoding) = PlaybackSettingsPresentation.passthroughLabel(value)
internal fun capabilityLabel(value: CapabilityOverride, hdr: Boolean) = PlaybackSettingsPresentation.capabilityLabel(value, hdr)
internal fun capabilityConsequence(value: CapabilityOverride, hdr: Boolean) = PlaybackSettingsPresentation.capabilityConsequence(value, hdr)
internal fun burnLabel(value: SubtitleBurnIn) = when (value) {
    SubtitleBurnIn.Always -> "Always"
    SubtitleBurnIn.ImageOnly -> "Only image formats"
    else -> "Automatic"
}
internal fun burnConsequence(value: SubtitleBurnIn) = when (value) {
    SubtitleBurnIn.Always ->
        "Every selected subtitle is burned into video by the server. This requires video conversion; " +
            "local appearance and timing controls cannot change it."
    SubtitleBurnIn.ImageOnly ->
        "Image subtitles are burned into the video, requiring video conversion. Supported text " +
            "subtitles can still use your style. Unsupported text may also need conversion."
    else -> "Plays supported subtitles on this device. Your server converts the video when a selected subtitle cannot play here."
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    value: T,
    values: List<T>,
    touch: Boolean,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    change: (T) -> Unit
) {
    if (touch) {
        TouchChoice(title, value, values, label, supporting = supporting, onSelect = change)
    } else {
        TvSettingsChoiceRow(title, value, values, label, modifier, supportingText = supporting, onSelect = change)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    value: Boolean,
    touch: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    change: () -> Unit
) {
    if (touch) {
        TouchSwitchRow(title, supporting, value, "settings:$title") { change() }
    } else {
        TvSettingsToggleRow(title, value, modifier, supportingText = supporting, onToggle = { change() })
    }
}

@Composable
private fun SettingSupportingText(text: String, touch: Boolean) {
    if (touch) {
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        androidx.tv.material3.Text(
            text,
            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SubtitlePreview(appearance: SubtitleAppearance, touch: Boolean = false) {
    val context = LocalContext.current
    val image = remember { imageSubtitleSample() }
    val previewHeight = if (touch && isShortTouchWindow()) 64.dp else 108.dp
    val cues = remember(appearance) {
        appearance.cues(
            listOf(
                Cue.Builder().setText("Text subtitle").setPosition(0.25f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setSize(0.45f).build(),
                Cue.Builder().setBitmap(image).setPosition(0.75f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(0.25f, Cue.LINE_TYPE_FRACTION).setSize(0.45f).build()
            )
        )
    }
    Column {
        AndroidView(
            factory = {
                SubtitleView(it).apply {
                    isFocusable = false
                    contentDescription =
                        "Live subtitle preview: Text subtitle; image subtitle sample"
                }
            },
            modifier = Modifier.fillMaxWidth().height(previewHeight).testTag("settings:subtitle-preview")
                .background(Brush.horizontalGradient(listOf(Color(0xFF152438), Color(0xFFE6D8AD), Color(0xFF152438), Color(0xFFE6D8AD)))),
            update = {
                // Normalize the sample height through the real caption resolver, preserving
                // the viewer's system caption scale when Font size is Default.
                appearance.applyTo(
                    it,
                    context,
                    heightScale = (14f / previewHeight.value) / SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
                )
                it.setCues(cues)
            }
        )
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (touch) {
                androidx.compose.material3.Text(
                    "Text",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Text(
                    "Image",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.tv.material3.Text(
                    "Text",
                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.tv.material3.Text(
                    "Image",
                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A readable PGS-like image sample, never decoder data. Transparent pixels preserve the scene behind it. */
internal fun imageSubtitleSample(): Bitmap = createBitmap(480, 80, Bitmap.Config.ARGB_8888).also { image ->
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 48f
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    val canvas = Canvas(image)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 4f
    paint.color = AndroidColor.BLACK
    canvas.drawText("Image subtitle", 240f, 56f, paint)
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.WHITE
    canvas.drawText("Image subtitle", 240f, 56f, paint)
}
