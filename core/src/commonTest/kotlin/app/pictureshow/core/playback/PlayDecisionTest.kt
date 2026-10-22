package app.pictureshow.core.playback

import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class PlayDecisionTest {
    private val identity = ClientIdentity("Living room TV", "device-123")
    private val server = Server("server-1", "Test Jellyfin", "https://example.test/jellyfin", "12.0.0")
    private val rootServer = server.copy(url = "http://192.0.2.5:8096/")
    private val token = "secret-token"

    private val transcodingUrl = "/videos/item-1/master.m3u8?DeviceId=device-123&MediaSourceId=src-1&VideoCodec=h264" +
        "&AudioCodec=aac&PlaySessionId=session-1&ApiKey=secret-token" +
        "&TranscodeReasons=VideoRangeTypeNotSupported%2CAudioChannelsNotSupported"

    private fun source(id: String = "src-1", directPlay: Boolean = false, directStream: Boolean = false, transcodingUrl: String? = null) =
        MediaSourceInfo(
            id = id,
            container = "mkv",
            eTag = "etag-1",
            supportsDirectPlay = directPlay,
            supportsDirectStream = directStream,
            supportsTranscoding = true,
            transcodingUrl = transcodingUrl
        )

    private fun decide(
        vararg sources: MediaSourceInfo,
        playSessionId: String? = "session-1",
        server: Server = this.server,
        mediaSourceId: String? = null
    ): PlayOutcome = PlayDecisions.decide(
        server,
        "item-1",
        PlaybackInfoResponse(sources.toList(), playSessionId),
        identity,
        token,
        mediaSourceId
    )

    private fun PlayOutcome.decision(): PlayDecision = assertIs<PlayOutcome.Play>(this).decision

    @Test
    fun onlyAuthorizationCarriesTheAccountTokenEvenWithLegacyRequiredHeaders() {
        val source = source(directPlay = true).copy(
            requiredHttpHeaders = mapOf(
                "Authorization" to "legacy",
                "authorization" to "duplicate",
                "ApiKey" to token,
                "X-Emby-Token" to token,
                "X-Test" to "required"
            )
        )
        val decision = assertIs<PlayOutcome.Play>(decide(source)).decision
        assertEquals(setOf("Authorization", "X-Test"), decision.headers.keys)
        assertTrue(decision.headers.getValue("Authorization").contains(token))
        assertEquals("required", decision.headers["X-Test"])
    }

    @Test
    fun directPlayUsesTheStaticStreamUnderTheBasePath() {
        val decision = decide(source(directPlay = true)).decision()
        assertEquals(PlayMethod.DirectPlay, decision.method)
        assertEquals(
            "https://example.test/jellyfin/Videos/item-1/stream?static=true&mediaSourceId=src-1&playSessionId=session-1&tag=etag-1",
            decision.url
        )
        assertEquals("session-1", decision.playSessionId)
        assertEquals("src-1", decision.mediaSourceId)
        assertTrue(decision.headers.getValue("Authorization").contains("Token=\"secret-token\""))
        assertFalse(decision.url.contains(token))
        assertEquals(emptyList(), decision.transcodeReasons)
    }

    @Test
    fun directStreamAlsoUsesTheStaticStream() {
        val decision = decide(source(directStream = true, transcodingUrl = transcodingUrl)).decision()
        assertEquals(PlayMethod.DirectStream, decision.method)
        assertTrue(decision.url.startsWith("https://example.test/jellyfin/Videos/item-1/stream?static=true"))
    }

    @Test
    fun transcodeResolvesTheRelativeUrlAgainstTheBasePathAndDropsTheKey() {
        val decision = decide(source(transcodingUrl = transcodingUrl)).decision()
        assertEquals(PlayMethod.Transcode, decision.method)
        assertEquals(
            "https://example.test/jellyfin/videos/item-1/master.m3u8?DeviceId=device-123&MediaSourceId=src-1&VideoCodec=h264" +
                "&AudioCodec=aac&PlaySessionId=session-1&TranscodeReasons=VideoRangeTypeNotSupported%2CAudioChannelsNotSupported",
            decision.url
        )
        assertEquals(listOf("VideoRangeTypeNotSupported", "AudioChannelsNotSupported"), decision.transcodeReasons)
        assertFalse(decision.url.contains("ApiKey", ignoreCase = true))
        assertTrue(decision.headers.getValue("Authorization").startsWith("MediaBrowser "))
    }

    @Test
    fun serverWithoutBasePathAndTrailingSlash() {
        val direct = decide(source(directPlay = true), server = rootServer).decision()
        assertTrue(direct.url.startsWith("http://192.0.2.5:8096/Videos/item-1/stream?"), direct.url)
        val transcode = decide(source(transcodingUrl = "/videos/item-1/master.m3u8?api_key=secret-token"), server = rootServer).decision()
        assertEquals("http://192.0.2.5:8096/videos/item-1/master.m3u8", transcode.url)
    }

    @Test
    fun absoluteSameOriginTranscodingUrlIsKept() {
        val decision = decide(source(transcodingUrl = "https://example.test/live/master.m3u8?a=1")).decision()
        assertEquals("https://example.test/live/master.m3u8?a=1", decision.url)
    }

    @Test
    fun transcodeOutsideTheServerOriginIsClearlyUnplayable() {
        for (url in listOf(
            "https://cdn.example.test/live/master.m3u8",
            "https://example.test:444/live/master.m3u8",
            "http://example.test/live/master.m3u8",
            "//cdn.example.test/live/master.m3u8",
            "file:///live/master.m3u8"
        )) {
            val outcome = assertIs<PlayOutcome.Unplayable>(decide(source(transcodingUrl = url)))
            assertEquals(UnplayableReason.UNSAFE_STREAM_URL, outcome.reason, url)
            assertTrue(outcome.message.contains("origin"), outcome.message)
            assertFalse(outcome.toString().contains(token))
            assertFalse(outcome.toString().contains(url))
        }
    }

    @Test
    fun protocolRelativeTranscodePreservesItsAuthorityAndUsesTheServerScheme() {
        val decision = decide(source(transcodingUrl = "//example.test/live/master.m3u8?ApiKey=secret-token")).decision()
        assertEquals("https://example.test/live/master.m3u8", decision.url)
        assertTrue(decision.headers.getValue("Authorization").contains(token))
        assertFalse(decision.url.contains(token))
    }

    @Test
    fun authenticationOriginMatchesSchemeHostAndEffectivePortOnly() {
        for (url in listOf(
            "https://example.test/manifest.m3u8",
            "https://EXAMPLE.test:443/elsewhere/segment.ts",
            "HTTPS://example.test/segment.ts",
            PlayDecisions.resolve(server, "captions/stream.srt"),
            PlayDecisions.resolve(server, "/captions/stream.srt"),
            PlayDecisions.resolve(server, "//example.test:443/captions/stream.srt")
        )) {
            assertTrue(PlayDecisions.isSameOrigin(server, url), url)
        }
        val explicitDefault = server.copy(url = "https://example.test:443/jellyfin")
        assertTrue(PlayDecisions.isSameOrigin(explicitDefault, "https://example.test/x"))
        val httpServer = server.copy(url = "http://example.test:80/jellyfin")
        assertTrue(PlayDecisions.isSameOrigin(httpServer, "http://example.test/x"))
        assertTrue(PlayDecisions.isSameOrigin(rootServer, "http://192.0.2.5:8096/segment.ts"))
    }

    @Test
    fun authenticationOriginRejectsOtherOriginsAndAmbiguousReferences() {
        for (url in listOf(
            "https://cdn.example.test/stream.srt",
            "https://example.test.evil.test/stream.srt",
            "https://example.test:444/stream.srt",
            "https://example.test:0/stream.srt",
            "https://example.test:/stream.srt",
            "https://example.test:65536/stream.srt",
            "http://example.test/stream.srt",
            "https://example.test:80/stream.srt",
            "//example.test/stream.srt",
            "/stream.srt",
            "stream.srt",
            "https:///example.test/stream.srt",
            "https://example.test@cdn.example.test/stream.srt",
            "https://name@example.test/stream.srt",
            "https://example.test\\@cdn.example.test/stream.srt",
            "https://%65xample.test/stream.srt",
            " https://example.test/stream.srt",
            "https://example.test/str\neam.srt",
            "file:///stream.srt"
        )) {
            assertFalse(PlayDecisions.isSameOrigin(server, url), url)
        }
        assertFalse(PlayDecisions.isSameOrigin(rootServer, "http://192.0.2.5/segment.ts"))
        val ipv6 = server.copy(url = "https://[2001:db8::1]/base")
        assertTrue(PlayDecisions.isSameOrigin(ipv6, "https://[2001:db8::1]:443/x"))
        assertFalse(PlayDecisions.isSameOrigin(ipv6, "https://[2001:db8::1]:444/x"))
        assertFalse(PlayDecisions.isSameOrigin(ipv6, "https://[2001:db8::2]/x"))
        assertFalse(PlayDecisions.isSameOrigin(ipv6, "https://[2001:db8::1]:/x"))
        assertFalse(PlayDecisions.isSameOrigin(ipv6, "https://[2001:db8::1]:0/x"))
    }

    @Test
    fun relativeResolutionPreservesTheBasePathWithoutTreatingAuthoritiesAsPaths() {
        assertEquals("https://example.test/jellyfin/captions/a.srt", PlayDecisions.resolve(server, "captions/a.srt"))
        assertEquals("https://example.test/jellyfin/captions/a.srt", PlayDecisions.resolve(server, "/captions/a.srt"))
        assertEquals("https://other.test/captions/a.srt", PlayDecisions.resolve(server, "//other.test/captions/a.srt"))
        assertFalse(PlayDecisions.isSameOrigin(server, PlayDecisions.resolve(server, "//other.test/captions/a.srt")))
    }

    @Test
    fun invalidServerOriginCannotProduceAnAuthenticatedStaticDecision() {
        val outcome = decide(source(directPlay = true), server = server.copy(url = "file:///media"))
        assertEquals(UnplayableReason.UNSAFE_STREAM_URL, assertIs<PlayOutcome.Unplayable>(outcome).reason)
    }

    @Test
    fun missingTranscodingUrlMeansNoCompatibleSource() {
        val outcome = decide(source(transcodingUrl = null))
        assertEquals(UnplayableReason.NO_COMPATIBLE_SOURCE, assertIs<PlayOutcome.Unplayable>(outcome).reason)
        val blank = decide(source(transcodingUrl = ""))
        assertEquals(UnplayableReason.NO_COMPATIBLE_SOURCE, assertIs<PlayOutcome.Unplayable>(blank).reason)
    }

    @Test
    fun serverErrorCodeIsRefused() {
        val outcome = PlayDecisions.decide(
            server,
            "item-1",
            PlaybackInfoResponse(listOf(source(directPlay = true)), "s", PlaybackErrorCode.NotAllowed),
            identity,
            token
        )
        assertEquals(UnplayableReason.SERVER_REFUSED, assertIs<PlayOutcome.Unplayable>(outcome).reason)
    }

    @Test
    fun multipleSourcesPickTheFirstPlayableOrTheChosenOne() {
        val unplayable = source(id = "src-0")
        val transcoded = source(id = "src-1", transcodingUrl = transcodingUrl)
        val direct = source(id = "src-2", directPlay = true)
        assertEquals("src-1", decide(unplayable, transcoded, direct).decision().mediaSourceId)
        val chosen = decide(unplayable, transcoded, direct, mediaSourceId = "src-2").decision()
        assertEquals("src-2", chosen.mediaSourceId)
        assertEquals(PlayMethod.DirectPlay, chosen.method)
        val missing = decide(transcoded, direct, mediaSourceId = "src-9")
        assertEquals(UnplayableReason.SOURCE_NOT_FOUND, assertIs<PlayOutcome.Unplayable>(missing).reason)
        val chosenUnplayable = decide(unplayable, direct, mediaSourceId = "src-0")
        assertEquals(UnplayableReason.NO_COMPATIBLE_SOURCE, assertIs<PlayOutcome.Unplayable>(chosenUnplayable).reason)
    }

    @Test
    fun absentPlaySessionIdIsCarriedAsNull() {
        val direct = decide(source(directPlay = true), playSessionId = null).decision()
        assertNull(direct.playSessionId)
        assertEquals("https://example.test/jellyfin/Videos/item-1/stream?static=true&mediaSourceId=src-1&tag=etag-1", direct.url)
        val transcode = decide(
            source(transcodingUrl = "/videos/item-1/master.m3u8?MediaSourceId=src-1"),
            playSessionId = null
        ).decision()
        assertNull(transcode.playSessionId)
        val reports = PlaybackReports(transcode).progress(Duration.ZERO, false, null, null)
        assertNull(reports.playSessionId)
    }

    @Test
    fun requiredHeadersAreSentButCannotReplaceAuthorization() {
        val withHeaders = source(directPlay = true).copy(
            requiredHttpHeaders = mapOf("User-Agent" to "x", "Authorization" to "evil", "Empty" to null)
        )
        val decision = decide(withHeaders).decision()
        assertEquals("x", decision.headers["User-Agent"])
        assertTrue(decision.headers.getValue("Authorization").startsWith("MediaBrowser "))
        assertFalse("Empty" in decision.headers)
    }

    @Test
    fun itemIdsAreEncodedInTheStaticUrl() {
        val url = PlayDecisions.staticStreamUrl(server, "a b/c", source(), null)
        assertTrue(url.startsWith("https://example.test/jellyfin/Videos/a%20b%2Fc/stream?"), url)
    }
}
