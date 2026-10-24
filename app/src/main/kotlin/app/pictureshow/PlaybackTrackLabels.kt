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

/** Match a complete metadata word or phrase, so "Unforced" cannot suppress "Forced". */
private fun String.names(value: String): Boolean = contains(
    Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(value)}(?![\\p{L}\\p{N}])")
)

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
    val title = stream.title?.takeIf { it.isNotBlank() }
    val name = if (language != null && title?.names(language) == true) title else language ?: title
    if (name != null) {
        add(name)
        val titleCarriesFormat = title != null && !title.equals(name, true) && !title.equals(format, true) && title.names(format)
        if (!name.names(format) && !titleCarriesFormat) add(format)
    } else {
        add(format)
    }
    // Keep descriptive titles (for example audio description or commentary) even when a language is present.
    title?.takeIf { language != null && !it.equals(name, true) && !it.equals(format, true) }?.let(::add)
    if (language == null && trackNumber != null) add("Track $trackNumber")
    fun badge(word: String, enabled: Boolean) {
        val alreadyNamed = title?.names(word) == true
        if (enabled && !alreadyNamed) add(word)
    }
    badge("Default", showDefault && stream.isDefault)
    badge("Forced", stream.isForced)
    badge("SDH", stream.isHearingImpaired)
    badge("External", stream.isExternal || stream.isExternalUrl)
}.joinToString(" · ")

/**
 * Labels for a whole chooser list. A track number identifies every track that lacks a language, and "Default" is
 * kept only for a list where exactly one track is the default, where it actually distinguishes that track.
 */
internal fun trackLabels(streams: List<MediaStream>): List<String> {
    val defaults = streams.count { it.isDefault }
    val labels = streams.mapIndexed { position, stream ->
        trackLabel(stream, trackNumber = position + 1, showDefault = defaults == 1 && streams.size > 1)
    }
    // Use menu order, never an invented language/role. Resolve collisions again in case a server title
    // itself contains one of these suffixes. Every appended number is unique within this chooser.
    var distinct = labels
    while (distinct.toSet().size != distinct.size) {
        val duplicates = distinct.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        distinct = distinct.mapIndexed { position, label -> if (label in duplicates) "$label · Track ${position + 1}" else label }
    }
    return distinct
}
