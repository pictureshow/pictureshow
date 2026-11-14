/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveRecordingOfflineTest {
    private val policy = OfflinePolicy(true, true, true, true)
    private val qualified = OfflinePreset.entries.toSet()
    private val finished = OfflineCandidate("recording", "Episode", sizeBytes = 125, canDownload = true, recordingStatus = "Completed")

    @Test fun completedRecordingUsesNormalFinitePresetsAndOriginalPath() {
        val original = offlinePresets(policy, finished, qualified).first()
        assertTrue(original.available)
        assertEquals(125L, original.estimateBytes)
        assertEquals("/Items/recording/Download", offlineOriginalRequest(finished.itemId).path)
        assertEquals(listOf("recording"), offlineSelect(listOf(finished), OfflinePreset.original).itemIds)
    }

    @Test fun growthFlagOverridesCompletedOrMissingStatus() {
        assertTrue(offlinePresets(policy, finished.copy(type = "Recording", recordingStatus = null), qualified).none { it.available })
        listOf("Completed", null).forEach { status ->
            val growing = finished.copy(isInProgress = true, recordingStatus = status)
            assertTrue(offlinePresets(policy, growing, qualified).all { !it.available && it.reason == "recording-incomplete" })
            assertEquals(listOf("recording"), offlineSelect(listOf(growing), OfflinePreset.original).skipped)
        }
    }

    @Test fun recorderFailuresAndUnknownExplicitStatusCannotBecomeFiniteDownloads() {
        listOf("New", "InProgress", "Cancelled", "Conflict", "Error", "FutureStatus").forEach { status ->
            assertTrue(offlinePresets(policy, finished.copy(recordingStatus = status), qualified).none { it.available })
        }
        assertTrue(offlinePresets(policy, finished.copy(recordingStatus = null), qualified).first().available)
    }

    @Test fun completedStateCannotOverrideInfiniteSourceOrDownloadDenial() {
        assertTrue(offlinePresets(policy, finished.copy(isInfinite = true), qualified).none { it.available })
        assertFalse(offlinePresets(policy, finished.copy(canDownload = false), qualified).first().available)
        assertFalse(offlinePresets(policy.copy(enableDownloads = false), finished, qualified).first().available)
        assertFalse(offlinePresets(policy.copy(enablePlayback = false), finished, qualified).first().available)
    }
}
