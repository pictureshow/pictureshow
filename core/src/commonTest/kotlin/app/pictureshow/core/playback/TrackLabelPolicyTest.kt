/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackLabelPolicyTest {
    private val languageName: (String?) -> String? = { if (it == "en") "English" else null }
    private val names: (String, String) -> Boolean = { text, phrase -> text.contains(phrase, ignoreCase = true) }

    @Test fun chooserDistinguishesLanguageDuplicatesAndOneDefault() {
        val audio = MediaStream(type = MediaStreamType.Audio, index = 1, language = "en", codec = "aac", channels = 2)
        assertEquals(
            listOf("English · AAC stereo · Track 1", "English · AAC stereo · Track 2"),
            trackLabelsPolicy(listOf(audio, audio.copy(index = 9)), languageName, names)
        )
        assertEquals(
            listOf("English · AAC stereo · Default", "English · AAC stereo"),
            trackLabelsPolicy(listOf(audio.copy(isDefault = true), audio.copy(index = 9)), languageName, names)
        )
    }

    @Test fun descriptiveRoleAndForcedStateSurviveWithoutInventingLanguage() {
        val subtitle = MediaStream(type = MediaStreamType.Subtitle, index = 7, codec = "srt", title = "Commentary", isForced = true)
        assertEquals(
            "Commentary · SRT · Track 1 · Forced",
            trackLabelsPolicy(listOf(subtitle), languageName, names).single()
        )
        assertEquals(
            "English · SRT · Commentary · Forced",
            trackLabelPolicy(subtitle.copy(language = "en"), languageName = languageName, names = names)
        )
    }

    @Test fun muxerHandlersFallBackExactlyAsMissingTitles() {
        val stream = MediaStream(
            type = MediaStreamType.Audio,
            index = 8,
            codec = "eac3",
            channels = 6,
            isDefault = true,
            isForced = true,
            isHearingImpaired = true,
            isExternal = true
        )
        for (title in listOf("Core Media Audio", "cOrE mEdIa TeXt", "SoundHandler", "GPAC ISO Audio Handler", "Lavf61.7.100")) {
            for (language in listOf(null, "en")) {
                val bare = stream.copy(language = language)
                assertEquals(
                    trackLabelPolicy(bare, 3, languageName = languageName, names = names),
                    trackLabelPolicy(bare.copy(title = title), 3, languageName = languageName, names = names),
                    "$title / $language"
                )
            }
        }
    }

    @Test fun realTitlesContainingHandlerWordsKeepTheirExistingLabels() {
        val stream = MediaStream(type = MediaStreamType.Audio, index = 8, codec = "aac", channels = 2)
        val titles = listOf(
            "Audio description",
            "Director's commentary",
            "Core Media Audio commentary",
            "The SoundHandler",
            "Lavf documentary",
            "Lavf61.7.100 commentary",
            "Stereo",
            "Surround"
        )
        for (title in titles) {
            assertEquals(
                "$title · AAC stereo · Track 1",
                trackLabelsPolicy(listOf(stream.copy(title = title)), languageName, names).single(),
                title
            )
        }
    }
}
