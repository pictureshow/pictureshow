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
