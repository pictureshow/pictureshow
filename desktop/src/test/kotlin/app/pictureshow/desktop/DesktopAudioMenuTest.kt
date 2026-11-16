/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.StreamDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAudioMenuTest {
    @Test fun audioMenuKeepsCodecAndLayoutWithoutOff() {
        val streams = listOf(MediaStream(type = MediaStreamType.Audio, index = 2, language = "en", codec = "eac3", channels = 6))
        assertEquals(listOf(2 to "English · E-AC-3 5.1"), desktopAudioChoices(streams))
        assertEquals(emptyList(), desktopAudioChoices(emptyList()))
        assertEquals(2, StreamDefaults.choose(streams, MediaStreamType.Audio, -1, "en"))
        assertEquals(-1, StreamDefaults.choose(streams, MediaStreamType.Subtitle, -1, "en"))
    }
}
