/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@androidx.annotation.ChecksSdkIntAtLeast(api = 26)
internal fun supportsWatchNext(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 &&
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
    context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

@RequiresApi(26)
internal class AndroidWatchProvider(private val context: Context) : WatchProvider {
    private val resolver = context.contentResolver

    // Public WatchNextProgram getters inherit @RestrictTo base classes in 1.1.0 (b/138150076).
    @SuppressLint("RestrictedApi")
    override fun rows(): List<WatchRow> = buildList {
        // TvProvider scopes ordinary callers to their own package; also filter explicitly for test/OEM providers.
        val cursor = checkNotNull(resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null))
        cursor.use {
            while (it.moveToNext()) {
                val program = WatchNextProgram.fromCursor(it)
                if (program.packageName == context.packageName) {
                    add(
                        WatchRow(
                            program.id,
                            program.internalProviderId.orEmpty(),
                            program.isBrowsable,
                            program.lastPlaybackPositionMillis,
                            program.lastEngagementTimeUtcMillis
                        )
                    )
                }
            }
        }
    }
    override fun insert(program: WatchNextProgram) {
        checkNotNull(resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues()))
    }
    override fun update(id: Long, program: WatchNextProgram) {
        val values = program.toContentValues().apply {
            // An omitted duration on an update would retain the previous (possibly malformed) total.
            if (!containsKey(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS)) {
                putNull(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS)
            }
        }
        resolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null)
    }
    override fun delete(id: Long) {
        resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
    }
}

internal class StoredWatchDismissals(context: Context) : WatchDismissals {
    private val preferences = context.getSharedPreferences("watch_next_dismissed", Context.MODE_PRIVATE)
    override fun contains(key: String) = preferences.getBoolean(key, false)

    @SuppressLint("UseKtx")
    override fun retainAccounts(prefixes: Set<String>) {
        val obsolete = preferences.all.keys.filter { key -> prefixes.none { key.startsWith(it) } }
        if (obsolete.isEmpty()) return
        val editor = preferences.edit()
        obsolete.forEach(editor::remove)
        check(editor.commit())
    }

    // KTX edit returns Unit; this write must detect commit failure before deleting the provider row.
    @SuppressLint("UseKtx")
    override fun add(key: String) {
        // Commit before deleting the provider row: a process death must not forget the user's dismissal.
        // KTX edit returns Unit; retain the editor so a failed disk commit is detected.
        val editor = preferences.edit()
        editor.putBoolean(key, true)
        check(editor.commit())
    }
}

/** One conflated refresh actor; cancellation and account revalidation prevent stale network results from republishing. */
internal class WatchNextCoordinator(
    private val application: Application,
    private val accounts: AccountStore,
    private val catalog: CatalogService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val providerFactory: () -> WatchProvider? = {
        if (supportsWatchNext(application)) AndroidWatchProvider(application) else null
    },
    private val dismissals: WatchDismissals = StoredWatchDismissals(application)
) : Application.ActivityLifecycleCallbacks {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun refresh() {
        requests.trySend(Unit)
    }

    fun start() {
        val provider = providerFactory() ?: return
        application.registerActivityLifecycleCallbacks(this)
        val sync = WatchNextSync(provider, dismissals)
        scope.launch {
            try {
                accounts.load()
                merge(
                    accounts.snapshots.map { snapshot -> snapshot.accounts to snapshot.currentKey }.distinctUntilChanged().map { Unit },
                    requests.receiveAsFlow()
                ).collectLatest {
                    try {
                        val account = accounts.snapshots.value.currentAccount?.takeUnless { it.expired }
                        // Clear removed viewers' launcher dismissal history as well as published rows.
                        val prefixes = accounts.snapshots.value.accounts.map { "${watchAccountKey(it)}:" }.toSet()
                        sync.retainSavedAccounts(prefixes)
                        sync.retainAccount(account)
                        dismissals.retainAccounts(prefixes)
                        if (account == null) return@collectLatest
                        val resume = catalog.resume(account)
                        val next = catalog.nextUp(account)
                        // AccountStore can change while either request is in flight, including token expiration.
                        if (accounts.snapshots.value.currentAccount != account) return@collectLatest
                        val failure = listOf(resume, next).filterIsInstance<ClientResult.Failure>().firstOrNull()
                        if (failure != null) {
                            if (failure.error.kind in setOf(ErrorKind.EXPIRED, ErrorKind.INVALID_CREDENTIALS)) {
                                accounts.expire(account.key, account.accessToken)
                            }
                            return@collectLatest
                        }
                        sync.replace(
                            account,
                            (resume as ClientResult.Success).value.items,
                            (next as ClientResult.Success).value.items,
                            System.currentTimeMillis()
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Unsupported OEM providers and temporary network/storage failures must not crash startup.
                        android.util.Log.w("PictureshowWatchNext", "Refresh unavailable; will retry on the next app event")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.util.Log.w("PictureshowWatchNext", "Could not load saved accounts for launcher refresh")
            }
        }
    }

    override fun onActivityResumed(activity: Activity) = refresh()
    override fun onActivityStopped(activity: Activity) = refresh()
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
