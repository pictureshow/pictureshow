/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncCloseReason
import app.pictureshow.core.sync.SyncStayFacts
import app.pictureshow.core.sync.syncCloseDecision
import app.pictureshow.core.sync.syncOpensGroupEntry
import app.pictureshow.core.sync.syncStayEndsOnDenial
import app.pictureshow.core.sync.syncStayReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** SYN-08 stay-in-group cases run against the production core policy. */
class SyncStayVectorsTest {
    @Test fun portableStayCasesExerciseProductionPolicy() {
        val resource = requireNotNull(javaClass.classLoader.getResource("sync-vectors/sync-stay.json"))
        val suite = Json.parseToJsonElement(resource.readText()).jsonObject
        assertEquals("sync-stay", suite.getValue("suite").jsonPrimitive.content)
        val cases = suite.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(29, cases.size)
        assertEquals(setOf("close", "reminder", "denial", "open"), cases.map { it.str("op") }.toSet())
        cases.forEach { case ->
            val id = case.str("id")
            val input = case.getValue("input").jsonObject
            val expected = case.getValue("expected")
            when (case.str("op")) {
                "close" -> {
                    val d = syncCloseDecision(
                        SyncCloseReason.valueOf(input.str("reason")),
                        input.bool("participating"),
                        input.bool("following")
                    )
                    val e = expected.jsonObject
                    assertEquals(e.str("outcome"), d.outcome.name, id)
                    assertEquals(e.bool("ignoreWait"), d.ignoreWait, id)
                    assertEquals(e.optStr("departure"), d.departure?.name, id)
                }
                "reminder" -> {
                    val r = syncStayReminder(
                        SyncStayFacts(
                            input.optStr("groupId"),
                            input.optStr("groupName"),
                            input.str("phase"),
                            input.getValue("queueEntries").jsonPrimitive.int,
                            input.bool("playerOpen"),
                            input.bool("connected"),
                            input.bool("leavePending")
                        )
                    )
                    if (expected is JsonNull) {
                        assertEquals(null, r, id)
                    } else {
                        val e = expected.jsonObject
                        assertEquals(e.optStr("groupName"), r?.groupName, id)
                        assertEquals(e.bool("rejoin"), r?.rejoin, id)
                        assertEquals(e.bool("leaveUnconfirmed"), r?.leaveUnconfirmed, id)
                    }
                }
                "denial" -> assertEquals(
                    expected.jsonPrimitive.boolean,
                    syncStayEndsOnDenial(input.str("reason"), input.bool("playerOpen")),
                    id
                )
                "open" -> assertEquals(expected.jsonPrimitive.boolean, syncOpensGroupEntry(input.bool("following")), id)
                else -> error("Unknown op in $id")
            }
        }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.optStr(key: String): String? = getValue(key).let { v: JsonElement -> if (v is JsonNull) null else v.jsonPrimitive.content }
}
