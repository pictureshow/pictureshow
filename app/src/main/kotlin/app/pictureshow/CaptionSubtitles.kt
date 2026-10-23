/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.UserConfiguration
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType

/** A failed configuration read leaves the server in charge, including an explicit None mode. */
internal fun captionSubtitleLanguage(enabled: Boolean, configuration: UserConfiguration?, tvLanguage: String): String? =
    if (!enabled || configuration == null || configuration.subtitleMode.equals("None", ignoreCase = true)) {
        null
    } else {
        configuration.subtitleLanguage.takeIf(String::isNotBlank) ?: tvLanguage
    }

/** Full captions take precedence over forced-only translations; codec and delivery remain the server's job. */
internal fun captionSubtitleIndex(source: MediaSourceInfo, preferredLanguage: String): Int? {
    val language = captionLanguageKey(preferredLanguage) ?: return null
    return source.mediaStreams.filter {
        it.type == MediaStreamType.Subtitle && captionLanguageKey(it.language) == language
    }.sortedWith(
        compareBy<MediaStream> { it.isForced }
            .thenByDescending { it.isHearingImpaired }
            .thenByDescending { it.index == source.defaultSubtitleStreamIndex }
            .thenByDescending { it.isDefault }
            .thenBy { it.index }
    ).firstOrNull()?.index
}

// Jellyfin accepts ISO 639-2 bibliographic codes as well as the terminology codes returned by Locale.
private fun captionLanguageKey(value: String?): String? = when (val language = PlaybackTracks.language(value?.trim()?.lowercase())) {
    "alb" -> "sqi"
    "arm" -> "hye"
    "baq" -> "eus"
    "bur" -> "mya"
    "chi" -> "zho"
    "cze" -> "ces"
    "dut" -> "nld"
    "fre" -> "fra"
    "geo" -> "kat"
    "ger" -> "deu"
    "gre" -> "ell"
    "ice" -> "isl"
    "mac" -> "mkd"
    "mao" -> "mri"
    "may" -> "msa"
    "per" -> "fas"
    "rum" -> "ron"
    "slo" -> "slk"
    "tib" -> "bod"
    "wel" -> "cym"
    else -> language?.takeIf(String::isNotBlank)
}
