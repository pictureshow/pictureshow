/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ProfileLock
import app.pictureshow.core.ProfilePinResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** A check that threw reads from the counted state, never as a fresh pad. */
class PinFailedCheckTest {
    @Test
    fun aThrownCheckKeepsTheWaitOrLockout() {
        assertEquals(ProfilePinResult.LOCKED_OUT, pinResultAfterFailedCheck(ProfileLock("h", failures = 10, failedAt = 5), 6))
        assertEquals(ProfilePinResult.WAIT, pinResultAfterFailedCheck(ProfileLock("h", failures = 5, failedAt = 1_000), 2_000))
        assertEquals(ProfilePinResult.WRONG, pinResultAfterFailedCheck(ProfileLock("h", failures = 2, failedAt = 1_000), 2_000))
        assertEquals(ProfilePinResult.WRONG, pinResultAfterFailedCheck(null, 0))
    }
}
