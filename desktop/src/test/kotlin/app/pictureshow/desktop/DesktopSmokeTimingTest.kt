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
    @Test fun wrappedEdtFailureReportsCauseAndLocationWithoutPrivateMessages() {
        val original = IllegalStateException("password=private https://fixture.invalid /Users/private/file")
        original.stackTrace = arrayOf(StackTraceElement("app.pictureshow.desktop.DesktopSmokeKt", "screenshot", "DesktopSmoke.kt", 123))
        val wrapped = java.lang.reflect.InvocationTargetException(original)
        val diagnostics = fixtureFailureDiagnostics(wrapped).toString()
        assertTrue(diagnostics.contains("java.lang.reflect.InvocationTargetException"))
        assertTrue(diagnostics.contains("java.lang.IllegalStateException"))
        assertTrue(diagnostics.contains("screenshot")); assertTrue(diagnostics.contains("123"))
        for (privateValue in listOf("password", "private", "fixture.invalid", "/Users")) assertFalse(diagnostics.contains(privateValue))
        // Throwable permits arbitrary cause implementations; cycles stay bounded.
        val cycle = object : Throwable() { override val cause: Throwable get() = this }
        assertEquals(1, fixtureFailureDiagnostics(cycle)["causes"]!!.let { it as kotlinx.serialization.json.JsonArray }.size)
    }

    @Test fun nativeFullscreenProbeUsesWin32PointRectAndMonitorInfoLayouts() {
        assertEquals(8,FixtureScreenPoint().size())
        assertEquals(16,FixtureNativeRect().size())
        val monitor=FixtureNativeMonitorInfo()
        assertEquals(40,monitor.size());assertEquals(40,monitor.cbSize)
    }

    @Test fun taskbarRequiresRealFullFrameWithoutShrinkingRequiredCapture() {
        val display=java.awt.Rectangle(0,0,1920,1080)
        val usable=java.awt.Rectangle(0,0,1920,1032)
        assertTrue(fixtureCaptureNeedsFullscreen(display,usable,1920,1080,true))
        assertFalse(fixtureCaptureNeedsFullscreen(display,usable,1280,800,true))
        assertFalse(fixtureCaptureNeedsFullscreen(display,display,1920,1080,true))
        assertFailsWith<IllegalArgumentException> { fixtureCaptureNeedsFullscreen(display,usable,1920,1080,false) }
        assertFailsWith<IllegalArgumentException> { fixtureCaptureNeedsFullscreen(java.awt.Rectangle(0,0,1920,1032),usable,1920,1080,true) }
        assertFailsWith<IllegalArgumentException> { fixtureCaptureNeedsFullscreen(display,usable,3840,2160,true) }
    }

    @Test fun fullscreenRestoresGeometryTopmostAndPlacementOnCaptureAndEntryFailure() {
        val initial=FixtureWindowPresentation(java.awt.Rectangle(100,60,1280,800),false,androidx.compose.ui.window.WindowPlacement.Maximized)
        var actual=initial
        var failEntry=false
        val presentation=FixtureFullscreenPresentation({ actual },{ value ->
            actual=value
            if(failEntry && value.topmost) error("entry failed after native change")
        })
        assertFailsWith<IllegalStateException> {
            try {
                presentation.enter(java.awt.Rectangle(0,0,1920,1080))
                assertEquals(java.awt.Rectangle(0,0,1920,1080),actual.bounds)
                assertTrue(actual.topmost)
                assertEquals(androidx.compose.ui.window.WindowPlacement.Floating,actual.placement)
                // Reentry must retain the original restoration target.
                presentation.enter(java.awt.Rectangle(0,0,1920,1080))
                error("capture failed")
            } finally { presentation.restore() }
        }
        assertEquals(initial,actual)
        failEntry=true
        assertFailsWith<IllegalStateException> {
            try { presentation.enter(java.awt.Rectangle(0,0,1920,1080)) }
            finally { presentation.restore() }
        }
        assertEquals(initial,actual)
        presentation.restore()
        assertEquals(initial,actual)
        actual=initial.copy(topmost=true)
        failEntry=false
        presentation.enter(java.awt.Rectangle(0,0,1920,1080));presentation.restore()
        assertTrue(actual.topmost)
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
