/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import java.util.Locale

internal fun trackLanguage(language: String?): String {
    if (language.isNullOrBlank() || language == "und") return "Unknown language"
    val normalized = PlaybackTracks.language(language) ?: language
    val locale = Locale.forLanguageTag(normalized)
    return locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() } ?: language
}

internal fun trackLabel(stream: MediaStream): String = buildList {
    add(trackLanguage(stream.language))
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
    add(codec + channels)
    if (stream.isDefault) add("Default")
    if (stream.isForced) add("Forced")
    if (stream.isHearingImpaired) add("SDH")
    if (stream.isExternal || stream.isExternalUrl) add("External")
}.joinToString(" · ")
