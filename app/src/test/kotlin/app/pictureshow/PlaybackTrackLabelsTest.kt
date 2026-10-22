package app.pictureshow

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlaybackJson
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTrackLabelsTest {
    @Test
    fun labelsDescribeLanguageCodecChannelsAndSubtitleFlagsFromServerJson() {
        val audio = MediaStream(type = MediaStreamType.Audio, index = 2, language = "eng", codec = "eac3", channels = 6, isDefault = true)
        assertEquals("English · E-AC-3 5.1 · Default", trackLabel(audio))
        val subtitle = PlaybackJson.decodeFromString<MediaStream>(
            """{"Type":"Subtitle","Index":4,"Language":"eng","Codec":"ass","IsForced":true,"IsHearingImpaired":true,"IsExternal":true}"""
        )
        assertEquals("English · ASS · Forced · SDH · External", trackLabel(subtitle))
    }
}
