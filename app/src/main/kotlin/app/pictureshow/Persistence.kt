package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pictureshow.core.TextPersistence
import kotlinx.coroutines.flow.first
import java.util.UUID

class AccountPersistence(private val preferences: DataStore<Preferences>) : TextPersistence {
    override suspend fun read(): String? = preferences.data.first()[ACCOUNTS]

    override suspend fun write(value: String) {
        preferences.edit { it[ACCOUNTS] = value }
    }

    private companion object {
        val ACCOUNTS = stringPreferencesKey("accounts_v1")
    }
}

/** Generation happens inside DataStore's serialized transaction, including concurrent first reads. */
class DeviceIdentityStore(private val preferences: DataStore<Preferences>) {
    suspend fun getOrCreate(): String {
        val saved = preferences.edit { values ->
            if (values[DEVICE_ID].isNullOrBlank()) values[DEVICE_ID] = UUID.randomUUID().toString()
        }
        return checkNotNull(saved[DEVICE_ID])
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
