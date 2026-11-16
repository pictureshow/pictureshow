/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.QuickConnectApprovalResult
import app.pictureshow.core.normalizeQuickConnectCode
import app.pictureshow.core.validQuickConnectCode
import kotlinx.coroutines.launch

/** The copy for each approval outcome (AUTH-15); never a raw HTTP status or server body. */
internal fun quickConnectApprovalMessage(result: QuickConnectApprovalResult): Int = when (result) {
    QuickConnectApprovalResult.APPROVED -> R.string.qc_approve_done
    QuickConnectApprovalResult.INVALID_INPUT -> R.string.qc_approve_invalid
    QuickConnectApprovalResult.WRONG_OR_EXPIRED -> R.string.qc_approve_wrong
    QuickConnectApprovalResult.DISABLED -> R.string.qc_approve_disabled
    QuickConnectApprovalResult.OFFLINE -> R.string.qc_approve_offline
    QuickConnectApprovalResult.SIGN_IN_REQUIRED -> R.string.qc_approve_sign_in
    QuickConnectApprovalResult.FAILED -> R.string.qc_approve_failed
}

/**
 * AUTH-15: the viewer signed in on this phone or tablet approves the code a TV shows, so the TV signs in as them.
 * Jellyfin's approval answers only yes or no, so success names the viewer, not the TV.
 */
@Composable
internal fun TouchQuickConnectApproval(
    account: Account,
    serverName: String,
    approve: suspend (Account, String) -> QuickConnectApprovalResult,
    onBack: () -> Unit
) {
    // A TextFieldValue, so the cursor stays after the last character when spaces are dropped; with a plain String the
    // keyboard's idea of the text and the field's disagree, and Gboard replaced the code mid-typing (OBSERVED, leg 162).
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val code = field.text
    var busy by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<QuickConnectApprovalResult?>(null) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (busy) return
        if (!validQuickConnectCode(code)) {
            result = QuickConnectApprovalResult.INVALID_INPUT
            return
        }
        busy = true
        result = null
        scope.launch {
            result = try {
                approve(account, code)
            } finally {
                busy = false
            }
        }
    }
    TouchPage(stringResource(R.string.qc_approve_title), onBack) {
        Text(
            stringResource(R.string.qc_approve_intro, account.user.name, serverName),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = field,
            onValueChange = { typed ->
                // Pasted codes may carry spaces or a newline; the field keeps at most what a code could be.
                val cleaned = normalizeQuickConnectCode(typed.text).take(6)
                field = if (cleaned == typed.text) typed else TextFieldValue(cleaned, TextRange(cleaned.length))
                if (result != QuickConnectApprovalResult.APPROVED) result = null
            },
            label = { Text(stringResource(R.string.qc_approve_field)) },
            singleLine = true,
            enabled = !busy,
            isError = result == QuickConnectApprovalResult.INVALID_INPUT,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            textStyle = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().testTag("qc-approve:code")
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = ::submit,
            enabled = !busy && code.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("qc-approve:submit")
        ) {
            Text(stringResource(if (busy) R.string.qc_approve_busy else R.string.qc_approve_action))
        }
        result?.let { outcome ->
            Spacer(Modifier.height(16.dp))
            Text(
                if (outcome == QuickConnectApprovalResult.APPROVED) {
                    stringResource(R.string.qc_approve_done, account.user.name)
                } else {
                    stringResource(quickConnectApprovalMessage(outcome))
                },
                color = if (outcome == QuickConnectApprovalResult.APPROVED) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("qc-approve:result").semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
