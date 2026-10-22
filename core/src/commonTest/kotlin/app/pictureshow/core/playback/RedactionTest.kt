package app.pictureshow.core.playback

import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedactionTest {
    private val token = "secret-token"
    private val identity = ClientIdentity("Living room TV", "device-123")
    private val server = Server("server-1", "Test Jellyfin", "https://example.test/jellyfin", "12.0.0")

    @Test
    fun urlKeysAreRedactedInAnySpelling() {
        assertEquals("https://h/v?a=1&ApiKey=REDACTED&b=2", Redaction.url("https://h/v?a=1&ApiKey=secret-token&b=2"))
        assertEquals("https://h/v?api_key=REDACTED", Redaction.url("https://h/v?api_key=secret-token"))
        assertEquals("https://h/v?APIKEY=REDACTED#t", Redaction.url("https://h/v?APIKEY=secret-token#t"))
        assertEquals("https://h/v?x=1", Redaction.url("https://h/v?x=1"))
    }

    @Test
    fun stripApiKeyKeepsEverythingElse() {
        assertEquals("/v/master.m3u8?a=1&b=2", Redaction.stripApiKey("/v/master.m3u8?a=1&ApiKey=secret-token&b=2"))
        assertEquals("/v/master.m3u8", Redaction.stripApiKey("/v/master.m3u8?api_key=secret-token"))
        assertEquals("/v/master.m3u8?a=1#f", Redaction.stripApiKey("/v/master.m3u8?a=1&apikey=x#f"))
        assertEquals("/v/master.m3u8", Redaction.stripApiKey("/v/master.m3u8"))
    }

    @Test
    fun encodedTokenParameterNamesAreRemovedAndMaskedWithoutChangingOtherRawParts() {
        val encoded = "https://captions.example.test/text.srt?x=%2f%2F&&%61pi_key=secret-a&API%4bEY=secret-b" +
            "&X-%45mby-Token=secret-c&x%2DMediaBrowser%2DToken=secret-d&language=en%2DUS#part"
        assertEquals("https://captions.example.test/text.srt?x=%2f%2F&&language=en%2DUS#part", Redaction.stripApiKey(encoded))
        assertEquals(
            "https://captions.example.test/text.srt?x=%2f%2F&&%61pi_key=REDACTED&API%4bEY=REDACTED" +
                "&X-%45mby-Token=REDACTED&x%2DMediaBrowser%2DToken=REDACTED&language=en%2DUS#part",
            Redaction.url(encoded)
        )
        assertEquals("/text.srt", Redaction.stripApiKey("/text.srt?%61pI%5fKeY=secret"))
        assertEquals("/text.srt?not%zzkey=value", Redaction.stripApiKey("/text.srt?not%zzkey=value"))
    }

    @Test
    fun authorizationHeaderTokenIsRedacted() {
        val header = "MediaBrowser Client=\"Pictureshow\", Device=\"TV\", DeviceId=\"d\", Version=\"1\", Token=\"secret-token\""
        val redacted = Redaction.header("Authorization", header)
        assertFalse(redacted.contains(token))
        assertTrue(redacted.endsWith("Token=\"REDACTED\""))
        assertEquals("REDACTED", Redaction.header("X-Emby-Token", token))
        assertEquals("x", Redaction.header("User-Agent", "x"))
    }

    @Test
    fun everyRequiredHeaderValueIsMaskedWithoutChangingPlaybackHeaders() {
        val secrets = mapOf(
            "Cookie" to "access_token=cookie-credential",
            "X-Session-Credential" to "arbitrary-credential",
            "X-Authorization" to "Bearer another-credential",
            "User-Agent" to "private-player-name"
        )
        val source = MediaSourceInfo(id = "src-1", supportsDirectPlay = true, requiredHttpHeaders = secrets)
        val decision = assertIs<PlayOutcome.Play>(
            PlayDecisions.decide(server, "item-1", PlaybackInfoResponse(listOf(source)), identity, token)
        ).decision
        val diagnostic = decision.toString()
        for ((name, secret) in secrets) {
            assertEquals(secret, decision.headers[name])
            assertFalse(diagnostic.contains(secret), diagnostic)
            assertTrue(diagnostic.contains("$name=REDACTED"), diagnostic)
        }
        assertFalse(diagnostic.contains(token), diagnostic)
        assertTrue(decision.headers.getValue("Authorization").contains(token))
        assertEquals(decision.headers.keys.associateWith { "REDACTED" }, Redaction.headers(decision.headers))
    }

    @Test
    fun decisionAndSourceToStringNeverContainTheToken() {
        val source = MediaSourceInfo(
            id = "src-1",
            transcodingUrl = "/videos/item-1/master.m3u8?ApiKey=secret-token",
            mediaStreams = listOf(
                MediaStream(type = MediaStreamType.Subtitle, deliveryUrl = "/Videos/item-1/Subtitles/2/Stream.srt?ApiKey=secret-token")
            )
        )
        val response = PlaybackInfoResponse(listOf(source), "session-1")
        val decision = assertIs<PlayOutcome.Play>(PlayDecisions.decide(server, "item-1", response, identity, token)).decision

        for (text in listOf(decision.toString(), source.toString(), response.toString(), source.mediaStreams.single().toString())) {
            assertFalse(text.contains(token), text)
        }
        assertTrue(decision.toString().contains("REDACTED"))
        assertTrue(source.toString().contains("ApiKey=REDACTED"))
        // The token still reaches the server, in the header only.
        assertTrue(decision.headers.getValue("Authorization").contains(token))
        assertFalse(decision.url.contains(token))
    }
}
