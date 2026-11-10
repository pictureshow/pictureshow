/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES
import app.pictureshow.core.offline.OFFLINE_SYNC_BYTES
import app.pictureshow.core.offline.OfflineResponse
import app.pictureshow.core.offline.OfflineValidator
import app.pictureshow.core.offline.offlineBodyEnd
import app.pictureshow.core.offline.offlineRangeHeader
import app.pictureshow.core.offline.offlineResumeDecision
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal class OfflineHttpResponse(
    val status: Int,
    val header: (String) -> String?,
    val body: InputStream?,
    val contentLength: Long?,
    private val closer: () -> Unit
) : AutoCloseable {
    override fun close() = closer()
}

/** One request at a time per resource. [onCall] gets what ends the blocking call from another thread, before it blocks. */
internal fun interface OfflineHttp {
    fun open(url: String, headers: Map<String, String>, range: String?, onCall: (cancel: () -> Unit) -> Unit): OfflineHttpResponse
}

/**
 * A blocking call or read doesn't notice a cancelled coroutine and can sit for the whole read timeout. This ends it the
 * moment the caller is cancelled, however late the call registers.
 */
internal suspend fun <T> withCallCancel(block: suspend CoroutineScope.((cancel: () -> Unit) -> Unit) -> T): T = coroutineScope {
    val registered = AtomicReference<() -> Unit>()
    val cancelled = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            cancelled.set(true)
            registered.get()?.invoke()
        }
    }
    try {
        block { cancel ->
            registered.set(cancel)
            if (cancelled.get()) cancel()
        }
    } finally {
        // Ended normally: nothing left to break, and this scope can't finish while the watcher waits.
        registered.set(null)
        watcher.cancel()
    }
}

/** The byte ledger the transfer must clear with before every write. */
internal interface OfflineLedger {
    /** False: the reason is stored and the transfer stops before writing. */
    suspend fun reserve(bytes: Long): Boolean
    suspend fun commit(bytes: Long)
    suspend fun discard(bytes: Long)

    /** Hands back what a finished or dropped body didn't use, so a retry in the same attempt doesn't count it twice. */
    suspend fun release(bytes: Long)
    suspend fun progress(received: Long, total: Long?)
}

internal sealed interface OfflineTransferResult {
    data class Complete(val validator: OfflineValidator?, val bytes: Long) : OfflineTransferResult

    /** The ledger said no. The partial stays. */
    data object NoBudget : OfflineTransferResult

    data class Failed(val reason: String) : OfflineTransferResult

    /** Worth trying again: the connection dropped or the server restarted the body. */
    data class Interrupted(val reason: String) : OfflineTransferResult
}

internal const val OFFLINE_CHUNK_BYTES = 256 * 1024
internal const val OFFLINE_UNKNOWN_RESERVE_BYTES = 8L * 1024 * 1024

