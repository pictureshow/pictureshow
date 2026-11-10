/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import app.pictureshow.core.playback.CapabilityOverride
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PostCredits
import app.pictureshow.core.playback.SubtitleBurnIn
import app.pictureshow.core.playback.SubtitleStyle

internal val playbackGroups = listOf("Network quality", "Subtitle appearance", "Subtitle conversion", "Continuation and output")

/** Shared controls and values on TV and touch; native buttons retain each surface's input semantics. */
@Composable
internal fun AdvancedPlaybackSettings(
    group: String,
    local: PlaybackPreferences,
    touch: Boolean,
    onLocal: ((PlaybackPreferences) -> PlaybackPreferences) -> Unit,
    onClose: () -> Unit
) {
    val first = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val tuning = local.tuning
    val style = local.subtitleStyle
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = if (touch) 640.dp else 420.dp)
                .background(Color(0xFF10131C)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingText(group, touch, heading = true)
            // The preview stays visible while its controls scroll; it is the actual Media3 renderer.
            if (group == "Subtitle appearance") SubtitlePreview(local.appearance)
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (group) {
                    "Network quality" -> {
                        SettingText(
                            "Home means a local server address. Metered takes priority; other servers use Remote. " +
                                "Auto-adjust reduces quality when measured bandwidth drops. The player Quality button overrides " +
                                "it for this viewing.",
                            touch
                        )
                        ChoiceRow("Home limit", local.bitrateMbps, PlaybackPreferences.BITRATES, touch, ::bitrateLabel) { value ->
                            onLocal { it.copy(bitrateMbps = value) }
                        }
                        ChoiceRow("Remote limit", tuning.remoteBitrateMbps, PlaybackPreferences.BITRATES, touch, ::bitrateLabel) { value ->
                            onLocal { it.copy(tuning = it.tuning.copy(remoteBitrateMbps = value)) }
                        }
                        ChoiceRow(
                            "Metered limit",
                            tuning.meteredBitrateMbps,
                            PlaybackPreferences.BITRATES,
                            touch,
                            ::bitrateLabel
                        ) { value ->
                            onLocal { it.copy(tuning = it.tuning.copy(meteredBitrateMbps = value)) }
                        }
                        ToggleRow("Auto-adjust quality", tuning.autoAdjustQuality, touch) {
                            onLocal { it.copy(tuning = it.tuning.copy(autoAdjustQuality = !it.tuning.autoAdjustQuality)) }
                        }
                        SettingText("A lower cap may make the server convert the video. Auto is a 200 Mbps ceiling, not unlimited.", touch)
                    }
                    "Subtitle appearance" -> {
                        ChoiceRow("Font size", local.subtitleSize, SubtitleSize.entries, touch, {
                            it.name
                        }) { value -> onLocal { it.copy(subtitleSize = value) } }
                        ChoiceRow("Text color", style.foreground, SubtitleStyle.COLORS, touch, {
                            it
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(foreground = value)) } }
                        ChoiceRow("Text opacity", style.textOpacity, listOf(100, 80, 60, 40, 20, 0), touch, {
                            "$it%"
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(textOpacity = value)) } }
                        ChoiceRow("Edge", style.edge, SubtitleStyle.EDGES, touch, {
                            it
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(edge = value)) } }
                        ChoiceRow("Edge color", style.edgeColor, SubtitleStyle.COLORS.drop(1), touch, {
                            it
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(edgeColor = value)) } }
                        ChoiceRow("Background", local.subtitleBackground, SubtitleBackground.entries, touch, {
                            it.name
                        }) { value -> onLocal { it.copy(subtitleBackground = value) } }
                        ChoiceRow("Background color", style.backgroundColor, SubtitleStyle.COLORS.drop(1), touch, {
                            it
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(backgroundColor = value)) } }
                        ChoiceRow("Background opacity", style.backgroundOpacity, listOf(80, 100, 60, 40, 20, 0), touch, {
                            "$it%"
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(backgroundOpacity = value)) } }
                        ChoiceRow("Position", style.position, SubtitleStyle.POSITIONS, touch, {
                            it
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(position = value)) } }
                        ToggleRow("Bold", style.bold, touch) {
                            onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(bold = !it.subtitleStyle.bold)) }
                        }
                        ToggleRow("Italic", style.italic, touch) {
                            onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(italic = !it.subtitleStyle.italic)) }
                        }
                        ChoiceRow("Image subtitle opacity", style.imageOpacity, listOf(100, 80, 60, 40, 20, 0), touch, {
                            "$it%"
                        }) { value -> onLocal { it.copy(subtitleStyle = it.subtitleStyle.copy(imageOpacity = value)) } }
                        ToggleRow("Override authored ASS styling", style.overrideEmbedded, touch) {
                            onLocal {
                                it.copy(subtitleStyle = it.subtitleStyle.copy(overrideEmbedded = !it.subtitleStyle.overrideEmbedded))
                            }
                        }
                        SettingText(
                            "Override removes embedded text styling, including ASS fonts and colors. Image subtitles retain " +
                                "authored position. Burned-in subtitles are pixels in the video and cannot use these changes.",
                            touch
                        )
                    }
                    "Subtitle conversion" -> {
                        ChoiceRow("Burn subtitles", tuning.subtitleBurnIn, SubtitleBurnIn.entries, touch, {
                            burnLabel(it)
                        }) { value -> onLocal { it.copy(tuning = it.tuning.copy(subtitleBurnIn = value)) } }
                        SettingText(
                            when (tuning.subtitleBurnIn) {
                                SubtitleBurnIn.Always ->
                                    "Every selected subtitle is burned into video by the server. This requires video conversion; " +
                                        "local appearance and timing controls cannot change it."
                                SubtitleBurnIn.ImageOnly ->
                                    "Image formats are burned into the video, requiring conversion. Supported text subtitles stay " +
                                        "local and can be styled. Unsupported text may still require conversion."
                                else ->
                                    "The player renders supported subtitles locally. The server converts video when a selected " +
                                        "subtitle cannot be rendered here."
                            },
                            touch
                        )
                    }
                    else -> {
                        ChoiceRow("After credits", tuning.postCredits, PostCredits.entries, touch, {
                            if (it == PostCredits.KeepScenes) "Keep scenes after credits" else "Next episode during credits"
                        }) { value -> onLocal { it.copy(tuning = it.tuning.copy(postCredits = value)) } }
                        SettingText(
                            "Keep scenes waits for the physical end before advancing. Outro skipping stops at the segment " +
                                "end, preserving later scenes.",
                            touch
                        )
                        ChoiceRow("Rewind on resume", tuning.rewindSeconds, listOf(0, 5, 10, 15, 30), touch, {
                            "$it seconds"
                        }) { value -> onLocal { it.copy(tuning = it.tuning.copy(rewindSeconds = value)) } }
                        ChoiceRow("Audio channel limit", tuning.maxAudioChannels, listOf(null, 2, 6, 8), touch, {
                            it?.toString()
                                ?: "Detected"
                        }) { value -> onLocal { it.copy(tuning = it.tuning.copy(maxAudioChannels = value)) } }
                        ToggleRow("Downmix to stereo", tuning.downmix, touch) {
                            onLocal { it.copy(tuning = it.tuning.copy(downmix = !it.tuning.downmix)) }
                        }
                        PassthroughEncoding.entries.forEach { format ->
                            ToggleRow("Allow ${format.name} passthrough", format !in tuning.disabledPassthrough, touch) {
                                onLocal {
                                    it.copy(
                                        tuning = it.tuning.copy(
                                            disabledPassthrough = if (format in
                                                it.tuning.disabledPassthrough
                                            ) {
                                                it.tuning.disabledPassthrough - format
                                            } else {
                                                it.tuning.disabledPassthrough +
                                                    format
                                            }
                                        )
                                    )
                                }
                            }
                        }
                        SettingText(
                            "Allowing a format still requires the connected audio output to support it. Downmix and channel " +
                                "limits may require server audio conversion.",
                            touch
                        )
                        ToggleRow("Match frame rate", tuning.matchFrameRate, touch) {
                            onLocal { it.copy(tuning = it.tuning.copy(matchFrameRate = !it.tuning.matchFrameRate)) }
                        }
                        SettingText(
                            "Android chooses an available display mode. Some TVs briefly blank while switching; a request " +
                                "does not guarantee a switch.",
                            touch
                        )
                        // Android's public video path follows stream HDR metadata. No invented force-output switch.
                        SettingText(
                            "Dynamic range: follows the video and Android display capabilities. Manual output switching is " +
                                "unavailable on this renderer.",
                            touch
                        )
                        ChoiceRow("Advertise HDR support", tuning.hdr, CapabilityOverride.entries, touch, {
                            it.name
                        }) { value -> onLocal { it.copy(tuning = it.tuning.copy(hdr = value)) } }
                        ChoiceRow("Advertise Dolby Vision support", tuning.dolbyVision, CapabilityOverride.entries, touch, {
                            it.name
                        }) { value -> onLocal { it.copy(tuning = it.tuning.copy(dolbyVision = value)) } }
                        ToggleRow("Prefer Dolby Vision over HDR10+", tuning.preferDolbyVision, touch) {
                            onLocal { it.copy(tuning = it.tuning.copy(preferDolbyVision = !it.tuning.preferDolbyVision)) }
                        }
                        SettingText(
                            "Detected is recommended. Force changes the compatibility sent to Jellyfin; it cannot add a " +
                                "decoder or HDR display and may fail. Disable requests a compatible conversion. Dolby Vision " +
                                "preference removes HDR10+ advertising when Dolby Vision is available; actual output still " +
                                "depends on the stream and decoder.",
                            touch
                        )
                    }
                }
            }
            SettingButton("Done", touch, onClose, Modifier.focusRequester(first))
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

