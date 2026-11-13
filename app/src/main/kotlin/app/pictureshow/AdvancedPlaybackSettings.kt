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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    onClose: () -> Unit,
    supportedPassthrough: Set<PassthroughEncoding> = emptySet()
) {
    val first = remember { FocusRequester() }
    val content: @Composable () -> Unit = {
        PlaybackSettingRows(group, local, touch, first, supportedPassthrough, onLocal)
    }
    if (touch) {
        var picker by remember { mutableStateOf<TouchChoicePanel?>(null) }
        val groupScroll = rememberScrollState()
        val closePanel = { if (picker != null) picker = null else onClose() }
        val rows: @Composable () -> Unit = {
            CompositionLocalProvider(LocalTouchChoicePanel provides { picker = it }) { content() }
        }
        Dialog(onDismissRequest = closePanel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // The covered Activity's window hint can lag live rotation. Measure the dialog's
            // own allocation so a portrait window cannot retain the two-pane landscape layout.
            BoxWithConstraints {
                val compact = maxHeight < 480.dp
                val besidePreview = compact && maxWidth >= 600.dp && group == "Subtitle appearance" && picker == null
                // Keep transparent window insets separate from the painted, width-capped dialog.
                Box(Modifier.padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 24.dp)) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("settings:touch-panel")
                            .semantics { paneTitle = picker?.title ?: group },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        val panelModifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
                            .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 24.dp)
                        val currentPicker = picker
                        if (currentPicker != null) {
                            Column(panelModifier) {
                                TouchSettingsHeading(currentPicker.title)
                                Box(Modifier.weight(1f, fill = false)) {
                                    TouchChoiceOptions(currentPicker) { index ->
                                        currentPicker.onSelect(index)
                                        picker = null
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        picker = null
                                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        androidx.compose.material3.Text("Cancel")
                                    }
                                }
                            }
                        } else if (besidePreview) {
                            Row(
                                panelModifier,
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(240.dp)) { SubtitlePreview(local.appearance, touch, compact) }
                                Column(Modifier.weight(1f)) {
                                    TouchSettingsHeading(group)
                                    TouchSettingsList(onClose, groupScroll, rows)
                                }
                            }
                        } else {
                            Column(panelModifier) {
                                TouchSettingsHeading(group)
                                if (group == "Subtitle appearance") SubtitlePreview(local.appearance, touch, compact)
                                TouchSettingsList(onClose, groupScroll, rows)
                            }
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
private fun TouchSettingsHeading(title: String) {
    androidx.compose.material3.Text(
        title,
        modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp),
        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
    )
}

/** Keep the native scroll semantics and dividers, with a visible cue even when a row boundary fits exactly. */
@Composable
private fun ColumnScope.TouchSettingsList(onClose: () -> Unit, scroll: ScrollState, content: @Composable () -> Unit) {
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
    val indicator = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    if (scrolled) androidx.compose.material3.HorizontalDivider()
    Box(
        Modifier.weight(1f, fill = false).clipToBounds().drawWithContent {
            drawContent()
            if (scroll.canScrollBackward) {
                val height = 16.dp.toPx().coerceAtMost(size.height)
                drawRect(Brush.verticalGradient(listOf(surface, Color.Transparent), endY = height), size = size.copy(height = height))
            }
            if (scroll.canScrollForward) {
                val height = 24.dp.toPx().coerceAtMost(size.height)
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, surface), startY = size.height - height, endY = size.height),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - height),
                    size = size.copy(height = height)
                )
            }
            if (scroll.maxValue > 0) {
                val total = size.height + scroll.maxValue
                val thumb = (size.height * size.height / total).coerceAtLeast(24.dp.toPx()).coerceAtMost(size.height)
                val top = (size.height - thumb) * scroll.value / scroll.maxValue
                drawRoundRect(
                    indicator,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - 2.dp.toPx(), top),
                    size = androidx.compose.ui.geometry.Size(2.dp.toPx(), thumb),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                )
            }
        }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).testTag("settings:touch-list"),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) { content() }
    }
    if (scroll.canScrollForward) androidx.compose.material3.HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        androidx.compose.material3.TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            androidx.compose.material3.Text("Done")
        }
    }
}

