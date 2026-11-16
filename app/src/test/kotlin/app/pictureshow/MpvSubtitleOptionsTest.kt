/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.SubtitleBackground
import app.pictureshow.core.playback.SubtitleSize
import app.pictureshow.core.playback.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLAY-60: SET-15's choices reach mpv's renderer; defaults leave mpv's look and authored ASS styling alone. */
class MpvSubtitleOptionsTest {
    @Test fun defaultsOnlyKeepAuthoredStyles() {
        assertEquals(mapOf("sub-ass-override" to "scale"), mpvSubtitleOptions(SubtitleAppearance()))
    }

    @Test fun viewerChoicesBecomeMpvOptions() {
        val options = mpvSubtitleOptions(
            SubtitleAppearance(
                SubtitleSize.Large,
                SubtitleBackground.Box,
                SubtitleStyle(
                    foreground = "Yellow",
                    textOpacity = 80,
                    edge = "Outline",
                    edgeColor = "Black",
                    backgroundColor = "Black",
                    backgroundOpacity = 60,
                    position = "Top",
                    bold = true,
                    overrideEmbedded = true
                )
            )
        )
        assertEquals("#CCFFFF00", options["sub-color"])
        assertEquals("3", options["sub-border-size"])
        assertEquals("#FF000000", options["sub-border-color"])
        assertEquals("background-box", options["sub-border-style"])
        assertEquals("#99000000", options["sub-back-color"])
        assertEquals("1.25", options["sub-scale"])
        assertEquals("top", options["sub-align-y"])
        assertEquals("yes", options["sub-bold"])
        assertEquals("force", options["sub-ass-override"])
    }
}