internal fun bitrateLabel(value: Int?) = value?.let { "$it Mbps" } ?: "Auto (200 Mbps)"
internal fun burnLabel(value: SubtitleBurnIn) = when (value) {
    SubtitleBurnIn.Always -> "Always"
    SubtitleBurnIn.ImageOnly -> "Only image formats"
    else -> "Automatic"
}

@Composable
private fun <T> ChoiceRow(title: String, value: T, values: List<T>, touch: Boolean, label: (T) -> String, change: (T) -> Unit) {
    if (touch) {
        TouchChoice(title, value, values, label, onSelect = change)
    } else {
        SettingButton("$title: ${label(value)}", false, { change(nextSetting(values, value)) })
    }
}

@Composable
private fun ToggleRow(title: String, value: Boolean, touch: Boolean, change: () -> Unit) {
    if (touch) {
        TouchSwitchRow(title, "", value, "settings:$title") { change() }
    } else {
        SettingButton("$title: ${if (value) "On" else "Off"}", false, change)
    }
}

@Composable
private fun SettingButton(text: String, touch: Boolean, action: () -> Unit, modifier: Modifier = Modifier) {
    val semantics = modifier.testTag("settings:$text").semantics { onClick(label = "Change value", action = null) }
    if (touch) {
        androidx.compose.material3.TextButton(onClick = action, modifier = semantics.heightIn(min = 48.dp)) {
            androidx.compose.material3.Text(text)
        }
    } else {
        androidx.tv.material3.Button(onClick = action, modifier = semantics) { androidx.tv.material3.Text(text) }
    }
}

