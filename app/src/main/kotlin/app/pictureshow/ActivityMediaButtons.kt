/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.media3.session.MediaSession

/** Handle our foreground remote keys before the platform's delayed, legacy-state-based toggle. */
internal class ActivityMediaButtons(context: Context) : MediaSession.Callback {
    private val packageName = context.packageName
    private val uid = Process.myUid()

    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        val activity = controllerInfo.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION &&
            controllerInfo.packageName == packageName &&
            controllerInfo.uid == uid
        return if (activity) {
            // Resolve the current wrapper now. Its playWhenReady retains intent while a decoder
            // retires/reopens; BUFFERING alone does not mean that the viewer asked to pause.
            AnonymousMediaButtons.handle(session, intent, "activity")
        } else {
            AnonymousMediaButtons.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }
}
