/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.StatFs
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.offline.OFFLINE_PART_SUFFIX
import app.pictureshow.core.offline.OfflineBudget
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJobEvent
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflineResource
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.OfflineVerifyInput
import app.pictureshow.core.offline.commit
import app.pictureshow.core.offline.commitReady
import app.pictureshow.core.offline.discard
import app.pictureshow.core.offline.offlineAdmit
import app.pictureshow.core.offline.offlineCleanupRequest
import app.pictureshow.core.offline.offlineDecodeManifest
import app.pictureshow.core.offline.offlineDefaultCap
import app.pictureshow.core.offline.offlineEncodeManifest
import app.pictureshow.core.offline.offlineEntryDir
import app.pictureshow.core.offline.offlineEntryId
import app.pictureshow.core.offline.offlineSafePath
import app.pictureshow.core.offline.offlineVerify
import app.pictureshow.core.offline.reconcile
import app.pictureshow.core.offline.reduceOfflineJob
import app.pictureshow.core.offline.release
import app.pictureshow.core.offline.reserve
import app.pictureshow.core.offline.setCap
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

internal data class OfflineLiveProgress(val received: Long, val total: Long?)

/** One viewer's offline library as the screens read it. */
internal data class OfflineLibrary(
    val manifest: OfflineManifest = OfflineManifest(),
    /** The manifest exists but can't be read by this version or at all. Nothing was deleted. */
    val recoverable: Boolean = false,
    val needsPassword: Boolean = false,
    val progress: Map<String, OfflineLiveProgress> = emptyMap()
)

@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
internal enum class OfflineNetwork { none, metered, unmetered }

/** Wi-Fi-only by default; the per-job override is the only way past it (OFF-06a). */
internal fun offlineNetworkAllows(network: OfflineNetwork, wifiOnly: Boolean, cellularAllowed: Boolean): Boolean = when (network) {
    OfflineNetwork.none -> false
    OfflineNetwork.unmetered -> true
    OfflineNetwork.metered -> !wifiOnly || cellularAllowed
}

/** Keeps the process alive and honest while bytes move. */
internal interface OfflineForeground {
    fun update(activeTitles: List<String>)
}

private const val OFFLINE_RETRIES = 3
private const val OFFLINE_LOG = "PictureshowOffline"
private const val OFFLINE_STORAGE_MARGIN = 16L * 1024 * 1024

