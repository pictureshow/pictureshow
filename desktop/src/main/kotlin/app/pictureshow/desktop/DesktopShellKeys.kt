/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.input.key.Key

/** What a window-level key does once every focused control has had its turn (the bubble phase). */
internal enum class ShellAction { Back, ExitFullscreen, Fullscreen, TogglePlay, ToggleMusic, SeekBack, SeekForward, Help, Search, Settings, Left, Right, Up, Down }

/** One key press as the shell sees it; [char] is the typed character when the platform reports one. */
internal data class ShellKeyPress(
    val key: Key,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val char: Char? = null
)

/** Pages whose fields take typed text, where letters and ? are never shortcuts. */
internal val DESKTOP_TYPING_ROUTES = setOf(DesktopRoute.Servers, DesktopRoute.SignIn, DesktopRoute.QuickConnect, DesktopRoute.Search)
/** Pages that show the sidebar, where the Settings shortcut applies. */
internal val DESKTOP_SIDEBAR_ROUTES = setOf(DesktopRoute.HomeEditor, DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.Settings, DesktopRoute.Search, DesktopRoute.Person, DesktopRoute.MusicLibrary, DesktopRoute.MusicItem, DesktopRoute.MusicNow, DesktopRoute.Downloads, DesktopRoute.WatchTogether, DesktopRoute.Remote)
private val ARROW_ROUTES = setOf(DesktopRoute.HomeEditor, DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.Accounts, DesktopRoute.Settings, DesktopRoute.Search, DesktopRoute.Person, DesktopRoute.MusicLibrary, DesktopRoute.MusicItem, DesktopRoute.MusicNow, DesktopRoute.Downloads, DesktopRoute.WatchTogether, DesktopRoute.Remote)

/**
 * The window's fallback key map. It runs only for keys no focused control consumed, so a button's
 * Space, a slider's arrows, a menu's Escape and a row group's own arrow rules (D-7) come first.
 *
 * - Space and the arrows act on playback only when no control has focus (PLAY-29, D-12); with a
 *   control focused, arrows move focus between controls instead.
 * - Letters (K, J, L, F) and ? work unless a text field may have focus, and never with Ctrl, Cmd or Alt.
 * - Settings is ⌘, on macOS and Ctrl+, elsewhere, from any page with the sidebar.
 * - Away from the video player, with music queued ([music]), Space with nothing focused and K play or pause the
 *   music (MUS-11); a media key's play/pause does it from anywhere but the video player.
 */
internal fun shellFallbackAction(
    press: ShellKeyPress,
    route: DesktopRoute,
    controlFocused: Boolean,
    fullscreen: Boolean,
    mac: Boolean,
    signedIn: Boolean,
    music: Boolean = false
): ShellAction? {
    if (press.key == Key.Escape && !press.ctrl && !press.meta && !press.alt) return if (fullscreen) ShellAction.ExitFullscreen else ShellAction.Back
    val command = if (mac) press.meta && !press.ctrl else press.ctrl && !press.meta
    if (command && !press.alt && !press.shift && press.key == Key.F) return if (signedIn && route in DESKTOP_SIDEBAR_ROUTES) ShellAction.Search else null
    if (command && !press.alt && press.key == Key.Comma) return if (signedIn && route in DESKTOP_SIDEBAR_ROUTES) ShellAction.Settings else null
    if (press.ctrl || press.meta || press.alt) return null
    val typing = route in DESKTOP_TYPING_ROUTES
    if (!typing && (press.char == '?' || (press.key == Key.Slash && press.shift))) return ShellAction.Help
    if (route == DesktopRoute.Player) {
        when (press.key) {
            Key.Spacebar -> return if (controlFocused) null else ShellAction.TogglePlay
            Key.K -> return ShellAction.TogglePlay
            Key.J -> return ShellAction.SeekBack
            Key.L -> return ShellAction.SeekForward
            Key.F -> return ShellAction.Fullscreen
            Key.DirectionLeft -> return if (controlFocused) ShellAction.Left else ShellAction.SeekBack
            Key.DirectionRight -> return if (controlFocused) ShellAction.Right else ShellAction.SeekForward
            Key.DirectionUp -> return if (controlFocused) ShellAction.Up else null
            Key.DirectionDown -> return if (controlFocused) ShellAction.Down else null
        }
        return null
    }
    if (music && !typing && (press.key == Key.K || (press.key == Key.Spacebar && !controlFocused))) return ShellAction.ToggleMusic
    if (music && press.key == Key.MediaPlayPause) return ShellAction.ToggleMusic
    if (route in ARROW_ROUTES) return when (press.key) {
        Key.DirectionLeft -> ShellAction.Left
        Key.DirectionRight -> ShellAction.Right
        Key.DirectionUp -> ShellAction.Up
        Key.DirectionDown -> ShellAction.Down
        else -> null
    }
    return null
}