internal class OfflineResourceTransfer(
    private val http: OfflineHttp,
    private val ledger: OfflineLedger,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() }
) {
    /**
     * @param spaceFmp4 this is a server conversion: leave room for the index that makes it seekable.
     * @param sourceETag the source version seen before this attempt, compared against the stored one.
     * @param trustedBytes how much of a partial of this length to keep. After a restart only what came before the last sync
     * boundary can be trusted: a power cut may have left a tail of the right length and the wrong bytes, and verify's hash
     * is taken from the same file.
     */
    suspend fun run(
        target: File,
        url: String,
        headers: Map<String, String>,
        stored: OfflineValidator?,
        sourceETag: String?,
        spaceFmp4: Boolean,
        trustedBytes: (Long) -> Long = { it },
        onValidator: suspend (OfflineValidator?) -> Unit = {}
    ): OfflineTransferResult = withContext(Dispatchers.IO) {
        withCallCancel { onCall -> attempt(target, url, headers, stored, sourceETag, spaceFmp4, trustedBytes, onValidator, onCall) }
    }

    private suspend fun attempt(
        target: File,
        url: String,
        headers: Map<String, String>,
        stored: OfflineValidator?,
        sourceETag: String?,
        spaceFmp4: Boolean,
        trustedBytes: (Long) -> Long,
        onValidator: suspend (OfflineValidator?) -> Unit,
        onCall: (cancel: () -> Unit) -> Unit
    ): OfflineTransferResult {
        target.parentFile?.mkdirs()
        // A conversion can't resume; its partial is only a leftover. Its bytes leave the ledger before it starts over.
        var partial = target.length()
        if (spaceFmp4 && partial > 0) {
            ledger.discard(partial)
            target.delete()
            partial = 0
        }
        if (!spaceFmp4 && partial > 0) {
            val keep = trustedBytes(partial)
            if (keep < partial) {
                RandomAccessFile(target, "rw").use { it.setLength(keep) }
                ledger.discard(partial - keep)
                partial = keep
            }
        }
        val range = if (spaceFmp4) null else offlineRangeHeader(partial, stored)
        if (range == null && partial > 0) {
            ledger.discard(partial)
            target.delete()
            partial = 0
        }
        val response = try {
            http.open(url, headers, range, onCall)
        } catch (e: IOException) {
            return OfflineTransferResult.Interrupted("network")
        }
        return response.use {
            if (response.status == 401 || response.status == 403) return OfflineTransferResult.Failed("unauthorized")
            // A Wi-Fi sign-in page or proxy, not media. Saving it would cost the partial and pass off a page as a file.
            if (response.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true)) {
                return OfflineTransferResult.Interrupted("network")
            }
            val decision = offlineResumeDecision(
                partial,
                stored,
                OfflineResponse(
                    response.status,
                    response.header("Content-Range"),
                    response.header("ETag"),
                    response.header("Last-Modified"),
                    response.contentLength,
                    response.header("Accept-Ranges"),
                    sourceETag
                )
            )
            when (decision.action) {
                "fail" -> OfflineTransferResult.Failed(decision.reason ?: "server")
                "complete" -> OfflineTransferResult.Complete(decision.validator ?: stored, partial)
                "restart" -> {
                    ledger.discard(partial)
                    target.delete()
                    // A 200 is a whole body we can take from zero. Anything else needs a fresh request.
                    if (response.status == 200 && response.body != null) {
                        copy(response, target, 0, decision.totalBytes ?: response.contentLength, decision.validator, spaceFmp4, onValidator)
                    } else {
                        OfflineTransferResult.Interrupted(decision.reason ?: "restart")
                    }
                }
                else -> copy(response, target, decision.offset, decision.totalBytes, decision.validator, spaceFmp4, onValidator)
            }
        }
    }

    private suspend fun copy(
        response: OfflineHttpResponse,
        target: File,
        offset: Long,
        total: Long?,
        validator: OfflineValidator?,
        spaceFmp4: Boolean,
        onValidator: suspend (OfflineValidator?) -> Unit
    ): OfflineTransferResult {
        val body = response.body ?: return OfflineTransferResult.Failed("no-body")
        onValidator(validator)
        var received = offset
        var reserved = 0L
        if (total != null && total > offset) {
            if (!ledger.reserve(total - offset)) return OfflineTransferResult.NoBudget
            reserved = total - offset
        }
        val buffer = ByteArray(OFFLINE_CHUNK_BYTES)
        var cleanEof = false
        try {
            FileOutputStream(target, offset > 0).use { file ->
                val spacer = if (spaceFmp4) Fmp4Spacer(file) else null
                // Everything before a boundary reaches the disk before anything is written past it (offlineDurableOffset).
                var position = offset
                var nextSync = (offset / OFFLINE_SYNC_BYTES + 1) * OFFLINE_SYNC_BYTES
                fun writeSynced(length: Int) {
                    var at = 0
                    while (at < length) {
                        val take = minOf((length - at).toLong(), nextSync - position).toInt()
                        file.write(buffer, at, take)
                        at += take
                        position += take
                        if (position == nextSync) {
                            sync(file)
                            nextSync += OFFLINE_SYNC_BYTES
                        }
                    }
                }
                suspend fun room(bytes: Long): Boolean {
                    if (reserved >= bytes) return true
                    val want = maxOf(bytes - reserved, OFFLINE_UNKNOWN_RESERVE_BYTES)
                    if (!ledger.reserve(want)) return false
                    reserved += want
                    return true
                }
                try {
                    if (spacer != null &&
                        !room(OFFLINE_CHUNK_BYTES.toLong() + OFFLINE_INDEX_PLACEHOLDER_BYTES)
                    ) {
                        return OfflineTransferResult.NoBudget
                    }
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = body.read(buffer)
                        if (read < 0) {
                            cleanEof = true
                            break
                        }
                        // Bytes already in memory are written only after the ledger clears them (OFF-03e).
                        val extra = if (spacer != null && spacer.inserted == 0L) OFFLINE_INDEX_PLACEHOLDER_BYTES.toLong() else 0L
                        if (!room(read + extra)) return OfflineTransferResult.NoBudget
                        val before = spacer?.inserted ?: 0L
                        if (spacer != null) spacer.write(buffer, read) else writeSynced(read)
                        val written = read + (spacer?.inserted ?: 0L) - before
                        ledger.commit(written)
                        reserved -= written
                        received += read
                        ledger.progress(received, total)
                    }
                    sync(file)
                } catch (e: IOException) {
                    runCatching { sync(file) }
                    if (isDiskFull(e)) return OfflineTransferResult.NoBudget
                    return OfflineTransferResult.Interrupted("network")
                }
            }
        } finally {
            // Commits moved the rest. A drop leaves this much promised to nothing; the next try asks again.
            if (reserved > 0) ledger.release(reserved)
        }
        return when (offlineBodyEnd(received, total, cleanEof).action) {
            "complete", "unknown-length-complete" -> OfflineTransferResult.Complete(validator, target.length())
            "overrun" -> {
                ledger.discard(target.length())
                target.delete()
                OfflineTransferResult.Interrupted("overrun")
            }
            else -> OfflineTransferResult.Interrupted("truncated")
        }
    }
}

private fun isDiskFull(e: IOException): Boolean = e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space left")

/** OkHttp drops Authorization on a cross-origin redirect by itself. */
internal fun offlineOkHttp(): OfflineHttp {
    val client: OkHttpClient = PlaybackMedia.createPlaybackHttpClient().newBuilder()
        // A conversion may say nothing for a long while before its first bytes.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    return OfflineHttp { url, headers, range, onCall ->
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            // A compressed body would make Content-Length and resume offsets lie.
            header("Accept-Encoding", "identity")
            if (range != null) header("Range", range)
        }.build()
        val call: Call = client.newCall(request)
        onCall { call.cancel() }
        val response = call.execute()
        OfflineHttpResponse(
            response.code,
            { name -> response.header(name) },
            response.body.byteStream(),
            response.body.contentLength().takeIf { it >= 0 }
        ) {
            response.close()
        }
    }
}
