/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult

/** Called after Stopped: that report may already have marked played and incremented the count. */
internal suspend fun CatalogSession.completePlayback(account: Account, itemId: String): ClientResult<Unit> {
    val owner = completionIntent(itemId)
    var acknowledged = false
    try {
        val item = when (val result = completionItem(account, itemId)) {
            is ClientResult.Failure -> return result
            is ClientResult.Success -> result.value
        }
        if (item.userData.played) {
            playbackCompleted(item)
            acknowledged = true
            return ClientResult.Success(Unit)
        }
        return when (val result = call(account) { played(account, itemId, true) }) {
            is ClientResult.Success -> {
                playbackCompleted(item.copy(userData = result.value))
                acknowledged = true
                ClientResult.Success(Unit)
            }
            is ClientResult.Failure -> result
        }
    } finally {
        // Failure/cancellation is not an acknowledgement; keep only a newer owner's intent.
        completionFinished(itemId, owner, acknowledged)
    }
}
