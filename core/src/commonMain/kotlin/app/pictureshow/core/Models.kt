package app.pictureshow.core

import kotlinx.serialization.Serializable

@Serializable
data class Server(val id: String, val name: String, val url: String, val version: String)

@Serializable
data class User(val id: String, val name: String)

@Serializable
data class Account(val server: Server, val user: User, val accessToken: String) {
    val key: String get() = "${server.id}:${user.id}"
}

data class ClientIdentity(val deviceName: String, val deviceId: String, val version: String = "0.1.0")

enum class ErrorKind {
    INVALID_ADDRESS,
    NOT_JELLYFIN,
    UNSUPPORTED_VERSION,
    INVALID_CREDENTIALS,
    NETWORK,
    SERVER,
    EXPIRED,
    INVALID_RESPONSE,
}

data class ClientError(val kind: ErrorKind, val message: String)

sealed interface ClientResult<out T> {
    data class Success<T>(val value: T) : ClientResult<T>

    data class Failure(val error: ClientError) : ClientResult<Nothing>
}

sealed interface QuickConnectState {
    data class Code(val code: String) : QuickConnectState

    data class Authorized(val account: Account) : QuickConnectState

    data class Failed(val error: ClientError) : QuickConnectState

    data object Disabled : QuickConnectState
}
