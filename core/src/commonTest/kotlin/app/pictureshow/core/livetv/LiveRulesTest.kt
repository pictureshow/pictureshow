/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LiveRulesTest {
    private val from = "2030-11-03T05:00:00Z"
    private val to = "2030-11-03T07:00:00Z"
    private fun program(id: String, start: String, end: String, channel: String = "c") =
        LiveProgram(id, channelId = channel, startDate = start, endDate = end)

    @Test fun windowContainsOverlapsButNeitherBoundaryTouchNorBlockedChannel() {
        val a = program("overlap", "2030-11-03T04:30:00Z", "2030-11-03T05:30:00Z")
        val b = program("start-edge", "2030-11-03T04:00:00Z", from)
        val c = program("end-edge", to, "2030-11-03T08:00:00Z")
        val denied = program("blocked", from, to, "secret")
        val malformed = program("backwards", to, from)
        assertEquals(listOf("overlap"), guidePrograms(listOf(a, a, b, c, denied, malformed), setOf("c"), from, to).map { it.id })
    }

    @Test fun repeatedHoursAreDistinctUtcInstantsRatherThanLocalClockStrings() {
        val first = program("first", "2030-11-03T01:15:00-04:00", "2030-11-03T01:45:00-04:00")
        val second = program("second", "2030-11-03T01:15:00-05:00", "2030-11-03T01:45:00-05:00")
        assertEquals(listOf("first", "second"), guidePrograms(listOf(second, first), setOf("c"), from, to).map { it.id })
        assertEquals(3_600_000L, liveInstant(second.startDate)!! - liveInstant(first.startDate)!!)
    }

    @Test fun numbersSortAsComponentsWithDeterministicUnknownNumberFallback() {
        val numbers = listOf("10", "2.10", null, "2.2", "2.09", "99999999999999999999999999", "02.2")
        val channels = numbers.mapIndexed { n, number -> LiveChannel(n.toString(), "Channel $n", number) }
        assertEquals(listOf("3", "6", "4", "1", "0", "5", "2"), sortChannels(channels + channels[0]).map { it.id })
    }

    @Test fun tuneUsesStartInclusiveEndExclusiveAndRejectsUnknownTimes() {
        val p = program("p", from, to)
        assertFalse(canTune(p, liveInstant(from)!! - 1))
        assertTrue(canTune(p, liveInstant(from)!!))
        assertFalse(canTune(p, liveInstant(to)!!))
        assertFalse(canTune(p.copy(startDate = null), liveInstant(from)!!))
    }

    @Test fun latePagesAndNewViewersCannotReuseThePreviousGuide() {
        val p = program("p", from, to)
        val cache = LiveGuideCache("viewer-a", 4, setOf("c"))
        assertEquals(cache, cache.page("viewer-b", 4, listOf(p), 10))
        assertEquals(cache, cache.page("viewer-a", 3, listOf(p), 10))
        val loaded = cache.page("viewer-a", 4, listOf(p, p.copy(id = "secret", channelId = "blocked")), liveInstant(from)!!)
        assertEquals(listOf("p"), loaded.programs.map { it.id })
        assertTrue(loaded.actionAllowed(p, liveInstant(from)!!))
        assertFalse(loaded.copy(offline = true).actionAllowed(p, liveInstant(from)!!))
        assertFalse(loaded.actionAllowed(p, liveInstant(from)!! + 300_001))
        assertFalse(loaded.actionAllowed(p, liveInstant(to)!!))
    }

    private val fields = Json.parseToJsonElement(
        """{"Id":"default-id","Name":"Old","ServiceName":"Emby","ExternalProgramId":"provider-program","KeepUntil":"UntilDeleted","ChannelId":"c","ProgramId":"old","PrePaddingSeconds":20,"PostPaddingSeconds":30,"Priority":1,"RecordNewOnly":true,"RecordAnyChannel":false,"Days":["Monday"],"ProviderExtension":{"keep":true}}"""
    ) as JsonObject

    @Test fun defaultsProjectionKeepsCommonProviderIdsAndPaddingWithoutSeriesFields() {
        val original = LiveSchedule(fields)
        val draft = timerDraft(original, program("p", from, to), series = false)
        assertEquals(JsonPrimitive("Emby"), draft.fields["ServiceName"])
        assertEquals(JsonPrimitive("provider-program"), draft.fields["ExternalProgramId"])
        assertEquals(JsonPrimitive("UntilDeleted"), draft.fields["KeepUntil"])
        assertEquals(JsonPrimitive(20), draft.fields["PrePaddingSeconds"])
        assertFalse("RecordNewOnly" in draft.fields)
        assertFalse("Id" in draft.fields)
        assertEquals("p", draft.programId)
        val series = timerDraft(original, program("p", from, to), series = true)
        assertEquals(fields["ProviderExtension"], series.fields["ProviderExtension"])
        assertEquals(fields["RecordNewOnly"], series.fields["RecordNewOnly"])
        assertFalse("Id" in series.fields)
    }

    @Test fun editingPreservesEveryUneditedFieldAndRejectsInventedProviderCapabilities() {
        val edited = editSchedule(LiveSchedule(fields), JsonObject(mapOf("Priority" to JsonPrimitive(7))), series = true)
        assertEquals(setOf("Priority"), edited.dirtyFields)
        assertEquals(fields - "Priority", edited.fields - "Priority")
        assertFailsWith<IllegalArgumentException> {
            editSchedule(edited, JsonObject(mapOf("KeepUpTo" to JsonPrimitive(5))), series = true)
        }
        assertFailsWith<IllegalArgumentException> {
            editSchedule(edited, JsonObject(mapOf("RecordNewOnly" to JsonPrimitive(false))), series = false)
        }
        assertFailsWith<IllegalArgumentException> {
            editSchedule(edited, JsonObject(mapOf("PrePaddingSeconds" to JsonPrimitive(-1))), series = true)
        }
        assertFailsWith<IllegalArgumentException> {
            editSchedule(edited, JsonObject(mapOf("RecordNewOnly" to JsonPrimitive("yes"))), series = true)
        }
    }

    @Test fun serverStatusesRemainDistinctAndDoNotPromiseRecordingSuccess() {
        val statuses = listOf("New", "InProgress", "Completed", "Cancelled", "ConflictedOk", "ConflictedNotOk", "Error", "NewServerStatus")
        assertEquals(statuses.size, statuses.map(::recordingStatus).distinct().size)
        assertTrue(recordingStatus("NewServerStatus").contains("NewServerStatus"))
    }
}
