/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:android.annotation.SuppressLint("RestrictedApi")

package app.pictureshow

import android.media.session.MediaSessionManager.RemoteUserInfo
import androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo as CompatInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacyControllerIdentityTest {
    @Test
    fun anonymousFrameworkCallerSurvivesRealCompatValidationWithoutChangingPidOrUid() {
        val anonymous = RemoteUserInfo("", 123, 2000)
        // This is the real Media3 constructor in the crashing callback, not a mock exception.
        assertFailsWith<IllegalArgumentException> { CompatInfo(anonymous) }
        val compatible = CompatInfo(LegacyControllerIdentity.Api28.normalize(anonymous))
        assertEquals(LEGACY_CONTROLLER_PACKAGE_NAME, compatible.packageName)
        assertEquals(123, compatible.pid)
        assertEquals(2000, compatible.uid)
        assertFalse(LegacyControllerIdentity.verified(true, compatible.packageName))
    }

    @Test
    fun namedFrameworkCallerRetainsExactIdentityAndVerification() {
        val named = RemoteUserInfo("example.remote", 456, 10001)
        assertSame(named, LegacyControllerIdentity.Api28.normalize(named))
        val compatible = CompatInfo(LegacyControllerIdentity.Api28.normalize(named))
        assertEquals("example.remote", compatible.packageName)
        assertEquals(456, compatible.pid)
        assertEquals(10001, compatible.uid)
        assertTrue(LegacyControllerIdentity.verified(true, compatible.packageName))
        assertFalse(LegacyControllerIdentity.verified(false, compatible.packageName))
    }
}
