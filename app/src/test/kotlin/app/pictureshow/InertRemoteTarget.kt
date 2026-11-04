/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf

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
