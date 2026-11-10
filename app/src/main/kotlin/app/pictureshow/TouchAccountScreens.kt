/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.pictureshow.core.Account
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.Server
import app.pictureshow.core.serverLabel

@Composable
internal fun TouchServers(
    state: ServerState,
    snapshot: AccountSnapshot,
    onAddress: (String) -> Unit,
    onConnect: (String) -> Unit,
    onRemembered: (Server) -> Unit,
    onScan: () -> Unit,
    onAccounts: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    fun connect() {
        keyboard?.hide()
        onConnect(state.address)
    }
    TouchPage("Welcome to Pictureshow", onBack) {
        Text("Your Jellyfin library, wherever you watch.", style = MaterialTheme.typography.titleMedium)
        Text("Connect to your server to get started.")
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddress,
            label = { Text("Server address") },
            placeholder = { Text("https://media.example.com") },
            singleLine = true,
            enabled = !state.connecting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.fillMaxWidth().testTag("touch:server-address")
        )
        TouchAction(if (state.connecting) "Connecting…" else "Connect", enabled = !state.connecting) { connect() }
        TouchMessage(state.error)
        if (snapshot.rememberedServers.isNotEmpty()) {
            Text("Remembered servers", style = MaterialTheme.typography.titleLarge)
            snapshot.rememberedServers.forEach { server ->
                TouchAction("Sign in to ${snapshot.serverLabel(server)}", enabled = !state.connecting) { onRemembered(server) }
            }
        }
        Text("On your network", style = MaterialTheme.typography.titleLarge)
        state.discovered.forEach { server ->
            TouchAction("${server.name} · ${server.address}", enabled = !state.connecting) { onConnect(server.address) }
        }
        Text(
            when {
                state.scanning -> "Looking for servers…"
                state.discovered.isEmpty() -> "No servers found. Enter an address above, or search again."
                else -> "Choose a server above."
            },
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        TouchAction("Search again", enabled = !state.scanning, onClick = onScan)
        if (snapshot.accounts.isNotEmpty() ||
            snapshot.rememberedServers.isNotEmpty()
        ) {
            TouchAction("Remembered accounts", onClick = onAccounts)
        }
    }
}

@Composable
internal fun TouchLogin(
    state: LoginState,
    server: Server,
    sessionEnded: Boolean,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSignIn: () -> Unit,
    onQuickConnect: () -> Unit,
    onBack: () -> Unit,
    serverLabel: String = server.name
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        keyboard?.hide()
        onSignIn()
    }
    TouchPage("Sign in to $serverLabel", onBack) {
        if (sessionEnded) TouchMessage("Your session ended. Sign in again.")
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsername,
            label = { Text("Username") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().testTag("touch:username")
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                TextButton(onClick = { reveal = !reveal }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (reveal) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("touch:password")
        )
        TouchAction(if (state.busy) "Signing in…" else "Sign in", enabled = !state.busy) { submit() }
        TouchMessage(state.error)
        TouchMessage(state.notice)
        if (state.loading) Text("Loading sign-in options…")
        if (state.quickConnect) TouchAction("Use Quick Connect", enabled = !state.busy, onClick = onQuickConnect)
        if (state.users.isNotEmpty()) {
            Text("Public users", style = MaterialTheme.typography.titleLarge)
            state.users.forEach { user -> TouchAction(user.name, enabled = !state.busy) { onUsername(user.name) } }
        }
    }
}

