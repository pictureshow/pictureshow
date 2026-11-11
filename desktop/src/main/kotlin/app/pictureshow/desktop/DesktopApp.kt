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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.ui.*
import java.util.concurrent.ConcurrentHashMap

/** Geometry of real controls, used by the finite Robot smoke driver, never by normal input handling. */
private val LocalDesktopPage = staticCompositionLocalOf { "" }
private val LocalModalOpen = staticCompositionLocalOf { false }
object DesktopUiProbe {
    internal val owners = DesktopControlOwners()
    val bounds = ConcurrentHashMap<String, androidx.compose.ui.geometry.Rect>()
    val focus = ConcurrentHashMap<String, Boolean>()
    val pageOfTag = ConcurrentHashMap<String, String>()
    val requestFocus = ConcurrentHashMap<String, FocusRequester>()
    val lastFocus = ConcurrentHashMap<String, String>()
    val reveal = ConcurrentHashMap<String, () -> Unit>()
}
fun Modifier.probe(tag: String): Modifier = composed {
    val owner = remember { Any() }
    val requester = remember { BringIntoViewRequester() }
    val focusRequester = remember { FocusRequester() }
    val page = LocalDesktopPage.current
    val scope = rememberCoroutineScope()
    DisposableEffect(tag) {
        DesktopUiProbe.owners.register(tag, owner) {
            DesktopUiProbe.bounds.remove(tag)
            DesktopUiProbe.focus[tag] = false
            DesktopUiProbe.pageOfTag[tag] = page
            DesktopUiProbe.requestFocus[tag] = focusRequester
            DesktopUiProbe.reveal[tag] = { scope.launch { requester.bringIntoView() } }
        }
        onDispose { DesktopUiProbe.owners.release(tag, owner) {
            DesktopUiProbe.bounds.remove(tag); DesktopUiProbe.focus.remove(tag); DesktopUiProbe.reveal.remove(tag); DesktopUiProbe.requestFocus.remove(tag); DesktopUiProbe.pageOfTag.remove(tag)
        } }
    }
    focusRequester(focusRequester).bringIntoViewRequester(requester).testTag(tag).onGloballyPositioned { DesktopUiProbe.owners.update(tag, owner) { DesktopUiProbe.bounds[tag] = it.boundsInWindow(); DesktopUiProbe.pageOfTag[tag] = page } }
        .onFocusChanged { DesktopUiProbe.owners.update(tag, owner) { DesktopUiProbe.focus[tag] = it.isFocused; if(it.isFocused && page.isNotBlank()) DesktopUiProbe.lastFocus[page] = tag } }
}
@Composable
private fun Modifier.focusOutline(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val drawRing = focused && !LocalModalOpen.current
    return onFocusChanged { focused = it.hasFocus }.border(if (drawRing) 2.dp else 0.dp, if (drawRing) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
}

/** A focus ring outside the control fill stays visible on primary and tonal controls. */
@Composable
internal fun Modifier.controlFocus(shape: Shape = RoundedCornerShape(50), allowInModal: Boolean = false): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val modalOpen = LocalModalOpen.current
    return onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused && (!modalOpen || allowInModal)) {
            val gap = 4.dp.toPx()
            inset(-gap, -gap, -gap, -gap) {
                drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun DesktopTooltip(label: String, above: Boolean = false, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp)) {
                Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
        delayMillis = 500,
        // Keep the cursor provider used by the qualified resize/hover flow. The
        // component-coordinate provider reproduced a disposed popup scene on
        // Windows; an explicit cursor gap preserves visibility without it.
        tooltipPlacement = TooltipPlacement.CursorPoint(
            alignment = if (above) Alignment.TopCenter else Alignment.BottomCenter,
            offset = DpOffset(0.dp, if (above) (-24).dp else 24.dp)
        ),
        content = content
    )
}

