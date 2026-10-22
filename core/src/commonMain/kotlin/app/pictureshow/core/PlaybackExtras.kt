/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    @SerialName("StartPositionTicks") val startTicks: Long = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("ImageTag") val imageTag: String? = null
) {
    val startMs: Long get() = startTicks.coerceAtLeast(0) / 10_000
}

@Serializable
data class TrickplayInfo(
    @SerialName("Width") val width: Int = 0,
    @SerialName("Height") val height: Int = 0,
    @SerialName("TileWidth") val tileWidth: Int = 0,
    @SerialName("TileHeight") val tileHeight: Int = 0,
    @SerialName("ThumbnailCount") val thumbnailCount: Int = 0,
    @SerialName("Interval") val interval: Int = 0
)

@Serializable
enum class SegmentType { Unknown, Intro, Outro, Recap, Preview, Commercial }
enum class SegmentMode { Ask, Automatic, Off }

@Serializable
data class MediaSegment(
    @SerialName("Id") val id: String = "",
    @SerialName("Type") val type: SegmentType = SegmentType.Unknown,
    @SerialName("StartTicks") val startTicks: Long = 0,
    @SerialName("EndTicks") val endTicks: Long = 0
) {
    val startMs: Long get() = startTicks.coerceAtLeast(0) / 10_000
    val endMs: Long get() = endTicks.coerceAtLeast(0) / 10_000
}

/** Segment end is exclusive. Unknown and malformed server data never cause a seek. */
fun activeSegment(segments: List<MediaSegment>, positionMs: Long, modes: Map<SegmentType, SegmentMode> = emptyMap()): MediaSegment? =
    segments.filter {
        it.type != SegmentType.Unknown &&
            it.startTicks >= 0 &&
            it.endMs > it.startMs &&
            positionMs >= it.startMs &&
            positionMs < it.endMs &&
            modes[it.type] != SegmentMode.Off
    }.minByOrNull { it.startTicks }
fun creditsStartMs(durationMs: Long, segments: List<MediaSegment>): Long? = segments.filter {
    it.type == SegmentType.Outro && it.startTicks >= 0 && it.endMs > it.startMs && it.startMs < durationMs
}.minOfOrNull { it.startMs }

/** From the credits, or for the last 10 seconds when there is no credits segment, so no story is cut. */
fun nextEpisodeTriggerMs(durationMs: Long, segments: List<MediaSegment>): Long? {
    if (durationMs <= 0) return null
    return creditsStartMs(durationMs, segments) ?: (durationMs - 10_000).coerceAtLeast(0)
}

data class TrickplayTile(val info: TrickplayInfo, val sheet: Int, val column: Int, val row: Int)
fun trickplayTile(info: TrickplayInfo, positionMs: Long): TrickplayTile? {
    if (info.width !in 1..4096 ||
        info.height !in 1..4096 ||
        info.tileWidth !in 1..100 ||
        info.tileHeight !in 1..100 ||
        info.interval <= 0 ||
        info.thumbnailCount <= 0
    ) {
        return null
    }
    val frame = (positionMs.coerceAtLeast(0) / info.interval).coerceAtMost(info.thumbnailCount.toLong() - 1).toInt()
    val perSheet = info.tileWidth * info.tileHeight
    return TrickplayTile(info, frame / perSheet, frame % perSheet % info.tileWidth, frame % perSheet / info.tileWidth)
}

/** Match the selected media source; a different version's thumbnail timeline can be wrong. */
fun chooseTrickplay(item: MediaItem, mediaSourceId: String?, preferredWidth: Int): TrickplayInfo? {
    val widths = if (mediaSourceId != null) item.trickplay[mediaSourceId] else item.trickplay.values.singleOrNull()
    val valid = widths.orEmpty().values.filter { trickplayTile(it, 0) != null }.sortedBy { it.width }
    return valid.firstOrNull { it.width >= preferredWidth } ?: valid.lastOrNull()
}
