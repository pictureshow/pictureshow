/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Optional external text retries for as long as it is selected, without holding A/V ready.
 * The caller cancels when deselected/released; retry count is not a playback failure budget.
 */
object ExternalSubtitleLoading {
    fun retryDelayMs(errorCount: Int): Long = (errorCount.toLong().coerceAtLeast(1) * 1_000).coerceAtMost(10_000)

    /** A full-file subtitle keeps authored time when server-seek video starts at local zero. */
    fun sourceOffsetMs(fullTimeline: Boolean, serverStartMs: Long): Long = if (fullTimeline) serverStartMs else 0
}
