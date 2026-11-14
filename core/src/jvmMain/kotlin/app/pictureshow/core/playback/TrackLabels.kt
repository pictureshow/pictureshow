/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import java.util.Locale
import java.util.regex.Pattern

// Desktop JDK locale data echoes some ISO 639-2 terminology codes; Android ICU names them.
private val trackLanguageTags = Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).isO3Language }

fun trackLanguage(language: String?): String? {
    if (language.isNullOrBlank() || language == "und") return null
    // Try the server's tag first: the JDK maps "fr" to "French" but echoes some ISO-639-2 codes ("fra" -> "fra").
    val tag = language.replace('_', '-')
    val display = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)
    if (display.isNotBlank() && !display.equals(tag, ignoreCase = true)) return display
    val normalized = trackLanguageTags[StreamDefaults.language(language)] ?: language
    val normalizedDisplay = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.ENGLISH)
    if (normalizedDisplay.isNotBlank() && !normalizedDisplay.equals(normalized, ignoreCase = true)) return normalizedDisplay
    return language
}

// Keep native assertions: ICU case folding expands category sets (for example U+0345), and word
// boundaries depend on surrounding text. Compile these fixed sets once, with full-string context.
private val trackPhrasePrefix = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?<!\\bnon\\p{Pd})")
private val trackPhraseSuffix = Pattern.compile("(?i)(?![\\p{L}\\p{N}])")

private fun Pattern.matchesAt(value: String, index: Int): Boolean {
    val matcher = matcher(value)
    return matcher.find(index) && matcher.start() == index
}

// Android ICU performs full Unicode folding (ﬁ -> fi, ß -> ss); String.ignoreCase does not.
// Cache only the literal phrase: compiling Unicode boundary sets was the panel's hot path.
// A fresh Matcher keeps shared Patterns thread-safe and gives boundaries in original-string offsets.
private val trackPhrasePatterns = LinkedHashMap<String, Pattern>(32, .75f, true)

private fun trackPhrasePattern(value: String): Pattern = synchronized(trackPhrasePatterns) {
    trackPhrasePatterns[value] ?: Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE).also {
        trackPhrasePatterns[value] = it
        if (trackPhrasePatterns.size > 128) {
            val eldest = trackPhrasePatterns.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }
}

/** Match a complete metadata phrase without compiling Android ICU Unicode sets for every row/badge. */
fun trackMetadataNames(title: String, value: String): Boolean = title.names(value)

private fun String.names(value: String): Boolean {
    val matcher = trackPhrasePattern(value).matcher(this)
    var from = 0
    while (from <= length && matcher.find(from)) {
        val start = matcher.start()
        val end = matcher.end()
        if (trackPhrasePrefix.matchesAt(this, start) && trackPhraseSuffix.matchesAt(this, end)) return true
        // A rejected phrase may overlap a later valid one, as in non-Forced Forced Forced.
        from = start + 1
    }
    return false
}

/** JVM/Android locale and regex adapters for the shared composition policy. */
fun trackLabel(stream: MediaStream, trackNumber: Int? = null, showDefault: Boolean = true): String =
    trackLabelPolicy(stream, trackNumber, showDefault, ::trackLanguage, ::trackMetadataNames)

fun trackLabels(streams: List<MediaStream>): List<String> = trackLabelsPolicy(streams, ::trackLanguage, ::trackMetadataNames)
