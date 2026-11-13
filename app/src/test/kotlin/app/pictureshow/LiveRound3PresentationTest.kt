/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class LiveRound3PresentationTest {
    private val ready = LiveTvState(loading = false, policy = LivePolicy(true, true, true, true))
    private val channel = LiveChannel("c", "Channel", "1")
    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()

    @Test fun permissionWinsOverLoadingErrorsAndFiltersWithoutLeakingTheRawResponse() {
        val state = ready.copy(policy = LivePolicy(), loading = true, error = "HTTP 403", offline = true)
        val result = liveEmptyPresentation(state, "Guide", 0, true, "bad")!!
        assertEquals("Live TV isn't available on this account", result.title)
        assertEquals(LiveEmptyAction.Back, result.action)
        assertTrue(!result.toString().contains("403"))
    }

    @Test fun coldReadFailureAndSuccessfulEmptyGuideRequireDifferentRecoveryActions() {
        val emptyGuide = ready.copy(channels = listOf(channel))
        val failure = liveEmptyPresentation(emptyGuide.copy(offline = true, error = "HTTP 503"), "Guide", 1, false, "")!!
        assertEquals("Couldn't load Live TV", failure.title)
        assertEquals(LiveEmptyAction.Retry, failure.action)
        assertTrue(!failure.body.contains("XMLTV"))
        assertTrue(!failure.toString().contains("503"))
        val empty = liveEmptyPresentation(emptyGuide, "Guide", 1, false, "")!!
        assertEquals("No guide data", empty.title)
        assertEquals(LiveEmptyAction.Channels, empty.action)
        assertTrue(empty.body.contains("XMLTV"))
        assertNull(liveEmptyPresentation(emptyGuide, "Channels", 1, false, ""))
    }

    @Test fun favoritesAndSearchCanBeClearedWithoutInventingAMissingTuner() {
        val state = ready.copy(channels = listOf(channel))
        assertEquals(LiveEmptyAction.Channels, liveEmptyPresentation(state, "Channels", 0, true, "")!!.action)
        assertEquals("No favourite channels yet", liveEmptyPresentation(state, "Guide", 0, true, "")!!.title)
        assertEquals(LiveEmptyAction.ClearFilters, liveEmptyPresentation(state, "Channels", 0, true, "abc")!!.action)
        assertNull(liveEmptyPresentation(state.copy(loading = true), "Guide", 0, true, ""))
        assertEquals("No tuner configured", liveEmptyPresentation(ready.copy(tunerEnabled = false), "Channels", 0, false, "")!!.title)
        assertEquals("No recordings yet", liveEmptyPresentation(ready, "Recordings", 0, false, "")!!.title)
        assertEquals("No scheduled recordings", liveEmptyPresentation(ready, "Schedules", 0, false, "")!!.title)
    }

    @Test fun anActiveFailedOrUnboundedTimerIsNeverCalledRecordingNow() {
        fun row(start: Long?, end: Long?, status: String = "New") = LiveSchedule(
            buildJsonObject {
                put("Id", "timer")
                put("Status", status)
                start?.let { put("StartDate", Instant.ofEpochMilli(it).toString()) }
                end?.let { put("EndDate", Instant.ofEpochMilli(it).toString()) }
            }
        )
        assertEquals("Recording now", liveScheduleGroup(row(now, now + 1), now))
        assertEquals("Past", liveScheduleGroup(row(now - 2, now), now))
        assertEquals("Upcoming", liveScheduleGroup(row(now + 1, now + 2), now))
        listOf(row(null, null), row(now + 2, now + 1), row(now - 1, now + 1, "Error"), row(now - 1, now + 1, "Cancelled")).forEach {
            assertEquals("Other schedules", liveScheduleGroup(it, now))
        }
        assertEquals(
            listOf("Recording now", "Upcoming", "Series rules", "Other schedules", "Past"),
            liveScheduleGroups(emptyList(), emptyList(), now).map {
                it.title
            }
        )
    }

    @Test fun liveEpisodeUsesCatalogSpecialsAndIncompleteNumberRules() {
        fun label(season: Int?, episode: Int?) =
            liveProgramEpisodeLabel(LiveProgram("p", "Show", "c", seasonNumber = season, episodeNumber = episode))
        assertEquals("S1:E14", label(1, 14))
        assertEquals("Special E2", label(0, 2))
        assertEquals("S3", label(3, null))
        assertEquals("E4", label(null, 4))
        assertNull(label(null, null))
    }

    @Test fun relativeStateDoesNotRepeatTheClockTimeAndMissingDatesDoNotInventAnAiring() {
        val range = "7:39 – 8:07 AM"
        assertEquals("Ended · $range", liveProgramTimeLabel(range, null, Instant.ofEpochMilli(now).toString(), now))
        assertEquals("Starts in 12 min · $range", liveProgramTimeLabel(range, Instant.ofEpochMilli(now + 720_000).toString(), null, now))
        assertEquals(range, liveProgramTimeLabel(range, null, null, now))
    }

    @Test fun rawTransportStatusesDoNotReachViewerCopyButValidationStillDoes() {
        assertEquals("Couldn't complete this change. Check your connection and try again.", liveVisibleError("Request failed (503)."))
        assertEquals("Favorites are disabled for this viewer.", liveVisibleError("Favorites are disabled for this viewer."))
    }
}
