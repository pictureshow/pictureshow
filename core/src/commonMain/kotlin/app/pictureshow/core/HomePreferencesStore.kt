/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One serialized read/modify/write stream for this catalog session; tokens never enter the saved document. */
class HomePreferencesStore(private val service: CatalogService, private val accounts: AccountStore) {
    private val mutex = Mutex()
    data class Read(val preferences: HomePreferences, val status: String? = null)

    suspend fun read(account: Account): Read = mutex.withLock { readLocked(account) }

    private suspend fun readLocked(account: Account): Read {
        val snapshot = accounts.load()
        val local = snapshot.homePreferences[account.key] ?: HomePreferences()
        if (account.key in snapshot.pendingHomePreferences) return Read(local, "Saved on this device. Retry server save in Customize home.")
        return when (val result = service.displayPreferences(account)) {
            is ClientResult.Failure -> {
                expire(account, result)
                Read(local, "Using this device's layout. Server preferences could not be read.")
            }
            is ClientResult.Success -> {
                val preferences = readHomePreferences(result.value) ?: local
                if (snapshot.accounts.any { it.key == account.key }) {
                    try {
                        accounts.saveHomePreferences(account.key, preferences, pending = false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return Read(preferences, "Using the server layout. Could not save a copy on this device.")
                    }
                }
                Read(preferences)
            }
        }
    }

    suspend fun change(account: Account, transform: (HomePreferences) -> HomePreferences): Read = mutex.withLock {
        val current = readLocked(account).preferences
        val next = transform(current)
        try {
            // Local commit first: a failed remote write must not revert this viewer's last choice on next launch.
            accounts.saveHomePreferences(account.key, next, pending = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock Read(current, "Could not save the layout on this device. Try again.")
        }
        val document = service.displayPreferences(account)
        if (document is ClientResult.Success) {
            val saved = service.saveDisplayPreferences(account, homePreferencesDocument(document.value, next))
            if (saved is ClientResult.Success) {
                try {
                    accounts.saveHomePreferences(account.key, next, pending = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withLock Read(next, "Saved on the server and this device. Could not confirm sync locally; retry server save.")
                }
                return@withLock Read(next, "Saved for this viewer on the server.")
            }
            if (saved is ClientResult.Failure) expire(account, saved)
        } else if (document is ClientResult.Failure) {
            expire(account, document)
        }
        Read(next, "Saved on this device. Server save failed; choose Retry server save.")
    }

    private suspend fun expire(account: Account, failure: ClientResult.Failure) {
        if (failure.error.kind == ErrorKind.EXPIRED) accounts.expire(account.key, account.accessToken)
    }
}
