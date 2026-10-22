/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackEntryTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val account =
        Account(Server("server", "Cinema", "https://media.example.test/base", "12.0.0"), User("user", "Sam"), "fake-token")

    private fun play() = Intent(PLAY_ACTION).putExtra("itemId", id)
    private fun view(value: String) = Intent(Intent.ACTION_VIEW, Uri.parse(value))

    @Test
    fun actionRequiresStringIdentityAndAnOptionalLongPosition() {
        assertEquals(ParsedPlaybackEntry.Play(PlaybackEntry(id)), parsePlaybackEntry(play()))
        assertEquals(ParsedPlaybackEntry.Play(PlaybackEntry(id, 0)), parsePlaybackEntry(play().putExtra("positionMs", 0L)))
        assertEquals(ParsedPlaybackEntry.Play(PlaybackEntry(id, 1234)), parsePlaybackEntry(play().putExtra("positionMs", 1234L)))
        listOf(
            Intent(PLAY_ACTION),
            Intent(PLAY_ACTION).putExtra("itemId", 123L),
            play().putExtra("positionMs", "1234"),
            play().putExtra("positionMs", 1234),
            play().putExtra("positionMs", null as String?),
            play().putExtra("positionMs", -1L),
            play().putExtra("positionMs", Long.MAX_VALUE),
            play().putExtra("server", "https://other.example.test"),
            play().setData(Uri.parse("https://other.example.test"))
        ).forEach { assertEquals(ParsedPlaybackEntry.Invalid, parsePlaybackEntry(it)) }
    }

    @Test
    fun onlyExactHexItemIdentifiersAreAcceptedAndUppercaseNormalizes() {
        listOf("", "abc", "$id/extra", "../$id", " $id", "g".repeat(32), "a".repeat(31), "a".repeat(33))
            .forEach { assertEquals(ParsedPlaybackEntry.Invalid, parsePlaybackEntry(Intent(PLAY_ACTION).putExtra("itemId", it))) }
        assertEquals(
            ParsedPlaybackEntry.Play(PlaybackEntry(id)),
            parsePlaybackEntry(Intent(PLAY_ACTION).putExtra("itemId", id.uppercase()))
        )
    }

    @Test
    fun uriParsesResumeAndExplicitStartAndRejectsAmbiguousOrOverflowingInput() {
        assertEquals(ParsedPlaybackEntry.Play(PlaybackEntry(id)), parsePlaybackEntry(view("pictureshow://play/$id")))
        assertEquals(ParsedPlaybackEntry.Play(PlaybackEntry(id, 0)), parsePlaybackEntry(view("pictureshow://play/$id?positionMs=0")))
        assertEquals(
            ParsedPlaybackEntry.Play(PlaybackEntry(id, 12_345)),
            parsePlaybackEntry(view("pictureshow://play/$id?positionMs=12345"))
        )
        listOf(
            "pictureshow://play/$id/extra", "pictureshow://play/", "pictureshow://other/$id",
            "pictureshow://user@play/$id", "pictureshow://play:123/$id", "pictureshow://play/$id#fragment",
            "pictureshow://play/$id?positionMs=", "pictureshow://play/$id?positionMs=-1",
            "pictureshow://play/$id?positionMs=1.5", "pictureshow://play/$id?positionMs=%2B1",
            "pictureshow://play/$id?positionMs=9223372036854775808", "pictureshow://play/$id?positionMs=922337203685478",
            "pictureshow://play/$id?positionMs=1&positionMs=2", "pictureshow://play/$id?url=https://other.example.test"
        ).forEach { assertEquals(ParsedPlaybackEntry.Invalid, parsePlaybackEntry(view(it)), it) }
        assertEquals(ParsedPlaybackEntry.Ignore, parsePlaybackEntry(Intent(Intent.ACTION_MAIN)))
        assertEquals(ParsedPlaybackEntry.Ignore, parsePlaybackEntry(view("https://example.test/$id")))
    }

    @Test
    fun resolvesAuthenticatedItemBeforeOpeningAndUsesServerResumeUnlessExplicitlyOverridden() = runTest {
        var requests = 0
        val item = MediaItem(id, "An Invented Film", "Movie", userData = ItemUserData(playbackPositionTicks = 456_789_000))
        val http = http(Json.encodeToString(item)) { request ->
            requests++
            assertEquals("/base/Items/$id", request.url.encodedPath)
            assertEquals(true, request.headers[HttpHeaders.Authorization]?.contains("fake-token"))
            assertEquals(null, request.url.parameters["api_key"])
        }
        val session = session(http)
        assertEquals(ResolvedPlaybackEntry.Open(item, 45_678), resolvePlaybackEntry(PlaybackEntry(id), account, session))
        assertEquals(ResolvedPlaybackEntry.Open(item, 0), resolvePlaybackEntry(PlaybackEntry(id, 0), account, session))
        assertEquals(ResolvedPlaybackEntry.Open(item, 123), resolvePlaybackEntry(PlaybackEntry(id, 123), account, session))
        assertEquals(3, requests)
        http.close()
    }

    @Test
    fun noAccountOrExpiredAccountGoesToNormalStartupWithoutLookingUpTheItem() = runTest {
        val http = http("") { error("No catalog request should happen before sign-in") }
        val session = session(http)
        assertEquals(ResolvedPlaybackEntry.NormalStartup, resolvePlaybackEntry(PlaybackEntry(id), null, session))
        assertEquals(ResolvedPlaybackEntry.NormalStartup, resolvePlaybackEntry(PlaybackEntry(id), account.copy(expired = true), session))
        http.close()
    }

    @Test
    fun missingInaccessibleAndNonPlayableItemsNeverOpenThePlayer() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)) {
            val http = http("", status)
            assertEquals(ResolvedPlaybackEntry.Unavailable, resolvePlaybackEntry(PlaybackEntry(id), account, session(http)))
            http.close()
        }
        for (item in listOf(MediaItem(id, type = "Series"), MediaItem("f".repeat(32), type = "Movie"))) {
            val http = HttpClient(
                MockEngine {
                    respond(Json.encodeToString(item), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
            assertIs<ResolvedPlaybackEntry.Unavailable>(resolvePlaybackEntry(PlaybackEntry(id), account, session(http)))
            http.close()
        }
    }

    private fun TestScope.http(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        inspect: (io.ktor.client.request.HttpRequestData) -> Unit = {}
    ) = HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                inspect(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
    }

    private fun session(http: HttpClient) = CatalogSession(
        JellyfinClient(http, ClientIdentity("TV", "invented-device")),
        AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
    )
}
