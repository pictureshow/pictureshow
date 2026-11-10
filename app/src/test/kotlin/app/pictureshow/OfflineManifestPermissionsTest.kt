/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first download starts a foreground service: without this permission on a device, startForeground throws and the app dies. Robolectric
 * doesn't enforce it, so nothing else here would notice a merge that drops it. Another feature's library declares it too, and removing that
 * library's copy with tools:node="remove" removes this one with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineManifestPermissionsTest {
    @Test fun theMergedManifestKeepsWhatADownloadsForegroundServiceNeeds() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested, "FOREGROUND_SERVICE in $requested")
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in requested, "FOREGROUND_SERVICE_DATA_SYNC in $requested")
    }
}
