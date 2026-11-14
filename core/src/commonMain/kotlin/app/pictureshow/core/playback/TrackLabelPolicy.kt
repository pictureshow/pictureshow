/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/**
 * A track's language names it; without one the server's title or the codec/channel description carries the label,
 * and [trackNumber] keeps same-looking tracks apart. "Default" appears only when [showDefault] says it
 * distinguishes this track in its list.
 */
fun trackLabelPolicy(
    stream: MediaStream,
    trackNumber: Int? = null,
    showDefault: Boolean = true,
    languageName: (String?) -> String?,
    names: (String, String) -> Boolean
): String = buildList {
    val codec = when (stream.codec?.lowercase()) {
        "eac3" -> "E-AC-3"
        "ac3" -> "AC-3"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "subrip", "srt" -> "SRT"
        "hdmv_pgs_subtitle", "pgssub", "pgs" -> "PGS"
        "dvd_subtitle", "dvdsub", "vobsub" -> "VobSub"
        "mov_text" -> "mov_text"
        "opus" -> "Opus"
        else -> stream.codec?.uppercase() ?: "Unknown format"
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
    val language = languageName(stream.language)
    val title = stream.title?.takeIf { it.isNotBlank() }
    val name = if (language != null && title?.let { names(it, language) } == true) title else language ?: title
    if (name != null) {
        add(name)
        val titleCarriesFormat = title != null && !title.equals(name, true) && !title.equals(format, true) && names(title, format)
        if (!names(name, format) && !titleCarriesFormat) add(format)
    } else {
        add(format)
    }
    // Keep descriptive titles (for example audio description or commentary) even when a language is present.
    title?.takeIf { language != null && !it.equals(name, true) && !it.equals(format, true) }?.let(::add)
    if (language == null && trackNumber != null) add("Track $trackNumber")
    fun badge(word: String, enabled: Boolean) {
        val alreadyNamed = title?.let { names(it, word) } == true
        if (enabled && !alreadyNamed) add(word)
    }
    badge("Default", showDefault && stream.isDefault)
    badge("Forced", stream.isForced)
    badge("SDH", stream.isHearingImpaired)
    badge("External", stream.isExternal || stream.isExternalUrl)
}.joinToString(" · ")

/**
 * Labels for a whole chooser list. Track numbers only disambiguate colliding labels, and "Default" is kept only for a list where exactly one track is the default, where it actually distinguishes that track.
 */
fun trackLabelsPolicy(streams: List<MediaStream>, languageName: (String?) -> String?, names: (String, String) -> Boolean): List<String> {
    val defaults = streams.count { it.isDefault }
    val labels = streams.map { stream ->
        trackLabelPolicy(stream, showDefault = defaults == 1 && streams.size > 1, languageName = languageName, names = names)
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
