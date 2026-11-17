/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.livetv

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

fun liveInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/** Exact half-open overlap. UTC parsing also handles the two distinct occurrences of a DST repeated hour. */
fun guidePrograms(programs: List<LiveProgram>, allowedChannels: Set<String>, startUtc: String, endUtc: String): List<LiveProgram> {
    val start = requireNotNull(liveInstant(startUtc)) { "Guide start must be a UTC instant." }
    val end = requireNotNull(liveInstant(endUtc)) { "Guide end must be a UTC instant." }
    require(end > start && end - start <= 86_400_000) { "Guide windows must be positive and at most 24 hours." }
    return programs.filter {
        val from = liveInstant(it.startDate)
        val to = liveInstant(it.endDate)
        it.channelId in allowedChannels && from != null && to != null && to > from && to > start && from < end
    }.distinctBy { it.id }.sortedWith(compareBy({ liveInstant(it.startDate) }, { it.channelId }, { it.id }))
}

fun canTune(program: LiveProgram, nowEpochMillis: Long): Boolean {
    val start = liveInstant(program.startDate) ?: return false
    val end = liveInstant(program.endDate) ?: return false
    return start <= nowEpochMillis && nowEpochMillis < end
}

private fun compareChannelNumbers(left: String?, right: String?): Int {
    val a = left?.trim().orEmpty()
    val b = right?.trim().orEmpty()
    if (a.isEmpty() || b.isEmpty()) {
        return when {
            a == b -> 0
            a.isEmpty() -> 1
            else -> -1
        }
    }
    val pattern = Regex("[0-9]+|[^0-9]+")
    val aa = pattern.findAll(a).map { it.value }.toList()
    val bb = pattern.findAll(b).map { it.value }.toList()
    aa.zip(bb).forEach { (x, y) ->
        val numeric = x.first().isDigit() && y.first().isDigit()
        val xx = if (numeric) x.trimStart('0').ifEmpty { "0" } else x.lowercase()
        val yy = if (numeric) y.trimStart('0').ifEmpty { "0" } else y.lowercase()
        val order = if (numeric && xx.length != yy.length) xx.length.compareTo(yy.length) else xx.compareTo(yy)
        if (order != 0) return order
    }
    return aa.size.compareTo(bb.size)
}

/** Number components are natural, never floating point (2.10 follows 2.9). Names/IDs break ties deterministically. */
fun sortChannels(channels: List<LiveChannel>): List<LiveChannel> = channels.distinctBy { it.id }.sortedWith { a, b ->
    compareChannelNumbers(a.number, b.number).takeIf { it != 0 }
        ?: a.name.lowercase().compareTo(b.name.lowercase()).takeIf { it != 0 }
        ?: a.id.compareTo(b.id)
}

private val timerFields = setOf(
    "ChannelId", "ProgramId", "Name", "Overview", "StartDate", "EndDate", "PrePaddingSeconds", "PostPaddingSeconds",
    "IsPrePaddingRequired", "IsPostPaddingRequired", "Priority", "ServiceName", "ExternalChannelId", "ExternalId",
    "ExternalProgramId", "KeepUntil", "ChannelName"
)
private val WEEKDAYS = setOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val timerEdits = setOf("PrePaddingSeconds", "PostPaddingSeconds", "Priority")
private val seriesEdits = timerEdits + setOf(
    "RecordAnyTime",
    "RecordAnyChannel",
    "RecordNewOnly",
    "Days",
    "KeepUpTo",
    "SkipEpisodesInLibrary",
    "KeepUntil"
)

/**
 * Defaults are a SeriesTimerInfoDto. One-off projects only TimerInfoDto's common writable fields.
 *
 * The airing's identity and times come from the freshly read program. Jellyfin's TimerInfoDto declares StartDate and
 * EndDate as non-nullable DateTime, so a draft without both, or with a time that doesn't parse as an instant, or one
 * that ends before it starts, could only be refused by the server. There is no draft then (null), and the caller tells
 * the viewer to refresh rather than sending a body the server must reject.
 */
fun timerDraft(defaults: LiveSchedule, program: LiveProgram, series: Boolean): LiveSchedule? {
    val start = program.startDate?.takeIf { liveInstant(it) != null } ?: return null
    val end = program.endDate?.takeIf { liveInstant(it) != null } ?: return null
    if (requireNotNull(liveInstant(end)) <= requireNotNull(liveInstant(start))) return null
    return LiveSchedule(
        JsonObject(
            defaults.fields.filterKeys { if (series) it !in setOf("Id", "SeriesTimerId", "Status") else it in timerFields } +
                mapOf(
                    "ProgramId" to JsonPrimitive(program.id),
                    "ChannelId" to JsonPrimitive(program.channelId),
                    "Name" to JsonPrimitive(program.name),
                    "StartDate" to JsonPrimitive(start),
                    "EndDate" to JsonPrimitive(end)
                )
        )
    )
}

