/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Whitespace is harmless when pasting; punctuation and non-ASCII lookalikes are never guessed. */
fun normalizeQuickConnectCode(input: String): String = input.filterNot(Char::isWhitespace).uppercase()

fun validQuickConnectCode(input: String): Boolean = normalizeQuickConnectCode(input).let { code ->
    code.length == 6 && code.all { it in 'A'..'Z' || it in '0'..'9' }
}

/** Stable presentation keys: platforms supply localized copy, never raw HTTP or server bodies. */
enum class QuickConnectApprovalResult {
    APPROVED,
    INVALID_INPUT,
    WRONG_OR_EXPIRED,
    DISABLED,
    OFFLINE,
    SIGN_IN_REQUIRED,
    FAILED
}

fun quickConnectApprovalResult(status: Int, approved: Boolean?): QuickConnectApprovalResult = when {
    status in 200..299 && approved == true -> QuickConnectApprovalResult.APPROVED
    status in 200..299 && approved == false || status in setOf(400, 404, 410) -> QuickConnectApprovalResult.WRONG_OR_EXPIRED
    status == 401 -> QuickConnectApprovalResult.SIGN_IN_REQUIRED
    else -> QuickConnectApprovalResult.FAILED
}

/** Approval uses only the current viewer's token. No user override, password, secret, or target identity is invented. */
class QuickConnectApprovalClient(private val http: HttpClient, private val identity: ClientIdentity) {
    suspend fun authorize(account: Account, input: String): QuickConnectApprovalResult {
        if (!validQuickConnectCode(input)) return QuickConnectApprovalResult.INVALID_INPUT
        if (account.expired) return QuickConnectApprovalResult.SIGN_IN_REQUIRED
        val authorization = AuthorizationHeader.build(identity.forAccount(account), account.accessToken)
        return try {
            withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
                suspend fun enabled(): Boolean? {
                    val response = http.request("${account.server.url.trimEnd('/')}/QuickConnect/Enabled") {
                        headers.append(HttpHeaders.Authorization, authorization)
                    }
                    if (response.status.value !in 200..299) return null
                    return response.bodyAsText().trim().toBooleanStrictOrNull()
                }
                when (enabled()) {
                    false -> return@withTimeoutOrNull QuickConnectApprovalResult.DISABLED
                    null -> return@withTimeoutOrNull QuickConnectApprovalResult.FAILED
                    true -> Unit
                }
                val response = http.request("${account.server.url.trimEnd('/')}/QuickConnect/Authorize") {
                    method = HttpMethod.Post
                    url.parameters.append("code", normalizeQuickConnectCode(input))
                    headers.append(HttpHeaders.Authorization, authorization)
                }
                // Jellyfin also answers 401 when Quick Connect is turned off between Enabled and Authorize.
                if (response.status.value == 401 && enabled() == false) {
                    QuickConnectApprovalResult.DISABLED
                } else {
                    quickConnectApprovalResult(response.status.value, response.bodyAsText().trim().toBooleanStrictOrNull())
                }
            } ?: QuickConnectApprovalResult.OFFLINE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            QuickConnectApprovalResult.OFFLINE
        }
    }
}
