/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import app.pictureshow.ui.PictureshowTheme
import kotlin.test.*

/** Compose checks for the leg 172 pilot fixes (hosted gate only: the remote container lacks Skiko). */
@OptIn(ExperimentalTestApi::class)
class DesktopPilotFixesUiTest {
    /** D2: inside the clipping controls Surface, the frame still draws in its own popup layer above the band. */
    @Test fun trickplayFrameDrawsInAPopupNotInsideTheClippedControlsBand() = runComposeUiTest {
        val info = TrickplayInfo(width = 320, height = 180, tileWidth = 10, tileHeight = 10, thumbnailCount = 100, interval = 10_000)
        setContent { PictureshowTheme {
            Column(Modifier.size(800.dp, 600.dp)) {
                Spacer(Modifier.weight(1f))
                Surface(Modifier.fillMaxWidth().height(40.dp)) {
                    Box(Modifier.fillMaxWidth().height(20.dp)) {
                        TrickplayFrame(ImageBitmap(3200, 1800), info, TrickplayTile(info, 0, 1, 2), "Preview at 0:33", 300f, 1000)
                    }
                }
            }
        } }
        onNodeWithContentDescription("Preview at 0:33").assertExists()
        assertEquals(2, onAllNodes(isRoot()).fetchSemanticsNodes().size, "the frame must be in a popup, outside the band's clip")
    }
}
