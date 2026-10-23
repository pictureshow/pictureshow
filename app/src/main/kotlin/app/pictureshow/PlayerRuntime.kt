/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.displayRuntimeMillis

/** Keep the current position useful even when the declared total is untrustworthy. */
internal fun playerClock(positionMs: Long, durationMs: Long, paused: Boolean): String {
    val total = displayRuntimeMillis(durationMs)
    val time = playerTime(positionMs) + (total?.let { " / ${playerTime(it)}" } ?: "")
    return (if (paused) "Paused  ·  " else "") + time
}
