/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTrackMenuTest {
    @Test
    fun descriptiveTitlesDoNotRepeatForcedDefaultSdhOrExternalBadges() {
        val stream = MediaStream(
            type = MediaStreamType.Subtitle, index = 4, language = "en", codec = "ass",
            title = "English Forced SDH External Default", isForced = true, isDefault = true,
            isHearingImpaired = true, isExternal = true
        )
        assertEquals("English Forced SDH External Default · ASS", trackLabel(stream))
        assertEquals("English · ASS Forced SDH External Default", trackLabel(stream.copy(title = "ASS Forced SDH External Default")))
        assertEquals(
            "English · ASS · Forced narrative · SDH · External",
            trackLabel(
                stream.copy(
                    title = "Forced narrative",
                    isDefault = false
                )
            )
        )
        assertEquals(
            "English · ASS · Unforced · Forced",
            trackLabel(
                stream.copy(
                    title = "Unforced",
                    isDefault = false,
                    isHearingImpaired = false,
                    isExternal = false
                )
            )
        )
    }

    @Test
    fun identicalLanguageTracksHaveDistinctHonestNumbersAndUniqueTitlesStayShort() {
        val first = MediaStream(type = MediaStreamType.Subtitle, index = 4, language = "en", codec = "srt")
        val labels = trackLabels(listOf(first, first.copy(index = 9), first.copy(index = 11, title = "Commentary")))
        assertEquals(listOf("English · SRT · Track 1", "English · SRT · Track 2", "English · SRT · Commentary"), labels)
        val collision = trackLabels(listOf(first, first.copy(index = 9), first.copy(index = 11, title = "Track 1")))
        assertEquals(collision.size, collision.toSet().size)
    }

    @Test
    fun tracksWithoutLanguageGetNumbersAndAllDefaultTracksGetNoBadge() {
        val streams = listOf(
            MediaStream(type = MediaStreamType.Audio, index = 1, codec = "truehd", channels = 6, isDefault = true),
            MediaStream(type = MediaStreamType.Audio, index = 2, codec = "dts", channels = 6, isDefault = true),
            MediaStream(type = MediaStreamType.Audio, index = 3, codec = "vorbis", channels = 6, isDefault = true)
        )
        assertEquals(
            listOf("TrueHD 5.1 · Track 1", "DTS 5.1 · Track 2", "VORBIS 5.1 · Track 3"),
            trackLabels(streams)
        )
    }

    @Test
    fun theOnlyDefaultEarnsTheBadge() {
        val streams = listOf(
            MediaStream(type = MediaStreamType.Audio, index = 1, language = "en", codec = "ac3", channels = 2, isDefault = true),
            MediaStream(type = MediaStreamType.Audio, index = 2, language = "fr", codec = "ac3", channels = 2)
        )
        assertEquals(listOf("English · AC-3 stereo · Default", "French · AC-3 stereo"), trackLabels(streams))
    }

    @Test
    fun aLoneTrackNeedsNoDefaultBadgeOrNumber() {
        assertEquals(
            listOf("English · AC-3 stereo"),
            trackLabels(
                listOf(MediaStream(type = MediaStreamType.Audio, index = 1, language = "en", codec = "ac3", channels = 2, isDefault = true))
            )
        )
    }

    @Test
    fun aTitleRepeatingTheFormatIsNotDuplicated() {
        assertEquals(
            listOf("DTS 5.1 · Track 1"),
            trackLabels(listOf(MediaStream(type = MediaStreamType.Audio, index = 1, codec = "dts", channels = 6, title = "DTS 5.1")))
        )
    }
}
