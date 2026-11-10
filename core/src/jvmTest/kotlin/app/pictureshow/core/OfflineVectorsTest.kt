/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.control.TemporaryEvent
import app.pictureshow.core.control.TemporaryState
import app.pictureshow.core.control.reduceTemporary
import app.pictureshow.core.offline.Fmp4Fragment
import app.pictureshow.core.offline.Fmp4Track
import app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES
import app.pictureshow.core.offline.OfflineBudget
import app.pictureshow.core.offline.OfflineCandidate
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineJobEvent
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineManifestResult
import app.pictureshow.core.offline.OfflineOutboxEntry
import app.pictureshow.core.offline.OfflineOutboxEvent
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineReconnectFacts
import app.pictureshow.core.offline.OfflineRequest
import app.pictureshow.core.offline.OfflineResponse
import app.pictureshow.core.offline.OfflineServerSource
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.OfflineValidator
import app.pictureshow.core.offline.OfflineVerifyInput
import app.pictureshow.core.offline.commit
import app.pictureshow.core.offline.commitReady
import app.pictureshow.core.offline.discard
import app.pictureshow.core.offline.fmp4BoxHeader
import app.pictureshow.core.offline.fmp4IndexBoxes
import app.pictureshow.core.offline.fmp4ParseMoof
import app.pictureshow.core.offline.fmp4ParseMoov
import app.pictureshow.core.offline.offlineAdmit
import app.pictureshow.core.offline.offlineAudioRequest
import app.pictureshow.core.offline.offlineBodyEnd
import app.pictureshow.core.offline.offlineCleanupRequest
import app.pictureshow.core.offline.offlineDecodeManifest
import app.pictureshow.core.offline.offlineDefaultCap
import app.pictureshow.core.offline.offlineDownloadSetDevice
import app.pictureshow.core.offline.offlineDurableOffset
import app.pictureshow.core.offline.offlineEncodeManifest
import app.pictureshow.core.offline.offlineEntryDir
import app.pictureshow.core.offline.offlineEntryId
import app.pictureshow.core.offline.offlineHandoffSelection
import app.pictureshow.core.offline.offlineNextEpisode
import app.pictureshow.core.offline.offlineOriginalRequest
import app.pictureshow.core.offline.offlinePresets
import app.pictureshow.core.offline.offlineRangeHeader
import app.pictureshow.core.offline.offlineReadyBlockers
import app.pictureshow.core.offline.offlineReconnectDecision
import app.pictureshow.core.offline.offlineResumeDecision
import app.pictureshow.core.offline.offlineResumeTicks
import app.pictureshow.core.offline.offlineSafePath
import app.pictureshow.core.offline.offlineSelect
import app.pictureshow.core.offline.offlineSha256Hex
import app.pictureshow.core.offline.offlineSidecarAudioRequest
import app.pictureshow.core.offline.offlineSubtitleRequest
import app.pictureshow.core.offline.offlineTrackOnServer
import app.pictureshow.core.offline.offlineVerify
import app.pictureshow.core.offline.offlineVideoRequest
import app.pictureshow.core.offline.reconcile
import app.pictureshow.core.offline.reduceOfflineJob
import app.pictureshow.core.offline.reduceOfflineOutbox
import app.pictureshow.core.offline.release
import app.pictureshow.core.offline.reserve
import app.pictureshow.core.offline.setCap
import app.pictureshow.core.offline.trackSwitch
import app.pictureshow.core.offline.undownloadedTracks
import app.pictureshow.core.offline.watchedPreview
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Frozen offline traces. Apple and web port them; a suite no platform runs is a contract nobody holds. */
class OfflineVectorsTest {
    private val json = Json { encodeDefaults = true }

    @Test fun job() = run("offline-job")

    @Test fun budget() = run("offline-budget")

    @Test fun resume() = run("offline-resume")

    @Test fun manifest() = run("offline-manifest")

    @Test fun outbox() = run("offline-outbox")

    @Test fun plan() = run("offline-plan")

