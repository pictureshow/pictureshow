/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Play filters a listing by the merged manifest's required features. One APK serves TVs, phones and tablets, so none may be
 * required: a library adding one, or leanback flipping back, would silently hide the app from a whole device class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlayDistributionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun installedManifestRequiresNoFeatureSoTvPhonesAndTabletsAllQualify() {
        val features = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        val tv = assertNotNull(features.singleOrNull { it.name == PackageManager.FEATURE_LEANBACK })
        val touch = assertNotNull(features.singleOrNull { it.name == PackageManager.FEATURE_TOUCHSCREEN })
        assertEquals(0, tv.flags and FeatureInfo.FLAG_REQUIRED)
        assertEquals(0, touch.flags and FeatureInfo.FLAG_REQUIRED)
        val required = features.filter { it.name != null && it.flags and FeatureInfo.FLAG_REQUIRED != 0 }.map { it.name }
        assertEquals(emptyList(), required)
    }

    @Test
    fun theLauncherActivityOpensFromBothTheTvAndThePhoneLauncher() {
        val tv = app.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(app.packageName),
            0
        )
        val phone = app.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(app.packageName),
            0
        )
        assertTrue(tv.any { it.activityInfo.name.endsWith(".MainActivity") })
        assertTrue(phone.any { it.activityInfo.name.endsWith(".MainActivity") })
    }

    @Test
    fun thePlayerActivitySupportsPictureInPictureWithoutRestarting() {
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0)
        // FLAG_SUPPORTS_PICTURE_IN_PICTURE is hidden API; its value is 0x400000.
        assertTrue(info.flags and 0x400000 != 0, "supportsPictureInPicture")
        val needed = ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_ORIENTATION
        assertEquals(needed, info.configChanges and needed)
    }
}
