/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.tvprovider.media.tv.TvContractCompat
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.getKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PictureshowApplication::class)
class FireDistributionTest {
    private val app = ApplicationProvider.getApplicationContext<PictureshowApplication>()

    @AfterTest fun tearDown() = stopKoin()

    @Test
    fun fireBuildNeverConstructsPlayBillingAndDoesNotShipEitherGoogleSdk() {
        assertNull(app.supporter)
        listOf("com.android.billingclient.api.BillingClient", "com.google.android.gms.cast.framework.CastContext").forEach {
            assertFailsWith<ClassNotFoundException> { Class.forName(it) }
        }
    }

    @Test
    fun launcherStartsTheTvInterfaceAndCastStaysHiddenEvenWithAnUnexpectedUiMode() {
        shadowOf(app.getSystemService(UiModeManager::class.java)).setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
        assertTrue(usesTvInterface(app))
        val previous = CastRuntime.linkBuilds
        val sender = getKoin().get<CastSenderHost>()
        sender.refreshCapability()
        assertFalse(sender.state.value.capability.visible)
        assertFalse(sender.state.value.capability.canDiscover)
        assertTrue(sender.state.value.capability.jellyfinRemote)
        assertEquals(previous, CastRuntime.linkBuilds)
    }

    @Test
    fun fireDoesNotPublishWatchNextEvenIfTheDeviceAdvertisesTheAndroidProvider() {
        val pm = app.packageManager
        shadowOf(pm).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val provider = ProviderInfo().apply {
            authority = TvContractCompat.AUTHORITY
            name = "TestTvProvider"
            packageName = app.packageName
            applicationInfo = app.applicationInfo
        }
        shadowOf(pm).addOrUpdateProvider(provider)
        assertNotNull(pm.resolveContentProvider(TvContractCompat.AUTHORITY, 0))
        assertFalse(supportsWatchNext(app))
    }

    @Test
    fun manifestExposesBothLaunchersWithoutRequiringTouchOrPlayServices() {
        val pm = app.packageManager
        val packageName = app.packageName
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName)
            val activity = pm.queryIntentActivities(intent, 0).single().activityInfo
            assertEquals(MainActivity::class.java.name, activity.name)
            assertTrue(activity.exported)
        }
        val flags = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_PERMISSIONS
        val info = pm.getPackageInfo(packageName, flags)
        val touch = info.reqFeatures.orEmpty().single { it.name == PackageManager.FEATURE_TOUCHSCREEN }
        assertEquals(0, touch.flags and FeatureInfo.FLAG_REQUIRED)
        assertEquals(28, app.applicationInfo.minSdkVersion)
        assertEquals(36, app.applicationInfo.targetSdkVersion)
        assertTrue(app.applicationInfo.banner != 0 && app.applicationInfo.icon != 0)
        assertFalse(info.requestedPermissions.orEmpty().any { it == "com.android.vending.BILLING" || it.endsWith("WRITE_EPG_DATA") })
        val metadata = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
        assertNull(metadata?.getString("com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"))
    }
}
