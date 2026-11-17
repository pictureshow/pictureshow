/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PLAY-61: a passthrough format that stalled mpv is decoded from then on, in later app runs too, until the app's
 * version changes; the other formats still pass through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class MpvPassthroughStallsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun reset() = MpvPassthroughStalls.clear()

    @Test fun aStallOutlivesTheRun() {
        MpvPassthroughStalls.load(context, "1.0+1")
        MpvPassthroughStalls.stalled("eac3")
        MpvPassthroughStalls.clear() // the app process ends
        MpvPassthroughStalls.load(context, "1.0+1")
        assertEquals(setOf("eac3"), MpvPassthroughStalls.formats)
    }

    @Test fun aNewAppVersionForgetsTheStalls() {
        MpvPassthroughStalls.load(context, "1.0+1")
        MpvPassthroughStalls.stalled("eac3")
        MpvPassthroughStalls.clear()
        MpvPassthroughStalls.load(context, "1.1+2")
        assertEquals(emptySet(), MpvPassthroughStalls.formats)
        MpvPassthroughStalls.clear()
        MpvPassthroughStalls.load(context, "1.1+2")
        assertEquals(emptySet(), MpvPassthroughStalls.formats, "the old version's stalls are gone from the device too")
    }

    @Test fun onlyTheFormatThatStalledIsDecodedAfterwards() {
        MpvPassthroughStalls.stalled("eac3")
        assertEquals(listOf("ac3", "dts"), listOf("ac3", "eac3", "dts") - MpvPassthroughStalls.formats)
    }

    @Test fun dtsCoversDtsHd() {
        MpvPassthroughStalls.stalled("DTS")
        assertEquals(setOf("dts", "dts-hd"), MpvPassthroughStalls.formats)
    }

    @Test fun anUnknownCodecDropsNothing() {
        MpvPassthroughStalls.stalled(null)
        MpvPassthroughStalls.stalled("aac")
        assertEquals(emptySet(), MpvPassthroughStalls.formats)
    }
}
