/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult

/** Called after Stopped: that report may already have marked played and incremented the count. */
internal suspend fun CatalogSession.completePlayback(account: Account, itemId: String): ClientResult<Unit> {
    val item = when (val result = call(account) { item(account, itemId) }) {
        is ClientResult.Failure -> return result
        is ClientResult.Success -> result.value
    }
    if (item.userData.played) {
        playbackCompleted(item)
        return ClientResult.Success(Unit)
    }
    return when (val result = call(account) { played(account, itemId, true) }) {
        is ClientResult.Success -> {
            playbackCompleted(item.copy(userData = result.value))
            ClientResult.Success(Unit)
        }
        is ClientResult.Failure -> result
    }
}
