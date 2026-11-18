/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*

/**
 * The PIN field: digits only, masked, Enter submits. Desktop's equivalent of the remote's number pad is the keyboard's
 * number keys, so it is one field rather than a grid of buttons. It clears after each submission ([tries]).
 */
@Composable
internal fun DesktopPinField(tries: Int, enabled: Boolean, tag: String, label: String = "PIN", onSubmit: (String) -> Unit) {
    var pin by remember(tries) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        pin, { pin = desktopPinInput(it) }, enabled = enabled, singleLine = true, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (pin.isNotEmpty()) onSubmit(pin) }),
        modifier = Modifier.width(280.dp).focusRequester(focus).probe(tag)
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && pin.isNotEmpty()) { onSubmit(pin); true } else false }
            .semantics { contentDescription = "$label, ${pin.length} digits entered" }
    )
    LaunchedEffect(tries, enabled) { if (enabled) { withFrameNanos { }; runCatching { focus.requestFocus() } } }
    Spacer(Modifier.height(4.dp))
    Button(onClick = { onSubmit(pin) }, enabled = enabled && pin.isNotEmpty(), modifier = Modifier.controlFocus(allowInModal = true).probe("$tag-ok")) { Text("OK") }
}

/** The whole-window lock (AUTH-16/18): nothing of the viewer composes behind it. */
@Composable
internal fun DesktopPinGate(c: DesktopController, modifier: Modifier = Modifier) {
    val pins by c.pins.state.collectAsState()
    val gate = pins.gate ?: return
    Box(modifier.fillMaxSize().probe("pin-gate"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(desktopPinTitle(gate), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text("This viewer has a PIN on this computer.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            DesktopPinField(gate.tries, enabled = !gate.busy && !gate.lockedOut && gate.waitSeconds == 0, tag = "pin-gate-field", onSubmit = c.pins::submit)
            desktopPinMessage(gate)?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            TextButton(onClick = c::lockedUsePassword, modifier = Modifier.controlFocus().probe("pin-gate-password")) { Text("Forgot the PIN? Sign in with the password") }
            TextButton(onClick = c::lockedSwitchUser, modifier = Modifier.controlFocus().probe("pin-gate-switch")) { Text("Switch user") }
        }
    }
}

/** Dialogs over a page: the switch question from the chooser, the current-PIN check in Settings and the new-PIN pad. */
@Composable
internal fun DesktopPinDialogs(c: DesktopController) {
    val pins by c.pins.state.collectAsState()
    pins.prompt?.let { prompt ->
        AlertDialog(onDismissRequest = c.pins::cancelPrompt, modifier = Modifier.probe("pin-dialog"),
            title = { Text(desktopPinTitle(prompt)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopPinField(prompt.tries, enabled = !prompt.busy && !prompt.lockedOut && prompt.waitSeconds == 0, tag = "pin-dialog-field", onSubmit = c.pins::submit)
                    desktopPinMessage(prompt)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (prompt.purpose == DesktopPinPurpose.SWITCH) TextButton(onClick = { c.pins.cancelPrompt(); c.signInAgain(prompt.key) }, modifier = Modifier.controlFocus(allowInModal = true).probe("pin-dialog-password")) { Text("Forgot the PIN? Sign in with the password") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = c.pins::cancelPrompt, modifier = Modifier.controlFocus(allowInModal = true).probe("pin-dialog-cancel")) { Text("Cancel") } })
    }
    pins.setup?.let { setup ->
        AlertDialog(onDismissRequest = c.pins::cancelSetup, modifier = Modifier.probe("pin-setup"),
            title = { Text(if (setup.first == null) "Choose a PIN" else "Type the PIN again") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (setup.first == null) "$PROFILE_PIN_MIN to $PROFILE_PIN_MAX digits. Pictureshow asks for it before anyone opens ${setup.name} on this computer."
                        else "Type the same PIN again to turn it on for ${setup.name}.")
                    DesktopPinField(setup.tries, enabled = true, tag = "pin-setup-field", label = desktopPinSetupLabel(setup), onSubmit = c.pins::setupEnter)
                    desktopPinSetupMessage(setup)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = c.pins::cancelSetup, modifier = Modifier.controlFocus(allowInModal = true).probe("pin-setup-cancel")) { Text("Cancel") } })
    }
}

/** Settings rows for the current viewer's PIN (AUTH-16/19). Each viewer sets its own while it is the one signed in. */
@Composable
internal fun ProfilePinSettingsSection(state: DesktopState, c: DesktopController) {
    val account = state.account?.takeIf { it.signedIn } ?: return
    val pins by c.pins.state.collectAsState()
    if (pins.unlocked != account.key) return
    val lock = pins.locks[account.key]
    val name = account.user.name
    SettingsSection("Profile PIN") {
        SettingRow(if (lock == null) "Set a PIN" else "Change PIN", if (lock == null) "Off. Anyone using this computer can open $name." else "On. Asked when switching to $name.") {
            OutlinedButton(onClick = { c.pins.beginSetOrChange(account) }, modifier = Modifier.controlFocus().probe("settings:pin")) { Text(if (lock == null) "Set a PIN" else "Change PIN") }
        }
        if (lock != null) {
            SettingSwitch("Ask when Pictureshow opens", if (lock.askOnStart) "On: the PIN is asked every time Pictureshow starts on $name." else "Off: asked only when someone switches to $name.", lock.askOnStart, "settings:pin-ask-on-open") { c.pins.setAskOnStart(account, it) }
            SettingRow("Turn off PIN", "The PIN stays on this computer. Ratings, libraries and viewing hours are set for each user on your Jellyfin server.") {
                OutlinedButton(onClick = { c.pins.beginRemove(account) }, modifier = Modifier.controlFocus().probe("settings:pin-remove")) { Text("Turn off") }
            }
        } else Text("Five minutes or more away from Pictureshow (minimized, or in another app while nothing plays) counts as opening it again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pins.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** AUTH-15: Settings → Approve a sign-in code. */
@Composable
internal fun QuickConnectApprovalSection(state: DesktopState, c: DesktopController) {
    val account = state.account?.takeIf { it.signedIn } ?: return
    val approval by c.approval.state.collectAsState()
    SettingRow("Approve a sign-in code", "Enter the code a TV or another device shows to sign it in as you") {
        OutlinedButton(onClick = c.approval::open, modifier = Modifier.controlFocus().probe("settings:approve")) { Text("Approve a code") }
    }
    if (approval.open) {
        var code by remember { mutableStateOf("") }
        val field = remember { FocusRequester() }
        AlertDialog(onDismissRequest = c.approval::close, modifier = Modifier.probe("approve-dialog"),
            title = { Text("Approve a sign-in code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("On the TV or other device, choose Use Quick Connect. Enter the 6-character code it shows to sign it in as ${account.user.name} on ${account.server.name}.")
                    OutlinedTextField(code, { code = it.take(32); c.approval.edited() }, singleLine = true, label = { Text("Code") }, enabled = !approval.busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { c.approval.approve(code) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field).probe("approve-code"))
                    approval.result?.let { Text(desktopApprovalText(it, approval.approvedAs), color = if (it == QuickConnectApprovalResult.APPROVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("approve-result")) }
                }
            },
            confirmButton = { Button(onClick = { c.approval.approve(code) }, enabled = !approval.busy && code.isNotBlank(), modifier = Modifier.controlFocus(allowInModal = true).probe("approve-submit")) { Text(if (approval.busy) "Approving…" else "Approve") } },
            dismissButton = { TextButton(onClick = c.approval::close, modifier = Modifier.controlFocus(allowInModal = true).probe("approve-close")) { Text(if (approval.result == QuickConnectApprovalResult.APPROVED) "Done" else "Cancel") } })
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
    }
}

/** The setup field's label: the second entry is a confirmation, not another new PIN (pilot 172). */
internal fun desktopPinSetupLabel(setup: DesktopPinSetup): String = if (setup.first == null) "New PIN" else "Confirm PIN"
