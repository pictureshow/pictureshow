/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.*

/** Pure rules behind the leg 172 pilot fixes. */
class DesktopPilotFixesTest {
    /** D2: the frame sits wholly above the seek track, centred on the pointer, and never leaves the window. */
    @Test fun trickplayFrameSitsAboveTheTrackInsideTheWindow() {
        val track = IntRect(100, 700, 1100, 720)
        val window = IntSize(1280, 800)
        val p = trickplayFramePosition(track, window, x = 500f, trackWidth = 1000, w = 192, h = 108, gap = 30)
        assertEquals(IntOffset(100 + 500 - 96, 700 - 108 - 30), p)
        assertTrue(p.y + 108 <= track.top, "the frame's bottom is above the track")
        assertEquals(100, trickplayFramePosition(track, window, 0f, 1000, 192, 108, 30).x, "clamped to the track's left")
        assertEquals(100 + 1000 - 192, trickplayFramePosition(track, window, 1000f, 1000, 192, 108, 30).x, "clamped to the track's right")
        assertEquals(0, trickplayFramePosition(IntRect(0, 50, 1000, 70), window, 500f, 1000, 192, 108, 30).y, "never above the window")
    }

    /** D5: entries share the rail's height between 14 and 22 dp. */
    @Test fun letterEntriesShareTheRailHeight() {
        assertEquals(22.dp, letterEntryHeight(800.dp, 27))
        assertEquals(480.dp / 27, letterEntryHeight(480.dp, 27))
        assertEquals(14.dp, letterEntryHeight(200.dp, 27), "below the minimum the rail scrolls instead")
    }

    /** D6: the mini bar's spoken name carries a failure, so a reader hears it without opening Now playing. */
    @Test fun musicMiniBarSaysWhyNothingPlays() {
        assertEquals("Now playing: Tone A4, Leg Band. None of the tracks left in this queue could play.",
            musicMiniDescription("Tone A4", "Leg Band", "None of the tracks left in this queue could play."))
        assertEquals("Now playing: Tone A4", musicMiniDescription("Tone A4", null, null))
    }

    /** Low: the second PIN entry reads as a confirmation. */
    @Test fun pinSetupSecondEntryIsLabelledConfirm() {
        assertEquals("New PIN", desktopPinSetupLabel(DesktopPinSetup("k", "Viewer")))
        assertEquals("Confirm PIN", desktopPinSetupLabel(DesktopPinSetup("k", "Viewer", first = "1234")))
    }

    /** Low: Jellyfin's "Home videos and photos" libraries get no title filters; movie libraries keep them. */
    @Test fun photoLibrariesOfferNoTitleFilters() {
        fun library(type: String?) = app.pictureshow.core.MediaItem("l", type = "CollectionFolder", collectionType = type)
        assertFalse(libraryFiltersOffered(library("homevideos")))
        assertFalse(libraryFiltersOffered(library("photos")))
        assertTrue(libraryFiltersOffered(library("movies")))
    }

    /** Low: a pointer-opened sub-page returns to its opener, not the page's entry control. */
    @Test fun pointerOpenerIsRememberedForBack() {
        DesktopUiProbe.pageOfTag["settings:about"] = "Settings"
        try {
            rememberOpener("settings:about")
            assertEquals("settings:about", DesktopUiProbe.lastFocus["Settings"])
        } finally { DesktopUiProbe.pageOfTag.remove("settings:about"); DesktopUiProbe.lastFocus.remove("Settings") }
    }

    /** Low: the Chapters chip names itself once, and other chips still say label and value. */
    @Test fun menuChipDescriptionsDoNotRepeatTheLabel() {
        assertEquals("Chapters", trackMenuDescription("Chapters", "Chapters", null))
        assertEquals("Audio: English. Converted", trackMenuDescription("Audio", "English", "Converted"))
    }

    /** Low: a photo card keeps the landscape shape instead of a portrait poster crop; films stay posters. */
    @Test fun photoCardsAreNotPosterCropped() {
        assertFalse(app.pictureshow.ui.catalogCardIsPoster(app.pictureshow.core.MediaItem("p", type = "Photo"), wide = false))
        assertTrue(app.pictureshow.ui.catalogCardIsPoster(app.pictureshow.core.MediaItem("m", type = "Movie"), wide = false))
    }
}