@Composable
private fun SettingText(text: String, touch: Boolean, heading: Boolean = false) {
    val modifier = if (heading) Modifier.semantics { heading() } else Modifier
    if (touch) {
        androidx.compose.material3.Text(text, color = Color.White, modifier = modifier)
    } else {
        androidx.tv.material3.Text(
            text,
            modifier = modifier,
            style = if (heading) {
                androidx.tv.material3.MaterialTheme.typography.headlineSmall
            } else {
                androidx.tv.material3.MaterialTheme.typography.bodyMedium
            }
        )
    }
}

@Composable
internal fun SubtitlePreview(appearance: SubtitleAppearance) {
    val context = LocalContext.current
    val image = remember { Bitmap.createBitmap(120, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.WHITE) } }
    val cues = remember(appearance) {
        appearance.cues(
            listOf(
                Cue.Builder().setText("A moment worth watching").build(),
                Cue.Builder().setBitmap(image).setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(0.32f, Cue.LINE_TYPE_FRACTION).setSize(0.25f).build()
            )
        )
    }
    AndroidView(
        factory = {
            SubtitleView(it).apply {
                isFocusable = false
                contentDescription =
                    "Live subtitle preview: A moment worth watching; image subtitle sample"
            }
        },
        modifier = Modifier.fillMaxWidth().height(100.dp).background(Color(0xFF263248)),
        update = {
            appearance.applyTo(it, context)
            it.setFractionalTextSize(
                0.14f *
                    (
                        appearance.resolve(1f, androidx.media3.ui.CaptionStyleCompat.DEFAULT).textFraction /
                            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
                        )
            )
            it.setCues(cues)
        }
    )
}
