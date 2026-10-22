package app.pictureshow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    // Only account selection changes reset the graph. Ordinary route changes remain on Back's stack.
    LaunchedEffect(state.snapshot.currentKey, state.snapshot.currentAccount?.expired) {
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
            val discoveryFocus = rememberDiscoveryFocus(screen.discovered.firstOrNull()?.id, screen.connecting)
            Page("Find your Jellyfin server", modifier = discoveryFocus.container) {
                Text("Choose a server on your network, or enter an address.")
                RemoteField("Server address", screen.address, vm::address, initial = true, onDone = { vm.connect() })
                Action(if (screen.connecting) "Connecting…" else "Connect", enabled = !screen.connecting) { vm.connect() }
                Message(screen.error)
                state.snapshot.accounts.map { it.server }.distinctBy { it.id }.forEach { server ->
                    Action("${server.name} · remembered") { nav.navigate(serverRoute("login", server)) }
                }
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
                val initialAccount = state.snapshot.currentAccount ?: state.snapshot.accounts.firstOrNull()
                state.snapshot.accounts.forEach { account ->
                    Action(
                        "${account.user.name} on ${account.server.name}",
                        initial = account.key == initialAccount?.key,
                        enabled = !screen.busy
                    ) {
                        vm.select(account.key)
                    }
                }
                Message(screen.error)
                state.snapshot.accounts.map { it.server }.distinctBy { it.id }.forEach { server ->
                    Action("Sign in another user on ${server.name}") { nav.navigate(serverRoute("login", server)) }
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
            DisposableEffect(Unit) { onDispose { vm.leave() } }
            Page("Sign in to ${server.name}") {
                RemoteField("Username", screen.username, vm::username, initial = true, enabled = !screen.busy)
                RemoteField("Password", screen.password, vm::password, password = true, enabled = !screen.busy, onDone = vm::signIn)
                Action(if (screen.busy) "Signing in…" else "Sign in", enabled = !screen.busy, onClick = vm::signIn)
                Message(screen.error)
                Message(screen.notice)
                if (screen.loading) Text("Loading sign-in options…")
                if (screen.quickConnect) {
                    Action("Use Quick Connect", enabled = !screen.busy) {
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
            val vm: QuickConnectViewModel = koinViewModel(parameters = { parametersOf(server) })
            val screen by vm.state.collectAsStateWithLifecycle()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(vm, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    vm.start()
                    try {
                        awaitCancellation()
                    } finally {
                        vm.stop()
                    }
                }
            }
            DisposableEffect(vm) { onDispose { vm.stop() } }
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
                        accepted = capabilities.audioOutput.passthrough.joinToString(", ") {
                            when (it.codec) {
                                app.pictureshow.core.playback.AudioCodec.AC3 -> "AC-3"
                                app.pictureshow.core.playback.AudioCodec.EAC3 -> "E-AC-3"
                                app.pictureshow.core.playback.AudioCodec.TRUEHD -> "TrueHD"
                                else -> it.codec.name
                            }
                        }.ifEmpty { "PCM only" }
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
                    onThisTv = { nav.navigate("this-tv") }
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
                // Runs once per entry, never when returning from detail.
                LaunchedEffect(entry) {
                    if (screen.query.isEmpty()) vm.query(entry.arguments?.getString("query").orEmpty(), immediate = true)
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
                PlayerScreen(account, item, entry.arguments?.getLong("start") ?: 0, catalog) { nav.popBackStack() }
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

@Composable
private fun RemoteField(
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    // Both halves of the press are consumed, so the keyboard opens only after the key is released
                    // and the release can't reach it.
                    if (it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter) {
                        if (it.type == KeyEventType.KeyUp) {
                            if (editing) keyboard?.show() else editing = true
                        }
                        true
                    } else {
                        false
                    }
                }
                .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFB8C4FF) else Color(0xFF747A8E), RoundedCornerShape(8.dp))
                .background(Color(0xFF202635), RoundedCornerShape(8.dp))
                .padding(16.dp)
                .semantics { contentDescription = label }
        )
    }
    LaunchedEffect(Unit) { if (initial) focus.requestFocus() }
}
