/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import app.pictureshow.core.MediaItem
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LiveRecordingOfflineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val policy = OfflinePolicy(true, true, true, true)
    private val recording = """{"Id":"r","Name":"Recorded show","Type":"Episode","CanDownload":true,
        "Status":"Completed","IsInProgress":false,"RunTimeTicks":1250000000,
        "MediaSources":[{"Id":"source","Container":"ts","Size":3995188,"IsInfiniteStream":false,
        "MediaStreams":[{"Index":0,"Type":"Video"},{"Index":1,"Type":"Audio","Language":"eng"}]}]}"""

    @Test fun completedRecordingOpensNormalDownloadPreviewAndBuildsLocalEpisode() {
        val item = json.decodeFromString<MediaItem>(recording)
        assertTrue(offlineOffersDownload(item))
        assertNotNull(offlineDownloadRoute(item, null, null))
        val apiItem = json.decodeFromString<OfflineApiItem>(recording)
        val choices = offlinePreviewChoices(policy, listOf(apiItem))
        assertTrue(choices.first { it.preset == OfflinePreset.original }.available)
        val entry = offlineBuildEntry(apiItem, OfflineChoice("r", OfflinePreset.original), 1)
        assertEquals("/Items/r/Download", entry.requestFor(entry.resources.first { it.id == "media" }, "device", "session").path)
        assertEquals("media.ts", entry.resources.first { it.id == "media" }.path)
        assertEquals(1250000000L, entry.runtimeTicks)
        assertTrue(entry.tracks.any { it.type == "audio" && it.available })
    }

    @Test fun growingRecordingIsBlockedAtDetailAndAuthoritativePreview() {
        assertFalse(offlineOffersDownload(MediaItem("growing", "Growing recording", "Recording")))
        val growing = recording.replace("\"IsInProgress\":false", "\"IsInProgress\":true")
        assertFalse(offlineOffersDownload(json.decodeFromString(growing)))
        assertNull(offlineDownloadRoute(json.decodeFromString(growing), null, null))
        val preview = offlinePreviewChoices(policy, listOf(json.decodeFromString<OfflineApiItem>(growing)))
        assertTrue(preview.all { !it.available && it.reason == "recording-incomplete" })
        assertEquals("Only finished recordings can be downloaded.", offlinePresetUnavailable(preview.first().reason))
    }

    @Test fun failedAndUnknownRecorderStatesCannotOfferDownload() {
        listOf("Error", "Cancelled", "FutureStatus").forEach { status ->
            val body = recording.replace("Completed", status)
            assertFalse(offlineOffersDownload(json.decodeFromString(body)))
            assertTrue(offlinePreviewChoices(policy, listOf(json.decodeFromString<OfflineApiItem>(body))).none { it.available })
        }
    }

    @Test fun perItemDenialSurvivesBothDtoPaths() {
        val denied = recording.replace("\"CanDownload\":true", "\"CanDownload\":false")
        assertNull(offlineDownloadRoute(json.decodeFromString(denied), null, null))
        assertTrue(offlinePreviewChoices(policy, listOf(json.decodeFromString<OfflineApiItem>(denied))).none { it.available })
    }

    @Test fun containerFileDenialDoesNotHideSelectedEpisodeDownload() {
        val series = MediaItem("series", "Series", "Series", canDownload = false)
        assertTrue(offlineOffersDownload(series))
        assertNotNull(offlineDownloadRoute(series, "season", "Season 1"))
        val season = MediaItem("season", "Season 1", "Season", seriesId = "series", canDownload = false)
        assertTrue(offlineOffersDownload(season))
        assertNotNull(offlineDownloadRoute(season, "season", "Season 1"))
        // Each leaf is still judged independently in the preview and at transfer time.
        val deniedEpisode = json.decodeFromString<OfflineApiItem>(recording.replace("\"CanDownload\":true", "\"CanDownload\":false"))
        assertTrue(offlinePreviewChoices(policy, listOf(deniedEpisode)).none { it.available })
    }

    @Test fun libraryEpisodesWithoutRecorderFieldsKeepExistingDownloadPath() {
        val item = MediaItem("episode", "Episode", "Episode")
        assertTrue(offlineOffersDownload(item))
        assertNotNull(offlineDownloadRoute(item, null, null))
    }
}
