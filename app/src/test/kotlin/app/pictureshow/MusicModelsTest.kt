/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.music.ArtistRole
import app.pictureshow.core.music.MusicRequest
import app.pictureshow.core.music.MusicSection
import app.pictureshow.core.music.MusicService
import app.pictureshow.core.music.PlaylistWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** What the music screens hold: tabs that keep their place, and playlist writes that read back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class MusicModelsTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    private val account = Account(Server("s", "Test", "http://server", "12"), User("v", "Viewer"), "token")

    private class Memory : TextPersistence {
        private var text: String? = null

        override suspend fun read() = text

        override suspend fun write(value: String) {
            text = value
        }
    }

    internal class FakeMusic : MusicService {
        val requests = mutableListOf<MusicRequest>()
        val writes = mutableListOf<String>()
        var pages: (MusicRequest) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage(emptyList())) }
        var playlist = mutableListOf<MediaItem>()
        var canEdit = true
        var writeOutcome = "ok"
        var applyWrites = true

        override suspend fun music(account: Account, request: MusicRequest): ClientResult<ItemPage> {
            requests += request
            if (request.path.startsWith("Playlists/") && request.path.endsWith("/Items")) {
                return ClientResult.Success(ItemPage(playlist.toList(), playlist.size))
            }
            return pages(request)
        }

        override suspend fun instantMix(account: Account, item: String) = ClientResult.Success(ItemPage(emptyList()))

        override suspend fun playlistCanEdit(account: Account, playlist: String) = canEdit

        override suspend fun createPlaylist(account: Account, name: String, items: List<String>, isPublic: Boolean): PlaylistWrite {
            writes += "create $name $items public=$isPublic"
            return PlaylistWrite(writeOutcome)
        }

        override suspend fun renamePlaylist(account: Account, playlist: String, name: String): PlaylistWrite {
            writes += "rename $name"
            return PlaylistWrite(writeOutcome)
        }

        override suspend fun addToPlaylist(account: Account, playlist: String, items: List<String>): PlaylistWrite {
            writes += "add $items"
            if (applyWrites) {
                items.forEach {
                    this.playlist +=
                        MediaItem(id = it, name = it, type = "Audio", playlistItemId = "e${this.playlist.size}")
                }
            }
            return PlaylistWrite(writeOutcome)
        }

        override suspend fun removeFromPlaylist(account: Account, playlist: String, entries: List<String>): PlaylistWrite {
            writes += "remove $entries"
            if (applyWrites) this.playlist.removeAll { it.playlistItemId in entries }
            return PlaylistWrite(writeOutcome)
        }

        override suspend fun movePlaylistEntry(account: Account, playlist: String, entry: String, index: Int): PlaylistWrite {
            writes += "move $entry $index"
            if (applyWrites) {
                val row = this.playlist.first { it.playlistItemId == entry }
                this.playlist.remove(row)
                this.playlist.add(index, row)
            }
            return PlaylistWrite(writeOutcome)
        }
    }

    private fun settle() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun accounts() = AccountStore(Memory())

    private fun track(id: String, entry: String? = null, location: String? = null) =
        MediaItem(id = id, name = id, type = "Audio", playlistItemId = entry, locationType = location)

    @Test fun aRowsNumberComesFromWhatItIsListedIn() {
        val track = MediaItem(id = "t", name = "Track", type = "Audio", indexNumber = 7)
        val playlist = MediaItem(id = "p", name = "Mix", type = "Playlist", mediaType = "Audio")
        assertEquals(listOf("1", "2", "3"), (0..2).map { musicRowNumber(playlist, track, it) })
        assertEquals("7", musicRowNumber(MediaItem(id = "a", name = "Album", type = "MusicAlbum"), track, 0))
        assertEquals("", musicRowNumber(MediaItem(id = "m", name = "Artist", type = "MusicArtist"), track, 4))
    }

    @Test fun musicRoutesOnlyWhatIsMusic() {
        assertTrue(MediaItem(id = "l", name = "Music", type = "CollectionFolder", collectionType = "music").isMusic())
        assertTrue(MediaItem(id = "a", name = "A", type = "MusicAlbum").isMusic())
        assertTrue(MediaItem(id = "p", name = "P", type = "Playlist", mediaType = "Audio").isMusic())
        assertFalse(MediaItem(id = "p", name = "P", type = "Playlist", mediaType = "Video").isMusic())
        assertFalse(MediaItem(id = "m", name = "M", type = "Movie").isMusic())
        assertFalse(MediaItem(id = "l", name = "Movies", type = "CollectionFolder", collectionType = "movies").isMusic())
    }

    @Test fun eachTabKeepsItsOwnRowsAndAPageNeverDuplicatesARow() {
        val music = FakeMusic()
        music.pages = { request ->
            val start = request.parameters.getValue("startIndex").toInt()
            val rows = if (start == 0) listOf("a", "b") else listOf("b", "c")
            ClientResult.Success(ItemPage(rows.map { MediaItem(id = it, name = it, type = "MusicAlbum") }, 4))
        }
        val vm = MusicLibraryViewModel(account, MediaItem(id = "lib", name = "Music"), music, accounts())
        settle()
        vm.nearEnd(1)
        settle()
        assertEquals(listOf("a", "b", "c"), vm.state.value.current.items.map(MediaItem::id))
        val albumReads = music.requests.size
        vm.select(MusicSection.GENRES)
        settle()
        vm.select(MusicSection.ALBUMS)
        settle()
        assertEquals(listOf("a", "b", "c"), vm.state.value.current.items.map(MediaItem::id), "Coming back keeps what was read")
        assertEquals(albumReads + 1, music.requests.size, "Coming back reads nothing again")
    }

    @Test fun anEndedPageStopsAsking() {
        val music = FakeMusic()
        music.pages = { ClientResult.Success(ItemPage(listOf(MediaItem(id = "a", name = "a", type = "MusicAlbum")), 1)) }
        val vm = MusicLibraryViewModel(account, MediaItem(id = "lib", name = "Music"), music, accounts())
        settle()
        vm.nearEnd(0)
        settle()
        assertEquals(1, music.requests.size)
    }

    @Test fun favoritesReadsFavoriteArtistsToo() {
        val music = FakeMusic()
        music.pages = { request ->
            ClientResult.Success(
                ItemPage(
                    if (request.path == "Artists") {
                        listOf(MediaItem(id = "artist", name = "artist", type = "MusicArtist"))
                    } else {
                        listOf(track("song"))
                    },
                    1
                )
            )
        }
        val vm = MusicLibraryViewModel(account, MediaItem(id = "lib", name = "Music"), music, accounts())
        settle()
        vm.select(MusicSection.FAVORITES)
        settle()
        assertEquals(listOf("artist", "song"), vm.state.value.current.items.map(MediaItem::id))
    }

    @Test fun aFailedReadStopsPagingUntilRetried() {
        val music = FakeMusic()
        var fail = true
        music.pages = {
            if (fail) {
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "No"))
            } else {
                ClientResult.Success(ItemPage(listOf(MediaItem(id = "a", name = "a", type = "MusicAlbum")), 1))
            }
        }
        val vm = MusicLibraryViewModel(account, MediaItem(id = "lib", name = "Music"), music, accounts())
        settle()
        assertEquals("No", vm.state.value.current.error?.message)
        vm.nearEnd(0)
        settle()
        assertEquals(1, music.requests.size)
        fail = false
        vm.retry()
        settle()
        assertNull(vm.state.value.current.error)
        assertEquals(1, vm.state.value.current.items.size)
    }

    private fun playlistVm(music: FakeMusic) = MusicDetailViewModel(
        account,
        MediaItem(id = "p", name = "Mix", type = "Playlist", mediaType = "Audio"),
        ArtistRole.ALBUM_ARTIST,
        null,
        music,
        accounts(),
        CatalogSession(CatalogModelsTest.FakeCatalog(), accounts())
    )

    @Test fun aMoveIsReadBackAndTheScreenShowsTheServersOrder() {
        val music = FakeMusic()
        music.playlist = mutableListOf(track("a", "e1"), track("a", "e2"), track("b", "e3"))
        val vm = playlistVm(music)
        settle()
        assertEquals(listOf("e1", "e2", "e3"), vm.state.value.tracks.map(MediaItem::playlistItemId), "One song twice is two rows")
        vm.moveEntry("e3", 0)
        settle()
        assertEquals(listOf("move e3 0"), music.writes)
        assertEquals(listOf("e3", "e1", "e2"), vm.state.value.tracks.map(MediaItem::playlistItemId))
        assertNull(vm.state.value.edit.message)
    }

    @Test fun aSilentServerIsNeverWrittenToAgainBeforeItsChecked() {
        val music = FakeMusic()
        music.playlist = mutableListOf(track("a", "e1"), track("b", "e2"))
        music.writeOutcome = "unknown"
        music.applyWrites = false
        val vm = playlistVm(music)
        settle()
        vm.moveEntry("e2", 0)
        settle()
        assertEquals(1, music.writes.size)
        assertEquals("That change didn't save. Try again.", vm.state.value.message)
        assertEquals("idle", vm.state.value.edit.phase)
    }

    @Test fun aDeniedWriteSaysSoAndChangesNothing() {
        val music = FakeMusic()
        music.playlist = mutableListOf(track("a", "e1"))
        music.writeOutcome = "denied"
        val vm = playlistVm(music)
        settle()
        vm.removeEntry("e1")
        settle()
        assertEquals("The server didn't allow that change.", vm.state.value.message)
        assertEquals(1, vm.state.value.tracks.size)
    }

    @Test fun aPlaylistTheServerSaysIsntOursIsReadOnly() {
        val music = FakeMusic()
        music.playlist = mutableListOf(track("a", "e1"))
        music.canEdit = false
        val vm = playlistVm(music)
        settle()
        assertFalse(vm.state.value.canEdit)
        vm.removeEntry("e1")
        settle()
        assertTrue(music.writes.isEmpty(), "Refused here, not after")
        assertEquals("You can't edit this playlist.", vm.state.value.message)
    }

    @Test fun aGenreReadsItsTracksOnlyWhenAskedAndAllOfThem() {
        val music = FakeMusic()
        music.pages = { request ->
            when (request.parameters["includeItemTypes"]) {
                "Audio" -> {
                    val start = request.parameters.getValue("startIndex").toInt()
                    ClientResult.Success(ItemPage(listOf(track("t$start")), 2))
                }
                else -> ClientResult.Success(ItemPage(listOf(MediaItem(id = "album", name = "album", type = "MusicAlbum")), 1))
            }
        }
        val vm =
            MusicDetailViewModel(
                account,
                MediaItem(id = "g", name = "Jazz", type = "MusicGenre"),
                ArtistRole.ALBUM_ARTIST,
                "lib",
                music,
                accounts(),
                CatalogSession(CatalogModelsTest.FakeCatalog(), accounts())
            )
        settle()
        assertEquals(1, music.requests.size, "Opening a genre reads its albums")
        var heard = emptyList<String>()
        vm.allTracks { heard = it.map(MediaItem::id) }
        settle()
        assertEquals(listOf("t0", "t1"), heard)
    }

    @Test fun anAlbumIsOrderedByDiscThenTrack() {
        val music = FakeMusic()
        music.pages = {
            ClientResult.Success(
                ItemPage(
                    listOf(
                        MediaItem(id = "d2t1", name = "x", type = "Audio", parentIndexNumber = 2, indexNumber = 1),
                        MediaItem(id = "d1t2", name = "y", type = "Audio", parentIndexNumber = 1, indexNumber = 2),
                        MediaItem(id = "d1t1", name = "z", type = "Audio", parentIndexNumber = 1, indexNumber = 1)
                    ),
                    3
                )
            )
        }
        val vm =
            MusicDetailViewModel(
                account,
                MediaItem(id = "a", name = "A", type = "MusicAlbum"),
                ArtistRole.ALBUM_ARTIST,
                null,
                music,
                accounts(),
                CatalogSession(CatalogModelsTest.FakeCatalog(), accounts())
            )
        settle()
        assertEquals(listOf("d1t1", "d1t2", "d2t1"), vm.state.value.tracks.map(MediaItem::id))
        assertEquals(2, vm.state.value.discs.size)
    }

    @Test fun aNewPlaylistIsPrivateAndASilentCreateIsNotSentAgain() {
        val music = FakeMusic()
        music.writeOutcome = "unknown"
        val vm = MusicPlaylistPickerViewModel(account, listOf("t"), music)
        settle()
        vm.create(" Road trip ")
        settle()
        assertEquals(listOf("create Road trip [t] public=false"), music.writes)
        assertTrue(vm.state.value.done)
        assertEquals("The server didn't answer, so check Playlists before trying again.", vm.state.value.message)
    }

    @Test fun addingToAPlaylistReadsBackAndRefusesOneThatIsntEditable() {
        val music = FakeMusic()
        music.pages =
            { ClientResult.Success(ItemPage(listOf(MediaItem(id = "p", name = "Mix", type = "Playlist", mediaType = "Audio")), 1)) }
        val vm = MusicPlaylistPickerViewModel(account, listOf("t"), music)
        settle()
        val playlist = vm.state.value.playlists.single()
        music.canEdit = false
        vm.add(playlist)
        settle()
        assertTrue(music.writes.isEmpty())
        assertEquals("You can't edit this playlist.", vm.state.value.message)
        music.canEdit = true
        vm.add(playlist)
        settle()
        assertEquals(listOf("add [t]"), music.writes)
        assertTrue(vm.state.value.done)
        assertEquals("Added to Mix.", vm.state.value.message)
    }

    @Test fun unavailableRowsAreNotQueuedAndTheStartFollowsWhatRemains() {
        val tracks = listOf(track("gone", location = "Virtual"), track("a", "e1"), track("a", "e2"))
        val play = account.musicPlay(tracks, tracks[2], "playlist:p", "Mix")!!
        assertEquals(listOf("a", "a"), play.tracks.map(MusicTrack::id))
        assertEquals(1, play.start, "The second copy of the song, not the first")
        assertNull(account.musicPlay(listOf(track("gone", location = "Virtual")), null, "c", "n"))
    }

    @Test fun timesReadTheWayAPlayerShowsThem() {
        assertEquals("0:05", formatMusicTime(5_000))
        assertEquals("3:07", formatMusicTime(187_000))
        assertEquals("1:02:03", formatMusicTime(3_723_000))
    }
}
