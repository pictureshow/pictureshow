/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.module.Module

/** No account is connected to Seerr, so Requests stays hidden; the store's secrets never reach a real key store. */
internal fun Module.seerrFixture() {
    single { SeerrStore(MemoryPreferences(), SeerrCipher { error("No Seerr secret is stored in UI graphs") }) }
}

internal class MemoryPreferences : DataStore<Preferences> {
    private val lock = Mutex()
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
        transform(data.value).also { data.value = it }
    }
}
