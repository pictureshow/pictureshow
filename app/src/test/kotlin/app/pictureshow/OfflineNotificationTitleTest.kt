/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ongoing download notification names what the viewer asked for (an album, a show), not its first part. */
class OfflineNotificationTitleTest {
    private fun entry(id: String, kind: OfflineMediaKind, labels: OfflineLabels, phase: OfflinePhase = OfflinePhase.transferring) =
        OfflineEntry(id = id, itemId = id, kind = kind, preset = OfflinePreset.original, labels = labels, job = OfflineJob(phase))

    private fun title(vararg entries: OfflineEntry, key: String? = "me"): Pair<String, Int> {
        val titles = offlineForegroundTitles(mapOf("me" to OfflineLibrary(OfflineManifest(entries = entries.toList()))), key)
        return offlineNotificationTitle(titles) to titles.size
    }

    @Test
    fun `an album download is named for the album and still counts its tracks`() {
        val a = entry("t1", OfflineMediaKind.audio, OfflineLabels(title = "Thunderdays", album = "Promo"))
        val b = entry("t2", OfflineMediaKind.audio, OfflineLabels(title = "Cornered!", album = "Promo"))
        assertEquals("Promo" to 2, title(a, b))
    }

    @Test
    fun `episodes are named for their show, a movie for itself`() {
        assertEquals("Columbo" to 1, title(entry("e", OfflineMediaKind.episode, OfflineLabels(title = "Pilot", seriesName = "Columbo"))))
        val movie = OfflineLabels(title = "Sintel", album = "x", seriesName = "y")
        assertEquals("Sintel" to 1, title(entry("m", OfflineMediaKind.movie, movie)))
    }

    @Test
    fun `a missing or blank album or show falls back to the item, and nothing nameable says Downloads`() {
        assertEquals("Lone" to 1, title(entry("t", OfflineMediaKind.audio, OfflineLabels(title = "Lone", album = "  "))))
        assertEquals("Ep" to 1, title(entry("e", OfflineMediaKind.episode, OfflineLabels(title = "Ep"))))
        val promo = OfflineLabels(title = "Lone", album = "Promo")
        assertEquals("Downloads" to 1, title(entry("t", OfflineMediaKind.audio, promo), key = null))
        assertEquals("Downloads" to 0, title(entry("d", OfflineMediaKind.audio, promo, OfflinePhase.ready)))
    }
}
