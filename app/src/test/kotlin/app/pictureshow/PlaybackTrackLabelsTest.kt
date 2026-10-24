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

    @Test
    fun tracksWithoutLanguageUseTheServerTitleOrCodecAndNeverClaimUnknownLanguage() {
        val titled = MediaStream(type = MediaStreamType.Audio, index = 1, codec = "ac3", channels = 2, title = "Director's commentary")
        assertEquals("Director's commentary · AC-3 stereo", trackLabel(titled))
        val bare = MediaStream(type = MediaStreamType.Audio, index = 2, codec = "truehd", channels = 6, isDefault = true)
        assertEquals("TrueHD 5.1 · Default", trackLabel(bare))
        val subtitle = MediaStream(type = MediaStreamType.Subtitle, index = 3, codec = "subrip", isExternal = true)
        assertEquals("SRT · External", trackLabel(subtitle))
        val twoLetter = MediaStream(type = MediaStreamType.Audio, index = 4, language = "fr", codec = "ac3", channels = 2)
        assertEquals("French · AC-3 stereo", trackLabel(twoLetter))
    }

    @Test
    fun negatedHyphenatedTitleCannotHideForcedMetadata() {
        val subtitle = MediaStream(type = MediaStreamType.Subtitle, index = 2, codec = "srt", isForced = true)
        listOf("Non-Forced", "non-forced", "Non‑Forced", "Non–Forced").forEach { title ->
            assertEquals("$title · SRT · Forced", trackLabel(subtitle.copy(title = title)))
        }
        listOf("English - Forced", "English-Forced").forEach { title ->
            assertEquals("$title · SRT", trackLabel(subtitle.copy(language = "eng", title = title)))
        }
    }
}
