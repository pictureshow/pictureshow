/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.control.TemporaryEvent
import app.pictureshow.core.control.TemporarySession
import app.pictureshow.core.control.TemporaryState
import app.pictureshow.core.control.reduceTemporary
import app.pictureshow.core.offline.offlineDownloadSetDevice
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OfflineSessionsFile(
    val state: TemporaryState = TemporaryState(),
    val tokens: Map<String, String> = emptyMap(),
    val servers: Map<String, String> = emptyMap()
)

internal sealed interface OfflineSetToken {
    data class Ready(val deviceId: String, val token: String) : OfflineSetToken

    /** Quick Connect is off on this server; only the viewer's password can issue a separate token. */
    data object NeedsPassword : OfflineSetToken

    data class Unavailable(val reason: String) : OfflineSetToken
}

/** The download set's own device and token (CTRL-11): the interactive sign-in is never written into a job. */
internal class OfflineSessionOwner(private val file: File, private val api: OfflineApi, private val installationDeviceId: suspend () -> String) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): OfflineSessionsFile =
        runCatching { json.decodeFromString<OfflineSessionsFile>(file.readText()) }.getOrDefault(OfflineSessionsFile())

    private fun save(value: OfflineSessionsFile) = file.writeAtomically(json.encodeToString(OfflineSessionsFile.serializer(), value))

    private suspend fun binding(account: Account, generation: Long) = TemporarySession(
        account.server.id,
        account.user.id,
        offlineDownloadSetDevice(installationDeviceId(), account.server.id, account.user.id),
        SENDER,
        generation
    )

    /** The live token, provisioning one if needed. A leftover record is revoked first, so a set never has two owners. */
    suspend fun token(account: Account, password: String? = null): OfflineSetToken = mutex.withLock {
        val wanted = binding(account, 0)
        var saved = load()
        val existing = saved.state.records.firstOrNull { it.server == wanted.server && it.viewer == wanted.viewer && it.device == wanted.device }
        if (existing?.phase == "active") {
            val token = saved.tokens[existing.secretRef]
            if (token != null) return OfflineSetToken.Ready(existing.device, token)
        }
        if (existing != null) {
            settle(existing)
            saved = load()
            if (saved.state.records.any { it.device == wanted.device }) return OfflineSetToken.Unavailable("revocation-pending")
        }
        val generation = (existing?.generation ?: -1) + 1
        val binding = wanted.copy(generation = generation)
        val creating = reduceTemporary(saved.state, TemporaryEvent("create", binding))
        if (creating.effect != "provision") return OfflineSetToken.Unavailable(creating.reason ?: "invalid")
        // Written before the server call: a crash after the issue still leaves a record that gets settled.
        saved = saved.copy(state = creating, servers = saved.servers + (account.server.id to account.server.url))
        save(saved)
        val issued = runCatching {
            when {
                password != null -> api.issueByPassword(account, binding.device, password)
                api.quickConnectEnabled(account.server.url) -> api.issueByQuickConnect(account, binding.device)
                else -> null
            }
        }
        val pair = issued.getOrElse { error ->
            save(saved.copy(state = saved.state.copy(records = saved.state.records.filterNot { it.device == binding.device })))
            return OfflineSetToken.Unavailable(if (error is OfflineApiException && error.status in 401..403) "denied" else "network")
        }
        if (pair == null) {
            save(saved.copy(state = saved.state.copy(records = saved.state.records.filterNot { it.device == binding.device })))
            return OfflineSetToken.NeedsPassword
        }
        val (token, viewer) = pair
        val secretRef = "dl:${binding.device}"
        val next = reduceTemporary(saved.state, TemporaryEvent("issued", binding, normalise(viewer, account.user.id), secretRef))
        saved = saved.copy(state = next, tokens = saved.tokens + (secretRef to token))
        save(saved)
        if (next.effect != "use") {
            // Someone else's token came back: it's revoked, never used.
            finishRevocation(binding)
            return OfflineSetToken.Unavailable("wrong-viewer")
        }
        OfflineSetToken.Ready(binding.device, token)
    }

    private fun normalise(returned: String, expected: String) =
        if (returned.replace("-", "").equals(expected.replace("-", ""), ignoreCase = true)) expected else returned

    /** Sign-out. The local secret leaves when the server confirms; otherwise a private pending record keeps retrying. */
    suspend fun end(serverId: String, viewerId: String) = mutex.withLock {
        load().state.records.filter { it.server == serverId && it.viewer == viewerId }.forEach { settle(it) }
    }

    /** Any set whose account no longer exists on this device. */
    suspend fun endAllExcept(accounts: Set<Pair<String, String>>) = mutex.withLock {
        load().state.records.filterNot { (it.server to it.viewer) in accounts }.forEach { settle(it) }
    }

    /** Retries revocations a dead network left pending. */
    suspend fun reconnect() = mutex.withLock {
        load().state.records.filter { it.phase in listOf("pending-revoke", "revoking", "pending-persist") }.forEach { settle(it) }
    }

    private suspend fun settle(record: TemporarySession) {
        val saved = load()
        when (record.phase) {
            "active" -> {
                save(saved.copy(state = reduceTemporary(saved.state, TemporaryEvent("end", record))))
                finishRevocation(record)
            }
            "creating", "cancelled-creating" -> {
                // Died mid-issue: no secret ever reached us, so there's nothing to revoke but the record.
                save(saved.copy(state = saved.state.copy(records = saved.state.records - record)))
            }
            else -> finishRevocation(record)
        }
    }

    private suspend fun finishRevocation(binding: TemporarySession) {
        var saved = load()
        val phase = saved.state.records.firstOrNull { it.device == binding.device }?.phase
        if (phase == "pending-persist") {
            saved = saved.copy(state = reduceTemporary(saved.state, TemporaryEvent("persisted", saved.state.records.first { it.device == binding.device })))
            save(saved)
        } else if (phase == "pending-revoke") {
            saved = saved.copy(state = reduceTemporary(saved.state, TemporaryEvent("reconnect", saved.state.records.first { it.device == binding.device })))
            save(saved)
        }
        val record = saved.state.records.firstOrNull { it.device == binding.device } ?: return
        val token = saved.tokens[record.secretRef]
        val server = saved.servers[record.server]
        val ok = token == null || server == null || try {
            api.logout(server, record.device, token)
            true
        } catch (e: OfflineApiException) {
            // An already-dead token is revoked.
            e.status == 401
        } catch (e: IOException) {
            false
        }
        val after = reduceTemporary(saved.state, TemporaryEvent(if (ok) "logout-ok" else "logout-failed", record))
        val erase = after.effect == "erase-secret"
        save(saved.copy(state = after, tokens = if (erase) saved.tokens - record.secretRef.orEmpty() else saved.tokens))
    }

    private companion object {
        const val SENDER = "offline"
    }
}
