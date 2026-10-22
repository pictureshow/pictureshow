package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UserConfigurationTest {
    private val account = Account(Server("s", "Test", "https://media.example.test/base", "12.0.0"), User("u", "Sam"), "fake-token")

    @Test
    fun nullableLanguagesRemainNoPreferenceAndUnknownFieldsArePreserved() {
        val fields = Json.parseToJsonElement(
            """{"AudioLanguagePreference":null,"SubtitleLanguagePreference":null,"SubtitleMode":"None","Other":null}"""
        ).jsonObject
        val configuration = UserConfiguration(fields)
        assertEquals("", configuration.audioLanguage)
        assertEquals("", configuration.subtitleLanguage)
        assertEquals("None", configuration.subtitleMode)
        assertEquals(fields["Other"], configuration.withPreference("AudioLanguagePreference", "eng").fields["Other"])
    }

    @Test
    fun preferencesRoundTripWholeConfigurationAndChangeOnlyRequestedFields() = runTest {
        val original = Json.parseToJsonElement(
            """{"AudioLanguagePreference":"eng","SubtitleLanguagePreference":"eng","SubtitleMode":"Always","RememberAudioSelections":true,"RememberSubtitleSelections":true,"PlayDefaultAudioTrack":false,"UnknownFutureField":{"nested":[1,"keep"]}}"""
        ).jsonObject
        val changes = listOf("AudioLanguagePreference" to "jpn", "SubtitleLanguagePreference" to "fra", "SubtitleMode" to "OnlyForced")
        var read = false
        val writes = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("fake-token"))
                    assertTrue(!request.url.toString().contains("fake-token"))
                    if (request.method == HttpMethod.Get) {
                        assertEquals("https://media.example.test/base/Users/u", request.url.toString())
                        read = true
                        respond("{\"Configuration\":$original}")
                    } else {
                        assertTrue(read)
                        assertEquals("https://media.example.test/base/Users/Configuration?userId=u", request.url.toString())
                        writes += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        try {
            val client = UserConfigurationClient(http, ClientIdentity("TV", "device"))
            var configuration = assertIs<ClientResult.Success<UserConfiguration>>(client.configuration(account)).value
            assertEquals("eng", configuration.audioLanguage)
            assertEquals("Always", configuration.subtitleMode)
            for ((name, value) in changes) {
                configuration = configuration.withPreference(name, value)
                assertIs<ClientResult.Success<Unit>>(client.saveConfiguration(account, configuration))
            }
            changes.forEachIndexed { index, (name, value) ->
                assertEquals(value, writes[index].getValue(name).jsonPrimitive.content)
                for ((key, expected) in original) {
                    if (key !in changes.take(index + 1).map { it.first }) assertEquals(expected, writes[index][key], key)
                }
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun expiredConfigurationDoesNotBecomeAnEmptyObjectThatCanOverwritePreferences() = runTest {
        val http =
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { respond("private body", HttpStatusCode.Unauthorized) }
                }
            }
        try {
            val result = UserConfigurationClient(http, ClientIdentity("TV", "device")).configuration(account)
            assertEquals(ErrorKind.INVALID_CREDENTIALS, assertIs<ClientResult.Failure>(result).error.kind)
        } finally {
            http.close()
        }
    }
}
