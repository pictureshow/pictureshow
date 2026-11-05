/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflineResourceRole
import org.koin.compose.koinInject

internal const val OFFLINE_DOWNLOADS_ROUTE = "downloads"
private const val OFFLINE_STORAGE_ROUTE = "downloads-storage"

internal fun offlinePlayerRoute(entry: OfflineEntry) = "offline-player?entry=${Uri.encode(entry.id)}"

/** Movies and episodes download as themselves; a series offers the season on screen. */
internal fun offlineDownloadRoute(item: MediaItem, seasonId: String?, seasonName: String?): String? {
    val series = if (item.type == "Series") item.id else item.seriesId
    return when {
        item.type in listOf("Movie", "Episode", "Video") -> offlineRoute(item.id, null, null, item.name)
        item.type in listOf("Series", "Season") && seasonId != null && series != null ->
            offlineRoute(seasonId, series, seasonId, listOfNotNull(item.seriesName ?: item.name.takeIf { item.type == "Series" }, seasonName).joinToString(", "))
        else -> null
    }
}

private fun offlineRoute(item: String, series: String?, season: String?, title: String) =
    "download?item=${Uri.encode(item)}&series=${Uri.encode(series.orEmpty())}&season=${Uri.encode(season.orEmpty())}&title=${Uri.encode(title)}"

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
    val activity = LocalContext.current as? Activity
    return { if (nav.previousBackStackEntry == null) activity?.finish() else nav.popBackStack() }
}

private fun NavController.openLibrary() = navigate("home") {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

/** Everything the local player needs, loaded with no network. */
@Composable
private fun OfflineLocalPlayer(account: Account?, entryId: String, onGone: () -> Unit, content: @Composable (Account, MediaItem, Long) -> Unit) {
    val engine: OfflineEngine = koinInject()
    var ready by remember { mutableStateOf<Triple<OfflineEntry, OfflinePlaybackBinding, Long>?>(null) }
    LaunchedEffect(entryId) {
        val current = account ?: return@LaunchedEffect onGone()
        val entry = engine.ready()[current.key]?.manifest?.entries?.firstOrNull { it.id == entryId }?.takeIf { it.job.phase == OfflinePhase.ready }
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
        ready = Triple(entry, OfflinePlaybackBinding(gateway, entry.sidecarAudio(file)), store.resumeTicks(entry.itemId) / 10_000)
    }
    val loaded = ready ?: return
    val current = account ?: return
    CompositionLocalProvider(LocalOfflinePlayback provides loaded.second) {
        content(current, loaded.first.playerItem(), loaded.third)
    }
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
        OfflineLocalPlayer(account(), entry.text("entry").orEmpty(), { nav.popBackStack() }) { current, item, start ->
            PlayerScreen(current, item, start, catalog(), onExit = { nav.popBackStack() })
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
        OfflineLocalPlayer(account(), entry.text("entry").orEmpty(), { nav.popBackStack() }) { current, item, start ->
            TouchPlayerScreen(current, item, start, catalog(), {}, onExit = { nav.popBackStack() })
        }
    }
}
