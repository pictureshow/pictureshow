/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.livetv.LiveProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveGuideGeometryTest {
    @Test fun gapsStayEmptyAndOverlapsStayVisible() {
        val rows = listOf(
            LiveProgram("a", startDate = "1970-01-01T00:00:10Z", endDate = "1970-01-01T00:00:30Z"),
            LiveProgram("b", startDate = "1970-01-01T00:00:20Z", endDate = "1970-01-01T00:00:40Z"),
            LiveProgram("c", startDate = "1970-01-01T00:01:00Z", endDate = "1970-01-01T00:03:00Z")
        )
        val cells = liveGuideCells(rows + rows[0], 0, 100_000)
        assertEquals(3, cells.size)
        assertEquals(listOf(0, 1, 0), cells.map { it.lane })
        assertEquals(listOf(.1f, .2f, .6f), cells.map { it.startFraction })
        assertEquals(listOf(.2f, .2f, .4f), cells.map { it.widthFraction })
    }
}
