/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pictureshow.core.Account
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.seerr.SeerrClient
import app.pictureshow.core.seerr.SeerrConfig
import app.pictureshow.core.seerr.SeerrError
import app.pictureshow.core.seerr.SeerrFailure
import app.pictureshow.core.seerr.SeerrSecret
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Key material never leaves AndroidKeyStore; each ciphertext is authenticated to the Pictureshow account. */
internal class SeerrCipher(private val key: () -> SecretKey = ::seerrKey) {
    fun encrypt(account: String, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(account: String, ciphertext: String): String {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

@Synchronized
private fun seerrKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey("pictureshow.seerr.v1", null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(
            KeyGenParameterSpec.Builder("pictureshow.seerr.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
    }.generateKey()
}

@Serializable
internal data class SeerrSaved(val config: SeerrConfig, val encryptedSecret: String)
internal val SEERR_CONNECTIONS = stringPreferencesKey("seerr_connections_v1")
private val seerrJson = Json { ignoreUnknownKeys = true }

/** Called in the same DataStore transaction that signs out or forgets the Jellyfin account. */
internal fun retainSeerrAccounts(values: MutablePreferences, snapshot: AccountSnapshot) {
    val existing = values[SEERR_CONNECTIONS] ?: return
    val saved = seerrJson.decodeFromString<Map<String, SeerrSaved>>(existing)
    val retained = snapshot.accounts.filter { !it.signedOut }.map { it.key }.toSet()
    values[SEERR_CONNECTIONS] = seerrJson.encodeToString(saved.filterKeys { it in retained })
}

internal class SeerrStore(private val preferences: DataStore<Preferences>, private val cipher: SeerrCipher = SeerrCipher()) {
    private val http = HttpClient(OkHttp) {
        followRedirects = false
        engine { config { followRedirects(false); followSslRedirects(false) } }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
    }
    val connections = preferences.data.map { decode(it).mapValues { entry -> entry.value.config } }
    private fun decode(values: Preferences): Map<String, SeerrSaved> =
        values[SEERR_CONNECTIONS]?.let { seerrJson.decodeFromString(it) } ?: emptyMap()

    suspend fun connect(account: Account, url: String, apiKey: String?, username: String, password: String): SeerrConfig {
        val secret: SeerrSecret
        val config: SeerrConfig
        if (apiKey != null) {
            config = SeerrClient.apiKey(http, url, apiKey.trim(), account.user.id)
            secret = SeerrSecret(apiKey = apiKey.trim())
        } else {
            val login = SeerrClient.login(http, url, username.trim(), password)
            config = SeerrConfig(app.pictureshow.core.seerr.normalizeSeerrUrl(url), app.pictureshow.core.seerr.SeerrMode.JELLYFIN, login.first)
            secret = login.second
        }
        // Test the effective identity, including X-API-User, before publishing the connection.
        val verified = config.copy(user = SeerrClient(http, config, secret).me())
        val encrypted = cipher.encrypt(account.key, seerrJson.encodeToString(secret))
        preferences.edit { values ->
            val snapshot = values[stringPreferencesKey("accounts_v1")]?.let { seerrJson.decodeFromString<AccountSnapshot>(it) }
            if (snapshot != null && snapshot.accounts.none { it.key == account.key && !it.signedOut }) throw SeerrFailure(SeerrError.EXPIRED)
            values[SEERR_CONNECTIONS] = seerrJson.encodeToString(decode(values) + (account.key to SeerrSaved(verified, encrypted)))
        }
        return verified
    }

    suspend fun disconnect(key: String) {
        preferences.edit { values -> values[SEERR_CONNECTIONS] = seerrJson.encodeToString(decode(values) - key) }
    }

    fun client(key: String, saved: SeerrSaved): SeerrClient {
        val secret = try {
            seerrJson.decodeFromString<SeerrSecret>(cipher.decrypt(key, saved.encryptedSecret))
        } catch (_: Exception) {
            throw SeerrFailure(SeerrError.STORAGE)
        }
        var expected = saved.encryptedSecret
        return SeerrClient(http, saved.config, secret) { renewed ->
            val encrypted = cipher.encrypt(key, seerrJson.encodeToString(renewed))
            preferences.edit { values ->
                val current = decode(values)
                // A response from before Disconnect/sign-out cannot restore a connection.
                if (current[key]?.encryptedSecret == expected) {
                    values[SEERR_CONNECTIONS] = seerrJson.encodeToString(current + (key to saved.copy(encryptedSecret = encrypted)))
                    expected = encrypted
                }
            }
        }
    }

    suspend fun client(key: String): SeerrClient? {
        val values = preferences.data.first()
        return decode(values)[key]?.let { client(key, it) }
    }
}
