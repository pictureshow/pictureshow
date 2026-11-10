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
import app.pictureshow.core.offline.offlineConversionShort
import app.pictureshow.core.offline.offlineDecodeManifest
import app.pictureshow.core.offline.offlineDefaultCap
import app.pictureshow.core.offline.offlineDurableOffset
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
import kotlin.coroutines.coroutineContext
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

    /** Something still has to finish without the viewer touching it, so a restart must stay booked. */
    fun unfinished(any: Boolean) = Unit
}

private const val OFFLINE_RETRIES = 3
private const val OFFLINE_LOG = "PictureshowOffline"
private val OFFLINE_UNFINISHED = setOf(
    OfflinePhase.queued,
    OfflinePhase.waitingNetwork,
    OfflinePhase.waitingStorage,
    OfflinePhase.preparing,
    OfflinePhase.transferring,
    OfflinePhase.verifying
)
private const val OFFLINE_STORAGE_MARGIN = 16L * 1024 * 1024

internal class OfflineEngine(
    private val context: Context,
    private val accounts: AccountStore,
    private val identity: suspend () -> ClientIdentity,
    private val foreground: OfflineForeground,
    http: OfflineHttp? = null,
    private val apiClient: okhttp3.OkHttpClient = PlaybackMedia.createPlaybackHttpClient(),
    networkState: (() -> OfflineNetwork)? = null,
    private val freeBytes: (File) -> Long? = { dir -> runCatching { StatFs(dir.existingParent().path).availableBytes }.getOrNull() },
    private val quotaBytes: (File) -> Long? = { dir -> runCatching { StatFs(dir.existingParent().path).totalBytes }.getOrNull() },
    private val retryDelayMs: (Int) -> Long = { attempt -> 5_000L * (1L shl (attempt * 2)) },
    private val unreachableDelayMs: (Int) -> Long = { tries -> minOf(300_000L, 5_000L shl tries.coerceAtMost(6)) },
    private val storageRecheckMs: Long = 30_000L,
    private val revocationRecheckMs: Long = 60_000L,
    private val trashCleaner: (OfflineFiles) -> Unit = OfflineFiles::emptyTrash,
    private val restartTrustedBytes: (Long) -> Long = ::offlineDurableOffset,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val jobNetwork = OfflineJobNetwork()
    private val http = http ?: offlineOkHttp(
        jobNetwork.calls(apiClient.newBuilder().readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build())
    )
    private val apiCalls = jobNetwork.calls(apiClient)
    private val networkState = networkState ?: { measuredNetwork(context, jobNetwork.network) }

    /** Background work here must never take the app down with it, and a viewer may be in the middle of a film. */
    private val onlineViewingReadLock = Any()
    private var lastOnlineViewingRead: Job? = null
    private val scope = scope + CoroutineExceptionHandler { _, error ->
        if (error is Error) throw error
        android.util.Log.w(OFFLINE_LOG, "Offline work ended: ${error.javaClass.simpleName}")
    }
    internal val files = OfflineFiles(context.noBackupFilesDir)
    private val mutex = Mutex()
    private val mutableLibraries = MutableStateFlow(emptyMap<String, OfflineLibrary>())

    /** The attempt in charge of each download. [liveAttempts] also has the ones that were told to stop and haven't yet. */
    private val runners = mutableMapOf<String, Job>()
    private val liveAttempts = java.util.concurrent.ConcurrentHashMap<Job, String>()
    private val verifyFailures = mutableMapOf<String, Int>()
    private val progressStores = mutableMapOf<String, OfflineProgressStore>()
    private val signedOut = mutableSetOf<String>()
    private val unsaved = mutableSetOf<String>()
    private var unsavedWatch: Job? = null

    /** Partials this process has already picked up. Any other came through a restart, and so maybe a power cut. */
    private val durableParts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var started: Job? = null
    private var api: OfflineApi? = null
    private var sessionOwner: OfflineSessionOwner? = null
    private var lastAccountKeys = emptySet<String>()
    private val unreachable = mutableMapOf<String, OfflineUnreachable>()
    private val freeAtDenial = mutableMapOf<String, Long>()
    private var storageWatch: Job? = null
    private var revocationWatch: Job? = null

    @Volatile
    private var lastNetwork: OfflineNetwork? = null

    val libraries: StateFlow<Map<String, OfflineLibrary>> = mutableLibraries

    private suspend fun api(): OfflineApi = api ?: OfflineApi(apiCalls, identity()).also { api = it }

    private suspend fun sessions(): OfflineSessionOwner = sessionOwner ?: OfflineSessionOwner(
        files.sessions(),
        api()
    ) { identity().deviceId }.also { sessionOwner = it }

    /** Runs once per process. Everything here works with the network off. */
    fun start(): Job {
        started?.let { return it }
        val job = scope.launch {
            emptyTrashSoon()
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

    /** The next booked job run. Starting is once per process, and a warm process still has rows nothing else will move. */
    suspend fun wake() {
        start().join()
        networkChanged()
    }

    /** The scheduler's network may differ from the default (VPN, simultaneous Wi-Fi/cellular). */
    suspend fun useJobNetwork(owner: Any, network: Network?) {
        mutex.withLock {
            if (jobNetwork.network != network) suspendTransfersLocked()
            jobNetwork.owner = owner
            jobNetwork.network = network
        }
    }

    fun releaseJobNetwork(owner: Any) {
        scope.launch {
            mutex.withLock {
                // A late stop from the previous run cannot clear the new run's route or cancel its IO.
                if (jobNetwork.owner !== owner) return@withLock
                suspendTransfersLocked()
                jobNetwork.owner = null
                jobNetwork.network = null
            }
        }
    }

    /** However much is in there, deleting it holds nobody up: a cold start offline waits on nothing here. */
    private fun emptyTrashSoon() {
        scope.launch { trashCleaner(files) }
    }

    /** Sign-out and removal: bytes go now, without waiting for any server (OFF-09). */
    private suspend fun observeAccounts() {
        accounts.snapshots.collect { snapshot ->
            val keys = snapshot.accounts.map(Account::key).toSet()
            mutex.withLock {
                val gone = lastAccountKeys - keys
                lastAccountKeys = keys
                gone.forEach { purgeLocked(it) }
                synchronized(progressStores) { signedOut.removeAll(keys) }
                (keys - mutableLibraries.value.keys).forEach { loadLocked(it) }
            }
            runCatching { sessions().endAllExcept(snapshot.accounts.map { it.server.id to it.user.id }.toSet()) }
            watchRevocations()
            if (keys.isNotEmpty() || lastAccountKeys.isEmpty()) mutex.withLock { pumpLocked() }
        }
    }

    private fun purgeOrphansLocked(keys: Set<String>) {
        val owned = keys.map { files.accountDir(it).name }.toSet()
        files.accountDirs().filterNot { it.name in owned }.forEach { files.moveToTrash(it) }
        emptyTrashSoon()
    }

    private fun purgeLocked(key: String) {
        runners.keys.filter { it.startsWith("$key|") }.forEach { runnerKey -> runners.remove(runnerKey)?.cancel() }
        synchronized(progressStores) {
            signedOut += key
            progressStores.remove(key)?.retire()
        }
        mutableLibraries.update { it - key }
        // The rename hides everything in one step; a failed slow delete stays private in trash and retries at next start.
        files.moveToTrash(files.accountDir(key))
        emptyTrashSoon()
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

    /**
     * A full disk can refuse this write, and the viewer's way out of a full disk is Delete, which has to get as far as
     * removing bytes. So a refused write leaves the state in memory and in effect, and is tried again. A restart before it
     * lands falls back on the last manifest and reconcile, which recounts from what's really on disk.
     */
    private fun saveLocked(key: String, manifest: OfflineManifest) {
        try {
            files.manifest(key).writeAtomically(offlineEncodeManifest(manifest))
            unsaved -= key
        } catch (e: IOException) {
            manifestNotSavedLocked(key, e)
        } catch (e: IllegalStateException) {
            manifestNotSavedLocked(key, e)
        }
        mutableLibraries.update { all -> all + (key to (all[key] ?: OfflineLibrary()).copy(manifest = manifest, recoverable = false)) }
    }

    private fun manifestNotSavedLocked(key: String, error: Exception) {
        android.util.Log.w(OFFLINE_LOG, "Manifest not saved: ${error.javaClass.simpleName}")
        unsaved += key
        if (unsavedWatch?.isActive == true) return
        unsavedWatch = scope.launch {
            while (true) {
                delay(storageRecheckMs)
                val more = mutex.withLock {
                    unsaved.toList().forEach { waiting ->
                        val current = library(waiting)?.manifest
                        if (current != null) saveLocked(waiting, current) else unsaved.remove(waiting)
                    }
                    unsaved.isNotEmpty()
                }
                if (!more) return@launch
            }
        }
    }

    /** Called from screens, whose scopes end the app on an exception: whatever goes wrong here stays here. */
    private suspend fun <T> contained(otherwise: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(OFFLINE_LOG, "Offline action ended: ${e.javaClass.simpleName}")
        otherwise
    }

    /** In memory only: reconcile recounts bytes from disk, so a kill can't strand them. */
    private fun setBudgetLocked(key: String, budget: OfflineBudget) {
        mutableLibraries.update { all ->
            val library = all[key] ?: return@update all
            all + (key to library.copy(manifest = library.manifest.copy(budget = budget)))
        }
    }

    private fun account(key: String): Account? = accounts.snapshots.value.accounts.firstOrNull { it.key == key }

    fun progressStore(account: Account): OfflineProgressStore = synchronized(progressStores) {
        if (account.key in signedOut) {
            OfflineProgressStore(files.progress(account.key), account).apply { retire() }
        } else {
            // The app's current record beats the caller's copy, which a screen may have held since before a re-sign-in.
            val newest = account(account.key) ?: account
            progressStores.getOrPut(account.key) { OfflineProgressStore(files.progress(account.key), newest) }.also { it.account = newest }
        }
    }

    fun resourceFile(key: String, entry: OfflineEntry, resource: OfflineResource): File = files.resource(key, entry.id, resource.path)

    // Downloads

    suspend fun enqueue(account: Account, item: OfflineApiItem, choice: OfflineChoice, warning: String? = null): Boolean =
        contained(false) { mutex.withLock { enqueueLocked(account, item, choice, warning) } }

    private fun enqueueLocked(account: Account, item: OfflineApiItem, choice: OfflineChoice, warning: String?): Boolean {
        val library = library(account.key) ?: run {
            loadLocked(account.key)
            library(account.key)
        } ?: return false
        if (library.recoverable) return false
        val id = offlineEntryId(item.id, choice.preset)
        // A second request for the same media is the same download (OFF-01: deduplicated).
        if (library.manifest.entries.any { it.id == id }) return true
        val entry = offlineBuildEntry(item, choice, library.manifest.nextSequence, warning)
        val manifest = library.manifest.copy(entries = library.manifest.entries + entry, nextSequence = library.manifest.nextSequence + 1)
        saveLocked(account.key, manifest)
        item.userData?.let { userData(account, item.id, it.core()) }
        pumpLocked()
        return true
    }

    suspend fun pause(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("pause"))

    suspend fun resume(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("resume"))

    suspend fun retry(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("retry"))

    suspend fun redownload(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("redownload"))

    suspend fun delete(key: String, entryId: String) = job(key, entryId, OfflineJobEvent("delete"))

    suspend fun setCellular(key: String, entryId: String, allowed: Boolean) =
        job(key, entryId, OfflineJobEvent("set-cellular", cellularAllowed = allowed))

    suspend fun deleteMany(key: String, entryIds: List<String>) = entryIds.forEach { delete(key, it) }

    suspend fun setCap(key: String, capBytes: Long) = contained(Unit) { mutex.withLock { setCapLocked(key, capBytes) } }

    private fun setCapLocked(key: String, capBytes: Long) {
        val manifest = library(key)?.manifest ?: return
        val newest = manifest.entries.sortedByDescending { it.createdSequence }.map(OfflineEntry::id)
        val result = manifest.budget.setCap(capBytes, newest)
        saveLocked(key, manifest.copy(budget = result.budget))
        result.pauseJobs.forEach { eventLocked(key, it, OfflineJobEvent("pause", reason = "cap")) }
        storageMayHaveFreedLocked(key)
        pumpLocked()
    }

    suspend fun setWifiOnly(key: String, wifiOnly: Boolean) = contained<Unit>(Unit) {
        mutex.withLock {
            library(key)?.manifest?.let { manifest ->
                saveLocked(key, manifest.copy(wifiOnly = wifiOnly))
                applyNetworkLocked()
            }
        }
    }

    /** The viewer typed the password this server needs because Quick Connect is off. It's not kept. */
    suspend fun providePassword(account: Account, password: String): Boolean = contained(false) { passwordGiven(account, password) }

    private suspend fun passwordGiven(account: Account, password: String): Boolean {
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
    suspend fun refresh() = contained(Unit) { mutex.withLock { refreshLocked() } }

    private fun refreshLocked() {
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

    private suspend fun job(key: String, entryId: String, event: OfflineJobEvent) = contained(Unit) {
        mutex.withLock {
            eventLocked(key, entryId, event)
            if (event.op == "delete") storageMayHaveFreedLocked(key)
            pumpLocked()
        }
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
                "stop-transfer" -> runners[runnerKey(key, entryId)]?.cancel()
                "discard-partial" -> discardPartialsLocked(key, entryId)
                "delete-owned" -> deleteOwnedLocked(key, entryId)
                "start-attempt" -> startAttemptLocked(key, entryId, next?.attempt ?: 0)
            }
        }
        publishForeground()
        return true
    }

    /**
     * Two attempts on one partial would write over each other, so this one waits for every earlier one to end, which being
     * told to stop makes quick (withCallCancel). It waits for all of them: one cancelled before it ran is gone at once, and
     * can't stand in for an older one that is still blocked.
     */
    private fun startAttemptLocked(key: String, entryId: String, attempt: Long) {
        val slot = runnerKey(key, entryId)
        val earlier = liveAttempts.filterValues { it == slot }.keys.toList()
        val job = scope.launch { runAttempt(key, entryId, attempt, earlier) }
        liveAttempts[job] = slot
        job.invokeOnCompletion { liveAttempts.remove(job) }
        runners[slot] = job
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
        // The flag asked on behalf of a download that needed the password; with the last of those gone nothing is asking.
        mutableLibraries.update { all ->
            val library = all[key]
            val stillAsked = library?.manifest?.entries.orEmpty().any {
                it.job.phase == OfflinePhase.failed && it.job.failure == "needs-password"
            }
            if (library == null || !library.needsPassword || stillAsked) all else all + (key to library.copy(needsPassword = false))
        }
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
        foreground.update(offlineForegroundTitles(mutableLibraries.value, accounts.snapshots.value.currentKey))
        foreground.unfinished(
            mutableLibraries.value.values.any { library ->
                library.manifest.entries.any { it.job.phase in OFFLINE_UNFINISHED }
            }
        )
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
        watchRevocations()
    }

    /**
     * A signed-out viewer's download set must end even when the server was off at sign-out, and a server coming back
     * tells nobody. So while one is owed, keep asking.
     */
    private fun watchRevocations() {
        if (revocationWatch?.isActive == true) return
        revocationWatch = scope.launch {
            while (runCatching { sessions().revocationsPending() }.getOrDefault(false)) {
                delay(revocationRecheckMs)
                if (networkState() != OfflineNetwork.none) runCatching { sessions().reconnect() }
            }
        }
    }

    fun foregroundTimedOut() {
        scope.launch { mutex.withLock { suspendTransfersLocked() } }
    }

    private fun suspendTransfersLocked() {
        lastAccountKeys.forEach { key ->
            library(key)?.manifest?.entries?.filter {
                it.job.phase == OfflinePhase.preparing ||
                    it.job.phase == OfflinePhase.transferring ||
                    it.job.phase == OfflinePhase.verifying
            }?.forEach { eventLocked(key, it.id, OfflineJobEvent("network-lost")) }
        }
    }

    // One attempt

    private suspend fun runAttempt(key: String, entryId: String, attempt: Long, earlier: List<Job>) {
        val self = coroutineContext[Job]
        val session = UUID.randomUUID().toString().replace("-", "")
        var cleanup: (suspend () -> Unit)? = null
        try {
            earlier.forEach { it.join() }
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
                    if (token.reason == "network") networkLost(key, entryId, attempt) else fail(key, entryId, attempt, token.reason)
                    return
                }
            }
            cleanup = { api.cleanup(current.server.url, offlineCleanupRequest(set.deviceId, session), set.deviceId, set.token) }
            val fresh = runCatching { api.item(current, entryOrNull(key, entryId)?.itemId ?: return) }.getOrElse { error ->
                when {
                    error is CancellationException -> throw error
                    error is OfflineApiException && error.status in 401..404 -> fail(key, entryId, attempt, "denied")
                    else -> networkLost(key, entryId, attempt)
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
            networkLost(key, entryId, attempt)
        } catch (e: Exception) {
            // Whatever it was ends this one attempt where the viewer can see it and press Retry.
            android.util.Log.w(OFFLINE_LOG, "Offline attempt ended: ${e.javaClass.simpleName}")
            fail(key, entryId, attempt, "internal")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                cleanup?.invoke()
                mutex.withLock {
                    // A newer attempt may be waiting on this one to end: it's in charge now, and has reserved nothing yet.
                    if (runners[runnerKey(key, entryId)] === self) runners.remove(runnerKey(key, entryId))
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
    private suspend fun networkLost(key: String, entryId: String, attempt: Long) {
        mutex.withLock {
            // network-lost isn't bound to an attempt in the reducer, because it's also a fact about the device.
            if (entryOrNull(key, entryId)?.job?.attempt != attempt) return@withLock
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

        override suspend fun release(bytes: Long) = mutex.withLock {
            library(key)?.manifest?.budget?.release(entryId)?.let { setBudgetLocked(key, it.budget) } ?: Unit
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
        if (adoptedFinal(key, entryId, entry, resource, final)) return true
        val url = api.url(serverUrl, entry.requestFor(resource, deviceId, session))
        val transfer = OfflineResourceTransfer(http, ledger(key, entryId))
        val spaced = entry.needsIndex(resource)
        var tries = 0
        while (true) {
            val stored = entryOrNull(key, entryId)?.resources?.firstOrNull { it.id == resource.id }?.validator
            val trusted: (Long) -> Long = if (part.path in durableParts) ({ it }) else restartTrustedBytes
            val result = transfer.run(part, url, headers, stored, entry.sourceETag, spaced, trusted) { validator ->
                mutex.withLock { updateResourceLocked(key, entryId, resource.id) { it.copy(validator = validator) } }
            }
            durableParts += part.path
            when (result) {
                is OfflineTransferResult.Complete -> {
                    if (spaced && !writeFmp4Index(part)) {
                        fail(key, entryId, attempt, "not-indexable")
                        return false
                    }
                    // The name comes first: from here a kill costs a hash, not a conversion the server may have taken hours over.
                    if (!part.renameTo(final)) throw IOException("Could not commit ${resource.id}")
                    val sha = final.sha256Hex()
                    val bytes = final.length()
                    mutex.withLock {
                        updateResourceLocked(key, entryId, resource.id) {
                            it.copy(complete = true, bytes = bytes, sha256 = sha, validator = result.validator ?: it.validator)
                        }
                    }
                    return true
                }
                OfflineTransferResult.NoBudget -> {
                    mutex.withLock {
                        if (entryOrNull(key, entryId)?.job?.attempt != attempt) return false
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
                        networkLost(key, entryId, attempt)
                        return false
                    }
                    if (tries >= OFFLINE_RETRIES) {
                        // A server that stopped answering comes back on its own. One that answers and keeps dropping won't.
                        if (serverAnswers(key, entry.itemId, api)) {
                            fail(key, entryId, attempt, result.reason)
                        } else {
                            networkLost(key, entryId, attempt)
                        }
                        return false
                    }
                    delay(retryDelayMs(tries))
                }
            }
        }
    }

    /**
     * A kill between the rename and the save leaves a finished file the manifest doesn't know about. It's taken up when it
     * can be shown whole, and verify still has the last word; otherwise it goes, so it can't count against the cap twice.
     */
    private suspend fun adoptedFinal(key: String, entryId: String, entry: OfflineEntry, resource: OfflineResource, final: File): Boolean {
        if (!final.exists()) return false
        val whole = if (entry.needsIndex(resource)) {
            fmp4MeasureTicks(final)?.let { !offlineConversionShort(entry.runtimeTicks, it) } ?: false
        } else {
            resource.validator?.totalBytes == final.length()
        }
        if (!whole) {
            val bytes = final.length()
            if (final.delete()) ledger(key, entryId).discard(bytes)
            return false
        }
        val sha = final.sha256Hex()
        val bytes = final.length()
        mutex.withLock { updateResourceLocked(key, entryId, resource.id) { it.copy(complete = true, bytes = bytes, sha256 = sha) } }
        return true
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
            val converted = entry.needsIndex(resource)
            offlineVerify(
                OfflineVerifyInput(
                    file.length(),
                    if (converted) resource.bytes else resource.validator?.totalBytes ?: resource.bytes,
                    file.sha256Hex(),
                    resource.sha256,
                    entry.sourceETag,
                    sourceNow,
                    if (converted) entry.runtimeTicks else null,
                    if (converted) fmp4MeasureTicks(file) else null
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

    /** Nothing waits on this: a viewer leaving the player doesn't wait for a read, and a title with no download reads nothing. */
    fun onlineViewingEnded(account: Account, itemId: String, read: suspend () -> OfflineUserData?): Job =
        synchronized(onlineViewingReadLock) {
            // Register in call order, then dispatch before touching disk or the network. An UNDISPATCHED IO coroutine
            // can still be on the player's Main thread; withContext(IO) sees the same context and won't move it.
            // Joining the previous job also keeps Stopped's read from arriving after Played's and restoring 99%.
            val previous = lastOnlineViewingRead
            scope.launch {
                previous?.join()
                if (ready()[account.key]?.manifest?.entries?.any { it.itemId == itemId } != true) {
                    return@launch
                }
                try {
                    val data = read()
                    data?.let { progressStore(account).onlineViewingEnded(itemId, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    android.util.Log.w("PictureshowOffline", "Position after online viewing not read: ${e.javaClass.simpleName}")
                }
            }.also { lastOnlineViewingRead = it }
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

/**
 * What the ongoing notification may say. Everyone's running downloads count, but on a shared device only the signed-in
 * viewer's are named: the rest are blank, after theirs. No viewer signed in, no names.
 */
internal fun offlineForegroundTitles(libraries: Map<String, OfflineLibrary>, currentKey: String?): List<String> {
    fun running(library: OfflineLibrary) = library.manifest.entries.filter {
        it.job.phase == OfflinePhase.preparing || it.job.phase == OfflinePhase.transferring || it.job.phase == OfflinePhase.verifying
    }
    val mine = libraries[currentKey]?.let(::running).orEmpty().map { it.labels.title }
    val others = libraries.filterKeys { it != currentKey }.values.flatMap(::running).map { "" }
    return mine + others
}

private data class OfflineUnreachable(val tries: Int, val untilMs: Long)

internal fun measuredNetwork(context: Context, network: Network? = null): OfflineNetwork {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return OfflineNetwork.none
    val capabilities = (network ?: manager.activeNetwork)?.let(manager::getNetworkCapabilities) ?: return OfflineNetwork.none
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return OfflineNetwork.none
    val free = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    return if (free) OfflineNetwork.unmetered else OfflineNetwork.metered
}
