/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflinePresetChoice
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phone review P-17: each download quality names its size, and an estimate says it is one. */
class OfflineChoiceSizeTest {
    @Test
    fun exactSizesReadPlainAndEstimatesSayAbout() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val gib = 1024L * 1024 * 1024
            assertEquals(
                "2.0 GB",
                offlineChoiceSize(OfflinePresetChoice(OfflinePreset.original, true, estimateBytes = 2 * gib, exactSize = true))
            )
            assertEquals(
                "About 700 MB",
                offlineChoiceSize(
                    OfflinePresetChoice(
                        OfflinePreset.video720,
                        true,
                        estimateBytes =
                        700L * 1024 * 1024
                    )
                )
            )
            assertNull(offlineChoiceSize(OfflinePresetChoice(OfflinePreset.original, true)))
            assertNull(offlineChoiceSize(OfflinePresetChoice(OfflinePreset.original, true, estimateBytes = 0)))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
