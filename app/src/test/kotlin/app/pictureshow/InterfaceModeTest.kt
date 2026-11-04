/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class InterfaceModeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val mode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

    @Test
    fun televisionKeepsRemoteInterfaceEvenWhenItReportsTouchHardware() {
        shadowOf(mode).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
        assertTrue(usesTvInterface(context))
    }

    @Test
    fun televisionWithoutTouchKeepsRemoteInterface() {
        shadowOf(mode).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, false)
        assertTrue(usesTvInterface(context))
    }

    @Test
    fun phoneAndTabletUseTouchInterfaceIndependentlyOfCurrentInputHardware() {
        shadowOf(mode).setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        assertFalse(usesTvInterface(context))
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, false)
        assertFalse(usesTvInterface(context))
        assertFalse(isTvInterface(Configuration.UI_MODE_TYPE_UNDEFINED))
    }
}
