/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's own wiring of what follows a local viewing, with a real lookup against a server that answers or doesn't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class OfflineNextHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private val lookups = AtomicInteger()
    private lateinit var host: PlaybackHostState
    private var exits = 0
    private lateinit var http: HttpClient

    private fun episode(id: String, number: Int, phase: OfflinePhase = OfflinePhase.ready) = OfflineEntry(
        "$id:original",
        id,
        OfflineMediaKind.episode,
        preset = OfflinePreset.original,
        labels = OfflineLabels(id, seriesId = "show", seasonNumber = 1, episodeNumber = number),
        job = OfflineJob(phase = phase)
    )

    private val first = episode("e1", 1)

    private object Gateway : PlaybackGateway {
        override suspend fun prepare(request: PlaybackRequest) = ClientResult.Failure(ClientError(ErrorKind.SERVER, "local"))
        override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit)
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
    }

    /** [episodes] is what the server answers about the season; null is a server that isn't answering. */
    private fun play(entries: () -> List<OfflineEntry>, episodes: String?) {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val body = when {
                        path.endsWith("/Episodes") -> {
                            lookups.incrementAndGet()
                            episodes ?: return@addHandler respond("{}", HttpStatusCode.ServiceUnavailable)
                        }
                        path.endsWith("/e1") -> """{"Id":"e1","Name":"e1","Type":"Episode","SeriesId":"show"}"""
                        else -> return@addHandler respond("", HttpStatusCode.NoContent)
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        startKoin {
            modules(
                module {
                    single { http }
                    single { DeviceIdentityStore(store) }
                    single { PlaybackPreferencesStore(store) }
                }
            )
        }
        val accounts = AccountStore(
            object : TextPersistence {
                override suspend fun read(): String? = null
                override suspend fun write(value: String) = Unit
            }
        )
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        val next = OfflineNext(entries, first, {}, {})
        val output = Media3PlaybackOutput(compose.activity, account.server)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalOfflinePlayback provides OfflinePlaybackBinding(Gateway, emptyList(), next = next)) {
                    host = rememberPlaybackHost(
                        account,
                        MediaItem("e1", "e1", "Episode", seriesId = "show"),
                        0,
                        catalog,
                        {},
                        { exits++ },
                        providedOutput = output
                    )
                }
            }
        }
        compose.waitUntil(10_000) { host.nextLookup != null }
        compose.waitForIdle()
    }

    private fun theTitleEnds() {
        compose.runOnIdle { host.ended = true }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        stopKoin()
    }

    private val serverNames = { id: String ->
        """{"Items":[{"Id":"e1","Type":"Episode"},{"Id":"$id","Name":"From the server","Type":"Episode"}]}"""
    }

    @Test fun noServerAnswerStillFindsTheNextDownloadInThePlayer() {
        play({ listOf(first, episode("e2", 2)) }, episodes = null)
        assertEquals("e2", host.nextEpisode?.id)
    }

    @Test fun aNextTitleTheServerNamesThatIsNotDownloadedIsNotOffered() {
        play({ listOf(first, episode("e2", 2)) }, episodes = serverNames("e9"))
        assertEquals(null, host.nextEpisode)
    }

    @Test fun anEpisodeThatFinishesDownloadingDuringTheViewingFollowsIt() {
        var entries = listOf(first, episode("e2", 2, OfflinePhase.transferring))
        play({ entries }, episodes = serverNames("e2"))
        assertEquals(null, host.nextEpisode)
        entries = listOf(first, episode("e2", 2))
        theTitleEnds()
        assertEquals("e2", host.nextEpisode?.id)
        assertEquals(0, exits, "the viewing stays for the next episode")
    }

    @Test fun anEpisodeStillDownloadingAtTheEndEndsTheViewingOnce() {
        play({ listOf(first, episode("e2", 2, OfflinePhase.transferring)) }, episodes = serverNames("e2"))
        theTitleEnds()
        assertEquals(null, host.nextEpisode)
        compose.waitUntil(10_000) { host.exitRequested }
    }
}
