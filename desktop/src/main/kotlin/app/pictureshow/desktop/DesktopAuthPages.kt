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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import io.ktor.http.encodeURLPathPart

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

/**
 * What the sign-in and account pages ask of the app. [DesktopController] provides it through
 * [authActions]; the UI tests drive the same pages with a recorder.
 */
internal interface AuthActions {
    fun discover()
    fun connect(address: String)
    fun signIn(username: String, password: String)
    fun quickConnect()
    fun clearError()
    fun accounts()
    fun selectAccount(key: String)
    /** AUTH-07: sign another viewer in on a saved server, without asking for its address again. */
    fun addAccountOn(server: Server)
    fun addServer()
    fun signOut()
    fun back()
}

internal fun DesktopController.authActions(): AuthActions {
    val c = this
    return object : AuthActions {
        override fun discover() = c.discover()
        override fun connect(address: String) = c.connect(address)
        override fun signIn(username: String, password: String) = c.signIn(username, password)
        override fun quickConnect() = c.quickConnect()
        override fun clearError() = c.clearError()
        override fun accounts() = c.accounts()
        override fun selectAccount(key: String) = c.selectAccount(key)
        override fun addAccountOn(server: Server) = c.addAccountOn(server)
        override fun addServer() = c.addServer()
        override fun signOut() = c.signOut()
        override fun back() = c.back()
    }
}

/** AUTH-04: the copy for a username and password the server refused at sign-in. */
internal const val SIGN_OUT_PASSWORD_COPY = "You'll need the password to sign in again."
internal const val SIGN_OUT_CONSEQUENCE_COPY = "$SIGN_OUT_PASSWORD_COPY Your account stays saved on this computer."
internal const val WRONG_CREDENTIALS_COPY = "That username and password didn't work. Check them and try again."
/** AUTH-04: how many public users the picker shows before "Show all N users". */
internal const val PUBLIC_USERS_SHOWN = 8

@Composable
internal fun ServerPage(state: DesktopState, c: DesktopController) = ServerForm(state, remember(c) { c.authActions() })

