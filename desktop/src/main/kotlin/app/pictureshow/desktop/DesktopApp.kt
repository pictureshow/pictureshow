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

/** Geometry of real controls, used by the finite Robot smoke driver, never by normal input handling. */
internal val LocalDesktopPage = staticCompositionLocalOf { "" }
internal val LocalModalOpen = staticCompositionLocalOf { false }
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
internal fun Modifier.focusOutline(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val drawRing = focused && !LocalModalOpen.current
    return onFocusChanged { focused = it.hasFocus }.border(if (drawRing) 2.dp else 0.dp, if (drawRing) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
}

/** A focus ring outside the control fill stays visible on primary and tonal controls. */
@Composable
internal fun Modifier.controlFocus(shape: Shape = RoundedCornerShape(50), allowInModal: Boolean = false, enabled: Boolean = true, insetRing: Boolean = false): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val modalOpen = LocalModalOpen.current
    return onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused && enabled && (!modalOpen || allowInModal)) {
            val gap = if (insetRing) 3.dp.toPx() else -4.dp.toPx()
            inset(gap, gap, gap, gap) {
                drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** Popups clip their full-width rows: keep the complete ring inside the row and away from neighbors. */
@Composable
internal fun Modifier.menuItemFocus(): Modifier = padding(horizontal = 4.dp)
    .controlFocus(RoundedCornerShape(6.dp), allowInModal = true, insetRing = true)

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
internal fun ShortcutRow(keys: String, action: String) {
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
    /** Receives the bubble-phase handler: keys that no focused control consumed. */
    windowFallbackKeys: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean) -> Unit = {},
    /** macOS keeps ⌘, for the app menu's Settings item; elsewhere Ctrl+, is the window shortcut. */
    macShortcuts: Boolean = macChrome,
    chrome: @Composable (@Composable () -> Unit) -> Unit = { it() },
    windowControls: @Composable RowScope.() -> Unit = {}) {
    val state by controller.state.collectAsState()
    // Semantics and tooltip callbacks may outlive this composition during sign-out.

    val menus = remember { DesktopMenuOwner() }
    val sidebarAccount = state.account
    val musicUi by controller.music.state.collectAsState()
    val pageKey = state.route.name + when(state.route) {
        DesktopRoute.Library -> ":${state.library?.id}:${state.sortBy}:${state.descending}"
        DesktopRoute.Detail -> ":${state.detail?.id}"
        DesktopRoute.Person -> ":${state.person?.id}"
        DesktopRoute.MusicLibrary -> ":${state.library?.id}"
        DesktopRoute.MusicItem -> ":${state.detail?.id}:${state.musicRole}"
        else -> ""
    }
    val savedPages = rememberSaveableStateHolder()
    LaunchedEffect(state.returnFocus, pageKey) {
        if (state.route == DesktopRoute.Search && state.returnFocus == "search-field") {
            delay(100)
            runCatching { DesktopUiProbe.requestFocus["search-field"]?.requestFocus() }
            controller.returnFocusDone()
        }
    }
    val focus = LocalFocusManager.current
    var help by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Account?>(null) }
    var signOut by remember { mutableStateOf<Account?>(null) }
    var modalReturnFocus by remember { mutableStateOf<String?>(null) }
    DesktopPageEntryFocus(state, pageKey, blocked = help || remove != null || signOut != null)
    LaunchedEffect(help, remove, signOut) {
        delay(100)
        val modal = when {
            help -> "help-close"
            remove != null -> "remove-cancel"
            signOut != null -> "sign-out-cancel"
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
            // Preview phase: only what must win over every control (fullscreen, and keeping
            // modal dialogs modal). It stays active when resize or pointer input clears a
            // child's focus, and when focus is in the custom title bar.
            SideEffect { windowKeys { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when {
                        e.key == Key.F11 || (e.key == Key.Enter && e.isAltPressed) || (macChrome && e.key == Key.F && e.isCtrlPressed && e.isMetaPressed) -> { onFullscreen(); true }
                        e.key == Key.Escape && menus.escape() -> true
                        e.key == Key.Escape && help -> { help = false; true }
                        e.key == Key.Escape && remove != null -> { remove = null; true }
                        e.key == Key.Escape && signOut != null -> { signOut = null; true }
                        (help || remove != null || signOut != null) && e.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> true
                        // PLAY-29: the player page owns its keys (DesktopPlayerPage.kt, playerKeyAction), including a menu's Escape.
                        state.route == DesktopRoute.Player && !help && remove == null && signOut == null && PlayerKeyboard.handle(e) { modalReturnFocus = null; help = true } -> true
                        else -> false
                    }
            } }
            // Bubble phase (D-7, D-12): keys reach the window only after the focused control and
            // its groups declined them, so a focused button keeps Space and a row keeps its arrows.
            SideEffect { windowFallbackKeys { e ->
                if (help || remove != null || signOut != null) return@windowFallbackKeys false
                val typed = (e.nativeKeyEvent as? java.awt.event.KeyEvent)?.takeIf { it.id == java.awt.event.KeyEvent.KEY_TYPED }?.keyChar
                if (e.type != KeyEventType.KeyDown && typed == null) return@windowFallbackKeys false
                val press = ShellKeyPress(e.key, e.isCtrlPressed, e.isMetaPressed, e.isAltPressed, e.isShiftPressed, typed)
                if (typed != null && typed != '?') return@windowFallbackKeys false
                val action = shellFallbackAction(press, state.route, DesktopUiProbe.focus.values.any { it }, fullscreen, macShortcuts, state.account != null, music = musicUi.visible)
                    ?: return@windowFallbackKeys false
                when (action) {
                    ShellAction.ExitFullscreen -> onFullscreen()
                    ShellAction.Back -> controller.back()
                    ShellAction.Fullscreen -> onFullscreen()
                    ShellAction.TogglePlay -> controller.playerToggle()
                    ShellAction.ToggleMusic -> controller.music.toggle()
                    ShellAction.SeekBack -> controller.seek((state.playback?.positionSeconds ?: 0.0) - 10)
                    ShellAction.SeekForward -> controller.seek((state.playback?.positionSeconds ?: 0.0) + 10)
                    ShellAction.Help -> { modalReturnFocus = DesktopUiProbe.focus.entries.firstOrNull { it.value }?.key; help = true }
                    ShellAction.Search -> controller.search()
                    ShellAction.Settings -> if (state.route != DesktopRoute.Settings) controller.settings()
                    ShellAction.Left -> focus.moveFocus(FocusDirection.Left)
                    ShellAction.Right -> focus.moveFocus(FocusDirection.Right)
                    ShellAction.Up -> focus.moveFocus(FocusDirection.Up)
                    ShellAction.Down -> focus.moveFocus(FocusDirection.Down)
                }
                true
            } }
            CompositionLocalProvider(LocalDesktopMenus provides menus, LocalModalOpen provides (help || remove != null || signOut != null || desktopAccountsModalOpen(controller))) {
            Column(Modifier.fillMaxSize().background(if (state.route == DesktopRoute.Player) Color.Transparent else MaterialTheme.colorScheme.background)) {
                chrome {
                Row(Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surface).padding(start = if(macChrome) 80.dp else 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PICTURESHOW", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(20.dp))
                    if (state.route in setOf(DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Detail, DesktopRoute.Player, DesktopRoute.Library, DesktopRoute.LiveTv, DesktopRoute.Settings, DesktopRoute.Search, DesktopRoute.Person, DesktopRoute.HomeEditor, DesktopRoute.MusicLibrary, DesktopRoute.MusicItem, DesktopRoute.MusicNow, DesktopRoute.Downloads, DesktopRoute.WatchTogether, DesktopRoute.Remote) || state.route in DESKTOP_ACCOUNTS_ROUTES || (state.route == DesktopRoute.Accounts && state.account != null)) TextButton(onClick = controller::back, modifier = Modifier.controlFocus().probe("back")) { Icon(DesktopGlyph.Back, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back") }
                    Spacer(Modifier.weight(1f))
                    DesktopUtility("Keyboard shortcuts", DesktopGlyph.Keyboard, "shortcuts") { modalReturnFocus = "shortcuts"; help = true }
                    DesktopUtility(if(fullscreen) "Exit fullscreen" else "Fullscreen", DesktopGlyph.Fullscreen, "fullscreen", onFullscreen)
                    windowControls()
                }
                }
                // The body is a directional fence: arrows never walk out of it into the title bar's
                // icons (D-7); Tab and Shift+Tab still reach them.
                // AUTH-16/18: while the viewer's PIN is asked, nothing of that viewer composes.
                val pinGate by controller.pins.state.collectAsState()
                if (pinGate.gate != null && pinGate.gate?.key == state.account?.key) DesktopPinGate(controller, Modifier.weight(1f)) else
                Row(Modifier.weight(1f).directionalFence()) {
                    if (sidebarAccount != null && state.route !in listOf(DesktopRoute.Photo, DesktopRoute.Player, DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Accounts, DesktopRoute.Servers)) {
                        DesktopSidebar(
                            account = sidebarAccount,
                            libraries = state.sidebarLibraries(),
                            root = state.root,
                            onHome = controller::home,
                            onSearch = controller::search,
                            onLibrary = controller::browseRoot,
                            onSettings = { if (state.route != DesktopRoute.Settings) controller.settings() },
                            onAccounts = controller::accounts,
                            onRequests = controller::openRequests.takeIf { controller.seerr.state.collectAsState().value.connected },
                            onDownloads = { if (state.route != DesktopRoute.Downloads) controller.downloads() },
                            together = controller.together?.let { { DesktopTogetherNav(controller, state.route) } },
                            onSignOut = { modalReturnFocus = "nav:account"; signOut = state.account }
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        state.error?.takeIf { state.errorKind != ErrorKind.INVALID_CREDENTIALS }?.let { error ->
                            Card(Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    if (state.route == DesktopRoute.Loading || (state.errorKind in setOf(ErrorKind.NETWORK, ErrorKind.SERVER) && state.route in setOf(DesktopRoute.Servers, DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.Person, DesktopRoute.QuickConnect))) TextButton(onClick = controller::retry, enabled = !state.busy, modifier = Modifier.controlFocus().probe("retry")) { Text("Retry") }
                                }
                            }
                        }
                        if (state.busy && state.route != DesktopRoute.Search) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" })
                        Box(Modifier.fillMaxSize()) {
                            savedPages.SaveableStateProvider(pageKey) {
                            CompositionLocalProvider(LocalDesktopPage provides pageKey) {
                            when (state.route) {
                                DesktopRoute.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                DesktopRoute.Servers -> ServerPage(state, controller)
                                DesktopRoute.SignIn -> SignInPage(state, controller)
                                DesktopRoute.QuickConnect -> QuickConnectPage(state, controller)
                                DesktopRoute.Accounts -> AccountsPage(state, controller, onRemove = { a -> modalReturnFocus = "remove:${a.key}"; remove = a })
                                DesktopRoute.Home -> HomePage(state, controller)
                                DesktopRoute.Search -> SearchPage(state, controller)
                                DesktopRoute.Person -> SearchPersonPage(state, controller)
                                DesktopRoute.LiveTv -> DesktopLiveTvPage(controller)
                                DesktopRoute.Library -> LibraryPage(state, controller)
                                DesktopRoute.Detail -> DetailPage(state, controller)
                                DesktopRoute.Player -> PlayerPage(state, controller, onFullscreen)
                                DesktopRoute.Settings -> SettingsPage(state, controller) { modalReturnFocus = "sign-out"; signOut = state.account }
                                DesktopRoute.Requests, DesktopRoute.SeerrTitle, DesktopRoute.About, DesktopRoute.Licenses, DesktopRoute.License, DesktopRoute.Supporter, DesktopRoute.ThisComputer -> DesktopAccountsPage(state, controller)
                                DesktopRoute.HomeEditor -> HomeEditorPage(state, controller)
                                DesktopRoute.Photo -> PhotoViewerPage(state, controller)
                                DesktopRoute.MusicLibrary -> DesktopMusicLibraryPage(state, controller)
                                DesktopRoute.MusicItem -> DesktopMusicItemPage(state, controller)
                                DesktopRoute.MusicNow -> DesktopMusicNowPage(controller)
                                DesktopRoute.Downloads -> DesktopDownloadsPage(controller)
                                DesktopRoute.WatchTogether -> DesktopWatchTogetherPage(controller)
                                DesktopRoute.Remote -> DesktopRemotePage(controller)
                            }
                            }
                            }
                        }
                    }
                }
                // MUS-03/04: music keeps playing while browsing; the bar stays put under every page but the player and Now playing.
                if (state.account != null && state.route !in setOf(DesktopRoute.Player, DesktopRoute.MusicNow, DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Servers)) DesktopMusicMiniBar(controller)
                if (state.account != null && state.route != DesktopRoute.Player) DesktopTogetherBar(controller)
            }
            val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            musicUi.prompt?.let { DesktopMusicReplacePrompt(controller, it) }
            if (help) DesktopShortcutsDialog(macShortcuts, macChrome, (windowHeight - 64.dp).coerceAtLeast(240.dp)) { help = false }
            signOut?.let { a ->
                SignOutConfirmation(a.user.name, onCancel = { signOut = null }, onConfirm = {
                    modalReturnFocus = null; signOut = null; controller.signOut()
                })
            }
            remove?.let { a ->
                AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${a.user.name}?") }, text = { Text("${a.user.name} will be removed from this computer. You can sign in again any time.") }, dismissButton = { TextButton(onClick = { remove = null }, modifier = Modifier.controlFocus(allowInModal = true).probe("remove-cancel")) { Text("Cancel") } }, confirmButton = { TextButton(onClick = { modalReturnFocus = null; remove = null; controller.removeAccount(a.key) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.controlFocus(allowInModal = true).probe("remove-confirm")) { Text("Remove account") } }) }
            DesktopPinDialogs(controller)
            }
        }
    }
}

internal data class ShortcutSection(val title: String, val rows: List<Pair<String, String>>, val note: String? = null)

/** The ? sheet. The playback rows are the PLAY-29 set; the player page owns M and the volume keys. */
internal fun desktopShortcutSheet(mac: Boolean, macChrome: Boolean): List<ShortcutSection> = listOf(
    ShortcutSection("Anywhere", listOf(
        (if (mac) "Tab / ⇧Tab" else "Tab / Shift+Tab") to "Move between controls and rows",
        "↑ ↓ ← →" to "Move within a row; browse menus",
        "Enter / Space" to "Activate focused control",
        "Esc" to "Close a dialog, exit fullscreen, or go back",
        (if (mac) "⌘," else "Ctrl+,") to "Settings",
        (if (macChrome) "⌃⌘F" else "F11 / Alt+Enter") to "Fullscreen",
        "?" to "This list"
    )),
    ShortcutSection("Browsing", listOf((if (mac) "⌘F" else "Ctrl+F") to "Search your libraries"), note = "Search opens with the field focused. Down enters results; Escape goes back. Unavailable during playback."),
    ShortcutSection("During playback", listOf(
        "Space / K" to "Play or pause",
        "← → / J L" to "Back or forward 10 seconds",
        "↑ / ↓" to "Volume up or down",
        "M" to "Mute or unmute",
        "F" to "Fullscreen",
        "Esc" to "Close a panel, hide the controls, then leave the player"
    ), note = "Space and the arrows act on the video when no button has focus. Playback actions are also in the control bar.")
)

/** Arrow keys never leave this group (D-7); Tab and Shift+Tab still do. */
internal fun Modifier.directionalFence(): Modifier = focusProperties {
    onExit = { if (requestedFocusDirection in listOf(FocusDirection.Left, FocusDirection.Right, FocusDirection.Up, FocusDirection.Down)) cancelFocusChange() }
}.focusGroup()

internal const val SIDEBAR_WIDTH_DP = 208

/**
 * The sidebar (D-1, D-6, D-14): an account menu on top, a scrolling list of Home and the
 * libraries with its own scrollbar, and a pinned footer, so every destination stays inside the
 * window at 800×600 with Living-room size and any number of libraries. Focus moving into a
 * hidden item scrolls it into view.
 */
@Composable
internal fun DesktopSidebar(
    account: Account,
    libraries: List<MediaItem>,
    root: String?,
    onHome: () -> Unit,
    onLibrary: (MediaItem) -> Unit,
    onSettings: () -> Unit,
    onAccounts: () -> Unit,
    onSignOut: () -> Unit,
    onSearch: () -> Unit = {},
    onRequests: (() -> Unit)? = null,
    onDownloads: () -> Unit = {},
    together: (@Composable () -> Unit)? = null
) {
    Column(Modifier.width(SIDEBAR_WIDTH_DP.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 12.dp).testTag("sidebar")) {
        DesktopAccountMenu(account, onSettings, onAccounts, onSignOut, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(8.dp))
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth().testTag("sidebar-list")) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SidebarItem("Home", root == DESKTOP_ROOT_HOME, "nav:home", onHome)
                libraries.forEach { library ->
                    SidebarItem(library.name, root == desktopLibraryRoot(library.id), "nav:library:${library.id}") { onLibrary(library) }
                }
            }
            if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)))
                .testTag("sidebar-more-below"))
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp))
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        // Search and Settings stay reachable independently of the number of libraries.
        Column(Modifier.padding(horizontal = 12.dp).testTag("sidebar-footer"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SidebarItem("Downloads", root == DESKTOP_ROOT_DOWNLOADS, "nav:downloads", onDownloads)
            onRequests?.let { SidebarItem("Requests", root == DESKTOP_ROOT_REQUESTS, "nav:requests", it) }
            together?.invoke()
            SidebarItem("Search", root == DESKTOP_ROOT_SEARCH, "nav:search", onSearch)
            SidebarItem("Settings", root == DESKTOP_ROOT_SETTINGS, "nav:settings", onSettings)
        }
    }
}

