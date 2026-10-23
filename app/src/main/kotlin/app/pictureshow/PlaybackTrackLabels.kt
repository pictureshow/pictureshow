/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import java.util.Locale

internal fun trackLanguage(language: String?): String? {
    if (language.isNullOrBlank() || language == "und") return null
    // Try the server's tag first: the JDK maps "fr" to "French" but echoes some ISO-639-2 codes ("fra" -> "fra").
    val tag = language.replace('_', '-')
    val display = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)
    if (display.isNotBlank() && !display.equals(tag, ignoreCase = true)) return display
    val normalized = PlaybackTracks.language(language) ?: language
    val normalizedDisplay = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.ENGLISH)
    if (normalizedDisplay.isNotBlank() && !normalizedDisplay.equals(normalized, ignoreCase = true)) return normalizedDisplay
    return language
}

/**
 * A track's language names it; without one the server's title or the codec/channel description carries the label,
 * and [trackNumber] keeps same-looking tracks apart. "Default" appears only when [showDefault] says it
 * distinguishes this track in its list.
 */
internal fun trackLabel(stream: MediaStream, trackNumber: Int? = null, showDefault: Boolean = true): String = buildList {
    val codec = when (stream.codec?.lowercase(Locale.ROOT)) {
        "eac3" -> "E-AC-3"
        "ac3" -> "AC-3"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "subrip", "srt" -> "SRT"
        "hdmv_pgs_subtitle", "pgssub", "pgs" -> "PGS"
        "dvd_subtitle", "dvdsub", "vobsub" -> "VobSub"
        "mov_text" -> "mov_text"
        "opus" -> "Opus"
        else -> stream.codec?.uppercase(Locale.ROOT) ?: "Unknown format"
    }
    val channels = if (stream.type == MediaStreamType.Audio) {
        when (stream.channels) {
            1 -> " mono"
            2 -> " stereo"
            6 -> " 5.1"
            8 -> " 7.1"
            null -> ""
            else -> " ${stream.channels} channels"
        }
    } else {
        ""
    }
    val format = codec + channels
    val language = trackLanguage(stream.language)
    val name = language ?: stream.title?.takeIf { it.isNotBlank() }
    if (name != null) {
        add(name)
        if (!name.equals(format, ignoreCase = true)) add(format)
    } else {
        add(format)
    }
    if (language == null && trackNumber != null) add("Track $trackNumber")
    if (showDefault && stream.isDefault) add("Default")
    if (stream.isForced) add("Forced")
    if (stream.isHearingImpaired) add("SDH")
    if (stream.isExternal || stream.isExternalUrl) add("External")
}.joinToString(" · ")

/**
 * Labels for a whole chooser list. A track number identifies every track that lacks a language, and "Default" is
 * kept only for a list where exactly one track is the default, where it actually distinguishes that track.
 */
internal fun trackLabels(streams: List<MediaStream>): List<String> {
    val defaults = streams.count { it.isDefault }
    return streams.mapIndexed { position, stream ->
        trackLabel(stream, trackNumber = position + 1, showDefault = defaults == 1 && streams.size > 1)
    }
}
