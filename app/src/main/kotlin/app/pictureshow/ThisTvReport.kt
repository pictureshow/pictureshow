/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DeviceProfileBuilder
import app.pictureshow.core.playback.PlaybackJson
import kotlinx.serialization.json.Json

internal data class ThisTvReport(val summary: String, val profileJson: String) {
    val sharedText get() = "$summary\n\nDevice profile JSON\n$profileJson"
}

/** Capability-only input: deliberately has no account, server, media, identity, or free-form device-name parameter. */
internal fun thisTvReport(
    capabilities: DeviceCapabilities,
    appVersion: String,
    androidVersion: String,
    route: String,
    ffmpeg: List<String>
): ThisTvReport {
    val profile = DeviceProfileBuilder.build(capabilities)
    val json = Json(PlaybackJson) { prettyPrint = true }.encodeToString(profile)
    val summary = buildString {
        appendLine("Pictureshow $appVersion · Android $androidVersion")
        appendLine()
        appendLine("Video decoders (reported limits)")
        capabilities.videoDecoders.forEach { decoder ->
            appendLine("${decoder.codec} · ${if (decoder.hardware) "hardware" else "software"}")
            appendLine(
                "  ${decoder.maxWidth} × ${decoder.maxHeight} at ${decoder.maxFrameRateAtMaxSize} fps; up to ${decoder.maxFrameRate} fps at 1080p or below"
            )
            appendLine(
                "  ${decoder.maxBitDepth}-bit; profiles: ${decoder.profiles.joinToString()}; level: ${decoder.maxLevel?.let {
                    "${it.major}.${it.minor}"
                } ?: "not reported"}"
            )
            appendLine(
                "  HDR: ${decoder.hdrFormats.joinToString().ifEmpty {
                    "none reported"
                }}; Dolby Vision profiles: ${decoder.dolbyVisionProfiles.joinToString().ifEmpty { "none reported" }}"
            )
            decoder.profileCapabilities.forEach { tuple ->
                appendLine(
                    "  ${tuple.profile}: ${tuple.maxBitDepth}-bit, level ${tuple.maxLevel?.let {
                        "${it.major}.${it.minor}"
                    } ?: "unknown"}, HDR ${tuple.hdrFormats.joinToString().ifEmpty { "none" }}"
                )
            }
            decoder.sustainablePerformance?.let { appendLine("  Real-time evidence: ${it.width} × ${it.height} at ${it.frameRate} fps") }
            appendLine()
        }
        if (capabilities.videoDecoders.isEmpty()) appendLine("No video decoder capabilities reported")
        val display = capabilities.display
        appendLine("Display maximum reported mode: ${display.maxWidth} × ${display.maxHeight} at ${display.maxRefreshRate} Hz")
        appendLine("Display HDR: ${display.hdrFormats.joinToString().ifEmpty { "none reported" }}")
        appendLine()
        appendLine("Audio output: $route")
        appendLine("PCM channels: ${capabilities.audioOutput.pcmChannels}")
        val encodings = passthroughEncodingLabels(capabilities.audioOutput.passthrough, emptyLabel = "none reported")
        appendLine("Route passthrough encodings: $encodings")
        appendLine("Passthrough preference: ${if (capabilities.settings.passthroughEnabled) "On" else "Off"}")
        appendLine()
        appendLine("FFmpeg decoders present (profile-relevant audio): ${ffmpeg.joinToString().ifEmpty { "none available" }}")
        appendLine()
        append(
            "Streaming bitrate ceiling in effect: ${capabilities.settings.maxStreamingBitrate / 1_000_000} Mbps (server limits may lower this)"
        )
    }
    return ThisTvReport(summary, json)
}
