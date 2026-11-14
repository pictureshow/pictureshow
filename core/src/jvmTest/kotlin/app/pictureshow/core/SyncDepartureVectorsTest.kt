/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncDeparture
import app.pictureshow.core.sync.SyncDepartureCause
import app.pictureshow.core.sync.SyncDepartureEvent
import app.pictureshow.core.sync.SyncDepartureState
import app.pictureshow.core.sync.reduceSyncDeparture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Fixed external traces; the only reducer called is the production core API. */
class SyncDepartureVectorsTest {
    @Test fun portableDepartureTracesExerciseProductionReducer() {
        val resource = requireNotNull(javaClass.classLoader.getResource("sync-vectors/sync-departure.json"))
        val suite = Json.parseToJsonElement(resource.readText()).jsonObject
        assertEquals("sync-departure", suite.getValue("suite").jsonPrimitive.content)
        assertEquals(1L, suite.getValue("schema").jsonPrimitive.long)
        val cases = suite.getValue("cases").jsonArray
        assertEquals(18, cases.size, "Keep the independently reviewed trace denominator")
        cases.forEach { element ->
            val case = element.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            var actual = state(case.getValue("initial").jsonObject)
            case.getValue("steps").jsonArray.forEachIndexed { index, stepElement ->
                val step = stepElement.jsonObject
                actual = reduceSyncDeparture(actual, event(step.getValue("event").jsonObject))
                assertEquals(state(step.getValue("expected").jsonObject), actual, "$id step$index")
            }
        }
    }

    private fun nullableLong(e: JsonElement): Long? = if (e is JsonNull) null else e.jsonPrimitive.long
    private fun nullableString(e: JsonElement): String? = if (e is JsonNull) null else e.jsonPrimitive.content
    private fun state(v: JsonObject): SyncDepartureState {
        val pending = v.getValue("pending").takeUnless { it is JsonNull }?.jsonObject?.let {
            SyncDeparture(
                it.getValue("id").jsonPrimitive.long,
                nullableString(it.getValue("groupName")),
                SyncDepartureCause.valueOf(it.getValue("cause").jsonPrimitive.content)
            )
        }
        return SyncDepartureState(
            v.getValue("scope").jsonPrimitive.long,
            v.getValue("revision").jsonPrimitive.long,
            pending,
            nullableLong(v.getValue("presentedIn"))
        )
    }
    private fun event(v: JsonObject): SyncDepartureEvent {
        val scope = v.getValue("scope").jsonPrimitive.long
        return when (v.getValue("kind").jsonPrimitive.content) {
            "ScopeChanged" -> SyncDepartureEvent.ScopeChanged(scope)
            "MembershipStarted" -> SyncDepartureEvent.MembershipStarted(scope)
            "Recorded" -> SyncDepartureEvent.Recorded(
                scope,
                nullableString(v.getValue("name")),
                SyncDepartureCause.valueOf(v.getValue("cause").jsonPrimitive.content)
            )
            "Presented" -> SyncDepartureEvent.Presented(
                scope,
                v.getValue("id").jsonPrimitive.long,
                v.getValue("chooserSession").jsonPrimitive.long
            )
            "Dismissed" -> SyncDepartureEvent.Dismissed(
                scope,
                v.getValue("id").jsonPrimitive.long,
                v.getValue("chooserSession").jsonPrimitive.long
            )
            else -> error("Unknown departure event; do not silently skip a portable trace: $v")
        }
    }
}
