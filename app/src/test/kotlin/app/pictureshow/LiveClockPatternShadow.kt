/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.text.format.DateFormat
import java.util.Locale
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Robolectric's ShadowICU returns unknown skeletons verbatim. Use its real ICU dependency for clock-pattern tests. */
@Implements(DateFormat::class)
class LiveClockPatternShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun getBestDateTimePattern(locale: Locale, skeleton: String): String =
            com.ibm.icu.text.DateTimePatternGenerator.getInstance(locale).getBestPattern(skeleton)
    }
}
