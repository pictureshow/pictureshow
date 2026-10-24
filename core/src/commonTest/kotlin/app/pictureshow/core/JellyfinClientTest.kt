package app.pictureshow.core

import app.cash.turbine.test
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinClientTest {
    private val identity = ClientIdentity("Living room TV", "device-123")
    private val server = Server("server-1", "Test Jellyfin", "https://example.test/jellyfin", "12.0.0")
    private val successfulAuth = """{"AccessToken":"token-1","ServerId":"server-1","User":{"Id":"user-1","Name":"Taylor"}}"""

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): JellyfinClient {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            }
        }
        backgroundScope.coroutineContext.job.invokeOnCompletion { http.close() }
        return JellyfinClient(http, identity)
    }

    private fun info(version: String = "12.0.0", product: String = "Jellyfin Server"): String =
        """{"Id":"server-1","ServerName":"Test Jellyfin","Version":"$version","ProductName":"$product"}"""

    @Test
    fun httpsIsPreferredAndBasePathIsPreserved() = runTest {
        val requests = mutableListOf<String>()
        val result = client {
            requests += it.url.toString()
            respond(info())
        }.probe("example.test/jellyfin")
        assertEquals("https://example.test/jellyfin", assertIs<ClientResult.Success<Server>>(result).value.url)
        assertEquals(listOf("https://example.test/jellyfin/System/Info/Public"), requests)
    }

    @Test
    fun unreachableHttpsFallsBackToHttpJellyfinPort() = runTest {
        val requests = mutableListOf<String>()
        val result = client {
            requests += it.url.toString()
            if (it.url.protocol.name == "http" && it.url.port == 8096) respond(info()) else error("unreachable")
        }.probe("example.test")
        assertEquals("http://example.test:8096", assertIs<ClientResult.Success<Server>>(result).value.url)
        assertEquals(4, requests.size)
    }

    @Test
    fun failedHttpsDefaultStillPrefersHttpsJellyfinPortOverHttp() = runTest {
        val result = client {
            if (it.url.port == 443) error("unreachable") else respond(info())
        }.probe("example.test")
        assertEquals("https://example.test:8920", assertIs<ClientResult.Success<Server>>(result).value.url)
    }

    @Test
    fun nonJellyfinResponseIsRejected() = runTest {
        val result = client { respond(info(product = "Other server")) }.probe("http://example.test:8096")
        assertEquals(ErrorKind.NOT_JELLYFIN, assertIs<ClientResult.Failure>(result).error.kind)
    }

    @Test
    fun obsoleteAndBaselinePrereleaseServersAreRejectedWithClearMessage() = runTest {
        listOf("10.9.11", "9.99.0", "10.10.0-rc.1").forEach { version ->
            val result = client { respond(info(version)) }.probe("http://example.test:8096")
            val failure = assertIs<ClientResult.Failure>(result)
            assertEquals(ErrorKind.UNSUPPORTED_VERSION, failure.error.kind)
            assertTrue(failure.error.message.contains("10.10.0"))
        }
    }

    @Test
    fun obsoleteServerMessageSurvivesFailuresFromOtherCandidatePorts() = runTest {
        val result = client {
            when (it.url.port) {
                443 -> respond(info("10.9.11"))
                8920 -> error("no listener")
                80 -> respond("", HttpStatusCode.NotFound)
                else -> respond(info(product = "Other service"))
            }
        }.probe("example.test")
        val failure = assertIs<ClientResult.Failure>(result)
        assertEquals(ErrorKind.UNSUPPORTED_VERSION, failure.error.kind)
        assertTrue(failure.error.message.contains("10.9.11"))
    }

    @Test
    fun proxyAuthenticationDuringProbeIsReportedAsServerConfiguration() = runTest {
        val result = client { respond("", HttpStatusCode.Unauthorized) }.probe("example.test")
        val failure = assertIs<ClientResult.Failure>(result)
        assertEquals(ErrorKind.SERVER, failure.error.kind)
        assertTrue(failure.error.message.contains("public server information"))
    }

    @Test
    fun minimumAndNewerVersionsAreAccepted() = runTest {
        listOf("10.10.0", "10.10.1", "10.11.0", "12.0.0").forEach { version ->
            val result = client { respond(info(version)) }.probe("http://example.test:8096")
            assertEquals(version, assertIs<ClientResult.Success<Server>>(result).value.version)
        }
    }

    @Test
    fun invalidAddressMakesNoNetworkRequest() = runTest {
        val result = client { error("should not be called") }.probe("ftp://example.test")
        assertEquals(ErrorKind.INVALID_ADDRESS, assertIs<ClientResult.Failure>(result).error.kind)
    }

    @Test
    fun passwordAuthenticationSendsCorrectJsonAndHeaderAndReturnsAccount() = runTest {
        val result = client {
            assertEquals("/jellyfin/Users/AuthenticateByName", it.url.encodedPath)
            assertEquals("POST", it.method.value)
            assertEquals(AuthorizationHeader.build(identity), it.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            assertEquals("Taylor", body.getValue("Username").jsonPrimitive.content)
            assertEquals("p\"a,ss", body.getValue("Pw").jsonPrimitive.content)
            respond(successfulAuth, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }.authenticate(server, "Taylor", "p\"a,ss")
        assertEquals(
            Account(server, User("user-1", "Taylor"), "token-1", deviceId = identity.deviceId),
            assertIs<ClientResult.Success<Account>>(result).value
        )
    }

    @Test
    fun incorrectPasswordMaps401ToInvalidCredentials() = runTest {
        val result = client { respond("", HttpStatusCode.Unauthorized) }.authenticate(server, "Taylor", "wrong")
        assertEquals(ErrorKind.INVALID_CREDENTIALS, assertIs<ClientResult.Failure>(result).error.kind)
    }

    @Test
    fun networkAndServerErrorsAreDistinguished() = runTest {
        val network = client { error("disconnected") }.authenticate(server, "Taylor", "password")
        val serverError = client { respond("", HttpStatusCode.InternalServerError) }.authenticate(server, "Taylor", "password")
        assertEquals(ErrorKind.NETWORK, assertIs<ClientResult.Failure>(network).error.kind)
        assertEquals(ErrorKind.SERVER, assertIs<ClientResult.Failure>(serverError).error.kind)
    }

    @Test
    fun malformedOrMismatchedAuthenticationResultIsRejected() = runTest {
        listOf("not json", "{}", successfulAuth.replace("server-1", "different-server")).forEach { response ->
            val result = client { respond(response) }.authenticate(server, "Taylor", "password")
            assertEquals(ErrorKind.INVALID_RESPONSE, assertIs<ClientResult.Failure>(result).error.kind)
        }
    }

    @Test
    fun hiddenPublicUsersRemainAnEmptySuccess() = runTest {
        val result = client { respond("[]") }.publicUsers(server)
        assertEquals(emptyList(), assertIs<ClientResult.Success<List<User>>>(result).value)
    }

    @Test
    fun publicUsersParseWithUnrelatedFieldsIgnored() = runTest {
        val result = client { respond("""[{"Id":"user-1","Name":"Taylor","Policy":{"IsAdministrator":false}}]""") }.publicUsers(server)
        assertEquals(listOf(User("user-1", "Taylor")), assertIs<ClientResult.Success<List<User>>>(result).value)
    }

    @Test
    fun disabledQuickConnectNeverInitiatesOrPolls() = runTest {
        val service = client {
            assertEquals("/jellyfin/QuickConnect/Enabled", it.url.encodedPath)
            respond("false")
        }
        assertEquals(false, assertIs<ClientResult.Success<Boolean>>(service.quickConnectEnabled(server)).value)
        service.quickConnect(server).test {
            assertEquals(QuickConnectState.Disabled, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun quickConnectPendingThenAuthorizedExchangesSecretForAccount() = runTest {
        var polls = 0
        val service = client {
            when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                "QuickConnect/Enabled" -> respond("true")
                "QuickConnect/Initiate" -> respond("""{"Code":"123456","Secret":"secret +&?"}""")
                "QuickConnect/Connect" -> {
                    assertEquals("secret +&?", it.url.parameters["secret"])
                    polls++
                    respond("""{"Authenticated":${polls == 2}}""")
                }
                "Users/AuthenticateWithQuickConnect" -> {
                    val body = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                    assertEquals("secret +&?", body.getValue("Secret").jsonPrimitive.content)
                    respond(successfulAuth)
                }
                else -> error("unexpected endpoint")
            }
        }
        service.quickConnect(server).test {
            assertEquals(QuickConnectState.Code("123456"), awaitItem())
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(0, polls)
            expectNoEvents()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, polls)
            expectNoEvents()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("token-1", assertIs<QuickConnectState.Authorized>(awaitItem()).account.accessToken)
            awaitComplete()
        }
    }

    @Test
    fun pendingQuickConnectRetainsOriginalSecretUntilServerApproval() = runTest {
        var initiations = 0
        var approved = false
        val service = client {
            when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                "QuickConnect/Enabled" -> respond("true")
                "QuickConnect/Initiate" -> {
                    initiations++
                    respond("""{"Code":"123456","Secret":"original-secret"}""")
                }
                "QuickConnect/Connect" -> {
                    assertEquals("original-secret", it.url.parameters["secret"])
                    respond("""{"Authenticated":$approved}""")
                }
                "Users/AuthenticateWithQuickConnect" -> {
                    val body = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                    assertEquals("original-secret", body.getValue("Secret").jsonPrimitive.content)
                    respond(successfulAuth)
                }
                else -> error("Unexpected endpoint")
            }
        }
        service.quickConnect(server).test {
            assertEquals(QuickConnectState.Code("123456"), awaitItem())
            advanceTimeBy(60_000)
            runCurrent()
            expectNoEvents()
            assertEquals(1, initiations)
            approved = true
            advanceTimeBy(5_000)
            runCurrent()
            assertIs<QuickConnectState.Authorized>(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun expired401And404RequestsGetNewCodes() = runTest {
        for (expiredStatus in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
            var initiations = 0
            val service = client {
                when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                    "QuickConnect/Enabled" -> respond("true")
                    "QuickConnect/Initiate" -> {
                        initiations++
                        respond("""{"Code":"${if (initiations == 1) "111111" else "222222"}","Secret":"secret-$initiations"}""")
                    }
                    "QuickConnect/Connect" -> respond("", expiredStatus)
                    else -> error("unexpected endpoint")
                }
            }
            service.quickConnect(server).test {
                assertEquals(QuickConnectState.Code("111111"), awaitItem())
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(QuickConnectState.Code("222222"), awaitItem())
                assertEquals(2, initiations)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun cancellingQuickConnectStopsPollingEvenAfterVirtualTimeAdvances() = runTest {
        var polls = 0
        val service = client {
            when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                "QuickConnect/Enabled" -> respond("true")
                "QuickConnect/Initiate" -> respond("""{"Code":"123456","Secret":"secret"}""")
                "QuickConnect/Connect" -> {
                    polls++
                    respond("""{"Authenticated":false}""")
                }
                else -> error("unexpected endpoint")
            }
        }
        service.quickConnect(server).test {
            awaitItem()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, polls)
            cancelAndIgnoreRemainingEvents()
        }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, polls)
    }

    @Test
    fun quickConnectFailureEmitsFailureInsteadOfContinuingToPoll() = runTest {
        val service = client { respond("", HttpStatusCode.InternalServerError) }
        service.quickConnect(server).test {
            assertEquals(ErrorKind.SERVER, assertIs<QuickConnectState.Failed>(awaitItem()).error.kind)
            awaitComplete()
        }
    }

    @Test
    fun invalidInitiationCodeFailsWithoutPolling() = runTest {
        val service = client {
            when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                "QuickConnect/Enabled" -> respond("true")
                "QuickConnect/Initiate" -> respond("""{"Code":"12oops","Secret":"secret"}""")
                else -> error("Must not poll an invalid initiation")
            }
        }
        service.quickConnect(server).test {
            assertEquals(ErrorKind.INVALID_RESPONSE, assertIs<QuickConnectState.Failed>(awaitItem()).error.kind)
            awaitComplete()
        }
    }

    @Test
    fun rejectedExchangeSecretRenewsCodeAndNewApprovalSignsIn() = runTest {
        for (rejection in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
            var attempts = 0
            val service = client {
                when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                    "QuickConnect/Enabled" -> respond("true")
                    "QuickConnect/Initiate" -> {
                        attempts++
                        respond("""{"Code":"${if (attempts == 1) "111111" else "222222"}","Secret":"attempt-$attempts"}""")
                    }
                    "QuickConnect/Connect" -> respond("""{"Authenticated":true}""")
                    "Users/AuthenticateWithQuickConnect" -> {
                        val body = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                        assertEquals("attempt-$attempts", body.getValue("Secret").jsonPrimitive.content)
                        if (attempts == 1) respond("", rejection) else respond(successfulAuth)
                    }
                    else -> error("Unexpected endpoint")
                }
            }
            service.quickConnect(server).test {
                assertEquals(QuickConnectState.Code("111111"), awaitItem())
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(QuickConnectState.Code("222222"), awaitItem())
                advanceTimeBy(5_000)
                runCurrent()
                assertIs<QuickConnectState.Authorized>(awaitItem())
                awaitComplete()
                assertEquals(2, attempts)
            }
        }
    }

    @Test
    fun tokenExchangeFailureIsShownAndPollingEnds() = runTest {
        var polls = 0
        val service = client {
            when (it.url.encodedPath.substringAfter("/jellyfin/")) {
                "QuickConnect/Enabled" -> respond("true")
                "QuickConnect/Initiate" -> respond("""{"Code":"123456","Secret":"secret"}""")
                "QuickConnect/Connect" -> {
                    polls++
                    respond("""{"Authenticated":true}""")
                }
                "Users/AuthenticateWithQuickConnect" -> respond("", HttpStatusCode.InternalServerError)
                else -> error("Unexpected endpoint")
            }
        }
        service.quickConnect(server).test {
            assertEquals(QuickConnectState.Code("123456"), awaitItem())
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ErrorKind.SERVER, assertIs<QuickConnectState.Failed>(awaitItem()).error.kind)
            awaitComplete()
        }
        advanceTimeBy(30_000)
        assertEquals(1, polls)
    }

    @Test
    fun requestCancellationIsPropagated() = runTest {
        var cancelled = false
        val service = client {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val request = async { service.authenticate(server, "Taylor", "password") }
        runCurrent()
        request.cancel()
        try {
            request.await()
        } catch (_: CancellationException) {
            // Expected: cancellation is not surfaced to the user as a connection failure.
        }
        assertTrue(cancelled)
        assertTrue(request.isCancelled)
    }

    @Test
    fun requestTimeoutMapsToNetworkError() = runTest {
        val service = client { awaitCancellation() }
        val request = async { service.authenticate(server, "Taylor", "password") }
        runCurrent()
        assertFalse(request.isCompleted)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(ErrorKind.NETWORK, assertIs<ClientResult.Failure>(request.await()).error.kind)
    }

    @Test
    fun authenticatedUserAndLogoutSendTokenAndPreserveBasePath() = runTest {
        val paths = mutableListOf<String>()
        val account = Account(server, User("user-1", "Taylor"), "token-1")
        val service = client {
            paths += it.url.encodedPath
            assertEquals(AuthorizationHeader.build(identity, "token-1"), it.headers[HttpHeaders.Authorization])
            if (it.url.encodedPath.endsWith("/Me")) {
                respond("""{"Id":"user-1","Name":"Taylor"}""")
            } else {
                assertEquals("POST", it.method.value)
                respond("", HttpStatusCode.NoContent)
            }
        }
        assertEquals(account.user, assertIs<ClientResult.Success<User>>(service.me(account)).value)
        assertIs<ClientResult.Success<Unit>>(service.logout(account))
        assertEquals(listOf("/jellyfin/Users/Me", "/jellyfin/Sessions/Logout"), paths)
    }
}
