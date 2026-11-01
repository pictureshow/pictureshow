/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.AudioDecoder
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.CodecLevel
import app.pictureshow.core.playback.Combo
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DeviceProfileBuilder
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.HdrFormat
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayDecisions
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.PlaybackReports
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackSettings
import app.pictureshow.core.playback.ProfileEvaluator
import app.pictureshow.core.playback.Redaction
import app.pictureshow.core.playback.Ticks
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
import app.pictureshow.core.playback.VideoPerformancePoint
import app.pictureshow.core.playback.VideoProfileCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/** Executes checked-in expectations only. It deliberately has no snapshot update mode. */
class ReferenceParityVectorsTest {
    private val json = PlaybackJson

    @Test
    fun everyReferenceVectorMatchesProduction() = runTest {
        val directory = File(requireNotNull(javaClass.classLoader.getResource("parity-vectors")).toURI())
        val files = directory.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }.toList()
        assertTrue(files.isNotEmpty(), "No parity vectors were packaged")
        val ids = mutableSetOf<String>()
        val failures = mutableListOf<String>()
        var executed = 0
        files.forEach { file ->
            val document = json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(1, document.int("schemaVersion"), file.name)
            val suite = document.text("suite")
            require(suite in setOf("reference", "application", "recovery")) { "Unhandled suite $suite in ${file.name}" }
            val cases = document.getValue("cases").jsonArray
            assertTrue(cases.isNotEmpty(), "Empty suite ${file.name}")
            cases.forEach { case ->
                val vector = case.jsonObject
                val id = vector.text("id")
                require(ids.add(id)) { "Duplicate vector id $id" }
                // Companion suite runners execute application policies and playback recovery.
                if (suite == "reference") {
                    executed++
                    try {
                        val actual =
                            execute(
                                vector.text("operation"),
                                vector.getValue("input").jsonObject,
                                document.getValue("context").jsonObject,
                                StandardTestDispatcher(testScheduler)
                            )
                        assertEquals(vector.getValue("expected"), actual, id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        failures += "$id: ${failure.message}"
                    }
                } else {
                    require(file.relativeTo(directory).invariantSeparatorsPath == "$suite.json") {
                        "Companion runner only handles the root $suite.json"
                    }
                }
            }
        }
        assertTrue(executed > 0)
        assertTrue(failures.isEmpty(), "${failures.size}/$executed parity vectors failed:\n${failures.joinToString("\n")}")
    }

