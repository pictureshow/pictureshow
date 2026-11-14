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
            "Commentary · SRT · Forced",
            trackLabelsPolicy(listOf(subtitle), languageName, names).single()
        )
        assertEquals(
            "English · SRT · Commentary · Forced",
            trackLabelPolicy(subtitle.copy(language = "en"), languageName = languageName, names = names)
        )
    }
}
