/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackNetwork
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ActiveReadBandwidthDataSourceTest {
    private class Clock(var nanos: Long = 0)
    private class Body(private val clock: Clock, network: Boolean = true) : BaseDataSource(network) {
        var readNanos = 1_000_000_000L
        var readBytes = 786_432
        var fail = false
        var opened = false
        var closed = false
        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            opened = true
            closed = false
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            clock.nanos += readNanos
            if (fail) throw IOException("scripted read failure")
            if (readBytes > 0) bytesTransferred(readBytes)
            return readBytes
        }
        override fun getUri(): Uri = Uri.parse("https://example.test/film.mp4")
        override fun close() {
            if (opened && !closed) transferEnded()
            closed = true
        }
    }
    private class Output : PlaybackOutput {
        var moment = PlaybackPosition(0, false)
        override fun position() = moment
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            moment = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() {
            moment = moment.copy(paused = true)
        }
        override fun release() = Unit
    }
    private val spec get() = DataSpec(Uri.parse("https://example.test/film.mp4"))
    private val buffer = ByteArray(786_432)

    @Test fun twoWindowsOfOneOpenProgressiveTransferReduceActualServerCapBeforeCloseAndPreservePausedClock() = runTest {
        val account = Account(Server("s", "Server", "https://example.test", "12.0.0"), User("u", "Viewer"), "fictional")
        val host = PlaybackHostState(
            MediaItem(id = "item", name = "Film"),
            0,
            Media3PlaybackOutput(RuntimeEnvironment.getApplication(), account.server),
            {},
            {}
        )
        host.network = PlaybackNetwork.Remote
        val requests = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                        requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        respond(
                            """{"MediaSources":[{"Id":"src","Container":"mp4","SupportsDirectPlay":true,
                            "SupportsTranscoding":true,"TranscodingUrl":"/videos/item/master.m3u8?MediaSourceId=src",
                            "TranscodingSubProtocol":"hls","TranscodingContainer":"mp4"}],"PlaySessionId":"view"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        val capabilities = DeviceCapabilities("Test", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2))
        val gateway = ServerPlaybackGateway(
            PlaybackClient(http, ClientIdentity("Test", "test-device")),
            account,
            "item",
            { capabilities.copy(settings = host.effectiveSettings) }
        ) { ClientResult.Success(Unit) }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        host.controller = session
        val clock = Clock()
        val body = Body(clock)
        val source = progressiveBandwidthSources(
            DataSource.Factory { body },
            Handler(Looper.getMainLooper()),
            { true },
            { estimate, bytes -> host.bandwidthSample(estimate, bytes, clock.nanos / 1_000_000) },
            { clock.nanos }
        ).createDataSource()
        try {
            session.start(73_000)
            runCurrent()
            session.ready()
            runCurrent()
            output.moment = PlaybackPosition(74_000, true)
            host.stateSource = mutableStateOf(session.state.value)
            source.open(spec)
            source.read(buffer, 0, buffer.size)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals(1, requests.size, "One window must not lower quality")
            source.read(buffer, 0, buffer.size)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertFalse(body.closed, "A long progressive response has not ended")
            assertEquals(listOf(20_000_000, 4_000_000), requests.map { it.getValue("MaxStreamingBitrate").jsonPrimitive.int })
            assertEquals(4_000_000, requests.last().getValue("DeviceProfile").jsonObject.getValue("MaxStreamingBitrate").jsonPrimitive.int)
            assertEquals(740_000_000L, requests.last().getValue("StartTimeTicks").jsonPrimitive.long)
            assertEquals(PlaybackPosition(74_000, true), output.moment)
        } finally {
            source.close()
            session.close()
            runCurrent()
            host.reportScope.cancel()
            http.close()
        }
    }

    @Test fun fastReadsSeparatedByLoaderOrViewerIdleDoNotBecomeSlowNetworkSamples() {
        val clock = Clock()
        val body = Body(clock).apply { readNanos = 100_000_000 }
        val guard = BandwidthQualityGuard()
        val estimates = mutableListOf<Long>()
        var reductions = 0
        val source = ActiveReadBandwidthDataSource(body, { clock.nanos }) { estimate, bytes ->
            estimates += estimate
            if (guard.reduction(20_000_000, estimate, bytes, clock.nanos / 1_000_000) != null) reductions++
        }
        source.open(spec)
        repeat(20) {
            clock.nanos += 30_000_000_000 // Time outside read must never enter its throughput denominator.
            source.read(buffer, 0, buffer.size)
        }
        assertEquals(listOf(62_914_560L, 62_914_560L), estimates)
        assertEquals(0, reductions)
        source.close()
    }

    @Test fun localAndNotFullSpeedTransfersNeverProvideQualitySamples() {
        for ((network, flags) in listOf(false to 0, true to DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)) {
            val clock = Clock()
            var samples = 0
            val source = ActiveReadBandwidthDataSource(Body(clock, network), { clock.nanos }) { _, _ -> samples++ }
            source.open(spec.buildUpon().setFlags(flags).build())
            repeat(3) { source.read(buffer, 0, buffer.size) }
            source.close()
            assertEquals(0, samples)
        }
    }

    @Test fun eofAndReadFailureDoNotFlushPartialSamplesAndReopenStartsFresh() {
        val clock = Clock()
        val body = Body(clock).apply { readNanos = 500_000_000 }
        var samples = 0
        val source = ActiveReadBandwidthDataSource(body, { clock.nanos }) { _, _ -> samples++ }
        source.open(spec)
        source.read(buffer, 0, buffer.size)
        body.readBytes = C.RESULT_END_OF_INPUT
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        body.fail = true
        assertFailsWith<IOException> { source.read(buffer, 0, buffer.size) }
        source.close()
        assertEquals(0, samples)
        body.fail = false
        body.readBytes = buffer.size
        source.open(spec)
        source.read(buffer, 0, buffer.size)
        assertEquals(0, samples, "A new HTTP open cannot complete the prior partial window")
        source.read(buffer, 0, buffer.size)
        assertEquals(1, samples)
        source.close()
    }

    @Test fun applicationThreadDeliveryRejectsQueuedSampleAfterSourceRetires() {
        val clock = Clock()
        val body = Body(clock)
        var current = true
        var samples = 0
        val source = progressiveBandwidthSources(
            DataSource.Factory { body },
            Handler(Looper.getMainLooper()),
            { current },
            { _, _ -> samples++ },
            { clock.nanos }
        ).createDataSource()
        source.open(spec)
        source.read(buffer, 0, buffer.size)
        assertEquals(0, samples, "Loader callbacks must be posted to the application thread")
        current = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, samples)
        source.close()
        assertTrue(body.closed)
    }
}
