/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** AUTH-15: what the phone sends to approve a TV's code, and how each server answer reads. */
class QuickConnectApprovalTest {
    private val account = Account(Server("s", "Home", "https://jf.invalid/", "10.10.0"), User("u", "Viewer"), "token-1")
    private val identity = ClientIdentity("Phone", "device-1")

    private fun run(
        input: String,
        enabled: List<String> = listOf("true"),
        authorize: Pair<HttpStatusCode, String> = HttpStatusCode.OK to "true",
        fail: Boolean = false
    ): Pair<QuickConnectApprovalResult, List<String>> {
        val requests = mutableListOf<String>()
        var enabledReads = 0
        val http = HttpClient(
            MockEngine { request ->
                if (fail) throw IOException("unreachable")
                requests += "${request.method.value} ${request.url.encodedPath}?${request.url.encodedQuery} " +
                    (request.headers["Authorization"]?.contains("Token=\"token-1\"") == true)
                if (request.url.encodedPath.endsWith("/QuickConnect/Enabled")) {
                    respond(enabled[minOf(enabledReads++, enabled.lastIndex)], HttpStatusCode.OK)
                } else {
                    respond(authorize.second, authorize.first)
                }
            }
        )
        val result = runBlocking { QuickConnectApprovalClient(http, identity).authorize(account, input) }
        return result to requests
    }

    @Test fun approvesAPastedCodeWithTheViewersOwnToken() {
        val (result, requests) = run(" abc 123\n")
        assertEquals(QuickConnectApprovalResult.APPROVED, result)
        assertEquals(listOf("GET /QuickConnect/Enabled? true", "POST /QuickConnect/Authorize?code=ABC123 true"), requests)
    }

    @Test fun aShortCodeSendsNothing() {
        val (result, requests) = run("abc12")
        assertEquals(QuickConnectApprovalResult.INVALID_INPUT, result)
        assertTrue(requests.isEmpty())
    }

    @Test fun quickConnectTurnedOffIsSaidBeforeAnyApproval() {
        val (result, requests) = run("ABC123", enabled = listOf("false"))
        assertEquals(QuickConnectApprovalResult.DISABLED, result)
        assertEquals(1, requests.size)
    }

    @Test fun aRefusalAfterQuickConnectWasTurnedOffSaysTurnedOff() {
        val (result, _) = run("ABC123", enabled = listOf("true", "false"), authorize = HttpStatusCode.Unauthorized to "")
        assertEquals(QuickConnectApprovalResult.DISABLED, result)
    }

    @Test fun aRefusedTokenAsksToSignInAgain() {
        val (result, _) = run("ABC123", authorize = HttpStatusCode.Unauthorized to "")
        assertEquals(QuickConnectApprovalResult.SIGN_IN_REQUIRED, result)
    }

    @Test fun anUnknownCodeIsWrongOrExpired() {
        assertEquals(QuickConnectApprovalResult.WRONG_OR_EXPIRED, run("ABC123", authorize = HttpStatusCode.NotFound to "").first)
    }

    @Test fun anUnreachableServerIsOffline() {
        assertEquals(QuickConnectApprovalResult.OFFLINE, run("ABC123", fail = true).first)
    }

    @Test fun anEndedSignInSendsNothing() {
        val http = HttpClient(MockEngine { error("no request expected") })
        val result = runBlocking { QuickConnectApprovalClient(http, identity).authorize(account.copy(expired = true), "ABC123") }
        assertEquals(QuickConnectApprovalResult.SIGN_IN_REQUIRED, result)
    }
}
