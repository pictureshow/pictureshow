package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class PlaybackClientTest {
    private val identity = ClientIdentity("Living room TV", "device-123")
    private val server = Server("server-1", "Test Jellyfin", "https://example.test/jellyfin", "12.0.0")
    private val account = Account(server, User("user-1", "Taylor"), "secret-token")
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): PlaybackClient {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    requests += request to request.body.toByteArray().decodeToString()
                    handler(request)
                }
            }
        }
        backgroundScope.coroutineContext.job.invokeOnCompletion { http.close() }
        return PlaybackClient(http, identity)
    }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.noContent() = respond("", HttpStatusCode.NoContent)

    private fun body(index: Int = 0): JsonObject = PlaybackJson.parseToJsonElement(requests[index].second).jsonObject

    private val transcodeResponse = """
        {"MediaSources":[{"Id":"src-1","Container":"mkv","SupportsDirectPlay":false,"SupportsDirectStream":false,
        "SupportsTranscoding":true,"TranscodingUrl":"/videos/item-1/master.m3u8?MediaSourceId=src-1&ApiKey=secret-token",
        "TranscodingSubProtocol":"hls","TranscodingContainer":"mp4"}],"PlaySessionId":"session-1"}
    """.trimIndent()

    @Test
    fun playbackInfoPostsTheProfileStartPositionAndStreamChoices() = runTest {
        val playback = client { json(transcodeResponse) }
        val result = playback.prepare(
            account,
            "item-1",
            CapabilityFixtures.dolbyVisionStreamer,
            PlaybackRequest(startPosition = 90.seconds, mediaSourceId = "src-1", audioStreamIndex = 2, subtitleStreamIndex = 4)
        )
        val decision = assertIs<PlayOutcome.Play>(assertIs<ClientResult.Success<PlayOutcome>>(result).value).decision
        assertEquals("https://example.test/jellyfin/videos/item-1/master.m3u8?MediaSourceId=src-1", decision.url)
        assertEquals("session-1", decision.playSessionId)

        val (request, _) = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://example.test/jellyfin/Items/item-1/PlaybackInfo", request.url.toString())
        assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("Token=\"secret-token\""))
        assertFalse(request.url.toString().contains("secret-token"))
        val sent = body()
        assertEquals("user-1", sent.getValue("UserId").jsonPrimitive.content)
        assertEquals(900_000_000L, sent.getValue("StartTimeTicks").jsonPrimitive.long)
        assertEquals("src-1", sent.getValue("MediaSourceId").jsonPrimitive.content)
        assertEquals(2, sent.getValue("AudioStreamIndex").jsonPrimitive.int)
        assertEquals(4, sent.getValue("SubtitleStreamIndex").jsonPrimitive.int)
        assertEquals(120_000_000, sent.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        val profile = sent.getValue("DeviceProfile").jsonObject
        assertEquals(120_000_000, profile.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        assertEquals(120_000_000, profile.getValue("MaxStaticBitrate").jsonPrimitive.int)
        assertEquals("4K Dolby Vision streamer", sent.getValue("DeviceProfile").jsonObject.getValue("Name").jsonPrimitive.content)
        assertEquals("true", sent.getValue("EnableDirectPlay").jsonPrimitive.content)
    }

    @Test
    fun defaultBitrateCapIsSentInRequestAndBothProfileLimits() = runTest {
        val playback = client { json(transcodeResponse) }
        playback.prepare(account, "item-1", CapabilityFixtures.projector)
        assertEquals(200_000_000, body().getValue("MaxStreamingBitrate").jsonPrimitive.int)
        val profile = body().getValue("DeviceProfile").jsonObject
        assertEquals(200_000_000, profile.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        assertEquals(200_000_000, profile.getValue("MaxStaticBitrate").jsonPrimitive.int)
        assertEquals(0L, body().getValue("StartTimeTicks").jsonPrimitive.long)
        assertFalse("AudioStreamIndex" in body())
    }

    @Test
    fun rawRequestDefaultSerializesTheCapAndNonPositiveCeilingsAreRejected() {
        val sent = PlaybackJson.encodeToJsonElement(PlaybackInfoRequest.serializer(), PlaybackInfoRequest()).jsonObject
        assertEquals(200_000_000, sent.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        for (invalid in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { PlaybackSettings(maxStreamingBitrate = invalid) }
            assertFailsWith<IllegalArgumentException> { PlaybackInfoRequest(maxStreamingBitrate = invalid) }
        }
    }

    @Test
    fun playbackInfoFailuresAreTyped() = runTest {
        val unauthorized = client { respond("", HttpStatusCode.Unauthorized) }
        val failure = assertIs<ClientResult.Failure>(unauthorized.prepare(account, "item-1", CapabilityFixtures.projector))
        assertEquals(ErrorKind.INVALID_CREDENTIALS, failure.error.kind)
    }

    @Test
    fun unreadablePlaybackInfoIsInvalidResponse() = runTest {
        val playback = client { json("""{"MediaSources": 5}""") }
        val failure = assertIs<ClientResult.Failure>(playback.prepare(account, "item-1", CapabilityFixtures.projector))
        assertEquals(ErrorKind.INVALID_RESPONSE, failure.error.kind)
    }

    @Test
    fun rememberedTrackChoicesIncludingOffAreSentInProgressBeforeStopped() = runTest {
        val playback = client { noContent() }
        val decision = assertIs<PlayOutcome.Play>(
            PlayDecisions.decide(
                server,
                "item-1",
                PlaybackJson.decodeFromString<PlaybackInfoResponse>(transcodeResponse),
                identity,
                "secret-token"
            )
        ).decision
        val reports = PlaybackReports(decision)
        playback.reportStart(account, reports.progress(0.seconds, false, 2, 4))
        playback.reportProgress(account, reports.progress(10.seconds, true, 1, -1))
        playback.reportStopped(account, reports.stopped(10.seconds))
        assertEquals(2, body(0).getValue("AudioStreamIndex").jsonPrimitive.int)
        assertEquals(4, body(0).getValue("SubtitleStreamIndex").jsonPrimitive.int)
        assertEquals(1, body(1).getValue("AudioStreamIndex").jsonPrimitive.int)
        assertEquals(-1, body(1).getValue("SubtitleStreamIndex").jsonPrimitive.int)
        assertEquals("https://example.test/jellyfin/Sessions/Playing/Progress", requests[1].first.url.toString())
        assertEquals("https://example.test/jellyfin/Sessions/Playing/Stopped", requests[2].first.url.toString())
        assertFalse("AudioStreamIndex" in body(2)) // The server's stop DTO has no stream selection fields.
        assertFalse("SubtitleStreamIndex" in body(2))
    }

    @Test
    fun progressReportsCarryTicksMethodIndexesAndPausedState() = runTest {
        val playback = client { noContent() }
        val decision = PlayDecisions.decide(
            server,
            "item-1",
            PlaybackJson.decodeFromString(PlaybackInfoResponse.serializer(), transcodeResponse),
            identity,
            "secret-token"
        ).let { assertIs<PlayOutcome.Play>(it).decision }
        val reports = PlaybackReports(decision)

        assertIs<ClientResult.Success<Unit>>(playback.reportStart(account, reports.progress(0.seconds, false, 1, null)))
        assertIs<ClientResult.Success<Unit>>(playback.reportProgress(account, reports.progress(12.minutes + 345.milliseconds, true, 2, 3)))
        assertIs<ClientResult.Success<Unit>>(playback.reportStopped(account, reports.stopped(15.minutes)))

        assertEquals(
            listOf("Sessions/Playing", "Sessions/Playing/Progress", "Sessions/Playing/Stopped").map { "https://example.test/jellyfin/$it" },
            requests.map { it.first.url.toString() }
        )
        val start = body(0)
        assertEquals("Transcode", start.getValue("PlayMethod").jsonPrimitive.content)
        assertEquals("session-1", start.getValue("PlaySessionId").jsonPrimitive.content)
        assertEquals("src-1", start.getValue("MediaSourceId").jsonPrimitive.content)
        assertEquals("item-1", start.getValue("ItemId").jsonPrimitive.content)
        assertEquals(1, start.getValue("AudioStreamIndex").jsonPrimitive.int)
        assertFalse("SubtitleStreamIndex" in start)
        assertEquals("false", start.getValue("IsPaused").jsonPrimitive.content)

        val progress = body(1)
        assertEquals(7_203_450_000L, progress.getValue("PositionTicks").jsonPrimitive.long)
        assertEquals("true", progress.getValue("IsPaused").jsonPrimitive.content)
        assertEquals(2, progress.getValue("AudioStreamIndex").jsonPrimitive.int)
        assertEquals(3, progress.getValue("SubtitleStreamIndex").jsonPrimitive.int)
        assertEquals("RepeatNone", progress.getValue("RepeatMode").jsonPrimitive.content)

        val stopped = body(2)
        assertEquals(9_000_000_000L, stopped.getValue("PositionTicks").jsonPrimitive.long)
        assertEquals("session-1", stopped.getValue("PlaySessionId").jsonPrimitive.content)
        assertFalse("PlayMethod" in stopped)
        for ((request, text) in requests) {
            assertFalse(request.url.toString().contains("secret-token"))
            assertFalse(text.contains("secret-token"))
            assertTrue(request.headers[HttpHeaders.Authorization]!!.startsWith("MediaBrowser "))
        }
    }

    @Test
    fun fallbackDisablesDirectMethodsAndStreamCopy() = runTest {
        val playback = client { json(transcodeResponse) }
        playback.prepare(
            account,
            "item-1",
            CapabilityFixtures.projector,
            PlaybackRequest(
                startPosition = 45.seconds,
                enableDirectPlay = false,
                enableDirectStream = false,
                allowVideoStreamCopy = false
            )
        )
        assertEquals("false", body().getValue("EnableDirectPlay").jsonPrimitive.content)
        assertEquals("false", body().getValue("EnableDirectStream").jsonPrimitive.content)
        assertEquals("false", body().getValue("AllowVideoStreamCopy").jsonPrimitive.content)
        assertEquals(450_000_000L, body().getValue("StartTimeTicks").jsonPrimitive.long)
    }

    @Test
    fun activeEncodingDeleteIsScopedAndAuthenticatedWithoutBodyOrTokenInUrl() = runTest {
        val playback = client { noContent() }
        assertIs<ClientResult.Success<Unit>>(playback.stopEncoding(account, "session/with space"))
        val (request, text) = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/jellyfin/Videos/ActiveEncodings", request.url.encodedPath)
        assertEquals("device-123", request.url.parameters["deviceId"])
        assertEquals("session/with space", request.url.parameters["playSessionId"])
        assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("secret-token"))
        assertFalse(request.url.toString().contains("secret-token"))
        assertEquals("", text)
    }

    @Test
    fun ticksAreHundredsOfNanoseconds() {
        assertEquals(1L, Ticks.of(100.nanoseconds))
        assertEquals(19L, Ticks.of(1_999.nanoseconds))
        assertEquals(10_000L, Ticks.of(1.milliseconds))
        assertEquals(10_000_000L, Ticks.of(1.seconds))
        assertEquals(10_000L, Ticks.fromMillis(1))
        assertEquals(36_000_000_000L, Ticks.of(60.minutes))
    }
}