    @Test fun reconnect() = run("offline-reconnect")

    @Test fun next() = run("offline-next")

    @Test fun fmp4() = run("offline-fmp4")

    @Test fun unknownOperationsFail() {
        assertFails { execute("unknown", JsonObject(emptyMap()), JsonObject(emptyMap())) }
        assertFails { reduceOfflineJob(OfflineJob(), OfflineJobEvent("unknown")) }
        assertFails { reduceOfflineOutbox(null, OfflineOutboxEvent("unknown")) }
    }

    @Test fun negativeBytesAreRefused() {
        assertFails { OfflineBudget(capBytes = 100).reserve("a", -1, null) }
        assertFails { OfflineBudget(capBytes = 100).commit("a", -1) }
        assertFails { OfflineBudget(capBytes = 100).discard("a", -1) }
    }

    @Test fun sha256AgreesWithThePlatformOnEveryLengthAcrossBlockBoundaries() {
        val random = Random(87)
        for (length in 0..300) {
            val bytes = random.nextBytes(length)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, offlineSha256Hex(bytes), "length $length")
        }
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", offlineSha256Hex(million))
    }

    @Test fun budgetNeverExceedsItsCapAcrossRandomOperations() {
        val random = Random(87)
        val jobs = listOf("a", "b", "c")
        repeat(200) {
            var budget = OfflineBudget(capBytes = random.nextLong(1, 1000))
            repeat(200) {
                val job = jobs.random(random)
                val result = when (random.nextInt(5)) {
                    0, 1 -> budget.reserve(job, random.nextLong(0, 200), null)
                    2 -> budget.commit(job, random.nextLong(0, 200))
                    3 -> budget.release(job)
                    else -> budget.setCap(random.nextLong(budget.usedBytes, budget.usedBytes + 1000), jobs.shuffled(random))
                }
                budget = result.budget
                val cap = requireNotNull(budget.capBytes)
                assertTrue(budget.usedBytes + budget.reservations.values.sum() <= cap, "$budget")
                assertTrue(budget.reservations.values.all { it > 0 }, "$budget")
            }
        }
    }

    @Test fun indexNeverExceeds65535References() {
        fun references(count: Int): Long {
            val fragments = List(count) { Fmp4Fragment(it.toLong(), 1, 1, it.toLong()) }
            val out = fmp4IndexBoxes(Fmp4Track(1, 1000, 0), fragments, 1024 * 1024)
            val headerAt = freeSize(out)
            return ((out[headerAt + 38].toLong() and 0xff) shl 8) or (out[headerAt + 39].toLong() and 0xff)
        }
        assertEquals(65535, references(65535))
        assertEquals(32768, references(65536))
    }

    @Test fun defaultPlaceholderHoldsHoursOfFragmentsUncoalesced() {
        val fragments = List(40_000) { Fmp4Fragment(it * 100L, 100, 10, it * 10L) }
        val out = fmp4IndexBoxes(Fmp4Track(1, 1000, 0), fragments, OFFLINE_INDEX_PLACEHOLDER_BYTES)
        assertEquals(OFFLINE_INDEX_PLACEHOLDER_BYTES, out.size)
        val at = freeSize(out)
        assertEquals("sidx", out.copyOfRange(at + 4, at + 8).decodeToString())
        assertEquals(40_000 and 0xffff, ((out[at + 38].toInt() and 0xff) shl 8) or (out[at + 39].toInt() and 0xff))
    }

    private fun freeSize(out: ByteArray): Int {
        val header = requireNotNull(fmp4BoxHeader(out, out.size))
        return if (header.first == "free") header.second.toInt() else 0
    }

    private fun run(suite: String) {
        val document = json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("offline-vectors/$suite.json")).bufferedReader().use { it.readText() }
        ).jsonObject
        assertEquals(suite, document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { value ->
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val operation = case.getValue("operation").jsonPrimitive.content
            var state = case.getValue("state")
            case.getValue("steps").jsonArray.forEachIndexed { index, entry ->
                val step = entry.jsonObject
                val output = execute(operation, state, step.getValue("event").jsonObject)
                val expected = step.getValue("expected").jsonObject
                assertTrue(expected.isNotEmpty(), "$id/$index has no assertions")
                assertProjection(expected, output, "$id/$index")
                state = nextState(operation, output, state)
            }
        }
    }

    private fun nextState(operation: String, output: JsonElement, state: JsonElement): JsonElement = when (operation) {
        "job" -> output.jsonObject.getValue("job")
        "budget" -> output.jsonObject.getValue("budget")
        "outbox" -> output.jsonObject.getValue("entry")
        "download-set" -> output
        else -> state
    }

    private fun assertProjection(expected: JsonElement, actual: JsonElement, path: String) {
        when (expected) {
            is JsonObject -> {
                assertTrue(actual is JsonObject, "$path expected object, was $actual")
                if (expected.isEmpty()) assertEquals(expected, actual, path)
                expected.forEach { (key, value) ->
                    val member = actual[key] ?: JsonNull
                    if (key == "reservations") assertEquals(value, member, "$path.$key") else assertProjection(value, member, "$path.$key")
                }
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray && actual.size == expected.size, "$path expected $expected, was $actual")
                expected.forEachIndexed { i, value -> assertProjection(value, actual[i], "$path[$i]") }
            }
            else -> assertEquals(expected, actual, path)
        }
    }

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.contentOrNull?.toLong()

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.need(key: String): String = requireNotNull(text(key)) { "Missing $key" }

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.preset(key: String = "preset") = OfflinePreset.valueOf(need(key))

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private inline fun <reified T> JsonObject.decoded(key: String): T? = get(key)?.takeUnless {
        it is JsonNull
    }?.let { json.decodeFromJsonElement<T>(it) }

    private fun refused(block: () -> JsonElement): JsonElement = try {
        block()
    } catch (_: IllegalArgumentException) {
        buildJsonObject { put("error", true) }
    }

    private fun request(request: OfflineRequest) = buildJsonObject {
        put("path", request.path)
        put("method", request.method)
        put(
            "query",
            buildJsonArray {
                request.query.forEach { (key, value) ->
                    add(
                        buildJsonArray {
                            add(JsonPrimitive(key))
                            add(JsonPrimitive(value))
                        }
                    )
                }
            }
        )
    }

    private fun manifestResult(result: OfflineManifestResult) = buildJsonObject {
        put("manifest", json.encodeToJsonElement(result.manifest))
        put("ok", result.ok)
        put("reason", result.reason)
        put("deletePaths", json.encodeToJsonElement(result.deletePaths))
        put("changedEntries", json.encodeToJsonElement(result.changedEntries))
    }

    private fun candidate(o: JsonObject) = OfflineCandidate(
        o.need("itemId"),
        o.need("type"),
        o.bool("isInfinite") ?: false,
        o.long("sizeBytes"),
        o.int("bitrate"),
        o.long("runtimeTicks"),
        o.bool("canDownload")
    )

    private fun execute(operation: String, state: JsonElement, event: JsonObject): JsonElement = when (operation) {
        "job" -> {
            val result = reduceOfflineJob(
                json.decodeFromJsonElement<OfflineJob>(state),
                OfflineJobEvent(event.need("op"), event.long("attempt"), event.text("reason"), event.bool("cellularAllowed"))
            )
            buildJsonObject {
                put("job", result.job?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                put("effects", json.encodeToJsonElement(result.effects))
                put("ignored", result.ignored)
            }
        }
        "admit" -> buildJsonObject {
            put("start", json.encodeToJsonElement(offlineAdmit(event.int("activeCount")!!, event.strings("queued"))))
        }
        "budget" -> budgetStep(json.decodeFromJsonElement<OfflineBudget>(state), event)
        "default-cap" -> buildJsonObject { put("cap", offlineDefaultCap(event.long("quotaBytes"))) }
        "range-header" -> buildJsonObject {
            put("header", offlineRangeHeader(event.long("partialBytes")!!, event.decoded<OfflineValidator>("validator")))
        }
        "decision" -> {
            val r = event.getValue("response").jsonObject
            val decision = offlineResumeDecision(
                event.long("partialBytes")!!,
                event.decoded<OfflineValidator>("stored"),
                OfflineResponse(
                    r.int("status")!!,
                    r.text("contentRange"),
                    r.text("etag"),
                    r.text("lastModified"),
                    r.long("contentLength"),
                    r.text("acceptRanges"),
                    r.text("sourceETag")
                )
            )
            buildJsonObject {
                put("action", decision.action)
                put("offset", decision.offset)
                put("totalBytes", decision.totalBytes)
                put("validator", decision.validator?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                put("reason", decision.reason)
            }
        }
        "body-end" -> buildJsonObject {
            put("action", offlineBodyEnd(event.long("receivedBytes")!!, event.long("totalBytes"), event.bool("cleanEof")!!).action)
        }
        "durable-offset" -> buildJsonObject {
            put("offset", offlineDurableOffset(event.long("partialBytes")!!))
        }
        "verify" -> {
            val result = offlineVerify(
                OfflineVerifyInput(
                    event.long("actualBytes")!!,
                    event.long("expectedBytes"),
                    event.need("actualSha256"),
                    event.text("expectedSha256"),
                    event.text("sourceETagBefore"),
                    event.text("sourceETagNow"),
                    event.long("expectedRuntimeTicks"),
                    event.long("measuredTicks")
                )
            )
            buildJsonObject {
                put("ok", result.ok)
                put("reason", result.reason)
            }
        }
        "blockers" -> buildJsonObject {
            put("blockers", json.encodeToJsonElement(offlineReadyBlockers(event.decoded<OfflineEntry>("entry")!!)))
        }
        "commit" -> manifestResult(
            json.decodeFromJsonElement<OfflineManifest>(state).commitReady(event.need("entryId"), event.long("attempt")!!)
        )
        "reconcile" -> manifestResult(
            json.decodeFromJsonElement<OfflineManifest>(state).reconcile(
                event.getValue("inventory").jsonObject.mapValues { it.value.jsonPrimitive.long }
            )
        )
        "decode" -> buildJsonObject {
            put("manifest", offlineDecodeManifest(event.need("text"))?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        }
        "roundtrip" -> {
            val manifest = event.decoded<OfflineManifest>("manifest")!!
            buildJsonObject { put("same", offlineDecodeManifest(offlineEncodeManifest(manifest)) == manifest) }
        }
        "track-switch" -> buildJsonObject {
            put("action", event.decoded<OfflineEntry>("entry")!!.trackSwitch(event.need("type"), event.int("serverIndex")))
        }
        "reconnect-tracks" -> buildJsonObject {
            val tracks = event.decoded<OfflineEntry>("entry")!!.undownloadedTracks(event.need("type"))
            put("indexes", JsonArray(tracks.map { JsonPrimitive(it.serverIndex) }))
        }
        "next-episode" -> buildJsonObject {
            val next = offlineNextEpisode(
                event.decoded<List<OfflineEntry>>("entries")!!,
                event.decoded<OfflineEntry>("current")!!,
                event.bool("serverAnswered")!!,
                event.text("serverNext")
            )
            put("entry", next?.id)
        }
        "reconnect-decision" -> {
            val decision = offlineReconnectDecision(
                event.need("trackSwitch"),
                event.need("network"),
                OfflineReconnectFacts(event.text("server") ?: "unknown", event.bool("playbackAllowed"), event.bool("trackOnServer"))
            )
            buildJsonObject {
                put("outcome", decision.outcome)
                put("metered", decision.metered)
            }
        }
        "reconnect-track-on-server" -> buildJsonObject {
            val sources = event.decoded<List<OfflineServerSource>>("sources")!!
            put("found", offlineTrackOnServer(event.decoded<OfflineTrack>("track")!!, event.text("sourceId"), sources))
        }
        "handoff-selection" -> {
            val selection =
                offlineHandoffSelection(event.need("type"), event.int("index")!!, event.int("currentAudio"), event.int("currentSubtitle"))
            buildJsonObject {
                put("audio", selection.audio)
                put("subtitle", selection.subtitle)
            }
        }
        "entry-id" -> {
            val id = offlineEntryId(event.need("itemId"), event.preset())
            buildJsonObject {
                put("id", id)
                put("dir", offlineEntryDir(id))
                put("dirIsSafe", offlineSafePath(offlineEntryDir(id)))
            }
        }
        "entry-dir" -> buildJsonObject { put("dir", offlineEntryDir(event.need("entryId"))) }
        "safe-path" -> buildJsonObject { put("safe", offlineSafePath(event.need("path"))) }
        "preview" -> {
            val preview = json.decodeFromJsonElement<OfflineManifest>(state).watchedPreview(event.strings("played").toSet())
            buildJsonObject {
                put("entryIds", json.encodeToJsonElement(preview.entryIds))
                put("titles", json.encodeToJsonElement(preview.titles))
                put("bytes", preview.bytes)
            }
        }
        "outbox" -> outboxStep(state, event)
        "resume" -> buildJsonObject {
            val ticks = offlineResumeTicks(event.decoded<OfflineOutboxEntry>("entry"), event.decoded<OfflineUserData>("lastKnown"))
            put("ticks", ticks.toString())
        }
        "download-set" -> json.encodeToJsonElement(
            reduceTemporary(json.decodeFromJsonElement<TemporaryState>(state), json.decodeFromJsonElement<TemporaryEvent>(event))
        )
        "sha256" -> buildJsonObject { put("hex", offlineSha256Hex(event.need("text").encodeToByteArray())) }
        "device" -> buildJsonObject {
            put("device", offlineDownloadSetDevice(event.need("installationDeviceId"), event.need("serverId"), event.need("userId")))
        }
        "presets" -> {
            val p = event.getValue("policy").jsonObject
            val choices = offlinePresets(
                OfflinePolicy(
                    p.bool("enableDownloads")!!,
                    p.bool("enablePlayback")!!,
                    p.bool("videoTranscoding")!!,
                    p.bool("audioTranscoding")!!
                ),
                candidate(event.getValue("candidate").jsonObject),
                event.strings("qualified").map(OfflinePreset::valueOf).toSet()
            )
            buildJsonObject {
                put(
                    "choices",
                    buildJsonArray {
                        choices.forEach { c ->
                            add(
                                buildJsonObject {
                                    put("preset", c.preset.name)
                                    put("available", c.available)
                                    put("reason", c.reason)
                                    put("estimateBytes", c.estimateBytes)
                                    put("exactSize", c.exactSize)
                                }
                            )
                        }
                    }
                )
            }
        }
        "select" -> {
            val selection = offlineSelect(event.getValue("items").jsonArray.map { candidate(it.jsonObject) }, event.preset())
            buildJsonObject {
                put("itemIds", json.encodeToJsonElement(selection.itemIds))
                put("skipped", json.encodeToJsonElement(selection.skipped))
                put("knownBytes", selection.knownBytes)
                put("estimated", selection.estimated)
                put("unknownCount", selection.unknownCount)
            }
        }
        "original-request" -> request(offlineOriginalRequest(event.need("itemId")))
        "video-request" -> refused {
            request(
                offlineVideoRequest(
                    event.need("itemId"),
                    event.need("sourceId"),
                    event.preset(),
                    event.int("audioStreamIndex"),
                    event.need("deviceId"),
                    event.need("playSessionId")
                )
            )
        }
        "sidecar-request" -> refused {
            request(
                offlineSidecarAudioRequest(
                    event.need("itemId"),
                    event.need("sourceId"),
                    event.preset(),
                    event.int("audioStreamIndex")!!,
                    event.need("deviceId"),
                    event.need("playSessionId")
                )
            )
        }
        "audio-request" -> refused {
            request(
                offlineAudioRequest(
                    event.need("itemId"),
                    event.text("sourceId"),
                    event.preset(),
                    event.need("deviceId"),
                    event.need("playSessionId")
                )
            )
        }
        "subtitle-request" -> request(offlineSubtitleRequest(event.need("itemId"), event.need("sourceId"), event.int("streamIndex")!!))
        "cleanup-request" -> request(offlineCleanupRequest(event.need("deviceId"), event.need("playSessionId")))
        "box-header" -> buildJsonObject {
            val header = fmp4BoxHeader(hex(event.need("hex")), event.int("length")!!)
            put(
                "header",
                if (header == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("type", header.first)
                        put("size", header.second)
                    }
                }
            )
        }
        "parse-moof" -> buildJsonObject {
            val fragment = fmp4ParseMoof(hex(event.need("hex")), event.int("trackId")!!, event.long("trexDefaultDuration")!!)
            put(
                "fragment",
                if (fragment == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("moofOffset", fragment.moofOffset)
                        put("totalSize", fragment.totalSize)
                        put("durationInTimescale", fragment.durationInTimescale)
                        put("earliestTime", fragment.earliestTime)
                    }
                }
            )
        }
        "parse-moov" -> buildJsonObject {
            val track = fmp4ParseMoov(hex(event.need("hex")))
            put(
                "track",
                if (track == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("id", track.id)
                        put("timescale", track.timescale)
                        put("trexDefaultDuration", track.trexDefaultDuration)
                    }
                }
            )
        }
        "index-boxes" -> refused {
            val t = event.getValue("track").jsonObject
            val out = fmp4IndexBoxes(
                Fmp4Track(t.int("id")!!, t.long("timescale")!!, t.long("trexDefaultDuration")!!),
                event.getValue("fragments").jsonArray.map {
                    val f = it.jsonObject
                    Fmp4Fragment(f.long("moofOffset")!!, f.long("totalSize")!!, f.long("durationInTimescale")!!, f.long("earliestTime")!!)
                },
                event.int("maxBytes")!!
            )
            buildJsonObject {
                put("hex", out.toHex())
                put("bytes", out.size)
            }
        }
        else -> error("Unknown offline vector operation: $operation")
    }

    private fun budgetStep(budget: OfflineBudget, event: JsonObject): JsonElement {
        val result = when (event.need("op")) {
            "reserve" -> budget.reserve(event.need("job"), event.long("bytes")!!, event.long("freeBytes"))
            "commit" -> budget.commit(event.need("job"), event.long("bytes")!!)
            "release" -> budget.release(event.need("job"))
            "discard" -> budget.discard(event.need("job"), event.long("committedBytes")!!)
            "setCap" -> budget.setCap(event.long("capBytes"), event.strings("jobNewestFirst"))
            else -> error("Unknown budget operation")
        }
        return buildJsonObject {
            put("budget", json.encodeToJsonElement(result.budget))
            put("granted", result.granted)
            put("reason", result.reason)
            put("pauseJobs", json.encodeToJsonElement(result.pauseJobs))
        }
    }

    private fun outboxStep(state: JsonElement, event: JsonObject): JsonElement {
        val entry = (state as? JsonObject)?.let { json.decodeFromJsonElement<OfflineOutboxEntry>(it) }
        val result = reduceOfflineOutbox(
            entry,
            OfflineOutboxEvent(
                event.need("op"),
                event.text("server"),
                event.text("viewer"),
                event.text("itemId"),
                event.long("positionTicks"),
                event.bool("played"),
                event.long("sequence"),
                event.decoded<OfflineUserData>("current"),
                event.decoded<OfflineUserData>("base"),
                event.int("status")
            )
        )
        return buildJsonObject {
            put("entry", result.entry?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("effect", result.effect)
            put("write", result.write?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("ignored", result.ignored)
        }
    }
}