@Composable
internal fun TouchQuickConnect(
    state: QuickState,
    server: Server,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    serverLabel: String = server.name
) {
    TouchPage("Quick Connect", onBack) {
        Text(serverLabel, style = MaterialTheme.typography.titleLarge)
        Text("On another signed-in Jellyfin app, open Settings → Quick Connect and enter this code.")
        state.code?.let { code ->
            Text(
                code,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.testTag("touch:quick-code").semantics {
                    contentDescription = "Quick Connect code ${code.toList().joinToString(" ")}"
                    liveRegion = LiveRegionMode.Polite
                }
            )
        }
        if (state.busy) Text("Getting a code…")
        if (state.renewed) Text("The previous code expired. Enter the new code above.")
        if (state.disabled) {
            Text("Quick Connect is disabled on this server. Go back to use your username and password.")
        } else if (!state.busy && state.error == null) {
            Text("Waiting for approval…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        TouchMessage(state.error)
        if (state.error != null) TouchAction("Try again", onClick = onRetry)
    }
}

/**
 * The one way forward on Accounts: the current account, else the first remembered one. Null when there are none, and
 * then adding a server is the way forward.
 */
internal fun touchAccountsPrimary(snapshot: AccountSnapshot): String? =
    snapshot.accounts.firstOrNull { it.key == snapshot.currentKey }?.key ?: snapshot.accounts.firstOrNull()?.key

@Composable
internal fun TouchAccounts(
    state: AccountsState,
    snapshot: AccountSnapshot,
    onSelect: (Account) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onRemoveServer: (Server) -> Unit,
    onSignIn: (Server) -> Unit,
    onAddServer: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    var removeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var removeServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeServerUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val account = snapshot.accounts.firstOrNull { it.key == removeKey }
    val server = snapshot.rememberedServers.firstOrNull { it.id == removeServerId && it.url == removeServerUrl }
    fun dismiss() {
        removeKey = null
        removeServerId = null
        removeServerUrl = null
    }
    LaunchedEffect(snapshot) {
        if (removeKey != null && account == null) removeKey = null
        if (removeServerId != null && server == null) {
            removeServerId = null
            removeServerUrl = null
        }
    }
    val primary = touchAccountsPrimary(snapshot)
    TouchPage("Accounts", onBack) {
        if (snapshot.accounts.isEmpty()) Text("No remembered accounts. Sign in to start watching.")
        snapshot.accounts.forEach { remembered ->
            Text("${remembered.user.name} · ${snapshot.serverLabel(remembered.server)}", style = MaterialTheme.typography.titleMedium)
            val label = when {
                remembered.expired -> "Sign in again as ${remembered.user.name}"
                snapshot.currentKey == remembered.key -> "Continue as ${remembered.user.name}"
                else -> "Switch to ${remembered.user.name}"
            }
            if (remembered.key == primary) {
                TouchAction(label, Modifier.testTag("touch:accounts:primary"), enabled = !state.busy) { onSelect(remembered) }
            } else {
                TouchSecondaryAction(label, !state.busy) { onSelect(remembered) }
            }
            TouchRemoveAction("Remove ${remembered.user.name} on ${snapshot.serverLabel(remembered.server)}", !state.busy) {
                removeKey = remembered.key
            }
        }
        TouchMessage(state.error)
        snapshot.rememberedServers.forEach { remembered ->
            Text(snapshot.serverLabel(remembered), style = MaterialTheme.typography.titleLarge)
            TouchSecondaryAction("Sign in another user on ${snapshot.serverLabel(remembered)}", !state.busy) { onSignIn(remembered) }
            TouchRemoveAction("Remove server ${snapshot.serverLabel(remembered)}", !state.busy) {
                removeServerId = remembered.id
                removeServerUrl = remembered.url
            }
        }
        if (primary == null) {
            TouchAction("Add a server", Modifier.testTag("touch:accounts:primary"), enabled = !state.busy, onAddServer)
        } else {
            TouchSecondaryAction("Add a server", !state.busy, onAddServer)
        }
    }
    if (account != null || server != null) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) dismiss() },
            properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
            title = { Text(if (account != null) "Remove remembered account?" else "Remove server?") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        if (account != null) {
                            "Remove ${account.user.name} on ${snapshot.serverLabel(account.server)} from this device? " +
                                "You can sign in again later."
                        } else {
                            "Remove ${snapshot.serverLabel(server!!)} and its remembered accounts from this device? " +
                                "You can add it again later."
                        }
                    )
                    Text("Downloads saved for it on this device are deleted.")
                    TouchMessage(state.error)
                    if (state.busy) Text("Removing…")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = { if (account != null) onRemoveAccount(account.key) else server?.let(onRemoveServer) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("touch:confirm-removal")
                ) { Text(if (account != null) "Remove account" else "Remove server", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { dismiss() }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        )
    }
}
