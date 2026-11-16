/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.subtitles

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** SUB-DL end to end against a scripted server: what's asked, in what order, and what the panel ends on. */
class RemoteSubtitleSearchTest {
    private val account = Account(Server("s", "Server", "https://media.example.test", "12.0.0"), User("u", "viewer"), "token")
    private val json = headersOf("Content-Type", "application/json")
    private val cultures = """[{"DisplayName":"English","TwoLetterISOLanguageName":"en","ThreeLetterISOLanguageName":"eng"},
        {"DisplayName":"Spanish","TwoLetterISOLanguageName":"es","ThreeLetterISOLanguageName":"spa"}]"""
    private val before = """{"Items":[{"MediaSources":[{"Id":"src","MediaStreams":[
        {"Type":"Video","Index":0},{"Type":"Audio","Index":1,"Language":"eng"}]}]}]}"""
    private val after = """{"Items":[{"MediaSources":[{"Id":"src","MediaStreams":[
        {"Type":"Subtitle","Index":0,"Language":"spa","Codec":"srt","IsExternal":true},
        {"Type":"Video","Index":1},{"Type":"Audio","Index":2,"Language":"eng"}]}]}]}"""

    private class Script(
        val policy: String,
        val fetchers: String = """[{"Name":"Open Subtitles"}]""",
        val searchStatus: HttpStatusCode = HttpStatusCode.OK,
        val downloadStatus: HttpStatusCode = HttpStatusCode.NoContent,
        /** Item reads before the refreshed list appears. */
        val staleReads: Int = 1
    ) {
        val calls = mutableListOf<String>()
        var itemReads = 0
    }

    private fun TestScope.search(script: Script): RemoteSubtitleSearch {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val path = request.url.encodedPath + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                    script.calls += "${request.method.value} $path"
                    when {
                        path == "/Users/Me" -> respond(
                            """{"Policy":${script.policy},"Configuration":{"SubtitleLanguagePreference":"spa"}}""",
                            headers = json
                        )
                        path.startsWith("/Libraries/AvailableOptions") ->
                            respond("""{"SubtitleFetchers":${script.fetchers}}""", headers = json)
                        path == "/Localization/Cultures" -> respond(cultures, headers = json)
                        request.method == HttpMethod.Get && path.startsWith("/Items/item/RemoteSearch/Subtitles/") ->
                            if (script.searchStatus == HttpStatusCode.OK) {
                                respond(
                                    """[{"Id":"low","DownloadCount":3,"Format":"srt","ThreeLetterISOLanguageName":"spa"},
                                    {"Id":"best","IsHashMatch":true,"Format":"srt","ThreeLetterISOLanguageName":"spa"}]""",
                                    headers = json
                                )
                            } else {
                                respond("", script.searchStatus)
                            }
                        request.method == HttpMethod.Post -> respond("", script.downloadStatus)
                        path.startsWith("/Items?ids=item") -> {
                            script.itemReads++
                            respond(if (script.itemReads <= script.staleReads) before else after, headers = json)
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            }
        }
        return RemoteSubtitleSearch(RemoteSubtitleClient(http, ClientIdentity("TV", "device")), account, this, "en")
    }

    private val target = RemoteSubtitleTarget("item", "Movie", playable = true, mediaSourceId = "src", selectedAudio = 1)

    @Test fun noPermissionSaysSoWithoutSearching() = runTest {
        val script = Script("""{"IsAdministrator":false,"EnableSubtitleManagement":false}""")
        val search = search(script)
        search.open(target)
        advanceUntilIdle()
        assertEquals(RemoteSubtitlePhase.Blocked, search.state.value.phase)
        assertEquals(RemoteSubtitleAccess.NotAllowed, search.state.value.access)
        assertTrue(script.calls.none { "RemoteSearch" in it }, script.calls.toString())
    }

    @Test fun noProviderSaysSoWithoutSearching() = runTest {
        val script = Script("""{"IsAdministrator":false,"EnableSubtitleManagement":true}""", fetchers = "[]")
        val search = search(script)
        search.open(target)
        advanceUntilIdle()
        assertEquals(RemoteSubtitleAccess.NoProvider, search.state.value.access)
        assertTrue(script.calls.none { "RemoteSearch" in it }, script.calls.toString())
    }

    @Test fun offlineCopyAsksNothing() = runTest {
        val script = Script("""{"IsAdministrator":true}""")
        val search = search(script)
        search.open(target.copy(playable = false))
        advanceUntilIdle()
        assertEquals(RemoteSubtitleAccess.Unavailable, search.state.value.access)
        assertTrue(script.calls.isEmpty(), script.calls.toString())
    }

    @Test fun searchesInPreferenceThenAdoptsTheRenumberedStream() = runTest {
        val script = Script("""{"IsAdministrator":false,"EnableSubtitleManagement":true}""")
        val search = search(script)
        search.open(target)
        advanceUntilIdle()
        val state = search.state.value
        assertEquals("spa", state.language)
        assertEquals(listOf("best", "low"), state.results.map { it.id })
        assertTrue(script.calls.any { it == "GET /Items/item/RemoteSearch/Subtitles/spa" }, script.calls.toString())
        var adopted: AdoptedSubtitle? = null
        search.download(state.results.first()) { adopted = it }
        advanceUntilIdle()
        assertTrue(script.calls.any { it == "POST /Items/item/RemoteSearch/Subtitles/best" }, script.calls.toString())
        assertEquals(RemoteSubtitlePhase.Added, search.state.value.phase)
        // The new external file is listed first, so the selected audio moves from 1 to 2.
        assertEquals(AdoptedSubtitle(subtitleIndex = 0, audioIndex = 2), adopted)
    }

    @Test fun refusedSearchReadsAsNotAllowed() = runTest {
        val script = Script("""{"IsAdministrator":false}""", searchStatus = HttpStatusCode.Forbidden)
        val search = search(script)
        search.open(target)
        advanceUntilIdle()
        assertEquals(RemoteSubtitlePhase.Results, search.state.value.phase)
        assertEquals(RemoteSubtitleEmpty.NotAllowed, search.state.value.empty)
    }

    @Test fun failedDownloadKeepsResultsAndSwitchesNothing() = runTest {
        val script = Script("""{"IsAdministrator":true}""", downloadStatus = HttpStatusCode.InternalServerError)
        val search = search(script)
        search.open(target)
        advanceUntilIdle()
        var adopted: AdoptedSubtitle? = null
        search.download(search.state.value.results.first()) { adopted = it }
        advanceUntilIdle()
        assertEquals(RemoteSubtitleEmpty.Failed, search.state.value.empty)
        assertEquals(2, search.state.value.results.size)
        assertNull(adopted)
    }

    @Test fun neverListedEndsOnNotListedWithoutSwitching() = runTest {
        val script = Script("""{"IsAdministrator":true}""", staleReads = Int.MAX_VALUE)
        val search = search(script)
        search.open(target)
        advanceUntilIdle()
        var adopted: AdoptedSubtitle? = null
        search.download(search.state.value.results.first()) { adopted = it }
        advanceUntilIdle()
        assertEquals(RemoteSubtitlePhase.NotListed, search.state.value.phase)
        assertNull(adopted)
    }
}
