package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceRenewalTest {
    private val server = Server("server", "Cinema", "https://media.example.test", "12.0.0")
    private val account = Account(server, User("viewer", "Viewer"), "fake-old-token")

    @Test fun existingTokenApprovesOnlyItsOwnNewIdentityBeforeTheReplacementIsReturned() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        paths += path
                        val header = it.headers[HttpHeaders.Authorization]!!
                        if (path == "/QuickConnect/Authorize") {
                            assertTrue(header.contains("DeviceId=\"legacy-device\""))
                            assertTrue(header.contains("Token=\"fake-old-token\""))
                            assertEquals("123456", it.url.parameters["code"])
                            assertEquals(null, it.url.parameters["userId"])
                        } else {
                            assertTrue(header.contains("DeviceId=\"new-device\""))
                            assertFalseToken(header)
                        }
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled", "/QuickConnect/Authorize" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                else -> {
                                    assertTrue(it.body.toByteArray().decodeToString().contains("fake-secret"))
                                    """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"fake-new-token"}"""
                                }
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Success<Account>>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device"))
                    .renewDevice(account, "new-device")
            ).value
            assertEquals(account.copy(deviceId = "new-device", accessToken = "fake-new-token"), result)
            assertEquals(
                listOf("/QuickConnect/Enabled", "/QuickConnect/Initiate", "/QuickConnect/Authorize", "/Users/AuthenticateWithQuickConnect"),
                paths
            )
        } finally {
            http.close()
        }
    }

    @Test fun disabledQuickConnectDefersWithoutAttemptingToAuthorize() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        calls++
                        assertEquals("/QuickConnect/Enabled", it.url.encodedPath)
                        respond("false", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Failure>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device"))
                    .renewDevice(account, "new-device")
            )
            assertEquals(ErrorKind.SERVER, result.error.kind)
            assertEquals(null, account.deviceId)
            assertEquals("fake-old-token", account.accessToken)
            assertEquals(1, calls)
        } finally {
            http.close()
        }
    }

    @Test fun lostApprovalReplyStillExchangesTheAlreadyMintedToken() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        paths += path
                        if (path == "/QuickConnect/Authorize") throw IllegalStateException("Reply lost")
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                else -> """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"fake-new-token"}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Success<Account>>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device")).renewDevice(account, "new-device")
            ).value
            assertEquals("fake-new-token", result.accessToken)
            assertEquals("/Users/AuthenticateWithQuickConnect", paths.last())
        } finally {
            http.close()
        }
    }

    @Test fun lostExchangeReplyRetriesTheSameSecretAndDeviceWithoutAuthorizingAgain() = runTest {
        var exchanges = 0
        var approvals = 0
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        if (path == "/QuickConnect/Authorize") approvals++
                        if (path == "/Users/AuthenticateWithQuickConnect") {
                            exchanges++
                            assertTrue(it.headers[HttpHeaders.Authorization]!!.contains("DeviceId=\"new-device\""))
                            assertTrue(it.body.toByteArray().decodeToString().contains("fake-secret"))
                            if (exchanges == 1) throw IllegalStateException("Reply lost")
                        }
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled", "/QuickConnect/Authorize" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                else -> """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"fake-new-token"}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Success<Account>>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device")).renewDevice(account, "new-device")
            ).value
            assertEquals("fake-new-token", result.accessToken)
            assertEquals(2, exchanges)
            assertEquals(1, approvals)
        } finally {
            http.close()
        }
    }

    @Test fun revokedAuthorizationDoesNotMintOrExchangeAnotherCredential() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        paths += path
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                else -> "{}"
                            },
                            status = if (path in listOf("/QuickConnect/Authorize", "/Users/Me")) {
                                io.ktor.http.HttpStatusCode.Unauthorized
                            } else {
                                io.ktor.http.HttpStatusCode.OK
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Failure>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device")).renewDevice(account, "new-device")
            )
            assertEquals(ErrorKind.INVALID_CREDENTIALS, result.error.kind)
            assertEquals("/Users/Me", paths.last())
            assertTrue(paths.none { it == "/Users/AuthenticateWithQuickConnect" })
        } finally {
            http.close()
        }
    }

    @Test fun quickConnectDisabledDuringApprovalDoesNotExpireAValidToken() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        paths += path
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                                else -> "{}"
                            },
                            status = if (path == "/QuickConnect/Authorize") {
                                io.ktor.http.HttpStatusCode.Unauthorized
                            } else {
                                io.ktor.http.HttpStatusCode.OK
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Failure>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device")).renewDevice(account, "new-device")
            )
            assertEquals(ErrorKind.SERVER, result.error.kind)
            assertEquals("/Users/Me", paths.last())
            assertTrue(paths.none { it == "/Users/AuthenticateWithQuickConnect" })
        } finally {
            http.close()
        }
    }

    @Test fun unexpectedUserCredentialIsRevokedBeforeItIsDiscarded() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        paths += path
                        if (path == "/Sessions/Logout") {
                            assertTrue(it.headers[HttpHeaders.Authorization]!!.contains("Token=\"fake-unexpected-token\""))
                        }
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled", "/QuickConnect/Authorize" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                "/Sessions/Logout" -> ""
                                else -> """
                                    {"ServerId":"server","User":{"Id":"unexpected","Name":"Viewer"},
                                    "AccessToken":"fake-unexpected-token"}
                                """.trimIndent()
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val result = assertIs<ClientResult.Failure>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device")).renewDevice(account, "new-device")
            )
            assertEquals(ErrorKind.INVALID_RESPONSE, result.error.kind)
            assertEquals("/Sessions/Logout", paths.last())
        } finally {
            http.close()
        }
    }

    @Test fun stalledExchangeHasOneBoundedWindowAndPreservesTheExistingCredential() = runTest {
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        val path = it.url.encodedPath
                        if (path == "/Users/AuthenticateWithQuickConnect") kotlinx.coroutines.awaitCancellation()
                        respond(
                            when (path) {
                                "/QuickConnect/Enabled", "/QuickConnect/Authorize" -> "true"
                                else -> """{"Code":"123456","Secret":"fake-secret"}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val started = testScheduler.currentTime
            val result = assertIs<ClientResult.Failure>(
                JellyfinClient(http, ClientIdentity("Test TV", "legacy-device")).renewDevice(account, "new-device")
            )
            assertEquals(ErrorKind.NETWORK, result.error.kind)
            assertEquals(2_000L, testScheduler.currentTime - started)
            assertEquals("fake-old-token", account.accessToken)
            assertEquals(null, account.deviceId)
        } finally {
            http.close()
        }
    }

    private fun assertFalseToken(header: String) = kotlin.test.assertFalse(header.contains("Token="))
}
