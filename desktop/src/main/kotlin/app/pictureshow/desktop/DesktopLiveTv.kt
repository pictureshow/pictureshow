/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.livetv.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class DesktopLiveTab { Channels, Guide, Recordings, Schedules }

data class DesktopLiveState(
    val loading: Boolean = true,
    val writing: Boolean = false,
    val policy: LivePolicy? = null,
    val tunerEnabled: Boolean? = null,
    val channels: List<LiveChannel> = emptyList(),
    val programs: List<LiveProgram> = emptyList(),
    val recordings: List<MediaItem> = emptyList(),
    val timers: List<LiveSchedule> = emptyList(),
    val series: List<LiveSchedule> = emptyList(),
    val start: Long = desktopGuideStart(System.currentTimeMillis()),
    val fetchedAt: Long? = null,
    val offline: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val tab: DesktopLiveTab = DesktopLiveTab.Channels,
    val search: String = "",
    val favorites: Boolean = false,
    val selected: LiveProgram? = null,
    val focusKey: String? = null,
    val draft: LiveSchedule? = null,
    val draftSeries: Boolean = false,
    val draftExisting: Boolean = false
) {
    fun actionsAvailable(now: Long): Boolean = policy?.access == true && LiveGuideCache(
        "", 0, channels.map { it.id }.toSet(), fetchedAtMillis = fetchedAt, offline = offline, refreshing = loading
    ).snapshotFresh(now)
}