    private suspend fun execute(operation: String, input: JsonObject, context: JsonObject, dispatcher: CoroutineDispatcher): JsonElement {
        val server = json.decodeFromJsonElement<Server>(context.getValue("server"))
        val identityFields = context.getValue("identity").jsonObject
        val identity = ClientIdentity(identityFields.text("deviceName"), identityFields.text("deviceId"), identityFields.text("version"))
        val credentials = context.getValue("account").jsonObject
        val account =
            Account(
                server,
                User(credentials.text("userId"), "Fixture viewer"),
                credentials.text("token"),
                deviceId = credentials.optionalText("deviceId")
            )
        return when (operation) {
            "subtitle-selection-local" -> JsonPrimitive(
                app.pictureshow.core.playback.ExternalSubtitleLoading.canSelectLocally(
                    input.bool("transcoding"),
                    input.bool("subtitle"),
                    input.bool("currentLocal"),
                    input.bool("nextLocal")
                )
            )
            "external-subtitle-loading" -> obj(
                "retryDelayMs" to JsonPrimitive(
                    app.pictureshow.core.playback.ExternalSubtitleLoading.retryDelayMs(input.int("errorCount"))
                ),
                "sourceOffsetMs" to JsonPrimitive(
                    app.pictureshow.core.playback.ExternalSubtitleLoading.sourceOffsetMs(
                        input.getValue("fullTimeline").jsonPrimitive.boolean,
                        input.getValue("serverStartMs").jsonPrimitive.long
                    )
                )
            )
            "address" -> invalidOrValue { obj("urls" to array(AddressCandidates.build(input.text("value")))) }
            "authorization" -> JsonPrimitive(
                AuthorizationHeader.build(
                    ClientIdentity(
                        input.optionalText("deviceName") ?: identity.deviceName,
                        input.optionalText("deviceId") ?: identity.deviceId,
                        input.optionalText("version") ?: identity.version
                    ),
                    input.optionalText("token")
                )
            )
            "sameOrigin" -> JsonPrimitive(PlayDecisions.isSameOrigin(server, input.text("url")))
            "resolve" -> JsonPrimitive(PlayDecisions.resolve(server, input.text("url")))
            "stripApiKey" -> JsonPrimitive(Redaction.stripApiKey(input.text("url")))
            "image" -> primitive(
                imageUrl(
                    server,
                    json.decodeFromJsonElement(input.getValue("item")),
                    input.int("width"),
                    input.optionalInt("height"),
                    input.bool("wide", false)
                )
            )
            "chapterImage" -> JsonPrimitive(chapterImageUrl(server, input.text("itemId"), input.int("index"), input.text("tag")))
            "trickplaySheet" -> JsonPrimitive(
                trickplaySheetUrl(server, input.text("itemId"), input.text("mediaSourceId"), input.int("width"), input.int("sheet"))
            )
            "decision" -> decisionJson(
                PlayDecisions.decide(
                    server,
                    input.text("itemId"),
                    json.decodeFromJsonElement(input.getValue("response")),
                    identity.forAccount(account),
                    account.accessToken,
                    input.optionalText("mediaSourceId")
                )
            )
            "reports" -> {
                val source = MediaSourceInfo(id = "source", liveStreamId = "live")
                val decision =
                    PlayDecision(
                        "film",
                        source,
                        PlayMethod.valueOf(input.text("method")),
                        "https://media.example.test/unused",
                        emptyMap(),
                        "session"
                    )
                val reports = PlaybackReports(decision)
                obj(
                    "progress" to
                        json.encodeToJsonElement(
                            reports.progress(
                                input.long("positionMs").milliseconds,
                                input.bool("paused"),
                                input.optionalInt("audioStreamIndex"),
                                input.optionalInt("subtitleStreamIndex"),
                                input.bool("canSeek")
                            )
                        ),
                    "stopped" to json.encodeToJsonElement(reports.stopped(input.long("positionMs").milliseconds, input.bool("failed")))
                )
            }
            "ticks" -> JsonPrimitive(Ticks.of(input.long("nanoseconds").nanoseconds))
            "profile" -> json.encodeToJsonElement(DeviceProfileBuilder.build(capabilities(input.getValue("capabilities").jsonObject)))
            "request" -> json.encodeToJsonElement(
                PlaybackClient.playbackInfoRequest(
                    account,
                    capabilities(input.getValue("capabilities").jsonObject),
                    PlaybackRequest(
                        startPosition = (input.optionalLong("startMs") ?: 0).milliseconds,
                        mediaSourceId = input.optionalText("mediaSourceId"),
                        audioStreamIndex = input.optionalInt("audioStreamIndex"),
                        subtitleStreamIndex = input.optionalInt("subtitleStreamIndex"),
                        enableDirectPlay = input.bool("enableDirectPlay", true),
                        enableDirectStream = input.bool("enableDirectStream", true),
                        allowVideoStreamCopy = input.bool("allowVideoStreamCopy", true),
                        allowAudioStreamCopy = input.bool("allowAudioStreamCopy", true)
                    )
                )
            )
            "compatibility" -> {
                val c = input.getValue("combo").jsonObject
                val combo =
                    Combo(
                        c.text(
                            "container"
                        ),
                        c.optionalText(
                            "videoCodec"
                        ),
                        c.optionalText(
                            "videoProfile"
                        ),
                        c.optionalInt(
                            "bitDepth"
                        ),
                        c.optionalText("rangeType"), c.optionalText("audioCodec"), c.optionalInt("audioChannels"),
                        c["subtitles"]?.jsonArray?.map {
                            it.jsonPrimitive.content
                        }.orEmpty(),
                        c.optionalInt("width"), c.optionalInt("height"),
                        c["frameRate"]?.takeUnless {
                            it ==
                                JsonNull
                        }?.jsonPrimitive?.double,
                        c.optionalInt("level")
                    )
                array(
                    ProfileEvaluator.directPlayFailures(
                        DeviceProfileBuilder.build(capabilities(input.getValue("capabilities").jsonObject)),
                        combo
                    ).sorted()
                )
            }
            "validateBitrate" -> invalidOrValue { JsonPrimitive(PlaybackSettings(input.int("value")).maxStreamingBitrate) }
            "http" -> http(input, account, identity, dispatcher)
            else -> error("Unknown parity operation: $operation")
        }
    }

