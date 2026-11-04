/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class TouchPeopleDetailsTest {
    @Test fun fullDetailKeepsPeopleWhenTheBatchProjectionRefreshesUserData() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val body = if (request.url.encodedPath.endsWith("/Items/film")) {
                        """{"Id":"film","Type":"Movie","People":[
                            {"Id":"person","Name":"A Performer","Role":"The Guide","Type":"Actor","PrimaryImageTag":"portrait"}
                        ]}"""
                    } else {
                        """{"Items":[{"Id":"film","Type":"Movie","UserData":{"Played":true}}]}"""
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val account = Account(Server("server", "Server", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
            val client = JellyfinClient(http, ClientIdentity("Touch", "test-device"))
            val item = assertIs<ClientResult.Success<MediaItem>>(client.item(account, "film")).value
            assertEquals(MediaPerson("person", "A Performer", "The Guide", "Actor", "portrait"), item.people.single())
            assertEquals(true, item.userData.played)
        } finally {
            http.close()
        }
    }

    @Test fun missingAndNullPeopleRemainCompatibleWithOlderDetailResponses() = runTest {
        for (body in listOf("""{"Id":"person","Type":"Person"}""", """{"Id":"person","Type":"Person","People":null}""")) {
            val http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
                }
            }
            try {
                val account = Account(Server("server", "Server", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
                val client = JellyfinClient(http, ClientIdentity("Touch", "test-device"))
                assertEquals(emptyList(), assertIs<ClientResult.Success<MediaItem>>(client.item(account, "person")).value.people)
            } finally {
                http.close()
            }
        }
    }
}