/** Platform lifecycle and I/O only. Every entitlement, schedule write and guide rule uses the common core. */
class DesktopLiveTv internal constructor(
    private val api: LiveTvService,
    private val scope: CoroutineScope,
    private val currentAccount: () -> Account?,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutable = MutableStateFlow(DesktopLiveState())
    val state = mutable.asStateFlow()
    private var owner: Account? = null
    private var load: Job? = null
    private var mutation: Job? = null
    private var generation = 0L

    @Synchronized fun open(account: Account) {
        if (owner?.key != account.key || owner?.accessToken != account.accessToken) {
            clear()
            owner = account
        }
        refresh()
    }
    @Synchronized fun clear() {
        generation++
        load?.cancel(); mutation?.cancel()
        owner = null
        mutable.value = DesktopLiveState()
    }
    private fun owns(account: Account) = owner?.key == account.key && owner?.accessToken == account.accessToken &&
        currentAccount()?.let { it.key == account.key && it.accessToken == account.accessToken } == true
    private fun update(account: Account, change: (DesktopLiveState) -> DesktopLiveState) {
        if (owns(account)) mutable.update(change)
    }
    fun tab(tab: DesktopLiveTab) { mutable.update { it.copy(tab = tab, selected = null) } }
    fun search(value: String) { mutable.update { it.copy(search = value) } }
    fun favorites() { mutable.update { it.copy(favorites = !it.favorites) } }
    fun clearFilters() { mutable.update { it.copy(search = "", favorites = false) } }
    fun select(program: LiveProgram, key: String) { mutable.update { it.copy(selected = program, focusKey = key) } }
    fun dismissProgram() { mutable.update { it.copy(selected = null) } }
    fun dismissDraft() { if (!mutable.value.writing) mutable.update { it.copy(draft = null) } }
    fun moveWindow(start: Long) { refresh(start) }
    fun jumpNow() { refresh(desktopGuideStart(now())) }

    @Synchronized fun refresh(start: Long = mutable.value.start) {
        val a = owner ?: return
        if (!owns(a)) return
        load?.cancel()
        val version = ++generation
        update(a) { it.copy(loading = true, offline = false, error = null, start = start) }
        load = scope.launch {
            try {
                val policy = api.policy(a).required()
                if (!policy.access) {
                    update(a) { DesktopLiveState(loading = false, policy = policy, start = start) }
                    return@launch
                }
                update(a) { it.copy(policy = policy, timers = if (policy.manage) it.timers else emptyList(), series = if (policy.manage) it.series else emptyList()) }
                val info = api.info(a).required()
                val channels = mutableListOf<LiveChannel>()
                var offset = 0
                do {
                    val page = api.channels(a, startIndex = offset).required()
                    channels += page.items; offset += page.items.size
                } while (page.items.isNotEmpty() && offset < page.totalRecordCount)
                val allowed = channels.map { it.id }.toSet()
                update(a) { it.copy(tunerEnabled = info.enabled, channels = sortChannels(channels), programs = it.programs.filter { p -> p.channelId in allowed }, selected = it.selected?.takeIf { p -> p.channelId in allowed }) }
                val programs = mutableListOf<LiveProgram>()
                // Keep on-now/next truthful even when the viewer browses a future or past guide window.
                val windows = listOf(start, desktopGuideStart(now())).distinct()
                for (window in windows) for (batch in allowed.toList().chunked(100)) {
                    offset = 0
                    do {
                        val page = api.programs(a, batch, iso(window), iso(window + WINDOW), startIndex = offset).required()
                        programs += guidePrograms(page.items, allowed, iso(window), iso(window + WINDOW))
                        offset += page.items.size
                    } while (page.items.isNotEmpty() && offset < page.totalRecordCount)
                }
                val recordings = mutableListOf<MediaItem>()
                offset = 0
                do {
                    val page = api.recordings(a, startIndex = offset).required()
                    recordings += page.items; offset += page.items.size
                } while (page.items.isNotEmpty() && offset < page.totalRecordCount)
                val timers = if (policy.manage) api.timers(a).required() else emptyList()
                val series = if (policy.manage) api.timers(a, true).required() else emptyList()
                if (generation == version) update(a) { it.copy(loading = false, programs = programs.distinctBy(LiveProgram::id), recordings = recordings.distinctBy(MediaItem::id), timers = timers, series = series, fetchedAt = now(), offline = false, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: LiveDesktopReadFailure) {
                if (generation == version) update(a) {
                    if (e.status in listOf(401, 403)) DesktopLiveState(loading = false, policy = LivePolicy(), start = start)
                    else it.copy(loading = false, offline = true, error = "Couldn’t reach the server. Retry to update Live TV.")
                }
            } catch (_: Exception) {
                if (generation == version) update(a) { it.copy(loading = false, offline = true, error = "Couldn’t load Live TV. Retry to update it.") }
            }
        }
    }
    fun defaults(program: LiveProgram, series: Boolean) = mutate { a ->
        val policy = api.policy(a).required()
        require(policy.access && policy.manage) { "Recording management is disabled for this viewer." }
        require(mutable.value.actionsAvailable(now())) { "Refresh the guide before scheduling." }
        val fresh = api.program(a, program.id).required()
        require(liveInstant(fresh.endDate)?.let { it > now() } == true) { "This airing has ended. Refresh the guide." }
        val existingId = if (series) fresh.seriesTimerId else fresh.timerId
        if (!existingId.isNullOrBlank()) {
            val existing = api.timers(a, series).required().singleOrNull { it.id == existingId }
                ?: error("This schedule changed. Refresh Live TV before editing.")
            update(a) { it.copy(draft = existing, draftSeries = series, draftExisting = true) }
        } else {
            val defaults = api.defaults(a, program.id).required()
            val draft = timerDraft(defaults, fresh, series)
            update(a) { it.copy(draft = draft, draftSeries = series, draftExisting = false) }
        }
    }
    fun edit(schedule: LiveSchedule, series: Boolean) { mutable.update { it.copy(draft = schedule, draftSeries = series, draftExisting = true) } }
    fun save(draft: LiveSchedule, series: Boolean, existing: Boolean) = write {
        if (existing) api.updateTimer(it, draft, series) else api.createTimer(it, draft, series)
    }
    fun cancel(schedule: LiveSchedule, series: Boolean) = write { api.cancelTimer(it, schedule.id, series) }
    fun delete(item: MediaItem) = write { api.deleteRecording(it, item.id) }
    fun favorite(channel: LiveChannel) = mutate { a ->
        api.favorite(a, channel.id, !channel.userData.isFavorite).required()
        refresh()
    }
    private fun write(operation: suspend (Account) -> LiveWrite) = mutate { a ->
        require(mutable.value.actionsAvailable(now())) { "Refresh Live TV before making changes." }
        val answer = operation(a)
        update(a) { it.copy(notice = answer.message ?: when (answer.outcome) {
            "ok" -> "Recording change saved."
            "unknown" -> "The result is uncertain. Refresh to reconcile before trying again."
            else -> "The recording change was not saved. Refresh and review it."
        }, draft = if (answer.outcome == "ok") null else it.draft) }
        refresh()
    }
    @Synchronized private fun mutate(operation: suspend (Account) -> Unit) {
        val a = owner ?: return
        if (!owns(a) || mutable.value.writing) return
        update(a) { it.copy(writing = true, error = null, notice = null) }
        mutation = scope.launch {
            try { operation(a) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update(a) { it.copy(error = if (e is LiveDesktopReadFailure) "The server could not complete this change. Refresh and review it." else e.message ?: "The change could not be saved.") } }
            finally { update(a) { it.copy(writing = false) } }
        }
    }
    companion object { const val WINDOW = 3 * 60 * 60 * 1000L }
}

private class LiveDesktopReadFailure(val status: Int?) : Exception()
private fun <T> ClientResult<T>.required(): T = when (this) {
    is ClientResult.Success -> value
    is ClientResult.Failure -> throw LiveDesktopReadFailure(error.status)
}
internal fun desktopGuideStart(now: Long): Long = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    .let { it.withMinute(it.minute / 30 * 30).withSecond(0).withNano(0).toInstant().toEpochMilli() }
internal fun iso(value: Long): String = Instant.ofEpochMilli(value).toString()

/** Crash-safe reconciliation markers contain only a hash, never an account token or private server URL. */
internal class DesktopLivePendingWrites(private val directory: File) : LivePendingWrites {
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
    override suspend fun contains(key: String): Boolean = file(key).exists()
    override suspend fun add(key: String) { atomicPrivateWrite(file(key), byteArrayOf(1)) }
    override suspend fun remove(key: String) { val f = file(key); check(!f.exists() || f.delete()) { "Could not clear recording reconciliation state." } }
}
