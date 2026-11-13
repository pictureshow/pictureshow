/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.app.Application
import app.pictureshow.core.playback.FrameRateDisplayMode
import app.pictureshow.core.playback.FrameRateMatch
import app.pictureshow.core.playback.HdrFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidFrameRateMatchTest {
    private val current = FrameRateDisplayMode(1, 1920, 1080, 60f)
    private fun mode(id: Int, refreshRate: Float) = current.copy(id = id, refreshRate = refreshRate)

    @Test fun integerMultiplesPreferNativeRateAndKeepResolutionWithoutThreeTwoPulldown() {
        val modes = listOf(
            current,
            mode(2, 119.88f),
            mode(3, 47.952f),
            mode(4, 23.976f),
            FrameRateDisplayMode(5, 3840, 2160, 24f)
        )
        assertEquals(4, FrameRateMatch.select(23.976f, current, modes)?.id)
        assertEquals(3, FrameRateMatch.select(24f, current, modes.filter { it.id != 4 })?.id)
        assertNull(FrameRateMatch.select(24f, current, listOf(current, modes.last())))
        assertEquals(6, FrameRateMatch.select(25f, current, listOf(current, mode(6, 50f)))?.id)
        assertNull(FrameRateMatch.select(Float.NaN, current, modes))
        assertNull(FrameRateMatch.select(-1f, current, modes))
    }

    @Test fun matchingRefreshMustNotDiscardKnownHdrSupport() {
        val hdr = current.copy(hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG))
        val modes =
            listOf(
                mode(2, 24f).copy(hdrFormats = setOf(HdrFormat.HDR10)),
                mode(3, 48f).copy(hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG))
            )
        assertEquals(3, FrameRateMatch.select(24f, hdr, modes)?.id)
        assertNull(FrameRateMatch.select(24f, hdr, modes.take(1)))
    }

    @Test fun actualWindowRequestRestoresEarlierPreferenceOnDisposalUnknownRateAndReplacement() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val window = controller.get().window
        window.attributes = window.attributes.apply { preferredDisplayModeId = 7 }
        try {
            val first = FrameRateWindowRequest(window)
            first.update(24f, current, listOf(mode(2, 24f)))
            assertEquals(2, window.attributes.preferredDisplayModeId)
            first.update(-1f, current, listOf(mode(2, 24f)))
            assertEquals(7, window.attributes.preferredDisplayModeId)
            first.update(24f, current, listOf(mode(2, 24f)))
            first.close()
            assertEquals(7, window.attributes.preferredDisplayModeId)
            val replacement = FrameRateWindowRequest(window)
            replacement.update(25f, current, listOf(mode(3, 50f)))
            assertEquals(3, window.attributes.preferredDisplayModeId)
            replacement.close()
            replacement.update(24f, current, listOf(mode(2, 24f)))
            assertEquals(7, window.attributes.preferredDisplayModeId)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
