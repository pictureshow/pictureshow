package app.pictureshow

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Server
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Pictureshow() }
        }
    }
}

private fun serverRoute(screen: String, server: Server) = "$screen?server=${Uri.encode(Json.encodeToString(server))}"

@Composable
private fun Pictureshow(session: SessionViewModel = koinViewModel()) {
    val state by session.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
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
    LaunchedEffect(state.snapshot.currentKey) {
        val destination = if (state.snapshot.currentAccount != null) "home" else if (state.snapshot.accounts.isEmpty()) "servers" else "accounts"
        nav.navigate(destination) { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
    }
    NavHost(navController = nav, startDestination = "waiting") {
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
            Page("Find your Jellyfin server") {
                Text("Choose a server on your network, or enter an address.")
                RemoteField("Server address", screen.address, vm::address, initial = true, onDone = { vm.connect() })
                Action(if (screen.connecting) "Connecting…" else "Connect", enabled = !screen.connecting) { vm.connect() }
                Message(screen.error)
                state.snapshot.accounts.map { it.server }.distinctBy { it.id }.forEach { server ->
                    Action("${server.name} · remembered") { nav.navigate(serverRoute("login", server)) }
                }
                screen.discovered.forEach { server ->
                    Action("${server.name} · ${server.address}", enabled = !screen.connecting) { vm.connect(server.address) }
                }
                Text(if (screen.scanning) "Looking for servers…" else if (screen.discovered.isEmpty()) "No servers found. You can enter an address above." else "Discovery complete.")
                Action("Search again", enabled = !screen.scanning, onClick = vm::scan)
                if (state.snapshot.accounts.isNotEmpty()) Action("Remembered accounts") { nav.navigate("accounts") }
            }
        }
        composable("accounts") {
            val vm: AccountsViewModel = koinViewModel()
            val screen by vm.state.collectAsStateWithLifecycle()
            Page("Who’s watching?") {
                state.snapshot.accounts.forEachIndexed { index, account ->
                    Action("${account.user.name} on ${account.server.name}", initial = index == 0, enabled = !screen.busy) { vm.select(account.key) }
                }
                Message(screen.error)
                state.snapshot.accounts.map { it.server }.distinctBy { it.id }.forEach { server ->
                    Action("Sign in another user on ${server.name}") { nav.navigate(serverRoute("login", server)) }
                }
                Action("Add a server", initial = state.snapshot.accounts.isEmpty()) { nav.navigate("servers") }
            }
        }
        composable("login?server={server}", arguments = listOf(navArgument("server") { type = NavType.StringType })) { entry ->
            val server = remember(entry) { Json.decodeFromString<Server>(requireNotNull(entry.arguments?.getString("server"))) }
            val vm: LoginViewModel = koinViewModel(parameters = { parametersOf(server) })
            val screen by vm.state.collectAsStateWithLifecycle()
            DisposableEffect(Unit) { onDispose { vm.leave() } }
            Page("Sign in to ${server.name}") {
                RemoteField("Username", screen.username, vm::username, initial = true, enabled = !screen.busy)
                RemoteField("Password", screen.password, vm::password, password = true, enabled = !screen.busy, onDone = vm::signIn)
                Action(if (screen.busy) "Signing in…" else "Sign in", enabled = !screen.busy, onClick = vm::signIn)
                Message(screen.error)
                Message(screen.notice)
                if (screen.loading) Text("Loading sign-in options…")
                if (screen.quickConnect) Action("Use Quick Connect", enabled = !screen.busy) { nav.navigate(serverRoute("quick", server)) }
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
                    try { awaitCancellation() } finally { vm.stop() }
                }
            }
            DisposableEffect(vm) { onDispose { vm.stop() } }
            Page("Quick Connect · ${server.name}") {
                Text("Open Jellyfin on your phone or computer → Settings → Quick Connect, and enter this code")
                screen.code?.let { Text(it, style = MaterialTheme.typography.displayLarge, modifier = Modifier.semantics { contentDescription = "Quick Connect code ${it.toList().joinToString(" ")}" }) }
                if (screen.busy) Text("Getting a code…") else if (!screen.disabled && screen.error == null) Text("Waiting for approval. Expired codes are renewed automatically.")
                if (screen.disabled) Text("Quick Connect is disabled on this server. Use your username and password.")
                Message(screen.error)
                if (screen.error != null) Action("Try again", onClick = vm::start)
                Action("Back", initial = true) { nav.popBackStack() }
            }
        }
        composable("home") {
            state.snapshot.currentAccount?.let { account ->
                val vm: HomeViewModel = koinViewModel(key = account.key, parameters = { parametersOf(account) })
                val screen by vm.state.collectAsStateWithLifecycle()
                Page("Pictureshow") {
                    Text("Signed in as ${screen.account.user.name} on ${screen.account.server.name}", style = MaterialTheme.typography.headlineMedium)
                    Text("Your library will appear here in a future update.")
                    Message(screen.error)
                    Action("Switch user", initial = true, enabled = !screen.busy, onClick = vm::switchUser)
                    Action(if (screen.busy) "Please wait…" else "Sign out", enabled = !screen.busy, onClick = vm::signOut)
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF10131C)).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Action(label: String, initial: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.focusRequester(focus)) { Text(label) }
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
    onDone: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onDone() }),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if ((it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) {
                        keyboard?.show()
                        true
                    } else false
                }
                .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFB8C4FF) else Color(0xFF747A8E), RoundedCornerShape(8.dp))
                .background(Color(0xFF202635), RoundedCornerShape(8.dp))
                .padding(16.dp)
                .semantics { contentDescription = label },
        )
    }
    LaunchedEffect(Unit) { if (initial) focus.requestFocus() }
}
