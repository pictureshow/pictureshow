/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** An explicit, already-validated entry position wins; Play from start always supplies zero. */
fun resumeStartPositionMs(ticks: Long, resume: Boolean = true, explicitPositionMs: Long? = null): Long =
    explicitPositionMs ?: if (resume) ticks.coerceAtLeast(0) / 10_000 else 0
