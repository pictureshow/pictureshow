/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.*

class DesktopPlayerKeysTest {
    private fun action(key: Key, focused: Boolean = false, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false, char: Int = 0) =
        playerKeyAction(key, char, shift, ctrl, alt, meta, focused)

    @Test fun surfaceKeysActOnlyWhenNoControlHasFocus() {
        assertEquals(PlayerKey.Toggle, action(Key.Spacebar))
        assertEquals(PlayerKey.Back, action(Key.DirectionLeft))
        assertEquals(PlayerKey.Forward, action(Key.DirectionRight))
        assertEquals(PlayerKey.VolumeUp, action(Key.DirectionUp))
        assertEquals(PlayerKey.VolumeDown, action(Key.DirectionDown))
        for (key in listOf(Key.Spacebar, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown))
            assertNull(action(key, focused = true), "a focused control keeps $key")
    }

    @Test fun lettersWorkWithFocusAndNeverWithModifiers() {
        val letters = mapOf(Key.K to PlayerKey.Toggle, Key.J to PlayerKey.Back, Key.L to PlayerKey.Forward, Key.F to PlayerKey.Fullscreen, Key.M to PlayerKey.Mute)
        for ((key, expected) in letters) {
            assertEquals(expected, action(key)); assertEquals(expected, action(key, focused = true))
            assertNull(action(key, ctrl = true)); assertNull(action(key, alt = true)); assertNull(action(key, meta = true))
        }
        assertNull(action(Key.Spacebar, meta = true))
        assertNull(action(Key.DirectionLeft, alt = true))
    }

    @Test fun questionMarkOpensTheSheetOnAnyLayout() {
        assertEquals(PlayerKey.Help, action(Key.Slash, shift = true))
        assertEquals(PlayerKey.Help, action(Key.Unknown, shift = true, char = '?'.code))
        assertNull(action(Key.Slash))
        assertNull(action(Key.Slash, shift = true, meta = true))
        assertNull(action(Key.Escape), "Escape stays with the window's PLAY-05 order")
    }
}
