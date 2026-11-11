package app.pictureshow

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlaybackJson
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTrackLabelsTest {
    @Test
    fun chooserNumbersOnlyUnknownOrIdenticalLabelsAndHidesUninformativeDefaults() {
        val stream = MediaStream(type = MediaStreamType.Audio, index = 12, language = "eng", codec = "aac", channels = 2)
        assertEquals(listOf("English · AAC stereo"), trackLabels(listOf(stream.copy(isDefault = true))))
        assertEquals(
            listOf("English · AAC stereo · Track 1", "English · AAC stereo · Track 2"),
            trackLabels(listOf(stream, stream.copy(index = 99)))
        )
        assertEquals(listOf("AAC stereo · Track 1"), trackLabels(listOf(stream.copy(language = null))))
    }

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

    @Test
    fun metadataMatchingKeepsUnicodeWordBoundariesAndLiteralFormatPunctuation() {
        val stream = MediaStream(type = MediaStreamType.Subtitle, index = 2, codec = "srt", isForced = true)
        val notBadges = listOf(
            "Unforced",
            "Forcedness",
            "Forced²",
            "ForcedⅧ",
            "Forced𐐀",
            "Non-Forced",
            "non‑forced"
        )
        notBadges.forEach { title ->
            assertEquals("$title · SRT · Forced", trackLabel(stream.copy(title = title)), title)
        }
        listOf("(Forced)", "Forced / SRT", "Forced\nSRT", "anon-Forced", "é́non-Forced", "non-Forced / Forced").forEach { title ->
            val format = if (title.contains("SRT")) "" else " · SRT"
            assertEquals(title + format, trackLabel(stream.copy(title = title)), title)
        }
        assertEquals(
            "E-AC-3 5.1 · Forced",
            trackLabel(
                MediaStream(type = MediaStreamType.Audio, index = 1, codec = "eac3", channels = 6, title = "E-AC-3 5.1", isForced = true)
            )
        )
    }
}
