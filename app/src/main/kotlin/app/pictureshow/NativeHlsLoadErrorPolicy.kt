/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/** Keep retryable native HLS media failures inside PlaybackSession's buffering deadline.
 * Eight-second silent attempts fail near 8, 16, 25, 35 and 46 seconds with default backoff.
 * A minimum of four covers an aligned cold start, but prefetch can begin before buffering,
 * and faster I/O failures reach any small count sooner. Let the session own the time budget.
 * Default backoff, fallback/exclusion and non-retryable error decisions are inherited.
 */
internal class NativeHlsLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when (dataType) {
        C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> Int.MAX_VALUE
        else -> super.getMinimumLoadableRetryCount(dataType)
    }
}
