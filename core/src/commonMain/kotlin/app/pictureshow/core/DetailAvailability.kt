/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** Empty is knowledge from a complete detail read, including its dependent shelves. */
fun showDetailEmptyState(
    itemType: String?,
    loading: Boolean,
    readSucceeded: Boolean,
    readFailed: Boolean,
    episodeCount: Int,
    seasonCount: Int
): Boolean = itemType in listOf("Series", "Season") &&
    !loading &&
    readSucceeded &&
    !readFailed &&
    episodeCount == 0 &&
    seasonCount == 0
