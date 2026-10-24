/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DeviceProfileBuilder
import app.pictureshow.core.playback.PlaybackJson
import java.math.BigDecimal
import java.math.RoundingMode
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
            val atMaxSize = displayRate(decoder.maxFrameRateAtMaxSize)
            appendLine(
                "  ${decoder.maxWidth} × ${decoder.maxHeight} at $atMaxSize fps; up to ${displayRate(
                    decoder.maxFrameRate
                )} fps at 1080p or below"
            )
            appendLine(
                buildList {
                    add("${decoder.maxBitDepth}-bit")
                    decoder.profiles.takeIf { it.isNotEmpty() }?.let { add("profiles: ${it.joinToString()}") }
                    decoder.maxLevel?.let { add("level: ${it.major}.${it.minor}") }
                }.joinToString("; ", prefix = "  ")
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
            decoder.sustainablePerformance?.let {
                appendLine("  Real-time evidence: ${it.width} × ${it.height} at ${displayRate(it.frameRate)} fps")
            }
            appendLine()
        }
        if (capabilities.videoDecoders.isEmpty()) appendLine("No video decoder capabilities reported")
        val display = capabilities.display
        val refresh = displayRate(display.maxRefreshRate)
        appendLine("Display maximum reported mode: ${display.maxWidth} × ${display.maxHeight} at $refresh Hz")
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

/** Reported rates are measured doubles; people read at most two decimals ("23.98", "60", "119.12"). The JSON keeps raw values. */
internal fun displayRate(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val rounded = BigDecimal(value).setScale(2, RoundingMode.HALF_UP)
    return if (rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
}
