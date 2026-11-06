/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The end of a local viewing: a local player is bound to one file, and the next title can't borrow it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class OfflineNextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")

    private fun episode(id: String, number: Int) = OfflineEntry(
        "$id:original",
        id,
        OfflineMediaKind.episode,
        preset = OfflinePreset.original,
        labels = OfflineLabels(id, seriesId = "show", seasonNumber = 1, episodeNumber = number),
        job = OfflineJob(phase = OfflinePhase.ready)
    )

    private val first = episode("e1", 1)
    private val second = episode("e2", 2)
    private val opened = mutableListOf<String>()
    private var stops = 0

    private fun next(vararg entries: OfflineEntry) = OfflineNext(entries.toList(), first, { opened += it.id }, { stops++ })

    private object Gateway : PlaybackGateway {
        override suspend fun prepare(request: PlaybackRequest) = ClientResult.Failure(ClientError(ErrorKind.SERVER, "local"))
        override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit)
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
    }

    /** The viewing as the player sees it, at the end of the title with the local gateway still bound. */
    private fun finishWith(next: OfflineNext, item: MediaItem): List<PlaybackHostState> {
        val hosts = mutableListOf<PlaybackHostState>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalOfflinePlayback provides OfflinePlaybackBinding(Gateway, emptyList(), next = next)) {
                    PlayerViewing(account, first.playerItem(), 0, {}) { current, start, onNext ->
                        val viewing = rememberPlaybackHostState(account, current, start, onNext, {})
                        SideEffect { if (viewing !in hosts) hosts += viewing }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            hosts.single().advanceReported = true
            hosts.single().finish(item)
        }
        compose.waitForIdle()
        return hosts
    }

    @Test fun theNextEpisodeOpensItsOwnDownloadInsteadOfTheSameFileUnderItsTitle() {
        val hosts = finishWith(next(first, second), second.playerItem())
        assertEquals(listOf(first.itemId), hosts.map { it.item.id }, "no second title on the local gateway")
        assertEquals(listOf(second.id), opened)
        assertEquals(0, stops)
    }

    @Test fun aNextTitleThatIsNotDownloadedEndsTheViewing() {
        val hosts = finishWith(next(first), MediaItem("elsewhere", "From a controller"))
        assertEquals(listOf(first.itemId), hosts.map { it.item.id })
        assertTrue(opened.isEmpty())
        assertEquals(1, stops)
    }

    @Test fun theServersAnswerOnlyNamesADownload() {
        val next = next(first, second)
        val answered = next.after(ClientResult.Success(MediaItem("e2", "Second, from the server")))
        assertEquals("e2", (answered as ClientResult.Success).value?.id)
        assertEquals("e2", answered.value?.name, "the title comes from the download, not the answer")
        assertEquals(null, (next.after(ClientResult.Success(MediaItem("e9", "Not here"))) as ClientResult.Success).value)
        assertEquals(null, (next.after(ClientResult.Success(null)) as ClientResult.Success).value)
    }

    @Test fun noAnswerFallsBackToTheNextNumberAndNeverReportsAFailure() {
        val answered = next(first, second).after(ClientResult.Failure(ClientError(ErrorKind.NETWORK, "no route")))
        assertEquals("e2", (answered as ClientResult.Success).value?.id)
    }
}
