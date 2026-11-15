/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.pictureshow.ui.PictureshowTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopPlaybackInfoDialogTest {
    @Test fun keyboardScrollReachesOverflowAndCloseRemainsReachable() = runComposeUiTest {
        var closed = false
        setContent { PictureshowTheme {
            DesktopPlaybackInfoDialog((1..14).map { PlaybackInfoSection("Section $it", listOf("Detail $it")) }) { closed = true }
        } }
        onNodeWithText("Close").assertIsFocused()
        onNodeWithText("Close").performKeyInput { pressKey(Key.MoveEnd) }
        waitForIdle()
        onNodeWithText("Detail 14").assertIsDisplayed()
        onNodeWithText("Close").performKeyInput { pressKey(Key.Enter) }
        assertTrue(closed)
    }
}
