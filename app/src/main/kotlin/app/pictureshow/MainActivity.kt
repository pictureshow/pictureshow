package app.pictureshow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
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
                    nav.navigate(serverRoute("login", it))
                }
            }
            val remembered = state.snapshot.rememberedServers
            val discoveryFocus =
                rememberDiscoveryFocus(screen.discovered.firstOrNull()?.id.takeIf { state.snapshot.lastServer == null }, screen.connecting)
            Page("Find your Jellyfin server", modifier = discoveryFocus.container) {
                Text("Choose a server on your network, or enter an address.")
                remembered.forEachIndexed { index, server ->
                    val label = if (server == state.snapshot.lastServer) "Sign in to ${server.name}" else "${server.name} · remembered"
                    Action(label, initial = index == 0 && state.snapshot.lastServer != null) { nav.navigate(serverRoute("login", server)) }
                }
                RemoteField(
                    "Server address",
                    screen.address,
                    vm::address,
                    initial = state.snapshot.lastServer == null,
                    onDone = { vm.connect() }
                )
                Action(if (screen.connecting) "Connecting…" else "Connect", enabled = !screen.connecting) { vm.connect() }
                Message(screen.error)
                screen.discovered.forEachIndexed { index, server ->
                    Action(
                        "${server.name} · ${server.address}",
                        enabled = !screen.connecting,
                        modifier = if (index == 0) discoveryFocus.firstServer else Modifier
                    ) { vm.connect(server.address) }
                }
                Text(
                    if (screen.scanning) {
                        "Looking for servers…"
                    } else if (screen.discovered.isEmpty()) {
                        "No servers found. You can enter an address above."
                    } else {
                        "Discovery complete."
                    }
                )
                Action("Search again", enabled = !screen.scanning, onClick = vm::scan)
                if (state.snapshot.accounts.isNotEmpty()) Action("Remembered accounts") { nav.navigate("accounts") }
            }
        }
        composable("accounts") {
            val vm: AccountsViewModel = koinViewModel()
            val screen by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(screen.selected) {
                // Selecting the current account does not change the bootstrap key. Close this
                // transient switcher ourselves; a different account still resets the graph above.
                if (screen.selected &&
                    nav.currentBackStackEntry?.destination?.route == "accounts" &&
                    nav.previousBackStackEntry?.destination?.route == "home"
                ) {
                    nav.popBackStack()
                }
            }
            Page("Who’s watching?") {
                val offerSignedOutServer = state.snapshot.currentAccount == null && state.snapshot.lastServer != null
                val initialAccount = state.snapshot.currentAccount ?: state.snapshot.accounts.firstOrNull()
                state.snapshot.accounts.forEach { account ->
                    Action(
                        "${account.user.name} on ${account.server.name}",
                        initial = !offerSignedOutServer && account.key == initialAccount?.key,
                        enabled = !screen.busy
                    ) {
                        vm.select(account.key)
                    }
                }
                Message(screen.error)
                state.snapshot.rememberedServers.forEachIndexed { index, server ->
                    Action("Sign in another user on ${server.name}", initial = offerSignedOutServer && index == 0) {
                        nav.navigate(serverRoute("login", server))
                    }
                }
                Action("Add a server", initial = state.snapshot.accounts.isEmpty()) { nav.navigate("servers") }
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
                Action(if (screen.busy) "Signing in…" else "Sign in", enabled = !screen.busy, onClick = vm::signIn)
                Message(screen.error)
                Message(screen.notice)
                if (sessionEnded) Text("Your session ended. Sign in again.")
                if (screen.loading) Text("Loading sign-in options…")
                if (screen.quickConnect) {
                    Action("Use Quick Connect", initial = quickFocus, enabled = !screen.busy) {
                        quickFocus = true
                        nav.navigate(serverRoute("quick", server))
                    }
                }
                if (screen.users.isNotEmpty()) Text("Or choose a public user:")
                screen.users.forEach { user -> Action(user.name, enabled = !screen.busy) { vm.username(user.name) } }
                Action("Back", enabled = !screen.busy) { nav.popBackStack() }
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
            Page("Quick Connect · ${server.name}") {
                Text(
                    "Open Jellyfin on your phone or computer → Settings → Quick Connect, " +
                        "and enter this code"
                )
                screen.code?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Quick Connect code ${it.toList().joinToString(" ")}"
                        }
                    )
                }
                if (screen.renewed) Text("The previous code is no longer valid. Enter this new code.")
                if (screen.busy) {
                    Text("Getting a code…")
                } else if (!screen.disabled &&
                    screen.error == null
                ) {
                    Text("Waiting for approval. Expired codes are renewed automatically.")
                }
                if (screen.disabled) Text("Quick Connect is disabled on this server. Use your username and password.")
                Message(screen.error)
                if (screen.error != null) Action("Try again", onClick = vm::start)
                Action("Back", initial = true) { nav.popBackStack() }
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
                    PlayerScreen(account, item, entry.arguments?.getLong("start") ?: 0, catalog, onCurrentItem) { nav.popBackStack() }
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
                            vm.reload()
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
                        onPlay = { playable, resume -> nav.navigate(playerRoute(playable, resume)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Color(0xFF10131C)).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Action(label: String, modifier: Modifier = Modifier, initial: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.focusRequester(focus)) { Text(label) }
    LaunchedEffect(Unit) { if (initial && enabled) focus.requestFocus() }
}

@Composable
private fun Message(message: String?) {
    if (message != null) Text(message, color = Color(0xFFFFCCB8))
}

@OptIn(ExperimentalLayoutApi::class)
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Visibility also covers floating TV keyboards with zero bottom inset. Back may hide the IME
    // without ending editing, so editing alone cannot decide whether arrows should navigate the page.
    val imeVisible = WindowInsets.isImeVisible
    Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
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
                    if (!it.isFocused) editing = false
                }
                .onPreviewKeyEvent {
                    when {
                        !enabled -> false
                        !imeVisible && (it.key == Key.DirectionUp || it.key == Key.DirectionDown) -> {
                            if (it.type == KeyEventType.KeyDown) {
                                editing = false
                                focusManager.moveFocus(if (it.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                            }
                            true
                        }
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
                .semantics { contentDescription = label }
                .editWithKeyboard {
                    focus.requestFocus()
                    editing = true
                    keyboard?.show()
                }
        )
    }
    LaunchedEffect(Unit) { if (initial) focus.requestFocus() }
}
