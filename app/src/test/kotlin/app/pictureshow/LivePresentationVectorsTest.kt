/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, shadows = [LiveClockPatternShadow::class])
class LivePresentationVectorsTest {
    @Test fun everyPresentationVector() {
        val file = listOf(File("../spec/vectors/live-presentation.json"), File("spec/vectors/live-presentation.json")).first { it.exists() }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(160, cases.size)
        for (element in cases) {
            val envelope = element.jsonObject
            val c = envelope.getValue("input").jsonObject
            fun value(key: String) = c.getValue(key).jsonPrimitive.content
            val id = envelope.getValue("id").jsonPrimitive.content
            fun liveWindow(element: kotlinx.serialization.json.JsonElement?): app.pictureshow.core.playback.ObservedLiveWindow? {
                if (element == null || element == kotlinx.serialization.json.JsonNull) return null
                val w = element.jsonObject
                return app.pictureshow.core.playback.ObservedLiveWindow(
                    100_000,
                    w.getValue("duration").jsonPrimitive.long,
                    w.getValue("default").jsonPrimitive.long,
                    w.getValue("seekable").jsonPrimitive.boolean
                )
            }
            when (envelope.getValue("operation").jsonPrimitive.content) {
                "guide-text-window" -> {
                    val actual = liveGuideTextWindow(
                        c.getValue("cellX").jsonPrimitive.float,
                        c.getValue("cellWidth").jsonPrimitive.float,
                        c.getValue("viewportStart").jsonPrimitive.float,
                        c.getValue("viewportWidth").jsonPrimitive.float
                    )
                    val expected = envelope.getValue("expected").jsonObject
                    assertEquals(expected.getValue("inset").jsonPrimitive.float, actual.inset, 0.001f, id)
                    assertEquals(expected.getValue("width").jsonPrimitive.float, actual.width, 0.001f, id)
                }
                "recording-action-order" -> assertEquals(
                    envelope.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
                    recordingDetailActionOrder(
                        c.getValue("hasFromStart").jsonPrimitive.boolean,
                        c.getValue("hasDownload").jsonPrimitive.boolean
                    ),
                    id
                )
                "compact-height" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveCompactHeight(c.getValue("heightDp").jsonPrimitive.int, c.getValue("tv").jsonPrimitive.boolean),
                    id
                )
                "disclosure-rotation" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveDisclosureRotation(c.getValue("expanded").jsonPrimitive.boolean),
                    0.001f,
                    id
                )
                "guide-scroll" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideScrollDistance(value("offset").toFloat(), value("size").toFloat(), value("container").toFloat()),
                    id
                )

