/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Retired credentials survive a crash, but never revoke a device still used by a saved viewer. */
internal suspend fun retireDeviceCredentials(accounts: AccountStore, service: JellyfinService) = withContext(NonCancellable) {
    withTimeoutOrNull(2_000) {
        for (old in accounts.snapshots.value.retiredDeviceAccounts) {
            val shared = accounts.snapshots.value.accounts.any {
                it.accessToken.isNotBlank() &&
                    !it.expired &&
                    it.server.id == old.server.id &&
                    (it.deviceId == old.deviceId || (it.deviceId.isNullOrBlank() && old.deviceId.isNullOrBlank()))
            }
            if (shared) continue
            try {
                val result = service.logout(old)
                if (result is ClientResult.Success ||
                    result is ClientResult.Failure &&
                    result.error.kind == ErrorKind.INVALID_CREDENTIALS
                ) {
                    accounts.completeDeviceRetirement(old)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the queued credential for the next foreground launch.
            }
        }
    }
}

internal suspend fun rememberSignedIn(accounts: AccountStore, service: JellyfinService, account: Account) = withContext(NonCancellable) {
    accounts.remember(account)
    retireDeviceCredentials(accounts, service)
}

/** Make progress per viewer; a slow, offline or revoked account cannot undo a completed rotation. */
internal suspend fun separateLegacyDevices(
    accounts: AccountStore,
    service: JellyfinService,
    clock: () -> Long = System::currentTimeMillis
) {
    val started = clock()
    val snapshot = accounts.snapshots.value
    val legacy = snapshot.accounts.filter { it.deviceId.isNullOrBlank() && !it.expired && it.accessToken.isNotBlank() }
        .sortedBy { if (it.key == snapshot.currentKey) 0 else 1 }
    val deferred = mutableSetOf<String>()
    try {
        for (old in legacy) {
            if (clock() - started >= 3_000) break
            if (old.server.id in deferred || (snapshot.deviceUpgradeRetryAfter[old.server.id] ?: 0) > clock()) continue
            // The bounded protocol and local commit finish together. Once Authorize mints a token,
            // cancellation must not discard it between exchange and persistence/cleanup.
            withContext(NonCancellable) {
                var replacement: Account? = null
                var committed = false
                try {
                    when (val result = service.separateDevice(old)) {
                        is ClientResult.Success -> {
                            val value = result.value
                            if (!value.deviceId.isNullOrBlank() && value.key == old.key && value.accessToken != old.accessToken) {
                                replacement = value
                                committed = accounts.replaceCredentials(listOf(old), listOf(value))
                            } else if (value != old) {
                                deferred += old.server.id
                            }
                            // The interface's default no-op service does not support rotation.
                        }
                        is ClientResult.Failure -> {
                            if (result.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                                accounts.expire(old.key, old.accessToken)
                            } else {
                                deferred += old.server.id
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    deferred += old.server.id
                } finally {
                    if (!committed) {
                        replacement?.let { fresh ->
                            withTimeoutOrNull(2_000) { runCatching { service.logout(fresh) } }
                        }
                    }
                }
            }
        }
        for (serverId in deferred) {
            runCatching { accounts.deferDeviceUpgrade(serverId, clock() + 30 * 60 * 1_000L) }
        }
    } finally {
        retireDeviceCredentials(accounts, service)
    }
}
