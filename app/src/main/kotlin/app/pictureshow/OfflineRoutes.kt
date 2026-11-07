/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.MediaItem
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflineResourceRole
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext

internal const val OFFLINE_DOWNLOADS_ROUTE = "downloads"
private const val OFFLINE_STORAGE_ROUTE = "downloads-storage"

internal fun offlinePlayerRoute(entry: OfflineEntry) = "offline-player?entry=${Uri.encode(entry.id)}"

/** Movies and episodes download as themselves; a series offers the season on screen. */
internal fun offlineDownloadRoute(item: MediaItem, seasonId: String?, seasonName: String?): String? {
    val series = if (item.type == "Series") item.id else item.seriesId
    return when {
        item.type in listOf("Movie", "Episode", "Video") -> offlineRoute(item.id, null, null, item.name)
        item.type in listOf("Series", "Season") && seasonId != null && series != null ->
            offlineRoute(
                seasonId,
                series,
                seasonId,
                listOfNotNull(
                    item.seriesName ?: item.name.takeIf {
                        item.type == "Series"
                    },
                    seasonName
                ).joinToString(", ")
            )
        else -> null
    }
}

private fun offlineRoute(item: String, series: String?, season: String?, title: String) = "download?item=${Uri.encode(
    item
)}&series=${Uri.encode(series.orEmpty())}&season=${Uri.encode(season.orEmpty())}&title=${Uri.encode(title)}"

private val previewArguments = listOf(
    navArgument("item") { type = NavType.StringType },
    navArgument("series") {
        type = NavType.StringType
        defaultValue = ""
    },
    navArgument("season") {
        type = NavType.StringType
        defaultValue = ""
    },
    navArgument("title") {
        type = NavType.StringType
        defaultValue = ""
    }
)

private fun NavBackStackEntry.text(name: String) = arguments?.getString(name)?.takeIf(String::isNotEmpty)

/** Downloads as the first screen has nothing behind it: Back leaves the app rather than showing an empty graph. */
@Composable
private fun rememberDownloadsBack(nav: NavController): () -> Unit {
    val activity = LocalActivity.current
    return { if (nav.previousBackStackEntry == null) activity?.finish() else nav.popBackStack() }
}

private fun NavController.openLibrary() = navigate("home") {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

/** Everything the local player needs, loaded with no network. */
@Composable
private fun OfflineLocalPlayer(
    account: Account?,
    entryId: String,
    onGone: () -> Unit,
    onLeave: (String) -> Unit,
    content: @Composable (Account, MediaItem, Long) -> Unit
) {
    val engine: OfflineEngine = koinInject()
    val context = LocalContext.current
    val identity: DeviceIdentityStore = koinInject()
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf<Triple<OfflineEntry, OfflinePlaybackBinding, Long>?>(null) }
    LaunchedEffect(entryId) {
        val current = account ?: return@LaunchedEffect onGone()
        val entry =
            engine.ready()[current.key]?.manifest?.entries?.firstOrNull { it.id == entryId }?.takeIf { it.job.phase == OfflinePhase.ready }
                ?: return@LaunchedEffect onGone()
        val media = entry.resources.firstOrNull { it.role == OfflineResourceRole.media } ?: return@LaunchedEffect onGone()
        val store = engine.progressStore(current)
        val file = { path: String -> engine.files.resource(current.key, entry.id, path) }
        val gateway = OfflinePlaybackGateway(
            entry,
            file(media.path),
            { id -> entry.resources.firstOrNull { it.id == id }?.let { file(it.path) } },
            store,
            store.newSequence(),
            engine::syncSoon
        )
        val api = offlineInteractiveApi(context, current, identity)
        val reconnect = OfflineReconnect(
            entry,
            scope,
            { measuredNetwork(context).name },
            OfflineApiReconnectProbe(api, current, entry),
            OfflineStoreHandoff(gateway, store, entry.itemId, OfflineApiProgressRemote(api))
        ) { start -> onLeave(offlineStreamRoute(entry, start)) }
        val next = OfflineNext(
            { engine.libraries.value[current.key]?.manifest?.entries.orEmpty() },
            entry,
            { onLeave(offlinePlayerRoute(it)) },
            onGone
        )
        val binding = OfflinePlaybackBinding(gateway, entry.sidecarAudio(file), offlineUndownloadedStreams(entry), reconnect, next)
        ready = Triple(entry, binding, store.resumeTicks(entry.itemId) / 10_000)
    }
    val loaded = ready ?: return
    val current = account ?: return
    CompositionLocalProvider(LocalOfflinePlayback provides loaded.second) {
        content(current, loaded.first.playerItem(), loaded.third)
    }
}

