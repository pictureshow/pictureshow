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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class LegacyControllerNullIdentityTest {
    @Test
    fun nullFrameworkPackageAlsoUsesUnknownIdentityAndPreservesSystemUid() {
        // Reflection supplies the null that the framework's Java constructor annotation disallows in Kotlin.
        val framework = RemoteUserInfo::class.java.getConstructor(String::class.java, Int::class.java, Int::class.java)
            .newInstance(null, 789, 1000)
        assertFailsWith<NullPointerException> { CompatInfo(framework) }
        val compatible = CompatInfo(LegacyControllerIdentity.Api28.normalize(framework))
        assertEquals(LEGACY_CONTROLLER_PACKAGE_NAME, compatible.packageName)
        assertEquals(789, compatible.pid)
        assertEquals(1000, compatible.uid)
    }
}
