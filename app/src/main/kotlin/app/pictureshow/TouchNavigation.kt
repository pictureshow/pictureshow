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

internal fun touchTabFor(route: String?): String? = when {
    route == "home" -> "home"
    route in listOf("libraries", "live-tv") -> "libraries"
    route?.startsWith("search?") == true -> "search"
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
    // The tab lit for the page before this one. Plain memory, not state: it is only read when a page first opens.
    val last = remember { arrayOfNulls<String>(1) }
    val handle = entry?.let { runCatching { it.savedStateHandle }.getOrNull() }
    val (lit, record) = touchOwner(entry?.destination?.route, handle?.get<String>(TOUCH_OWNER_KEY), last[0])
    SideEffect {
        if (record) handle?.set(TOUCH_OWNER_KEY, lit)
        last[0] = lit
    }
    return lit
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
                        touchTabs.forEach { (route, label) ->
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
