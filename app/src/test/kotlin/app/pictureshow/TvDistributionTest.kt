/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class TvDistributionTest {
    @Test
    fun installedManifestRequiresTvAndKeepsTouchscreenOptional() {
        val app = RuntimeEnvironment.getApplication()
        val features = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        val tv = assertNotNull(features.singleOrNull { it.name == PackageManager.FEATURE_LEANBACK })
        val touch = assertNotNull(features.singleOrNull { it.name == PackageManager.FEATURE_TOUCHSCREEN })
        assertEquals(android.content.pm.FeatureInfo.FLAG_REQUIRED, tv.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
        assertEquals(0, touch.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
    }
}
