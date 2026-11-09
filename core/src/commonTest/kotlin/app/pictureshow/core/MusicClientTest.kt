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
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    private val lines = mutableListOf<String>()
    private var failAt = 0

    // The engine shares the test's clock, or the client's request timeout fires at once on virtual time.
    private fun TestScope.client() = MusicClient(
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        lines += request.url.toString()
                        sent += "${request.method.value} ${request.url.encodedPath}" to
                            ((request.body as? TextContent)?.let { Json.parseToJsonElement(it.text).jsonObject } ?: JsonObject(emptyMap()))
                        val status = if (failAt == lines.size) HttpStatusCode.Forbidden else HttpStatusCode.OK
                        respond("""{"Id":"new"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
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

    private val ids = (1..500).map { it.toString().padStart(32, '0') }

    /** 500 ids in one URL is about 16 KB, twice the server's request-line limit; batches keep every line well under it. */
    @Test fun aLongAddGoesInBatchesUnderTheRequestLineLimit() = runTest {
        assertEquals("ok", client().addToPlaylist(account, "p", ids).outcome)
        assertEquals(5, lines.size)
        assertTrue(lines.all { it.length < 4_096 }, lines.map { it.length }.toString())
        val sentIds = lines.flatMap { Url(it).parameters["ids"]!!.split(",") }
        assertEquals(ids, sentIds)
    }

    @Test fun aLongRemoveGoesInBatchesToo() = runTest {
        assertEquals("ok", client().removeFromPlaylist(account, "p", ids).outcome)
        assertEquals(ids, lines.flatMap { Url(it).parameters["entryIds"]!!.split(",") })
        assertTrue(lines.all { it.length < 4_096 })
    }

    /** A failure after a batch was applied leaves the playlist partly changed: only a read-back can say, so it's unknown. */
    @Test fun aFailureAfterAnAppliedBatchIsUnknownAndStops() = runTest {
        failAt = 2
        assertEquals("unknown", client().addToPlaylist(account, "p", ids).outcome)
        assertEquals(2, lines.size)
    }

    @Test fun aRefusalOfTheFirstBatchIsThatRefusal() = runTest {
        failAt = 1
        assertEquals("denied", client().addToPlaylist(account, "p", ids).outcome)
        assertEquals(1, lines.size)
    }
}
