/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.MediaItem
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
        assertEquals(366, cases.size)
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
                "channel-monogram" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveChannelMonogramVisible(value("imageState") == "success"),
                    id
                )
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
                "touch-live-slider" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    touchLiveSliderAvailable(liveWindow(c["window"]), c.getValue("expired").jsonPrimitive.boolean),
                    id
                )
                "live-seek-presentation" -> {
                    val expected = envelope.getValue("expected").jsonObject
                    assertEquals(expected.getValue("glyphDp").jsonPrimitive.int, LIVE_SEEK_MARK_DP, id)
                    assertEquals(expected.getValue("disabledAlpha").jsonPrimitive.float, LIVE_SEEK_DISABLED_ALPHA, .001f, id)
                    assertEquals(expected.getValue("scrimAlpha").jsonPrimitive.float, LIVE_SEEK_SCRIM_ALPHA, .001f, id)
                }
                "tv-review-surface" -> {
                    val expected = envelope.getValue("expected").jsonObject
                    assertEquals(expected.getValue("minimumRowHeightDp").jsonPrimitive.int, LIVE_REVIEW_SUMMARY_MIN_HEIGHT_DP, id)
                    assertEquals(expected.getValue("disabledFocusBorderDp").jsonPrimitive.int, LIVE_DISABLED_FOCUS_BORDER_DP, id)
                    // LiveTvReviewClosureTest also runs these font/wrapping/contrast cases against the real native surfaces.
                }
                "review-header-navigation" -> {
                    // LiveTvReviewNavigationTest drives these exact Left/Right events under Foundation's real TV pivot.
                    assertEquals(
                        envelope.getValue("expected").jsonObject.getValue("fullHeadingVisible").jsonPrimitive.boolean,
                        liveEditorShouldRevealHeader(c.getValue("tv").jsonPrimitive.boolean, true),
                        id
                    )
                }
                "review-header-reveal" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveEditorShouldRevealHeader(
                        c.getValue("tv").jsonPrimitive.boolean,
                        c.getValue("firstActionRowFocused").jsonPrimitive.boolean
                    ),
                    id
                )
                "program-recording-time-state" -> {
                    val expected = envelope.getValue("expected").jsonObject
                    val end = c["endDate"]?.jsonPrimitive?.contentOrNull
                    val now = c.getValue("now").jsonPrimitive.long
                    assertEquals(expected.getValue("ended").jsonPrimitive.boolean, liveProgramEnded(end, now), id)
                    assertEquals(expected.getValue("creationAvailable").jsonPrimitive.boolean, liveProgramCreationAvailable(end, now), id)
                }
                "quality-overflow-affordance" -> {
                    val expected = envelope.getValue("expected").jsonObject
                    val cues = qualityScrollCues(c.getValue("offsetDp").jsonPrimitive.float, c.getValue("remainingDp").jsonPrimitive.float)
                    assertEquals(expected.getValue("above").jsonPrimitive.boolean, cues.first, id)
                    assertEquals(expected.getValue("below").jsonPrimitive.boolean, cues.second, id)
                    assertEquals(expected.getValue("dividerDp").jsonPrimitive.int, QUALITY_SCROLL_DIVIDER_DP, id)
                }
                "quality-scroll-cues" -> assertEquals(
                    envelope.getValue("expected").jsonArray.map { it.jsonPrimitive.boolean },
                    qualityScrollCues(c.getValue("offsetDp").jsonPrimitive.float, c.getValue("remainingDp").jsonPrimitive.float)
                        .let { listOf(it.first, it.second) },
                    id
                )
                "program-metadata-lines" -> assertEquals(
                    envelope.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
                    liveProgramMetadataLines(
                        c["channel"]?.jsonPrimitive?.contentOrNull,
                        c.getValue("range").jsonPrimitive.content,
                        c["episode"]?.jsonPrimitive?.contentOrNull,
                        c["title"]?.jsonPrimitive?.contentOrNull,
                        c.getValue("tv").jsonPrimitive.boolean
                    ),
                    id
                )
                "recording-label-body" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.int,
                    RECORDING_DETAIL_LABEL_MIN_HEIGHT_DP,
                    id
                )
                "review-summary-focus" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveReviewSummaryFocusable(c.getValue("tv").jsonPrimitive.boolean),
                    id
                )
                "recording-mutation-slot" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.int,
                    RECORDING_DETAIL_MUTATION_SIZE_DP,
                    id
                )
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

                "empty-state" -> {
                    fun number(key: String, fallback: Int = 0) = c[key]?.jsonPrimitive?.int ?: fallback
                    val channels = number("channels")
                    val state = LiveTvState(
                        loading = c["loading"]?.jsonPrimitive?.boolean ?: false,
                        policy = if (c["policyKnown"]?.jsonPrimitive?.boolean == false) {
                            null
                        } else {
                            app.pictureshow.core.livetv.LivePolicy(
                                c["access"]?.jsonPrimitive?.boolean ?: true,
                                c["manage"]?.jsonPrimitive?.boolean ?: true,
                                true,
                                true
                            )
                        },
                        tunerEnabled = c["tuner"]?.jsonPrimitive?.boolean,
                        offline = c["offline"]?.jsonPrimitive?.boolean ?: false,
                        channels = List(channels) { app.pictureshow.core.livetv.LiveChannel("c$it", "Channel $it", "$it") },
                        programs = List(number("programs")) { app.pictureshow.core.livetv.LiveProgram("p$it", "Programme", "c0") }
                    )
                    val result = liveEmptyPresentation(
                        state,
                        c["tab"]?.jsonPrimitive?.content ?: "Channels",
                        number("filtered", channels),
                        c["favorites"]?.jsonPrimitive?.boolean ?: false,
                        c["search"]?.jsonPrimitive?.content ?: ""
                    )
                    val expected = envelope.getValue("expected")
                    assertEquals(
                        if (expected ==
                            kotlinx.serialization.json.JsonNull
                        ) {
                            null
                        } else {
                            expected.jsonArray.map { it.jsonPrimitive.content }
                        },
                        result?.let {
                            listOf(it.title, it.action.label) +
                                (if (c["includeAction"]?.jsonPrimitive?.boolean == true) listOf(it.action.name) else emptyList()) +
                                (if (c["includeBody"]?.jsonPrimitive?.boolean == true) listOf(it.body) else emptyList())
                        },
                        id
                    )
                }
                "program-episode" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.contentOrNull,
                    liveProgramEpisodeLabel(
                        app.pictureshow.core.livetv.LiveProgram(
                            "p",
                            "Programme",
                            "c",
                            seasonNumber = c["season"]?.jsonPrimitive?.contentOrNull?.toInt(),
                            episodeNumber = c["episode"]?.jsonPrimitive?.contentOrNull?.toInt()
                        )
                    ),
                    id
                )
                "program-relative-time" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveProgramTimeLabel(
                        value("range"),
                        c["start"]?.jsonPrimitive?.contentOrNull,
                        c["end"]?.jsonPrimitive?.contentOrNull,
                        Instant.parse(value("now")).toEpochMilli()
                    ),
                    id
                )
                "schedule-review-summary" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveScheduleReviewSummary(
                        app.pictureshow.core.livetv.LiveSchedule(c.getValue("fields").jsonObject),
                        c.getValue("series").jsonPrimitive.boolean,
                        c.getValue("existing").jsonPrimitive.boolean,
                        LiveTime(true, Locale.US, ZoneId.of("UTC"), Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()),
                        touch = c["touch"]?.jsonPrimitive?.boolean ?: false
                    ),
                    id
                )
                "guide-channel-column" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideChannelWidth(
                        c.getValue("tv").jsonPrimitive.boolean,
                        c.getValue("fontScale").jsonPrimitive.float,
                        c.getValue("windowDp").jsonPrimitive.int
                    ),
                    .001f,
                    id
                )
                "schedule-order" -> assertEquals(
                    envelope.getValue("expected").jsonArray.map {
                        it.jsonPrimitive.content
                    },
                    liveScheduleGroups(emptyList(), emptyList(), 0).map { it.title },
                    id
                )
                "touch-parent" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.contentOrNull,
                    touchTabFor(c["route"]?.jsonPrimitive?.contentOrNull),
                    id
                )
                "library-cards" -> assertEquals(
                    envelope.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
                    listOf("destination:live-tv") + liveOrdinaryLibraries(
                        c.getValue("items").jsonArray.map { Json.decodeFromJsonElement<MediaItem>(it) }
                    ).map { it.id },
                    id
                )
                "library-destination" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    if (MediaItem(
                            "view",
                            value("name"),
                            value("type"),
                            collectionType = c["collectionType"]?.jsonPrimitive?.contentOrNull
                        ).isLiveTvLibrary()
                    ) {
                        "live-tv"
                    } else {
                        "catalog"
                    },
                    id
                )
                "visible-error" -> assertEquals(envelope.getValue("expected").jsonPrimitive.content, liveVisibleError(value("message")), id)
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

                "guide-now-enabled" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveGuideNowEnabled(
                        c.getValue("tv").jsonPrimitive.boolean,
                        c.getValue("enabled").jsonPrimitive.boolean,
                        c.getValue("atNow").jsonPrimitive.boolean
                    ),
                    id
                )

                "guide-now-focus" -> {
                    val channels = c.getValue("channels").jsonArray.map {
                        app.pictureshow.core.livetv.LiveChannel(it.jsonPrimitive.content)
                    }
                    val programs = c.getValue("programs").jsonArray.map {
                        val p = it.jsonObject
                        app.pictureshow.core.livetv.LiveProgram(
                            p.getValue("id").jsonPrimitive.content,
                            channelId = p.getValue("channel").jsonPrimitive.content,
                            startDate = Instant.ofEpochMilli(p.getValue("start").jsonPrimitive.long).toString(),
                            endDate = Instant.ofEpochMilli(p.getValue("end").jsonPrimitive.long).toString()
                        )
                    }
                    assertEquals(
                        envelope.getValue("expected").jsonPrimitive.contentOrNull,
                        liveGuideNowFocus(
                            channels,
                            programs,
                            c["selectedKey"]?.jsonPrimitive?.contentOrNull,
                            c.getValue("start").jsonPrimitive.long,
                            c.getValue("now").jsonPrimitive.long
                        ),
                        id
                    )
                }

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
                    liveGuideRulerHeight(
                        c.getValue("tv").jsonPrimitive.boolean,
                        c.getValue("fontScale").jsonPrimitive.float,
                        c["compact"]?.jsonPrimitive?.boolean ?: false
                    ),
                    0.001f,
                    id
                )

                "live-pause-focus" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    livePauseRequestsControlFocus(
                        c.getValue("live").jsonPrimitive.boolean,
                        c.getValue("surfaceFocused").jsonPrimitive.boolean
                    ),
                    id
                )
                "live-buffer-fill" -> {
                    val actual =
                        liveTrackPositionFraction(
                            liveWindow(c["window"]),
                            c.getValue("position").jsonPrimitive.long,
                            c.getValue("expired").jsonPrimitive.boolean
                        )
                    val expected = envelope.getValue("expected")
                    if (expected == kotlinx.serialization.json.JsonNull) {
                        assertEquals(null, actual, id)
                    } else {
                        assertEquals(expected.jsonPrimitive.float, actual!!, 0.0001f, id)
                    }
                }
                "live-buffer-thumb" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveBufferSliderPosition(
                        liveWindow(c["window"])!!,
                        c.getValue("position").jsonPrimitive.long,
                        c.getValue("expired").jsonPrimitive.boolean
                    ),
                    0.001f,
                    id
                )
                "live-buffer-scrub-end" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.long,
                    liveBufferScrubEnd(liveWindow(c["window"])!!),
                    id
                )
                "live-buffer-endpoints" -> {
                    val actual =
                        liveBufferEndpointLabels(
                            liveWindow(c["window"]),
                            c.getValue("position").jsonPrimitive.long,
                            c.getValue("expired").jsonPrimitive.boolean
                        )
                    assertEquals(
                        envelope.getValue("expected").jsonArray.map {
                            it.jsonPrimitive.content
                        },
                        listOf(actual.first, actual.second),
                        id
                    )
                }
                "live-buffer-status" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveTimelineVisualStatus(
                        LiveTimelineReadout(value("status"), value("clock")),
                        c["behind"]?.takeUnless {
                            it ==
                                kotlinx.serialization.json.JsonNull
                        }?.jsonPrimitive?.long
                    ),
                    id
                )
                "guide-ruler-label-fit" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveGuideRulerLabelFits(
                        c.getValue("x").jsonPrimitive.float,
                        c.getValue("width").jsonPrimitive.float,
                        c.getValue("viewportStart").jsonPrimitive.float,
                        c.getValue("viewportWidth").jsonPrimitive.float
                    ),
                    id
                )
                "guide-viewport-focus" -> {
                    val cells = c.getValue("cells").jsonArray.map { element ->
                        val item = element.jsonObject
                        LiveGuideCell(
                            app.pictureshow.core.livetv.LiveProgram(item.getValue("id").jsonPrimitive.content),
                            item.getValue("x").jsonPrimitive.float / LIVE_GUIDE_WIDTH,
                            item.getValue("width").jsonPrimitive.float / LIVE_GUIDE_WIDTH,
                            item.getValue("lane").jsonPrimitive.int
                        )
                    }
                    assertEquals(
                        envelope.getValue("expected").jsonPrimitive.contentOrNull,
                        liveGuideViewportFocus(
                            cells,
                            c["current"]?.jsonPrimitive?.contentOrNull,
                            c.getValue("viewportStart").jsonPrimitive.float,
                            c.getValue("viewportWidth").jsonPrimitive.float
                        ),
                        id
                    )
                }
                "schedule-effective-changes" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveScheduleHasChanges(
                        app.pictureshow.core.livetv.LiveSchedule(c.getValue("fields").jsonObject),
                        c.getValue("edits").jsonObject
                    ),
                    id
                )
                "schedule-review-label" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveScheduleReviewLabel(c.getValue("existing").jsonPrimitive.boolean, c.getValue("changed").jsonPrimitive.boolean),
                    id
                )
                "schedule-confirm-guard" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveScheduleCanConfirm(
                        c.getValue("existing").jsonPrimitive.boolean,
                        c.getValue("changed").jsonPrimitive.boolean,
                        c.getValue("busy").jsonPrimitive.boolean,
                        c["canSubmit"]?.jsonPrimitive?.boolean ?: true
                    ),
                    id
                )
                "program-recording-label" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.content,
                    liveProgramRecordingLabel(value("status")),
                    id
                )

                "program-record-entry-focus" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveProgramRecordInitialFocus(c.getValue("tv").jsonPrimitive.boolean, c.getValue("primary").jsonPrimitive.boolean),
                    id
                )

                "program-content-inset" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.int,
                    liveProgramContentInset(c.getValue("tv").jsonPrimitive.boolean),
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
