/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.serverLabel
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun TouchPictureshow(
    session: SessionViewModel = koinViewModel(),
    searchEntry: String? = null,
    consumeSearchEntry: () -> Unit = {},
    playbackEntry: ParsedPlaybackEntry? = null,
    entrySequence: Long = 0,
    consumePlaybackEntry: () -> Unit = {}
) {
    val state by session.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val exitPlayback = rememberPlaybackExit(nav)
    val backStackEntry by nav.currentBackStackEntryAsState()
    val context = LocalContext.current
    val catalogService: CatalogService = koinInject()
    val accounts: AccountStore = koinInject()
    val catalog =
        remember(state.snapshot.currentKey, state.snapshot.currentAccount?.accessToken) {
            CatalogSession(catalogService, accounts) {
                // Touch catalog has no TV launcher channels.
            }
        }
    if (state.loading) {
        TouchShell(false, null, {}) { TouchPage("Pictureshow") { Text("Loading saved accounts…") } }
        return
    }
    if (state.error != null) {
        TouchShell(false, null, {}) {
            TouchPage("Pictureshow") {
                TouchMessage(state.error)
                TouchAction("Retry", onClick = session::load)
            }
        }
        return
    }
    // Navigation restores its own stack. Save the identity that installed it too: the first
    // composition after recreation is not an account change. Do not put credentials in saved state.
    var accountInstalled by rememberSaveable { mutableStateOf(false) }
    var installedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var installedExpired by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Credentials stay in memory. Recreated navigation already belongs to the loaded account.
    var installedToken by remember { mutableStateOf(state.snapshot.currentAccount?.accessToken) }
    val route = backStackEntry?.destination?.route
    val signedIn = state.snapshot.currentAccount?.expired == false
    val inPlayer = route?.startsWith("player?") == true
    val showNavigation = signedIn &&
        route !in listOf("waiting", "servers") &&
        route?.startsWith("login?") != true &&
        route?.startsWith("quick?") != true &&
        !inPlayer
    val remote: RemoteControllerHost = koinInject()
    val remoteState by remote.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.snapshot.currentAccount) { remote.bind(state.snapshot.currentAccount) }
    LifecycleResumeEffect(remote) {
        remote.setForeground(true)
        onPauseOrDispose { remote.setForeground(false) }
    }
    val cast: CastSenderHost = koinInject()
    val castState by cast.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.snapshot.currentAccount) { cast.bind(state.snapshot.currentAccount) }
    LifecycleResumeEffect(cast) {
        cast.setForeground(true)
        onPauseOrDispose { cast.setForeground(false) }
    }
    // What the viewer asked to play while choosing. Picking runs it once; closing the chooser forgets it.
    var remotePending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun chooseRemote(castRequest: CastPlayRequest? = null, then: (() -> Unit)?) {
        remotePending = then
        cast.openChooser(castRequest)
    }
    LaunchedEffect(castState.chooserOpen) { remote.setPicker(castState.chooserOpen) }
    if (castState.chooserOpen) {
        val castRequest = cast.chooserRequest
        fun close() {
            remotePending = null
            cast.closeChooser()
        }
        TouchCastChooser(
            remoteState,
            castState,
            showCast = castRequest != null,
            onThisDevice = castRequest?.let { request ->
                {
                    request.onThisDevice?.invoke()
                    close()
                }
            },
            onPickRemote = { target ->
                remote.attach(target)
                remotePending?.invoke()
                castRequest?.onJellyfinPick?.invoke()
                close()
                if (nav.currentBackStackEntry?.destination?.route != "remote") nav.navigate("remote")
            },
            onPickCast = { target ->
                cast.pick(target)
                if (cast.state.value.blockedReach == null && cast.state.value.pickRefusal == null) {
                    remotePending = null
                    if (castRequest?.local == null) nav.navigate("cast") { launchSingleTop = true }
                }
            },
            onSetCastEnabled = cast::setEnabled,
            onDismiss = ::close
        )
    }
    TouchCastAttention(castState, cast)
    // Play here, or Resume here with no player left open: the receiver's position, in a new local player.
    LaunchedEffect(castState.localStart?.serial) {
        val start = castState.localStart ?: return@LaunchedEffect
        val account = state.snapshot.currentAccount
        val item = account?.let { catalogService.item(it, start.itemId) } as? app.pictureshow.core.ClientResult.Success
        cast.localStartHandled(start.serial)
        item?.let { nav.navigate(playerRoute(it.value, start.positionMs)) }
    }
    // The shell subcomposes its content; keep effects with the host so its graph exists first.
    TouchShell(showNavigation, touchTabFor(route), { selectTouchTab(nav, it) }, fullscreen = inPlayer, above = {
        if (!inPlayer && route != "remote") TouchRemoteBar(remoteState, remote.now()) { nav.navigate("remote") { launchSingleTop = true } }
        if (!inPlayer && route != "cast") TouchCastBar(castState) { nav.navigate("cast") { launchSingleTop = true } }
    }) {
        LaunchedEffect(state.snapshot.currentKey, state.snapshot.currentAccount?.expired, state.snapshot.currentAccount?.accessToken) {
            val key = state.snapshot.currentKey
            val expired = state.snapshot.currentAccount?.expired
            val token = state.snapshot.currentAccount?.accessToken
            if (accountInstalled && installedKey == key && installedExpired == expired && installedToken == token) return@LaunchedEffect
            // Saved tab stacks retain their ViewModels and queries; they belong to this account.
            if (accountInstalled) nav.graph.forEach { nav.clearBackStack(it.id) }
            installedKey = key
            installedExpired = expired
            installedToken = token
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
        NavHost(navController = nav, startDestination = "waiting") {
            composable("waiting") { TouchPage("Pictureshow") { Text("Loading…") } }
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
                TouchServers(
                    screen,
                    state.snapshot,
                    vm::address,
                    vm::connect,
                    { nav.navigate(serverRoute("login", it)) },
                    vm::scan,
                    { nav.navigate("accounts") { launchSingleTop = true } },
                    onBack = if (nav.previousBackStackEntry != null) {
                        (
                            {
                                nav.popBackStack()
                                Unit
                            }
                            )
                    } else {
                        null
                    }
                )
            }
            composable("accounts") { entry ->
                val vm: AccountsViewModel = koinViewModel()
                val screen by vm.state.collectAsStateWithLifecycle()
                SignInCompletion(screen.selected, entry.id, nav, state.snapshot, installedKey, installedExpired, accounts)
                TouchAccounts(
                    screen,
                    state.snapshot,
                    onSelect = { account ->
                        if (account.expired && account.key == state.snapshot.currentKey) {
                            nav.navigate(serverRoute("login", account.server) + "&username=${Uri.encode(account.user.name)}")
                        } else {
                            vm.select(account.key)
                        }
                    },
                    onRemoveAccount = vm::removeAccount,
                    onRemoveServer = vm::removeServer,
                    onSignIn = { nav.navigate(serverRoute("login", it)) },
                    onAddServer = { nav.navigate("servers") },
                    onBack = if (nav.previousBackStackEntry != null) {
                        (
                            {
                                nav.popBackStack()
                                Unit
                            }
                            )
                    } else {
                        null
                    }
                )
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
                DisposableEffect(vm, activity) { onDispose { vm.leave(preserveInput = activity?.isChangingConfigurations == true) } }
                val expired = state.snapshot.currentAccount?.let {
                    it.expired && it.server.id == server.id && it.server.url == server.url
                } == true
                BackHandler(enabled = nav.previousBackStackEntry == null) { leaveLogin(nav) }
                TouchLogin(
                    screen,
                    server,
                    expired,
                    vm::username,
                    vm::password,
                    vm::signIn,
                    { nav.navigate(serverRoute("quick", server)) },
                    { leaveLogin(nav) },
                    state.snapshot.serverLabel(server)
                )
            }
            composable("quick?server={server}", arguments = listOf(navArgument("server") { type = NavType.StringType })) { entry ->
                val server = remember(entry) { Json.decodeFromString<Server>(requireNotNull(entry.arguments?.getString("server"))) }
                val loginEntry = remember(entry) { nav.getBackStackEntry("login?server={server}&username={username}") }
                val vm: QuickConnectViewModel = koinViewModel(
                    viewModelStoreOwner = loginEntry,
                    parameters = { parametersOf(server, loginEntry.savedStateHandle) }
                )
                val screen by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.start() }
                SignInCompletion(screen.signedIn, entry.id, nav, state.snapshot, installedKey, installedExpired, accounts)
                TouchQuickConnect(screen, server, vm::start, state.snapshot.serverLabel(server)) { nav.popBackStack() }
            }
            listOf("home", "libraries").forEach { destination ->
                composable(destination) {
                    state.snapshot.currentAccount?.let { account ->
                        val profile: HomeViewModel = koinViewModel(key = account.key, parameters = { parametersOf(account) })
                        val vm: CatalogHomeViewModel = koinViewModel(parameters = { parametersOf(account, catalog) })
                        val screen by vm.state.collectAsStateWithLifecycle()
                        val profileState by profile.state.collectAsStateWithLifecycle()
                        TouchCatalogHome(
                            screen.copy(busy = profileState.busy, error = screen.error ?: profileState.error),
                            profileState.account.copy(server = account.server),
                            onOpen = { nav.navigate(itemRoute(it)) },
                            onRetry = {
                                profile.verify()
                                vm.reload()
                            },
                            onResume = vm::resumed,
                            librariesOnly = destination == "libraries"
                        )
                    }
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
                    LaunchedEffect(entry) { vm.initialize(entry.savedStateHandle, entry.arguments?.getString("query").orEmpty()) }
                    TouchSearchScreen(
                        screen,
                        account,
                        vm::query,
                        vm::submit,
                        { vm.query(it, immediate = true) },
                        vm::clearRecent,
                        vm::retry,
                        {
                            vm.opened()
                            nav.navigate(itemRoute(it))
                        }
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
                    PlayerRestoration(item, onRestore = {
                        if (nav.currentBackStackEntry?.id == entry.id) recoverPlaybackEntry(nav, it)
                    }) { onCurrentItem ->
                        CompositionLocalProvider(LocalTouchPlayerCast provides TouchPlayerCast(cast, remote)) {
                            TouchPlayerScreen(
                                account,
                                item,
                                entry.arguments?.getLong("start") ?: 0,
                                catalog,
                                onCurrentItem,
                                onExit = exitPlayback
                            )
                        }
                    }
                }
            }
            composable("item?item={item}", arguments = listOf(navArgument("item") { type = NavType.StringType })) { entry ->
                state.snapshot.currentAccount?.let { account ->
                    val item = remember(entry) { Json.decodeFromString<MediaItem>(requireNotNull(entry.arguments?.getString("item"))) }
                    if (item.type == "Person") {
                        val vm: PersonViewModel = koinViewModel(parameters = { parametersOf(account, item, catalog) })
                        val screen by vm.state.collectAsStateWithLifecycle()
                        TouchPersonScreen(screen, { nav.navigate(itemRoute(it)) }, vm::nearEnd, vm::retry) { nav.popBackStack() }
                    } else if (item.isFolder &&
                        item.type !in listOf("Series", "Season") ||
                        item.type in listOf("CollectionFolder", "BoxSet", "Folder")
                    ) {
                        val vm: CatalogGridViewModel = koinViewModel(parameters = { parametersOf(account, item, catalog) })
                        val screen by vm.state.collectAsStateWithLifecycle()
                        TouchCatalogGrid(screen, { nav.navigate(itemRoute(it)) }, vm::nearEnd, vm::sort, vm::retry) { nav.popBackStack() }
                    } else {
                        val vm: CatalogDetailViewModel = koinViewModel(parameters = { parametersOf(account, item, catalog) })
                        val screen by vm.state.collectAsStateWithLifecycle()
                        LaunchedEffect(entry) {
                            entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                // The model's arrival read and a deliberate Retry own the budget; this only refreshes a real return.
                                vm.resumed()
                                awaitCancellation()
                            }
                        }
                        TouchCatalogDetail(
                            screen,
                            { nav.navigate(itemRoute(it)) },
                            vm::season,
                            vm::played,
                            vm::favorite,
                            vm::reload,
                            onPlay = { playable, resume -> nav.navigate(playerRoute(playable, resume)) },
                            // Without a position the TV applies this viewer's own resume, which is newer than this screen.
                            onPlayOnTv = { playable, fromStart ->
                                val startMs = if (fromStart) 0 else playable.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000
                                chooseRemote(
                                    CastPlayRequest(
                                        listOf(castPlayItem(account.server, playable)),
                                        startMs = startMs,
                                        onThisDevice = { nav.navigate(playerRoute(playable, startMs)) }
                                    )
                                ) { remote.playNow(listOf(playable.id), 0L.takeIf { fromStart }) }
                            },
                            onPlayNext = { playable ->
                                if (remoteState.controller?.phase == "attached") {
                                    remote.queue(listOf(playable.id), next = true)
                                } else {
                                    chooseRemote(then = { remote.queue(listOf(playable.id), next = true) })
                                }
                            },
                            onAddToQueue = { playable ->
                                if (remoteState.controller?.phase == "attached") {
                                    remote.queue(listOf(playable.id), next = false)
                                } else {
                                    chooseRemote(then = { remote.queue(listOf(playable.id), next = false) })
                                }
                            },
                            onBack = { nav.popBackStack() },
                            origin = item,
                            onPlayedRequest = vm::playedRequested
                        )
                    }
                }
            }
            composable("remote") {
                TouchRemoteScreen(remoteState, remote, onChoose = { chooseRemote(then = null) }, onBack = { nav.popBackStack() })
            }
            composable("cast") {
                // Only from this screen: Play here may already have opened a player above it.
                TouchCastScreen(castState, cast, onBack = {
                    if (nav.currentBackStackEntry?.destination?.route ==
                        "cast"
                    ) {
                        nav.popBackStack()
                    }
                })
            }
            composable("settings") {
                state.snapshot.currentAccount?.let { account ->
                    val vm: PlaybackSettingsViewModel = koinViewModel(key = account.key, parameters = { parametersOf(account) })
                    val screen by vm.state.collectAsStateWithLifecycle()
                    var accepted by remember { mutableStateOf("Checking…") }
                    DisposableEffect(Unit) {
                        val probe = DeviceCapabilityProbe(context)
                        fun update(value: app.pictureshow.core.playback.DeviceCapabilities) {
                            accepted = passthroughEncodingLabels(value.audioOutput.passthrough)
                        }
                        update(probe.snapshot())
                        probe.start(::update)
                        onDispose { probe.close() }
                    }
                    TouchSettings(
                        screen,
                        accepted,
                        vm::local,
                        vm::server,
                        vm::reload,
                        { nav.navigate("accounts") },
                        { nav.navigate("about") },
                        { nav.navigate("supporter") },
                        onRemote = { nav.navigate("remote") },
                        onThisDevice = { nav.navigate("this-device") }
                    )
                }
            }
            composable("this-device") { TouchThisDevice(koinInject()) { nav.popBackStack() } }
            composable("about") { TouchAbout({ nav.navigate("licenses") }, { nav.popBackStack() }) }
            composable("licenses") {
                val libraries = remember { readLicenseCatalog(context) }
                TouchLicenses(libraries, { nav.navigate("license/$it") }, { nav.popBackStack() })
            }
            composable("license/{index}") { entry ->
                val library = remember(entry) { readLicenseCatalog(context)[entry.arguments!!.getString("index")!!.toInt()] }
                TouchLicense(library) { nav.popBackStack() }
            }
            composable("supporter") {
                val owner = (context.applicationContext as PictureshowApplication).supporter
                val screen by owner.state.collectAsStateWithLifecycle()
                val activity = LocalActivity.current
                LaunchedEffect(owner) { owner.refresh() }
                TouchSupporter(screen, { activity?.let(owner::purchase) }) { nav.popBackStack() }
            }
        }
    }
}
