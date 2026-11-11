/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSmokeTimingTest {
    @Test fun tenSecondSeekRejectsOvershootUndershootAndInvalidPositions() {
        assertTrue(fixtureSeekLanded(3.0,13.0))
        assertTrue(fixtureSeekLanded(3.0,12.5))
        assertTrue(fixtureSeekLanded(3.0,13.5))
        for(after in listOf(3.0,8.0,12.49,13.51,23.0,Double.NaN,Double.POSITIVE_INFINITY)) assertFalse(fixtureSeekLanded(3.0,after))
        assertFalse(fixtureSeekLanded(Double.NaN,13.0))
    }

    @Test fun blankPixelsCannotBorrowAnOsMenuBarAsAppPaintEvidence() {
        val frame=java.awt.image.BufferedImage(640,400,java.awt.image.BufferedImage.TYPE_INT_RGB)
        frame.createGraphics().let { g -> g.color=java.awt.Color.WHITE;g.fillRect(0,0,640,30);g.dispose() }
        val blank=fixtureCaptureContent(frame)
        assertEquals(0.0,blank.first);assertEquals(0.0,blank.second)
        frame.setRGB(40,100,java.awt.Color.WHITE.rgb)
        assertTrue(fixtureCaptureContent(frame).second<.002)
        frame.createGraphics().let { g -> g.color=java.awt.Color.WHITE;g.fillRect(40,100,200,100);g.dispose() }
        val painted=fixtureCaptureContent(frame)
        assertTrue(painted.first>=4.0&&painted.second>=.002)
    }
    private fun cue(start: String = "00:00:00,000", end: String = "00:03:00,000") =
        "1\n$start --> $end\nPictureshow original CC0 subtitle\n"

    @Test fun deliveryByteLimitIsAppliedBeforeDecodingOrReadingWholeBody() {
        assertEquals(cue(), fixtureSubtitleText(java.io.ByteArrayInputStream(cue().toByteArray())))
        val oversized = java.io.ByteArrayInputStream(ByteArray(10_000))
        assertFailsWith<IllegalArgumentException> { fixtureSubtitleText(oversized) }
        assertEquals(10_000 - 1025, oversized.available())
    }

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
