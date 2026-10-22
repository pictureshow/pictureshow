/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.os.Bundle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Device-test-only: adb shell am start -n app.pictureshow/.DeviceProfileExportActivity */
class DeviceProfileExportActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val preferences: PlaybackPreferencesStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                val report = captureThisTv(this@DeviceProfileExportActivity, preferences.read())
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val directory = requireNotNull(getExternalFilesDir(null))
                    File(directory, "device-profile.json").writeText(report.profileJson)
                }
                android.util.Log.i("PictureshowProfile", "Wrote device-profile.json to app-specific external files")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.util.Log.e("PictureshowProfile", "Profile export failed; retry the export activity")
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
