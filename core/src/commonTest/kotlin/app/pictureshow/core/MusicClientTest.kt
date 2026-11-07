/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.music.MusicClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MusicClientTest {
    private val account = Account(Server("s", "Test", "https://media.example.test/", "12.0.0"), User("viewer", "Viewer"), "token")
    private val sent = mutableListOf<Pair<String, JsonObject>>()

    // The engine shares the test's clock, or the client's request timeout fires at once on virtual time.
    private fun TestScope.client() = MusicClient(
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        sent += "${request.method.value} ${request.url.encodedPath}" to
                            Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        respond("""{"Id":"new"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        ),
        ClientIdentity("TV", "test-device")
    )

    /** The server's own default is public, so leaving the field out would show the playlist to every user on it. */
    @Test fun aNewPlaylistOnTheWireIsPrivateUnlessTheViewerChose() = runTest {
        val music = client()
        assertEquals("new", music.createPlaylist(account, "Mine", listOf("a")).id)
        music.createPlaylist(account, "Ours", emptyList(), isPublic = true)
        assertEquals("${HttpMethod.Post.value} /Playlists", sent[0].first)
        assertFalse(sent[0].second.getValue("IsPublic").jsonPrimitive.boolean)
        assertEquals(true, sent[1].second.getValue("IsPublic").jsonPrimitive.boolean)
    }

    @Test fun aRenameLeavesWhoCanSeeItAlone() = runTest {
        client().renamePlaylist(account, "p", "New")
        assertEquals(setOf("Name"), sent.single().second.keys)
    }
}
