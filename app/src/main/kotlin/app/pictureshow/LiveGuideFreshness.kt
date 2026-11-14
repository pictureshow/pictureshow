/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

/** One reference timestamp for the guide and every main Live TV destination. */
internal fun liveGuideFreshness(fetchedAt: Long?, offline: Boolean, time: LiveTime): String? = fetchedAt?.let {
    "${if (offline) "Offline · last loaded" else "Updated"} ${time.clock(it)}"
}
