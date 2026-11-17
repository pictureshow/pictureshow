/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Stream C2's one hook in [DesktopController.updateState]: it runs synchronously on every committed state, so the lock
 * is decided before the next frame. The current viewer changing asks the PIN gate (AUTH-16) and switches the Seerr
 * connection; leaving Settings ends PIN-change permissions (AUTH-19); playback starting or pausing moves the time away
 * (AUTH-18).
 */
internal fun desktopAccountsObserve(previous: DesktopState, next: DesktopState, pins: DesktopProfileLock, seerr: DesktopSeerr, musicPlaying: Boolean = false) {
    if (previous.account?.key != next.account?.key || previous.account?.signedIn != next.account?.signedIn) {
        pins.viewerChanged(next.account)
        seerr.viewerChanged(next.account)
    }
    if (previous.route == DesktopRoute.Settings && next.route != DesktopRoute.Settings) pins.leftSettings()
    pins.playing(desktopVideoPlaying(next) || musicPlaying)
}

/** Video counts while it is really playing; music (stream B) counts while audio is meant to come out. */
internal fun desktopVideoPlaying(s: DesktopState) = s.playback?.let { it.ready && !it.paused && it.end == null } == true

/** The window's minimized and focused states feed the time away (AUTH-18). */
internal fun attachPinWindow(window: Window, pins: DesktopProfileLock) {
    if (window.windowListeners.any { it is PinWindowListener }) return
    val listener = PinWindowListener(window, pins)
    window.addWindowListener(listener)
    window.addWindowFocusListener(listener)
    window.addWindowStateListener(listener)
    listener.report()
}

private class PinWindowListener(private val window: Window, private val pins: DesktopProfileLock) : WindowAdapter() {
    fun report(opposite: Window? = null) {
        val iconified = (window as? Frame)?.let { it.extendedState and Frame.ICONIFIED != 0 } ?: false
        // A dialog of ours (a PIN dialog, a menu popup) holding focus is still Pictureshow on screen.
        val focused = window.isFocused || window.ownedWindows.any { it.isFocused } || java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.let { active ->
            generateSequence(active) { it.owner }.any { it === window }
        } == true || opposite?.let { o -> generateSequence(o) { it.owner }.any { it === window } } == true
        pins.window(iconified = iconified, focused = focused)
    }
    override fun windowIconified(e: WindowEvent) = report()
    override fun windowDeiconified(e: WindowEvent) = report()
    override fun windowStateChanged(e: WindowEvent) = report()
    override fun windowGainedFocus(e: WindowEvent) = report()
    override fun windowLostFocus(e: WindowEvent) = report(e.oppositeWindow)
    override fun windowActivated(e: WindowEvent) = report()
    override fun windowDeactivated(e: WindowEvent) = report(e.oppositeWindow)
}
