/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.MediaItem
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackStopInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.robolectric.RuntimeEnvironment

/** The app graph needs a target host. One that is never bound opens no socket and changes nothing a test observes. */
internal fun inertRemoteTarget() = RemoteTargetHost(
    CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    { _, _, _ -> error("An inert remote target never connects") },
    register = { _, _ -> 0 },
    authorization = { "" },
    nowMs = { 0L },
    trace = {}
)

/** The app composes the receive policy from preferences; with nothing stored it reads the defaults. */
internal fun inertPlaybackPreferences() = PlaybackPreferencesStore(
    object : DataStore<Preferences> {
        override val data = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
    }
)

/** The app's root binds a music host. With no queue and no server it shows nothing and starts no player. */
internal fun inertMusicHost(accounts: AccountStore) = RuntimeEnvironment.getApplication().let { context ->
    MusicHost(
        context,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        accounts,
        MusicStore(context),
        object : MusicGateway {
            override suspend fun negotiate(account: Account, media: String, ceiling: Int) =
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "An inert music host has no server"))

            override suspend fun start(account: Account, info: PlaybackProgressInfo) = Unit

            override suspend fun progress(account: Account, info: PlaybackProgressInfo) = Unit

            override suspend fun stopped(account: Account, info: PlaybackStopInfo) = Unit

            override suspend fun check(account: Account, media: List<String>) = ClientResult.Success(media)

            override suspend fun items(account: Account, media: List<String>) = ClientResult.Success(emptyList<MediaItem>())
        },
        preferences = { PlaybackPreferences() },
        remoteAttached = { null },
        remoteTarget = { null },
        startService = {},
        metered = { false }
    )
}