@Composable
private fun ShortcutRow(keys: String, action: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(4.dp)) {
            Text(keys, Modifier.width(160.dp).padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textAlign = TextAlign.Center, maxLines = 1)
        }
        Text(action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DesktopApp(controller: DesktopController, onFullscreen: () -> Unit, fullscreen: Boolean, windowKeys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean) -> Unit,
    macChrome: Boolean = false,
    chrome: @Composable (@Composable () -> Unit) -> Unit = { it() },
    windowControls: @Composable RowScope.() -> Unit = {}) {
    val state by controller.state.collectAsState()
    // Semantics and tooltip callbacks may outlive this composition during sign-out.

    val sidebarAccount = state.account
    val pageKey = state.route.name + when(state.route) {
        DesktopRoute.Library -> ":${state.library?.id}:${state.sortBy}:${state.descending}"
        DesktopRoute.Detail -> ":${state.detail?.id}"
        else -> ""
    }
    val savedPages = rememberSaveableStateHolder()
    LaunchedEffect(pageKey, state.busy) {
        if(!state.busy) {
            delay(150)
            if(DesktopUiProbe.focus.entries.none { (tag, focused) -> focused && DesktopUiProbe.pageOfTag[tag] == pageKey }) {
                val target = DesktopUiProbe.lastFocus[pageKey] ?: when(state.route) {
                    DesktopRoute.Servers -> "server-address"
                    DesktopRoute.SignIn -> "username"
                    DesktopRoute.Detail -> "play"
                    DesktopRoute.Player -> "pause"
                    DesktopRoute.Home -> state.homeRows.firstOrNull()?.let { row -> row.items.firstOrNull()?.let { "card:${row.key}:${it.id}" } }
                    DesktopRoute.Library -> state.items.firstOrNull()?.let { "grid:${it.id}" }
                    else -> null
                }
                target?.let { runCatching { DesktopUiProbe.requestFocus[it]?.requestFocus() } }
            }
        }
    }
    val focus = LocalFocusManager.current
    var help by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Account?>(null) }
    var modalReturnFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(help, remove) {
        delay(100)
        val modal = when {
            help -> "help-close"
            remove != null -> "remove-cancel"
            else -> null
        }
        if (modal != null) DesktopUiProbe.requestFocus[modal]?.requestFocus()
        else modalReturnFocus?.let { DesktopUiProbe.requestFocus[it]?.requestFocus(); modalReturnFocus = null }
    }
    PictureshowTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * if (state.settings.largeInterface) 1.25f else 1f, density.fontScale),
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            // Window shortcuts remain active when resize or pointer input clears a
            // child's focus, and when focus is in the custom title bar.
            SideEffect { windowKeys { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when {
                        e.key == Key.F11 || (e.key == Key.Enter && e.isAltPressed) || (macChrome && e.key == Key.F && e.isCtrlPressed && e.isMetaPressed) -> { onFullscreen(); true }
                        e.key == Key.Escape && help -> { help = false; true }
                        e.key == Key.Escape && remove != null -> { remove = null; true }
                        (help || remove != null) && e.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> true
                        e.key == Key.Escape && fullscreen -> { onFullscreen(); true }
                        e.key == Key.Escape -> { controller.back(); true }
                        state.route == DesktopRoute.Player && !help && remove == null && e.key == Key.Spacebar -> { controller.playerToggle(); true }
                        state.route == DesktopRoute.Player && e.key == Key.DirectionLeft && !e.isAltPressed -> { controller.seek((state.playback?.positionSeconds ?: 0.0) - 10); true }
                        state.route == DesktopRoute.Player && e.key == Key.DirectionRight && !e.isAltPressed -> { controller.seek((state.playback?.positionSeconds ?: 0.0) + 10); true }
                        state.route in listOf(DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.Accounts) && e.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> {
                            focus.moveFocus(when(e.key) { Key.DirectionLeft -> FocusDirection.Left; Key.DirectionRight -> FocusDirection.Right; Key.DirectionUp -> FocusDirection.Up; else -> FocusDirection.Down }); true
                        }
                        else -> false
                    }
            } }
            CompositionLocalProvider(LocalModalOpen provides (help || remove != null)) {
            Column(Modifier.fillMaxSize().background(if (state.route == DesktopRoute.Player) Color.Transparent else MaterialTheme.colorScheme.background)) {
                chrome {
                Row(Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surface).padding(start = if(macChrome) 80.dp else 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PICTURESHOW", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(20.dp))
                    if (state.route in setOf(DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Detail, DesktopRoute.Player)) TextButton(onClick = controller::back, modifier = Modifier.controlFocus().probe("back")) { Icon(DesktopGlyph.Back, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back") }
                    Spacer(Modifier.weight(1f))
                    DesktopUtility("Keyboard shortcuts", DesktopGlyph.Keyboard, "shortcuts") { modalReturnFocus = "shortcuts"; help = true }
                    DesktopUtility(if(fullscreen) "Exit fullscreen" else "Fullscreen", DesktopGlyph.Fullscreen, "fullscreen", onFullscreen)
                    windowControls()
                }
                }
                Row(Modifier.weight(1f)) {
                    if (sidebarAccount != null && state.route !in listOf(DesktopRoute.Player, DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Accounts, DesktopRoute.Servers)) {
                        Column(Modifier.width(184.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sidebarAccount.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

                            DesktopTooltip(sidebarAccount.server.name) {

                                Text(sidebarAccount.server.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = sidebarAccount.server.name })
                            }
                            NavigationDrawerItem(label = { Text("Home", maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = state.route == DesktopRoute.Home, onClick = controller::home, modifier = Modifier.controlFocus().probe("nav:home"))
                            state.homeRows.filter { it.library }.flatMap { it.items }.forEach { library ->
                                NavigationDrawerItem(label = { Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = state.library?.id == library.id && state.route == DesktopRoute.Library, onClick = { controller.browse(library) }, modifier = Modifier.controlFocus().probe("nav:library:${library.id}"))
                            }
                            Spacer(Modifier.weight(1f))
                            NavigationDrawerItem(label = { Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = state.route == DesktopRoute.Settings, onClick = controller::settings, modifier = Modifier.controlFocus().probe("nav:settings"))
                            NavigationDrawerItem(label = { Text("Accounts", maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = false, onClick = controller::accounts, modifier = Modifier.controlFocus().probe("nav:accounts"))
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        state.error?.takeIf { state.errorKind != ErrorKind.INVALID_CREDENTIALS }?.let { error ->
                            Card(Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    if (state.route == DesktopRoute.Loading || (state.errorKind in setOf(ErrorKind.NETWORK, ErrorKind.SERVER) && state.route in setOf(DesktopRoute.Servers, DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.QuickConnect))) TextButton(onClick = controller::retry, enabled = !state.busy, modifier = Modifier.controlFocus().probe("retry")) { Text("Retry") }
                                }
                            }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" })
                        Box(Modifier.fillMaxSize()) {
                            savedPages.SaveableStateProvider(pageKey) {
                            CompositionLocalProvider(LocalDesktopPage provides pageKey) {
                            when (state.route) {
                                DesktopRoute.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                DesktopRoute.Servers -> ServerPage(state, controller)
                                DesktopRoute.SignIn -> SignInPage(state, controller)
                                DesktopRoute.QuickConnect -> FormPage("Quick Connect", "Approve this desktop from a signed-in Jellyfin app.") {
                                    Text(state.quickCode?.toCharArray()?.joinToString(" ") ?: "Getting your code…", style = MaterialTheme.typography.displayLarge, modifier = Modifier.probe("quick-code").semantics { liveRegion = LiveRegionMode.Polite })
                                    Text("In Jellyfin, open Settings → Quick Connect and enter this code. Keep this window open while you approve.")
                                    InlineTextAction("Use username and password", "quick-back", controller::back)
                                }
                                DesktopRoute.Accounts -> FormPage("Who's watching?", "Choose an account or add one.") {
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
                                                IconButton(onClick = { modalReturnFocus = "remove:${a.key}"; remove = a }, modifier = Modifier.controlFocus().probe("remove:${a.key}").semantics { contentDescription = "Remove ${a.user.name}" }) {
                                                    Icon(DesktopGlyph.Remove, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    OutlinedButton(onClick = controller::addServer, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("add-server")) { Text("Add account") }
                                }
                                DesktopRoute.Home -> HomePage(state, controller)
                                DesktopRoute.Library -> LibraryPage(state, controller)
                                DesktopRoute.Detail -> DetailPage(state, controller)
                                DesktopRoute.Player -> PlayerPage(state, controller, onFullscreen)
                                DesktopRoute.Settings -> SettingsPage(state, controller)
                            }
                            }
                            }
                        }
                    }
                }
            }
            if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Keyboard shortcuts") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Anywhere", style = MaterialTheme.typography.titleSmall)
                ShortcutRow(if (macChrome) "Tab / ⇧Tab" else "Tab / Shift+Tab", "Move between controls")
                ShortcutRow("↑ ↓ ← →", "Browse cards and menus")
                ShortcutRow("Enter / Space", "Activate focused control")
                ShortcutRow("Esc", "Close a dialog, exit fullscreen, or go back")
                ShortcutRow(if (macChrome) "⌃⌘F" else "F11 / Alt+Enter", "Fullscreen")
                HorizontalDivider()
                Text("During playback", style = MaterialTheme.typography.titleSmall)
                ShortcutRow("Space", "Pause or play")
                ShortcutRow("← / →", "Seek 10 seconds")
                Text("Playback actions are also available in the control bar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, confirmButton = { TextButton(onClick = { help = false }, modifier = Modifier.controlFocus(allowInModal = true).probe("help-close")) { Text("Close") } })
            remove?.let { a ->
                AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${a.user.name}?") }, text = { Text("${a.user.name} will be removed from this computer. You can sign in again any time.") }, dismissButton = { TextButton(onClick = { remove = null }, modifier = Modifier.controlFocus(allowInModal = true).probe("remove-cancel")) { Text("Cancel") } }, confirmButton = { TextButton(onClick = { modalReturnFocus = null; remove = null; controller.removeAccount(a.key) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.controlFocus(allowInModal = true).probe("remove-confirm")) { Text("Remove account") } }) }
            }
        }
    }
}

@Composable
private fun FormPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun ServerPage(state: DesktopState, c: DesktopController) {
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
private fun SignInPage(state: DesktopState, c: DesktopController) {
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
private fun HomePage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).probe("home"), state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { CatalogHeading("Home") }
        if (!state.busy && state.homeRows.isEmpty()) item { Text("Nothing to show yet. Add media in Jellyfin, then refresh."); InlineTextAction("Refresh", "home-refresh", c::home) }
        items(state.homeRows.sortedBy { if (it.library) 1 else 0 }, key = { it.key }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogHeading(row.title, section = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(row.items, key = { it.id }) { item ->
                        CatalogCard(item, account, row.wide || row.library, { c.open(item) }, Modifier.width(if(row.wide || row.library) 232.dp else 156.dp).focusOutline().probe("card:${row.key}:${item.id}"), announcement = catalogAnnouncement(item), tag = "card:${row.key}:${item.id}")
                    }
                }
            }
        }
    }
    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
@Composable
private fun LibraryPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val grid = rememberLazyGridState()
    LaunchedEffect(state.library?.id, state.sortBy, state.descending) { grid.scrollToItem(0) }
    LaunchedEffect(grid, state.items.size) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if(it >= state.items.size-12 && !state.busy && state.items.size < state.total) c.loadMore() } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CatalogHeading(state.library?.name ?: "Library", Modifier.weight(1f))
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Choice("Sort", listOf("SortName" to "Title", "DateCreated" to "Date added", "PremiereDate" to "Release date"), state.sortBy, "sort") { c.browse(state.library!!, it, state.descending) }
                    OutlinedButton(onClick = { c.browse(state.library!!,state.sortBy,!state.descending) }, modifier = Modifier.controlFocus().probe("sort-direction")) { Text(if(state.descending) "Descending ↓" else "Ascending ↑") }
                }
            }
        }
        Text("${state.total} items", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.items.isEmpty() && !state.busy) Text("This library is empty.")
        Box(Modifier.weight(1f)) {
        LazyVerticalGrid(GridCells.FixedSize(156.dp), state = grid, modifier = Modifier.fillMaxSize().padding(end = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.items, key = { it.id }) { item -> CatalogCard(item,account,false,{c.open(item)},Modifier.focusOutline().probe("grid:${item.id}"), announcement = catalogAnnouncement(item), tag = "grid:${item.id}") }
        }
        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
@Composable
private fun DetailPage(state: DesktopState, c: DesktopController) {
    val item = state.detail ?: return
    val account = state.account ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Detail content width excludes the persistent sidebar and top chrome; this
    // breakpoint corresponds to a full window around 1440 dp wide.
    val expansive = maxWidth >= 1120.dp
    val detailState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).probe("detail"), state = detailState, contentPadding = PaddingValues(start = if (expansive) 40.dp else 24.dp, top = if (expansive) 40.dp else 24.dp, end = if (expansive) 40.dp else 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val isEpisode = item.type == "Episode"
                val posterWidth = when { isEpisode && expansive -> 480.dp; isEpisode -> 360.dp; expansive -> 240.dp; else -> 180.dp }
                CatalogArtwork(item,account,isEpisode,Modifier.width(posterWidth).aspectRatio(if (isEpisode) 16f / 9f else 2f / 3f))
                Column(Modifier.widthIn(min=280.dp).weight(1f),verticalArrangement=Arrangement.spacedBy(if (expansive) 18.dp else 14.dp)) {
                    Text(item.name, style = if (expansive) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(listOfNotNull(item.productionYear?.toString(),item.officialRating,runtimeLabel(item.runTimeTicks).takeIf{it.isNotBlank()},episodeLabel(item).takeIf{item.type=="Episode" && it.isNotBlank()}).joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={c.play(item)},enabled=!state.busy,modifier = Modifier.controlFocus().heightIn(min=48.dp).probe("play")) { Text(if(item.userData.playbackPositionTicks>0) resumeLabel(item.userData.playbackPositionTicks) else "Play") }
                        if(item.userData.playbackPositionTicks>0) OutlinedButton(onClick={c.play(item,true)},modifier = Modifier.controlFocus().heightIn(min=48.dp).probe("play-start")) { Text("Play from start") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopTooltip(if (item.isWatched) "Mark unwatched" else "Mark watched") {
                            IconToggleButton(checked = item.isWatched, onCheckedChange = { c.toggleWatched() }, modifier = Modifier.controlFocus().probe("watched").semantics { contentDescription = "Watched"; stateDescription = if (item.isWatched) "Watched" else "Not watched" }) {
                                Icon(if (item.isWatched) DesktopGlyph.Watched else DesktopGlyph.WatchedOutline, contentDescription = null, tint = if (item.isWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DesktopTooltip(if (item.userData.isFavorite) "Remove favorite" else "Add to favorites") {
                            IconToggleButton(checked = item.userData.isFavorite, onCheckedChange = { c.toggleFavorite() }, modifier = Modifier.controlFocus().probe("favorite").semantics { contentDescription = "Favorite"; stateDescription = if (item.userData.isFavorite) "Favorite" else "Not favorite" }) {
                                Icon(if (item.userData.isFavorite) DesktopGlyph.Favorite else DesktopGlyph.FavoriteOutline, contentDescription = null, tint = if (item.userData.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        }
                    }
                    item.overview?.let { Text(it, Modifier.widthIn(max = 640.dp), style=MaterialTheme.typography.bodyLarge) }
                    if(item.genres.isNotEmpty()) Text(item.genres.joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(state.seasons.isNotEmpty()) item {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CatalogHeading("Seasons",section=true)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) { items(state.seasons, key={it.id}) { s -> FilterChip(selected=state.selectedSeason?.id==s.id,onClick={c.selectSeason(s)},label={Text(s.name)},modifier=Modifier.probe("season:${s.id}")) } }
            }
        }
        if(state.episodes.isNotEmpty()) item { CatalogHeading("Episodes",section=true) }
        items(state.episodes,key={it.id}) { episode ->
            OutlinedCard(onClick={c.detail(episode)},modifier=Modifier.fillMaxWidth().focusOutline().probe("episode:${episode.id}")) {
                Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    CatalogArtwork(episode,account,true,Modifier.width(160.dp).height(90.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("${episodeLabel(episode)} · ${episode.name}",style=MaterialTheme.typography.titleMedium); Text(episode.overview.orEmpty(),maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(runtimeLabel(episode.runTimeTicks))
                }
            }
        }
    }
    VerticalScrollbar(rememberScrollbarAdapter(detailState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    }
}
@Composable
fun PlayerPage(state: DesktopState, c: DesktopController, fullscreen: () -> Unit) {
    val p = state.playback ?: return
    var playerBottom by remember(p.decision) { mutableStateOf(0.0) }
    var videoTop by remember(p.decision) { mutableStateOf(0.0) }
    var videoBottom by remember(p.decision) { mutableStateOf(0.0) }
    LaunchedEffect(p.decision, playerBottom, videoTop, videoBottom) {
        if (playerBottom > 0.0 && videoBottom > videoTop) c.videoViewport(videoTop / playerBottom, (playerBottom - videoBottom) / playerBottom)
    }
    Column(Modifier.fillMaxSize().onGloballyPositioned { playerBottom = (it.positionInWindow().y + it.size.height).toDouble() }) {
        Spacer(Modifier.weight(1f).onGloballyPositioned {
            videoTop = it.positionInWindow().y.toDouble()
            videoBottom = videoTop + it.size.height
        })
        p.notice?.let { notice ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ⓘ", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notice.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(notice.reason, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(notice.fix, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(p.item.name,style=MaterialTheme.typography.titleMedium)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(clock(p.positionSeconds),Modifier.width(72.dp),textAlign=TextAlign.End,style=MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"))
                    Slider(value=p.positionSeconds.toFloat().coerceIn(0f,p.durationSeconds.coerceAtLeast(1.0).toFloat()),onValueChange={c.seek(it.toDouble())},valueRange=0f..p.durationSeconds.coerceAtLeast(1.0).toFloat(),modifier=Modifier.controlFocus(RoundedCornerShape(12.dp)).weight(1f).probe("seek").semantics { contentDescription="Playback position" })
                    Text(clock(p.durationSeconds),Modifier.width(72.dp),textAlign=TextAlign.Start,style=MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"))
                }

                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopTooltip("Back 10 seconds", above = true) { IconButton(onClick={c.seek((p.positionSeconds-10).coerceAtLeast(0.0))},modifier = Modifier.controlFocus().probe("seek-back")) { Icon(DesktopGlyph.TransportBack,contentDescription="Back 10 seconds") } }
                        DesktopTooltip(if (p.paused) "Play" else "Pause", above = true) { FilledIconButton(onClick=c::playerToggle,modifier = Modifier.controlFocus().probe("pause")) { Icon(if (p.paused) DesktopGlyph.Play else DesktopGlyph.Pause, contentDescription = if (p.paused) "Play" else "Pause") } }
                        DesktopTooltip("Forward 10 seconds", above = true) { IconButton(onClick={c.seek(p.positionSeconds+10)},modifier = Modifier.controlFocus().probe("seek-forward")) { Icon(DesktopGlyph.TransportForward,contentDescription="Forward 10 seconds") } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Audio",p.audioTracks.zip(trackLabels(p.audioTracks)).map{(track,label)->track.index to label},p.audioIndex,"audio",buttonValue = p.audioTracks.firstOrNull { it.index == p.audioIndex }?.let(::shortTrackLabel) ?: "Off", buttonWidth = 156.dp, fixedWidth = true, onChoose = c::selectAudio, icon = DesktopGlyph.Audio)
                        Choice("Subtitles",listOf(-1 to "Off")+p.subtitleTracks.zip(trackLabels(p.subtitleTracks)).map{(track,label)->track.index to label},p.subtitleIndex,"subtitles",buttonValue = if (p.subtitleIndex == -1) "Off" else p.subtitleTracks.firstOrNull { it.index == p.subtitleIndex }?.let(::shortTrackLabel) ?: "Off", buttonWidth = 156.dp, fixedWidth = true, onChoose = c::selectSubtitle, icon = DesktopGlyph.Subtitles)
                        DesktopTooltip("Fullscreen", above = true) { IconButton(onClick=fullscreen,modifier = Modifier.controlFocus().probe("player-fullscreen")) { Icon(DesktopGlyph.Fullscreen,contentDescription="Fullscreen") } }
                        if(p.nextEpisode!=null) OutlinedButton(onClick=c::nextEpisode,modifier = Modifier.controlFocus().heightIn(min=40.dp).probe("next-episode")) { Text("Next episode") }
                        DesktopTooltip("Stop playback", above = true) { IconButton(onClick=c::stopPlayback,modifier = Modifier.controlFocus().probe("stop")) { Icon(DesktopGlyph.Stop,contentDescription="Stop playback") } }
                    }

                }
                if(!p.ready) Text("Opening video…",modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            }
        }
    }
}
private fun clock(seconds:Double):String { val s=seconds.toLong().coerceAtLeast(0); return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60) }
@Composable
private fun SettingsPage(state:DesktopState,c:DesktopController) {
    val s=state.settings
    FormPage("Settings","Playback and viewing preferences for this desktop.") {
        SettingsSection("Account") {
            SettingRow(state.account?.user?.name ?: "Signed out", state.account?.server?.name ?: "No account is connected") {
                OutlinedButton(onClick = c::signOut, modifier = Modifier.controlFocus().heightIn(min = 40.dp).probe("sign-out")) { Text("Sign out") }
            }
        }
        SettingsSection("Playback") {
            SettingRow("Video quality", "Lower it if playback stutters on a slow connection.") {
                Choice("Video quality",listOf(0 to "Original / best compatible",4 to "4 Mbps",10 to "10 Mbps",20 to "20 Mbps",40 to "40 Mbps",80 to "80 Mbps"),s.bitrateMbps?:0,"quality",buttonValue = if ((s.bitrateMbps ?: 0) == 0) "Original" else "${s.bitrateMbps} Mbps", buttonWidth = 180.dp, trailingIcon = DesktopGlyph.ChevronDown, fixedWidth = true) { c.saveSettings(s.copy(bitrateMbps=it.takeIf{it>0})) }
            }
            SettingSwitch("Explain video conversion","Tell me when this desktop needs a different video format.",s.conversionNotice,"conversion") { c.saveSettings(s.copy(conversionNotice=it)) }
            SettingSwitch("Play next episode","Continue to the next episode when this one ends.",s.autoplayNext,"autoplay") { c.saveSettings(s.copy(autoplayNext=it)) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${s.volume}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(s.volume.toFloat(),{c.saveSettings(s.copy(volume=it.toInt()))},valueRange=0f..100f,modifier=Modifier.controlFocus(RoundedCornerShape(12.dp)).probe("volume").semantics { contentDescription="Volume" })
            }
        }
        SettingsSection("Interface") {
            SettingSwitch("Living-room size","Larger text and controls for watching from a distance.",s.largeInterface,"large-interface") { c.saveSettings(s.copy(largeInterface=it)) }
        }
        SettingsSection("About") {
            Text("Pictureshow · Free, open source Jellyfin client\nMPL-2.0 · Legitimate LLC\nVideo: dynamically loaded LGPL libmpv",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CatalogHeading(title, section = true)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
@Composable
private fun SettingRow(title: String, caption: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
@Composable
private fun SettingSwitch(title:String,caption:String,value:Boolean,tag:String,onChange:(Boolean)->Unit) {
    SettingRow(title, caption) { Switch(value,onChange,Modifier.controlFocus().probe(tag).semantics { contentDescription=title }) }
}
@Composable
private fun <T> Choice(label:String,items:List<Pair<T,String>>,selected:T,tag:String,buttonValue:String?=null,buttonWidth:Dp=560.dp,icon:ImageVector?=null,trailingIcon:ImageVector?=DesktopGlyph.ChevronDown,fixedWidth:Boolean=false,onChoose:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},modifier = Modifier.controlFocus().then(if (fixedWidth) Modifier.width(buttonWidth) else Modifier.widthIn(max=buttonWidth)).probe(tag).semantics { contentDescription = "$label: ${items.firstOrNull{it.first==selected}?.second ?: "Default"}" }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if(icon!=null) { Icon(icon,contentDescription=null,modifier=Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                Text(buttonValue ?: "$label · ${items.firstOrNull{it.first==selected}?.second ?: "Default"}",modifier=Modifier.weight(1f, fill=false),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=MaterialTheme.typography.labelLarge.fontWeight)
                if (trailingIcon != null) { Spacer(Modifier.width(8.dp)); Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(16.dp)) }
            }
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) { items.forEach { (key,text) -> DropdownMenuItem(text={Text(text)},onClick={expanded=false;onChoose(key)},leadingIcon={RadioButton(selected==key,null)},modifier=Modifier.controlFocus(RoundedCornerShape(4.dp)).probe("$tag:$key")) } }
    }
}

@Composable
private fun DesktopUtility(label: String, glyph: androidx.compose.ui.graphics.vector.ImageVector, tag: String, action: () -> Unit) {
    DesktopTooltip(label) {
        IconButton(onClick=action, modifier = Modifier.controlFocus().probe(tag)) { Icon(glyph, label, tint=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun InlineTextAction(label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe(tag)) { Text(label) }
}

private fun shortTrackLabel(stream: app.pictureshow.core.playback.MediaStream): String =
    trackLabel(stream).substringBefore(" · ").ifBlank { "Track ${stream.index + 1}" }