                "schedule-group" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveScheduleGroup(
                        app.pictureshow.core.livetv.LiveSchedule(c.getValue("fields").jsonObject),
                        Instant.parse(value("now")).toEpochMilli()
                    ),
                    id
                )

                "schedule-focus" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveScheduleFocusKey(
                        app.pictureshow.core.livetv.LiveSchedule(c.getValue("fields").jsonObject),
                        c.getValue("series").jsonPrimitive.boolean
                    ),
                    id
                )

                "refresh" -> {
                    val actual = liveRefreshPresentation(
                        c.getValue("loading").jsonPrimitive.boolean,
                        c.getValue("writing").jsonPrimitive.boolean,
                        c.getValue("tv").jsonPrimitive.boolean,
                        c.getValue("pulled").jsonPrimitive.boolean
                    )
                    assertEquals(
                        envelope.getValue("expected"),
                        buildJsonObject {
                            put("pullRefreshing", actual.pullRefreshing)
                            put("headerLoading", actual.headerLoading)
                        },
                        id
                    )
                }

                "live-readout" -> {
                    val actual = liveTimelineReadout(
                        liveWindow(c["window"]),
                        c.getValue("position").jsonPrimitive.long,
                        c.getValue("paused").jsonPrimitive.boolean,
                        c.getValue("expired").jsonPrimitive.boolean,
                        c.getValue("canTimeShift").jsonPrimitive.boolean
                    )
                    assertEquals(
                        envelope.getValue("expected"),
                        buildJsonObject {
                            put("status", actual.status)
                            put("clock", actual.clock)
                        },
                        id
                    )
                }

                "live-forward" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveForwardSeekAllowed(
                        c.getValue("live").jsonPrimitive.boolean,
                        liveWindow(c["window"]),
                        c.getValue("position").jsonPrimitive.long,
                        c.getValue("expired").jsonPrimitive.boolean
                    ),
                    id
                )

                "live-reader-sequence" -> {
                    val actual = c.getValue("steps").jsonArray.map { step ->
                        val window = liveWindow(step)!!
                        kotlinx.serialization.json.JsonPrimitive(
                            liveTimelineLabel(
                                c.getValue("paused").jsonPrimitive.boolean,
                                window.canTimeShift,
                                false,
                                window.behindLiveMs(c.getValue("position").jsonPrimitive.long)
                            )
                        )
                    }
                    assertEquals(envelope.getValue("expected"), kotlinx.serialization.json.JsonArray(actual), id)
                }

                "recording-priority" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveRecordingPriorityLabel(c.getValue("value").jsonPrimitive.contentOrNull),
                    id
                )

                "recording-days" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveRecordingDaysLabel(c.getValue("days").jsonArray.map { it.jsonPrimitive.content }.toSet()),
                    id
                )

                "guide-freshness-label" -> {
                    val time = LiveTime(
                        c.getValue("twentyFour").jsonPrimitive.boolean,
                        Locale.forLanguageTag(value("locale")),
                        ZoneId.of(value("zone")),
                        Instant.parse(value("now")).toEpochMilli()
                    )
                    assertEquals(
                        envelope.getValue("expected").jsonPrimitive.contentOrNull,
                        liveGuideFreshness(
                            c["fetchedAt"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() },
                            c.getValue("offline").jsonPrimitive.boolean,
                            time
                        )?.replace('\u202f', ' ')?.replace('\u00a0', ' '),
                        id
                    )
                }

                "guide-reference-notice" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.contentOrNull,
                    LiveTvState(
                        loading = c.getValue("loading").jsonPrimitive.boolean,
                        offline = c.getValue("offline").jsonPrimitive.boolean,
                        fetchedAt = c["fetchedAt"]?.jsonPrimitive?.contentOrNull?.toLong()
                    ).guideReferenceNotice(c.getValue("now").jsonPrimitive.long),
                    id
                )

                "guide-read-status" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    LiveTvState(
                        offline = c.getValue("readFailed").jsonPrimitive.boolean,
                        error = c["error"]?.jsonPrimitive?.contentOrNull
                    ).guideUnavailable,
                    id
                )

                "guide-empty-message" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveGuideEmptyMessage(c.getValue("unavailable").jsonPrimitive.boolean),
                    id
                )

                "channel-idle-label" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveChannelIdleLabel(
                        c.getValue("hasPrograms").jsonPrimitive.boolean,
                        c["unavailable"]?.jsonPrimitive?.boolean ?: false
                    ),
                    id
                )

                "scroll-owner" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveScrollOwner(value("tab"), c.getValue("linear").jsonPrimitive.boolean),
                    id
                )

                "recording-shelf-pivot" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveRecordingScrollDistance(
                        c.getValue("offset").jsonPrimitive.float,
                        c.getValue("size").jsonPrimitive.float,
                        c.getValue("containerSize").jsonPrimitive.float,
                        c.getValue("margin").jsonPrimitive.float,
                        c.getValue("above").jsonPrimitive.float,
                        c.getValue("heading").jsonPrimitive.float,
                        c.getValue("topInset").jsonPrimitive.float,
                        c.getValue("bottomInset").jsonPrimitive.float
                    ),
                    0.001f,
                    id
                )

                "recording-shelf-tail" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveRecordingTrailingSpace(
                        c.getValue("viewportHeight").jsonPrimitive.float,
                        c.getValue("shelfHeight").jsonPrimitive.float,
                        c.getValue("topInset").jsonPrimitive.float,
                        c.getValue("contentPadding").jsonPrimitive.float
                    ),
                    0.001f,
                    id
                )

                "recording-caption-height" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveRecordingCaptionHeight(
                        c.getValue("fontScale").jsonPrimitive.float,
                        c.getValue("titleLineHeight").jsonPrimitive.float,
                        c.getValue("metadataLineHeight").jsonPrimitive.float
                    ),
                    0.001f,
                    id
                )

                "recording-metadata" -> {
                    val item = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<app.pictureshow.core.MediaItem>(c.getValue("item"))
                    val time = LiveTime(
                        c.getValue("twentyFour").jsonPrimitive.boolean,
                        Locale.forLanguageTag(value("locale")),
                        ZoneId.of(value("zone")),
                        Instant.parse(value("now")).toEpochMilli()
                    )
                    val actual = buildJsonObject {
                        put("title", liveRecordingTitle(item))
                        put("metadata", liveRecordingMetadata(item, time).replace('\u202f', ' ').replace('\u00a0', ' '))
                        put("progress", liveRecordingProgress(item))
                    }
                    assertEquals(envelope.getValue("expected"), actual, id)
                }

                "focus-shift" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideFocusShift(
                        c.getValue("x").jsonPrimitive.float,
                        c.getValue("width").jsonPrimitive.float,
                        c.getValue("viewportLeft").jsonPrimitive.float,
                        c.getValue("viewportWidth").jsonPrimitive.float
                    ),
                    0.001f,
                    id
                )

                "schedule-status" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveScheduleStatus(
                        app.pictureshow.core.livetv.LiveSchedule(c.getValue("fields").jsonObject),
                        c.getValue("series").jsonPrimitive.boolean
                    ),
                    id
                )

                "initial-focus" -> {
                    val programs = c.getValue("programs").jsonArray.map {
                        val p = it.jsonObject
                        app.pictureshow.core.livetv.LiveProgram(
                            p.getValue("id").jsonPrimitive.content,
                            startDate = Instant.ofEpochMilli(p.getValue("start").jsonPrimitive.long).toString(),
                            endDate = Instant.ofEpochMilli(p.getValue("end").jsonPrimitive.long).toString()
                        )
                    }
                    val cells = liveGuideCells(programs, c.getValue("start").jsonPrimitive.long, c.getValue("end").jsonPrimitive.long)
                    assertEquals(
                        envelope.getValue("expected").jsonPrimitive.contentOrNull,
                        liveGuideInitialFocus(cells, c.getValue("now").jsonPrimitive.long),
                        id
                    )
                }

                "window-start" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    Instant.ofEpochMilli(
                        liveGuideWindowStart(Instant.parse(value("now")).toEpochMilli(), ZoneId.of(value("zone")))
                    ).toString(),
                    id
                )

                "ruler-height" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideRulerHeight(c.getValue("tv").jsonPrimitive.boolean, c.getValue("fontScale").jsonPrimitive.float),
                    0.001f,
                    id
                )

                "lane-height" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideLaneHeight(c.getValue("tv").jsonPrimitive.boolean, c.getValue("fontScale").jsonPrimitive.float),
                    0.001f,
                    id
                )

                "recording-dot" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    app.pictureshow.core.livetv.LiveProgram(
                        "p",
                        timerId = c["timerId"]?.jsonPrimitive?.contentOrNull,
                        seriesTimerId = c["seriesTimerId"]?.jsonPrimitive?.contentOrNull
                    ).hasRecordingTimer(),
                    id
                )

                "tick" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideTickX(c.getValue("tick").jsonPrimitive.int),
                    id
                )

                "text" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveGuideShowsText(c.getValue("width").jsonPrimitive.float),
                    id
                )

                else -> {
                    val time =
                        LiveTime(
                            c.getValue("twentyFour").jsonPrimitive.boolean,
                            Locale.forLanguageTag(value("locale")),
                            ZoneId.of(value("zone")),
                            Instant.parse(value("now")).toEpochMilli()
                        )
                    val actual = if (envelope.getValue("operation").jsonPrimitive.content ==
                        "clock"
                    ) {
                        time.clock(value("instant"))
                    } else {
                        time.range(value("start"), value("end"))
                    }
                    assertEquals(
                        envelope.getValue("expected").jsonPrimitive.content,
                        actual.replace('\u202f', ' ').replace('\u00a0', ' '),
                        id
                    )
                }
            }
        }
    }
}