    private suspend fun http(input: JsonObject, account: Account, identity: ClientIdentity, dispatcher: CoroutineDispatcher): JsonElement {
        val requests = mutableListOf<JsonElement>()
        val responses = input.getValue("responses").jsonArray
        val engine = MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val response = responses.getOrNull(requests.size)?.jsonObject ?: error("Unexpected extra request ${request.url}")
                    val body = request.body.toByteArray().decodeToString()
                    requests += obj(
                        "method" to JsonPrimitive(request.method.value),
                        "path" to JsonPrimitive(request.url.encodedPath),
                        "query" to JsonObject(request.url.parameters.entries().associate { it.key to array(it.value) }),
                        "authorization" to primitive(request.headers[HttpHeaders.Authorization]),
                        "body" to if (body.isEmpty()) JsonNull else json.parseToJsonElement(body)
                    )
                    respond(
                        response.getValue("body").toString(),
                        HttpStatusCode.fromValue(response.int("status")),
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        )
        val client = HttpClient(engine)
        try {
            val catalog = CatalogClient(client, identity)
            val search = SearchClient(client, identity)
            val playback = PlaybackClient(client, identity)
            val result: ClientResult<*> = when (val action = input.text("action")) {
                "catalog.views" -> catalog.views(account)
                "catalog.resume" -> catalog.resume(account)
                "catalog.nextUp" -> catalog.nextUp(account, input.optionalText("seriesId"))
                "catalog.latest" -> catalog.latest(account, input.text("parentId"))
                "catalog.seasons" -> catalog.seasons(account, input.text("seriesId"))
                "catalog.episodes" -> catalog.episodes(account, input.text("seriesId"), input.text("seasonId"))
                "catalog.item" -> catalog.item(account, input.text("itemId"))
                "catalog.items" -> catalog.items(
                    account,
                    BrowseQuery(
                        parentId = input.optionalText("parentId") ?: "library",
                        includeItemTypes = input.optionalText("includeItemTypes"),
                        recursive = input.bool("recursive", false),
                        sortBy = input.optionalText("sortBy") ?: "SortName",
                        descending = input.bool("descending", false),
                        startIndex = input.optionalInt("startIndex") ?: 0,
                        limit = input.optionalInt("limit") ?: 60,
                        ids = input["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    )
                )
                "catalog.played" -> catalog.played(account, input.text("itemId"), input.bool("value"))
                "catalog.favorite" -> catalog.favorite(account, input.text("itemId"), input.bool("value"))
                "search" -> search.search(account, input.text("query"), SearchKind.valueOf(input.text("kind")))
                "personItems" -> search.personItems(account, input.text("personId"), input.int("startIndex"), input.int("limit"))
                "prepare" -> playback.prepare(
                    account,
                    input.text("itemId"),
                    capabilities(input.getValue("capabilities").jsonObject),
                    PlaybackRequest(
                        startPosition = (
                            input.optionalLong("startMs")
                                ?: 0
                            ).milliseconds,
                        mediaSourceId = input.optionalText("mediaSourceId"),
                        audioStreamIndex = input.optionalInt("audioStreamIndex"),
                        subtitleStreamIndex = input.optionalInt("subtitleStreamIndex")
                    )
                )
                "stopEncoding" -> playback.stopEncoding(account, input.text("playSessionId"))
                "reportStart" -> playback.reportStart(account, json.decodeFromJsonElement(input.getValue("body")))
                "reportProgress" -> playback.reportProgress(account, json.decodeFromJsonElement(input.getValue("body")))
                "reportStopped" -> playback.reportStopped(account, json.decodeFromJsonElement(input.getValue("body")))
                else -> error("Unknown HTTP action $action")
            }
            val resultJson = when (result) {
                is ClientResult.Failure -> obj("kind" to JsonPrimitive("failure"), "errorKind" to JsonPrimitive(result.error.kind.name))
                is ClientResult.Success -> {
                    val fields = mutableMapOf<String, JsonElement>("kind" to JsonPrimitive("success"))
                    if (result.value is PlayOutcome) fields["outcome"] = decisionJson(result.value)
                    if (result.value is MediaItem) fields["resumeTicks"] = JsonPrimitive(result.value.userData.playbackPositionTicks)
                    JsonObject(fields)
                }
            }
            return obj("requests" to JsonArray(requests), "result" to resultJson)
        } finally {
            client.close()
        }
    }

    private fun capabilities(input: JsonObject): DeviceCapabilities {
        val display = input.getValue("display").jsonObject
        val output = input.getValue("audioOutput").jsonObject
        val settings = input["settings"]?.jsonObject
        return DeviceCapabilities(
            name = input.text("name"),
            videoDecoders = input.getValue("videoDecoders").jsonArray.map { entry ->
                val d = entry.jsonObject
                VideoDecoder(
                    codec = VideoCodec.valueOf(d.text("codec")),
                    profiles = d.getValue("profiles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    maxLevel = d["maxLevel"]?.jsonObject?.let { CodecLevel(it.int("major"), it.optionalInt("minor") ?: 0) },
                    maxWidth = d.int("maxWidth"),
                    maxHeight = d.int("maxHeight"),
                    maxFrameRate = d.getValue("maxFrameRate").jsonPrimitive.double,
                    maxFrameRateAtMaxSize =
                    d["maxFrameRateAtMaxSize"]?.jsonPrimitive?.double ?: d.getValue("maxFrameRate").jsonPrimitive.double,
                    maxBitDepth = d.optionalInt("maxBitDepth") ?: 8,
                    hdrFormats = d.hdr(),
                    dolbyVisionProfiles = d["dolbyVisionProfiles"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet().orEmpty(),
                    hardware = d.bool("hardware", true),
                    sustainablePerformance = d["sustainablePerformance"]?.jsonObject?.let {
                        VideoPerformancePoint(it.int("width"), it.int("height"), it.getValue("frameRate").jsonPrimitive.double)
                    },
                    profileCapabilities = d["profileCapabilities"]?.jsonArray?.map {
                        it.jsonObject.let { p ->
                            VideoProfileCapability(
                                p.text("profile"),
                                p["maxLevel"]?.takeUnless {
                                    it ==
                                        JsonNull
                                }?.jsonObject?.let { l -> CodecLevel(l.int("major"), l.int("minor")) },
                                p.int("maxBitDepth"),
                                p.hdr()
                            )
                        }
                    }.orEmpty()
                )
            },
            display = DisplayCapabilities(
                display.int("maxWidth"),
                display.int("maxHeight"),
                display.getValue("maxRefreshRate").jsonPrimitive.double,
                display.hdr()
            ),
            audioDecoders = input.getValue("audioDecoders").jsonArray.map {
                it.jsonObject.let { a -> AudioDecoder(AudioCodec.valueOf(a.text("codec")), a.int("maxChannels"), a.bool("hardware", true)) }
            },
            audioOutput = AudioOutput(
                output.int("pcmChannels"),
                output["passthrough"]?.jsonArray?.map {
                    PassthroughEncoding.valueOf(it.jsonPrimitive.content)
                }?.toSet().orEmpty()
            ),
            settings = PlaybackSettings(
                settings?.optionalInt("maxStreamingBitrate") ?: PlaybackSettings.DEFAULT_MAX_STREAMING_BITRATE,
                settings?.bool("passthroughEnabled", false) ?: false
            )
        )
    }

    private fun decisionJson(outcome: PlayOutcome): JsonElement = when (outcome) {
        is PlayOutcome.Unplayable -> obj("unplayable" to JsonPrimitive(outcome.reason.name))
        is PlayOutcome.Play -> outcome.decision.let { d ->
            obj(
                "method" to JsonPrimitive(d.method.name),
                "mediaSourceId" to primitive(d.mediaSourceId),
                "url" to JsonPrimitive(d.url),
                "headers" to JsonObject(d.headers.mapValues { JsonPrimitive(it.value) }),
                "playSessionId" to primitive(d.playSessionId),
                "transcodeReasons" to array(d.transcodeReasons)
            )
        }
    }

    private fun invalidOrValue(action: () -> JsonElement): JsonElement = try {
        action()
    } catch (_: IllegalArgumentException) {
        obj(
            "error" to JsonPrimitive("IllegalArgumentException")
        )
    }
    private fun JsonObject.hdr(): Set<HdrFormat> = this["hdrFormats"]?.jsonArray?.map {
        HdrFormat.valueOf(it.jsonPrimitive.content)
    }?.toSet().orEmpty()
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.optionalText(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.optionalInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.optionalLong(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.bool(key: String, fallback: Boolean? = null): Boolean =
        this[key]?.jsonPrimitive?.boolean ?: requireNotNull(fallback) { "Missing boolean $key" }
    private fun primitive(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun array(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*fields))
}
