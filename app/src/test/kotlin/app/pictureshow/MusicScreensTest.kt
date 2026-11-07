/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme as TvTheme
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
import app.pictureshow.core.playback.PlaybackPreferences
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackStopInfo
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The queue on a TV has to work with five keys, and on a phone with a screen reader, with no gesture needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h1080dp-xhdpi")
class MusicScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Test", "http://server", "12"), User("v", "Viewer"), "token")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: MusicHost

    private object Silent : MusicGateway {
        override suspend fun negotiate(account: Account, media: String, ceiling: Int) =
            ClientResult.Failure(ClientError(ErrorKind.NETWORK, "No server here"))

        override suspend fun start(account: Account, info: PlaybackProgressInfo) = Unit

        override suspend fun progress(account: Account, info: PlaybackProgressInfo) = Unit

        override suspend fun stopped(account: Account, info: PlaybackStopInfo) = Unit

        override suspend fun check(account: Account, media: List<String>) = ClientResult.Success(media)

        override suspend fun items(account: Account, media: List<String>) = ClientResult.Success(emptyList<MediaItem>())
    }

    private class Memory : TextPersistence {
        private var text: String? = null

        override suspend fun read() = text

        override suspend fun write(value: String) {
            text = value
        }
    }

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir, "music").deleteRecursively()
        val accounts = AccountStore(Memory())
        runBlocking { accounts.remember(account) }
        startKoin { modules(module { single { inertPlaybackPreferences() } }) }
        host = MusicHost(
            context,
            scope,
            accounts,
            MusicStore(context),
            Silent,
            preferences = { PlaybackPreferences() },
            remoteAttached = { null },
            remoteTarget = { null },
            startService = {},
            metered = { false }
        )
        until { host.state.value.account != null }
    }

    @After fun close() {
        scope.cancel()
        stopKoin()
    }

    /** The host answers on the main looper, which a test only turns by hand. */
    private fun until(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            kotlin.test.assertTrue(System.currentTimeMillis() < end, "Timed out. State: ${host.state.value.queue?.order}")
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun tracks(vararg ids: String) = ids.map { MusicTrack(it, "Title $it", "Artist", durationTicks = 1_000_000) }

    private fun queueOf(vararg ids: String) {
        host.append(account, tracks(*ids))
        until { host.state.value.queue?.entries?.size == ids.size }
    }

    private fun upNext() = host.state.value.upNext.map { it.track.id }

    private fun waitFor(ids: List<String>) {
        until { upNext() == ids }
        compose.waitForIdle()
    }

    @Test fun tvQueueRowsMoveAndRemoveWithButtons() {
        queueOf("a", "b", "c", "d")
        compose.setContent { TvTheme { TvMusicNow(host) {} } }
        val rows = host.state.value.upNext
        compose.onNodeWithTag("music:queue:up:${rows[0].entry}").assertIsNotEnabled()
        compose.onNodeWithTag("music:queue:down:${rows[2].entry}").assertIsNotEnabled()
        compose.onNodeWithTag("music:queue:down:${rows[0].entry}").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        waitFor(listOf("c", "b", "d"))
        compose.onNodeWithTag("music:queue:up:${rows[2].entry}").performSemanticsAction(SemanticsActions.OnClick)
        waitFor(listOf("c", "d", "b"))
        compose.onNodeWithTag("music:queue:remove:${rows[1].entry}").performSemanticsAction(SemanticsActions.OnClick)
        waitFor(listOf("d", "b"))
        assertEquals("a", host.state.value.current?.track?.id, "What's playing never moved")
    }

    @Test fun tvQueueRowJumps() {
        queueOf("a", "b", "c")
        compose.setContent { TvTheme { TvMusicNow(host) {} } }
        val target = host.state.value.upNext[1].entry
        compose.onNodeWithTag("music:queue:$target").performSemanticsAction(SemanticsActions.OnClick)
        until { host.state.value.current?.track?.id == "c" }
    }

    @Test fun touchQueueOffersTheSameEditsAsNamedActions() {
        queueOf("a", "b", "c", "d")
        compose.setContent { TouchTheme { TouchMusicNow(host) {} } }
        val row = host.state.value.upNext[1]
        compose.onNodeWithTag("music:now").performScrollToNode(hasTestTag("music:queue:${row.entry}"))
        val node = compose.onNodeWithTag("music:queue:${row.entry}")
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move up", "Move down", "Remove"), actions.map { it.label })
        actions.first { it.label == "Move up" }.action()
        waitFor(listOf("c", "b", "d"))
        val again = compose.onNodeWithTag("music:queue:${row.entry}").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move down", "Remove"), again.map { it.label }, "The first row can't move up")
        again.first { it.label == "Remove" }.action()
        waitFor(listOf("b", "d"))
    }

    private fun editablePlaylist(music: MusicModelsTest.FakeMusic): MusicDetailViewModel {
        music.playlist = mutableListOf(
            MediaItem(id = "a", name = "Title a", type = "Audio", playlistItemId = "e1", indexNumber = 7),
            MediaItem(id = "b", name = "Title b", type = "Audio", playlistItemId = "e2", indexNumber = 7)
        )
        val store = AccountStore(Memory())
        runBlocking { store.remember(account) }
        return MusicDetailViewModel(
            account,
            MediaItem(id = "p", name = "Mix", type = "Playlist", mediaType = "Audio"),
            ArtistRole.ALBUM_ARTIST,
            null,
            music,
            store,
            CatalogSession(CatalogModelsTest.FakeCatalog(), store)
        )
    }

    private fun editShowsTheRowsChanges(music: MusicModelsTest.FakeMusic, vm: MusicDetailViewModel) {
        until { vm.state.value.tracks.size == 2 && vm.state.value.canEdit }
        compose.waitForIdle()
        compose.onNodeWithTag("music:detail").performScrollToNode(hasTestTag("music:edit"))
        compose.onNodeWithTag("music:edit").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("music:detail").performScrollToNode(hasTestTag("music:edit:Move down:e1"))
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("7").assertDoesNotExist()
        val row = compose.onNodeWithTag("music:track:e1").getUnclippedBoundsInRoot()
        val edit = compose.onNodeWithTag("music:edit:Move down:e1").getUnclippedBoundsInRoot()
        assertTrue(edit.top >= row.bottom, "The row's changes sit beneath it: $edit under $row")
        compose.onNodeWithTag("music:edit:Move down:e1").performSemanticsAction(SemanticsActions.OnClick)
        until { music.writes == listOf("move e1 1") }
    }

    private fun songsTab(): MusicLibraryViewModel {
        val music = MusicModelsTest.FakeMusic()
        music.pages = {
            ClientResult.Success(ItemPage(listOf(MediaItem(id = "s", name = "Title s", type = "Audio", indexNumber = 7)), 1))
        }
        val store = AccountStore(Memory())
        runBlocking { store.remember(account) }
        return MusicLibraryViewModel(account, MediaItem(id = "lib", name = "Music"), music, store).also {
            it.select(app.pictureshow.core.music.MusicSection.SONGS)
        }
    }

    /** A song's number belongs to its album, and the Songs list mixes albums. */
    @Test fun touchSongsListShowsNoAlbumNumbers() {
        val vm = songsTab()
        compose.setContent { TouchTheme { TouchMusicLibrary(vm, host, { _, _ -> }, {}) } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Title s").assertExists() }.isSuccess }
        compose.onNodeWithText("7").assertDoesNotExist()
    }

    @Test fun tvSongsListShowsNoAlbumNumbers() {
        val vm = songsTab()
        compose.setContent { TvTheme { TvMusicLibrary(vm, host) { _, _ -> } } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Title s").assertExists() }.isSuccess }
        compose.onNodeWithText("7").assertDoesNotExist()
    }

    @Test fun touchPlaylistEditShowsEachRowsChanges() {
        val music = MusicModelsTest.FakeMusic()
        val vm = editablePlaylist(music)
        compose.setContent { TouchTheme { TouchMusicDetail(vm, host, { _, _ -> }, {}) } }
        editShowsTheRowsChanges(music, vm)
    }

    @Test fun tvPlaylistEditShowsEachRowsChanges() {
        val music = MusicModelsTest.FakeMusic()
        val vm = editablePlaylist(music)
        compose.setContent { TvTheme { TvMusicDetail(vm, host) { _, _ -> } } }
        editShowsTheRowsChanges(music, vm)
    }

    @Test fun theBarShowsOnlyWithAQueueAndOpensNowPlaying() {
        var opened = 0
        compose.setContent { TouchTheme { TouchMusicBar(host) { opened += 1 } } }
        compose.onNodeWithTag("music:bar").assertDoesNotExist()
        queueOf("a")
        compose.waitForIdle()
        compose.onNodeWithText("Title a").assertExists()
        compose.onNodeWithTag("music:bar:next").assertIsNotEnabled()
        compose.onNodeWithTag("music:bar").performClick()
        assertEquals(1, opened)
    }

    @Test fun replacingAQueueAsksAndKeepQueueKeepsIt() {
        queueOf("a", "b")
        compose.setContent { TouchTheme { TouchMusicPrompts(host) {} } }
        host.request(MusicPlay(account.key, tracks("z"), null, "album:z", "Other album", false))
        until { host.state.value.prompt != null }
        compose.onNodeWithText("Other album replaces what's in the queue now.").assertExists()
        compose.onNodeWithText("Keep queue").performClick()
        until { host.state.value.prompt == null }
        assertEquals(listOf("a", "b"), host.state.value.queue!!.entries.map { it.media })
    }

    @Test fun tvPromptPutsFocusOnTheSafeAnswer() {
        queueOf("a")
        compose.setContent { TvTheme { TvMusicPrompts(host) {} } }
        host.request(MusicPlay(account.key, tracks("z"), null, "album:z", "Other album", false))
        until { host.state.value.prompt != null }
        compose.waitForIdle()
        compose.onNodeWithText("Keep queue").assertIsFocused()
        assertFalse(host.state.value.queue!!.entries.any { it.media == "z" })
    }
}
