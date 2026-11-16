/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCardLinesTest {
    @Test
    fun theSeparateNextUpLineDescribesTheCurrentLayout() {
        assertEquals("Next up has its own row on Home.", separateNextUpSummary(true))
        assertEquals("Next episodes follow what you're watching in one Continue watching row on Home.", separateNextUpSummary(false))
    }
}
