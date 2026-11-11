/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveGuideCache
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.LiveTvService
import app.pictureshow.core.livetv.LiveWrite
import app.pictureshow.core.livetv.guidePrograms
import app.pictureshow.core.livetv.sortChannels
import app.pictureshow.core.livetv.timerDraft
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LiveTvState(
    val loading: Boolean = true,
    val writing: Boolean = false,
    val policy: LivePolicy? = null,
    val tunerEnabled: Boolean? = null,
    val channels: List<LiveChannel> = emptyList(),
    val programs: List<LiveProgram> = emptyList(),
    val recordings: List<MediaItem> = emptyList(),
    val timers: List<LiveSchedule> = emptyList(),
    val series: List<LiveSchedule> = emptyList(),
    val windowStart: Long = System.currentTimeMillis(),
    val fetchedAt: Long? = null,
    val offline: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val draft: LiveSchedule? = null,
    val draftSeries: Boolean = false,
    val writeVersion: Long = 0,
    val lastWriteOutcome: String? = null
) {
    // Refresh owns this flag; mutation errors do not invalidate a successfully loaded guide.
    val guideUnavailable: Boolean get() = offline
}

/** Navigation-entry scoped, with no global cache: account changes cannot reuse another viewer's guide. */
internal class LiveTvViewModel(
    private val account: Account,
    private val api: LiveTvService,
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val mutable = MutableStateFlow(LiveTvState(windowStart = liveGuideWindowStart(now())))
    val state = mutable.asStateFlow()
    private var load: Job? = null
    private var generation = 0L

    fun refresh(start: Long = mutable.value.windowStart) {
        load?.cancel()
        val version = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null, windowStart = start)
        load = viewModelScope.launch {
            try {
                val policy = api.policy(account).required()
                if (!policy.access) {
                    mutable.value = LiveTvState(
                        loading = false,
                        policy = policy,
                        windowStart = start,
                        error = "Live TV access is disabled for this viewer. Ask your server administrator."
                    )
                    return@launch
                }
                mutable.value = mutable.value.copy(
                    policy = policy,
                    timers = if (policy.manage) mutable.value.timers else emptyList(),
                    series = if (policy.manage) mutable.value.series else emptyList()
                )
                val info = api.info(account).required()
                val channels = mutableListOf<LiveChannel>()
                var offset = 0
                do {
                    val page = api.channels(account, startIndex = offset).required()
                    channels += page.items
                    offset += page.items.size
                } while (page.items.isNotEmpty() && offset < page.totalRecordCount)
                val allowed = channels.map { it.id }.toSet()
                // Purge denied/removed channels before the next request, even if guide fetching fails.
                mutable.value =
                    mutable.value.copy(
                        policy = policy,
                        tunerEnabled = info.enabled,
                        channels = sortChannels(channels.distinctBy { it.id }),
                        programs = mutable.value.programs.filter { it.channelId in allowed }
                    )
                val programs = mutableListOf<LiveProgram>()
                allowed.toList().chunked(100).forEach { channelBatch ->
                    offset = 0
                    do {
                        val page = api.programs(
                            account,
                            channelBatch,
                            Instant.ofEpochMilli(start).toString(),
                            Instant.ofEpochMilli(start + WINDOW).toString(),
                            startIndex = offset
                        ).required()
                        programs += page.items
                        offset += page.items.size
                    } while (page.items.isNotEmpty() && offset < page.totalRecordCount)
                }
                val recordings = mutableListOf<MediaItem>()
                offset = 0
                do {
                    val page = api.recordings(account, startIndex = offset).required()
                    recordings += page.items
                    offset += page.items.size
                } while (page.items.isNotEmpty() && offset < page.totalRecordCount)
                val timers = if (policy.manage) api.timers(account).required() else emptyList()
                val series = if (policy.manage) api.timers(account, series = true).required() else emptyList()
                if (generation == version) {
                    mutable.value = mutable.value.copy(
                        loading = false,
                        programs = guidePrograms(
                            programs,
                            allowed,
                            Instant.ofEpochMilli(start).toString(),
                            Instant.ofEpochMilli(start + WINDOW).toString()
                        ),
                        recordings = recordings.distinctBy { it.id },
                        timers = timers,
                        series = series,
                        fetchedAt = now(),
                        offline = false,
                        error = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LiveReadFailure) {
                if (generation == version) {
                    mutable.value =
                        if (failure.status in
                            listOf(401, 403)
                        ) {
                            LiveTvState(loading = false, policy = LivePolicy(), windowStart = start, error = failure.message)
                        } else {
                            mutable.value.copy(loading = false, offline = true, error = failure.message)
                        }
                }
            }
        }
    }

    fun defaults(program: LiveProgram, series: Boolean) = viewModelScope.launch {
        if (mutable.value.writing) return@launch
        mutable.value = mutable.value.copy(writing = true, error = null)
        try {
            val policy = api.policy(account).required()
            if (!policy.access || !policy.manage) throw LiveReadFailure("Recording management is disabled for this viewer.")
            val fresh = api.program(account, program.id).required()
            val defaults = api.defaults(account, program.id).required()
            mutable.value = mutable.value.copy(
                policy = policy,
                draft = runCatching { timerDraft(defaults, fresh, series) }.getOrElse {
                    throw LiveReadFailure("This program no longer has valid schedule dates. Refresh Live TV.")
                },
                draftSeries = series
            )
        } catch (failure: LiveReadFailure) {
            mutable.value = mutable.value.copy(error = failure.message)
        } finally {
            mutable.value = mutable.value.copy(writing = false)
        }
    }

    fun dismissDraft() {
        mutable.value = mutable.value.copy(draft = null)
    }

    /** Every write rechecks policy at the server; a failed/ambiguous create is never silently retried. */
    fun save(draft: LiveSchedule, series: Boolean, existing: Boolean) = write {
        if (existing) api.updateTimer(account, draft, series) else api.createTimer(account, draft, series)
    }

    fun cancel(schedule: LiveSchedule, series: Boolean) = write { api.cancelTimer(account, schedule.id, series) }
    fun delete(item: MediaItem) = write(deleting = true, itemDeleteAllowed = item.canDelete) { api.deleteRecording(account, item.id) }

    private fun write(deleting: Boolean = false, itemDeleteAllowed: Boolean? = null, operation: suspend () -> LiveWrite) =
        viewModelScope.launch {
            if (mutable.value.writing) return@launch
            mutable.value = mutable.value.copy(writing = true, error = null, notice = null)
            try {
                val policy = api.policy(account).required()
                if (!policy.access ||
                    !(if (deleting) itemDeleteAllowed == true || policy.deleteContent && itemDeleteAllowed != false else policy.manage)
                ) {
                    throw LiveReadFailure("This viewer does not have permission for this recording change.")
                }
                val result = operation()
                mutable.value =
                    mutable.value.copy(
                        notice = result.message,
                        draft = if (result.outcome == "ok") null else mutable.value.draft,
                        writeVersion = mutable.value.writeVersion + 1,
                        lastWriteOutcome = result.outcome
                    )
                refresh()
            } catch (failure: LiveReadFailure) {
                mutable.value = mutable.value.copy(error = failure.message)
            } finally {
                mutable.value = mutable.value.copy(writing = false)
            }
        }

    fun favorite(channel: LiveChannel) = viewModelScope.launch {
        if (mutable.value.writing) return@launch
        mutable.value = mutable.value.copy(writing = true, error = null)
        try {
            val policy = api.policy(account).required()
            if (!policy.userData) throw LiveReadFailure("Favorites are disabled for this viewer.")
            api.favorite(account, channel.id, !channel.userData.isFavorite).required()
            refresh()
        } catch (failure: LiveReadFailure) {
            mutable.value = mutable.value.copy(error = failure.message)
        } finally {
            mutable.value = mutable.value.copy(writing = false)
        }
    }

    companion object {
        const val WINDOW = 3 * 60 * 60 * 1000L
    }
}

private class LiveReadFailure(message: String, val status: Int? = null) : Exception(message)
private fun <T> ClientResult<T>.required(): T = when (this) {
    is ClientResult.Success -> value
    is ClientResult.Failure -> throw LiveReadFailure(error.message, error.status)
}

/** Cached data stays readable while a refresh is pending, but only a current authoritative snapshot can act. */
internal fun LiveTvState.actionsAvailable(now: Long): Boolean = LiveGuideCache(
    accountKey = "",
    generation = 0,
    channelIds = emptySet(),
    fetchedAtMillis = fetchedAt,
    offline = offline,
    refreshing = loading
).snapshotFresh(now)

/** Reference descriptions remain readable while an explicit refresh recovers stale actions. */
internal fun LiveTvState.guideReferenceNotice(now: Long): String? = when {
    offline -> "Saved guide is for reference. Reconnect before scheduling or tuning."
    loading || fetchedAt == null -> null
    now < fetchedAt -> "Guide freshness is unavailable. Refresh before scheduling or tuning."
    !actionsAvailable(now) -> "Guide is out of date. Refresh before scheduling or tuning."
    else -> null
}