@Composable
internal fun ServerForm(state: DesktopState, c: AuthActions) {
    LaunchedEffect(Unit) { c.discover() }
    var address by remember { mutableStateOf("") }
    var missing by remember { mutableStateOf(false) }
    val addressFocus = remember { FocusRequester() }
    val submit = {
        if (address.isBlank()) { missing = true; addressFocus.requestFocus() } else if (!state.busy) c.connect(address)
    }
    FormPage("Your library, on your desktop", "Connect to your Jellyfin server to start watching.") {
        OutlinedTextField(
            address, { address = it; if (it.isNotBlank()) missing = false },
            label = { Text("Server address") },
            supportingText = { Text(if (missing) "Enter your server's address." else "For example, https://jellyfin.example.org") },
            isError = missing, singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(addressFocus).probe("server-address")
        )
        Button(onClick = { c.connect(address) }, enabled = address.isNotBlank() && !state.busy, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("connect")) { Text(if (state.busy) "Connecting…" else "Connect") }
        Text("On your network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        state.rememberedServers.forEach { s -> OutlinedButton(onClick = { c.connect(s.url) }, modifier = Modifier.controlFocus()) { Text(serverLabel(s, state.rememberedServers)) } }
        state.discovered.forEach { s -> OutlinedButton(onClick = { c.connect(s.address) }, modifier = Modifier.controlFocus()) { Text(s.name) } }
        Text(if (state.discovering) "Looking for Jellyfin servers…" else if(state.discovered.isEmpty()) "No server found yet. You can always enter its address above." else "Select a server to connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        InlineTextAction("Search again", "discover", c::discover, enabled = !state.discovering)
        if (state.accounts.isNotEmpty()) InlineTextAction("Remembered accounts", "remembered-accounts", c::accounts)
    }
}

@Composable
internal fun SignInPage(state: DesktopState, c: DesktopController) = SignInForm(state, remember(c) { c.authActions() })

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignInForm(state: DesktopState, c: AuthActions) {
    val serverKey = state.server?.let { it.id to it.url }
    val savedUsername = state.signInUsername
    var username by remember(serverKey, savedUsername) { mutableStateOf(savedUsername.orEmpty()) }
    var password by remember(serverKey, savedUsername) { mutableStateOf(TextFieldValue("")) }
    var picked by remember(serverKey, savedUsername) { mutableStateOf(savedUsername) }
    var missing by remember(serverKey, savedUsername) { mutableStateOf(false) }
    var allUsers by remember(serverKey, savedUsername) { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val firstRevealedUser = remember { FocusRequester() }
    val credentialsRejected = state.errorKind == ErrorKind.INVALID_CREDENTIALS
    LaunchedEffect(credentialsRejected, state.busy) {
        if (credentialsRejected && !state.busy) {
            passwordFocus.requestFocus()
            password = password.copy(selection = TextRange(0, password.text.length))
        }
    }
    LaunchedEffect(state.busy) { if (!state.busy) submitted = false }
    // A sign-in that opens with no username starts there, whatever this page last had focused (a new account after an
    // earlier sign-in left Password as the page's remembered focus). Fields are disabled while busy, so wait for idle.
    val page = LocalDesktopPage.current
    LaunchedEffect(serverKey, savedUsername, state.busy) {
        if (!state.busy && !credentialsRejected) {
            if (!savedUsername.isNullOrBlank() && username == savedUsername) {
                withFrameNanos { }
                DesktopUiProbe.lastFocus[page] = "password"
                passwordFocus.requestFocus()
            } else if (username.isBlank()) {
                DesktopUiProbe.lastFocus.remove(page)
                runCatching { usernameFocus.requestFocus() }
            }
        }
    }
    val submit = {
        if (username.isBlank()) { missing = true; usernameFocus.requestFocus() }
        else if (!state.busy) { submitted = true; c.signIn(username, password.text) }
    }
    val confirmedPick = picked?.takeIf { it == username }
    FormPage("Sign in", state.server?.let { serverLabel(it, state.rememberedServers) } ?: "Your Jellyfin server") {
        OutlinedTextField(
            username, { username = it; if (it.isNotBlank()) missing = false; c.clearError() },
            label = { Text("Username") },
            supportingText = if (missing) ({ Text("Enter your username.") }) else null,
            isError = missing, singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus).probe("username")
        )
        if (confirmedPick != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.probe("signing-in-as")) {
            Text("Signing in as ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(confirmedPick, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { picked = null; username = ""; c.clearError(); usernameFocus.requestFocus() }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.controlFocus().probe("not-you")) { Text("Not you?") }
        }
        OutlinedTextField(password, { value ->
            // Cursor/selection changes are not a corrected credential. Keep the
            // rejection visible until the viewer actually edits the password.
            val edited = value.text != password.text
            password = value
            if (edited) c.clearError()
        }, label = { Text("Password") },
            supportingText = if (credentialsRejected) ({ Text(WRONG_CREDENTIALS_COPY) }) else null,
            isError = credentialsRejected, singleLine = true,
            visualTransformation = if(show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }, modifier = Modifier.controlFocus().semantics { contentDescription = if (show) "Hide password" else "Show password" }) { Text(if(show) "Hide" else "Show") } },
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).probe("password")
        )
        Button(onClick = { submit() }, enabled = username.isNotBlank() && !state.busy, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("sign-in")) { Text(if (state.busy && submitted) "Signing in…" else "Sign in") }
        OutlinedButton(onClick = c::quickConnect, enabled = !state.busy, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("quick-connect")) { Text("Use Quick Connect") }
        val users = state.publicUsers
        if (users.isNotEmpty()) {
            HorizontalDivider()
            Text("Or choose your name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            // Chips otherwise pad themselves to a 48 dp touch target, which spaces a pointer-and-keyboard list unevenly.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = if (allUsers || users.size <= PUBLIC_USERS_SHOWN) users else users.take(PUBLIC_USERS_SHOWN)
                shown.forEachIndexed { i, u ->
                    FilterChip(
                        selected = confirmedPick == u.name,
                        onClick = { username = u.name; picked = u.name; missing = false; c.clearError(); passwordFocus.requestFocus() },
                        label = { Text(u.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        enabled = !state.busy,
                        modifier = Modifier.controlFocus(RoundedCornerShape(8.dp)).then(if (i == PUBLIC_USERS_SHOWN) Modifier.focusRequester(firstRevealedUser) else Modifier).probe("user:${u.id}").semantics { contentDescription = u.name }
                    )
                }
                if (users.size > PUBLIC_USERS_SHOWN && !allUsers) {
                    TextButton(onClick = { allUsers = true }, modifier = Modifier.controlFocus().heightIn(min = 32.dp).probe("show-all-users")) { Text("Show all ${users.size} users") }
                }
            }
            }
            // The button that was focused goes away: keep focus where the list grew.
            LaunchedEffect(allUsers) { if (allUsers && users.size > PUBLIC_USERS_SHOWN) firstRevealedUser.requestFocus() }
        }
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

/**
 * The letters an account's avatar shows when the server has no picture for it: the first letter of the first and the
 * last word, so "ux-tom" and "ux-mei" read UT and UM rather than both U. Separators are spaces, dots, dashes,
 * underscores and @.
 */
internal fun accountInitials(name: String): String {
    val words = name.trim().split(Regex("[\\s._@-]+")).filter { w -> w.any(Char::isLetterOrDigit) }
    if (words.isEmpty()) return "?"
    fun letter(w: String) = w.first(Char::isLetterOrDigit).uppercase()
    return if (words.size == 1) letter(words[0]) else letter(words.first()) + letter(words.last())
}

/** Jellyfin serves a viewer's own picture without sign-in, as its login screen does. A 404 leaves the initials showing. */
internal fun userImageUrl(server: Server, user: User, size: Int = 96): String =
    "${server.url.trimEnd('/')}/Users/${user.id.encodeURLPathPart()}/Images/Primary?maxWidth=$size&maxHeight=$size&quality=90"

@Composable
internal fun AccountAvatar(account: Account) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
        Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Text(accountInitials(account.user.name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.probe("initials:${account.key}"))
            coil3.compose.AsyncImage(model = userImageUrl(account.server, account.user), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun AccountsPage(state: DesktopState, controller: DesktopController, onRemove: (Account) -> Unit) =
    AccountsList(state, remember(controller) { controller.authActions() }, onFocusRestored = controller::returnFocusDone, onRemove = onRemove)

@Composable
internal fun AccountsList(state: DesktopState, c: AuthActions, onFocusRestored: () -> Unit = {}, onRemove: (Account) -> Unit) {
    val servers = (state.rememberedServers + state.accounts.map { it.server }).distinctBy { it.id to it.url }
    val page = LocalDesktopPage.current
    LaunchedEffect(state.busy, state.account?.key, state.accounts.map { it.key }, state.returnFocus) {
        val preferred = state.returnFocus?.takeIf { tag -> state.accounts.any { "account:${it.key}" == tag } }
        if (!state.busy && preferred != null) {
            withFrameNanos { }
            DesktopUiProbe.requestFocus[preferred]?.let { requester ->
                DesktopUiProbe.lastFocus[page] = preferred
                requester.requestFocus()
                onFocusRestored()
            }
            return@LaunchedEffect
        }
        // The shell restores a page's last focus when it settles. On this page that could be a Remove button (after an
        // earlier removal dialog) or Sign out, which is gone once used: start on the current or first account instead.
        val last = DesktopUiProbe.lastFocus[page]
        if (!state.busy && (last == null || last.startsWith("remove:") || last == "accounts-sign-out" || (last.startsWith("account:") && state.accounts.none { "account:${it.key}" == last }))) {
            val first = state.accounts.firstOrNull { it.key == state.account?.key } ?: state.accounts.firstOrNull()
            if (first != null) DesktopUiProbe.lastFocus[page] = "account:${first.key}" else DesktopUiProbe.lastFocus.remove(page)
        }
    }
    FormPage("Who's watching?", "Choose an account, or add one. Removing an account only forgets it on this computer.") {
        state.account?.let { current ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp).probe("current-account"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Signed in as ${current.user.name} on ${serverLabel(current.server, servers)}.")
                    Text(SIGN_OUT_CONSEQUENCE_COPY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = c::signOut, enabled = !state.busy, modifier = Modifier.controlFocus().heightIn(min = 40.dp).probe("accounts-sign-out")) { Text("Sign out") }
            }
        }
        servers.forEach { s ->
            val label = serverLabel(s, servers)
            val onServer = state.accounts.filter { it.server.id == s.id && it.server.url == s.url }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                onServer.forEach { a -> AccountRow(a, current = a.key == state.account?.key, select = { c.selectAccount(a.key) }, remove = { onRemove(a) }) }
                OutlinedButton(onClick = { c.addAccountOn(s) }, enabled = !state.busy, modifier = Modifier.controlFocus().heightIn(min = 40.dp).probe("add-account:${s.id}")) {
                    Text(if (onServer.isEmpty()) "Sign in on $label" else "Sign in another user on $label", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OutlinedButton(onClick = c::addServer, modifier = Modifier.controlFocus().heightIn(min = 40.dp).probe("add-server")) { Text("Add a server") }
    }
}

@Composable
private fun AccountRow(a: Account, current: Boolean, select: () -> Unit, remove: () -> Unit) {
    val rowHover = remember { MutableInteractionSource() }
    val rowHovered by rowHover.collectIsHoveredAsState()
    var removeFocused by remember { mutableStateOf(false) }
    val removeHover = remember { MutableInteractionSource() }
    val removeHovered by removeHover.collectIsHoveredAsState()
    Row(
        Modifier.controlFocus(RoundedCornerShape(12.dp)).fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .hoverable(rowHover)
            // probe before clickable: its FocusRequester binds the next focus target, which must be the row, not Remove inside it.
            .probe("account:${a.key}").clickable(role = Role.Button, onClick = select)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AccountAvatar(a)
        Column(Modifier.weight(1f)) {
            Text(a.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (current) "Watching now" else if (a.expired || a.accessToken.isBlank()) "Signed out · Sign in again to continue" else "Saved on this computer",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!current && (a.expired || a.accessToken.isBlank())) {
            Icon(DesktopGlyph.ChevronRight, contentDescription = "Sign in as ${a.user.name}",
                modifier = Modifier.size(20.dp).probe("account-sign-in:${a.key}"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The icon alone didn't read as Remove (D-15): the word shows while the row or the button has the pointer or
        // focus. The button keeps its width so nothing shifts when it appears.
        TextButton(
            onClick = remove,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.controlFocus().width(112.dp).heightIn(min = 40.dp).hoverable(removeHover).onFocusChanged { removeFocused = it.isFocused }
                .probe("remove:${a.key}").semantics { contentDescription = "Remove ${a.user.name}" }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Remove", modifier = Modifier.probe("remove-label:${a.key}")); Spacer(Modifier.width(8.dp))
                Icon(DesktopGlyph.Remove, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}
