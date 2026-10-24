/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.displayRuntimeMillis
import app.pictureshow.core.imageUrl
import java.security.MessageDigest

internal data class WatchRow(val id: Long, val key: String, val browsable: Boolean, val positionMs: Int = 0, val engagementTime: Long = 0)

internal interface WatchProvider {
    fun rows(): List<WatchRow>
    fun insert(program: WatchNextProgram)
    fun update(id: Long, program: WatchNextProgram)
    fun delete(id: Long)
}

internal interface WatchDismissals {
    fun contains(key: String): Boolean
    fun add(key: String)
    fun retainAccounts(prefixes: Set<String>) = Unit
}

/** The provider's internal identity contains neither raw account IDs nor credentials. */
internal fun watchAccountKey(account: Account): String = MessageDigest.getInstance("SHA-256")
    .digest("${account.server.url.trimEnd('/')}\n${account.key}".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun watchItemKey(account: Account, item: MediaItem) = "${watchAccountKey(account)}:${item.id}"
private fun millis(ticks: Long): Int = (ticks.coerceAtLeast(0) / 10_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

// Public WatchNextProgram API inherits @RestrictTo base classes in tvprovider 1.1.0.
// Android's documented builder usage; annotation issue: https://issuetracker.google.com/issues/138150076
@SuppressLint("RestrictedApi")
internal fun watchProgram(account: Account, item: MediaItem, next: Boolean, time: Long): WatchNextProgram {
    val duration = displayRuntimeMillis((item.runTimeTicks ?: 0) / 10_000)?.toInt()
    val position = millis(item.userData.playbackPositionTicks).let { if (duration != null) it.coerceAtMost(duration) else it }
    val uri = Uri.Builder().scheme("pictureshow").authority("play").appendPath(item.id)
        .appendQueryParameter("positionMs", position.toString()).build()
    val episode = item.type == "Episode"
    val watchType = if (next) {
        TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
    } else {
        TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
    }
    return WatchNextProgram.Builder()
        .setInternalProviderId(watchItemKey(account, item))
        .setContentId(item.id)
        .setType(if (episode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
        .setWatchNextType(watchType)
        .setTitle(if (episode) item.seriesName ?: item.name else item.name)
        .setEpisodeTitle(if (episode) item.name else null)
        .apply {
            item.parentIndexNumber?.let { setSeasonNumber(it.toString(), it) }
            item.indexNumber?.let { setEpisodeNumber(it.toString(), it) }
            imageUrl(account.server, item, 640, 360, wide = true)?.let { setPosterArtUri(it.toUri()) }
        }
        .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9)
        .apply { duration?.let { setDurationMillis(it) } }
        .setLastPlaybackPositionMillis(position)
        .setLastEngagementTimeUtcMillis(time)
        .setIntent(Intent(Intent.ACTION_VIEW, uri).setPackage("app.pictureshow"))
        .build()
}

/** Called serially on IO. Never writes browsable; only the launcher controls that field. */
internal class WatchNextSync(private val provider: WatchProvider, private val dismissed: WatchDismissals) {
    /** Removed viewers have no dismissal history to preserve; delete their rows before any prefs write. */
    fun retainSavedAccounts(prefixes: Set<String>) {
        provider.rows().filter { row -> prefixes.none { row.key.startsWith(it) } }.forEach { provider.delete(it.id) }
    }

    fun retainAccount(account: Account?) {
        val prefix = account?.let { "${watchAccountKey(it)}:" }
        provider.rows().forEach { row ->
            if (!row.browsable) dismissed.add(row.key)
            if (prefix == null || !row.key.startsWith(prefix) || !row.browsable) provider.delete(row.id)
        }
    }

    // The public lastPlaybackPositionMillis getter is inherited from restricted BasePreviewProgram (b/138150076).
    @SuppressLint("RestrictedApi")
    fun replace(account: Account?, resume: List<MediaItem>, next: List<MediaItem>, time: Long) {
        retainAccount(account)
        if (account == null) return
        val wanted = (resume.map { it to false } + next.map { it to true })
            .filter { (item, _) ->
                !item.userData.played &&
                    !item.isFolder &&
                    item.type in setOf("Movie", "Episode") &&
                    item.id.matches(Regex("[a-fA-F0-9]{32}"))
            }.distinctBy { it.first.id }.associate { (item, upcoming) ->
                watchItemKey(account, item) to watchProgram(account, item, upcoming, time)
            }.filterKeys { !dismissed.contains(it) }
        val rows = provider.rows()
        rows.filter { it.key !in wanted }.forEach { provider.delete(it.id) }
        wanted.forEach { (key, program) ->
            val existing = rows.filter { it.key == key }
            val row = existing.firstOrNull()
            existing.drop(1).forEach { provider.delete(it.id) }
            if (row == null) {
                provider.insert(program)
            } else if (!row.browsable) {
                // Recheck after the first read: a launcher dismissal may arrive during reconciliation.
                dismissed.add(key)
                provider.delete(row.id)
            } else {
                val updated = if (row.positionMs == program.lastPlaybackPositionMillis && row.engagementTime > 0) {
                    WatchNextProgram.Builder(program).setLastEngagementTimeUtcMillis(row.engagementTime).build()
                } else {
                    program
                }
                provider.update(row.id, updated)
            }
        }
    }
}
