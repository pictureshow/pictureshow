/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.UserConfiguration

/** A failed configuration read leaves the server in charge, including an explicit None mode. */
fun captionSubtitleLanguage(enabled: Boolean, configuration: UserConfiguration?, tvLanguage: String): String? =
    if (!enabled || configuration == null || configuration.subtitleMode.equals("None", ignoreCase = true)) {
        null
    } else {
        configuration.subtitleLanguage.takeIf(String::isNotBlank) ?: tvLanguage
    }

/** Retain a valid server subtitle or Off, in any language; it may be the viewer's remembered choice.
 * With no usable default, prefer full captions, hearing-impaired, then text in the caption language.
 * Delivery is negotiated by the server.
 */
fun captionSubtitleIndex(source: MediaSourceInfo, preferredLanguage: String): Int? {
    if (source.defaultSubtitleStreamIndex == -1) return -1
    source.mediaStreams.firstOrNull {
        it.type == MediaStreamType.Subtitle && it.index >= 0 && it.index == source.defaultSubtitleStreamIndex
    }?.let { return it.index }
    val language = captionLanguageKey(preferredLanguage) ?: return null
    return source.mediaStreams.filter {
        it.type == MediaStreamType.Subtitle && captionLanguageKey(it.language) == language
    }.sortedWith(
        compareBy<MediaStream> { it.isForced }
            .thenByDescending { it.isHearingImpaired }
            .thenByDescending { it.isTextSubtitleStream || it.codec?.lowercase() in textCaptionCodecs }
            .thenByDescending { it.isDefault }
            .thenBy { it.index }
    ).firstOrNull()?.index
}

private val textCaptionCodecs = setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "ttml", "mov_text", "tx3g", "text", "smi", "sami")

// Jellyfin accepts ISO 639-2 bibliographic codes as well as the terminology codes returned by Locale.
private fun captionLanguageKey(value: String?): String? = when (val language = StreamDefaults.language(value?.trim()?.lowercase())) {
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
