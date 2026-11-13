/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

internal val touchTabs = listOf("home" to "Home", "libraries" to "Libraries", "search" to "Search", "settings" to "Settings")

internal fun touchTabFor(route: String?): String? = when {
    route in listOf("home", "live-tv") -> "home"
    route == "libraries" -> "libraries"
    route?.startsWith("search?") == true -> "search"
    route in listOf("settings", "accounts", "about", "licenses", "supporter") || route?.startsWith("license/") == true -> "settings"
    else -> null
}

internal fun selectTouchTab(nav: NavHostController, tab: String) {
    val destination = if (tab == "search") searchRoute() else tab
    if (touchTabFor(nav.currentBackStackEntry?.destination?.route) == tab &&
        nav.currentBackStackEntry?.destination?.route in listOf("home", "libraries", "settings", "search?query={query}&keyboard={keyboard}")
    ) {
        return
    }
    // The tab already showing, from a screen opened inside it: its root. Saving and restoring that stack would land right back here.
    val root = if (tab == "search") "search?query={query}&keyboard={keyboard}" else tab
    if (tab != "home" && nav.popBackStack(root, inclusive = false)) return
    nav.navigate(destination) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        // Home is the root. Restoring its saved stack can restore the startup Libraries
        // destination above it, so an explicit Home selection must reveal the root itself.
        restoreState = tab != "home"
    }
}

/** Chrome is absent from playback and sign-in. Insets are applied once, outside scrollable screens. */
@Composable
internal fun TouchShell(
    showNavigation: Boolean,
    selected: String?,
    onTab: (String) -> Unit,
    fullscreen: Boolean = false,
    above: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    // Playback owns its control insets; the video itself fills the window behind cutouts.
    val viewport = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()
    BoxWithConstraints(viewport) {
        val rail = maxWidth >= 600.dp
        val navigationVisible = showNavigation && WindowInsets.ime.getBottom(LocalDensity.current) == 0
        Row(Modifier.fillMaxSize()) {
            if (navigationVisible && rail) {
                NavigationRail {
                    touchTabs.forEach { (route, label) ->
                        NavigationRailItem(
                            selected = selected == route,
                            onClick = { onTab(route) },
                            icon = { Icon(touchNavIcon(route), contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().clipToBounds()) { content() }
                above()
                if (navigationVisible && !rail) {
                    NavigationBar {
                        touchTabs.forEach { (route, label) ->
                            NavigationBarItem(
                                selected = selected == route,
                                onClick = { onTab(route) },
                                icon = { Icon(touchNavIcon(route), contentDescription = null) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }
    }
}
