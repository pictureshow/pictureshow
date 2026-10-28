/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Call at the navigation host, so removing the player cannot cancel its bounded exit observation. */
@Composable
internal fun rememberPlaybackExit(nav: NavHostController, handoff: PlaybackExitHandoff? = null): () -> Unit {
    val scope = rememberCoroutineScope()
    val host = LocalView.current
    DisposableEffect(nav, handoff) {
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            handoff?.destinationChanged(controller.currentBackStackEntry?.id)
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    return remember<() -> Unit>(nav, scope, host, handoff) {
        {
            nav.popBackStack()
            nav.currentBackStackEntry?.takeIf { it.destination.route?.substringBefore('?') == "item" }?.let {
                handoff?.begin(it.id)
            }
            scope.launch { reportPlaybackExitDestination(nav, host) }
        }
    }
}

/** Read Compose input focus without depending on an accessibility service having queried its nodes. */
internal fun playbackContentFocused(host: View): Boolean? = runCatching {
    val owner = (host as? RootForTest)?.semanticsOwner ?: return@runCatching null
    fun focused(node: SemanticsNode): Boolean {
        if (node.config.contains(SemanticsProperties.HideFromAccessibility) || node.boundsInRoot.isEmpty) return false
        return node.config.getOrNull(SemanticsProperties.Focused) == true || node.children.any(::focused)
    }
    focused(owner.rootSemanticsNode)
}.getOrNull()

internal suspend fun reportPlaybackExitDestination(nav: NavHostController, host: View) {
    // One bounded snapshot, not a promise that a slow return has settled. Never delay navigation.
    delay(1_000)
    val entry = nav.currentBackStackEntry
    val destination = when (entry?.destination?.route?.substringBefore('?')) {
        "item" -> "item"
        "home" -> "home"
        "search" -> "search"
        "player" -> "player"
        null -> "none"
        else -> "other"
    }
    // Deliberately no route arguments, item titles, node labels or account data.
    val contentFocused = playbackContentFocused(host) ?: "unknown"
    PlaybackDiagnostics.write(
        "exit-destination sampleMs=1000 destination=$destination lifecycle=${entry?.lifecycle?.currentState ?: "none"} " +
            "windowFocused=${host.hasWindowFocus()} viewFocused=${host.hasFocus()} contentFocused=$contentFocused"
    )
}
