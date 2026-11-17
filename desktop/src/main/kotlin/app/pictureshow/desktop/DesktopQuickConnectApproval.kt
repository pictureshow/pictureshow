/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DesktopApprovalState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val result: QuickConnectApprovalResult? = null,
    /** The viewer the approval was made as, for the "Approved" line (the server doesn't say which device). */
    val approvedAs: String? = null
)

/**
 * AUTH-15: approve another device's Quick Connect code with the current viewer's own token. Validation, the Enabled
 * check and every outcome are core's [QuickConnectApprovalClient]; the code is never logged.
 */
class DesktopQuickConnectApproval(
    private val client: QuickConnectApprovalClient,
    private val scope: CoroutineScope,
    private val account: () -> Account?,
    private val log: (String) -> Unit = {}
) {
    private val mutable = MutableStateFlow(DesktopApprovalState())
    val state: StateFlow<DesktopApprovalState> = mutable.asStateFlow()
    private var job: Job? = null

    fun open() { mutable.value = DesktopApprovalState(open = true) }
    fun close() { job?.cancel(); mutable.value = DesktopApprovalState() }
    /** Editing the code clears the last outcome so it never describes a different code. */
    fun edited() { if (mutable.value.result != null) mutable.update { it.copy(result = null, approvedAs = null) } }

    fun approve(code: String) {
        val a = account() ?: return
        if (mutable.value.busy) return
        if (!validQuickConnectCode(code)) { mutable.update { it.copy(result = QuickConnectApprovalResult.INVALID_INPUT) }; return }
        mutable.update { it.copy(busy = true, result = null) }
        job = scope.launch {
            val result = client.authorize(a, code)
            log("quick connect approval result=${result.name.lowercase()}")
            mutable.update { it.copy(busy = false, result = result, approvedAs = a.user.name.takeIf { result == QuickConnectApprovalResult.APPROVED }) }
        }
    }
}

/** Plain copy for each outcome; never a raw status or server body. */
internal fun desktopApprovalText(result: QuickConnectApprovalResult, name: String?): String = when (result) {
    QuickConnectApprovalResult.APPROVED -> "Approved. The other device signs in as ${name.orEmpty()} in a moment."
    QuickConnectApprovalResult.INVALID_INPUT -> "Codes are 6 letters and numbers. Check the code on the other device."
    QuickConnectApprovalResult.WRONG_OR_EXPIRED -> "That code didn't work. It may have expired: the other device shows a new one by itself, so enter the code it shows now."
    QuickConnectApprovalResult.DISABLED -> "Quick Connect is turned off on this server. A server administrator can turn it on in the server's Dashboard."
    QuickConnectApprovalResult.OFFLINE -> "Pictureshow can't reach your server right now. Check your connection and try again."
    QuickConnectApprovalResult.SIGN_IN_REQUIRED -> "Your sign-in on this computer has ended. Sign in again, then approve the code."
    QuickConnectApprovalResult.FAILED -> "The server couldn't approve the code. Try again in a moment."
}
