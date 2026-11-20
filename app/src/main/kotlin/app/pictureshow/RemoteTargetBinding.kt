/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.pictureshow.core.Account
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Both Android shells accept the same playback opens and navigation commands. */
@Composable
internal fun RemoteTargetBinding(account: Account?, catalog: CatalogSession, nav: NavHostController, remoteTarget: RemoteTargetHost) {
    val context = LocalContext.current
    val music: MusicHost = koinInject()
    val remoteAccount by rememberUpdatedState(account)
    val remoteCatalog by rememberUpdatedState(catalog)
    LaunchedEffect(remoteTarget) {
        launch {
            remoteTarget.opens.collect { effect ->
                val viewer = remoteAccount?.takeUnless { it.expired }
                val item = effect.items.getOrNull(effect.index ?: 0)
                if (viewer != null && item != null && music.openFromRemote(viewer, effect.items, effect.index ?: 0)) {
                    remoteTarget.resolved(effect.generation, true)
                    return@collect
                }
                val resolved = if (viewer != null && item != null) {
                    resolvePlaybackEntry(PlaybackEntry(item, effect.ticks?.div(10_000)), viewer, remoteCatalog)
                } else {
                    ResolvedPlaybackEntry.Unavailable
                }
                val open = resolved as? ResolvedPlaybackEntry.Open
                if (remoteTarget.resolved(effect.generation, open != null)) {
                    navigatePlaybackEntry(nav, requireNotNull(open))
                } else if (open == null) {
                    Toast.makeText(context, ENTRY_UNAVAILABLE, Toast.LENGTH_SHORT).show()
                }
            }
        }
        remoteTarget.navigation.collect { name ->
            val code = remoteKeyCode(name)
            if (code != null) {
                context.playerActivity()?.pressRemoteKey(code)
            } else if (name == "GoHome" && remoteAccount?.expired == false) {
                nav.navigate("home") {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }
}
