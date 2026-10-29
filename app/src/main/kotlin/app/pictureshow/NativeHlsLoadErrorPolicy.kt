/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/** Preserve the app's 45-second cold-start window with eight-second idle attempts.
 * With Media3's default retry delays (0, 1, 2, 3, 4 seconds), consecutive idle errors
 * arrive near 8, 16, 25, 35 and 46 seconds. Four permits the fifth attempt, including
 * a first byte at 40 seconds. Keep a finite bound for paused/buffered players whose
 * session buffering timer is inactive. All other policy decisions remain inherited.
 */
internal class NativeHlsLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when (dataType) {
        C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> 4
        else -> super.getMinimumLoadableRetryCount(dataType)
    }
}
