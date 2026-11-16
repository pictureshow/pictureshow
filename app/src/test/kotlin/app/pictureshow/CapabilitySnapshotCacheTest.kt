/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.os.Looper
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CapabilitySnapshotCacheTest {
    private val capabilities = DeviceCapabilities("Test", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2))

    @Test fun playAndFallbackProbeOffMainAndReuseUntilDisplayOrAudioRouteChanges() = runBlocking {
        val cache = CapabilitySnapshotCache()
        var route = "display-1:audio-1"
        var probes = 0
        var keys = 0
        val caller = Thread.currentThread()
        val http =
            HttpClient(MockEngine { respond("""{"MediaSources":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            val gateway = ServerPlaybackGateway(
                PlaybackClient(http, ClientIdentity("Test", "fixture")),
                Account(Server("s", "Server", "https://example.test", "12"), User("u", "Viewer"), "fictional"),
                "film",
                capabilities = {
                    cache.snapshot({
                        assertNotEquals(caller, Thread.currentThread())
                        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                        keys++
                        route
                    }) {
                        assertNotEquals(caller, Thread.currentThread())
                        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                        probes++
                        capabilities
                    }
                },
                markPlayed = { ClientResult.Success(Unit) }
            )
            gateway.prepare(PlaybackRequest())
            gateway.prepare(PlaybackRequest(enableDirectPlay = false))
            assertEquals(1, probes, "Play/fallback with the same route must share a process snapshot")
            route = "display-2:audio-1"
            gateway.prepare(PlaybackRequest())
            assertEquals(2, probes)
            route = "display-2:audio-2"
            gateway.prepare(PlaybackRequest())
            assertEquals(3, probes)
            assertEquals(4, keys, "Cheap route facts are checked even across idle gaps")
        } finally {
            http.close()
        }
    }

    @Test fun concurrentColdRequestsShareOneProbeAndFailureDoesNotPoisonCache() = runBlocking {
        val cache = CapabilitySnapshotCache()
        var probes = 0
        assertFailsWith<IllegalStateException> { cache.snapshot({ "route" }) { error("probe failed") } }
        val answers = (1..8).map {
            async {
                cache.snapshot({ "route" }) {
                    probes++
                    capabilities
                }
            }
        }.awaitAll()
        assertEquals(1, probes)
        assertTrue(answers.all { it === capabilities })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun initialAndForcedNotificationsSurviveSupersedingDisplayRefresh() = kotlinx.coroutines.test.runTest {
        val pending = kotlinx.coroutines.CompletableDeferred<DeviceCapabilities>()
        var calls = 0
        var notifications = 0
        val updates = CapabilityUpdates(this, {
            calls++
            pending.await()
        }, { notifications++ }, true)
        updates.refresh()
        runCurrent()
        updates.refresh() // Same-capability display event supersedes initial suspended lookup.
        runCurrent()
        pending.complete(capabilities)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, notifications)
        updates.refresh(force = true) // Physical audio route changed, with identical accepted codecs.
        updates.refresh() // A following display event must retain the forced notification.
        runCurrent()
        assertEquals(2, notifications)
        updates.refresh()
        runCurrent()
        assertEquals(2, notifications, "Ordinary identical display changes must stay silent")
    }
}