/** Viewing online here moves the server's position; a later local viewing starts there and doesn't see it as someone else's progress. */
internal fun offlineAwareGateway(
    context: Context,
    account: Account,
    itemId: String,
    identity: DeviceIdentityStore,
    server: PlaybackGateway
): PlaybackGateway {
    val engine = GlobalContext.getOrNull()?.getOrNull<OfflineEngine>() ?: return server
    return OfflineOnlineViewingGateway(server) {
        engine.onlineViewingEnded(account, itemId) { offlineInteractiveApi(context, account, identity).userData(account, itemId) }
    }
}

private suspend fun offlineInteractiveApi(context: Context, account: Account, identity: DeviceIdentityStore) = OfflineApi(
    offlineReconnectHttp,
    ClientIdentity(
        deviceDisplayName(context),
        account.deviceId?.takeIf(String::isNotBlank) ?: identity.getOrCreate(),
        BuildConfig.VERSION_NAME
    )
)

/** The player owns the whole screen on every route that plays, downloaded or streamed. */
internal fun isTouchPlayerRoute(route: String?): Boolean =
    listOf("player?", "offline-player?", "online-player?").any { route?.startsWith(it) == true }

internal fun offlineStreamRoute(entry: OfflineEntry, start: OfflineStreamStart) =
    "online-player?entry=${Uri.encode(entry.id)}&start=${start.positionMs}" +
        "&audio=${start.audioIndex ?: ""}&subtitle=${start.subtitleIndex ?: ""}"

internal const val OFFLINE_STREAM_ROUTE = "online-player?entry={entry}&start={start}&audio={audio}&subtitle={subtitle}"

internal val offlineStreamArguments = listOf(
    navArgument("entry") { type = NavType.StringType },
    navArgument("start") {
        type = NavType.LongType
        defaultValue = 0L
    },
    navArgument("audio") {
        type = NavType.StringType
        defaultValue = ""
    },
    navArgument("subtitle") {
        type = NavType.StringType
        defaultValue = ""
    }
)

/**
 * The normal online player after a handoff: the local player is gone from the back stack, so Back lands on Downloads, and a later local viewing
 * reads where this one ended. A process restored here returns to Downloads rather than replaying the old offset.
 */
@Composable
private fun OfflineStreamPlayer(
    nav: NavController,
    route: NavBackStackEntry,
    account: Account?,
    content: @Composable (Account, MediaItem, Long, (MediaItem) -> Unit) -> Unit
) {
    val engine: OfflineEngine = koinInject()
    val context = LocalContext.current
    val identity: DeviceIdentityStore = koinInject()
    var loaded by remember { mutableStateOf<Pair<OfflineEntry, OfflineHandoffStart>?>(null) }
    LaunchedEffect(route.id) {
        val current = account ?: return@LaunchedEffect Unit.also { nav.popBackStack() }
        val id = route.text("entry").orEmpty()
        val entry = engine.ready()[current.key]?.manifest?.entries?.firstOrNull { it.id == id }
        if (entry == null) {
            nav.popBackStack()
            return@LaunchedEffect
        }
        val tracks = PlaybackStartTracks(entry.sourceId, route.text("audio")?.toIntOrNull(), route.text("subtitle")?.toIntOrNull())
        loaded = entry to OfflineHandoffStart(entry.itemId, tracks) { server ->
            offlineAwareGateway(context, current, entry.itemId, identity, server)
        }
    }
    val (entry, handoff) = loaded ?: return
    val current = account ?: return
    val item = remember(entry) { entry.playerItem() }
    PlayerRestoration(item, onRestore = { if (nav.currentBackStackEntry?.id == route.id) nav.popBackStack() }) { onCurrentItem ->
        CompositionLocalProvider(LocalOfflineHandoff provides handoff) {
            content(current, item, route.arguments?.getLong("start") ?: 0L, onCurrentItem)
        }
    }
}

internal fun NavController.leaveLocalPlayer(from: NavBackStackEntry, route: String) = navigate(route) {
    popUpTo(from.destination.id) { inclusive = true }
}

