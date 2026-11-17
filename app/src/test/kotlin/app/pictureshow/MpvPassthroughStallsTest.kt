/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLAY-61: a passthrough format that stalled mpv is decoded for the rest of the run; the others still pass through. */
class MpvPassthroughStallsTest {
    @AfterTest fun reset() = MpvPassthroughStalls.clear()

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