@Composable
private fun PlaybackSettingRows(
    group: String,
    local: PlaybackPreferences,
    touch: Boolean,
    first: FocusRequester,
    supportedPassthrough: Set<PassthroughEncoding>,
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
                "On the same network as your server. A lower limit may convert the video.",
                optionSupporting = ::bitrateOptionSupporting
            ) { value ->
                onLocal { it.copy(bitrateMbps = value) }
            }
            ChoiceRow(
                "Remote limit",
                tuning.remoteBitrateMbps,
                PlaybackPreferences.BITRATES,
                touch,
                ::bitrateLabel,
                supporting = "Away from home. A lower limit may convert the video.",
                optionSupporting = ::bitrateOptionSupporting
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(remoteBitrateMbps = value)) }
            }
            ChoiceRow(
                "Metered limit",
                tuning.meteredBitrateMbps,
                PlaybackPreferences.BITRATES,
                touch,
                ::bitrateLabel,
                supporting = "Mobile data or a metered hotspot. This takes priority over Home and Remote.",
                optionSupporting = ::bitrateOptionSupporting
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
            if (touch) {
                androidx.compose.material3.Text(
                    "Receiver passthrough",
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            } else {
                TvSettingsSectionHeader("Receiver passthrough")
            }
            if (supportedPassthrough.isEmpty()) {
                SettingSupportingText("Format choices appear when a receiver is connected.", touch)
            }
            ToggleRow(
                "Audio passthrough",
                local.passthrough,
                touch,
                supporting = PlaybackSettingsPresentation.passthroughSummary(
                    local.passthrough,
                    supportedPassthrough,
                    downmix = tuning.downmix
                ) + if (supportedPassthrough.isEmpty()) "" else " Turning a format off may convert the audio."
            ) { onLocal { it.copy(passthrough = !it.passthrough) } }
            PassthroughEncoding.entries.filter { it in supportedPassthrough }.forEach { format ->
                ToggleRow(
                    passthroughLabel(format),
                    format !in tuning.disabledPassthrough,
                    touch,
                    enabled = PlaybackSettingsPresentation.canConfigurePassthrough(
                        local.passthrough,
                        tuning.downmix,
                        format in supportedPassthrough
                    )
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
                ::capabilityLabel,
                supporting = capabilityConsequence(tuning.hdr, hdr = true),
                optionSupporting = { capabilityConsequence(it, hdr = true) }
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(hdr = value)) }
            }
            ChoiceRow(
                "Dolby Vision",
                tuning.dolbyVision,
                CapabilityOverride.entries,
                touch,
                ::capabilityLabel,
                supporting = capabilityConsequence(tuning.dolbyVision, hdr = false),
                optionSupporting = { capabilityConsequence(it, hdr = false) }
            ) { value ->
                onLocal { it.copy(tuning = it.tuning.copy(dolbyVision = value)) }
            }
            ToggleRow(
                "Prefer Dolby Vision",
                tuning.preferDolbyVision,
                touch,
                supporting = "Tries Dolby Vision when a video also has HDR10+. Your device must support it."
            ) {
                onLocal { it.copy(tuning = it.tuning.copy(preferDolbyVision = !it.tuning.preferDolbyVision)) }
            }
        }
    }
}

internal fun bitrateLabel(value: Int?) = value?.let { "$it Mbps" } ?: "Auto"
internal fun bitrateOptionSupporting(value: Int?): String? = if (value ==
    null
) {
    "Up to 200 Mbps"
} else {
    null
}
internal fun subtitleSizeLabel(value: SubtitleSize) = PlaybackSettingsPresentation.subtitleSizeLabel(value)
internal fun subtitleBackgroundLabel(value: SubtitleBackground) = PlaybackSettingsPresentation.subtitleBackgroundLabel(value)
internal fun subtitleValueLabel(value: String) = PlaybackSettingsPresentation.subtitleValueLabel(value)
internal fun postCreditsLabel(value: PostCredits) = PlaybackSettingsPresentation.postCreditsLabel(value)
internal fun audioChannelLabel(value: Int?) = PlaybackSettingsPresentation.audioChannelLabel(value)
internal fun passthroughLabel(value: PassthroughEncoding) = PlaybackSettingsPresentation.passthroughLabel(value)
internal fun capabilityLabel(value: CapabilityOverride) = PlaybackSettingsPresentation.capabilityLabel(value)
internal fun capabilityConsequence(value: CapabilityOverride, hdr: Boolean) = PlaybackSettingsPresentation.capabilityConsequence(value, hdr)
internal fun burnLabel(value: SubtitleBurnIn) = when (value) {
    SubtitleBurnIn.Always -> "Always"
    SubtitleBurnIn.ImageOnly -> "Only image formats"
    else -> "Automatic"
}
internal fun burnConsequence(value: SubtitleBurnIn) = PlaybackSettingsPresentation.burnConsequence(value)

@Composable
private fun <T> ChoiceRow(
    title: String,
    value: T,
    values: List<T>,
    touch: Boolean,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    optionSupporting: ((T) -> String?)? = null,
    change: (T) -> Unit
) {
    if (touch) {
        TouchChoice(title, value, values, label, supporting = supporting, optionSupporting = optionSupporting, onSelect = change)
    } else {
        TvSettingsChoiceRow(
            title,
            value,
            values,
            label,
            modifier,
            supportingText = supporting,
            optionSupportingText = optionSupporting,
            onSelect = change
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    value: Boolean,
    touch: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    change: () -> Unit
) {
    if (touch) {
        TouchSwitchRow(title, supporting, value, "settings:$title", enabled = enabled) { change() }
    } else {
        TvSettingsToggleRow(title, value, modifier.testTag("settings:$title"), supportingText = supporting, enabled = enabled, onToggle = {
            change()
        })
    }
}

@Composable
private fun SettingSupportingText(text: String, touch: Boolean) {
    if (touch) {
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        androidx.tv.material3.Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SubtitlePreview(appearance: SubtitleAppearance, touch: Boolean = false, compact: Boolean = touch && isShortTouchWindow()) {
    val context = LocalContext.current
    val image = remember { imageSubtitleSample() }
    val previewHeight = if (touch && compact) 64.dp else 108.dp
    val cues = remember(appearance) {
        appearance.cues(
            listOf(
                Cue.Builder().setText("Text subtitle").setPosition(0.25f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).setSize(0.45f).build(),
                Cue.Builder().setBitmap(image).setPosition(0.75f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).setSize(0.45f).build()
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
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            listOf("Text", "Image").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val modifier = Modifier.testTag("settings:subtitle-label:$label")
                    if (touch) {
                        androidx.compose.material3.Text(
                            label,
                            modifier = modifier,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        androidx.tv.material3.Text(
                            label,
                            modifier = modifier,
                            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
    paint.strokeWidth = 8f
    paint.color = AndroidColor.BLACK
    canvas.drawText("Image subtitle", 240f, 56f, paint)
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.WHITE
    canvas.drawText("Image subtitle", 240f, 56f, paint)
}
