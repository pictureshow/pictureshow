/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflineCollection
import app.pictureshow.core.offline.OfflineCollectionKind
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.offlineEntryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Downloads lists music by album or playlist, one row each that opens to its tracks, under a heading that isn't about watching. */
class OfflineMusicSectionsTest {
    private fun track(id: String, sequence: Long, phase: OfflinePhase = OfflinePhase.ready) = OfflineEntry(
        offlineEntryId(id, OfflinePreset.original),
        id,
        OfflineMediaKind.audio,
        preset = OfflinePreset.original,
        createdSequence = sequence,
        labels = OfflineLabels("Song $id", album = "Invented album", artist = "Invented artist"),
        job = OfflineJob(phase)
    )

    private val film = OfflineEntry(
        "film",
        "film",
        OfflineMediaKind.movie,
        preset = OfflinePreset.video720,
        createdSequence = 1,
        labels = OfflineLabels("Invented film"),
        job = OfflineJob(OfflinePhase.ready)
    )

    // The snapshot's order differs from the order the files arrived in.
    private val album = OfflineCollection(
        "alb",
        OfflineCollectionKind.album,
        "Invented album",
        "Invented artist",
        listOf("t3", "t1", "t2"),
        createdSequence = 10
    )

    private fun sections(vararg entries: OfflineEntry, collections: List<OfflineCollection> = listOf(album), open: Set<String> = emptySet()) =
        offlineSections(
            OfflineLibrary(OfflineManifest(entries = entries.toList(), collections = collections)),
            OfflineNetwork.unmetered,
            open = open
        )

    @Test fun anAlbumIsOneRowWithItsTrackCountUnderReadyToPlay() {
        val list = sections(film, track("t1", 2), track("t2", 3), track("t3", 4))
        assertEquals(listOf("Ready to play"), list.ordered.map { it.first })
        assertEquals(listOf("film", "collection:alb"), list.ready.map { it.key })
        val row = list.ready.last()
        assertEquals("Invented album", row.title)
        assertEquals("Invented artist · Album", row.subtitle)
        assertEquals("3 tracks · ${offlineBytes(0)}", row.status)
        assertEquals(listOf(OfflineAction.Play, OfflineAction.Tracks, OfflineAction.Delete), row.actions)
        // Its Play and art are the album's first track in the saved order.
        assertEquals("t3", row.entry.itemId)
        assertEquals("Show tracks", offlineRowActionLabel(row, OfflineAction.Tracks))
        assertEquals(
            "Delete Invented album from this device? You can still stream it from your server.",
            offlineCollectionDeleteQuestion(row)
        )
        assertEquals(OfflineFirstFocus.Row("film", OfflineAction.Play), offlineFirstFocus(false, list))
    }

    @Test fun anOpenAlbumListsItsTracksBelowItInTheSavedOrder() {
        val list = sections(track("t1", 2), track("t2", 3), track("t3", 4), open = setOf("alb"))
        assertEquals(listOf("collection:alb", "alb:t3:original", "alb:t1:original", "alb:t2:original"), list.ready.map { it.key })
        assertEquals(listOf(null, "alb", "alb", "alb"), list.ready.map { it.parent })
        assertEquals("Hide tracks", offlineRowActionLabel(list.ready.first(), OfflineAction.Tracks))
        // Each track keeps its own Play and Delete.
        assertEquals(listOf(OfflineAction.Play, OfflineAction.Delete), list.ready[1].actions)
        assertEquals(4, offlineRowPosition(list, list.ready[3].key))
    }

    @Test fun aSharedTrackSitsUnderTheNewestCollectionHoldingIt() {
        val playlist = OfflineCollection("pl", OfflineCollectionKind.playlist, "Invented playlist", null, listOf("t1"), createdSequence = 20)
        val list = sections(track("t1", 2), track("t2", 3), track("t3", 4), collections = listOf(album, playlist))
        assertEquals(listOf("collection:alb", "collection:pl"), list.ready.map { it.key })
        assertEquals("2 tracks · ${offlineBytes(0)}", list.ready[0].status)
        assertEquals("Playlist", list.ready[1].subtitle)
    }

    @Test fun anAlbumStillComingIsInProgressAndOffersNoPlayUntilATrackIsReady() {
        val list = sections(track("t1", 2, OfflinePhase.queued), track("t2", 3, OfflinePhase.queued), track("t3", 4, OfflinePhase.queued))
        assertEquals(listOf("In progress"), list.ordered.map { it.first })
        val row = list.active.single()
        assertEquals("Downloading · 0 of 3 tracks", row.status)
        assertEquals(listOf(OfflineAction.Tracks, OfflineAction.Delete), row.actions)
        assertEquals("Stop downloading Invented album and delete what's saved so far?", offlineCollectionDeleteQuestion(row))
    }

    @Test fun aTrackNoCollectionHoldsStaysItsOwnRow() {
        val loose = track("t9", 5)
        val list = sections(loose)
        assertEquals(listOf(loose.id), list.ready.map { it.key })
        assertNull(list.ready.single().collection)
    }
}