/** A destination: up to two lines, and a tooltip with the full name when even two don't fit (D-14). */
@Composable
internal fun SidebarItem(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    var clipped by remember(label) { mutableStateOf(false) }
    TooltipArea(
        tooltip = { if (clipped) Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp)) {
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        } },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(alignment = Alignment.BottomCenter, offset = DpOffset(0.dp, 24.dp))
    ) {
        NavigationDrawerItem(
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { clipped = it.hasVisualOverflow }) },
            selected = selected, onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp).controlFocus().probe(tag)
        )
    }
}

/** The name and server as one button with a chevron; its menu holds Switch account, Settings and Sign out (D-6, CAT-01a). */
@Composable
internal fun DesktopAccountMenu(account: Account, onSettings: () -> Unit, onAccounts: () -> Unit, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    Box(modifier) {
        DesktopTooltip("${account.user.name} on ${account.server.name}") {
            Surface(onClick = { open = true }, shape = RoundedCornerShape(12.dp), color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().focusRequester(opener).controlFocus(RoundedCornerShape(12.dp), enabled = !open).probe("nav:account")
                    .semantics { contentDescription = "${account.user.name} on ${account.server.name}. Account menu"; role = Role.DropdownList }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.user.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(account.server.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(DesktopGlyph.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DesktopDropdownMenu(expanded = open, onDismissRequest = { open = false }, opener = opener, initialFocus = initialFocus, modifier = Modifier.width((SIDEBAR_WIDTH_DP - 24).dp)) {
            DropdownMenuItem(text = { Text("Switch account") }, onClick = { open = false; onAccounts() }, modifier = Modifier.focusRequester(initialFocus).menuItemFocus().probe("account-menu:switch"))
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() }, modifier = Modifier.menuItemFocus().probe("account-menu:settings"))
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { open = false; onSignOut() }, modifier = Modifier.menuItemFocus().probe("account-menu:sign-out"))
        }
    }
}

@Composable
internal fun DesktopUtility(label: String, glyph: androidx.compose.ui.graphics.vector.ImageVector, tag: String, action: () -> Unit) {
    DesktopTooltip(label) {
        IconButton(onClick=action, modifier = Modifier.controlFocus().probe(tag)) { Icon(glyph, label, tint=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun InlineTextAction(label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe(tag)) { Text(label) }
}

internal fun shortTrackLabel(stream: app.pictureshow.core.playback.MediaStream): String =
    trackLanguage(stream.language) ?: trackLabel(stream).substringBefore(" · ").ifBlank { "Track ${stream.index + 1}" }

/** Entry focus applies after loading and after pointer navigation, without stealing focus during an edit. */
@Composable
internal fun DesktopPageEntryFocus(state: DesktopState, pageKey: String, blocked: Boolean = false) {
    val blockedNow by rememberUpdatedState(blocked)
    fun destinationHasFocus() = DesktopUiProbe.focus.entries.any { (tag, focused) -> focused && DesktopUiProbe.pageOfTag[tag] == pageKey }
    // Restore Search once on entry, not on every debounce completion: a viewer may have
    // deliberately moved to the sidebar while a query is pending.
    LaunchedEffect(pageKey) {
        if (state.route == DesktopRoute.Search) {
            delay(150)
            if (!blockedNow && !destinationHasFocus()) {
                val tag = if (state.returnFocus == "search-field") "search-field" else DesktopUiProbe.lastFocus[pageKey]?.takeIf { DesktopUiProbe.requestFocus.containsKey(it) } ?: "search-field"
                runCatching { DesktopUiProbe.requestFocus[tag]?.requestFocus() }
            }
        }
    }

    LaunchedEffect(pageKey, state.busy) {
        if(!state.busy && state.route != DesktopRoute.Search) {
            delay(150)
            if(!blockedNow && !destinationHasFocus()) {
                // PLAY-29: the player opens with focus on its playback surface (tag `player-surface`,
                // when the player page provides one) or on nothing, so Space and arrows reach playback.
                val target = if (state.route == DesktopRoute.Player) "player-surface".takeIf { DesktopUiProbe.requestFocus.containsKey(it) }
                else DesktopUiProbe.lastFocus[pageKey]?.takeIf { DesktopUiProbe.requestFocus.containsKey(it) && !destructiveEntryTarget(it) }
                    ?: desktopEntryTarget(state)
                target?.let { runCatching { DesktopUiProbe.requestFocus[it]?.requestFocus() } }
            }
        }
    }
}
internal fun desktopAudioChoices(streams: List<MediaStream>): List<Pair<Int, String>> =
    streams.zip(trackLabels(streams)).map { (track, label) -> track.index to label }

/** Route defaults and remembered focus may never select an account/session destructive action. */
internal fun destructiveEntryTarget(tag: String): Boolean = tag == "sign-out" || tag == "accounts-sign-out" ||
    tag.startsWith("remove:") || tag == "remove-confirm" || tag == "sign-out-confirm" || tag.endsWith(":sign-out")

internal fun desktopEntryTarget(state: DesktopState): String? = when(state.route) {
    DesktopRoute.Servers -> "server-address"
    DesktopRoute.SignIn -> "username"
    // The page's first control, so Settings opens at its top (pilot 172), not scrolled to Video quality.
    DesktopRoute.Settings -> "jump:Account"
    DesktopRoute.LiveTv -> "live:tab:Channels"
    DesktopRoute.Downloads -> "downloads:cap"
    DesktopRoute.Detail -> "play"
    DesktopRoute.Home -> state.homeRows.firstOrNull()?.let { row -> row.items.firstOrNull()?.let { "card:${row.key}:${it.id}" } }
    DesktopRoute.Library -> state.items.firstOrNull()?.let { "grid:${it.id}" } ?: "sort"
    DesktopRoute.HomeEditor -> "editor-first"
    DesktopRoute.Photo -> "photo-viewer"
    DesktopRoute.Person -> state.items.firstOrNull()?.let { "person:${it.id}" }
    DesktopRoute.Search -> "search-field"
    DesktopRoute.Player -> "player-surface"
    DesktopRoute.Accounts -> state.accounts.firstOrNull()?.let { "account:${it.key}" }
    DesktopRoute.MusicLibrary -> "music:tab:ALBUMS"
    DesktopRoute.MusicItem -> "music:play"
    DesktopRoute.MusicNow -> "music:now:toggle"
    DesktopRoute.WatchTogether -> "together-refresh"
    DesktopRoute.Remote -> "remote-disconnect"
    else -> desktopAccountsEntryTarget(state)
}

@Composable
internal fun SignOutConfirmation(name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Sign out of $name?") },
        text = { Text(SIGN_OUT_CONSEQUENCE_COPY) },
        dismissButton = { TextButton(onCancel, Modifier.focusRequester(cancelFocus).controlFocus(allowInModal = true).probe("sign-out-cancel")) { Text("Cancel") } },
        confirmButton = { TextButton(onConfirm, Modifier.controlFocus(allowInModal = true).probe("sign-out-confirm")) { Text("Sign out") } })
    LaunchedEffect(Unit) { withFrameNanos { }; cancelFocus.requestFocus() }
}

@Composable
internal fun DesktopShortcutsDialog(mac: Boolean, macChrome: Boolean, maxHeight: Dp, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.heightIn(max = maxHeight).probe("help-dialog"),
        title = { Text("Keyboard shortcuts") }, text = {
            val sheet = rememberScrollState()
            Box(Modifier.heightIn(max = (maxHeight - 160.dp).coerceAtLeast(80.dp))) {
                Column(Modifier.verticalScroll(sheet).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    desktopShortcutSheet(mac, macChrome).forEachIndexed { i, section ->
                        if (i > 0) HorizontalDivider()
                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                        section.rows.forEach { (keys, action) -> ShortcutRow(keys, action) }
                        section.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(sheet), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }, confirmButton = { TextButton(onDismiss, Modifier.controlFocus(allowInModal = true).probe("help-close")) { Text("Close") } })
}
