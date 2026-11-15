/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)


package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.ui.*
import java.util.concurrent.ConcurrentHashMap

@Composable
internal fun FormPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxHeight().verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 32.dp)) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 32.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CatalogHeading(title)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
@Composable
internal fun ServerPage(state: DesktopState, c: DesktopController) {
    LaunchedEffect(Unit) { c.discover() }
    var address by remember { mutableStateOf("") }
    FormPage("Your library, on your desktop", "Connect to your Jellyfin server to start watching.") {
        OutlinedTextField(address, { address = it }, label = { Text("Server address") }, supportingText = { Text("For example, https://jellyfin.example.org") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().probe("server-address"), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if(address.isNotBlank()) c.connect(address) }))
        Button(onClick = { c.connect(address) }, enabled = address.isNotBlank() && !state.busy, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("connect")) { Text(if (state.busy) "Connecting…" else "Connect") }
        Text("On your network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        state.rememberedServers.forEach { s -> OutlinedButton(onClick = { c.connect(s.url) }, modifier = Modifier.controlFocus()) { Text(s.name) } }
        state.discovered.forEach { s -> OutlinedButton(onClick = { c.connect(s.address) }, modifier = Modifier.controlFocus()) { Text(s.name) } }
        Text(if (state.discovering) "Looking for Jellyfin servers…" else if(state.discovered.isEmpty()) "No server found yet. You can always enter its address above." else "Select a server to connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        InlineTextAction("Search again", "discover", c::discover, enabled = !state.discovering)
        if (state.accounts.isNotEmpty()) InlineTextAction("Remembered accounts", "remembered-accounts", c::accounts)
    }
}
@Composable
internal fun SignInPage(state: DesktopState, c: DesktopController) {
    var username by remember(state.server?.id) { mutableStateOf("") }
    var password by remember(state.server?.id) { mutableStateOf(TextFieldValue("")) }
    var show by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val credentialsRejected = state.errorKind == ErrorKind.INVALID_CREDENTIALS
    LaunchedEffect(credentialsRejected, state.busy) {
        if (credentialsRejected && !state.busy) {
            passwordFocus.requestFocus()
            password = password.copy(selection = TextRange(0, password.text.length))
        }
    }
    FormPage("Sign in", state.server?.name ?: "Your Jellyfin server") {
        if (state.publicUsers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.publicUsers.take(4).forEach { u -> AssistChip(modifier = Modifier.controlFocus(), onClick = { username = u.name; c.clearError() }, label = { Text(u.name) }) } }
        OutlinedTextField(username, { username = it; c.clearError() }, label = { Text("Username") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().probe("username"))
        OutlinedTextField(password, { value ->
            // Cursor/selection changes are not a corrected credential. Keep the
            // rejection visible until the viewer actually edits the password.
            val edited = value.text != password.text
            password = value
            if (edited) c.clearError()
        }, label = { Text("Password") }, supportingText = if (credentialsRejected) ({ Text("Wrong username or password") }) else null, isError = credentialsRejected, singleLine = true, visualTransformation = if(show) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { show = !show }, modifier = Modifier.controlFocus()) { Text(if(show) "Hide" else "Show") } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).probe("password"), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { c.signIn(username,password.text) }))
        Button(onClick = { c.signIn(username, password.text) }, enabled = username.isNotBlank() && !state.busy, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("sign-in")) { Text("Sign in") }
        HorizontalDivider()
        OutlinedButton(onClick = c::quickConnect, enabled = !state.busy, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("quick-connect")) { Text("Use Quick Connect") }
    }
}

@Composable
internal fun QuickConnectPage(state: DesktopState, controller: DesktopController) {
    FormPage("Quick Connect", "Approve this computer from a signed-in Jellyfin app.") {
        Text(state.quickCode?.toCharArray()?.joinToString(" ") ?: "Getting your code…", style = MaterialTheme.typography.displayLarge, modifier = Modifier.probe("quick-code").semantics { liveRegion = LiveRegionMode.Polite })
        Text("In Jellyfin, open Settings → Quick Connect and enter this code. Keep this window open while you approve.")
        InlineTextAction("Use username and password", "quick-back", controller::back)
    }
}

@Composable
internal fun AccountsPage(state: DesktopState, controller: DesktopController, onRemove: (Account) -> Unit) {
    FormPage("Who's watching?", "Choose an account or add one.") {
        state.accounts.forEach { a ->
            Row(
                Modifier.controlFocus(RoundedCornerShape(12.dp)).fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { controller.selectAccount(a.key) }.probe("account:${a.key}")
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(a.user.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(a.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.server.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DesktopTooltip("Remove account") {
                    IconButton(onClick = { onRemove(a) }, modifier = Modifier.controlFocus().probe("remove:${a.key}").semantics { contentDescription = "Remove ${a.user.name}" }) {
                        Icon(DesktopGlyph.Remove, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedButton(onClick = controller::addServer, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("add-server")) { Text("Add account") }
    }
}
