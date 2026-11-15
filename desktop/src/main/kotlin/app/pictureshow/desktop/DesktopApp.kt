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
                                DesktopRoute.QuickConnect -> QuickConnectPage(state, controller)
                                DesktopRoute.Accounts -> AccountsPage(state, controller, onRemove = { a -> modalReturnFocus = "remove:${a.key}"; remove = a })
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
