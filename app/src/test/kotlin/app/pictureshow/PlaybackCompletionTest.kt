/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlaybackCompletionTest {
    private val account = Account(Server("server", "", "https://example.test", "12.0.0"), User("viewer", "Viewer"), "token")
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) = Unit
    })

    @Test
    fun completionReadsPostStopUserDataAndDoesNotCountAnAlreadyPlayedItemTwice() = runTest {
        val service = CatalogModelsTest.FakeCatalog()
        service.detail = ClientResult.Success(MediaItem("item", userData = ItemUserData(played = true)))
        assertTrue(CatalogSession(service, accounts).completePlayback(account, "item") is ClientResult.Success)
        assertTrue(service.actions.isEmpty())
    }

    @Test
    fun retryAfterLostMutationResponseReadsAgainInsteadOfCountingAnotherWatch() = runTest {
        val service = CatalogModelsTest.FakeCatalog()
        service.detail = ClientResult.Success(MediaItem("item"))
        service.actionBlock = {
            service.detail = ClientResult.Success(MediaItem("item", userData = ItemUserData(played = true)))
            ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Response lost"))
        }
        val session = CatalogSession(service, accounts)
        assertTrue(session.completePlayback(account, "item") is ClientResult.Failure)
        assertTrue(session.completePlayback(account, "item") is ClientResult.Success)
        assertEquals(listOf(Triple("played", "item", true)), service.actions)
    }

    @Test
    fun failedUserDataReadDoesNotBlindlyIncrementThePlayCount() = runTest {
        val service = CatalogModelsTest.FakeCatalog()
        service.detail = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Offline"))
        assertTrue(CatalogSession(service, accounts).completePlayback(account, "item") is ClientResult.Failure)
        assertTrue(service.actions.isEmpty())
    }
}