internal fun NavGraphBuilder.offlineTvScreens(nav: NavController, account: () -> Account?, catalog: () -> CatalogSession) {
    composable(OFFLINE_DOWNLOADS_ROUTE) {
        val current = account() ?: return@composable
        val back = rememberDownloadsBack(nav)
        OfflineDownloadsTvScreen(
            current,
            nav.previousBackStackEntry == null,
            onPlay = { nav.navigate(offlinePlayerRoute(it)) },
            onStorage = { nav.navigate(OFFLINE_STORAGE_ROUTE) },
            onOnline = nav::openLibrary,
            onBack = back
        )
    }
    composable(OFFLINE_STORAGE_ROUTE) {
        val current = account() ?: return@composable
        OfflineStorageTvScreen(current) { nav.popBackStack() }
    }
    composable("download?item={item}&series={series}&season={season}&title={title}", previewArguments) { entry ->
        val current = account() ?: return@composable
        OfflineDownloadTvScreen(
            current,
            entry.text("item").orEmpty(),
            entry.text("series"),
            entry.text("season"),
            entry.text("title").orEmpty(),
            onDone = {
                nav.navigate(OFFLINE_DOWNLOADS_ROUTE) { popUpTo(entry.destination.id) { inclusive = true } }
            },
            onBack = { nav.popBackStack() }
        )
    }
    composable("offline-player?entry={entry}", listOf(navArgument("entry") { type = NavType.StringType })) { entry ->
        OfflineLocalPlayer(
            account(),
            entry.text("entry").orEmpty(),
            { nav.popBackStack() },
            { nav.leaveLocalPlayer(entry, it) }
        ) { current, item, start ->
            PlayerScreen(current, item, start, catalog(), onExit = { nav.popBackStack() })
        }
    }
    composable(OFFLINE_STREAM_ROUTE, offlineStreamArguments) { entry ->
        OfflineStreamPlayer(nav, entry, account()) { current, item, start, onCurrentItem ->
            PlayerScreen(current, item, start, catalog(), onCurrentItem, onExit = { nav.popBackStack() })
        }
    }
}

internal fun NavGraphBuilder.offlineTouchScreens(nav: NavController, account: () -> Account?, catalog: () -> CatalogSession) {
    composable(OFFLINE_DOWNLOADS_ROUTE) {
        val current = account() ?: return@composable
        val back = rememberDownloadsBack(nav)
        val root = nav.previousBackStackEntry == null
        OfflineDownloadsTouchScreen(
            current,
            root,
            onPlay = { nav.navigate(offlinePlayerRoute(it)) },
            onStorage = { nav.navigate(OFFLINE_STORAGE_ROUTE) },
            onOnline = nav::openLibrary,
            onBack = if (root) null else back
        )
    }
    composable(OFFLINE_STORAGE_ROUTE) {
        val current = account() ?: return@composable
        OfflineStorageTouchScreen(current) { nav.popBackStack() }
    }
    composable("download?item={item}&series={series}&season={season}&title={title}", previewArguments) { entry ->
        val current = account() ?: return@composable
        OfflineDownloadTouchScreen(
            current,
            entry.text("item").orEmpty(),
            entry.text("series"),
            entry.text("season"),
            entry.text("title").orEmpty(),
            onDone = {
                nav.navigate(OFFLINE_DOWNLOADS_ROUTE) { popUpTo(entry.destination.id) { inclusive = true } }
            },
            onBack = { nav.popBackStack() }
        )
    }
    composable("offline-player?entry={entry}", listOf(navArgument("entry") { type = NavType.StringType })) { entry ->
        // Held from the route, not the player: the seconds it takes to load the next episode mustn't rotate the screen and back.
        TouchPlayerWindow()
        OfflineLocalPlayer(
            account(),
            entry.text("entry").orEmpty(),
            { nav.popBackStack() },
            { nav.leaveLocalPlayer(entry, it) }
        ) { current, item, start ->
            TouchPlayerScreen(current, item, start, catalog(), {}, onExit = { nav.popBackStack() })
        }
    }
    composable(OFFLINE_STREAM_ROUTE, offlineStreamArguments) { entry ->
        TouchPlayerWindow()
        OfflineStreamPlayer(nav, entry, account()) { current, item, start, onCurrentItem ->
            TouchPlayerScreen(current, item, start, catalog(), onCurrentItem, onExit = { nav.popBackStack() })
        }
    }
}
