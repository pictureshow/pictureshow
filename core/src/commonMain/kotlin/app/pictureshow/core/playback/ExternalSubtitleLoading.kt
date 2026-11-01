/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Optional external text retries for as long as it is selected, without holding A/V ready.
 * The caller cancels when deselected/released; retry count is not a playback failure budget.
 */
object ExternalSubtitleLoading {
    fun retryDelayMs(errorCount: Int): Long = (errorCount.toLong().coerceAtLeast(1) * 1_000).coerceAtMost(10_000)

    /** Attempt local selection before server negotiation; output still verifies its prepared groups. */
    fun canSelectLocally(transcoding: Boolean, subtitle: Boolean, currentLocal: Boolean, nextLocal: Boolean): Boolean =
        !transcoding || (subtitle && currentLocal && nextLocal)

    /** A full-file subtitle keeps authored time when server-seek video starts at local zero. */
    fun sourceOffsetMs(fullTimeline: Boolean, serverStartMs: Long): Long = if (fullTimeline) serverStartMs else 0
}
