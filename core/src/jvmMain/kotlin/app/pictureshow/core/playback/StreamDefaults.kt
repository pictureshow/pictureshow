/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import java.util.Locale

/** Exact Android default-stream policy; server indices carry the viewer's language and subtitle mode. */
object StreamDefaults {
    fun choose(streams: List<MediaStream>, type: MediaStreamType, serverIndex: Int?, preferredLanguage: String): Int {
        // A null server subtitle index means its user mode selected no subtitles (for example None).
        if (type == MediaStreamType.Subtitle) return serverIndex ?: -1
        val preferred = language(preferredLanguage)
        return serverIndex ?: streams.firstOrNull { it.type == type && it.isDefault }?.index
            ?: streams.firstOrNull { it.type == type && preferred != null && language(it.language) == preferred }?.index
            ?: if (type == MediaStreamType.Audio) streams.firstOrNull { it.type == type }?.index ?: -1 else -1
    }

    fun language(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "und" }?.let {
        runCatching { Locale.forLanguageTag(it.replace('_', '-')).isO3Language }.getOrDefault(it.lowercase())
    }
}
