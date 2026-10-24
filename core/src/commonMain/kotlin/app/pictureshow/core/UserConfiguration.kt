/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Keep unknown configuration fields intact when updating the user's playback preferences. */
class UserConfiguration(val fields: JsonObject) {
    val audioLanguage: String get() = fields["AudioLanguagePreference"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val subtitleLanguage: String get() = fields["SubtitleLanguagePreference"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val subtitleMode: String get() = fields["SubtitleMode"]?.jsonPrimitive?.contentOrNull ?: "Default"

    fun withPreference(name: String, value: String): UserConfiguration {
        require(name in PREFERENCES)
        return UserConfiguration(JsonObject(fields + (name to JsonPrimitive(value))))
    }

    // Configuration may acquire sensitive fields in future server versions.
    override fun toString(): String = "UserConfiguration(fields=${fields.keys})"

    companion object {
        val PREFERENCES = setOf("AudioLanguagePreference", "SubtitleLanguagePreference", "SubtitleMode")
    }
}

interface UserConfigurationService {
    suspend fun configuration(account: Account): ClientResult<UserConfiguration>
    suspend fun saveConfiguration(account: Account, configuration: UserConfiguration): ClientResult<Unit>
}

class UserConfigurationClient(private val http: HttpClient, private val identity: ClientIdentity) : UserConfigurationService {
    override suspend fun configuration(account: Account): ClientResult<UserConfiguration> = request(
        account,
        "Users/${account.user.id.encodeURLPathPart()}",
        HttpMethod.Get
    ) { UserConfiguration(Json.parseToJsonElement(it).jsonObject.getValue("Configuration").jsonObject) }

    override suspend fun saveConfiguration(account: Account, configuration: UserConfiguration): ClientResult<Unit> = request(
        account,
        "Users/Configuration?userId=${account.user.id.encodeURLParameter()}",
        HttpMethod.Post,
        configuration.fields.toString()
    ) { }

    private suspend fun <T> request(
        account: Account,
        path: String,
        method: HttpMethod,
        body: String? = null,
        decode: (String) -> T
    ): ClientResult<T> = try {
        withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            val response = http.request("${account.server.url.trimEnd('/')}/$path") {
                this.method = method
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            when (response.status.value) {
                in 200..299 -> ClientResult.Success(decode(response.bodyAsText()))
                401 -> failure(ErrorKind.INVALID_CREDENTIALS, "This sign-in has expired. Sign in again.")
                else -> failure(ErrorKind.SERVER, "Could not save or read playback preferences (${response.status.value}). Try again.")
            }
        } ?: failure(ErrorKind.NETWORK, "The server took too long to respond. Try again.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure(ErrorKind.NETWORK, "Could not read or save playback preferences. Check your connection and try again.")
    }

    private fun failure(kind: ErrorKind, message: String) = ClientResult.Failure(ClientError(kind, message))
}
