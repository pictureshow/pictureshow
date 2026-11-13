/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core
import app.pictureshow.core.sync.SyncQuality
import app.pictureshow.core.sync.syncChipShowsQuality
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class SyncChipQualityRuleTest {
    @Test fun chipsOmitUnknownButRetainEveryActualEstimate() {
        assertFalse(syncChipShowsQuality(SyncQuality.UNKNOWN))
        for (quality in listOf(SyncQuality.IN_SYNC, SyncQuality.CATCHING_UP, SyncQuality.OUT_OF_SYNC)) {
            assertTrue(syncChipShowsQuality(quality), quality.name)
        }
    }
}
