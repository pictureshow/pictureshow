package app.pictureshow

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
    internal val playbackExitHandoff = PlaybackExitHandoff()

    // Activity's framework callback is inherited through the restricted AndroidX core base.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            playbackExitHandoff.input(event.keyCode)
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN || event.action == android.view.KeyEvent.ACTION_UP) {
            val copy = android.view.KeyEvent(event)
            if (playbackExitHandoff.defer(copy.keyCode, copy.action == android.view.KeyEvent.ACTION_DOWN, copy.repeatCount) {
                    dispatchKeyEvent(copy)
                }
            ) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var playbackEntry by mutableStateOf<ParsedPlaybackEntry?>(null)
    private var searchEntry by mutableStateOf<String?>(null)
    private var entrySequence by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            acceptPlaybackIntent(intent)
        } else {
            searchEntry = savedInstanceState.getString("searchEntry")
            entrySequence = savedInstanceState.getLong("playbackEntrySequence")
            playbackEntry = when (savedInstanceState.getString("playbackEntryKind")) {
                "invalid" -> ParsedPlaybackEntry.Invalid
                "play" -> parsePlaybackEntry(
                    Intent(PLAY_ACTION).putExtra("itemId", savedInstanceState.getString("playbackEntryId")).apply {
                        if (savedInstanceState.containsKey("playbackEntryPosition")) {
                            putExtra("positionMs", savedInstanceState.getLong("playbackEntryPosition"))
                        }
                    }
                )
                else -> null
            }
        }
        setContent {
            PictureshowTheme {
                Pictureshow(
                    searchEntry = searchEntry,
                    consumeSearchEntry = { searchEntry = null },
                    playbackEntry = playbackEntry,
                    entrySequence = entrySequence,
                    consumePlaybackEntry = { playbackEntry = null }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { (application as PictureshowApplication).supporter.refresh() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("searchEntry", searchEntry)
        outState.putLong("playbackEntrySequence", entrySequence)
        when (val pending = playbackEntry) {
            ParsedPlaybackEntry.Invalid -> outState.putString("playbackEntryKind", "invalid")
            is ParsedPlaybackEntry.Play -> {
                outState.putString("playbackEntryKind", "play")
                outState.putString("playbackEntryId", pending.entry.itemId)
                pending.entry.positionMs?.let { outState.putLong("playbackEntryPosition", it) }
            }
            else -> Unit
        }
        super.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        PlaybackMediaSession.dispatchKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        PlaybackMediaSession.dispatchKeyEvent(event) || super.onKeyUp(keyCode, event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPlaybackIntent(intent)
    }

    private fun acceptPlaybackIntent(intent: Intent) {
        searchEntry = parseSearchIntent(intent)
        playbackEntry = parsePlaybackEntry(intent).takeUnless { it == ParsedPlaybackEntry.Ignore }
        entrySequence++
    }
}

internal fun itemRoute(item: MediaItem) = "item?item=${Uri.encode(Json.encodeToString(item))}"

private fun playerRoute(item: MediaItem, resume: Boolean) =
    playerRoute(item, if (resume) item.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000 else 0)

internal fun playerRoute(item: MediaItem, positionMs: Long) = "player?item=${Uri.encode(Json.encodeToString(item))}&start=$positionMs"

internal fun navigatePlaybackEntry(nav: NavHostController, entry: ResolvedPlaybackEntry.Open) {
    nav.navigate("home") {
        popUpTo(nav.graph.id) { inclusive = true }
        launchSingleTop = true
    }
    nav.navigate(itemRoute(entry.item))
    nav.navigate(playerRoute(entry.item, entry.positionMs))
}

/** Remove the player during recovery; reuse a matching detail or open the current auto-next item. */
internal fun recoverPlaybackEntry(nav: NavHostController, item: MediaItem) {
    val player = nav.currentBackStackEntry?.takeIf { it.destination.route?.startsWith("player?") == true } ?: return
    val previous = nav.previousBackStackEntry
    val previousItem = previous?.arguments?.getString("item")
        ?.takeIf { previous?.destination?.route?.startsWith("item?") == true }
        ?.let { Json.decodeFromString<MediaItem>(it) }
    if (previousItem?.id == item.id) {
        nav.popBackStack()
    } else {
        nav.navigate(itemRoute(item)) {
            popUpTo(player.destination.id) { inclusive = true }
        }
    }
}

/** Expired sessions start directly on login; their Back still needs an account-management exit. */
private fun leaveLogin(nav: NavHostController) {
    if (!nav.popBackStack()) {
        nav.navigate("accounts") {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

private fun serverRoute(screen: String, server: Server) = "$screen?server=${Uri.encode(Json.encodeToString(server))}"

internal fun accountDestination(snapshot: AccountSnapshot): String {
    val account = snapshot.currentAccount
    return when {
        account?.expired == true -> serverRoute("login", account.server) + "&username=${Uri.encode(account.user.name)}"
        account != null -> "home"
        snapshot.accounts.isEmpty() -> "servers"
        else -> "accounts"
    }
}

/** Complete a persisted sign-in/selection only after the installed session has its credential.
 * Identity/expiry changes still bootstrap the graph; this also handles unchanged identities.
 */
@Composable
private fun SignInCompletion(
    completed: Boolean,
    entryId: String,
    nav: NavHostController,
    snapshot: AccountSnapshot,
    installedKey: String?,
    installedExpired: Boolean?,
    accounts: AccountStore
) {
    LaunchedEffect(completed, snapshot, installedKey, installedExpired) {
        val saved = accounts.snapshots.value
        if (completed &&
            saved.currentAccount?.expired == false &&
            installedKey == saved.currentKey &&
            installedExpired == saved.currentAccount?.expired &&
            snapshot.currentKey == saved.currentKey &&
            snapshot.currentAccount?.expired == saved.currentAccount?.expired &&
            snapshot.currentAccount?.accessToken == saved.currentAccount?.accessToken &&
            nav.currentBackStackEntry?.id == entryId
        ) {
            nav.navigate("home") {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}

@Composable
internal fun Pictureshow(
    session: SessionViewModel = koinViewModel(),
    searchEntry: String? = null,
    consumeSearchEntry: () -> Unit = {},
    playbackEntry: ParsedPlaybackEntry? = null,
    entrySequence: Long = 0,
    consumePlaybackEntry: () -> Unit = {}
) {
    val state by session.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val exitHandoff = (LocalActivity.current as? MainActivity)?.playbackExitHandoff
    val exitPlayback = rememberPlaybackExit(nav, exitHandoff)
    val backStackEntry by nav.currentBackStackEntryAsState()
    var searchKeyboardRequest by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val catalogService: CatalogService = koinInject()
    val accounts: AccountStore = koinInject()
    val catalog =
        remember(state.snapshot.currentKey, state.snapshot.currentAccount?.accessToken) {
            CatalogSession(catalogService, accounts) {
                (context.applicationContext as? PictureshowApplication)?.refreshWatchNext()
            }
        }
    if (state.loading) {
        Page("Pictureshow") { Text("Loading saved accounts…") }
        return
    }
    if (state.error != null) {
        Page("Pictureshow") {
            Message(state.error)
            Action("Retry", initial = true, onClick = session::load)
        }
        return
    }
    // Navigation restores its own stack. Save the identity that installed it too: the first
    // composition after recreation is not an account change. Do not put credentials in saved state.
    var accountInstalled by rememberSaveable { mutableStateOf(false) }
    var installedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var installedExpired by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.snapshot.currentKey, state.snapshot.currentAccount?.expired) {
        val key = state.snapshot.currentKey
        val expired = state.snapshot.currentAccount?.expired
        if (accountInstalled && installedKey == key && installedExpired == expired) return@LaunchedEffect
        installedKey = key
        installedExpired = expired
        accountInstalled = true
        val destination = accountDestination(state.snapshot)
        nav.navigate(destination) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    // Account bootstrap above installs the normal graph first. An entry never waits for a future login.
    LaunchedEffect(entrySequence, state.snapshot.currentKey, state.snapshot.currentAccount?.accessToken) {
        val entry = playbackEntry ?: return@LaunchedEffect
        val account = state.snapshot.currentAccount?.takeUnless { it.expired }
        if (account == null) {
            consumePlaybackEntry()
            return@LaunchedEffect
        }
        when (entry) {
            ParsedPlaybackEntry.Ignore -> Unit
            ParsedPlaybackEntry.Invalid -> Toast.makeText(context, ENTRY_INVALID, Toast.LENGTH_SHORT).show()
            is ParsedPlaybackEntry.Play -> when (val resolved = resolvePlaybackEntry(entry.entry, account, catalog)) {
                ResolvedPlaybackEntry.NormalStartup -> Unit
                ResolvedPlaybackEntry.Unavailable -> {
                    Toast.makeText(context, ENTRY_UNAVAILABLE, Toast.LENGTH_SHORT).show()
                    nav.navigate("home") {
                        popUpTo(nav.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                is ResolvedPlaybackEntry.Open -> navigatePlaybackEntry(nav, resolved)
            }
        }
        consumePlaybackEntry()
    }
    LaunchedEffect(entrySequence, state.snapshot.currentKey, state.snapshot.currentAccount?.accessToken) {
        val query = searchEntry ?: return@LaunchedEffect
        val destination = searchIntentDestination(state.snapshot, query)
        if (destination.startsWith("search?")) navigateSearchEntry(nav, query)
        consumeSearchEntry()
    }
    NavHost(
        navController = nav,
        startDestination = "waiting",
        modifier = Modifier.fillMaxSize().catalogSearchKey(
            enabled = state.snapshot.currentAccount?.expired == false && isCatalogSearchRoute(backStackEntry?.destination?.route),
            onSearch = {
                if (backStackEntry?.destination?.route?.startsWith("search?") == true) {
                    searchKeyboardRequest++
                } else {
                    nav.navigate(searchRoute())
                }
            }
        )
    ) {
        composable("waiting") { Page("Pictureshow") { Text("Loading…") } }
        composable("servers") {
            val vm: ServerViewModel = koinViewModel()
            val screen by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.scan() }
            DisposableEffect(Unit) { onDispose { vm.leave() } }
            LaunchedEffect(screen.connected) {
                screen.connected?.let {
                    vm.consumeConnected()
                    nav.navigate(serverRoute("login", it)) { launchSingleTop = true }
                }
            }
            val remembered = state.snapshot.rememberedServers
            val discoveryFocus =
                rememberDiscoveryFocus(screen.discovered.firstOrNull()?.id.takeIf { state.snapshot.lastServer == null }, screen.connecting)
            ServerDiscoveryPage(modifier = discoveryFocus.container) {
                Text("Choose a server on your network, or enter an address.")
                remembered.forEachIndexed { index, server ->
                    val label = if (server == state.snapshot.lastServer) "Sign in to ${server.name}" else "${server.name} · remembered"
                    Action(label, initial = index == 0 && state.snapshot.lastServer != null) {
                        nav.navigate(serverRoute("login", server)) {
                            launchSingleTop =
                                true
                        }
                    }
                }
                RemoteField(
                    "Server address",
                    screen.address,
                    vm::address,
                    initial = state.snapshot.lastServer == null,
                    onDone = { vm.connect() }
                )
                Action(
                    if (screen.connecting) "Connecting…" else "Connect",
                    enabled = !screen.connecting
                ) { vm.connect() }
                Message(screen.error)
                screen.discovered.forEachIndexed { index, server ->
                    Action(
                        "${server.name} · ${server.address}",
                        enabled = !screen.connecting,
                        modifier = if (index == 0) discoveryFocus.firstServer else Modifier
                    ) { vm.connect(server.address) }
                }
                StatusLine(
                    if (screen.scanning) {
                        "Looking for servers…"
                    } else if (screen.discovered.isEmpty()) {
                        "No servers found. You can enter an address above."
                    } else {
                        "Discovery complete."
                    }
                )
                Action("Search again", enabled = !screen.scanning, onClick = vm::scan)
                if (state.snapshot.rememberedServers.isNotEmpty()) {
                    Action("Remembered accounts") {
                        // Reuse the original chooser; discard this entire Add a server flow.
                        if (!nav.popBackStack("accounts", false)) {
                            nav.navigate("accounts") {
                                popUpTo(nav.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }
        composable("accounts") { entry ->
            val vm: AccountsViewModel = koinViewModel()
            val screen by vm.state.collectAsStateWithLifecycle()
            SignInCompletion(screen.selected, entry.id, nav, state.snapshot, installedKey, installedExpired, accounts)
            var returnFocus by rememberSaveable { mutableStateOf<String?>(null) }
            var removalOpenerLabel by rememberSaveable { mutableStateOf<String?>(null) }
            var returningFromRemoval by rememberSaveable { mutableStateOf(false) }
            var removeKey by rememberSaveable { mutableStateOf<String?>(null) }
            var removeServerId by rememberSaveable { mutableStateOf<String?>(null) }
            var removeServerUrl by rememberSaveable { mutableStateOf<String?>(null) }
            val removingAccount = state.snapshot.accounts.firstOrNull { it.key == removeKey }
            val removingServer = state.snapshot.rememberedServers.firstOrNull { it.id == removeServerId && it.url == removeServerUrl }
            LaunchedEffect(state.snapshot, removeKey, removeServerId, removeServerUrl) {
                // Once a removal commits, the old target must not reopen if that viewer signs in again.
                if (removeKey != null && removingAccount == null) removeKey = null
                if (removeServerId != null && removingServer == null) {
                    removeServerId = null
                    removeServerUrl = null
                }
            }
            fun cancelRemoval() {
                // A removal attempt may clear returnFocus before persistence fails.
                // Rebuild the exact opener from the still-present confirmation target.
                returnFocus = when {
                    removingAccount != null -> "remove:${removingAccount.key}"
                    removingServer != null -> "remove:server:${removingServer.id}:${removingServer.url}"
                    else -> returnFocus
                }
                returningFromRemoval = true
                removeKey = null
                removeServerId = null
                removeServerUrl = null
            }
            if (removingAccount != null || removingServer != null) {
                androidx.compose.runtime.key("removal") {
                    ConfirmationFocus("Cancel") {
                        BackHandler(enabled = !screen.busy) { cancelRemoval() }
                        // While committing, hardware Back must not expose controls from the parent page.
                        BackHandler(enabled = screen.busy) { }
                        val description = if (removingAccount != null) {
                            "This removes ${removingAccount.user.name} on ${removingAccount.server.name} from this TV."
                        } else {
                            requireNotNull(removingServer)
                            val count = state.snapshot.accounts.count {
                                it.server.id == removingServer.id && it.server.url == removingServer.url
                            }
                            if (count == 0) {
                                "This removes ${removingServer.name} from this TV."
                            } else {
                                "This removes ${removingServer.name} and $count remembered " +
                                    "${if (count == 1) "account" else "accounts"} from this TV."
                            }
                        }
                        Page(
                            if (removingAccount != null) "Remove remembered account?" else "Remove server?",
                            announceTitle = false
                        ) {
                            Text(description, modifier = Modifier.semantics { hideFromAccessibility() })
                            Text(
                                "Other accounts stay signed in. You can add it again later.",
                                modifier = Modifier.semantics {
                                    hideFromAccessibility()
                                }
                            )
                            Message(screen.error)
                            val question = if (removingAccount != null) "Remove remembered account?" else "Remove server?"
                            Action(
                                "Cancel",
                                initial = true,
                                enabled = !screen.busy,
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        "$question $description Other accounts stay signed in. You can add it again later."
                                }
                            ) { cancelRemoval() }
                            Action(if (removingAccount != null) "Remove account" else "Remove server", enabled = !screen.busy) {
                                // The account-store publication drives navigation and dismisses this page.
                                returnFocus = null
                                if (removingAccount != null) {
                                    vm.removeAccount(removingAccount.key)
                                } else {
                                    removingServer?.let(vm::removeServer)
                                }
                            }
                        }
                    }
                }
            } else {
                androidx.compose.runtime.key("chooser") {
                    ConfirmationFocus(
                        removalOpenerLabel.takeIf { returningFromRemoval },
                        onRestored = { returningFromRemoval = false }
                    ) {
                        Page("Who’s watching?") {
                            val offerSignedOutServer = state.snapshot.currentAccount == null && state.snapshot.lastServer != null
                            val initialAccount = state.snapshot.currentAccount ?: state.snapshot.accounts.firstOrNull()
                            state.snapshot.accounts.forEach { account ->
                                Action(
                                    "${account.user.name} on ${account.server.name}",
                                    initial = returnFocus == account.key ||
                                        (returnFocus == null && !offerSignedOutServer && account.key == initialAccount?.key),
                                    enabled = !screen.busy
                                ) {
                                    if (account.expired && account.key == state.snapshot.currentKey) {
                                        returnFocus = account.key
                                        nav.navigate(serverRoute("login", account.server) + "&username=${Uri.encode(account.user.name)}") {
                                            launchSingleTop = true
                                        }
                                    } else {
                                        vm.select(account.key)
                                    }
                                }
                                Action(
                                    "Remove ${account.user.name} on ${account.server.name}",
                                    initial = returnFocus == "remove:${account.key}",
                                    enabled = !screen.busy
                                ) {
                                    returnFocus = "remove:${account.key}"
                                    removalOpenerLabel = "Remove ${account.user.name} on ${account.server.name}"
                                    removeKey = account.key
                                }
                            }
                            Message(screen.error)
                            state.snapshot.rememberedServers.forEachIndexed { index, server ->
                                val identity = "server:${server.id}:${server.url}"
                                Action(
                                    "Sign in another user on ${server.name}",
                                    initial = returnFocus == identity || (returnFocus == null && offerSignedOutServer && index == 0),
                                    enabled = !screen.busy
                                ) {
                                    returnFocus = identity
                                    nav.navigate(serverRoute("login", server)) { launchSingleTop = true }
                                }
                                Action(
                                    "Remove server ${server.name}",
                                    initial = returnFocus == "remove:$identity",
                                    enabled = !screen.busy
                                ) {
                                    returnFocus = "remove:$identity"
                                    removalOpenerLabel = "Remove server ${server.name}"
                                    removeServerId = server.id
                                    removeServerUrl = server.url
                                }
                            }
                            Action(
                                "Add a server",
                                initial = returnFocus == "add" ||
                                    (returnFocus == null && state.snapshot.rememberedServers.isEmpty()),
                                enabled = !screen.busy
                            ) {
                                returnFocus = "add"
                                nav.navigate("servers") { launchSingleTop = true }
                            }
                        }
                    }
                }
            }
        }

        composable(
            "login?server={server}&username={username}",
            arguments = listOf(
                navArgument("server") { type = NavType.StringType },
                navArgument("username") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val server = remember(entry) { Json.decodeFromString<Server>(requireNotNull(entry.arguments?.getString("server"))) }
            val vm: LoginViewModel = koinViewModel(parameters = { parametersOf(server) })
            val screen by vm.state.collectAsStateWithLifecycle()
            SignInCompletion(screen.signedIn, entry.id, nav, state.snapshot, installedKey, installedExpired, accounts)
            LaunchedEffect(entry) { vm.prefillUsername(entry.arguments?.getString("username").orEmpty()) }
            val activity = LocalActivity.current
            DisposableEffect(vm, activity) {
                onDispose { vm.leave(preserveInput = activity?.isChangingConfigurations == true) }
            }
            val focusManager = LocalFocusManager.current
            var quickFocus by rememberSaveable { mutableStateOf(false) }
            val sessionEnded = state.snapshot.currentAccount?.let {
                it.expired &&
                    it.server.id == server.id &&
                    it.server.url == server.url &&
                    it.user.name == entry.arguments?.getString("username")
            } == true
            BackHandler(enabled = nav.previousBackStackEntry == null) { leaveLogin(nav) }
            Page("Sign in to ${server.name}") {
                RemoteField(
                    "Username",
                    screen.username,
                    vm::username,
                    initial = !quickFocus,
                    enabled = !screen.busy,
                    onDone = { focusManager.moveFocus(FocusDirection.Down) }
                )
                RemoteField("Password", screen.password, vm::password, password = true, enabled = !screen.busy, onDone = vm::signIn)
                Action(
                    if (screen.busy) "Signing in…" else "Sign in",
                    enabled = !screen.busy,
                    onClick = vm::signIn
                )
                Message(screen.error)
                Message(screen.notice)
                if (sessionEnded) StatusLine("Your session ended. Sign in again.")
                if (screen.loading) StatusLine("Loading sign-in options…")
                if (screen.quickConnect) {
                    Action("Use Quick Connect", initial = quickFocus, enabled = !screen.busy) {
                        quickFocus = true
                        nav.navigate(serverRoute("quick", server)) { launchSingleTop = true }
                    }
                }
                if (screen.users.isNotEmpty()) Text("Or choose a public user:")
                screen.users.forEach { user -> Action(user.name, enabled = !screen.busy) { vm.username(user.name) } }
                Action("Back", enabled = !screen.busy) { leaveLogin(nav) }
            }
        }
        composable("quick?server={server}", arguments = listOf(navArgument("server") { type = NavType.StringType })) { entry ->
            val server = remember(entry) { Json.decodeFromString<Server>(requireNotNull(entry.arguments?.getString("server"))) }
            val loginEntry = remember(entry) {
                nav.getBackStackEntry("login?server={server}&username={username}")
            }
            val vm: QuickConnectViewModel = koinViewModel(
                viewModelStoreOwner = loginEntry,
                parameters = { parametersOf(server, loginEntry.savedStateHandle) }
            )
            val screen by vm.state.collectAsStateWithLifecycle()
            // The parent sign-in entry keeps this attempt across Back. Android saves its short-lived
            // code and secret with that entry for process restoration, never in account persistence.
            // Leaving the sign-in flow clears the owner and cancels polling.
            LaunchedEffect(vm) { vm.start() }
            SignInCompletion(screen.signedIn, entry.id, nav, state.snapshot, installedKey, installedExpired, accounts)
            Page("Quick Connect · ${server.name}") {
                Text(
                    "Open Jellyfin on your phone or computer → Settings → Quick Connect, " +
                        "and enter this code"
                )
                screen.code?.let { code -> QuickConnectCode(code) }
                if (screen.renewed) StatusLine("The previous code is no longer valid. Enter this new code.")
                if (screen.busy) {
                    StatusLine("Getting a code…")
                } else if (!screen.disabled && screen.error == null) {
                    StatusLine("Waiting for approval. Expired codes are renewed automatically.")
                }
                if (screen.disabled) StatusLine("Quick Connect is disabled on this server. Use your username and password.")
                Message(screen.error)
                if (screen.error != null) Action("Try again", initial = screen.code == null, onClick = vm::start)
                Action("Back") { nav.popBackStack() }
            }
        }

        composable("home") {
            state.snapshot.currentAccount?.let { account ->
                val profile: HomeViewModel = koinViewModel(key = account.key, parameters = { parametersOf(account) })
                val vm: CatalogHomeViewModel = koinViewModel(parameters = { parametersOf(account, catalog) })
                val screen by vm.state.collectAsStateWithLifecycle()
                val profileState by profile.state.collectAsStateWithLifecycle()
                CatalogHome(
                    screen.copy(busy = profileState.busy, error = screen.error ?: profileState.error),
                    profileState.account.copy(server = account.server),
                    onOpen = { nav.navigate(itemRoute(it)) },
                    onRetry = {
                        profile.verify()
                        vm.reload()
                    },
                    onSwitchUser = { nav.navigate("accounts") { launchSingleTop = true } },
                    onSignOut = profile::signOut,
                    onSearch = { nav.navigate(searchRoute()) },
                    onResume = vm::reload,
                    onSettings = { nav.navigate("settings") }
                )
            }
        }
        aboutScreens(nav)
        supporterScreen(nav)
        composable("this-tv") {
            ThisTvScreen(koinInject()) { nav.popBackStack() }
        }
        composable("settings") {
            state.snapshot.currentAccount?.let { account ->
                val vm: PlaybackSettingsViewModel = koinViewModel(key = account.key, parameters = { parametersOf(account) })
                val screen by vm.state.collectAsStateWithLifecycle()
                val settingsContext = LocalContext.current
                var accepted by remember { mutableStateOf("Checking…") }
                DisposableEffect(Unit) {
                    val probe = DeviceCapabilityProbe(settingsContext)
                    fun update(capabilities: app.pictureshow.core.playback.DeviceCapabilities) {
                        accepted = passthroughEncodingLabels(capabilities.audioOutput.passthrough)
                    }
                    update(probe.snapshot())
                    probe.start(::update)
                    onDispose { probe.close() }
                }
                PlaybackSettingsScreen(
                    screen,
                    accepted,
                    vm::local,
                    vm::server,
                    vm::reload,
                    onBack = { nav.popBackStack() },
                    onThisTv = { nav.navigate("this-tv") },
                    onAbout = { nav.navigate("about") },
                    onSupport = { nav.navigate("supporter") }
                )
            }
        }
        composable(
            "search?query={query}&keyboard={keyboard}",
            arguments = listOf(
                navArgument("query") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("keyboard") {
                    type = NavType.BoolType
                    defaultValue = true
                }
            )
        ) { entry ->
            state.snapshot.currentAccount?.takeUnless { it.expired }?.let { account ->
                val vm: SearchViewModel = koinViewModel(parameters = { parametersOf(account, catalog) })
                val screen by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(entry) {
                    vm.initialize(entry.savedStateHandle, entry.arguments?.getString("query").orEmpty())
                }
                SearchScreen(
                    screen, account, entry.arguments?.getBoolean("keyboard") ?: true,
                    vm::query, vm::submit, { vm.query(it, immediate = true) }, vm::clearRecent, vm::retry,
                    onOpen = {
                        vm.opened()
                        nav.navigate(itemRoute(it))
                    },
                    keyboardRequest = searchKeyboardRequest
                )
            }
        }
        composable(
            "player?item={item}&start={start}",
            arguments = listOf(
                navArgument("item") { type = NavType.StringType },
                navArgument("start") {
                    type = NavType.LongType
                    defaultValue = 0L
                }
            )
        ) { entry ->
            state.snapshot.currentAccount?.let { account ->
                val item = remember(entry) { Json.decodeFromString<MediaItem>(requireNotNull(entry.arguments?.getString("item"))) }
                // Recover to detail rather than replaying the route's original start offset.
                PlayerRestoration(item, onRestore = {
                    if (nav.currentBackStackEntry?.id == entry.id) recoverPlaybackEntry(nav, it)
                }) { onCurrentItem ->
                    PlayerScreen(account, item, entry.arguments?.getLong("start") ?: 0, catalog, onCurrentItem, onExit = exitPlayback)
                }
            }
        }
        composable("item?item={item}", arguments = listOf(navArgument("item") { type = NavType.StringType })) { entry ->
            state.snapshot.currentAccount?.let { account ->
                val item = remember(entry) { Json.decodeFromString<MediaItem>(requireNotNull(entry.arguments?.getString("item"))) }
                if (item.type == "Person") {
                    val vm: PersonViewModel = koinViewModel(parameters = { parametersOf(account, item, catalog) })
                    val screen by vm.state.collectAsStateWithLifecycle()
                    PersonScreen(screen, { nav.navigate(itemRoute(it)) }, vm::nearEnd, vm::retry, { nav.popBackStack() })
                } else if (item.isFolder &&
                    item.type !in listOf("Series", "Season") ||
                    item.type in listOf("CollectionFolder", "BoxSet", "Folder")
                ) {
                    val vm: CatalogGridViewModel = koinViewModel(parameters = { parametersOf(account, item, catalog) })
                    val screen by vm.state.collectAsStateWithLifecycle()
                    CatalogGrid(screen, { nav.navigate(itemRoute(it)) }, vm::nearEnd, vm::sort, vm::retry)
                } else {
                    val vm: CatalogDetailViewModel = koinViewModel(parameters = { parametersOf(account, item, catalog) })
                    val screen by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(entry) {
                        entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            vm.resumed()
                            awaitCancellation()
                        }
                    }
                    CatalogDetail(
                        screen,
                        { nav.navigate(itemRoute(it)) },
                        vm::season,
                        vm::played,
                        vm::favorite,
                        vm::reload,
                        onPlay = { playable, resume -> nav.navigate(playerRoute(playable, resume)) },
                        onFocusRestored = { target, focused -> exitHandoff?.finish(entry.id, target, focused) },
                        onPendingAction = { action -> exitHandoff?.accessibleSelect(entry.id, action) ?: false },
                        onPendingFocus = { exitHandoff?.accessibleFocus(entry.id) },
                        onPendingQueueNeeded = {
                            if (nav.currentBackStackEntry?.id == entry.id) exitHandoff?.prepare(entry.id)
                        },
                        onPendingControlsReady = { exitHandoff?.live(entry.id, it) },
                        onPendingQueueExpired = { exitHandoff?.expired(entry.id) == true },
                        origin = item
                    )
                }
            }
        }
    }
}

/** Keep the Add-server heading visible while large-text statuses and actions scroll below it. */
@Composable
private fun ServerDiscoveryPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().background(Color(0xFF10131C)).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Find your Jellyfin server",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() }
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun Page(title: String, modifier: Modifier = Modifier, announceTitle: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val restoring = LocalRestoringConfirmationFocus.current
    Box(modifier.fillMaxSize().background(Color(0xFF10131C)).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics {
                    if (announceTitle && !restoring) heading() else hideFromAccessibility()
                }
            )
            content()
        }
    }
}

@Composable
private fun Action(label: String, modifier: Modifier = Modifier, initial: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    val restoring = LocalRestoringConfirmationFocus.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusRequester(focus).focusProperties {
            // The acknowledgement predicate assumes the offered action owns both cursors.
            // Hiding siblings from TalkBack alone still lets a fast remote key reach them.
            canFocus = !restoring || initial
        }.semantics {
            if (restoring && !initial) hideFromAccessibility()
        }
    ) { Text(label) }
    LaunchedEffect(Unit) {
        if (initial && enabled) {
            focus.requestFocus()
        }
    }
}

@Composable
private fun Message(message: String?) {
    if (message != null) StatusLine(message, color = Color(0xFFFFCCB8))
}

@Composable
internal fun QuickConnectCode(code: String, announce: ((String) -> Unit)? = null) {
    val accessible = rememberAccessibilityEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val host = LocalView.current
    val announcer by rememberUpdatedState(announce)
    val focus = remember { FocusRequester() }
    var canFocus by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val label = "Quick Connect code ${code.toList().joinToString(" ")}"
    Text(
        code,
        style = MaterialTheme.typography.displayLarge,
        modifier = Modifier.focusRequester(focus).focusProperties { this.canFocus = canFocus && !accessible }.then(
            if (published) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
        ).readableFocus().semantics { contentDescription = label }
    )
    LaunchedEffect(lifecycle) {
        do {
            published = false
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            canFocus = true
            withFrameNanos { }
            // With a screen reader, retain its existing target and announce separately.
            // A programmatic input-focus event can arrive late and repeat that speech.
            // Without a reader, give the remote its normal code target while hidden.
            if (!accessible) focus.requestFocus()
            published = true
            repeat(2) { withFrameNanos { } }
        } while (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        ready = true
    }
    LaunchedEffect(label, ready, lifecycle) {
        if (ready) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            val deliver = announcer
            if (deliver != null) {
                deliver(label)
            } else {
                // A bounded announcement handles arrival and renewal without moving either
                // cursor. Live-region delivery is unreliable for this focused TV code label.
                @Suppress("DEPRECATION")
                host.announceForAccessibility(label)
            }
        }
    }
}

@Composable
internal fun StatusLine(message: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val restoring = LocalRestoringConfirmationFocus.current
    // Focus changes must not look like content updates to a live region. Keep the text on
    // its own semantics node; the focusable container reads its non-actionable child.
    // A very wide line can lose spatial Up/Down to a more distant narrow button.
    // Keep status centres near the left-aligned controls, including at larger text sizes.
    Box(
        Modifier.widthIn(max = 360.dp).focusProperties { canFocus = !restoring }.then(
            if (restoring) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
        ).readableFocus().padding(12.dp)
    ) {
        Text(message, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun RemoteField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    initial: Boolean = false,
    password: Boolean = false,
    enabled: Boolean = true,
    onDone: () -> Unit = {}
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Read-only until the remote's center button is pressed: a writable text field opens the keyboard as soon as it is
    // focused, and on a TV the keyboard then takes the D-pad and the first press types its highlighted key.
    var editing by remember { mutableStateOf(false) }
    val navigation = remember { RemoteFieldNavigation() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The field supplies this label in both traversal directions. Clear the caption
        // text too: TalkBack counts hidden text when deciding whether a group is a stop.
        Text(label, modifier = Modifier.clearAndSetSemantics {})
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            readOnly = !editing,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color(0xFFB8C4FF)),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                editing = false
                onDone()
            }),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) {
                        editing = false
                        navigation.clear()
                    }
                }
                .onPreviewKeyEvent {
                    when {
                        !enabled -> false
                        navigation.handle(it) { key ->
                            editing = false
                            keyboard?.hide()
                            focusManager.moveFocus(if (key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        } -> true
                        // Consume both halves so opening the keyboard on release cannot type a key into it.
                        it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter -> {
                            if (it.type == KeyEventType.KeyUp) {
                                if (editing) keyboard?.show() else editing = true
                            }
                            true
                        }
                        else -> false
                    }
                }
                .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFB8C4FF) else Color(0xFF747A8E), RoundedCornerShape(8.dp))
                .background(Color(0xFF202635), RoundedCornerShape(8.dp))
                .padding(16.dp)
                .semantics {
                    contentDescription = label
                    // The field remains read-only for hardware focus until activated, but is editable
                    // through accessibility. Preserve BasicTextField's password transformation.
                    isEditable = enabled
                    setText {
                        if (enabled) onValue(it.text)
                        enabled
                    }
                }
                .editWithKeyboard {
                    focus.requestFocus()
                    editing = true
                    keyboard?.show()
                }
        )
    }
    LaunchedEffect(Unit) { if (initial) focus.requestFocus() }
}
