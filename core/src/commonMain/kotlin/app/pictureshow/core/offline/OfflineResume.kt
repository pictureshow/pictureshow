/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlinx.serialization.Serializable

@Serializable
data class OfflineValidator(
    val lastModified: String? = null,
    val etag: String? = null,
    val sourceETag: String? = null,
    val totalBytes: Long? = null
)

/** The Range header to send, or null for a fresh full request. Usable: totalBytes known plus at least one of lastModified/etag. */
fun offlineRangeHeader(partialBytes: Long, validator: OfflineValidator?): String? {
    if (partialBytes <= 0 || validator?.totalBytes == null) return null
    if (validator.lastModified == null && validator.etag == null) return null
    return "bytes=$partialBytes-"
}

data class OfflineResponse(
    val status: Int,
    val contentRange: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentLength: Long? = null,
    val acceptRanges: String? = null,
    val sourceETag: String? = null
)

/** action: "append" | "restart" | "complete" | "fail". */
data class OfflineResumeDecision(
    val action: String,
    val offset: Long = 0,
    val totalBytes: Long? = null,
    val validator: OfflineValidator? = null,
    val reason: String? = null
)

private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")
private val UNSATISFIED_RANGE = Regex("""^bytes \*/(\d+)$""")

private data class ContentRange(val start: Long, val total: Long?)

private fun parseContentRange(value: String?): ContentRange? {
    val match = CONTENT_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val totalText = match.groupValues[3]
    val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
    if (end < start || (total != null && end >= total)) return null
    return ContentRange(start, total)
}

private fun differs(a: String?, b: String?) = a != null && b != null && a != b

private fun OfflineValidator.changedBy(response: OfflineResponse) =
    differs(lastModified, response.lastModified) || differs(etag, response.etag) || differs(sourceETag, response.sourceETag)

private fun OfflineResponse.validator(totalBytes: Long?) = OfflineValidator(lastModified, etag, sourceETag, totalBytes)

private fun restart(reason: String) = OfflineResumeDecision("restart", reason = reason)

/** What to do with a response to a full or ranged request. The server ignores If-Range, so validators are compared here. */
fun offlineResumeDecision(partialBytes: Long, stored: OfflineValidator?, response: OfflineResponse): OfflineResumeDecision =
    when (response.status) {
        200 -> if (partialBytes == 0L) {
            OfflineResumeDecision("append", 0, response.contentLength, response.validator(response.contentLength))
        } else {
            OfflineResumeDecision("restart", 0, response.contentLength, response.validator(response.contentLength), "no-range")
        }
        206 -> partialContent(partialBytes, stored, response)
        416 -> unsatisfiable(partialBytes, stored, response)
        401, 403 -> OfflineResumeDecision("fail", reason = "denied")
        404 -> OfflineResumeDecision("fail", reason = "gone")
        else -> OfflineResumeDecision("fail", reason = "server-${response.status}")
    }

private fun partialContent(partialBytes: Long, stored: OfflineValidator?, response: OfflineResponse): OfflineResumeDecision {
    val range = parseContentRange(response.contentRange) ?: return restart("invalid-range")
    if (range.start != partialBytes) return restart("range-offset")
    if (partialBytes == 0L) return OfflineResumeDecision("append", 0, range.total, response.validator(range.total))
    if (stored?.totalBytes == null || range.total != stored.totalBytes) return restart("range-total")
    if (stored.changedBy(response)) return restart("validator-changed")
    return OfflineResumeDecision("append", partialBytes, range.total, stored.merged(response))
}

private fun unsatisfiable(partialBytes: Long, stored: OfflineValidator?, response: OfflineResponse): OfflineResumeDecision {
    val total = UNSATISFIED_RANGE.matchEntire(response.contentRange?.trim() ?: "")?.groupValues?.get(1)?.toLongOrNull()
    if (total == null || total != partialBytes || stored?.totalBytes != total || stored.changedBy(response)) {
        return restart("range-unsatisfiable")
    }
    return OfflineResumeDecision("complete", partialBytes, total, stored.merged(response))
}

private fun OfflineValidator.merged(response: OfflineResponse) = copy(
    lastModified = response.lastModified ?: lastModified,
    etag = response.etag ?: etag,
    sourceETag = response.sourceETag ?: sourceETag
)

/** action: "complete" | "truncated" | "overrun" | "unknown-length-complete". */
data class OfflineBodyEnd(val action: String)

fun offlineBodyEnd(receivedBytes: Long, totalBytes: Long?, cleanEof: Boolean): OfflineBodyEnd = OfflineBodyEnd(
    when {
        totalBytes == null -> if (cleanEof) "unknown-length-complete" else "truncated"
        receivedBytes > totalBytes -> "overrun"
        receivedBytes == totalBytes -> "complete"
        else -> "truncated"
    }
)

private const val TICKS_PER_SECOND = 10_000_000L

/** A resumed body is trusted only up to the last boundary that was synced before anything was written past it. */
const val OFFLINE_SYNC_BYTES = 16L * 1024 * 1024

/**
 * A chunked conversion that dies at a fragment boundary ends as cleanly as a finished one, and its length and hash are of
 * the same short file, so running time is the only witness. Longer never fails. The allowance is the larger of 5 seconds and
 * 1% of the running time: a container's duration can exceed one track's by a little (measured 359.875 s against 360 s,
 * 0.035%), and a real cut loses far more than that.
 */
fun offlineConversionShort(expectedTicks: Long?, measuredTicks: Long?): Boolean {
    if (expectedTicks == null || measuredTicks == null || expectedTicks <= 0) return false
    return expectedTicks - measuredTicks > maxOf(5 * TICKS_PER_SECOND, expectedTicks / 100)
}

/**
 * Where to resume a partial after the process was gone, so a power cut may have torn its tail. The largest multiple of
 * OFFLINE_SYNC_BYTES strictly below the length: a file of exactly that length may not have reached its sync yet.
 */
fun offlineDurableOffset(partialBytes: Long): Long =
    if (partialBytes <= 0) 0 else (partialBytes - 1) / OFFLINE_SYNC_BYTES * OFFLINE_SYNC_BYTES

data class OfflineVerifyInput(
    val actualBytes: Long,
    val expectedBytes: Long?,
    val actualSha256: String,
    val expectedSha256: String? = null,
    val sourceETagBefore: String? = null,
    val sourceETagNow: String? = null,
    /** The item's running time, for a server conversion. Null: nothing to compare with. */
    val expectedRuntimeTicks: Long? = null,
    /** The running time the converted file's fragments add up to. Null for a file that isn't fragmented. */
    val measuredTicks: Long? = null
)

/** reason: "length" | "hash" | "short" | "source-changed". A null sourceETagNow (offline at verify) doesn't fail. */
data class OfflineVerifyResult(val ok: Boolean, val reason: String? = null)

fun offlineVerify(input: OfflineVerifyInput): OfflineVerifyResult = when {
    input.expectedBytes != null && input.expectedBytes != input.actualBytes -> OfflineVerifyResult(false, "length")
    input.expectedSha256 != null &&
        !input.expectedSha256.equals(
            input.actualSha256,
            ignoreCase = true
        ) -> OfflineVerifyResult(false, "hash")
    offlineConversionShort(input.expectedRuntimeTicks, input.measuredTicks) -> OfflineVerifyResult(false, "short")
    differs(input.sourceETagBefore, input.sourceETagNow) -> OfflineVerifyResult(false, "source-changed")
    else -> OfflineVerifyResult(true)
}
