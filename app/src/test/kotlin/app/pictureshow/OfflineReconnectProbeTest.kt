/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineReconnectFacts
import app.pictureshow.core.offline.OfflineTrack
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class OfflineReconnectProbeTest {
    private val server = MockWebServer()
    private val entry = OfflineEntry(
        "i:original",
        "i",
        OfflineMediaKind.movie,
        sourceId = "s1",
        preset = OfflinePreset.original,
        labels = OfflineLabels("Film")
    )
    private val track = OfflineTrack(6, "audio", "Japanese", "jpn", available = false)
    private var me = MockResponse().setBody("""{"Id":"viewer","Policy":{"EnableMediaPlayback":true}}""")
    private var item = MockResponse().setBody(
        """{"Id":"i","Name":"Film","Type":"Movie","MediaSources":[{"Id":"s1","MediaStreams":[{"Index":6,"Type":"Audio","Language":"jpn"}]}]}"""
    )

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/Users/Me") me else item
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun check(expired: Boolean = false): OfflineReconnectFacts = runBlocking {
        val account = Account(Server("s", "S", server.url("/").toString(), "12"), User("viewer", "V"), "token", expired = expired)
        OfflineApiReconnectProbe(OfflineApi(OkHttpClient(), ClientIdentity("Device", "device", "1")), account, entry).check(track)
    }

    @Test fun `the same viewer, allowed, with the track, is an offer's facts`() = assertEquals(
        OfflineReconnectFacts("ok", true, true),
        check()
    )

    @Test fun `an expired sign-in doesn't ask the server`() {
        assertEquals("signed-out", check(expired = true).server)
        assertEquals(0, server.requestCount)
    }

    @Test fun `another viewer behind the same address is signed out`() {
        me = MockResponse().setBody("""{"Id":"someone-else","Policy":{"EnableMediaPlayback":true}}""")
        assertEquals("signed-out", check().server)
    }

    @Test fun `a rejected token is signed out`() {
        me = MockResponse().setResponseCode(401)
        assertEquals("signed-out", check().server)
    }

    @Test fun `playback turned off for this account is not allowed`() {
        me = MockResponse().setBody("""{"Id":"viewer","Policy":{"EnableMediaPlayback":false}}""")
        assertEquals(OfflineReconnectFacts("ok", false, null), check())
    }

    @Test fun `an item the server no longer has means the track is gone`() {
        item = MockResponse().setResponseCode(404)
        assertEquals(OfflineReconnectFacts("ok", true, false), check())
    }

    @Test fun `a replaced file with that index on another track means the track is gone`() {
        item = MockResponse().setBody(
            """{"Id":"i","Name":"Film","Type":"Movie","MediaSources":[{"Id":"s1","MediaStreams":[{"Index":6,"Type":"Subtitle","Language":"jpn"}]}]}"""
        )
        assertEquals(OfflineReconnectFacts("ok", true, false), check())
    }

    @Test fun `a captive portal's page is a server not answering`() {
        me = MockResponse().setBody("<html>Sign in to the airport wifi</html>")
        assertEquals("unreachable", check().server)
    }

    @Test fun `a server error is a server not answering`() {
        me = MockResponse().setResponseCode(502)
        assertEquals("unreachable", check().server)
    }

    @Test fun `nothing listening is a server not answering`() {
        val address = server.url("/").toString()
        server.shutdown()
        val account = Account(Server("s", "S", address, "12"), User("viewer", "V"), "token")
        val facts = runBlocking {
            OfflineApiReconnectProbe(OfflineApi(OkHttpClient(), ClientIdentity("Device", "device", "1")), account, entry).check(track)
        }
        assertEquals("unreachable", facts.server)
    }
}
