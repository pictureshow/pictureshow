/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.control

import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** The server's target list. No activity window: an idle TV with a live socket counts as idle, yet is a real target (measured). */
fun remoteTargetsPath(viewer: String): String = "Sessions?controllableByUserId=$viewer"

/** `ws`/`wss` on the account's own base path. The token travels in the Authorization header only, never the URL. */
fun remoteSocketUrl(serverUrl: String): String? {
    val base = serverUrl.trim().trimEnd('/')
    val scheme = when {
        base.startsWith("https://", ignoreCase = true) -> "wss://" to 8
        base.startsWith("http://", ignoreCase = true) -> "ws://" to 7
        else -> return null
    }
    val rest = base.substring(scheme.second)
    return if (rest.isBlank()) null else "${scheme.first}$rest/socket"
}

/**
 * Server networking for both roles. A controller only lists and sends; it has no method that can report playback.
 * [send] answers the HTTP status, with 0 for no answer, and never retries: a repeat could pause or seek twice.
 */
class RemoteClient(private val client: HttpClient, private val identity: ClientIdentity) {
    private fun authorization(account: Account) = AuthorizationHeader.build(identity.forAccount(account), account.accessToken)

    private fun url(account: Account, path: String) = "${account.server.url.trimEnd('/')}/$path"

    suspend fun targets(account: Account): ClientResult<List<RemoteTarget>> = try {
        val response = withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            client.get(url(account, remoteTargetsPath(account.user.id))) { header(HttpHeaders.Authorization, authorization(account)) }
        }
        when {
            response == null -> ClientResult.Failure(ClientError(ErrorKind.NETWORK, "The server took too long to respond."))
            response.status.value == 401 -> ClientResult.Failure(ClientError(ErrorKind.INVALID_CREDENTIALS, "Sign in again."))
            response.status.value !in 200..299 -> ClientResult.Failure(ClientError(ErrorKind.SERVER, "The server could not list devices."))
            else -> {
                val rows = Json.parseToJsonElement(response.bodyAsText()) as? JsonArray
                    ?: throw SerializationException("Not a session list")
                ClientResult.Success(remoteTargets(rows, account.user.id, identity.forAccount(account).deviceId))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE, "The server returned an unreadable response."))
    } catch (_: Exception) {
        ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Could not reach the server."))
    }

    suspend fun send(account: Account, request: RemoteRequest): Int = try {
        withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            client.post(url(account, request.path)) {
                header(HttpHeaders.Authorization, authorization(account))
                request.query.forEach { (key, value) -> parameter(key, value) }
                request.body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it.toString())
                }
            }.status.value
        } ?: 0
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0
    }

    suspend fun registerTarget(account: Account, enabled: Boolean, commands: List<String>): Int = send(
        account,
        RemoteRequest("POST", "Sessions/Capabilities/Full", body = remoteCapabilitiesBody(enabled, commands))
    )
}
