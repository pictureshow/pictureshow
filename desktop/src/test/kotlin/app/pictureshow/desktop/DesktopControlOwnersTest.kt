/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import kotlin.test.*

class DesktopControlOwnersTest {
    @Test fun retiredDetailCannotEraseOrOverwriteReplacementPlayControl() {
        val owners = DesktopControlOwners()
        val movie = Any(); val episode = Any()
        var control: String? = null
        owners.register("play", movie) { control = "movie" }
        owners.register("play", episode) { control = "episode" }
        owners.release("play", movie) { control = null }
        owners.update("play", movie) { control = "stale movie" }
        assertEquals("episode", control)
        owners.update("play", episode) { control = "episode moved" }
        assertEquals("episode moved", control)
        owners.release("play", episode) { control = null }
        assertNull(control)
        owners.update("play", episode) { control = "disposed episode" }
        assertNull(control)
    }
}
