/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.text.NumberFormat
import java.util.Locale

internal fun communityRatingLabel(rating: Double, locale: Locale = Locale.getDefault()): String {
    val formatter = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }
    return "★ ${formatter.format(rating)}"
}
