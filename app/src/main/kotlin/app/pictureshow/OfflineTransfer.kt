/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES
import app.pictureshow.core.offline.OfflineResponse
import app.pictureshow.core.offline.OfflineValidator
import app.pictureshow.core.offline.offlineBodyEnd
import app.pictureshow.core.offline.offlineRangeHeader
import app.pictureshow.core.offline.offlineResumeDecision
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

/** One request at a time per resource; cancelling the coroutine cancels the call. */
internal fun interface OfflineHttp {
    fun open(url: String, headers: Map<String, String>, range: String?): OfflineHttpResponse
}

/** The byte ledger the transfer must clear with before every write. */
internal interface OfflineLedger {
    /** False: the reason is stored and the transfer stops before writing. */
    suspend fun reserve(bytes: Long): Boolean
    suspend fun commit(bytes: Long)
    suspend fun discard(bytes: Long)
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

internal class OfflineResourceTransfer(private val http: OfflineHttp, private val ledger: OfflineLedger) {
    /**
     * @param spaceFmp4 this is a server conversion: leave room for the index that makes it seekable.
     * @param sourceETag the source version seen before this attempt, compared against the stored one.
     */
    suspend fun run(
        target: File,
        url: String,
        headers: Map<String, String>,
        stored: OfflineValidator?,
        sourceETag: String?,
        spaceFmp4: Boolean,
        onValidator: suspend (OfflineValidator?) -> Unit = {}
    ): OfflineTransferResult = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        // A conversion can't resume; its partial is only a leftover. Its bytes leave the ledger before it starts over.
        var partial = target.length()
        if (spaceFmp4 && partial > 0) {
            ledger.discard(partial)
            target.delete()
            partial = 0
        }
        val range = if (spaceFmp4) null else offlineRangeHeader(partial, stored)
        if (range == null && partial > 0) {
            ledger.discard(partial)
            target.delete()
            partial = 0
        }
        val response = try {
            http.open(url, headers, range)
        } catch (e: IOException) {
            return@withContext OfflineTransferResult.Interrupted("network")
        }
        response.use {
            if (response.status == 401 || response.status == 403) return@withContext OfflineTransferResult.Failed("unauthorized")
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
        FileOutputStream(target, offset > 0).use { file ->
            val spacer = if (spaceFmp4) Fmp4Spacer(file) else null
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
                    if (spacer != null) spacer.write(buffer, read) else file.write(buffer, 0, read)
                    val written = read + (spacer?.inserted ?: 0L) - before
                    ledger.commit(written)
                    reserved -= written
                    received += read
                    ledger.progress(received, total)
                }
                file.fd.sync()
            } catch (e: IOException) {
                file.runCatching { fd.sync() }
                if (isDiskFull(e)) return OfflineTransferResult.NoBudget
                return OfflineTransferResult.Interrupted("network")
            }
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
    return OfflineHttp { url, headers, range ->
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            // A compressed body would make Content-Length and resume offsets lie.
            header("Accept-Encoding", "identity")
            if (range != null) header("Range", range)
        }.build()
        val call: Call = client.newCall(request)
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
