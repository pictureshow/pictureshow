/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.media.session.MediaSessionManager.RemoteUserInfo
import androidx.annotation.RequiresApi
import androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME

/** Called by the version-scoped Media3 bytecode guard, before its compat caller validation. */
internal object LegacyControllerIdentity {
    // Android 9–11 and some OEMs can deliver an anonymous media key. Media3 rejects the empty
    // package before the app callback runs (#3214). Use its existing unknown-controller identity,
    // preserving the real PID/UID rather than attributing this event to Pictureshow or a system app.
    @RequiresApi(28)
    object Api28 {
        @JvmStatic
        fun normalize(info: RemoteUserInfo): RemoteUserInfo = if (info.packageName.isNullOrEmpty()) {
            RemoteUserInfo(LEGACY_CONTROLLER_PACKAGE_NAME, info.pid, info.uid)
        } else {
            info
        }
    }

    @JvmStatic
    fun verified(platformVerified: Boolean, packageName: String): Boolean =
        platformVerified && packageName != LEGACY_CONTROLLER_PACKAGE_NAME
}
