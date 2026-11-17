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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

internal val touchTabs = listOf("home" to "Home", "libraries" to "Libraries", "search" to "Search", "settings" to "Settings")

/** The bar with Requests before Settings, shown only while the account is connected to Seerr. */
internal fun touchTabsWithRequests(label: String) = touchTabs.dropLast(1) + (SEERR_ROUTE to label) + touchTabs.last()

internal fun touchTabFor(route: String?): String? = when {
    route == "home" -> "home"
    route in listOf("libraries", "live-tv") -> "libraries"
    route?.startsWith("search?") == true -> "search"
    route == SEERR_ROUTE -> SEERR_ROUTE
    route == SEERR_CONNECT_ROUTE -> "settings"
    route in listOf("settings", "accounts", "about", "licenses", "supporter") || route?.startsWith("license/") == true -> "settings"
    else -> null
}

/** Where the root a page was opened under is kept with the page itself (TOUCH-02), so it survives Back and restoration. */
internal const val TOUCH_OWNER_KEY = "touch.owner"

/**
 * TOUCH-02: the lit root. A root page owns itself; any other page keeps the root that was lit when it opened (a title
 * opened from Home stays under Home), which is [stored] once it has been recorded. Returns the lit root and whether
 * [previous] should be recorded as this page's owner now.
 */
internal fun touchOwner(route: String?, stored: String?, previous: String?): Pair<String?, Boolean> {
    touchTabFor(route)?.let { return it to false }
    stored?.let { return it to false }
    return previous to (previous != null)
}

/** The tab to light for the page on screen; see [touchOwner]. */
@Composable
internal fun rememberTouchLitTab(entry: NavBackStackEntry?): String? {
    // The tab lit for the page before this one, its route, and the first page above Home it sits on. Plain memory, not
    // state: it is only read when a page first opens.
    val last = remember { arrayOfNulls<String>(3) }
    val handle = entry?.let { runCatching { it.savedStateHandle }.getOrNull() }
    val route = entry?.destination?.route
    val (lit, record) = touchOwner(route, handle?.get<String>(TOUCH_OWNER_KEY), last[0])
    SideEffect {
        if (record) {
            handle?.set(TOUCH_OWNER_KEY, lit)
            // Home's pages sit right above its root; each remembers the first of them, which brings them all back.
            if (lit == "home") handle?.set(TOUCH_HOME_FIRST_KEY, if (last[1] == "home") route else last[2])
        }
        last[0] = lit
        last[1] = route
        last[2] = handle?.get<String>(TOUCH_HOME_FIRST_KEY)
    }
    return lit
}

/** On a page opened inside Home: the route of the first page above Home's root, under which Navigation saves them. */
private const val TOUCH_HOME_FIRST_KEY = "touch.homeFirst"

internal fun selectTouchTab(nav: NavHostController, tab: String) {
    val destination = if (tab == "search") searchRoute() else tab
    if (touchTabFor(nav.currentBackStackEntry?.destination?.route) == tab &&
        nav.currentBackStackEntry?.destination?.route in
        listOf("home", "libraries", "settings", SEERR_ROUTE, "search?query={query}&keyboard={keyboard}")
    ) {
        return
    }
    // The tab already showing, from a screen opened inside it: its root. Saving and restoring that stack would land right back here.
    val root = if (tab == "search") "search?query={query}&keyboard={keyboard}" else tab
    if (tab != "home" && nav.popBackStack(root, inclusive = false)) return
    val home = runCatching { nav.getBackStackEntry("home") }.getOrNull()
    val inHome = touchPageOwner(nav.currentBackStackEntry) == "home"
    // TOUCH-02a for Home too: its pages sit right above the Home root, and Navigation saves them under the first of
    // them, so that page's route is what brings them back. Leaving from the root, or reselecting Home, forgets it.
    val homePlace = home?.let { runCatching { it.savedStateHandle.get<String>(TOUCH_HOME_PLACE_KEY) }.getOrNull() }
    if (inHome) {
        val above = nav.currentBackStackEntry?.let { runCatching { it.savedStateHandle.get<String>(TOUCH_HOME_FIRST_KEY) }.getOrNull() }
        home?.savedStateHandle?.set(TOUCH_HOME_PLACE_KEY, if (tab == "home") null else above)
    }
    nav.navigate(destination) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        // Home is the root. Restoring its saved stack can restore the startup Libraries
        // destination above it, so an explicit Home selection reveals the root, then Home's own place on top of it.
        restoreState = tab != "home"
    }
    if (tab == "home" && !inHome && homePlace != null) {
        home.savedStateHandle[TOUCH_HOME_PLACE_KEY] = null
        nav.navigate(homePlace) { restoreState = true }
    }
}

/** The first page opened above Home when the viewer left it; see [selectTouchTab]. */
private const val TOUCH_HOME_PLACE_KEY = "touch.homePlace"

/** The root a page belongs to: a root itself, else the one recorded when it opened (TOUCH-02). */
private fun touchPageOwner(entry: NavBackStackEntry?): String? = entry?.let {
    touchTabFor(it.destination.route) ?: runCatching { it.savedStateHandle.get<String>(TOUCH_OWNER_KEY) }.getOrNull()
}

/**
 * A bar or rail label never breaks inside a word. Material 3 keeps the whole label visible up to 2x text, so it stays
 * on one line at the viewer's size when that fits, and steps down only as far as the item's width needs, never below
 * its 1x size. Screen readers still hear the whole label from the text.
 */
@Composable
internal fun TouchNavLabel(label: String) {
    val style = LocalTextStyle.current
    val floor = with(LocalDensity.current) { style.fontSize.value.dp.toSp() }
    BasicText(
        label,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        softWrap = false,
        // At or below 1x text there is nothing to step down to.
        autoSize = if (floor.value < style.fontSize.value) {
            TextAutoSize.StepBased(minFontSize = floor, maxFontSize = style.fontSize, stepSize = 0.5.sp)
        } else {
            null
        }
    )
}

/** Chrome is absent from playback and sign-in. Insets are applied once, outside scrollable screens. */
@Composable
internal fun TouchShell(
    showNavigation: Boolean,
    selected: String?,
    onTab: (String) -> Unit,
    fullscreen: Boolean = false,
    above: @Composable () -> Unit = {},
    tabs: List<Pair<String, String>> = touchTabs,
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
                    tabs.forEach { (route, label) ->
                        NavigationRailItem(
                            selected = selected == route,
                            onClick = { onTab(route) },
                            icon = { Icon(touchNavIcon(route), contentDescription = null) },
                            label = { TouchNavLabel(label) }
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().clipToBounds()) { content() }
                above()
                if (navigationVisible && !rail) {
                    NavigationBar {
                        tabs.forEach { (route, label) ->
                            NavigationBarItem(
                                selected = selected == route,
                                onClick = { onTab(route) },
                                icon = { Icon(touchNavIcon(route), contentDescription = null) },
                                label = { TouchNavLabel(label) }
                            )
                        }
                    }
                }
            }
        }
    }
}
