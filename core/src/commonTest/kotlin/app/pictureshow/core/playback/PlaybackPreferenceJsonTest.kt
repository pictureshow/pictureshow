/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackPreferenceJsonTest {
    @Test fun malformedFieldsDoNotEraseIndependentAcceptedChoices() {
        val tuning = PlaybackPreferenceJson.tuning(
            """{"remoteBitrateMbps":10,"maxAudioChannels":"many","rewindSeconds":15,"subtitleBurnIn":"Future"}"""
        )
        assertEquals(10, tuning.remoteBitrateMbps)
        assertEquals(15, tuning.rewindSeconds)
        assertEquals(null, tuning.maxAudioChannels)
        assertEquals(SubtitleBurnIn.Automatic, tuning.subtitleBurnIn)
        val style = PlaybackPreferenceJson.style("""{"foreground":"Cyan","imageOpacity":"bad","position":"Top"}""")
        assertEquals("Cyan", style.foreground)
        assertEquals("Top", style.position)
        assertEquals(100, style.imageOpacity)
        assertEquals(PlaybackTuning(), PlaybackPreferenceJson.tuning("not json"))
    }
}
