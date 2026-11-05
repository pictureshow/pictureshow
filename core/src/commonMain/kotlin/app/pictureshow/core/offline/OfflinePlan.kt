/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

data class OfflinePolicy(val enableDownloads: Boolean, val enablePlayback: Boolean, val videoTranscoding: Boolean, val audioTranscoding: Boolean)

data class OfflineCandidate(
    val itemId: String,
    val type: String,
    val isInfinite: Boolean = false,
    val sizeBytes: Long? = null,
    val bitrate: Int? = null,
    val runtimeTicks: Long? = null,
    val canDownload: Boolean? = null
)

/** reason: "denied","no-conversion-right","not-qualified","live","wrong-media". */
data class OfflinePresetChoice(
    val preset: OfflinePreset,
    val available: Boolean,
    val reason: String? = null,
    val estimateBytes: Long? = null,
    val exactSize: Boolean = false
)

private val LIVE_TYPES = setOf("TvChannel", "LiveTvChannel", "LiveTvProgram", "Program", "TvProgram")
private val VIDEO_TYPES = setOf("Movie", "Episode", "Video")
private const val TICKS_PER_SECOND = 10_000_000L

private val OfflinePreset.isVideo get() = this == OfflinePreset.video720 || this == OfflinePreset.video1080
private val OfflinePreset.isAudio get() = this == OfflinePreset.audio128 || this == OfflinePreset.audio256

/** Bytes per second the preset's output runs at, video including its 128 kbps audio. */
private fun OfflinePreset.bytesPerSecond(): Long = when (this) {
    OfflinePreset.video720 -> (4_000_000L + 128_000) / 8
    OfflinePreset.video1080 -> (8_000_000L + 128_000) / 8
    OfflinePreset.audio128 -> 128_000L / 8
    OfflinePreset.audio256 -> 256_000L / 8
    OfflinePreset.original -> error("Original has no fixed rate")
}

private fun OfflinePreset.estimate(runtimeTicks: Long?): Long? = runtimeTicks?.let { bytesPerSecond() * it / TICKS_PER_SECOND }

private fun OfflineCandidate.isLive() = isInfinite || type in LIVE_TYPES

fun offlinePresets(policy: OfflinePolicy, candidate: OfflineCandidate, qualified: Set<OfflinePreset>): List<OfflinePresetChoice> =
    OfflinePreset.entries.map { preset ->
        fun unavailable(reason: String) = OfflinePresetChoice(preset, false, reason)
        when {
            !policy.enableDownloads || !policy.enablePlayback || candidate.canDownload == false -> unavailable("denied")
            candidate.isLive() -> unavailable("live")
            preset == OfflinePreset.original ->
                OfflinePresetChoice(preset, true, estimateBytes = candidate.sizeBytes, exactSize = candidate.sizeBytes != null)
            preset.isVideo && candidate.type !in VIDEO_TYPES -> unavailable("wrong-media")
            preset.isAudio && candidate.type != "Audio" -> unavailable("wrong-media")
            preset.isVideo && !policy.videoTranscoding -> unavailable("no-conversion-right")
            preset.isAudio && !policy.audioTranscoding -> unavailable("no-conversion-right")
            preset !in qualified -> unavailable("not-qualified")
            else -> OfflinePresetChoice(preset, true, estimateBytes = preset.estimate(candidate.runtimeTicks))
        }
    }

/**
 * Container expansion: ordered, de-duplicated by itemId keeping the first position. skipped holds live, non-downloadable and wrong-media
 * items. knownBytes sums exact sizes and estimates; estimated says whether any item is estimated or unknown; unknownCount counts those
 * with neither.
 */
data class OfflineSelection(val itemIds: List<String>, val skipped: List<String>, val knownBytes: Long, val estimated: Boolean, val unknownCount: Int)

fun offlineSelect(items: List<OfflineCandidate>, preset: OfflinePreset): OfflineSelection {
    val (taken, skipped) = items.distinctBy { it.itemId }.partition { item ->
        !item.isLive() && item.canDownload != false && when {
            preset.isVideo -> item.type in VIDEO_TYPES
            preset.isAudio -> item.type == "Audio"
            else -> true
        }
    }
    var known = 0L
    var estimated = false
    var unknown = 0
    for (item in taken) {
        val exact = item.sizeBytes.takeIf { preset == OfflinePreset.original }
        val estimate = if (preset == OfflinePreset.original) item.originalEstimate() else preset.estimate(item.runtimeTicks)
        when {
            exact != null -> known += exact
            estimate != null -> {
                known += estimate
                estimated = true
            }
            else -> {
                estimated = true
                unknown++
            }
        }
    }
    return OfflineSelection(taken.map { it.itemId }, skipped.map { it.itemId }, known, estimated, unknown)
}

