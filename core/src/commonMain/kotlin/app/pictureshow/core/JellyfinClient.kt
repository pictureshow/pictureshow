package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface JellyfinService {
    suspend fun probe(address: String): ClientResult<Server>

    suspend fun publicUsers(server: Server): ClientResult<List<User>>

    suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account>

    suspend fun quickConnectEnabled(server: Server): ClientResult<Boolean>

    fun quickConnect(server: Server): Flow<QuickConnectState>

    fun quickConnect(server: Server, pending: PendingQuickConnect?, onPending: (PendingQuickConnect?) -> Unit): Flow<QuickConnectState> =
        quickConnect(server)

    suspend fun me(account: Account): ClientResult<User>

    /** Optional same-user credential rotation; unsupported services retain the original account. */
    suspend fun separateDevice(account: Account): ClientResult<Account> = ClientResult.Success(account)

    suspend fun logout(account: Account): ClientResult<Unit>
}

class JellyfinClient(private val client: HttpClient, private val identity: ClientIdentity) :
    JellyfinService,
    CatalogService by CatalogClient(client, identity),
    SearchService by SearchClient(client, identity),
    UserConfigurationService by UserConfigurationClient(client, identity) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(address: String): ClientResult<Server> {
        val candidates = try {
            AddressCandidates.build(address)
        } catch (_: IllegalArgumentException) {
            return failure(ErrorKind.INVALID_ADDRESS, "Enter an HTTP or HTTPS server address without a query or credentials.")
        }
        // A fully specified endpoint can be slow; only automatic port discovery needs short probes.
        val probeTimeout = if (candidates.size == 1) ApiTimeouts.REQUEST_MILLIS else ApiTimeouts.PROBE_MILLIS
        var error = ClientError(ErrorKind.NETWORK, "Could not reach the server. Check its address and connection.")
        for (url in candidates) {
            val result = request(url, "System/Info/Public", timeoutMillis = probeTimeout) { text ->
                val info = json.parseToJsonElement(text).jsonObject
                if (info.string("ProductName") != "Jellyfin Server") {
                    throw ProtocolFailure(ErrorKind.NOT_JELLYFIN, "This address is not a Jellyfin server.")
                }
                val version = info.string("Version")
                if (!supportedVersion(version)) {
                    throw ProtocolFailure(ErrorKind.UNSUPPORTED_VERSION, "Jellyfin 10.10.0 or newer is required (server reports $version).")
                }
                Server(info.string("Id"), info.string("ServerName"), url, version)
            }
            when (result) {
                is ClientResult.Success -> return result
                is ClientResult.Failure -> {
                    val candidateError = if (result.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                        ClientError(
                            ErrorKind.SERVER,
                            "This address blocks public server information. Check the server or reverse proxy settings."
                        )
                    } else {
                        result.error
                    }
                    if (probeErrorPriority(candidateError.kind) >= probeErrorPriority(error.kind)) error = candidateError
                }
            }
        }
        return ClientResult.Failure(error)
    }

    override suspend fun publicUsers(server: Server): ClientResult<List<User>> = request(server.url, "Users/Public") { text ->
        (json.parseToJsonElement(text) as JsonArray).map { user(it.jsonObject) }
    }

    override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> = request(
        server.url,
        "Users/AuthenticateByName",
        HttpMethod.Post,
        buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }
    ) { account(server, it) }

    override suspend fun quickConnectEnabled(server: Server): ClientResult<Boolean> =
        request(server.url, "QuickConnect/Enabled") { json.parseToJsonElement(it).jsonPrimitive.boolean }

    override fun quickConnect(server: Server): Flow<QuickConnectState> = quickConnect(server, null) {}

    override fun quickConnect(
        server: Server,
        pending: PendingQuickConnect?,
        onPending: (PendingQuickConnect?) -> Unit
    ): Flow<QuickConnectState> = flow {
        // A restored attempt is bound to its initiating device, even before its user is known.
        if (!pending?.deviceId.isNullOrBlank() && pending?.deviceId != identity.deviceId) {
            JellyfinClient(
                client,
                identity.copy(deviceId = requireNotNull(pending?.deviceId))
            ).quickConnect(server, pending, onPending).collect {
                emit(it)
            }
            return@flow
        }
        when (val enabled = quickConnectEnabled(server)) {
            is ClientResult.Failure -> {
                emit(QuickConnectState.Failed(enabled.error))
                return@flow
            }
            is ClientResult.Success -> if (!enabled.value) {
                onPending(null)
                emit(QuickConnectState.Disabled)
                return@flow
            }
        }
        var attempt = pending
        var resumeImmediately = pending != null
        while (true) {
            if (attempt == null) {
                val initiation = request(server.url, "QuickConnect/Initiate", HttpMethod.Post) {
                    val body = json.parseToJsonElement(it).jsonObject
                    val code = body.string("Code")
                    require(code.length == 6 && code.all(Char::isDigit))
                    code to body.string("Secret")
                }
                if (initiation is ClientResult.Failure) {
                    emit(QuickConnectState.Failed(initiation.error))
                    return@flow
                }
                val (code, secret) = (initiation as ClientResult.Success).value
                attempt = PendingQuickConnect(code, secret, identity.deviceId)
                onPending(attempt)
            }
            val secret = attempt.secret
            emit(QuickConnectState.Code(attempt.code))
            while (true) {
                if (!resumeImmediately) delay(5_000)
                resumeImmediately = false
                val connected = request(server.url, "QuickConnect/Connect", secret = secret, expiryStatus = true) {
                    json.parseToJsonElement(it).jsonObject.getValue("Authenticated").jsonPrimitive.boolean
                }
                if (connected is ClientResult.Failure) {
                    if (connected.error.kind == ErrorKind.EXPIRED) {
                        attempt = null
                        onPending(null)
                        break
                    }
                    emit(QuickConnectState.Failed(connected.error))
                    return@flow
                }
                if ((connected as ClientResult.Success).value) {
                    val exchanged = request(
                        server.url,
                        "Users/AuthenticateWithQuickConnect",
                        HttpMethod.Post,
                        buildJsonObject { put("Secret", secret) },
                        expiryStatus = true
                    ) { account(server, it) }
                    // Approval and exchange can straddle expiry or revocation. Renew the attempt
                    // just as when polling rejects its secret; other failures still stop for retry.
                    if (exchanged is ClientResult.Failure && exchanged.error.kind == ErrorKind.EXPIRED) {
                        attempt = null
                        onPending(null)
                        break
                    }
                    if (exchanged is ClientResult.Success) onPending(null)
                    emit(
                        when (exchanged) {
                            is ClientResult.Success -> QuickConnectState.Authorized(exchanged.value)
                            is ClientResult.Failure -> QuickConnectState.Failed(exchanged.error)
                        }
                    )
                    return@flow
                }
            }
        }
    }

    override suspend fun me(account: Account): ClientResult<User> =
        request(account.server.url, "Users/Me", token = account.accessToken, requestIdentity = identity.forAccount(account)) {
            user(json.parseToJsonElement(it).jsonObject)
        }

    override suspend fun logout(account: Account): ClientResult<Unit> = request(
        account.server.url,
        "Sessions/Logout",
        HttpMethod.Post,
        token = account.accessToken,
        requestIdentity = identity.forAccount(account)
    ) {
    }

    /** Existing authorization approves a fresh identity for the same user; no password is retained or requested. */
    suspend fun renewDevice(account: Account, newDeviceId: String): ClientResult<Account> {
        val fresh = JellyfinClient(client, identity.copy(deviceId = newDeviceId))
        val enabled = fresh.request(account.server.url, "QuickConnect/Enabled", timeoutMillis = 1_000) {
            json.parseToJsonElement(it).jsonPrimitive.boolean
        }
        if (enabled is ClientResult.Failure) return enabled
        if (!(enabled as ClientResult.Success).value) return failure(ErrorKind.SERVER, "The server could not complete the request.")
        val initiated = fresh.request(account.server.url, "QuickConnect/Initiate", HttpMethod.Post, timeoutMillis = 1_000) {
            val value = json.parseToJsonElement(it).jsonObject
            val code = value.string("Code")
            require(code.length == 6 && code.all(Char::isDigit))
            code to value.string("Secret")
        }
        if (initiated is ClientResult.Failure) return initiated
        val (code, secret) = (initiated as ClientResult.Success).value
        val approved = request(
            account.server.url,
            "QuickConnect/Authorize?code=$code",
            HttpMethod.Post,
            token = account.accessToken,
            requestIdentity = identity.forAccount(account),
            timeoutMillis = 1_000
        ) { require(json.parseToJsonElement(it).jsonPrimitive.boolean) }
        if (approved is ClientResult.Failure && approved.error.kind == ErrorKind.INVALID_CREDENTIALS) {
            // Quick Connect can be disabled between Enabled and Authorize. Only expire the saved
            // credential when a normal authenticated endpoint also refuses it.
            val checked = withTimeoutOrNull(1_000) { me(account) }
            return if (checked is ClientResult.Failure && checked.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                approved
            } else {
                failure(ErrorKind.SERVER, "The server could not complete the request.")
            }
        }
        if (approved is ClientResult.Failure && approved.error.kind != ErrorKind.NETWORK) return approved
        // Exchange is cached by the server. A lost reply can safely be retried with the same
        // secret and identity, within one bounded window rather than minting another device.
        val exchanged = withTimeoutOrNull(2_000) {
            suspend fun exchange() = fresh.request(
                account.server.url,
                "Users/AuthenticateWithQuickConnect",
                HttpMethod.Post,
                buildJsonObject { put("Secret", secret) },
                timeoutMillis = 2_000
            ) { fresh.account(account.server, it) }
            val first = exchange()
            if (first is ClientResult.Failure && first.error.kind == ErrorKind.NETWORK) exchange() else first
        } ?: failure(ErrorKind.NETWORK, "The server took too long to respond. Check your connection and try again.")
        return if (exchanged is ClientResult.Success && exchanged.value.user.id != account.user.id) {
            withTimeoutOrNull(2_000) { fresh.logout(exchanged.value) }
            failure(ErrorKind.INVALID_RESPONSE, "The server returned an unexpected response.")
        } else {
            exchanged
        }
    }

    private fun account(server: Server, text: String): Account {
        val body = json.parseToJsonElement(text).jsonObject
        require(body.string("ServerId") == server.id)
        return Account(server, user(body.getValue("User").jsonObject), body.string("AccessToken"), deviceId = identity.deviceId)
    }

    private fun user(body: JsonObject): User = User(body.string("Id"), body.string("Name"))

    private suspend fun <T> request(
        baseUrl: String,
        path: String,
        method: HttpMethod = HttpMethod.Get,
        body: JsonObject? = null,
        token: String? = null,
        requestIdentity: ClientIdentity = identity,
        secret: String? = null,
        expiryStatus: Boolean = false,
        timeoutMillis: Long = ApiTimeouts.REQUEST_MILLIS,
        decode: (String) -> T
    ): ClientResult<T> = try {
        withTimeoutOrNull(timeoutMillis) {
            val response = client.request("${baseUrl.trimEnd('/')}/$path") {
                this.method = method
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(requestIdentity, token))
                if (secret != null) url.parameters.append("secret", secret)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
            when (response.status.value) {
                in 200..299 -> ClientResult.Success(decode(response.bodyAsText()))
                401, 404 -> if (expiryStatus) {
                    failure(ErrorKind.EXPIRED, "The Quick Connect code expired.")
                } else if (response.status.value == 401) {
                    failure(ErrorKind.INVALID_CREDENTIALS, "Sign-in was refused. Check your username and password or sign in again.")
                } else {
                    failure(ErrorKind.SERVER, "The server could not complete the request (404).")
                }
                else -> failure(ErrorKind.SERVER, "The server could not complete the request (${response.status.value}).")
            }
        } ?: failure(ErrorKind.NETWORK, "The server took too long to respond. Check your connection and try again.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ProtocolFailure) {
        failure(error.kind, error.message.orEmpty())
    } catch (_: SerializationException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an unreadable response.")
    } catch (_: IllegalArgumentException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an incomplete response.")
    } catch (_: NoSuchElementException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an incomplete response.")
    } catch (_: ClassCastException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an unexpected response.")
    } catch (_: Exception) {
        failure(ErrorKind.NETWORK, "Could not reach the server. Check your connection and try again.")
    }

    private class ProtocolFailure(val kind: ErrorKind, message: String) : Exception(message)
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content.also {
    require(it.isNotBlank() && it != "null")
}

private fun failure(kind: ErrorKind, message: String): ClientResult.Failure = ClientResult.Failure(ClientError(kind, message))

private fun supportedVersion(version: String): Boolean {
    val numbers = version.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: return false }
    if (numbers.size < 3) return false
    val minimum = listOf(10, 10, 0)
    numbers.take(3).zip(minimum).forEach { (actual, required) ->
        if (actual != required) return actual > required
    }
    return !version.contains('-')
}

private fun probeErrorPriority(kind: ErrorKind): Int = when (kind) {
    ErrorKind.UNSUPPORTED_VERSION -> 4
    ErrorKind.NOT_JELLYFIN -> 3
    ErrorKind.INVALID_RESPONSE -> 2
    ErrorKind.SERVER -> 1
    else -> 0
}
