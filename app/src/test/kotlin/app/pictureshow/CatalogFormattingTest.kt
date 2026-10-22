/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test

class CatalogFormattingTest {
    @Test
    fun ratingRoundsToExactlyOneDecimalUsingTheRequestedLocale() {
        assertEquals("★ 6.9", communityRatingLabel(6.894, Locale.US))
        assertEquals("★ 6,9", communityRatingLabel(6.894, Locale.GERMANY))
        assertEquals("★ 7.0", communityRatingLabel(7.0, Locale.US))
        assertEquals("★ 7,0", communityRatingLabel(6.99, Locale.FRANCE))
    }

    @Test
    fun itemCountUsesTheSingularForOneItem() {
        assertEquals("1 item", itemCountLabel(1))
        assertEquals("0 items", itemCountLabel(0))
        assertEquals("9 items", itemCountLabel(9))
    }
}
