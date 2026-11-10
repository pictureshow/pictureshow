/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module

/**
 * Minimal UI graphs still mount the real coordinator and renderer bindings. This
 * server denies group access; binding a viewer opens no group, clock or HTTP work.
 * Group scenarios provide their own gateway instead of silently succeeding here.
 */
internal fun Module.syncPlayFixture() {
    single {
        SyncPlayHost(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            object : SyncPlayGateway {
                override suspend fun groups(account: Account) = ClientResult.Success(emptyList<SyncGroup>())

                override suspend fun access(account: Account) = ClientResult.Success("None")

                override suspend fun time(account: Account): ClientResult<SyncUtcTime> =
                    error("Group clock calibration requires a scenario gateway")

                override suspend fun send(account: Account, request: SyncRequest): Int = error("Group requests require a scenario gateway")
            },
            wall = { 0L },
            monotonic = { 0L }
        )
    }
}
