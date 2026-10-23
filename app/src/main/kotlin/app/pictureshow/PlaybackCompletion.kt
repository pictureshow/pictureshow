/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult

/** Called after Stopped: that report may already have marked played and incremented the count. */
internal suspend fun CatalogSession.completePlayback(account: Account, itemId: String): ClientResult<Unit> {
    when (val item = call(account) { item(account, itemId) }) {
        is ClientResult.Failure -> return item
        is ClientResult.Success -> if (item.value.userData.played) return ClientResult.Success(Unit)
    }
    return when (val result = call(account) { played(account, itemId, true) }) {
        is ClientResult.Success -> ClientResult.Success(Unit)
        is ClientResult.Failure -> result
    }
}
