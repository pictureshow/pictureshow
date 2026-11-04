/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTrackUnicodeLabelsTest {
    @Test
    fun metadataPhrasesKeepThePlatformsOriginalRegexCaseFolding() {
        val names = Class.forName("app.pictureshow.PlaybackTrackLabelsKt")
            .getDeclaredMethod("names", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val cases = listOf(
            "ﬁnnish Commentary" to "Finnish",
            "AAC ﬆereo" to "AAC stereo",
            "ENGLİSH" to "English",
            "Fınnish" to "Finnish",
            "Swiss German" to "Swiss German",
            "Swiß German" to "Swiss German"
        )
        // The host JDK and Android ICU have different folds. Compare with the original
        // expression here; the native production-class probe pins Android's six answers.
        cases.forEach { (title, phrase) ->
            assertEquals(originalNames(title, phrase), names.invoke(null, title, phrase), "$title / $phrase")
        }
    }

    @Test
    fun languageTitleKeepsItsOriginalSpellingWhenItsFoldedPhraseMatches() {
        assertEquals(
            if (originalNames("ﬁnnish Commentary", "Finnish")) "ﬁnnish Commentary · SRT" else "Finnish · SRT · ﬁnnish Commentary",
            trackLabel(
                MediaStream(type = MediaStreamType.Subtitle, index = 1, language = "fin", codec = "srt", title = "ﬁnnish Commentary")
            )
        )
    }

    @Test
    fun negatedMetadataPrefixesKeepThePlatformsWordBoundarySemantics() {
        val names = Class.forName("app.pictureshow.PlaybackTrackLabelsKt")
            .getDeclaredMethod("names", String::class.java, String::class.java)
            .apply { isAccessible = true }
        listOf(
            "", " ", "-", "_", "é", "\u0301", " \u0301", "-\u0301", "é\u0301",
            "\u0301\u0327", " \u0301\u0327", "é\u0301\u0327",
            "\u200C", " \u200C", "é\u200C", "\u200D", " \u200D", "é\u200D", "²", "Ⅷ"
        ).forEach { prefix ->
            val title = "${prefix}non-Forced"
            assertEquals(originalNames(title, "Forced"), names.invoke(null, title, "Forced"), title)
        }
    }

    @Test
    fun categoryBoundariesKeepTheOriginalCaseInsensitiveSetSemantics() {
        val names = Class.forName("app.pictureshow.PlaybackTrackLabelsKt")
            .getDeclaredMethod("names", String::class.java, String::class.java)
            .apply { isAccessible = true }
        // On Android, this combining mark folds to a letter and belongs to (?i)\p{L}.
        // Host JDK behavior differs; native goldens pin the Android answers. Supplementary
        // lookbehind and recently assigned dash characters also vary by regex engine/version.
        listOf(
            "\u0345Forced" to "Forced",
            "Forced\u0345" to "Forced",
            "\u0345SRT" to "SRT",
            "SRT\u0345" to "SRT",
            "𐐀Forced" to "Forced",
            "non𐺭Forced" to "Forced"
        ).forEach { (title, phrase) ->
            assertEquals(originalNames(title, phrase), names.invoke(null, title, phrase), title)
        }
    }

    private fun originalNames(title: String, phrase: String): Boolean = title.contains(
        Regex("(?i)(?<![\\p{L}\\p{N}])(?<!\\bnon\\p{Pd})${Regex.escape(phrase)}(?![\\p{L}\\p{N}])")
    )
}
