/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopSmokeTimingTest {
    private fun cue(start: String = "00:00:00,000", end: String = "00:03:00,000") =
        "1\n$start --> $end\nPictureshow original CC0 subtitle\n"

    @Test fun nativeClockUsesActualDeliveredZeroOrAudioPrimingOffset() {
        assertEquals(0.0 to 180.0, fixtureDeliveredCue(cue(), 0.0, 180.0))
        assertEquals(.021 to 180.021, fixtureDeliveredCue(cue("00:00:00,021", "00:03:00,021"), 0.0, 180.0))
    }

    @Test fun alteredTextMultipleCuesInvalidTimeAndLargeRetimingCannotPass() {
        for (text in listOf(cue().replace("CC0", "other"), cue() + "\n" + cue(),
            cue("00:00:99,000"), cue("00:00:00,100", "00:03:00,100"),
            cue(end = "00:02:59,000"))) {
            assertFailsWith<IllegalArgumentException> { fixtureDeliveredCue(text, 0.0, 180.0) }
        }
    }
}
