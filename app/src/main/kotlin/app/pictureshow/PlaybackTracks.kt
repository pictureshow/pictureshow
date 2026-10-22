/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.Format
import androidx.media3.common.Tracks
import app.pictureshow.core.playback.MediaStream
import java.util.Locale

/** A server stream index is not a container's track id. Match metadata first, then retained order. */
internal object PlaybackTracks {
    fun find(streams: List<MediaStream>, stream: MediaStream, groups: List<Tracks.Group>): Pair<Tracks.Group, Int>? {
        val candidates = groups.flatMap { group -> (0 until group.length).map { group to it } }
        if (PlaybackMedia.deliveredExternally(stream)) {
            return candidates.firstOrNull { (group, track) ->
                group.getTrackFormat(track).id?.endsWith(PlaybackMedia.subtitleId(stream.index)) == true
            }?.takeIf { (group, track) -> group.isTrackSupported(track, false) }
        }
        val embedded = candidates.filterNot { (group, track) -> group.getTrackFormat(track).id?.contains("server-subtitle:") == true }
        val compatible = embedded.filter { (group, track) ->
            val format = group.getTrackFormat(track)
            val wantedLanguage = language(stream.language)
            val actualLanguage = language(format.language)
            (wantedLanguage == null || actualLanguage == null || wantedLanguage == actualLanguage) &&
                (stream.title.isNullOrBlank() || format.label.isNullOrBlank() || stream.title == format.label)
        }
        val scored = compatible.map { candidate -> candidate to score(stream, candidate.first.getTrackFormat(candidate.second)) }
        val best = scored.maxOfOrNull { it.second } ?: return null
        val matches = scored.filter { it.second == best }
        if (best > 0 && matches.size == 1) {
            return matches.single().first.takeIf { (group, track) -> group.isTrackSupported(track, false) }
        }
        val ordinal = streams.filter { it.type == stream.type && !PlaybackMedia.deliveredExternally(it) }.sortedBy { it.index }
            .indexOfFirst { it.index == stream.index }
        // When metadata cannot distinguish two streams, preserve the demuxer's order within the type.
        return embedded.getOrNull(ordinal)?.takeIf { candidate ->
            matches.any { it.first == candidate } &&
                candidate.first.isTrackSupported(candidate.second, false)
        }
    }

    private fun score(stream: MediaStream, format: Format): Int =
        (if (!stream.title.isNullOrBlank() && stream.title == format.label) 16 else 0) +
            (if (language(stream.language) != null && language(stream.language) == language(format.language)) 8 else 0) +
            (if (stream.channels != null && stream.channels == format.channelCount) 2 else 0) +
            (if (stream.codec != null && codec(format.sampleMimeType) == normalizeCodec(stream.codec)) 1 else 0)

    fun language(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "und" }?.let {
        runCatching { Locale.forLanguageTag(it.replace('_', '-')).isO3Language }.getOrDefault(it.lowercase())
    }

    private fun normalizeCodec(codec: String?): String? = when (val name = codec?.lowercase(Locale.ROOT)) {
        "srt", "subrip" -> "subrip"
        "ssa", "ass" -> "ass"
        "pgs", "pgssub", "hdmv_pgs_subtitle" -> "pgs"
        "dvdsub", "dvd_subtitle", "vobsub" -> "vobsub"
        "mov_text", "tx3g" -> "tx3g"
        "vtt", "webvtt" -> "webvtt"
        else -> name
    }

    private fun codec(mime: String?): String? = when (mime) {
        "audio/mp4a-latm" -> "aac"
        "audio/ac3" -> "ac3"
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "dts"
        "audio/true-hd" -> "truehd"
        "application/x-subrip" -> "subrip"
        "text/x-ssa" -> "ass"
        "text/vtt" -> "webvtt"
        "application/pgs" -> "pgs"
        "application/vobsub" -> "vobsub"
        "application/x-quicktime-tx3g" -> "tx3g"
        else -> mime?.substringAfterLast('/')
    }
}
