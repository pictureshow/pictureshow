/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineRequest
import app.pictureshow.core.offline.OfflineResource
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.offline.offlineAudioRequest
import app.pictureshow.core.offline.offlineEntryId
import app.pictureshow.core.offline.offlineOriginalRequest
import app.pictureshow.core.offline.offlineSidecarAudioRequest
import app.pictureshow.core.offline.offlineSubtitleRequest
import app.pictureshow.core.offline.offlineVideoRequest

/** What the preview screen decided. Track lists are server stream indexes. */
internal data class OfflineChoice(
    val itemId: String,
    val preset: OfflinePreset,
    val audio: List<Int> = emptyList(),
    val subtitles: List<Int> = emptyList(),
    val cellular: Boolean = false
)

internal const val OFFLINE_MAX_CONVERTED_AUDIO = 2
internal const val OFFLINE_MAX_CONVERTED_SUBTITLES = 2

private val converted = setOf(OfflinePreset.video720, OfflinePreset.video1080)

internal val OfflinePreset.isConvertedVideo: Boolean get() = this in converted

private val OfflinePreset.isConvertedAudio: Boolean get() = this == OfflinePreset.audio128 || this == OfflinePreset.audio256

private fun OfflineApiStream.label(): String = displayTitle ?: title ?: language ?: "Track $index"

/**
 * Original keeps every embedded track and saves external captions beside it. A conversion carries one muxed audio (the
 * lowest selected index, so demuxer order and server order agree), one more as a sidecar, and text subtitles as WebVTT.
 * Everything else is listed unavailable, so the viewer sees it and can deliberately go online for it (OFF-05).
 */
internal fun offlineBuildEntry(item: OfflineApiItem, choice: OfflineChoice, sequence: Long, warning: String? = null): OfflineEntry {
    val source = item.sources.firstOrNull()
    val streams = source?.streams.orEmpty()
    val audio = streams.filter { it.type == "Audio" }
    val subs = streams.filter { it.type == "Subtitle" }
    val resources = mutableListOf<OfflineResource>()
    val tracks = mutableListOf<OfflineTrack>()
    fun subtitleResource(stream: OfflineApiStream): String {
        val id = "subtitle-${stream.index}"
        resources +=
            OfflineResource(
                id,
                OfflineResourceRole.subtitle,
                "$id.vtt",
                required = true,
                serverStreamIndex = stream.index,
                mimeType = "text/vtt"
            )
        return id
    }
    fun track(stream: OfflineApiStream, kind: String, resourceId: String?, available: Boolean, codec: String? = stream.codec) =
        OfflineTrack(
            stream.index,
            kind,
            stream.label(),
            stream.language,
            stream.isDefault,
            stream.isForced,
            codec,
            resourceId,
            available
        )
    when {
        choice.preset == OfflinePreset.original -> {
            val extension = source?.container?.substringBefore(',')?.lowercase()?.filter(Char::isLetterOrDigit)?.ifBlank { null } ?: "bin"
            resources += OfflineResource("media", OfflineResourceRole.media, "media.$extension", bytes = null)
            audio.forEach { tracks += track(it, "audio", null, !it.isExternal) }
            subs.forEach { stream ->
                when {
                    !stream.isExternal -> tracks += track(stream, "subtitle", null, true)
                    stream.isText -> tracks += track(stream, "subtitle", subtitleResource(stream), true, "vtt")
                    else -> tracks += track(stream, "subtitle", null, false)
                }
            }
        }
        choice.preset.isConvertedVideo -> {
            val selected = choice.audio.distinct().sorted().take(OFFLINE_MAX_CONVERTED_AUDIO)
            val muxed = selected.firstOrNull() ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
            resources += OfflineResource("media", OfflineResourceRole.media, "media.mp4", serverStreamIndex = muxed, mimeType = "video/mp4")
            audio.forEach { stream ->
                when {
                    stream.index == muxed -> tracks += track(stream, "audio", null, true, "aac")
                    stream.index in selected -> {
                        val id = "audio-${stream.index}"
                        resources +=
                            OfflineResource(
                                id,
                                OfflineResourceRole.audio,
                                "$id.m4a",
                                serverStreamIndex = stream.index,
                                mimeType = "audio/mp4"
                            )
                        tracks += track(stream, "audio", id, true, "aac")
                    }
                    else -> tracks += track(stream, "audio", null, false)
                }
            }
            val wanted = choice.subtitles.distinct().take(OFFLINE_MAX_CONVERTED_SUBTITLES)
            subs.forEach { stream ->
                if (stream.index in wanted && stream.isText) {
                    tracks += track(stream, "subtitle", subtitleResource(stream), true, "vtt")
                } else {
                    tracks += track(stream, "subtitle", null, false)
                }
            }
        }
        else -> resources += OfflineResource("media", OfflineResourceRole.media, "media.m4a", mimeType = "audio/mp4")
    }
    resources += OfflineResource("artwork", OfflineResourceRole.artwork, "art.jpg", required = false, mimeType = "image/jpeg")
    val kind = when {
        choice.preset.isConvertedAudio || item.type == "Audio" -> OfflineMediaKind.audio
        item.type == "Episode" -> OfflineMediaKind.episode
        else -> OfflineMediaKind.movie
    }
    return OfflineEntry(
        id = offlineEntryId(item.id, choice.preset),
        itemId = item.id,
        kind = kind,
        sourceId = source?.id,
        sourceETag = source?.etag,
        container = source?.container,
        preset = choice.preset,
        runtimeTicks = item.runtimeTicks ?: source?.runtimeTicks,
        labels = OfflineLabels(
            title = item.name,
            seriesName = item.seriesName,
            seriesId = item.seriesId,
            seasonId = item.seasonId,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            year = item.year,
            overview = item.overview
        ),
        resources = resources,
        tracks = tracks,
        job = OfflineJob(cellularAllowed = choice.cellular),
        createdSequence = sequence,
        warning = warning
    )
}

/** The request that fetches one resource. A conversion's session id is new per attempt. */
internal fun OfflineEntry.requestFor(resource: OfflineResource, deviceId: String, playSessionId: String): OfflineRequest {
    val source = sourceId ?: itemId
    return when (resource.role) {
        OfflineResourceRole.media -> when {
            preset == OfflinePreset.original -> offlineOriginalRequest(itemId)
            preset.isConvertedVideo -> offlineVideoRequest(itemId, source, preset, resource.serverStreamIndex, deviceId, playSessionId)
            else -> offlineAudioRequest(itemId, sourceId, preset, deviceId, playSessionId)
        }
        OfflineResourceRole.audio -> offlineSidecarAudioRequest(
            itemId,
            source,
            preset,
            resource.serverStreamIndex ?: 0,
            deviceId,
            playSessionId
        )
        OfflineResourceRole.subtitle -> offlineSubtitleRequest(itemId, source, resource.serverStreamIndex ?: 0)
        OfflineResourceRole.artwork -> OfflineRequest("/Items/$itemId/Images/Primary", listOf("maxWidth" to "480", "format" to "Jpg"))
    }
}

/** Server conversions are the fragmented ones that need an index written after the transfer. */
internal fun OfflineEntry.needsIndex(resource: OfflineResource): Boolean = preset != OfflinePreset.original &&
    (resource.role == OfflineResourceRole.media || resource.role == OfflineResourceRole.audio)
