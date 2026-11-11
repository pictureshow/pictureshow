/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.TooltipArea
import androidx.compose.ui.window.TooltipPlacement
import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.ui.*
import java.util.concurrent.ConcurrentHashMap

/** Geometry of real controls, used by the finite Robot smoke driver, never by normal input handling. */
private val LocalDesktopPage = staticCompositionLocalOf { "" }
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
    return onFocusChanged { focused = it.hasFocus }.border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
}

@Composable
fun DesktopApp(controller: DesktopController, onFullscreen: () -> Unit, fullscreen: Boolean, windowKeys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean) -> Unit,
    macChrome: Boolean = false,
    chrome: @Composable (@Composable () -> Unit) -> Unit = { it() },
    windowControls: @Composable RowScope.() -> Unit = {}) {
    val state by controller.state.collectAsState()
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
    PictureshowTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * if (state.settings.largeInterface) 1.25f else 1f, density.fontScale)) {
            // Window shortcuts remain active when resize or pointer input clears a
            // child's focus, and when focus is in the custom title bar.
            SideEffect { windowKeys { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when {
                        e.key == Key.F11 || (e.key == Key.Enter && e.isAltPressed) -> { onFullscreen(); true }
                        e.key == Key.Escape && help -> { help = false; true }
                        e.key == Key.Escape && remove != null -> { remove = null; true }
                        e.key == Key.Escape && fullscreen -> { onFullscreen(); true }
                        e.key == Key.Escape -> { controller.back(); true }
                        state.route == DesktopRoute.Player && e.key == Key.Spacebar -> { controller.playerToggle(); true }
                        state.route == DesktopRoute.Player && e.key == Key.DirectionLeft && !e.isAltPressed -> { controller.seek((state.playback?.positionSeconds ?: 0.0) - 10); true }
                        state.route == DesktopRoute.Player && e.key == Key.DirectionRight && !e.isAltPressed -> { controller.seek((state.playback?.positionSeconds ?: 0.0) + 10); true }
                        state.route in listOf(DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.Accounts) && e.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> {
                            focus.moveFocus(when(e.key) { Key.DirectionLeft -> FocusDirection.Left; Key.DirectionRight -> FocusDirection.Right; Key.DirectionUp -> FocusDirection.Up; else -> FocusDirection.Down }); true
                        }
                        else -> false
                    }
            } }
            Column(Modifier.fillMaxSize().background(if (state.route == DesktopRoute.Player) Color.Transparent else MaterialTheme.colorScheme.background)) {
                chrome {
                Row(Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surface).padding(start = if(macChrome) 80.dp else 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PICTURESHOW", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(20.dp))
                    if (state.route in setOf(DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Detail, DesktopRoute.Player)) TextButton(onClick = controller::back, modifier = Modifier.probe("back")) { Icon(DesktopGlyph.Back, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { help = true }, modifier = Modifier.probe("shortcuts")) { Text("Keyboard shortcuts") }
                    TextButton(onClick = onFullscreen, modifier = Modifier.probe("fullscreen")) { Text(if (fullscreen) "Exit fullscreen" else "Fullscreen") }
                    windowControls()
                }
                }
                Row(Modifier.weight(1f)) {
                    if (state.account != null && state.route !in listOf(DesktopRoute.Player, DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Accounts, DesktopRoute.Servers)) {
                        Column(Modifier.width(184.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.account!!.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            TooltipArea(tooltip = { Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp)) { Text(state.account!!.server.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.inverseOnSurface) } }, delayMillis = 500, tooltipPlacement = TooltipPlacement.CursorPoint) {
                                Text(state.account!!.server.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = state.account!!.server.name })
                            }
                            NavigationDrawerItem(label = { Text("Home") }, selected = state.route == DesktopRoute.Home, onClick = controller::home, modifier = Modifier.probe("nav:home"))
                            state.homeRows.filter { it.library }.flatMap { it.items }.forEach { library ->
                                NavigationDrawerItem(label = { Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, selected = state.library?.id == library.id && state.route == DesktopRoute.Library, onClick = { controller.browse(library) }, modifier = Modifier.probe("nav:library:${library.id}"))
                            }
                            Spacer(Modifier.weight(1f))
                            NavigationDrawerItem(label = { Text("Settings") }, selected = state.route == DesktopRoute.Settings, onClick = controller::settings, modifier = Modifier.probe("nav:settings"))
                            NavigationDrawerItem(label = { Text("Switch user") }, selected = false, onClick = controller::accounts, modifier = Modifier.probe("nav:accounts"))
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        state.error?.takeIf { state.errorKind != ErrorKind.INVALID_CREDENTIALS }?.let { error ->
                            Card(Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    if (state.errorKind in setOf(ErrorKind.NETWORK, ErrorKind.SERVER) && state.route in setOf(DesktopRoute.Loading, DesktopRoute.Servers, DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.QuickConnect)) TextButton(onClick = controller::retry, enabled = !state.busy, modifier = Modifier.probe("retry")) { Text("Retry") }
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
                                    TextButton(onClick = controller::back, modifier = Modifier.probe("quick-back")) { Text("Use username and password") }
                                }
                                DesktopRoute.Accounts -> FormPage("Who's watching?", "Choose a saved viewer or connect another server.") {
                                    state.accounts.forEach { a ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(onClick = { controller.selectAccount(a.key) }, modifier = Modifier.weight(1f).probe("account:${a.key}")) { Text("${a.user.name} · ${a.server.name}") }
                                            TextButton(onClick = { remove = a }, modifier = Modifier.probe("remove:${a.key}")) { Text("Remove") }
                                        }
                                    }
                                    Button(onClick = controller::addServer, modifier = Modifier.probe("add-server")) { Text("Add a server / sign in another user") }
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
            if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Keyboard shortcuts") }, text = { Text("Tab / Shift+Tab · Move between controls\nArrow keys · Browse cards and menus\nEnter / Space · Activate focused control\nEscape · Close menu, exit fullscreen, then go back\nF11 / Alt+Enter · Fullscreen\nDuring playback: Space · Pause / play\nLeft / Right · Seek 10 seconds\nAll playback actions are also available in the control bar.") }, confirmButton = { TextButton(onClick = { help = false }, modifier = Modifier.probe("help-close")) { Text("Close") } })
            remove?.let { a ->
                val cancelFocus = remember(a.key) { FocusRequester() }
                LaunchedEffect(a.key) { delay(100); cancelFocus.requestFocus() }
                AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${a.user.name}?") }, text = { Text("Forget this viewer on ${a.server.name} on this desktop. Your Jellyfin account and other saved viewers stay available.") }, dismissButton = { TextButton(onClick = { remove = null }, modifier = Modifier.focusRequester(cancelFocus).probe("remove-cancel")) { Text("Cancel") } }, confirmButton = { TextButton(onClick = { remove = null; controller.removeAccount(a.key) }, modifier = Modifier.probe("remove-confirm")) { Text("Remove viewer") } }) }
        }
    }
}

@Composable
private fun FormPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CatalogHeading(title)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
@Composable
private fun ServerPage(state: DesktopState, c: DesktopController) {
    LaunchedEffect(Unit) { c.discover() }
    var address by remember { mutableStateOf("") }
    FormPage("Your library, on your desktop", "Connect to your Jellyfin server to start watching.") {
        OutlinedTextField(address, { address = it }, label = { Text("Server address") }, supportingText = { Text("For example, https://jellyfin.example.org") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().probe("server-address"), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if(address.isNotBlank()) c.connect(address) }))
        Button(onClick = { c.connect(address) }, enabled = address.isNotBlank() && !state.busy, modifier = Modifier.heightIn(min = 48.dp).probe("connect")) { Text(if (state.busy) "Connecting…" else "Connect") }
        Text("On your network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        state.rememberedServers.forEach { s -> OutlinedButton(onClick = { c.connect(s.url) }) { Text(s.name) } }
        state.discovered.forEach { s -> OutlinedButton(onClick = { c.connect(s.address) }) { Text(s.name ?: s.address) } }
        Text(if (state.discovering) "Looking for Jellyfin servers…" else if(state.discovered.isEmpty()) "No server found yet. You can always enter its address above." else "Select a server to connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = c::discover, enabled = !state.discovering, modifier = Modifier.probe("discover")) { Text("Search again") }
        if (state.accounts.isNotEmpty()) TextButton(onClick = c::accounts) { Text("Remembered accounts") }
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
        if (state.publicUsers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.publicUsers.take(4).forEach { u -> AssistChip(onClick = { username = u.name; c.clearError() }, label = { Text(u.name) }) } }
        OutlinedTextField(username, { username = it; c.clearError() }, label = { Text("Username") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().probe("username"))
        OutlinedTextField(password, { password = it; c.clearError() }, label = { Text("Password") }, supportingText = if (credentialsRejected) ({ Text("Wrong username or password") }) else null, isError = credentialsRejected, singleLine = true, visualTransformation = if(show) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { show = !show }) { Text(if(show) "Hide" else "Show") } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).probe("password"), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { c.signIn(username,password.text) }))
        Button(onClick = { c.signIn(username, password.text) }, enabled = username.isNotBlank() && !state.busy, modifier = Modifier.heightIn(min = 48.dp).probe("sign-in")) { Text("Sign in") }
        HorizontalDivider()
        OutlinedButton(onClick = c::quickConnect, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).probe("quick-connect")) { Text("Use Quick Connect") }
    }
}
@Composable
private fun HomePage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    LazyColumn(Modifier.fillMaxSize().probe("home"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { CatalogHeading("Home") }
        if (!state.busy && state.homeRows.isEmpty()) item { Text("Nothing to show yet. Add media in Jellyfin, then refresh."); TextButton(onClick=c::home) { Text("Refresh") } }
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
}
@Composable
private fun LibraryPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val grid = rememberLazyGridState()
    LaunchedEffect(state.library?.id, state.sortBy, state.descending) { grid.scrollToItem(0) }
    LaunchedEffect(grid, state.items.size) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if(it >= state.items.size-12 && !state.busy && state.items.size < state.total) c.loadMore() } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CatalogHeading(state.library?.name ?: "Library", Modifier.weight(1f))
            Choice("Sort", listOf("SortName" to "Title", "DateCreated" to "Date added", "PremiereDate" to "Release date"), state.sortBy, "sort") { c.browse(state.library!!, it, state.descending) }
            OutlinedButton(onClick = { c.browse(state.library!!,state.sortBy,!state.descending) }, modifier = Modifier.probe("sort-direction")) { Text(if(state.descending) "Descending ↓" else "Ascending ↑") }
        }
        Text("${state.total} items", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.items.isEmpty() && !state.busy) Text("This library is empty.")
        LazyVerticalGrid(GridCells.Adaptive(156.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.items, key = { it.id }) { item -> CatalogCard(item,account,false,{c.open(item)},Modifier.focusOutline().probe("grid:${item.id}"), artHeight = 228.dp, announcement = catalogAnnouncement(item), tag = "grid:${item.id}") }
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
    LazyColumn(Modifier.fillMaxSize().probe("detail"), contentPadding = PaddingValues(if (expansive) 40.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                CatalogArtwork(item,account,false,Modifier.width(if (expansive) 240.dp else 180.dp).aspectRatio(2f / 3f))
                Column(Modifier.widthIn(min=280.dp).weight(1f),verticalArrangement=Arrangement.spacedBy(if (expansive) 18.dp else 14.dp)) {
                    Text(item.name, style = if (expansive) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(listOfNotNull(item.productionYear?.toString(),item.officialRating,runtimeLabel(item.runTimeTicks).takeIf{it.isNotBlank()},episodeLabel(item).takeIf{item.type=="Episode" && it.isNotBlank()}).joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={c.play(item)},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).probe("play")) { Text(if(item.userData.playbackPositionTicks>0) resumeLabel(item.userData.playbackPositionTicks) else "Play") }
                        if(item.userData.playbackPositionTicks>0) OutlinedButton(onClick={c.play(item,true)},modifier=Modifier.probe("play-start")) { Text("Play from start") }
                        TooltipArea(tooltip = { Text(if (item.isWatched) "Mark unwatched" else "Mark watched") }) {
                            IconToggleButton(checked = item.isWatched, onCheckedChange = { c.toggleWatched() }, modifier = Modifier.probe("watched").semantics { contentDescription = "Watched" }) {
                                Icon(DesktopGlyph.Watched, contentDescription = null, tint = if (item.isWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TooltipArea(tooltip = { Text(if (item.userData.isFavorite) "Remove favorite" else "Add to favorites") }) {
                            IconToggleButton(checked = item.userData.isFavorite, onCheckedChange = { c.toggleFavorite() }, modifier = Modifier.probe("favorite").semantics { contentDescription = "Favorite" }) {
                                Icon(DesktopGlyph.Favorite, contentDescription = null, tint = if (item.userData.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
}
@Composable
fun PlayerPage(state: DesktopState, c: DesktopController, fullscreen: () -> Unit) {
    val p = state.playback ?: return
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        p.notice?.let { notice -> Surface(color=MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.padding(16.dp)) { Text(notice.title,style=MaterialTheme.typography.titleMedium); Text(notice.reason); Text(notice.fix) } } }
        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(p.item.name,style=MaterialTheme.typography.titleMedium)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(clock(p.positionSeconds),Modifier.width(60.dp))
                    Slider(value=p.positionSeconds.toFloat().coerceIn(0f,p.durationSeconds.coerceAtLeast(1.0).toFloat()),onValueChange={c.seek(it.toDouble())},valueRange=0f..p.durationSeconds.coerceAtLeast(1.0).toFloat(),modifier=Modifier.weight(1f).probe("seek").semantics { contentDescription="Playback position" })
                    Text(clock(p.durationSeconds),Modifier.width(60.dp))
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=c::playerToggle,modifier=Modifier.probe("pause")) { Text(if(p.paused) "Play" else "Pause") }
                    TextButton(onClick={c.seek((p.positionSeconds-10).coerceAtLeast(0.0))},modifier=Modifier.probe("seek-back")) { Text("−10 s") }
                    TextButton(onClick={c.seek(p.positionSeconds+10)},modifier=Modifier.probe("seek-forward")) { Text("+10 s") }
                    Choice("Audio",p.audioTracks.zip(trackLabels(p.audioTracks)).map{(track,label)->track.index to label},p.audioIndex,"audio",c::selectAudio)
                    Choice("Subtitles",listOf(-1 to "Off")+p.subtitleTracks.zip(trackLabels(p.subtitleTracks)).map{(track,label)->track.index to label},p.subtitleIndex,"subtitles",c::selectSubtitle)
                    if(p.nextEpisode!=null) OutlinedButton(onClick=c::nextEpisode,modifier=Modifier.probe("next-episode")) { Text("Next episode") }
                    TextButton(onClick=fullscreen) { Text("Fullscreen") }
                    OutlinedButton(onClick=c::stopPlayback,modifier=Modifier.probe("stop")) { Text("Stop") }
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
        CatalogHeading("Playback",section=true)
        Choice("Video quality",listOf(0 to "Original / best compatible",4 to "4 Mbps",10 to "10 Mbps",20 to "20 Mbps",40 to "40 Mbps",80 to "80 Mbps"),s.bitrateMbps?:0,"quality") { c.saveSettings(s.copy(bitrateMbps=it.takeIf{it>0})) }
        SettingSwitch("Explain video conversion","Tell me when this desktop needs a different video format.",s.conversionNotice,"conversion") { c.saveSettings(s.copy(conversionNotice=it)) }
        SettingSwitch("Play next episode","Continue to the next episode when this one ends.",s.autoplayNext,"autoplay") { c.saveSettings(s.copy(autoplayNext=it)) }
        Text("Volume · ${s.volume}%")
        Slider(s.volume.toFloat(),{c.saveSettings(s.copy(volume=it.toInt()))},valueRange=0f..100f,modifier=Modifier.probe("volume").semantics { contentDescription="Volume" })
        HorizontalDivider()
        CatalogHeading("Interface",section=true)
        SettingSwitch("Living-room size","Larger text and controls for watching from a distance.",s.largeInterface,"large-interface") { c.saveSettings(s.copy(largeInterface=it)) }
        Text("Pictureshow · Free, open source Jellyfin client\nMPL-2.0 · Legitimate LLC\nVideo: dynamically loaded LGPL libmpv",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick=c::signOut,modifier=Modifier.probe("sign-out")) { Text("Sign out") }
    }
}
@Composable
private fun SettingSwitch(title:String,caption:String,value:Boolean,tag:String,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) { Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleMedium); Text(caption,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(value,onChange,Modifier.probe(tag).semantics { contentDescription=title }) }
}
@Composable
private fun <T> Choice(label:String,items:List<Pair<T,String>>,selected:T,tag:String,onChoose:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.widthIn(max=260.dp).probe(tag)) { Text("$label · ${items.firstOrNull{it.first==selected}?.second ?: "Default"}",maxLines=1,overflow=TextOverflow.Ellipsis) }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) { items.forEach { (key,text) -> DropdownMenuItem(text={Text(text)},onClick={expanded=false;onChoose(key)},leadingIcon={RadioButton(selected==key,null)},modifier=Modifier.probe("$tag:$key")) } }
    }
}
