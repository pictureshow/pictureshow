/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class LiveRecordingSeasonPreviewTest {
    @Test fun seasonEndpointRetainsRecordingStateWithoutInventingSelectableFields() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            fun episode(id: String, state: String) = """{"Id":"$id","Name":"Recording","Type":"Episode",$state,
                "CanDownload":true,"RunTimeTicks":1250000000,"MediaSources":[{"Id":"source-$id",
                "Container":"ts","Size":3995188,"IsInfiniteStream":false,
                "MediaStreams":[{"Index":0,"Type":"Video"},{"Index":1,"Type":"Audio"}]}]}"""
            server.enqueue(
                MockResponse().setBody(
                    """{"Items":[${episode("growing", "\"IsInProgress\":true")},
                    ${episode("failed", "\"Status\":\"Error\"")},
                    ${episode("finished", "\"Status\":\"Completed\",\"IsInProgress\":false")}]}"""
                )
            )
            val account = Account(Server("s", "Demo", server.url("/").toString(), "12"), User("viewer", "Viewer"), "token")
            val episodes = OfflineApi(OkHttpClient(), ClientIdentity("Device", "device", "1"))
                .seasonEpisodes(account, "series", "season")
            val request = server.takeRequest()
            assertEquals("/Shows/series/Episodes", request.requestUrl?.encodedPath)
            assertEquals("season", request.requestUrl?.queryParameter("seasonId"))
            // These DTO properties are not members of Jellyfin's ItemFields enum.
            val fields = request.requestUrl?.queryParameter("fields").orEmpty().split(',')
            assertFalse("IsInProgress" in fields)
            assertFalse("Status" in fields)
            assertEquals(listOf("growing", "failed", "finished"), episodes.map { it.id })
            assertTrue(episodes[0].isInProgress)
            assertEquals("Error", episodes[1].recordingStatus)
            val policy = OfflinePolicy(true, true, true, true)
            episodes.take(2).forEach { episode ->
                val choices = offlinePreviewChoices(policy, listOf(episode))
                assertTrue(choices.isNotEmpty())
                assertTrue(choices.none { it.available })
                assertTrue(choices.all { it.reason == "recording-incomplete" })
            }
            assertTrue(offlinePreviewChoices(policy, listOf(episodes[2])).single { it.preset == OfflinePreset.original }.available)
        } finally {
            server.shutdown()
        }
    }
}