private fun OfflineCandidate.originalEstimate(): Long? {
    val rate = bitrate ?: return null
    val ticks = runtimeTicks ?: return null
    return (rate / 8L) * ticks / TICKS_PER_SECOND
}

/** method is GET but for the conversion cleanup. */
data class OfflineRequest(val path: String, val query: List<Pair<String, String>>, val method: String = "GET")

fun offlineOriginalRequest(itemId: String): OfflineRequest = OfflineRequest("/Items/$itemId/Download", emptyList())

/** Finite conversion request. The parameter order is exact: vectors freeze it. */
fun offlineVideoRequest(
    itemId: String,
    sourceId: String,
    preset: OfflinePreset,
    audioStreamIndex: Int?,
    deviceId: String,
    playSessionId: String
): OfflineRequest {
    val (height, rate) = when (preset) {
        OfflinePreset.video720 -> "720" to "4000000"
        OfflinePreset.video1080 -> "1080" to "8000000"
        else -> throw IllegalArgumentException("Not a video preset: $preset")
    }
    return OfflineRequest(
        "/Videos/$itemId/stream.mp4",
        buildList {
            add("mediaSourceId" to sourceId)
            add("playSessionId" to playSessionId)
            add("deviceId" to deviceId)
            add("videoCodec" to "h264")
            add("audioCodec" to "aac")
            add("maxHeight" to height)
            add("videoBitRate" to rate)
            add("audioBitRate" to "128000")
            add("maxAudioChannels" to "2")
            if (audioStreamIndex != null) add("audioStreamIndex" to audioStreamIndex.toString())
            add("subtitleMethod" to "Drop")
            add("static" to "false")
            add("enableAutoStreamCopy" to "false")
            add("allowVideoStreamCopy" to "false")
            add("allowAudioStreamCopy" to "false")
            add("container" to "mp4")
        }
    )
}

fun offlineSidecarAudioRequest(
    itemId: String,
    sourceId: String,
    preset: OfflinePreset,
    audioStreamIndex: Int,
    deviceId: String,
    playSessionId: String
): OfflineRequest {
    require(preset.isVideo) { "A sidecar belongs to a video preset: $preset" }
    return OfflineRequest(
        "/Audio/$itemId/stream.m4a",
        listOf(
            "mediaSourceId" to sourceId,
            "playSessionId" to playSessionId,
            "deviceId" to deviceId,
            "audioCodec" to "aac",
            "audioBitRate" to "128000",
            "maxAudioChannels" to "2",
            "audioStreamIndex" to audioStreamIndex.toString(),
            "static" to "false",
            "allowAudioStreamCopy" to "false"
        )
    )
}

fun offlineAudioRequest(itemId: String, sourceId: String?, preset: OfflinePreset, deviceId: String, playSessionId: String): OfflineRequest {
    val rate = when (preset) {
        OfflinePreset.audio128 -> "128000"
        OfflinePreset.audio256 -> "256000"
        else -> throw IllegalArgumentException("Not an audio preset: $preset")
    }
    return OfflineRequest(
        "/Audio/$itemId/stream.m4a",
        buildList {
            if (sourceId != null) add("mediaSourceId" to sourceId)
            add("playSessionId" to playSessionId)
            add("deviceId" to deviceId)
            add("audioCodec" to "aac")
            add("audioBitRate" to rate)
            add("maxAudioChannels" to "2")
            add("static" to "false")
            add("allowAudioStreamCopy" to "false")
        }
    )
}

fun offlineSubtitleRequest(itemId: String, sourceId: String, streamIndex: Int): OfflineRequest =
    OfflineRequest("/Videos/$itemId/$sourceId/Subtitles/$streamIndex/0/Stream.vtt", emptyList())

fun offlineCleanupRequest(deviceId: String, playSessionId: String): OfflineRequest =
    OfflineRequest("/Videos/ActiveEncodings", listOf("deviceId" to deviceId, "playSessionId" to playSessionId), "DELETE")