internal class OfflineEngine(
    private val context: Context,
    private val accounts: AccountStore,
    private val identity: suspend () -> ClientIdentity,
    private val foreground: OfflineForeground,
    private val http: OfflineHttp = offlineOkHttp(),
    private val apiClient: okhttp3.OkHttpClient = PlaybackMedia.createPlaybackHttpClient(),
    private val networkState: () -> OfflineNetwork = { measuredNetwork(context) },
    private val freeBytes: (File) -> Long? = { dir -> runCatching { StatFs(dir.existingParent().path).availableBytes }.getOrNull() },
    private val quotaBytes: (File) -> Long? = { dir -> runCatching { StatFs(dir.existingParent().path).totalBytes }.getOrNull() },
    private val retryDelayMs: (Int) -> Long = { attempt -> 5_000L * (1L shl (attempt * 2)) },
    private val unreachableDelayMs: (Int) -> Long = { tries -> minOf(300_000L, 5_000L shl tries.coerceAtMost(6)) },
    private val storageRecheckMs: Long = 30_000L,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    /** Background work here must never take the app down with it, and a viewer may be in the middle of a film. */
    private val scope = scope + CoroutineExceptionHandler { _, error ->
        if (error is Error) throw error
        android.util.Log.w(OFFLINE_LOG, "Offline work ended: ${error.javaClass.simpleName}")
    }
    internal val files = OfflineFiles(context.noBackupFilesDir)
    private val mutex = Mutex()
    private val mutableLibraries = MutableStateFlow(emptyMap<String, OfflineLibrary>())
    private val runners = mutableMapOf<String, Job>()
    private val verifyFailures = mutableMapOf<String, Int>()
    private val progressStores = mutableMapOf<String, OfflineProgressStore>()
    private var started: Job? = null
    private var api: OfflineApi? = null
    private var sessionOwner: OfflineSessionOwner? = null
    private var lastAccountKeys = emptySet<String>()
    private val unreachable = mutableMapOf<String, OfflineUnreachable>()
    private val freeAtDenial = mutableMapOf<String, Long>()
    private var storageWatch: Job? = null

    @Volatile
    private var lastNetwork: OfflineNetwork? = null

    val libraries: StateFlow<Map<String, OfflineLibrary>> = mutableLibraries

    private suspend fun api(): OfflineApi = api ?: OfflineApi(apiClient, identity()).also { api = it }

    private suspend fun sessions(): OfflineSessionOwner = sessionOwner ?: OfflineSessionOwner(
        files.sessions(),
        api()
    ) { identity().deviceId }.also { sessionOwner = it }

    /** Runs once per process. Everything here works with the network off. */
    fun start(): Job {
        started?.let { return it }
        val job = scope.launch {
            files.emptyTrash()
            val snapshot = accounts.load()
            mutex.withLock {
                purgeOrphansLocked(snapshot.accounts.map(Account::key).toSet())
                snapshot.accounts.forEach { loadLocked(it.key) }
                lastAccountKeys = snapshot.accounts.map(Account::key).toSet()
                pumpLocked()
                watchStorageLocked()
            }
            scope.launch { observeAccounts() }
            registerNetworkCallback()
            networkChanged()
        }
        started = job
        return job
    }

    /** Sign-out and removal: bytes go now, without waiting for any server (OFF-09). */
    private suspend fun observeAccounts() {
        accounts.snapshots.collect { snapshot ->
            val keys = snapshot.accounts.map(Account::key).toSet()
            mutex.withLock {
                val gone = lastAccountKeys - keys
                lastAccountKeys = keys
                gone.forEach { purgeLocked(it) }
                (keys - mutableLibraries.value.keys).forEach { loadLocked(it) }
            }
            runCatching { sessions().endAllExcept(snapshot.accounts.map { it.server.id to it.user.id }.toSet()) }
            if (keys.isNotEmpty() || lastAccountKeys.isEmpty()) mutex.withLock { pumpLocked() }
        }
    }

    private fun purgeOrphansLocked(keys: Set<String>) {
        val owned = keys.map { files.accountDir(it).name }.toSet()
        files.accountDirs().filterNot { it.name in owned }.forEach { files.moveToTrash(it) }
        files.emptyTrash()
    }

    private fun purgeLocked(key: String) {
        runners.keys.filter { it.startsWith("$key|") }.forEach { runnerKey -> runners.remove(runnerKey)?.cancel() }
        progressStores.remove(key)
        mutableLibraries.update { it - key }
        // The rename hides everything in one step; a failed slow delete stays private in trash and retries at next start.
        files.moveToTrash(files.accountDir(key))
        files.emptyTrash()
        publishForeground()
    }

    private fun loadLocked(key: String) {
        val file = files.manifest(key)
        var recoverable = false
        val manifest = if (file.exists()) {
            offlineDecodeManifest(runCatching { file.readText() }.getOrDefault("")).also { recoverable = it == null } ?: OfflineManifest()
        } else {
            OfflineManifest()
        }
        if (recoverable) {
            mutableLibraries.update { it + (key to OfflineLibrary(recoverable = true)) }
            return
        }
        val reconciled = manifest.reconcile(files.inventory(key))
        reconciled.deletePaths.filter(::offlineSafePath).forEach { File(files.mediaDir(key), it).delete() }
        val capped = if (reconciled.manifest.budget.capBytes == null) {
            reconciled.manifest.copy(
                budget = reconciled.manifest.budget.copy(capBytes = offlineDefaultCap(quotaBytes(files.accountDir(key))))
            )
        } else {
            reconciled.manifest
        }
        retryPendingCleanupLocked(key, capped).let { saveLocked(key, it) }
    }

    private fun retryPendingCleanupLocked(key: String, manifest: OfflineManifest): OfflineManifest {
        val left = manifest.pendingByteCleanup.filterNot { File(files.mediaDir(key), offlineEntryDir(it)).deleteRecursively() }
        return manifest.copy(pendingByteCleanup = left)
    }

    private fun library(key: String) = mutableLibraries.value[key]

    private fun saveLocked(key: String, manifest: OfflineManifest) {
        files.manifest(key).writeAtomically(offlineEncodeManifest(manifest))
        mutableLibraries.update { all -> all + (key to (all[key] ?: OfflineLibrary()).copy(manifest = manifest, recoverable = false)) }
    }

    /** In memory only: reconcile recounts bytes from disk, so a kill can't strand them. */
    private fun setBudgetLocked(key: String, budget: OfflineBudget) {
        mutableLibraries.update { all ->
            val library = all[key] ?: return@update all
            all + (key to library.copy(manifest = library.manifest.copy(budget = budget)))
        }
    }

    private fun account(key: String): Account? = accounts.snapshots.value.accounts.firstOrNull { it.key == key }

    fun progressStore(account: Account): OfflineProgressStore =
        synchronized(progressStores) { progressStores.getOrPut(account.key) { OfflineProgressStore(files.progress(account.key), account) } }

    fun resourceFile(key: String, entry: OfflineEntry, resource: OfflineResource): File = files.resource(key, entry.id, resource.path)

    // Downloads

    suspend fun enqueue(account: Account, item: OfflineApiItem, choice: OfflineChoice, warning: String? = null): Boolean = mutex.withLock {
        val library = library(account.key) ?: run {
            loadLocked(account.key)
            library(account.key)
        } ?: return@withLock false
        if (library.recoverable) return@withLock false
        val id = offlineEntryId(item.id, choice.preset)
        // A second request for the same media is the same download (OFF-01: deduplicated).
        if (library.manifest.entries.any { it.id == id }) return@withLock true
        val entry = offlineBuildEntry(item, choice, library.manifest.nextSequence, warning)
        val manifest = library.manifest.copy(entries = library.manifest.entries + entry, nextSequence = library.manifest.nextSequence + 1)
        saveLocked(account.key, manifest)
        item.userData?.let { progressStore(account).remember(item.id, it.core()) }
        pumpLocked()
        true
    }

    suspend fun pause(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("pause"))

    suspend fun resume(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("resume"))

    suspend fun retry(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("retry"))

    suspend fun redownload(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("redownload"))

    suspend fun delete(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("delete"))

    suspend fun setCellular(key: String, entryId: String, allowed: Boolean) =
        job(key, entryId, OfflineJobEvent("set-cellular", cellularAllowed = allowed))

    suspend fun deleteMany(key: String, entryIds: List<String>) = entryIds.forEach { delete(key, it) }

    suspend fun setCap(key: String, capBytes: Long) = mutex.withLock {
        val manifest = library(key)?.manifest ?: return@withLock
        val newest = manifest.entries.sortedByDescending { it.createdSequence }.map(OfflineEntry::id)
        val result = manifest.budget.setCap(capBytes, newest)
        saveLocked(key, manifest.copy(budget = result.budget))
        result.pauseJobs.forEach { eventLocked(key, it, OfflineJobEvent("pause", reason = "cap")) }
        storageMayHaveFreedLocked(key)
        pumpLocked()
    }

    suspend fun setWifiOnly(key: String, wifiOnly: Boolean) = mutex.withLock {
        val manifest = library(key)?.manifest ?: return@withLock
        saveLocked(key, manifest.copy(wifiOnly = wifiOnly))
        applyNetworkLocked()
    }

    /** The viewer typed the password this server needs because Quick Connect is off. It's not kept. */
    suspend fun providePassword(account: Account, password: String): Boolean {
        val result = sessions().token(account, password)
        mutex.withLock {
            mutableLibraries.update { all ->
                val library = all[account.key] ?: return@update all
                all + (account.key to library.copy(needsPassword = result is OfflineSetToken.NeedsPassword))
            }
            if (result is OfflineSetToken.Ready) {
                library(account.key)?.manifest?.entries?.filter {
                    it.job.phase == OfflinePhase.failed && it.job.failure == "needs-password"
                }
                    ?.forEach { eventLocked(account.key, it.id, OfflineJobEvent("retry")) }
                pumpLocked()
            }
        }
        return result is OfflineSetToken.Ready
    }

    /** A Downloads visit is a good moment to look again without the viewer asking. */
    suspend fun refresh() = mutex.withLock {
        lastAccountKeys.forEach { key ->
            storageMayHaveFreedLocked(key)
            val library = library(key) ?: return@forEach
            val reconciled = library.manifest.reconcile(files.inventory(key))
            if (library.manifest.entries.any { it.job.phase == OfflinePhase.ready } && reconciled.changedEntries.isNotEmpty()) {
                val missing = reconciled.manifest.entries.filter { it.job.phase == OfflinePhase.missing }.map(OfflineEntry::id).toSet()
                saveLocked(
                    key,
                    library.manifest.copy(
                        entries = library.manifest.entries.map { entry ->
                            if (entry.id in missing && entry.job.phase == OfflinePhase.ready) {
                                entry.copy(job = reduceOfflineJob(entry.job, OfflineJobEvent("missing")).job ?: entry.job)
                            } else {
                                entry
                            }
                        }
                    )
                )
            }
        }
        pumpLocked()
    }

    private fun storageMayHaveFreedLocked(key: String) {
        library(key)?.manifest?.entries?.filter { it.job.phase == OfflinePhase.waitingStorage }?.forEach {
            eventLocked(key, it.id, OfflineJobEvent("storage-available"))
        }
    }

    private suspend fun job(key: String, entryId: String, event: OfflineJobEvent) = mutex.withLock {
        eventLocked(key, entryId, event)
        if (event.op == "delete") storageMayHaveFreedLocked(key)
        pumpLocked()
    }

    private fun runnerKey(key: String, entryId: String) = "$key|$entryId"

    /** Every phase change is durable before its effects run. */
    private fun eventLocked(key: String, entryId: String, event: OfflineJobEvent): Boolean {
        val manifest = library(key)?.manifest ?: return false
        val entry = manifest.entries.firstOrNull { it.id == entryId } ?: return false
        val result = reduceOfflineJob(entry.job, event)
        if (result.ignored != null) return false
        val next = result.job
        val after = if (next == null) {
            manifest.copy(entries = manifest.entries - entry)
        } else {
            manifest.copy(entries = manifest.entries.map { if (it.id == entryId) it.copy(job = next) else it })
        }
        saveLocked(key, after)
        result.effects.forEach { effect ->
            when (effect) {
                "stop-transfer" -> runners.remove(runnerKey(key, entryId))?.cancel()
                "discard-partial" -> discardPartialsLocked(key, entryId)
                "delete-owned" -> deleteOwnedLocked(key, entryId)
                "start-attempt" -> runners[runnerKey(key, entryId)] = scope.launch { runAttempt(key, entryId, next?.attempt ?: 0) }
            }
        }
        publishForeground()
        return true
    }

    private fun discardPartialsLocked(key: String, entryId: String) {
        val manifest = library(key)?.manifest ?: return
        val entry = manifest.entries.firstOrNull { it.id == entryId } ?: return
        var freed = 0L
        entry.resources.forEach { resource ->
            listOf(files.resource(key, entryId, resource.path), partFile(key, entryId, resource)).forEach { file ->
                freed += file.length()
                file.delete()
            }
        }
        val budget = manifest.budget.discard(entryId, freed).budget
        saveLocked(
            key,
            manifest.copy(
                budget = budget,
                entries = manifest.entries.map { current ->
                    if (current.id != entryId) {
                        current
                    } else {
                        current.copy(
                            resources = current.resources.map {
                                it.copy(complete = false, bytes = null, sha256 = null, validator = null)
                            }
                        )
                    }
                }
            )
        )
    }

    private fun partFile(key: String, entryId: String, resource: OfflineResource) =
        File(files.resource(key, entryId, resource.path).path + OFFLINE_PART_SUFFIX)

    private fun deleteOwnedLocked(key: String, entryId: String) {
        val manifest = library(key)?.manifest ?: return
        val dir = File(files.mediaDir(key), offlineEntryDir(entryId))
        val bytes = dir.walkTopDown().filter(File::isFile).sumOf(File::length)
        val removed = dir.deleteRecursively()
        val budget = manifest.budget.discard(entryId, bytes).budget
        verifyFailures.remove(runnerKey(key, entryId))
        saveLocked(
            key,
            manifest.copy(
                budget = budget,
                // A removal that couldn't finish is retried at next start and the record is gone either way.
                pendingByteCleanup = if (removed) manifest.pendingByteCleanup else manifest.pendingByteCleanup + entryId
            )
        )
        eventLocked(key, entryId, OfflineJobEvent("deleted"))
    }

    private fun activeCount(manifest: OfflineManifest) = manifest.entries.count {
        it.job.phase == OfflinePhase.preparing ||
            it.job.phase == OfflinePhase.transferring ||
            it.job.phase == OfflinePhase.verifying
    }

    private fun pumpLocked() {
        val network = networkState()
        lastAccountKeys.forEach { key ->
            val current = account(key) ?: return@forEach
            if (current.expired) return@forEach
            val manifest = library(key)?.manifest ?: return@forEach
            val queued = manifest.entries.filter { it.job.phase == OfflinePhase.queued }.sortedBy(OfflineEntry::createdSequence)
            val (allowed, blocked) = queued.partition { offlineNetworkAllows(network, manifest.wifiOnly, it.job.cellularAllowed) }
            blocked.forEach { eventLocked(key, it.id, OfflineJobEvent("network-lost")) }
            offlineAdmit(activeCount(manifest), allowed.map(OfflineEntry::id)).forEach { id ->
                eventLocked(key, id, OfflineJobEvent("admit"))
            }
        }
        publishForeground()
    }

    private fun publishForeground() {
        val titles = mutableLibraries.value.values.flatMap { library ->
            library.manifest.entries.filter {
                it.job.phase == OfflinePhase.preparing ||
                    it.job.phase == OfflinePhase.transferring ||
                    it.job.phase == OfflinePhase.verifying
            }.map { it.labels.title }
        }
        foreground.update(titles)
    }

    /** The network really changed, or the viewer came to look: a server that couldn't be reached is worth trying now. */
    fun networkChanged() {
        scope.launch {
            mutex.withLock {
                unreachable.clear()
                applyNetworkLocked()
            }
            if (networkState() != OfflineNetwork.none) syncAll()
        }
    }

    /** Android reports these many times a second on some networks. Only a different answer counts as a change. */
    fun networkCapabilitiesChanged() {
        if (networkState() != lastNetwork) networkChanged()
    }

    private fun applyNetworkLocked() {
        val network = networkState()
        lastNetwork = network
        val now = monotonicMs()
        lastAccountKeys.forEach { key ->
            val manifest = library(key)?.manifest ?: return@forEach
            manifest.entries.forEach { entry ->
                val allowed = offlineNetworkAllows(network, manifest.wifiOnly, entry.job.cellularAllowed)
                val resting = (unreachable[unreachableSlot(key, entry.id)]?.untilMs ?: 0L) > now
                if (entry.job.phase == OfflinePhase.waitingNetwork && allowed && !resting) {
                    eventLocked(key, entry.id, OfflineJobEvent("network-back"))
                } else if (!allowed &&
                    entry.job.phase in
                    listOf(OfflinePhase.preparing, OfflinePhase.transferring, OfflinePhase.verifying, OfflinePhase.queued)
                ) {
                    eventLocked(key, entry.id, OfflineJobEvent("network-lost"))
                }
            }
        }
        pumpLocked()
    }

    fun syncSoon() {
        scope.launch { if (networkState() != OfflineNetwork.none) syncAll() }
    }

    /** Dirty progress only, with the viewer's own session. */
    suspend fun syncAll() {
        val remote = OfflineApiProgressRemote(api())
        lastAccountKeys.mapNotNull(::account).filterNot(Account::expired).forEach { current ->
            runCatching { progressStore(current).sync(remote) }
        }
        runCatching { sessions().reconnect() }
    }

    fun foregroundTimedOut() {
        scope.launch {
            mutex.withLock {
                lastAccountKeys.forEach { key ->
                    library(key)?.manifest?.entries?.filter {
                        it.job.phase == OfflinePhase.preparing ||
                            it.job.phase == OfflinePhase.transferring ||
                            it.job.phase == OfflinePhase.verifying
                    }?.forEach { eventLocked(key, it.id, OfflineJobEvent("network-lost")) }
                }
            }
        }
    }

    // One attempt

    private suspend fun runAttempt(key: String, entryId: String, attempt: Long) {
        val session = UUID.randomUUID().toString().replace("-", "")
        var cleanup: (suspend () -> Unit)? = null
        try {
            val current = account(key) ?: return
            val api = api()
            val set = when (val token = sessions().token(current)) {
                is OfflineSetToken.Ready -> token
                OfflineSetToken.NeedsPassword -> {
                    fail(key, entryId, attempt, "needs-password")
                    mutex.withLock {
                        mutableLibraries.update { all -> all[key]?.let { all + (key to it.copy(needsPassword = true)) } ?: all }
                    }
                    return
                }
                is OfflineSetToken.Unavailable -> {
                    if (token.reason == "network") networkLost(key, entryId) else fail(key, entryId, attempt, token.reason)
                    return
                }
            }
            cleanup = { api.cleanup(current.server.url, offlineCleanupRequest(set.deviceId, session), set.deviceId, set.token) }
            val fresh = runCatching { api.item(current, entryOrNull(key, entryId)?.itemId ?: return) }.getOrElse { error ->
                when {
                    error is CancellationException -> throw error
                    error is OfflineApiException && error.status in 401..404 -> fail(key, entryId, attempt, "denied")
                    else -> networkLost(key, entryId)
                }
                return
            }
            val sourceNow = fresh.sources.firstOrNull()?.etag
            val before = entryOrNull(key, entryId) ?: return
            // The source changed since the entry was created: partials of the old one can't be reused (OFF-03d).
            if (before.sourceETag != null && sourceNow != null && before.sourceETag != sourceNow) {
                mutex.withLock {
                    discardPartialsLocked(key, entryId)
                    updateEntryLocked(key, entryId) { it.copy(sourceETag = sourceNow) }
                }
            }
            if (!mutex.withLock { eventLocked(key, entryId, OfflineJobEvent("prepared", attempt = attempt)) }) return
            val headers = mapOf("Authorization" to api.authorization(set.deviceId, set.token))
            val serverUrl = current.server.url
            (entryOrNull(key, entryId) ?: return).resources.forEach { resource ->
                if (!transferResource(key, entryId, attempt, resource, serverUrl, api, headers, set.deviceId, session)) return
            }
            if (!mutex.withLock { eventLocked(key, entryId, OfflineJobEvent("transfer-complete", attempt = attempt)) }) return
            cleanup?.invoke()
            cleanup = null
            verify(key, entryId, attempt, current, api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            networkLost(key, entryId)
        } catch (e: Exception) {
            // Whatever it was ends this one attempt where the viewer can see it and press Retry.
            android.util.Log.w(OFFLINE_LOG, "Offline attempt ended: ${e.javaClass.simpleName}")
            fail(key, entryId, attempt, "internal")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                cleanup?.invoke()
                mutex.withLock {
                    runners.remove(runnerKey(key, entryId))
                    library(key)?.manifest?.budget?.release(entryId)?.let { setBudgetLocked(key, it.budget) }
                    mutableLibraries.update { all ->
                        all[key]?.let { all + (key to it.copy(progress = it.progress - entryId)) } ?: all
                    }
                    pumpLocked()
                }
            }
        }
    }

    private fun entryOrNull(key: String, entryId: String) = library(key)?.manifest?.entries?.firstOrNull { it.id == entryId }

    private fun updateEntryLocked(key: String, entryId: String, change: (OfflineEntry) -> OfflineEntry) {
        val manifest = library(key)?.manifest ?: return
        saveLocked(key, manifest.copy(entries = manifest.entries.map { if (it.id == entryId) change(it) else it }))
    }

    private fun updateResourceLocked(key: String, entryId: String, resourceId: String, change: (OfflineResource) -> OfflineResource) =
        updateEntryLocked(key, entryId) { entry ->
            entry.copy(
                resources = entry.resources.map {
                    if (it.id ==
                        resourceId
                    ) {
                        change(it)
                    } else {
                        it
                    }
                }
            )
        }

    /**
     * The network can look fine while the server is off or out of reach, so an attempt that couldn't reach it rests
     * before the next one, longer each time. Bytes arriving or a real network change end the rest.
     */
    private suspend fun networkLost(key: String, entryId: String) {
        mutex.withLock {
            eventLocked(key, entryId, OfflineJobEvent("network-lost"))
            val slot = unreachableSlot(key, entryId)
            val tries = unreachable[slot]?.tries ?: 0
            val rest = unreachableDelayMs(tries)
            unreachable[slot] = OfflineUnreachable(tries + 1, monotonicMs() + rest)
            scope.launch {
                delay(rest)
                mutex.withLock { applyNetworkLocked() }
            }
        }
    }

    private fun unreachableSlot(key: String, entryId: String) = "$key/$entryId"

    private fun monotonicMs() = System.nanoTime() / 1_000_000

    /** Space comes back without telling anyone. While something waits for it, look now and then. */
    private fun watchStorageLocked() {
        if (storageWatch?.isActive == true) return
        if (lastAccountKeys.none(::hasWaitingStorage)) return
        storageWatch = scope.launch {
            while (true) {
                delay(storageRecheckMs)
                val more = mutex.withLock {
                    val waiting = lastAccountKeys.filter(::hasWaitingStorage)
                    waiting.forEach { key ->
                        val free = freeBytes(files.accountDir(key)) ?: return@forEach
                        val before = freeAtDenial[key]
                        if (before == null || free > before + OFFLINE_STORAGE_MARGIN) {
                            freeAtDenial[key] = free
                            storageMayHaveFreedLocked(key)
                            pumpLocked()
                        }
                    }
                    waiting.isNotEmpty()
                }
                if (!more) return@launch
            }
        }
    }

    private fun hasWaitingStorage(key: String): Boolean {
        val entries = library(key)?.manifest?.entries.orEmpty()
        return entries.any { it.job.phase == OfflinePhase.waitingStorage }
    }

    private suspend fun fail(key: String, entryId: String, attempt: Long, reason: String) {
        mutex.withLock { eventLocked(key, entryId, OfflineJobEvent("fail", attempt = attempt, reason = reason)) }
    }

    private fun ledger(key: String, entryId: String) = object : OfflineLedger {
        override suspend fun reserve(bytes: Long): Boolean = mutex.withLock {
            val budget = library(key)?.manifest?.budget ?: return@withLock false
            val result = budget.reserve(entryId, bytes, freeBytes(files.accountDir(key)))
            setBudgetLocked(key, result.budget)
            result.granted
        }

        override suspend fun commit(bytes: Long) = mutex.withLock {
            unreachable.remove(unreachableSlot(key, entryId))
            library(key)?.manifest?.budget?.commit(entryId, bytes)?.let { setBudgetLocked(key, it.budget) } ?: Unit
        }

        override suspend fun discard(bytes: Long) = mutex.withLock {
            library(key)?.manifest?.budget?.discard(entryId, bytes)?.let { setBudgetLocked(key, it.budget) } ?: Unit
        }

        override suspend fun progress(received: Long, total: Long?) {
            mutableLibraries.update { all ->
                val library = all[key] ?: return@update all
                all + (key to library.copy(progress = library.progress + (entryId to OfflineLiveProgress(received, total))))
            }
        }
    }

    /** True to go on. False: the job's new phase is already recorded, or this attempt is over. */
    private suspend fun transferResource(
        key: String,
        entryId: String,
        attempt: Long,
        resource: OfflineResource,
        serverUrl: String,
        api: OfflineApi,
        headers: Map<String, String>,
        deviceId: String,
        session: String
    ): Boolean {
        val final = files.resource(key, entryId, resource.path)
        if (resource.complete && final.exists()) return true
        val entry = entryOrNull(key, entryId) ?: return false
        val part = partFile(key, entryId, resource)
        val url = api.url(serverUrl, entry.requestFor(resource, deviceId, session))
        val transfer = OfflineResourceTransfer(http, ledger(key, entryId))
        val spaced = entry.needsIndex(resource)
        var tries = 0
        while (true) {
            val stored = entryOrNull(key, entryId)?.resources?.firstOrNull { it.id == resource.id }?.validator
            val result = transfer.run(part, url, headers, stored, entry.sourceETag, spaced) { validator ->
                mutex.withLock { updateResourceLocked(key, entryId, resource.id) { it.copy(validator = validator) } }
            }
            when (result) {
                is OfflineTransferResult.Complete -> {
                    if (spaced && !writeFmp4Index(part)) {
                        fail(key, entryId, attempt, "not-indexable")
                        return false
                    }
                    val sha = part.sha256Hex()
                    val bytes = part.length()
                    if (!part.renameTo(final)) throw IOException("Could not commit ${resource.id}")
                    mutex.withLock {
                        updateResourceLocked(key, entryId, resource.id) {
                            it.copy(complete = true, bytes = bytes, sha256 = sha, validator = result.validator ?: it.validator)
                        }
                    }
                    return true
                }
                OfflineTransferResult.NoBudget -> {
                    mutex.withLock {
                        eventLocked(key, entryId, OfflineJobEvent("storage-denied"))
                        freeBytes(files.accountDir(key))?.let { freeAtDenial[key] = it }
                        watchStorageLocked()
                    }
                    return false
                }
                is OfflineTransferResult.Failed -> {
                    if (!resource.required) return true
                    if (result.reason == "unauthorized") {
                        // A revoked set is revalidated once by the viewer's own session on the next attempt.
                        current(key)?.let { sessions().end(it.server.id, it.user.id) }
                    }
                    fail(key, entryId, attempt, result.reason)
                    return false
                }
                is OfflineTransferResult.Interrupted -> {
                    tries++
                    if (!resource.required) return true
                    if (networkState() == OfflineNetwork.none) {
                        networkLost(key, entryId)
                        return false
                    }
                    if (tries >= OFFLINE_RETRIES) {
                        // A server that stopped answering comes back on its own. One that answers and keeps dropping won't.
                        if (serverAnswers(key, entry.itemId, api)) {
                            fail(key, entryId, attempt, result.reason)
                        } else {
                            networkLost(key, entryId)
                        }
                        return false
                    }
                    delay(retryDelayMs(tries))
                }
            }
        }
    }

    private fun current(key: String) = account(key)

    private suspend fun serverAnswers(key: String, itemId: String, api: OfflineApi): Boolean {
        val viewer = current(key) ?: return true
        return try {
            api.item(viewer, itemId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfflineApiException) {
            true
        } catch (e: IOException) {
            false
        } catch (e: SerializationException) {
            // A sign-in page or proxy answered. That isn't this server.
            false
        }
    }

    private suspend fun verify(key: String, entryId: String, attempt: Long, current: Account, api: OfflineApi) {
        val entry = entryOrNull(key, entryId) ?: return
        val sourceNow = runCatching { api.item(current, entry.itemId).sources.firstOrNull()?.etag }.getOrNull()
        val failure = entry.resources.filter { it.required }.firstNotNullOfOrNull { resource ->
            val file = files.resource(key, entryId, resource.path)
            if (!file.exists()) return@firstNotNullOfOrNull "length"
            offlineVerify(
                OfflineVerifyInput(
                    file.length(),
                    if (entry.needsIndex(resource)) resource.bytes else resource.validator?.totalBytes ?: resource.bytes,
                    file.sha256Hex(),
                    resource.sha256,
                    entry.sourceETag,
                    sourceNow
                )
            ).reason
        }
        mutex.withLock {
            val counter = runnerKey(key, entryId)
            if (failure != null) {
                val count = (verifyFailures[counter] ?: 0) + 1
                verifyFailures[counter] = count
                val event = if (count >= 2) {
                    OfflineJobEvent("fail", attempt = attempt, reason = failure)
                } else {
                    OfflineJobEvent("verify-failed", attempt = attempt, reason = failure)
                }
                eventLocked(key, entryId, event)
                return@withLock
            }
            verifyFailures.remove(counter)
            if (!eventLocked(key, entryId, OfflineJobEvent("verified", attempt = attempt))) return@withLock
            val manifest = library(key)?.manifest ?: return@withLock
            val result = manifest.commitReady(entryId, attempt)
            if (result.ok) {
                // A deliberately absent optional resource isn't owed to anybody.
                saveLocked(key, result.manifest)
            } else {
                eventLocked(key, entryId, OfflineJobEvent("fail", attempt = attempt, reason = result.reason ?: "commit"))
            }
            publishForeground()
        }
    }

    // Network

    private fun registerNetworkCallback() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = networkChanged()
                    override fun onLost(network: Network) = networkChanged()
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkCapabilitiesChanged()
                }
            )
        }
    }

    /** Waits for the first load, for callers that need a settled answer (cold start). */
    suspend fun ready(): Map<String, OfflineLibrary> {
        start().join()
        return libraries.first()
    }

    fun userData(account: Account, itemId: String, data: OfflineUserData) {
        scope.launch { progressStore(account).remember(itemId, data) }
    }
}

private fun File.existingParent(): File {
    var at: File? = this
    while (at != null && !at.exists()) at = at.parentFile
    return at ?: this
}

private data class OfflineUnreachable(val tries: Int, val untilMs: Long)

internal fun measuredNetwork(context: Context): OfflineNetwork {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return OfflineNetwork.none
    val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return OfflineNetwork.none
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return OfflineNetwork.none
    val free = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    return if (free) OfflineNetwork.unmetered else OfflineNetwork.metered
}