/** Only explicit dirty fields change; missing provider capabilities are not invented by an editor. */
fun editSchedule(original: LiveSchedule, edits: JsonObject, series: Boolean): LiveSchedule {
    val allowed = if (series) seriesEdits else timerEdits
    require(edits.keys.all { it in allowed && it in original.fields }) { "The provider does not support one of these fields." }
    edits.forEach { (key, value) ->
        if (key in timerEdits || key == "KeepUpTo") {
            val number = (value as? JsonPrimitive)?.content?.toIntOrNull()
            require(number != null && (key == "Priority" || number >= 0)) { "Padding and retention must be nonnegative integers." }
        }
        if (key in setOf("RecordAnyTime", "RecordAnyChannel", "RecordNewOnly", "SkipEpisodesInLibrary")) {
            require((value as? JsonPrimitive)?.booleanOrNull != null) { "Recording options must be booleans." }
        }
        if (key == "Days") {
            val days = value as? JsonArray
            val names = days?.map { (it as? JsonPrimitive)?.content }
            require(names != null && names.isNotEmpty() && names.distinct().size == names.size && names.all { it in WEEKDAYS }) {
                "Select at least one valid day without duplicates."
            }
        }
        if (key == "KeepUntil") {
            require((value as? JsonPrimitive)?.content in setOf("UntilDeleted", "UntilSpaceNeeded", "UntilWatched", "UntilDate")) {
                "Unknown retention option."
            }
        }
    }
    return LiveSchedule(JsonObject(original.fields + edits), original.dirtyFields + edits.keys)
}

fun recordingStatus(status: String): String = when (status) {
    "New" -> "Scheduled"
    "InProgress" -> "Recording"
    "Completed" -> "Completed"
    "Cancelled" -> "Cancelled"
    "ConflictedOk" -> "Overlaps"
    "ConflictedNotOk" -> "Won’t record"
    "Error" -> "Failed"
    else -> "Unknown status ($status)"
}

/**
 * LIVE-PD03 (TV review T-15): the TV guide header keeps "Live TV" as its title and names the focused programme under it,
 * led by where and when it is: "Now on Test A: News", "Later on Test A: News", "Earlier on Test A: News". Without a
 * channel name the lead is dropped; unparseable times read as "On".
 */
fun liveGuidePreviewLine(program: LiveProgram, channelName: String?, nowEpochMillis: Long): String {
    val start = liveInstant(program.startDate)
    val end = liveInstant(program.endDate)
    val lead = when {
        start == null || end == null -> "On"
        nowEpochMillis < start -> "Later on"
        nowEpochMillis >= end -> "Earlier on"
        else -> "Now on"
    }
    return channelName?.takeIf(String::isNotBlank)?.let { "$lead $it: ${program.name}" } ?: program.name
}

/** LIVE-11: one minute before a guide snapshot stops being fresh. */
const val LIVE_GUIDE_QUIET_REFRESH_MS = 240_000L

/** Cache ownership and a request generation prevent late pages and account switches from revealing another viewer's guide. */
data class LiveGuideCache(
    val accountKey: String,
    val generation: Long,
    val channelIds: Set<String>,
    val programs: List<LiveProgram> = emptyList(),
    val fetchedAtMillis: Long? = null,
    val offline: Boolean = false,
    val refreshing: Boolean = false
) {
    fun page(account: String, requestGeneration: Long, incoming: List<LiveProgram>, fetchedAt: Long): LiveGuideCache =
        if (account != accountKey || requestGeneration != generation) {
            this
        } else {
            copy(
                programs = (programs + incoming).filter { it.channelId in channelIds }.distinctBy { it.id },
                fetchedAtMillis = fetchedAt,
                offline = false
            )
        }

    fun snapshotFresh(now: Long): Boolean = !offline &&
        !refreshing &&
        fetchedAtMillis != null &&
        now >= fetchedAtMillis &&
        now - fetchedAtMillis <= 300_000

    /**
     * LIVE-11 (TV review T-5): a guide on screen refreshes itself quietly once it is [LIVE_GUIDE_QUIET_REFRESH_MS] old,
     * before it turns stale at five minutes, so a viewer who leaves the guide up never meets "out of date". Not while a
     * refresh is running, offline (that needs the viewer's Retry), or with a clock that runs backwards.
     */
    fun quietRefreshDue(now: Long): Boolean = !offline &&
        !refreshing &&
        fetchedAtMillis != null &&
        now >= fetchedAtMillis &&
        now - fetchedAtMillis >= LIVE_GUIDE_QUIET_REFRESH_MS

    fun actionAllowed(program: LiveProgram, now: Long): Boolean = snapshotFresh(now) &&
        program.channelId in channelIds &&
        (liveInstant(program.endDate)?.let { it > now } == true)
}

/** Semantic presentation follows server facts, independently of localized wording. */
enum class RecordingTone { Neutral, Live, Error, Muted }

fun recordingTone(status: String?): RecordingTone = when (status) {
    "InProgress" -> RecordingTone.Live
    "Error", "ConflictedNotOk" -> RecordingTone.Error
    "Cancelled" -> RecordingTone.Muted
    else -> RecordingTone.Neutral
}
